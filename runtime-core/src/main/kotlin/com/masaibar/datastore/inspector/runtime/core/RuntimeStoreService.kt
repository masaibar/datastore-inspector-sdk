package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.ClearPreferences
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import com.masaibar.datastore.inspector.protocol.GetSchemaResult
import com.masaibar.datastore.inspector.protocol.GetSnapshotResult
import com.masaibar.datastore.inspector.protocol.MutatePreferences
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.ReplaceCustomDocument
import com.masaibar.datastore.inspector.protocol.ReplacePreferences
import com.masaibar.datastore.inspector.protocol.ReplaceProtoBytes
import com.masaibar.datastore.inspector.protocol.ResetStore
import com.masaibar.datastore.inspector.protocol.ResolvedSnapshotResult
import com.masaibar.datastore.inspector.protocol.ResolvedStoreSnapshot
import com.masaibar.datastore.inspector.protocol.StoreBackend
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreDescriptor
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StoreStatus
import com.masaibar.datastore.inspector.protocol.UnsupportedReason
import com.masaibar.datastore.inspector.protocol.UnsupportedSnapshotInfo
import com.masaibar.datastore.inspector.protocol.WriteAppliedSnapshotUnavailable
import com.masaibar.datastore.inspector.protocol.WriteConflict
import com.masaibar.datastore.inspector.protocol.WriteConsistency
import com.masaibar.datastore.inspector.protocol.WriteOutcomeUnknown
import com.masaibar.datastore.inspector.protocol.WritePayload
import com.masaibar.datastore.inspector.protocol.WriteResult
import com.masaibar.datastore.inspector.protocol.WriteSuccess

public sealed class RuntimeStoreException(
  message: String
) : IllegalStateException(message) {
  public class NotFound : RuntimeStoreException("Storeが見つかりません。")

  public class NotReady : RuntimeStoreException("Storeはまだ解決されていません。")

  public class Unsupported : RuntimeStoreException("Storeは未対応です。")

  public class Failed(
    public val protocolCode: ProtocolErrorCode = ProtocolErrorCode.STORE_ERROR,
    public val retryable: Boolean = false,
    public val operationStarted: Boolean? = null
  ) : RuntimeStoreException("Storeの処理に失敗しました。")

  public class Stale : RuntimeStoreException("snapshot tokenが無効または使用済みです。")

  public class SchemaNotFound : RuntimeStoreException("Schemaが見つかりません。")

  public class Capability : RuntimeStoreException("交渉されていないcapabilityです。")
}

internal data class RuntimeObservableStore(
  val descriptor: StoreDescriptor,
  val generation: Long,
  val adapter: StoreAdapter
)

public class RuntimeStoreService(
  private val registry: DataStoreRegistry,
  private val leases: SnapshotLeaseCache = SnapshotLeaseCache(),
  private val processName: String = "unknown",
  private val catalog: DynamicStoreCatalog = DynamicStoreCatalog(emptyList(), leases)
) : AutoCloseable {
  private val writeCorrelations = WriteCorrelationTracker()

  public fun list(): List<StoreDescriptor> = list(TEST_CONTEXT)

  public fun list(context: RuntimeConnectionContext): List<StoreDescriptor> {
    requireCapability(context, ProtocolCapabilities.STORES_LIST)
    val registryEntries = registry.entries()
    val catalogEntries =
      try {
        catalog.refresh(context, processName, registryEntries)
      } catch (error: StoreCatalogException) {
        error.rethrowInspectionControlFlow()
        throw RuntimeStoreException.Failed(error.code, error.retryable)
      }
    return registryEntries
      .filter { entry -> staticEntryVisible(entry, context) }
      .map { entry -> descriptor(entry, context) } +
      catalogEntries.map { entry -> descriptor(entry, context) }
  }

  public suspend fun snapshot(storeId: String): GetSnapshotResult = snapshot(storeId, TEST_CONTEXT)

  public suspend fun snapshot(
    storeId: String,
    context: RuntimeConnectionContext
  ): GetSnapshotResult {
    requireCapability(context, ProtocolCapabilities.SNAPSHOT_GET)
    val handle = find(storeId, context)
    if (
      handle is StoreHandle.Static &&
      shouldDowngradeCustomEntry(handle.entry, context)
    ) {
      return UnsupportedSnapshotInfo(storeId, CUSTOM_DOCUMENT_UNAVAILABLE_REASON)
    }
    return when (handle) {
      is StoreHandle.Static ->
        when (val state = handle.entry.state) {
          RegistryState.Declared -> throw RuntimeStoreException.NotReady()
          is RegistryState.Resolved -> {
            requireAdapterCapabilities(state.adapter, context)
            snapshot(handle, state.adapter)
          }
          is RegistryState.Unsupported -> UnsupportedSnapshotInfo(storeId, state.reason)
          is RegistryState.Error -> throw RuntimeStoreException.Failed()
        }
      is StoreHandle.Dynamic -> snapshot(handle, handle.record.adapter())
    }
  }

  public suspend fun write(payload: WritePayload): WriteResult = write(payload, TEST_CONTEXT)

  public suspend fun write(
    payload: WritePayload,
    context: RuntimeConnectionContext
  ): WriteResult {
    val handle = find(payload.storeId, context)
    if (
      handle is StoreHandle.Static &&
      shouldDowngradeCustomEntry(handle.entry, context)
    ) {
      throw RuntimeStoreException.Unsupported()
    }
    val adapter = resolvedAdapter(handle)
    validateWriteCapability(adapter, payload, context)
    if (
      adapter.semantics.backend == StoreBackend.UNKNOWN ||
      adapter.semantics.writeConsistency == WriteConsistency.UNKNOWN
    ) {
      throw RuntimeStoreException.Unsupported()
    }
    val expectedFingerprint =
      leases.claim(
        payload.storeId,
        payload.expectedRevision,
        payload.expectedContentToken,
        handle.generation
      ) ?: throw RuntimeStoreException.Stale()
    val correlation = writeCorrelations.begin(payload.storeId, payload.correlationId)
    var correlatedFingerprint: String? = null
    try {
      val result =
        try {
          adapter.write(expectedFingerprint, payload.operation)
        } catch (error: StoreSnapshotUnsupportedException) {
          error.rethrowInspectionControlFlow()
          throw RuntimeStoreException.Unsupported()
        } catch (error: StoreAdapterException) {
          error.rethrowInspectionControlFlow()
          throw RuntimeStoreException.Failed(
            error.code,
            error.retryable,
            error.operationStarted
          )
        }
      return when (result) {
        is AdapterWriteResult.Applied -> {
          correlatedFingerprint = result.snapshot.fingerprint
          WriteSuccess(wrap(payload.storeId, handle.generation, result.snapshot))
        }
        is AdapterWriteResult.Conflict ->
          WriteConflict(wrap(payload.storeId, handle.generation, result.snapshot))
        AdapterWriteResult.AppliedSnapshotUnavailable ->
          WriteAppliedSnapshotUnavailable(payload.storeId)
        is AdapterWriteResult.OutcomeUnknown -> {
          correlatedFingerprint = result.currentSnapshot?.fingerprint
          WriteOutcomeUnknown(
            reason = result.reason,
            currentSnapshot =
              result.currentSnapshot?.let { snapshot ->
                wrap(payload.storeId, handle.generation, snapshot)
              }
          )
        }
      }
    } finally {
      writeCorrelations.complete(correlation, correlatedFingerprint)
    }
  }

  public fun schema(schemaId: String): GetSchemaResult = schema(schemaId, TEST_CONTEXT)

  public fun schema(
    schemaId: String,
    context: RuntimeConnectionContext
  ): GetSchemaResult {
    requireCapability(context, ProtocolCapabilities.SCHEMA_GET)
    registry.entries().forEach { entry ->
      val adapter = (entry.state as? RegistryState.Resolved)?.adapter ?: return@forEach
      if (!context.capabilities.containsAll(adapter.requiredCapabilities)) return@forEach
      adapter.schema(schemaId)?.let { return it }
    }
    catalog.entries(context).forEach { record ->
      record.adapter().schema(schemaId)?.let { return it }
    }
    throw RuntimeStoreException.SchemaNotFound()
  }

  public fun isBestEffortMutation(
    payload: WritePayload,
    context: RuntimeConnectionContext
  ): Boolean =
    ordinaryFailureOrNull {
      val handle = find(payload.storeId, context)
      val semantics =
        when (handle) {
          is StoreHandle.Static ->
            (handle.entry.state as? RegistryState.Resolved)?.adapter?.semantics
          is StoreHandle.Dynamic -> handle.record.descriptor().semantics
        }
      semantics?.writeConsistency == WriteConsistency.BEST_EFFORT_NON_ATOMIC
    } ?: false

  override fun close() {
    catalog.close()
    leases.clear()
    writeCorrelations.close()
  }

  internal fun observationTargets(
    context: RuntimeConnectionContext
  ): List<RuntimeObservableStore> {
    requireCapability(context, ProtocolCapabilities.STORE_CHANGES)
    val descriptors = list(context).associateBy(StoreDescriptor::id)
    return descriptors.values.mapNotNull { descriptor ->
      if (
        descriptor.status != StoreStatus.RESOLVED ||
        descriptor.logicalId == null ||
        descriptor.capabilities.none { capability ->
          capability.id == ProtocolCapabilities.STORE_CHANGES
        }
      ) {
        return@mapNotNull null
      }
      try {
        val handle = find(descriptor.id, context)
        RuntimeObservableStore(
          descriptor = descriptor,
          generation = handle.generation,
          adapter = resolvedAdapter(handle)
        )
      } catch (_: RuntimeStoreException) {
        // list後のStore lifecycle raceです。次のreconcileで境界として処理します。
        null
      }
    }
  }

  internal suspend fun claimWriteCorrelation(
    storeId: String,
    fingerprint: String
  ): String? = writeCorrelations.claim(storeId, fingerprint)

  private suspend fun snapshot(
    handle: StoreHandle,
    adapter: StoreAdapter
  ): GetSnapshotResult =
    try {
      ResolvedSnapshotResult(
        wrap(handle.storeId, handle.generation, adapter.snapshot())
      )
    } catch (error: StoreSnapshotUnsupportedException) {
      error.rethrowInspectionControlFlow()
      UnsupportedSnapshotInfo(handle.storeId, error.reason)
    } catch (error: StoreAdapterException) {
      error.rethrowInspectionControlFlow()
      throw RuntimeStoreException.Failed(
        error.code,
        error.retryable,
        error.operationStarted
      )
    }

  private fun resolvedAdapter(handle: StoreHandle): StoreAdapter =
    when (handle) {
      is StoreHandle.Static ->
        when (val state = handle.entry.state) {
          RegistryState.Declared -> throw RuntimeStoreException.NotReady()
          is RegistryState.Unsupported -> throw RuntimeStoreException.Unsupported()
          is RegistryState.Error -> throw RuntimeStoreException.Failed()
          is RegistryState.Resolved ->
            state.adapter.also { adapter ->
              if (adapter.runtimeUnsupportedReason != null) {
                throw RuntimeStoreException.Unsupported()
              }
            }
        }
      is StoreHandle.Dynamic -> handle.record.adapter()
    }

  private fun find(
    storeId: String,
    context: RuntimeConnectionContext
  ): StoreHandle {
    catalog.find(storeId, context)?.let { return StoreHandle.Dynamic(it) }
    val entry = registry.find(storeId) ?: throw RuntimeStoreException.NotFound()
    if (!staticEntryVisible(entry, context)) throw RuntimeStoreException.NotFound()
    return StoreHandle.Static(entry)
  }

  private fun staticEntryVisible(
    entry: RegistryEntry,
    context: RuntimeConnectionContext
  ): Boolean {
    if (shouldDowngradeCustomEntry(entry, context)) return true
    val adapter = (entry.state as? RegistryState.Resolved)?.adapter
    return adapter == null ||
      context.capabilities.containsAll(adapter.requiredCapabilities)
  }

  private fun shouldDowngradeCustomEntry(
    entry: RegistryEntry,
    context: RuntimeConnectionContext
  ): Boolean =
    isStaticCustomEntry(entry) &&
      ProtocolCapabilities.CUSTOM_DOCUMENT_GET !in context.capabilities

  private fun requireAdapterCapabilities(
    adapter: StoreAdapter,
    context: RuntimeConnectionContext
  ) {
    if (!context.capabilities.containsAll(adapter.requiredCapabilities)) {
      throw RuntimeStoreException.NotFound()
    }
  }

  private fun validateWriteCapability(
    adapter: StoreAdapter,
    payload: WritePayload,
    context: RuntimeConnectionContext
  ) {
    if (adapter.runtimeUnsupportedReason != null) {
      throw RuntimeStoreException.Unsupported()
    }
    if (!context.capabilities.containsAll(adapter.requiredCapabilities)) {
      throw RuntimeStoreException.NotFound()
    }
    val required =
      when (payload.operation) {
        is MutatePreferences -> setOf(ProtocolCapabilities.PREFERENCES_WRITE)
        is ReplacePreferences -> setOf(ProtocolCapabilities.PREFERENCES_REPLACE)
        ClearPreferences ->
          setOf(
            ProtocolCapabilities.PREFERENCES_WRITE,
            ProtocolCapabilities.STORE_RESET
          )
        is ReplaceProtoBytes -> setOf(ProtocolCapabilities.PROTO_REPLACE)
        is ReplaceCustomDocument ->
          setOf(ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE)
        ResetStore -> setOf(ProtocolCapabilities.STORE_RESET)
      }
    val storeCapabilities = adapter.capabilities.map(StoreCapability::id).toSet()
    if (
      !context.capabilities.containsAll(required) ||
      !storeCapabilities.containsAll(required)
    ) {
      throw RuntimeStoreException.Capability()
    }
  }

  private fun requireCapability(
    context: RuntimeConnectionContext,
    capability: String
  ) {
    if (capability !in context.capabilities) throw RuntimeStoreException.Capability()
  }

  private fun wrap(
    storeId: String,
    generation: Long,
    snapshot: AdapterSnapshot
  ): ResolvedStoreSnapshot {
    val lease = leases.issue(storeId, snapshot.fingerprint, generation)
    return ResolvedStoreSnapshot(
      storeId = storeId,
      revision = lease.revision,
      contentToken = lease.token,
      payload = snapshot.payload,
      storeGeneration = generation
    )
  }

  private fun descriptor(
    entry: RegistryEntry,
    context: RuntimeConnectionContext
  ): StoreDescriptor {
    if (shouldDowngradeCustomEntry(entry, context)) {
      return StoreDescriptor(
        id = entry.storeId,
        name = entry.declaration.name,
        fileName = entry.declaration.fileName,
        kind = StoreKind.CUSTOM,
        status = StoreStatus.UNSUPPORTED,
        capabilities = emptySet(),
        schema = null,
        unsupportedReason = CUSTOM_DOCUMENT_UNAVAILABLE_REASON,
        semantics = defaultStoreSemantics(StoreKind.CUSTOM),
        logicalId = logicalStoreId(entry),
        generation = 0
      )
    }
    val state = entry.state
    val adapter = (state as? RegistryState.Resolved)?.adapter
    val runtimeUnsupportedReason = adapter?.runtimeUnsupportedReason
    return StoreDescriptor(
      id = entry.storeId,
      name = entry.declaration.name,
      fileName = entry.declaration.fileName,
      kind = adapter?.kind ?: entry.declaration.kindHint,
      status =
        when (state) {
          RegistryState.Declared -> StoreStatus.DECLARED
          is RegistryState.Resolved ->
            if (runtimeUnsupportedReason == null) {
              StoreStatus.RESOLVED
            } else {
              StoreStatus.UNSUPPORTED
            }
          is RegistryState.Unsupported -> StoreStatus.UNSUPPORTED
          is RegistryState.Error -> StoreStatus.ERROR
        },
      capabilities =
        if (runtimeUnsupportedReason == null) {
          negotiatedCapabilities(adapter?.capabilities.orEmpty(), context)
        } else {
          emptySet()
        },
      schema = adapter?.schema,
      unsupportedReason =
        when (state) {
          is RegistryState.Resolved -> runtimeUnsupportedReason
          is RegistryState.Unsupported -> state.reason
          is RegistryState.Error ->
            UnsupportedReason("ADAPTER_ERROR", state.safeMessage, false)
          else -> null
        },
      semantics = adapter?.semantics ?: defaultStoreSemantics(entry.declaration.kindHint),
      logicalId = logicalStoreId(entry),
      generation = 0
    )
  }

  private fun isStaticCustomEntry(entry: RegistryEntry): Boolean {
    val adapter = (entry.state as? RegistryState.Resolved)?.adapter
    return adapter?.kind == StoreKind.CUSTOM ||
      entry.declaration.kindHint == StoreKind.CUSTOM
  }

  private fun descriptor(
    entry: CatalogRecord,
    context: RuntimeConnectionContext
  ): StoreDescriptor {
    val descriptor = entry.descriptor()
    return descriptor.copy(
      capabilities = negotiatedCapabilities(descriptor.capabilities, context),
      logicalId = logicalStoreId(entry.identity)
    )
  }

  private fun logicalStoreId(entry: RegistryEntry): String =
    opaqueLogicalStoreId(
      listOf(
        "static",
        processName,
        entry.declaration.declarationId
      )
    )

  private fun logicalStoreId(identity: StoreSemanticIdentity): String =
    opaqueLogicalStoreId(
      listOf(
        "catalog",
        identity.backend.wireName,
        identity.storageScope.wireName,
        identity.processName,
        identity.logicalName
      )
    )

  private fun opaqueLogicalStoreId(parts: List<String>): String =
    "store:" + sha256(parts.joinToString("\u0000").encodeToByteArray())

  private fun negotiatedCapabilities(
    capabilities: Set<StoreCapability>,
    context: RuntimeConnectionContext
  ): Set<StoreCapability> =
    capabilities.filterTo(linkedSetOf()) { capability ->
      capability.id in context.capabilities
    }

  private sealed interface StoreHandle {
    val storeId: String
    val generation: Long

    data class Static(
      val entry: RegistryEntry
    ) : StoreHandle {
      override val storeId: String = entry.storeId
      override val generation: Long = 0
    }

    data class Dynamic(
      val record: CatalogRecord
    ) : StoreHandle {
      override val storeId: String = record.storeId
      override val generation: Long = record.generation
    }
  }

  private companion object {
    val CUSTOM_DOCUMENT_UNAVAILABLE_REASON =
      UnsupportedReason(
        CustomStoreReasonCode.UNKNOWN,
        "Custom DataStoreはこのProtocol接続では利用できません。",
        false
      )

    val TEST_CONTEXT =
      RuntimeConnectionContext(
        version = ProtocolVersion.CURRENT,
        capabilities = ProtocolCapabilities.INITIAL,
        sessionId = "test"
      )
  }
}
