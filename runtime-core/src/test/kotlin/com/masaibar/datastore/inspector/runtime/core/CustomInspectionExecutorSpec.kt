package com.masaibar.datastore.inspector.runtime.core

import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import com.masaibar.datastore.inspector.protocol.StorageScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CustomInspectionExecutorSpec :
  DescribeSpec({
    describe("bounded execution") {
      context("1 workerと1 queue slotが使用中のとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handles: List<CustomInspectionHandle<String>>
        lateinit var started: CountDownLatch
        lateinit var release: CountDownLatch

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 1,
              queueCapacity = 1,
              timeoutMillis = 5_000
            )
          handles = List(3) { newHandle() }
          started = CountDownLatch(1)
          release = CountDownLatch(1)
        }

        afterEach {
          release.countDown()
          executor.close()
          handles.forEach(CustomInspectionHandle<String>::close)
        }

        it("3件目を即時拒否し無関係なhandleをquarantineしない") {
          coroutineScope {
            val running =
              async(Dispatchers.Default) {
                executor.execute(handles[0]) {
                  started.countDown()
                  release.awaitIgnoringInterrupts()
                  "running"
                }
              }
            started.await()
            val queued =
              async(Dispatchers.Default) {
                executor.execute(handles[1]) { "queued" }
              }
            withTimeout(1_000) {
              while (executor.queuedTaskCount() != 1) yield()
            }
            val rejected =
              shouldThrow<CustomInspectionFailure> {
                executor.execute(handles[2]) { "rejected" }
              }
            release.countDown()

            running.await() shouldBe "running"
            queued.await() shouldBe "queued"
            rejected.reason shouldBe CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
            rejected.operationStarted shouldBe false
            rejected.transient shouldBe true
            handles[2].unavailableReason() shouldBe null
            executor.largestPoolSize() shouldBe 1
          }
        }
      }

      context("non-cooperative probeがtimeout後もworker上で完了するとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handle: CustomInspectionHandle<String>
        lateinit var started: CountDownLatch
        lateinit var release: CountDownLatch
        lateinit var finished: CountDownLatch

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 1,
              queueCapacity = 1,
              timeoutMillis = 50
            )
          handle = newHandle()
          started = CountDownLatch(1)
          release = CountDownLatch(1)
          finished = CountDownLatch(1)
        }

        afterEach {
          release.countDown()
          executor.close()
          handle.close()
        }

        it("対象handleだけをquarantineし遅延完了後も再送を実行しない") {
          val first =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(handle) {
                started.countDown()
                release.awaitIgnoringInterrupts()
                finished.countDown()
                "late"
              }
            }
          val retryCalls = AtomicInteger()
          val second =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(handle) {
                retryCalls.incrementAndGet()
                "retry"
              }
            }
          release.countDown()
          finished.await(1, TimeUnit.SECONDS)

          started.count shouldBe 0
          first.reason shouldBe CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          first.operationStarted shouldBe false
          second.reason shouldBe CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          retryCalls.get() shouldBe 0
          handle.unavailableReason() shouldBe
            CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
        }
      }

      context("別Storeのnon-cooperative taskの後ろでqueued timeoutするとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handles: List<CustomInspectionHandle<String>>
        lateinit var started: CountDownLatch
        lateinit var release: CountDownLatch

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 1,
              queueCapacity = 1,
              timeoutMillis = 200
            )
          val queuedSerializer = PlainStringSerializer()
          handles =
            listOf(
              newHandle(),
              newHandle(queuedSerializer),
              newHandle(queuedSerializer)
            )
          started = CountDownLatch(1)
          release = CountDownLatch(1)
        }

        afterEach {
          release.countDown()
          executor.close()
          handles.forEach(CustomInspectionHandle<String>::close)
        }

        it("未開始Futureをqueueから即時除去しhandleをquarantineせず次Storeを通す") {
          coroutineScope {
            val blocker =
              async(Dispatchers.Default) {
                runCatching {
                  executor.execute(handles[0]) {
                    started.countDown()
                    release.awaitIgnoringInterrupts()
                    "blocker"
                  }
                }
              }
            started.await()
            val queued =
              async(Dispatchers.Default) {
                runCatching {
                  executor.execute(handles[1]) { "sensitive-payload" }
                }
              }
            withTimeout(1_000) {
              while (executor.queuedTaskCount() != 1) yield()
            }
            val queuedFailure =
              queued
                .await()
                .exceptionOrNull()
                .shouldBeInstanceOf<CustomInspectionFailure>()
            executor.queuedTaskCount() shouldBe 0
            val continued =
              async(Dispatchers.Default) {
                executor.execute(handles[2]) { "continued" }
              }
            withTimeout(1_000) {
              while (executor.queuedTaskCount() != 1) yield()
            }
            release.countDown()

            continued.await() shouldBe "continued"
            // CI負荷によりblocker側のIO wait開始がqueued側より後になる場合、
            // release後に正常完了し得る。このcontextの契約は未開始queued Futureの
            // timeout・即時除去であり、開始済みblockerのtimeoutは直前contextで固定する。
            blocker.await()
            queuedFailure.reason shouldBe CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
            queuedFailure.operationStarted shouldBe false
            queuedFailure.transient shouldBe true
            handles[1].unavailableReason() shouldBe null
            handles[2].unavailableReason() shouldBe null
            executor.queuedTaskCount() shouldBe 0
          }
        }
      }

      context("queued待機後にtaskが実行開始するとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handles: List<CustomInspectionHandle<String>>
        lateinit var started: CountDownLatch
        lateinit var release: CountDownLatch

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 1,
              queueCapacity = 1,
              timeoutMillis = 250
            )
          handles = List(2) { newHandle() }
          started = CountDownLatch(1)
          release = CountDownLatch(1)
        }

        afterEach {
          release.countDown()
          executor.close()
          handles.forEach(CustomInspectionHandle<String>::close)
        }

        it("queue待機時間とtask実行時間へ別々の固定budgetを与える") {
          coroutineScope {
            val blocker =
              async(Dispatchers.Default) {
                executor.execute(handles[0]) {
                  started.countDown()
                  release.awaitIgnoringInterrupts()
                  "released"
                }
              }
            started.await()
            val queued =
              async(Dispatchers.Default) {
                executor.execute(handles[1]) {
                  delay(175)
                  "completed"
                }
              }
            withTimeout(1_000) {
              while (executor.queuedTaskCount() != 1) yield()
            }
            delay(175)
            release.countDown()

            blocker.await() shouldBe "released"
            queued.await() shouldBe "completed"
            handles[0].unavailableReason() shouldBe null
            handles[1].unavailableReason() shouldBe null
          }
        }
      }

      context("Custom snapshot taskが別Storeの後ろで未開始timeoutするとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var blockerHandle: CustomInspectionHandle<String>
        lateinit var snapshotHandle: CustomInspectionHandle<String>
        lateinit var adapter: CustomStoreAdapter<String>
        lateinit var started: CountDownLatch
        lateinit var release: CountDownLatch

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 1,
              queueCapacity = 1,
              timeoutMillis = 150
            )
          blockerHandle = newHandle()
          snapshotHandle = newHandle()
          adapter =
            CustomStoreAdapter(
              ImmediateStringDataStore("snapshot"),
              snapshotHandle,
              executor
            )
          started = CountDownLatch(1)
          release = CountDownLatch(1)
        }

        afterEach {
          release.countDown()
          executor.close()
          blockerHandle.close()
          snapshotHandle.close()
        }

        it("一時的なUnsupported応答後もdescriptorを永続Unsupportedにしない") {
          coroutineScope {
            val blocker =
              async(Dispatchers.Default) {
                runCatching {
                  executor.execute(blockerHandle) {
                    started.countDown()
                    release.awaitIgnoringInterrupts()
                  }
                }
              }
            started.await()
            val failure =
              shouldThrow<StoreSnapshotUnsupportedException> {
                adapter.snapshot()
              }
            release.countDown()
            blocker.await()

            failure.reason.code shouldBe
              CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT.wireName
            snapshotHandle.unavailableReason() shouldBe null
            snapshotHandle.reportedUnsupportedReason() shouldBe null
            adapter.runtimeUnsupportedReason shouldBe null
          }
        }
      }

      context("caller coroutineが実行中probeをcancelするとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handle: CustomInspectionHandle<String>
        lateinit var started: CountDownLatch
        lateinit var release: CountDownLatch

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 1,
              queueCapacity = 1,
              timeoutMillis = 5_000
            )
          handle = newHandle()
          started = CountDownLatch(1)
          release = CountDownLatch(1)
        }

        afterEach {
          release.countDown()
          executor.close()
          handle.close()
        }

        it("futureをcancelして対象handleをquarantineする") {
          coroutineScope {
            val request =
              async(Dispatchers.Default) {
                executor.execute(handle) {
                  started.countDown()
                  release.awaitIgnoringInterrupts()
                }
              }
            started.await()
            request.cancelAndJoin()

            handle.unavailableReason() shouldBe
              CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          }
        }
      }

      context("executorをcloseした後") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handle: CustomInspectionHandle<String>

        beforeEach {
          executor = CustomInspectionExecutor(workerCount = 1, queueCapacity = 1)
          handle = newHandle()
          executor.close()
        }

        afterEach { handle.close() }

        it("新規probeを開始せずcapture unavailableを返す") {
          val calls = AtomicInteger()
          val failure =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(handle) {
                calls.incrementAndGet()
              }
            }

          failure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE
          calls.get() shouldBe 0
          handle.unavailableReason() shouldBe null
        }
      }
    }

    describe("operation-start marker") {
      context("timeout前にmutation expectationを作成していないとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handle: CustomInspectionHandle<String>
        lateinit var release: CountDownLatch

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 1,
              queueCapacity = 1,
              timeoutMillis = 50
            )
          handle = newHandle()
          release = CountDownLatch(1)
        }

        afterEach {
          release.countDown()
          executor.close()
          handle.close()
        }

        it("operationStarted=falseを返す") {
          val failure =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(handle) {
                release.awaitIgnoringInterrupts()
              }
            }

          failure.operationStarted shouldBe false
        }
      }

      context("mutation expectation作成後にnon-cooperative処理がtimeoutするとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handle: CustomInspectionHandle<String>
        lateinit var release: CountDownLatch
        lateinit var finished: CountDownLatch
        lateinit var expectation: MutationExpectation<String>

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 1,
              queueCapacity = 1,
              timeoutMillis = 50
            )
          handle = newHandle()
          release = CountDownLatch(1)
          finished = CountDownLatch(1)
          expectation =
            MutationExpectation(
              token = Any(),
              candidate = "edited",
              verifyProjection = { _, _ -> true }
            )
        }

        afterEach {
          release.countDown()
          executor.close()
          handle.close()
        }

        it("future結果観測前のmarkerをtrueで返し遅延完了でも復活させない") {
          val failure =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(handle) {
                handle.beginMutation(expectation)
                handle.endMutation(expectation)
                release.awaitIgnoringInterrupts()
                finished.countDown()
              }
            }
          release.countDown()
          finished.await(1, TimeUnit.SECONDS)

          failure.operationStarted shouldBe true
          handle.unavailableReason() shouldBe
            CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          shouldThrow<CustomInspectionFailure> {
            handle.requireAvailable()
          }.reason shouldBe CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
        }
      }

      context("mutation expectation作成後に未知のcommit/return例外が起きるとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handle: CustomInspectionHandle<String>
        lateinit var expectation: MutationExpectation<String>

        beforeEach {
          executor = CustomInspectionExecutor(workerCount = 1, queueCapacity = 1)
          handle = newHandle()
          expectation =
            MutationExpectation(
              token = Any(),
              candidate = "edited",
              verifyProjection = { _, _ -> true }
            )
        }

        afterEach {
          executor.close()
          handle.close()
        }

        it("outcome unknown相当のoperationStarted=trueとして再送を隔離する") {
          val failure =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(handle) {
                handle.beginMutation(expectation)
                handle.endMutation(expectation)
                throw IOException("commit result is unknown")
              }
            }

          failure.reason shouldBe CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          failure.operationStarted shouldBe true
          handle.unavailableReason() shouldBe
            CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
        }
      }

      context("mutation開始前に未知のprobe例外が起きるとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handle: CustomInspectionHandle<String>

        beforeEach {
          executor = CustomInspectionExecutor(workerCount = 1, queueCapacity = 1)
          handle = newHandle()
        }

        afterEach {
          executor.close()
          handle.close()
        }

        it("value round-trip mismatchかつoperationStarted=falseのままにする") {
          val failure =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(handle) {
                throw IOException("probe failed before mutation")
              }
            }

          failure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
          failure.operationStarted shouldBe false
          handle.unavailableReason() shouldBe null
        }
      }

      NestedControlFlowMode.entries.forEach { mode ->
        context("mutation開始後の同型wrapperが${mode.displayName}を包むとき") {
          lateinit var executor: CustomInspectionExecutor
          lateinit var handle: CustomInspectionHandle<String>
          lateinit var expectation: MutationExpectation<String>
          lateinit var controlFlow: NestedControlFlow

          beforeEach {
            executor =
              CustomInspectionExecutor(
                workerCount = 1,
                queueCapacity = 1
              )
            handle = newHandle()
            expectation =
              MutationExpectation(
                token = Any(),
                candidate = "edited",
                verifyProjection = { _, _ -> true }
              )
            controlFlow = mode.create()
          }

          afterEach {
            executor.close()
            handle.close()
          }

          it("最深の元例外を同一identityで再throwし開始済みStoreを隔離する") {
            val caught =
              shouldThrow<Throwable> {
                executor.execute(handle) {
                  handle.beginMutation(expectation)
                  handle.endMutation(expectation)
                  throw controlFlow.wrapper
                }
              }

            (caught === controlFlow.original) shouldBe true
            handle.unavailableReason() shouldBe mode.reason
          }
        }
      }

      context("mutation開始前のcause chainが循環しているとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var handle: CustomInspectionHandle<String>
        lateinit var first: IOException

        beforeEach {
          executor = CustomInspectionExecutor(workerCount = 1, queueCapacity = 1)
          handle = newHandle()
          first = IOException("first")
          val second = IOException("second", first)
          first.initCause(second)
        }

        afterEach {
          executor.close()
          handle.close()
        }

        it("循環を有限回で打ち切り通常のpre-mutation failureへfail closedする") {
          val failure =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(handle) {
                throw first
              }
            }

          failure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
          failure.operationStarted shouldBe false
          handle.unavailableReason() shouldBe null
        }
      }
    }

    describe("original serializer ownership") {
      context("2 handleが同じoriginal serializer identityを共有するとき") {
        lateinit var serializer: ConcurrentSerializer
        lateinit var first: CustomInspectionHandle<String>
        lateinit var second: CustomInspectionHandle<String>

        beforeEach {
          serializer = ConcurrentSerializer()
          first =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          second =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
        }

        afterEach {
          first.close()
          second.close()
        }

        it("original encode呼出しをidentity共有guardで直列化する") {
          coroutineScope {
            listOf(
              async(Dispatchers.Default) { first.encodeForInspection("first") },
              async(Dispatchers.Default) { second.encodeForInspection("second") }
            ).forEach { it.await() }
          }

          serializer.maximumConcurrency.get() shouldBe 1
          serializer.writeCalls.get() shouldBe 2
        }

        it("actual-write mismatchは対象handleだけをquarantineする") {
          first.abortInspection(
            CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH
          )
          val encoded = second.encodeForInspection("available")

          first.unavailableReason() shouldBe
            CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH
          second.unavailableReason() shouldBe null
          encoded.decodeToString() shouldBe "available"
          serializer.writeCalls.get() shouldBe 1
        }
      }

      SharedWaitingOperation.entries.forEach { operation ->
        context("shared guard待機中の${operation.displayName}より先にownerがtimeout abortされたとき") {
          lateinit var serializer: BlockingSerializer
          lateinit var owner: CustomInspectionHandle<String>
          lateinit var waiter: CustomInspectionHandle<String>

          beforeEach {
            serializer = BlockingSerializer()
            owner =
              CustomInspectionHandle
                .forSerializer(
                  serializer,
                  StorageScope.CREDENTIAL_PROTECTED
                ).first
            waiter =
              CustomInspectionHandle
                .forSerializer(
                  serializer,
                  StorageScope.CREDENTIAL_PROTECTED
                ).first
          }

          afterEach {
            serializer.release.countDown()
            owner.close()
            waiter.close()
          }

          it("guard取得後にshared poisonを再確認してoriginal serializerを呼ばない") {
            supervisorScope {
              val ownerCall =
                async(Dispatchers.Default) {
                  owner.encodeForInspection("owner")
                }
              serializer.started.await()
              val waitingCall =
                async(Dispatchers.Unconfined) {
                  operation.inspect(waiter)
                }
              owner.abortInspection(CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT)
              serializer.release.countDown()
              ownerCall.await().decodeToString() shouldBe "owner"
              val failure =
                shouldThrow<CustomInspectionFailure> {
                  waitingCall.await()
                }

              serializer.finished.await(1, TimeUnit.SECONDS) shouldBe true
              failure.reason shouldBe
                CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
              waiter.unavailableReason() shouldBe
                CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
              serializer.writeCalls.get() shouldBe 1
              serializer.readCalls.get() shouldBe 0
            }
          }
        }
      }

      context("同じoriginal serializerを共有するStore Aのprobeが実行開始後timeoutするとき") {
        lateinit var executor: CustomInspectionExecutor
        lateinit var serializer: BlockingSerializer
        lateinit var first: CustomInspectionHandle<String>
        lateinit var second: CustomInspectionHandle<String>
        lateinit var secondCalls: AtomicInteger

        beforeEach {
          executor =
            CustomInspectionExecutor(
              workerCount = 2,
              queueCapacity = 1,
              timeoutMillis = 50
            )
          serializer = BlockingSerializer()
          first =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          second =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          secondCalls = AtomicInteger()
        }

        afterEach {
          serializer.release.countDown()
          executor.close()
          first.close()
          second.close()
        }

        it("shared handleをpre-submitで拒否してMutex待ちをexecutorへ投入しない") {
          val firstFailure =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(first) {
                first.encodeForInspection("first")
              }
            }
          val secondFailure =
            shouldThrow<CustomInspectionFailure> {
              executor.execute(second) {
                secondCalls.incrementAndGet()
                second.encodeForInspection("second")
              }
            }
          serializer.release.countDown()

          serializer.finished.await(1, TimeUnit.SECONDS) shouldBe true
          firstFailure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          firstFailure.operationStarted shouldBe false
          secondFailure.reason shouldBe
            CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          secondCalls.get() shouldBe 0
          second.unavailableReason() shouldBe
            CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          serializer.writeCalls.get() shouldBe 1
          executor.largestPoolSize() shouldBe 1
        }
      }

      context("poisonされたserializer identityの全handleをcloseしたとき") {
        lateinit var serializer: ConcurrentSerializer
        lateinit var first: CustomInspectionHandle<String>
        lateinit var second: CustomInspectionHandle<String>
        lateinit var replacement: CustomInspectionHandle<String>

        beforeEach {
          serializer = ConcurrentSerializer()
          first =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          second =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
          first.abortInspection(CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT)
          first.close()
          second.close()
          replacement =
            CustomInspectionHandle
              .forSerializer(
                serializer,
                StorageScope.CREDENTIAL_PROTECTED
              ).first
        }

        afterEach {
          first.close()
          second.close()
          replacement.close()
        }

        it("同じidentityの新entryへ古いpoisonを引き継がない") {
          val encoded = replacement.encodeForInspection("replacement")

          replacement.unavailableReason() shouldBe null
          encoded.decodeToString() shouldBe "replacement"
          serializer.writeCalls.get() shouldBe 1
        }
      }

      context("通常のapplication writeがinspection mutation外で呼ばれるとき") {
        lateinit var serializer: ConcurrentSerializer
        lateinit var handle: CustomInspectionHandle<String>
        lateinit var effective: Serializer<String>

        beforeEach {
          serializer = ConcurrentSerializer()
          val selected =
            CustomInspectionHandle.forSerializer(
              serializer,
              StorageScope.CREDENTIAL_PROTECTED
            )
          handle = selected.first
          effective = selected.second
        }

        afterEach { handle.close() }

        it("shared poison後もoriginal writeを1回だけ呼びdecodeやpostcondition probeを追加しない") {
          handle.abortInspection(CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT)
          val output = ByteArrayOutputStream()
          effective.writeTo("ordinary", output)

          handle.unavailableReason() shouldBe
            CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          output.toString(Charsets.UTF_8.name()) shouldBe "ordinary"
          serializer.writeCalls.get() shouldBe 1
          serializer.readCalls.get() shouldBe 0
        }

        it("shared poison後もoriginal readを1回だけ呼ぶ") {
          handle.abortInspection(CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT)
          val decoded =
            effective.readFrom(
              ByteArrayInputStream("ordinary".encodeToByteArray())
            )

          handle.unavailableReason() shouldBe
            CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
          decoded shouldBe "ordinary"
          serializer.writeCalls.get() shouldBe 0
          serializer.readCalls.get() shouldBe 1
        }
      }
    }
  })

private fun newHandle(
  serializer: Serializer<String> = PlainStringSerializer()
): CustomInspectionHandle<String> =
  CustomInspectionHandle
    .forSerializer(
      serializer,
      StorageScope.CREDENTIAL_PROTECTED
    ).first

private data class NestedControlFlow(
  val wrapper: Throwable,
  val original: Throwable
)

private enum class NestedControlFlowMode(
  val displayName: String,
  val reason: CustomStoreReasonCode
) {
  CANCELLATION(
    "CancellationException",
    CustomStoreReasonCode.CUSTOM_PROBE_TIMEOUT
  ),
  FATAL(
    "LinkageError",
    CustomStoreReasonCode.CUSTOM_ACTUAL_WRITE_MISMATCH
  )
  ;

  fun create(): NestedControlFlow {
    val original =
      when (this) {
        CANCELLATION -> CancellationException("original cancellation")
        FATAL -> LinkageError("original linkage failure")
      }
    val wrapper =
      when (this) {
        CANCELLATION -> CancellationException("wrapper cancellation")
        FATAL -> LinkageError("wrapper linkage failure")
      }
    wrapper.initCause(original)
    return NestedControlFlow(wrapper, original)
  }
}

private enum class SharedWaitingOperation(
  val displayName: String
) {
  ENCODE("encode"),
  DECODE("decode")
  ;

  suspend fun inspect(handle: CustomInspectionHandle<String>) {
    when (this) {
      ENCODE -> handle.encodeForInspection("waiter")
      DECODE -> handle.decodeForInspection("waiter".encodeToByteArray())
    }
  }
}

private class PlainStringSerializer : Serializer<String> {
  override val defaultValue: String = ""

  override suspend fun readFrom(input: InputStream): String = input.readBytes().decodeToString()

  override suspend fun writeTo(
    t: String,
    output: OutputStream
  ) {
    output.write(t.encodeToByteArray())
  }
}

private class ImmediateStringDataStore(
  private val value: String
) : DataStore<String> {
  override val data: Flow<String> = flowOf(value)

  override suspend fun updateData(transform: suspend (t: String) -> String): String = transform(value)
}

private class ConcurrentSerializer : Serializer<String> {
  val writeCalls = AtomicInteger()
  val readCalls = AtomicInteger()
  val maximumConcurrency = AtomicInteger()
  private val active = AtomicInteger()
  override val defaultValue: String = ""

  override suspend fun readFrom(input: InputStream): String {
    readCalls.incrementAndGet()
    return input.readBytes().decodeToString()
  }

  override suspend fun writeTo(
    t: String,
    output: OutputStream
  ) {
    writeCalls.incrementAndGet()
    val current = active.incrementAndGet()
    maximumConcurrency.updateAndGet { maximum -> maxOf(maximum, current) }
    try {
      delay(25)
      output.write(t.encodeToByteArray())
    } finally {
      active.decrementAndGet()
    }
  }
}

private class BlockingSerializer : Serializer<String> {
  val started = CountDownLatch(1)
  val release = CountDownLatch(1)
  val finished = CountDownLatch(1)
  val writeCalls = AtomicInteger()
  val readCalls = AtomicInteger()
  override val defaultValue: String = ""

  override suspend fun readFrom(input: InputStream): String {
    readCalls.incrementAndGet()
    return input.readBytes().decodeToString()
  }

  override suspend fun writeTo(
    t: String,
    output: OutputStream
  ) {
    writeCalls.incrementAndGet()
    started.countDown()
    try {
      release.awaitIgnoringInterrupts()
      output.write(t.encodeToByteArray())
    } finally {
      finished.countDown()
    }
  }
}

private fun CountDownLatch.awaitIgnoringInterrupts() {
  while (count != 0L) {
    try {
      await()
    } catch (_: InterruptedException) {
      // timeout/cancel後も戻らないSerializerを模擬する。
    }
  }
}
