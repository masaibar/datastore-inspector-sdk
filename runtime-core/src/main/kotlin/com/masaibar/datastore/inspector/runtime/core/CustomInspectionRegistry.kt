@file:OptIn(ExperimentalDataStoreInspectorApi::class)

package com.masaibar.datastore.inspector.runtime.core

import androidx.datastore.core.DataStore
import com.masaibar.datastore.inspector.protocol.CustomDocumentLimits
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import java.util.IdentityHashMap
import java.util.ServiceLoader
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal object CustomInspectionRegistry {
  internal data class StorageCapture<T>(
    val originalSerializer: Any,
    val defaultValue: T,
    val handle: CustomInspectionHandle<T>?,
    val observedName: ObservedStoreName
  )

  private val lock = Any()
  private val stores = IdentityHashMap<Any, CustomInspectionHandle<*>>()
  private val storages = IdentityHashMap<Any, StorageCapture<*>>()

  fun <T> attachStore(
    store: DataStore<T>,
    handle: CustomInspectionHandle<T>
  ) {
    handle.attachStore(store)
    synchronized(lock) {
      val existing = stores[store]
      check(existing == null || existing === handle) {
        "同一DataStoreへ複数inspection handleを登録できません。"
      }
      stores[store] = handle
    }
  }

  fun <T> attachStorage(
    storage: Any,
    capture: StorageCapture<T>
  ) {
    synchronized(lock) {
      val existing = storages[storage]
      check(existing == null || existing === capture) {
        "同一Storageへ複数inspection handleを登録できません。"
      }
      storages[storage] = capture
    }
  }

  @Suppress("UNCHECKED_CAST")
  fun <T> handleForStore(store: DataStore<T>): CustomInspectionHandle<T>? =
    synchronized(lock) { stores[store] as? CustomInspectionHandle<T> }

  @Suppress("UNCHECKED_CAST")
  fun <T> handleForStorage(storage: Any): CustomInspectionHandle<T>? =
    synchronized(lock) {
      (storages[storage] as? StorageCapture<T>)?.handle
    }

  @Suppress("UNCHECKED_CAST")
  fun <T> captureForStorage(storage: Any): StorageCapture<T>? = synchronized(lock) { storages[storage] as? StorageCapture<T> }

  fun clear() {
    val handles =
      synchronized(lock) {
        val unique =
          java.util.Collections.newSetFromMap(
            IdentityHashMap<CustomInspectionHandle<*>, Boolean>()
          )
        unique += stores.values
        unique += storages.values.mapNotNull(StorageCapture<*>::handle)
        stores.clear()
        storages.clear()
        unique.toList()
      }
    handles.forEach { handle -> ordinaryFailureOrNull(handle::close) }
  }
}

internal class ObservedStoreName {
  private val value = AtomicReference<String?>(null)
  private val declarationId = AtomicReference<String?>(null)

  fun observe(baseName: String) {
    val safeName =
      baseName.takeIf { candidate ->
        candidate.isNotBlank() &&
          candidate.encodeToByteArray().size <= MAX_BASENAME_UTF8_BYTES &&
          candidate.none(Char::isSurrogate)
      } ?: return
    value.set(safeName)
    declarationId.get()?.let { id ->
      DataStoreInspectorRuntime.updateObservedFileName(id, safeName)
    }
  }

  fun current(): String? = value.get()

  fun bind(id: String) {
    declarationId.set(id)
    value.get()?.let { safeName ->
      DataStoreInspectorRuntime.updateObservedFileName(id, safeName)
    }
  }

  private companion object {
    const val MAX_BASENAME_UTF8_BYTES: Int = 255
  }
}

@ExperimentalDataStoreInspectorApi
public enum class InspectorCustomDocumentFormat {
  JSON,
  TEXT
}

@ExperimentalDataStoreInspectorApi
public interface InspectorCustomCodec<T> {
  /** Runtimeが`fallback:<codecId>:<schemaVersion>`へ名前空間化する安全なASCII識別子です。 */
  public val codecId: String
  public val schemaVersion: Int
    get() = 1
  public val format: InspectorCustomDocumentFormat

  public fun encode(value: T): String

  public fun decode(document: String): T

  /** 型固有のinvariantに違反する場合は例外を投げます。例外内容は外部へ公開されません。 */
  public fun validate(value: T): Unit = Unit
}

@ExperimentalDataStoreInspectorApi
public data class InspectorCustomCodecBinding<T : Any>(
  val serializerClass: Class<*>,
  val valueClass: Class<T>,
  val codec: InspectorCustomCodec<T>
)

@ExperimentalDataStoreInspectorApi
public interface InspectorCustomCodecBindingProvider {
  public val providerId: String

  public fun bindings(): List<InspectorCustomCodecBinding<*>>
}

/**
 * Gradle Pluginがdebug sourceへ生成するbinding providerから利用する型安全なDSLです。
 */
@ExperimentalDataStoreInspectorApi
public class InspectorCustomCodecBindingsBuilder {
  private val values = mutableListOf<InspectorCustomCodecBinding<*>>()

  public fun <T : Any> bind(
    serializerClass: Class<*>,
    valueClass: Class<T>,
    codec: InspectorCustomCodec<T>
  ) {
    values += InspectorCustomCodecBinding(serializerClass, valueClass, codec)
  }

  public fun build(): List<InspectorCustomCodecBinding<*>> = values.toList()
}

@ExperimentalDataStoreInspectorApi
public fun inspectorCustomCodecBindings(block: InspectorCustomCodecBindingsBuilder.() -> Unit): List<InspectorCustomCodecBinding<*>> =
  InspectorCustomCodecBindingsBuilder().apply(block).build()

internal object InspectorCustomCodecRegistry {
  private val lock = Any()
  private var providers: List<InspectorCustomCodecBindingProvider> = emptyList()

  fun load(classLoader: ClassLoader) {
    val loaded =
      ServiceLoader
        .load(InspectorCustomCodecBindingProvider::class.java, classLoader)
        .toList()
    synchronized(lock) { providers = loaded.toList() }
  }

  fun replaceForTest(value: List<InspectorCustomCodecBindingProvider>) {
    synchronized(lock) { providers = value.toList() }
  }

  fun resolve(
    serializerClass: Class<*>,
    valueClass: Class<*>
  ): CodecResolution {
    val snapshot = synchronized(lock) { providers.toList() }
    val duplicateProviders =
      snapshot
        .groupingBy(InspectorCustomCodecBindingProvider::providerId)
        .eachCount()
        .any { (_, count) -> count > 1 }
    if (duplicateProviders) return CodecResolution.Ambiguous
    val allBindings = mutableListOf<InspectorCustomCodecBinding<*>>()
    snapshot.forEach { provider ->
      val bindings =
        try {
          provider.bindings()
        } catch (error: Throwable) {
          error.rethrowInspectionControlFlow()
          return CodecResolution.Ambiguous
        }
      if (bindings.any { binding -> !binding.isValid() }) {
        return CodecResolution.Ambiguous
      }
      allBindings += bindings
    }
    val matches =
      allBindings.filter { binding ->
        binding.serializerClass == serializerClass &&
          binding.valueClass == valueClass
      }
    return when (matches.size) {
      0 -> CodecResolution.None
      1 -> CodecResolution.Resolved(matches.single())
      else -> CodecResolution.Ambiguous
    }
  }

  fun clear() {
    synchronized(lock) { providers = emptyList() }
  }
}

private fun InspectorCustomCodecBinding<*>.isValid(): Boolean {
  val id = codec.codecId
  val projectionId = fallbackProjectionId(id, codec.schemaVersion)
  return id.matches(CODEC_ID_PATTERN) &&
    codec.schemaVersion > 0 &&
    projectionId.encodeToByteArray().size <=
    CustomDocumentLimits.MAX_PROJECTION_ID_UTF8_BYTES
}

internal fun fallbackProjectionId(
  codecId: String,
  schemaVersion: Int
): String = "fallback:$codecId:$schemaVersion"

private val CODEC_ID_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")

internal sealed interface CodecResolution {
  data object None : CodecResolution

  data object Ambiguous : CodecResolution

  data class Resolved(
    val binding: InspectorCustomCodecBinding<*>
  ) : CodecResolution
}

internal class CustomInspectionExecutor(
  workerCount: Int = CustomInspectionLimits.WORKER_COUNT,
  queueCapacity: Int = CustomInspectionLimits.QUEUE_CAPACITY,
  private val timeoutMillis: Long = CustomInspectionLimits.PROBE_TIMEOUT_MILLIS
) : AutoCloseable {
  private val closed = AtomicBoolean(false)
  private val threadNumber = AtomicInteger()
  private val executor =
    ThreadPoolExecutor(
      workerCount,
      workerCount,
      0L,
      TimeUnit.MILLISECONDS,
      ArrayBlockingQueue(queueCapacity),
      ThreadFactory { task ->
        Thread(
          task,
          "DataStoreInspectorCustom-${threadNumber.incrementAndGet()}"
        ).apply { isDaemon = true }
      },
      ThreadPoolExecutor.AbortPolicy()
    )

  suspend fun <T> execute(
    handle: CustomInspectionHandle<*>,
    block: suspend () -> T
  ): T {
    handle.requireAvailable()
    if (closed.get()) {
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE
      )
    }
    val executionState = AtomicReference(ExecutionState.QUEUED)
    val startedSignal = CountDownLatch(1)
    val future =
      try {
        executor.submit<T> {
          if (
            !executionState.compareAndSet(
              ExecutionState.QUEUED,
              ExecutionState.RUNNING
            )
          ) {
            throw java.util.concurrent.CancellationException()
          }
          startedSignal.countDown()
          runBlocking { block() }
        }
      } catch (error: RejectedExecutionException) {
        error.rethrowInspectionControlFlow()
        throw CustomInspectionFailure(
          reason = CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT,
          transient = true
        )
      }
    return try {
      val result =
        runInterruptible(Dispatchers.IO) {
          if (!startedSignal.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            throw TimeoutException()
          }
          future.get(timeoutMillis, TimeUnit.MILLISECONDS)
        }
      handle.finishInspectionOperation()
      result
    } catch (error: TimeoutException) {
      val cancellation = cancel(future, executionState, handle)
      error.rethrowInspectionControlFlow()
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT,
        operationStarted = cancellation.operationStarted,
        transient = cancellation.cancelledWhileQueued
      )
    } catch (cancelled: CancellationException) {
      cancel(future, executionState, handle)
      cancelled.rethrowInspectionControlFlow()
      throw cancelled
    } catch (error: InterruptedException) {
      val cancellation = cancel(future, executionState, handle)
      Thread.currentThread().interrupt()
      error.rethrowInspectionControlFlow()
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT,
        operationStarted = cancellation.operationStarted,
        transient = cancellation.cancelledWhileQueued
      )
    } catch (error: ExecutionException) {
      val cause = error.cause
      when (val controlFlow = cause.findInspectionControlFlow()) {
        is CancellationException -> {
          val operationStarted = handle.finishInspectionOperation()
          if (operationStarted) {
            handle.quarantine(CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT)
          }
          throw controlFlow
        }
        is VirtualMachineError,
        is ThreadDeath,
        is LinkageError
        -> {
          val operationStarted = handle.finishInspectionOperation()
          if (operationStarted) {
            handle.quarantine(
              CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH
            )
          }
          throw controlFlow
        }
        null -> Unit
        else -> error("Unexpected inspection control flow.")
      }
      when (cause) {
        is CustomInspectionFailure -> {
          handle.finishInspectionOperation()
          throw cause
        }
        else ->
          if (cause.containsCustomActualWriteFailure()) {
            handle.finishInspectionOperation()
            throw CustomInspectionFailure(
              CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH
            )
          } else {
            val operationStarted = handle.finishInspectionOperation()
            if (operationStarted) {
              handle.abortInspection(
                CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
              )
            }
            throw CustomInspectionFailure(
              reason =
                if (operationStarted) {
                  CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
                } else {
                  CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
                },
              operationStarted = operationStarted
            )
          }
      }
    }
  }

  private fun cancel(
    future: java.util.concurrent.Future<*>,
    executionState: AtomicReference<ExecutionState>,
    handle: CustomInspectionHandle<*>
  ): CancelledExecution {
    val cancelledWhileQueued =
      executionState.compareAndSet(
        ExecutionState.QUEUED,
        ExecutionState.CANCELLED
      )
    future.cancel(true)
    (future as? Runnable)?.let(executor::remove)
    if (cancelledWhileQueued) {
      return CancelledExecution(
        operationStarted = false,
        cancelledWhileQueued = true
      )
    }
    return CancelledExecution(
      operationStarted =
        handle.abortInspection(CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT),
      cancelledWhileQueued = false
    )
  }

  internal fun largestPoolSize(): Int = executor.largestPoolSize

  internal fun queuedTaskCount(): Int = executor.queue.size

  override fun close() {
    if (closed.compareAndSet(false, true)) executor.shutdownNow()
  }

  private data class CancelledExecution(
    val operationStarted: Boolean,
    val cancelledWhileQueued: Boolean
  )

  private enum class ExecutionState {
    QUEUED,
    RUNNING,
    CANCELLED
  }
}

private fun Throwable?.containsCustomActualWriteFailure(): Boolean {
  var current = this
  val visited =
    java.util.Collections.newSetFromMap(
      IdentityHashMap<Throwable, Boolean>()
    )
  while (current != null) {
    val candidate = current
    if (!visited.add(candidate)) return false
    if (candidate is CustomActualWriteException) return true
    current = candidate.cause
  }
  return false
}

internal fun Throwable.rethrowInspectionControlFlow() {
  findInspectionControlFlow()?.let { throw it }
}

internal inline fun <T : Any> ordinaryFailureOrNull(block: () -> T): T? =
  try {
    block()
  } catch (error: Throwable) {
    error.rethrowInspectionControlFlow()
    null
  }

internal fun Throwable?.findInspectionControlFlow(): Throwable? {
  var current = this
  var deepestMatch: Throwable? = null
  val visited =
    java.util.Collections.newSetFromMap(
      IdentityHashMap<Throwable, Boolean>()
    )
  while (current != null) {
    val candidate = current
    if (!visited.add(candidate)) return deepestMatch
    if (
      candidate is CancellationException ||
      candidate is VirtualMachineError ||
      candidate is ThreadDeath ||
      candidate is LinkageError
    ) {
      deepestMatch = candidate
    }
    current = candidate.cause
  }
  return deepestMatch
}
