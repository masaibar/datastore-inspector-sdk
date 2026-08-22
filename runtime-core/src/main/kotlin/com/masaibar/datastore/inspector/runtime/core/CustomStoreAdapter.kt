package com.masaibar.datastore.inspector.runtime.core

import androidx.datastore.core.DataStore
import com.masaibar.datastore.inspector.protocol.CustomDocumentPayload
import com.masaibar.datastore.inspector.protocol.CustomDocumentValidation
import com.masaibar.datastore.inspector.protocol.CustomDocumentValidationException
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ReplaceCustomDocument
import com.masaibar.datastore.inspector.protocol.StoreBackend
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StoreSemantics
import com.masaibar.datastore.inspector.protocol.UnsupportedReason
import com.masaibar.datastore.inspector.protocol.WriteConsistency
import com.masaibar.datastore.inspector.protocol.WriteOperation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class CustomStoreAdapterFactory(
  private val executor: CustomInspectionExecutor = CustomInspectionExecutor()
) : StoreAdapterFactory,
  AutoCloseable {
  override val providerId: String = CUSTOM_STORE_ADAPTER_PROVIDER_ID

  @Suppress("UNCHECKED_CAST")
  override fun create(candidate: StoreCandidate): AdapterResolution {
    if (candidate.declaration.kindHint != StoreKind.CUSTOM) {
      return AdapterResolution.NotApplicable
    }
    val store =
      candidate.instance as? DataStore<*>
        ?: return AdapterResolution.Unsupported(
          unsupported(
            CustomStoreReasonCode.CUSTOM_CREATION_ROUTE_UNSUPPORTED
          )
        )
    val handle =
      CustomInspectionRegistry.handleForStore(store)
        ?: return AdapterResolution.Unsupported(
          unsupported(
            CustomStoreReasonCode.CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE
          )
        )
    return AdapterResolution.Resolved(
      CustomStoreAdapter(
        store = store as DataStore<Any?>,
        handle = handle as CustomInspectionHandle<Any?>,
        executor = executor
      )
    )
  }

  override fun close() {
    executor.close()
  }
}

internal class CustomStoreAdapter<T>(
  private val store: DataStore<T>,
  private val handle: CustomInspectionHandle<T>,
  private val executor: CustomInspectionExecutor
) : StoreAdapter {
  // 同一Storeの待機はglobal worker/queueと5秒のSerializer実行budgetを消費しない。
  private val singleFlight = Mutex()

  override val kind: StoreKind = StoreKind.CUSTOM
  override val capabilities: Set<StoreCapability> =
    setOf(
      StoreCapability(ProtocolCapabilities.SNAPSHOT_GET),
      StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_GET),
      StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE)
    )
  override val schema = null
  override val semantics: StoreSemantics =
    StoreSemantics(
      backend = StoreBackend.DATASTORE,
      storageScope = handle.storageScope,
      supportedValueTypes = emptySet(),
      writeConsistency = WriteConsistency.ATOMIC_TRANSACTIONAL
    )
  override val requiredCapabilities: Set<String> =
    setOf(
      ProtocolCapabilities.CUSTOM_DOCUMENT_GET
    )
  override val runtimeUnsupportedReason: UnsupportedReason?
    get() = handle.reportedUnsupportedReason()?.let(::unsupported)

  override suspend fun snapshot(): AdapterSnapshot =
    singleFlight.withLock {
      try {
        executor
          .execute(handle) {
            resolveSnapshot()
          }.also { handle.clearProjectionFailure() }
      } catch (failure: CustomInspectionFailure) {
        failure.rethrowInspectionControlFlow()
        if (!failure.transient) {
          handle.markProjectionFailure(failure.reason)
        }
        throw StoreSnapshotUnsupportedException(unsupported(failure.reason))
      }
    }

  override suspend fun write(
    expectedFingerprint: String,
    operation: WriteOperation
  ): AdapterWriteResult {
    val replace =
      operation as? ReplaceCustomDocument
        ?: throw StoreAdapterException(
          ProtocolErrorCode.CUSTOM_DOCUMENT_INVALID,
          operationStarted = false
        )
    try {
      CustomDocumentValidation.validate(replace.format, replace.document)
    } catch (error: CustomDocumentValidationException) {
      error.rethrowInspectionControlFlow()
      throw StoreAdapterException(
        ProtocolErrorCode.CUSTOM_DOCUMENT_INVALID,
        operationStarted = false
      )
    }
    return singleFlight.withLock {
      try {
        executor.execute(handle) {
          replace(expectedFingerprint, replace)
        }
      } catch (failure: CustomInspectionFailure) {
        failure.rethrowInspectionControlFlow()
        if (failure.reason == CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH) {
          handle.abortInspection(failure.reason)
        }
        val code =
          when (failure.reason) {
            CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT ->
              ProtocolErrorCode.CUSTOM_OPERATION_TIMEOUT
            CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH ->
              ProtocolErrorCode.CUSTOM_ACTUAL_WRITE_MISMATCH
            else -> ProtocolErrorCode.CUSTOM_PROJECTION_MISMATCH
          }
        throw StoreAdapterException(
          code,
          retryable = false,
          operationStarted = failure.operationStarted
        )
      } catch (error: CustomActualWriteException) {
        error.rethrowInspectionControlFlow()
        handle.abortInspection(CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH)
        throw StoreAdapterException(
          ProtocolErrorCode.CUSTOM_ACTUAL_WRITE_MISMATCH,
          operationStarted = false
        )
      }
    }
  }

  private suspend fun resolveSnapshot(): AdapterSnapshot {
    val current = store.data.first()
    val projected = CustomProjectionResolver(handle).resolve(current)
    return projected.toSnapshot()
  }

  private suspend fun replace(
    expectedFingerprint: String,
    operation: ReplaceCustomDocument
  ): AdapterWriteResult {
    val resolver = CustomProjectionResolver(handle)
    var conflict: ProjectedDocument<T>? = null
    var applied: ProjectedDocument<T>? = null
    var intended: ProjectedDocument<T>? = null
    var expectation: MutationExpectation<T>? = null
    val mutationToken = Any()
    try {
      withContext(InspectorMutationContext(mutationToken)) {
        store.updateData { current ->
          val currentProjected = resolver.resolve(current)
          if (currentProjected.fingerprint != expectedFingerprint) {
            conflict = currentProjected
            return@updateData current
          }
          val projection = currentProjected.projection
          if (
            projection.projectionId != operation.projectionId ||
            projection.schemaVersion != operation.schemaVersion ||
            projection.format != operation.format
          ) {
            throw CustomInspectionFailure(
              CustomStoreReasonCode.CUSTOM_DOCUMENT_ROUND_TRIP_MISMATCH
            )
          }
          if (operation.document == currentProjected.document) {
            applied = currentProjected
            return@updateData current
          }
          val candidate =
            try {
              projection.decode(operation.document)
            } catch (error: Throwable) {
              error.rethrowInspectionControlFlow()
              throw CustomInspectionFailure(
                CustomStoreReasonCode.CUSTOM_DOCUMENT_ROUND_TRIP_MISMATCH
              )
            }
          if (
            current == null ||
            candidate == null ||
            current.javaClass != candidate.javaClass
          ) {
            throw CustomInspectionFailure(
              CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
            )
          }
          if (candidate === current || candidate == current) {
            throw CustomInspectionFailure(
              CustomStoreReasonCode.CUSTOM_VALUE_EQUALITY_TOO_COARSE
            )
          }
          val roundTrip =
            try {
              projection.encode(candidate)
            } catch (error: Throwable) {
              error.rethrowInspectionControlFlow()
              throw CustomInspectionFailure(
                CustomStoreReasonCode.CUSTOM_DOCUMENT_ROUND_TRIP_MISMATCH
              )
            }
          if (roundTrip != operation.document) {
            throw CustomInspectionFailure(
              CustomStoreReasonCode.CUSTOM_DOCUMENT_ROUND_TRIP_MISMATCH
            )
          }
          val persistenceBytes = handle.encodeForInspection(candidate)
          val persisted = handle.decodeForInspection(persistenceBytes)
          if (!sameValue(candidate, persisted)) {
            throw CustomInspectionFailure(
              CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
            )
          }
          expectation?.let(handle::endMutation)
          val active: MutationExpectation<T> =
            MutationExpectation(
              token = mutationToken,
              candidate = candidate,
              verifyProjection = { actualBytes, actualValue ->
                val actualDocument =
                  projection.documentFromPersistence(actualBytes)
                    ?: projection.encode(actualValue)
                actualDocument == operation.document
              }
            )
          expectation = active
          intended = ProjectedDocument(projection, operation.document)
          handle.beginMutation(active)
          candidate
        }
      }
    } finally {
      expectation?.let(handle::endMutation)
    }
    conflict?.let { current ->
      return AdapterWriteResult.Conflict(current.toSnapshot())
    }
    expectation?.let { active ->
      if (!active.verified.get()) {
        throw CustomInspectionFailure(
          CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH
        )
      }
    }
    val result = applied ?: requireNotNull(intended)
    return AdapterWriteResult.Applied(result.toSnapshot())
  }

  private fun ProjectedDocument<T>.toSnapshot(): AdapterSnapshot =
    AdapterSnapshot(
      fingerprint = fingerprint,
      payload =
        CustomDocumentPayload(
          projectionId = projection.projectionId,
          schemaVersion = projection.schemaVersion,
          format = projection.format,
          document = document
        )
    )
}

private fun unsupported(reason: CustomStoreReasonCode): UnsupportedReason =
  UnsupportedReason(
    reason,
    "Custom DataStoreを安全に投影できません。",
    reason == CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
  )

internal const val CUSTOM_STORE_ADAPTER_PROVIDER_ID: String = "custom-projection-v1"
