package com.masaibar.datastore.inspector.runtime.core

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.masaibar.datastore.inspector.protocol.CustomDocumentFormat
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ReplaceCustomDocument
import com.masaibar.datastore.inspector.protocol.StorageScope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CustomStoreTimeoutSpec :
  DescribeSpec({
    describe("CustomStoreAdapter timeout boundary") {
      context("DataStoreがtransform開始前にnon-cooperative blockへ入るとき") {
        lateinit var harness: TimeoutHarness

        beforeEach {
          harness = TimeoutHarness(TimeoutPhase.BEFORE_TRANSFORM)
        }

        afterEach { harness.close() }

        it("pre-start timeoutをoperationStarted=falseにして再送を拒否する") {
          coroutineScope {
            val attempt =
              async(Dispatchers.Default) {
                runCatching {
                  harness.adapter.write(
                    harness.expectedFingerprint,
                    harness.replacement
                  )
                }
              }
            harness.store.blocked.await(1, TimeUnit.SECONDS) shouldBe true
            val failure =
              attempt
                .await()
                .exceptionOrNull()
                .shouldBeInstanceOf<StoreAdapterException>()
            val callsAfterTimeout = harness.store.updateCalls.get()
            val retry =
              runCatching {
                harness.adapter.write(
                  harness.expectedFingerprint,
                  harness.replacement
                )
              }.exceptionOrNull().shouldBeInstanceOf<StoreAdapterException>()
            harness.store.release.countDown()
            harness.store.finished.await(1, TimeUnit.SECONDS) shouldBe true

            failure.code shouldBe ProtocolErrorCode.CUSTOM_OPERATION_TIMEOUT
            failure.operationStarted shouldBe false
            retry.code shouldBe ProtocolErrorCode.CUSTOM_OPERATION_TIMEOUT
            retry.operationStarted shouldBe false
            harness.store.updateCalls.get() shouldBe callsAfterTimeout
            harness.store.current shouldBe TimeoutValue("before", 1)
            harness.handle.unavailableReason() shouldBe
              CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          }
        }
      }

      context("transformがmutation expectationを作成した後にcommit待ちでblockするとき") {
        lateinit var harness: TimeoutHarness

        beforeEach {
          harness = TimeoutHarness(TimeoutPhase.AFTER_TRANSFORM)
        }

        afterEach { harness.close() }

        it("post-start timeoutをoperationStarted=trueにして遅延完了後も再送しない") {
          coroutineScope {
            val attempt =
              async(Dispatchers.Default) {
                runCatching {
                  harness.adapter.write(
                    harness.expectedFingerprint,
                    harness.replacement
                  )
                }
              }
            harness.store.blocked.await(1, TimeUnit.SECONDS) shouldBe true
            val failure =
              attempt
                .await()
                .exceptionOrNull()
                .shouldBeInstanceOf<StoreAdapterException>()
            val callsAfterTimeout = harness.store.updateCalls.get()
            harness.store.release.countDown()
            harness.store.finished.await(1, TimeUnit.SECONDS) shouldBe true
            val retry =
              runCatching {
                harness.adapter.write(
                  harness.expectedFingerprint,
                  harness.replacement
                )
              }.exceptionOrNull().shouldBeInstanceOf<StoreAdapterException>()

            failure.code shouldBe ProtocolErrorCode.CUSTOM_OPERATION_TIMEOUT
            failure.operationStarted shouldBe true
            retry.code shouldBe ProtocolErrorCode.CUSTOM_OPERATION_TIMEOUT
            retry.operationStarted shouldBe false
            harness.store.updateCalls.get() shouldBe callsAfterTimeout
            harness.store.current shouldBe TimeoutValue("edited", 2)
            harness.handle.unavailableReason() shouldBe
              CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          }
        }
      }
    }

    describe("CustomStoreAdapter single-flight placement") {
      context("同一Storeの2件目が待機中に別Storeがsnapshotを要求するとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var firstHandle: CustomInspectionHandle<TimeoutValue>
        lateinit var otherHandle: CustomInspectionHandle<TimeoutValue>
        lateinit var blockedStore: FirstReadBlockingDataStore
        lateinit var firstAdapter: CustomStoreAdapter<TimeoutValue>
        lateinit var otherAdapter: CustomStoreAdapter<TimeoutValue>

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 2,
              queueCapacity = 64,
              timeoutMillis = 1_000
            )
          firstHandle =
            CustomInspectionHandle
              .forSerializer(
                TimeoutJsonSerializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          otherHandle =
            CustomInspectionHandle
              .forSerializer(
                TimeoutJsonSerializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          blockedStore = FirstReadBlockingDataStore(TimeoutValue("blocked", 1))
          firstAdapter = CustomStoreAdapter(blockedStore, firstHandle, executor)
          otherAdapter =
            CustomStoreAdapter(
              ImmediateDataStore(TimeoutValue("other", 2)),
              otherHandle,
              executor
            )
        }

        afterEach {
          blockedStore.release.countDown()
          executor.close()
          firstHandle.close()
          otherHandle.close()
        }

        it("同一StoreのMutex待機でworkerを消費せず別Storeを継続する") {
          coroutineScope {
            val first =
              async(Dispatchers.Default) {
                firstAdapter.snapshot()
              }
            blockedStore.blocked.await(1, TimeUnit.SECONDS) shouldBe true
            val sameStoreWaiter =
              async(Dispatchers.Default) {
                firstAdapter.snapshot()
              }
            delay(50)
            val other =
              withTimeout(500) {
                otherAdapter.snapshot()
              }
            blockedStore.release.countDown()

            other.payload
              .shouldBeInstanceOf<
                com.masaibar.datastore.inspector.protocol.CustomDocumentPayload
              >()
              .document shouldBe """{"label":"other","counter":2}"""
            first.await()
            sameStoreWaiter.await()
            blockedStore.collections.get() shouldBe 2
          }
        }
      }
    }
  })

private class TimeoutHarness(
  phase: TimeoutPhase
) : AutoCloseable {
  val handle =
    CustomInspectionHandle
      .forSerializer(
        TimeoutJsonSerializer,
        StorageScope.CREDENTIAL_PROTECTED
      ).first
  val store = TimeoutDataStore(TimeoutValue("before", 1), phase)
  private val executor =
    CustomInspectionExecutor(
      workerCount = 1,
      queueCapacity = 1,
      timeoutMillis = 250
    )
  val adapter = CustomStoreAdapter(store, handle, executor)
  val expectedFingerprint =
    kotlinx.coroutines.runBlocking {
      CustomProjectionResolver(handle).resolve(store.current).fingerprint
    }
  val replacement =
    ReplaceCustomDocument(
      projectionId = DIRECT_JSON_PROJECTION_ID,
      schemaVersion = 1,
      format = CustomDocumentFormat.JSON,
      document = """{"label":"edited","counter":2}"""
    )

  override fun close() {
    store.release.countDown()
    executor.close()
    handle.close()
  }
}

private enum class TimeoutPhase {
  BEFORE_TRANSFORM,
  AFTER_TRANSFORM
}

private class TimeoutDataStore(
  initial: TimeoutValue,
  private val phase: TimeoutPhase
) : DataStore<TimeoutValue> {
  @Volatile
  var current: TimeoutValue = initial
    private set
  val updateCalls = AtomicInteger()
  val blocked = CountDownLatch(1)
  val release = CountDownLatch(1)
  val finished = CountDownLatch(1)
  override val data: Flow<TimeoutValue>
    get() = flowOf(current)

  override suspend fun updateData(transform: suspend (t: TimeoutValue) -> TimeoutValue): TimeoutValue {
    updateCalls.incrementAndGet()
    try {
      if (phase == TimeoutPhase.BEFORE_TRANSFORM) {
        blocked.countDown()
        release.awaitIgnoringTimeoutInterrupts()
      }
      val candidate = transform(current)
      if (phase == TimeoutPhase.AFTER_TRANSFORM) {
        blocked.countDown()
        release.awaitIgnoringTimeoutInterrupts()
      }
      current = candidate
      return candidate
    } finally {
      finished.countDown()
    }
  }
}

private class FirstReadBlockingDataStore(
  private val value: TimeoutValue
) : DataStore<TimeoutValue> {
  val collections = AtomicInteger()
  val blocked = CountDownLatch(1)
  val release = CountDownLatch(1)
  override val data: Flow<TimeoutValue> =
    flow {
      if (collections.incrementAndGet() == 1) {
        blocked.countDown()
        release.awaitIgnoringTimeoutInterrupts()
      }
      emit(value)
    }

  override suspend fun updateData(transform: suspend (t: TimeoutValue) -> TimeoutValue): TimeoutValue = transform(value)
}

private class ImmediateDataStore(
  private val value: TimeoutValue
) : DataStore<TimeoutValue> {
  override val data: Flow<TimeoutValue> = flowOf(value)

  override suspend fun updateData(transform: suspend (t: TimeoutValue) -> TimeoutValue): TimeoutValue = transform(value)
}

@Serializable
private data class TimeoutValue(
  val label: String,
  val counter: Int
)

private object TimeoutJsonSerializer : Serializer<TimeoutValue> {
  private val json = Json
  override val defaultValue: TimeoutValue = TimeoutValue("before", 1)

  override suspend fun readFrom(input: InputStream): TimeoutValue =
    json.decodeFromString(
      TimeoutValue.serializer(),
      input.readBytes().decodeToString()
    )

  override suspend fun writeTo(
    t: TimeoutValue,
    output: OutputStream
  ) {
    output.write(
      json.encodeToString(TimeoutValue.serializer(), t).encodeToByteArray()
    )
  }
}

private fun CountDownLatch.awaitIgnoringTimeoutInterrupts() {
  while (count != 0L) {
    try {
      await()
    } catch (_: InterruptedException) {
      // timeout後にcommitへ進むnon-cooperative DataStoreを模擬する。
    }
  }
}
