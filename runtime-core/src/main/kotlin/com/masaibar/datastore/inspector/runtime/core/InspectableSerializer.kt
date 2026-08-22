package com.masaibar.datastore.inspector.runtime.core

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import androidx.datastore.core.okio.OkioSerializer
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

internal object CustomInspectionLimits {
  const val MAX_PERSISTENCE_BYTES: Int = 8 * 1024 * 1024
  const val PROBE_TIMEOUT_MILLIS: Long = 5_000
  const val WORKER_COUNT: Int = 2
  const val QUEUE_CAPACITY: Int = 64
}

internal class CustomInspectionFailure(
  val reason: CustomStoreReasonCode,
  val operationStarted: Boolean = false,
  val transient: Boolean = false
) : IllegalStateException("Custom DataStore inspection failed.")

internal class CustomActualWriteException : IOException("Custom DataStore write postcondition failed.")

internal class InspectorMutationContext(
  val token: Any
) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<InspectorMutationContext>
}

internal data class MutationExpectation<T>(
  val token: Any,
  val candidate: T,
  val verifyProjection: suspend (ByteArray, T) -> Boolean,
  val verified: AtomicBoolean = AtomicBoolean(false)
)

internal data class SerializerInspection<T, S : Any>(
  val original: S,
  val effective: S,
  val defaultValue: T,
  val handle: CustomInspectionHandle<T>?
)

internal fun <T> selectSerializerForInspection(
  original: Serializer<T>,
  storageScope: com.masaibar.datastore.inspector.protocol.StorageScope
): SerializerInspection<T, Serializer<T>> {
  val defaultValue = original.defaultValue
  if (isMessageLiteValue(defaultValue)) {
    return SerializerInspection(
      original = original,
      effective = original,
      defaultValue = defaultValue,
      handle = null
    )
  }
  val (handle, effective) =
    CustomInspectionHandle.forSerializer(
      original = original,
      storageScope = storageScope,
      defaultValue = defaultValue
    )
  return SerializerInspection(
    original = original,
    effective = effective,
    defaultValue = defaultValue,
    handle = handle
  )
}

internal fun <T> selectSerializerForInspection(
  original: OkioSerializer<T>,
  storageScope: com.masaibar.datastore.inspector.protocol.StorageScope
): SerializerInspection<T, OkioSerializer<T>> {
  val defaultValue = original.defaultValue
  if (isMessageLiteValue(defaultValue)) {
    return SerializerInspection(
      original = original,
      effective = original,
      defaultValue = defaultValue,
      handle = null
    )
  }
  val (handle, effective) =
    CustomInspectionHandle.forOkioSerializer(
      original = original,
      storageScope = storageScope,
      defaultValue = defaultValue
    )
  return SerializerInspection(
    original = original,
    effective = effective,
    defaultValue = defaultValue,
    handle = handle
  )
}

internal fun isMessageLiteValue(value: Any?): Boolean {
  if (value == null) return false
  val type =
    ordinaryFailureOrNull {
      Class.forName(
        "com.google.protobuf.MessageLite",
        false,
        value.javaClass.classLoader
      )
    } ?: return false
  return type.isInstance(value)
}

/**
 * 実Serializer、同一DataStore、capture結果、mutation postconditionをStore単位で所有します。
 */
internal class CustomInspectionHandle<T>(
  val originalSerializer: Any,
  val defaultValue: T,
  val storageScope: com.masaibar.datastore.inspector.protocol.StorageScope,
  private val encodeUnprotected: suspend (T) -> ByteArray,
  private val decodeUnprotected: suspend (ByteArray) -> T
) : AutoCloseable {
  private val guard = OriginalSerializerGuards.guardFor(originalSerializer)
  private val contractsLock = Any()
  private val capturedContracts = mutableListOf<StructuredContract<T>>()
  private var contractGeneration = 0L
  private var cachedProjection: CachedProjection<T>? = null
  private val activeMutation = AtomicReference<MutationExpectation<T>?>(null)
  private val operationInFlight = AtomicBoolean(false)
  private val quarantined = AtomicReference<CustomStoreReasonCode?>(null)
  private val projectionFailure = AtomicReference<CustomStoreReasonCode?>(null)
  private val closed = AtomicBoolean(false)

  @Volatile
  private var store: DataStore<T>? = null

  fun attachStore(instance: DataStore<T>) {
    check(!closed.get()) { "inspection handleはclose済みです。" }
    val existing = store
    check(existing == null || existing === instance) {
      "inspection handleを別DataStoreへ再利用できません。"
    }
    store = instance
  }

  fun dataStore(): DataStore<T> =
    store ?: throw CustomInspectionFailure(
      CustomStoreReasonCode.CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE
    )

  fun quarantine(reason: CustomStoreReasonCode) {
    quarantined.compareAndSet(null, reason)
  }

  fun abortInspection(reason: CustomStoreReasonCode): Boolean {
    quarantine(reason)
    if (reason == CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT) {
      guard.poison(reason)
    }
    activeMutation.set(null)
    return operationInFlight.getAndSet(false)
  }

  fun unavailableReason(): CustomStoreReasonCode? =
    if (closed.get()) {
      CustomStoreReasonCode.CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE
    } else {
      quarantined.get() ?: guard.poisonedReason()
    }

  fun reportedUnsupportedReason(): CustomStoreReasonCode? = unavailableReason() ?: projectionFailure.get()

  fun markProjectionFailure(reason: CustomStoreReasonCode) {
    projectionFailure.set(reason)
  }

  fun clearProjectionFailure() {
    projectionFailure.set(null)
  }

  fun finishInspectionOperation(): Boolean = operationInFlight.getAndSet(false)

  fun requireAvailable() {
    unavailableReason()?.let { reason -> throw CustomInspectionFailure(reason) }
  }

  suspend fun encodeForInspection(value: T): ByteArray {
    requireAvailable()
    return guard.withLock {
      requireAvailable()
      val (bytes, contracts) = captureEncode(value)
      recordContracts(contracts)
      bytes
    }
  }

  suspend fun decodeForInspection(bytes: ByteArray): T {
    requireAvailable()
    return guard.withLock {
      requireAvailable()
      val (value, contracts) =
        StructuredSerializationCapture.captureRead {
          decodeUnprotected(bytes)
        }
      recordContracts(contracts)
      value
    }
  }

  suspend fun delegateRead(block: suspend () -> T): T =
    guard.withLock {
      val (value, contracts) = StructuredSerializationCapture.captureRead(block)
      recordContracts(contracts)
      value
    }

  suspend fun delegateWrite(
    value: T,
    directWrite: suspend () -> Unit,
    bufferedWrite: suspend () -> ByteArray,
    commitBuffered: suspend (ByteArray) -> Unit
  ) {
    guard.withLock {
      val expectation = activeMutation.get()
      val contextToken = currentCoroutineContext()[InspectorMutationContext]?.token
      val tokenMatches = expectation != null && contextToken === expectation.token
      val candidateMatches = expectation != null && value === expectation.candidate
      if (contextToken != null || candidateMatches) {
        if (!tokenMatches || !candidateMatches) throw CustomActualWriteException()
        val active = requireNotNull(expectation)
        val (bytes, contracts) =
          actualWritePostcondition {
            bufferedWriteWithCapture(value, bufferedWrite)
          }
        actualWritePostcondition {
          recordContracts(contracts)
        }
        val (decoded, decodedContracts) =
          actualWritePostcondition {
            StructuredSerializationCapture.captureRead {
              decodeUnprotected(bytes)
            }
          }
        actualWritePostcondition {
          recordContracts(decodedContracts)
        }
        val postconditionSatisfied =
          actualWritePostcondition {
            sameValue(value, decoded) &&
              active.verifyProjection(bytes, decoded)
          }
        if (!postconditionSatisfied) {
          throw CustomActualWriteException()
        }
        if (
          activeMutation.get() !== active ||
          unavailableReason() != null
        ) {
          throw CustomActualWriteException()
        }
        commitBuffered(bytes)
        active.verified.set(true)
      } else {
        val contracts =
          StructuredSerializationCapture.captureWrite(value) {
            directWrite()
          }
        recordContracts(contracts)
      }
    }
  }

  /**
   * delegateWrite内でbyte列とstructured captureを1回のoriginal callから取得します。
   */
  private suspend fun bufferedWriteWithCapture(
    value: T,
    bufferedWrite: suspend () -> ByteArray
  ): Pair<ByteArray, List<StructuredContract<T>>> {
    lateinit var bytes: ByteArray
    val contracts =
      StructuredSerializationCapture.captureWrite(value) {
        bytes = bufferedWrite()
      }
    return bytes to contracts
  }

  private suspend fun <R> actualWritePostcondition(block: suspend () -> R): R =
    try {
      block()
    } catch (error: Throwable) {
      error.rethrowInspectionControlFlow()
      if (error is CustomActualWriteException) throw error
      throw CustomActualWriteException()
    }

  fun beginMutation(expectation: MutationExpectation<T>) {
    requireAvailable()
    check(activeMutation.compareAndSet(null, expectation)) {
      "Custom DataStore mutationはsingle-flightである必要があります。"
    }
    try {
      requireAvailable()
    } catch (failure: CustomInspectionFailure) {
      activeMutation.compareAndSet(expectation, null)
      failure.rethrowInspectionControlFlow()
      throw failure
    }
    operationInFlight.set(true)
  }

  fun endMutation(expectation: MutationExpectation<T>) {
    activeMutation.compareAndSet(expectation, null)
  }

  fun structuredContracts(): List<StructuredContract<T>> = synchronized(contractsLock) { capturedContracts.toList() }

  fun cachedProjectionFor(value: T): CustomProjection<T>? =
    synchronized(contractsLock) {
      cachedProjection
        ?.takeIf { cached ->
          cached.runtimeClass === value?.javaClass &&
            cached.contractGeneration == contractGeneration
        }?.projection
    }

  fun cacheProjection(
    value: T,
    projection: CustomProjection<T>
  ) {
    synchronized(contractsLock) {
      cachedProjection =
        CachedProjection(
          runtimeClass = value?.javaClass,
          contractGeneration = contractGeneration,
          projection = projection
        )
    }
  }

  fun invalidateProjectionCache() {
    synchronized(contractsLock) { cachedProjection = null }
  }

  private suspend fun captureEncode(value: T): Pair<ByteArray, List<StructuredContract<T>>> =
    bufferedWriteWithCapture(value) {
      encodeUnprotected(value)
    }

  private fun recordContracts(contracts: List<StructuredContract<T>>) {
    if (contracts.isEmpty()) return
    synchronized(contractsLock) {
      var added = false
      contracts.forEach { candidate ->
        if (
          capturedContracts.size < MAX_CAPTURED_ROOT_CONTRACTS &&
          capturedContracts.none { existing ->
            existing.serializer === candidate.serializer &&
              existing.module === candidate.module
          }
        ) {
          capturedContracts += candidate
          added = true
        }
      }
      if (added) {
        contractGeneration += 1
        cachedProjection = null
      }
    }
  }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    activeMutation.set(null)
    operationInFlight.set(false)
    projectionFailure.set(null)
    synchronized(contractsLock) {
      capturedContracts.clear()
      cachedProjection = null
      contractGeneration += 1
    }
    store = null
    OriginalSerializerGuards.release(originalSerializer, guard)
  }

  companion object {
    private const val MAX_CAPTURED_ROOT_CONTRACTS: Int = 2

    fun <T> forSerializer(
      original: Serializer<T>,
      storageScope: com.masaibar.datastore.inspector.protocol.StorageScope,
      defaultValue: T = original.defaultValue
    ): Pair<CustomInspectionHandle<T>, Serializer<T>> {
      lateinit var handle: CustomInspectionHandle<T>
      handle =
        CustomInspectionHandle(
          originalSerializer = original,
          defaultValue = defaultValue,
          storageScope = storageScope,
          encodeUnprotected = { value ->
            val output = BoundedByteArrayOutputStream()
            original.writeTo(value, output)
            output.toByteArray()
          },
          decodeUnprotected = { bytes ->
            original.readFrom(ByteArrayInputStream(bytes))
          }
        )
      return handle to InspectableSerializer(original, handle)
    }

    fun <T> forOkioSerializer(
      original: OkioSerializer<T>,
      storageScope: com.masaibar.datastore.inspector.protocol.StorageScope,
      defaultValue: T = original.defaultValue
    ): Pair<CustomInspectionHandle<T>, OkioSerializer<T>> {
      lateinit var handle: CustomInspectionHandle<T>
      handle =
        CustomInspectionHandle(
          originalSerializer = original,
          defaultValue = defaultValue,
          storageScope = storageScope,
          encodeUnprotected = { value ->
            val buffer = Buffer()
            val sink = BoundedSink(buffer).buffer()
            original.writeTo(value, sink)
            sink.flush()
            buffer.readByteArray()
          },
          decodeUnprotected = { bytes ->
            original.readFrom(Buffer().write(bytes))
          }
        )
      return handle to InspectableOkioSerializer(original, handle)
    }
  }
}

private class InspectableSerializer<T>(
  private val original: Serializer<T>,
  private val handle: CustomInspectionHandle<T>
) : Serializer<T> {
  override val defaultValue: T
    get() = handle.defaultValue

  override suspend fun readFrom(input: InputStream): T =
    handle.delegateRead {
      original.readFrom(input)
    }

  override suspend fun writeTo(
    t: T,
    output: OutputStream
  ) {
    handle.delegateWrite(
      value = t,
      directWrite = { original.writeTo(t, output) },
      bufferedWrite = {
        val buffer = BoundedByteArrayOutputStream()
        original.writeTo(t, buffer)
        buffer.toByteArray()
      },
      commitBuffered = { bytes -> output.write(bytes) }
    )
  }
}

private class InspectableOkioSerializer<T>(
  private val original: OkioSerializer<T>,
  private val handle: CustomInspectionHandle<T>
) : OkioSerializer<T> {
  override val defaultValue: T
    get() = handle.defaultValue

  override suspend fun readFrom(source: BufferedSource): T =
    handle.delegateRead {
      original.readFrom(source)
    }

  override suspend fun writeTo(
    t: T,
    sink: BufferedSink
  ) {
    handle.delegateWrite(
      value = t,
      directWrite = { original.writeTo(t, sink) },
      bufferedWrite = {
        val buffer = Buffer()
        val boundedSink = BoundedSink(buffer).buffer()
        original.writeTo(t, boundedSink)
        boundedSink.flush()
        buffer.readByteArray()
      },
      commitBuffered = { bytes -> sink.write(bytes) }
    )
  }
}

private class BoundedByteArrayOutputStream : ByteArrayOutputStream() {
  override fun write(value: Int) {
    requireCapacity(1)
    super.write(value)
  }

  override fun write(
    bytes: ByteArray,
    offset: Int,
    length: Int
  ) {
    requireCapacity(length)
    super.write(bytes, offset, length)
  }

  private fun requireCapacity(additional: Int) {
    if (size().toLong() + additional > CustomInspectionLimits.MAX_PERSISTENCE_BYTES) {
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_DOCUMENT_TOO_LARGE
      )
    }
  }
}

private class BoundedSink(
  delegate: okio.Sink
) : okio.ForwardingSink(delegate) {
  private var bytesWritten = 0L

  override fun write(
    source: Buffer,
    byteCount: Long
  ) {
    if (
      byteCount < 0 ||
      bytesWritten + byteCount > CustomInspectionLimits.MAX_PERSISTENCE_BYTES
    ) {
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_DOCUMENT_TOO_LARGE
      )
    }
    super.write(source, byteCount)
    bytesWritten += byteCount
  }
}

private class OriginalSerializerGuard {
  private val mutex = Mutex()
  private val poisoned = AtomicReference<CustomStoreReasonCode?>(null)
  var references: Int = 0

  suspend fun <T> withLock(block: suspend () -> T): T {
    mutex.lock()
    return try {
      block()
    } finally {
      mutex.unlock()
    }
  }

  fun poison(reason: CustomStoreReasonCode) {
    poisoned.compareAndSet(null, reason)
  }

  fun poisonedReason(): CustomStoreReasonCode? = poisoned.get()
}

private object OriginalSerializerGuards {
  private val lock = Any()
  private val entries = IdentityHashMap<Any, OriginalSerializerGuard>()

  fun guardFor(serializer: Any): OriginalSerializerGuard =
    synchronized(lock) {
      entries
        .getOrPut(serializer) { OriginalSerializerGuard() }
        .also {
          it.references += 1
        }
    }

  fun release(
    serializer: Any,
    guard: OriginalSerializerGuard
  ) {
    synchronized(lock) {
      val entry = entries[serializer] ?: return
      if (entry !== guard) return
      entry.references -= 1
      if (entry.references == 0) entries.remove(serializer)
    }
  }
}

internal fun sameValue(
  expected: Any?,
  actual: Any?
): Boolean {
  if (expected == null || actual == null) return expected == actual
  return expected.javaClass == actual.javaClass &&
    expected == actual &&
    expected.hashCode() == actual.hashCode()
}
