package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.ClearPreferences
import com.masaibar.datastore.inspector.protocol.InspectorNode
import com.masaibar.datastore.inspector.protocol.InspectorValueType
import com.masaibar.datastore.inspector.protocol.PreferenceKey
import com.masaibar.datastore.inspector.protocol.PreferencesTree
import com.masaibar.datastore.inspector.protocol.Presence
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.ResolvedSnapshotResult
import com.masaibar.datastore.inspector.protocol.ResponsePayload
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreChangeBoundaryNotification
import com.masaibar.datastore.inspector.protocol.StoreChangeBoundaryReason
import com.masaibar.datastore.inspector.protocol.StoreChangeKind
import com.masaibar.datastore.inspector.protocol.StoreChangeNotification
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StringValue
import com.masaibar.datastore.inspector.protocol.WriteOperation
import com.masaibar.datastore.inspector.protocol.WritePayload
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RuntimeStoreChangesSpec :
  DescribeSpec({
    describe("Runtime Store change coordinator") {
      context("1 Storeで同じfingerprintの再通知を挟んでstateが変わるとき") {
        lateinit var fixture: ChangeFixture

        beforeEach {
          fixture = changeFixture()
          awaitCondition { fixture.adapter.hasObserver }
        }

        afterEach {
          fixture.close()
        }

        it("baselineを1回送り、重複を捨て、changeを連続sequenceで送る") {
          fixture.adapter.emit("fingerprint-1", "before")
          awaitCondition { fixture.sink.payloads.size == 1 }
          fixture.adapter.emit("fingerprint-1", "before")
          Thread.sleep(50)
          fixture.adapter.emit("fingerprint-2", "after")
          awaitCondition { fixture.sink.payloads.size == 2 }

          val notifications =
            fixture.sink.payloads.map { payload ->
              payload.shouldBeInstanceOf<StoreChangeNotification>()
            }
          notifications.map(StoreChangeNotification::kind) shouldBe
            listOf(StoreChangeKind.BASELINE, StoreChangeKind.CHANGE)
          notifications.map(StoreChangeNotification::sequence) shouldBe listOf(1L, 2L)
          notifications.map { notification -> notification.store.logicalId }.distinct() shouldHaveSize 1
          notifications.map { notification -> notification.store.generation } shouldBe
            notifications.map(StoreChangeNotification::storeGeneration)
        }
      }

      context("IDE writeのobserver callbackがwrite resultより先に届くとき") {
        lateinit var fixture: ChangeFixture

        beforeEach {
          fixture = changeFixture()
          awaitCondition { fixture.adapter.hasObserver }
          fixture.adapter.emit("fingerprint-1", "before")
          awaitCondition { fixture.sink.payloads.size == 1 }
        }

        afterEach {
          fixture.close()
        }

        it("fingerprintで待ち合わせて同じcorrelation IDをchangeへ付ける") {
          val lease =
            runBlocking {
              fixture.service.snapshot("runtime-store", fixture.context)
            }.shouldBeInstanceOf<ResolvedSnapshotResult>().snapshot
          runBlocking {
            fixture.service.write(
              WritePayload(
                storeId = "runtime-store",
                expectedRevision = lease.revision,
                expectedContentToken = lease.contentToken,
                operation = ClearPreferences,
                correlationId = "ide-write-68"
              ),
              fixture.context
            )
          }
          awaitCondition { fixture.sink.payloads.size == 2 }

          fixture.sink.payloads.last()
            .shouldBeInstanceOf<StoreChangeNotification>()
            .correlationId shouldBe "ide-write-68"
        }
      }

      context("observerが失敗した後にStore自体もregistryから消えるとき") {
        lateinit var fixture: ChangeFixture

        beforeEach {
          fixture = changeFixture(reconcileIntervalMillis = 20)
          awaitCondition { fixture.adapter.hasObserver }
        }

        afterEach {
          fixture.close()
        }

        it("観測失敗を削除と誤報せず、実際の削除時だけlifecycle境界を送る") {
          fixture.adapter.fail()
          awaitCondition {
            fixture.sink.boundaries().any {
              it.reason == StoreChangeBoundaryReason.OBSERVATION_FAILED
            }
          }
          Thread.sleep(80)
          fixture.sink.boundaries().none {
            it.reason == StoreChangeBoundaryReason.STORE_REMOVED
          } shouldBe true

          fixture.registry.clear()
          awaitCondition {
            fixture.sink.boundaries().any {
              it.reason == StoreChangeBoundaryReason.STORE_REMOVED
            }
          }

          fixture.sink.boundaries().map(StoreChangeBoundaryNotification::reason) shouldBe
            listOf(
              StoreChangeBoundaryReason.OBSERVATION_FAILED,
              StoreChangeBoundaryReason.STORE_REMOVED
            )
        }
      }

      context("consumerが停止してStore内queueが満杯になるとき") {
        lateinit var fixture: ChangeFixture
        lateinit var blockingSink: BlockingRecordingSink

        beforeEach {
          blockingSink = BlockingRecordingSink()
          fixture = changeFixture(sink = blockingSink)
          awaitCondition { fixture.adapter.hasObserver }
        }

        afterEach {
          blockingSink.release()
          fixture.close()
        }

        it("古いstateを無制限保持せずsequence付きbackpressure境界を送る") {
          fixture.adapter.emit("fingerprint-1", "value-1")
          blockingSink.awaitFirstNotification()
          (2..10).forEach { index ->
            fixture.adapter.emit("fingerprint-$index", "value-$index")
          }
          awaitCondition {
            blockingSink.boundaries().any {
              it.reason == StoreChangeBoundaryReason.BACKPRESSURE
            }
          }

          val boundary =
            blockingSink.boundaries().single {
              it.reason == StoreChangeBoundaryReason.BACKPRESSURE
            }
          boundary.sequence shouldBe 11L
          boundary.logicalStoreId shouldBe
            blockingSink.payloads
              .first()
              .shouldBeInstanceOf<StoreChangeNotification>()
              .store
              .logicalId
        }
      }

      context("connection購読をcloseするとき") {
        lateinit var fixture: ChangeFixture

        beforeEach {
          fixture = changeFixture()
          awaitCondition { fixture.adapter.hasObserver }
        }

        afterEach {
          fixture.close()
        }

        it("adapter observer handleを明示的にdisposeする") {
          fixture.coordinator.close()
          awaitCondition { fixture.adapter.observerHandleClosed }

          fixture.adapter.hasObserver shouldBe false
        }
      }
    }
  })

private class ChangeFixture(
  val registry: DataStoreRegistry,
  val service: RuntimeStoreService,
  val adapter: ObservablePreferencesAdapter,
  val sink: RecordingSink,
  val coordinator: RuntimeStoreChangeCoordinator,
  val context: RuntimeConnectionContext
) : AutoCloseable {
  override fun close() {
    coordinator.close()
    service.close()
    registry.clear()
  }
}

private fun changeFixture(
  sink: RecordingSink = RecordingSink(),
  reconcileIntervalMillis: Long = 60_000
): ChangeFixture {
  val adapter = ObservablePreferencesAdapter()
  val registry = DataStoreRegistry { "runtime-store" }
  registry.resolve(
    instance = Any(),
    declaration =
      StoreDeclaration(
        declarationId = "main-preferences",
        name = "main",
        fileName = "main.preferences_pb",
        kindHint = StoreKind.PREFERENCES,
        owner = "fixture.Stores",
        property = "main"
      ),
    factories = listOf(FixedAdapterFactory(adapter))
  )
  val service = RuntimeStoreService(registry = registry, processName = "fixture.process")
  val context =
    RuntimeConnectionContext(
      version = ProtocolVersion.CURRENT,
      capabilities = ProtocolCapabilities.INITIAL,
      sessionId = "fixture-session"
    )
  val coordinator =
    RuntimeStoreChangeCoordinator(
      stores = service,
      context = context,
      sink = sink,
      subscriptionGeneration = 7,
      reconcileIntervalMillis = reconcileIntervalMillis
    )
  return ChangeFixture(registry, service, adapter, sink, coordinator, context)
}

private class FixedAdapterFactory(
  private val adapter: StoreAdapter
) : StoreAdapterFactory {
  override val providerId: String = "observable-fixture"

  override fun create(candidate: StoreCandidate): AdapterResolution =
    AdapterResolution.Resolved(adapter)
}

private class ObservablePreferencesAdapter : StoreAdapter {
  override val kind: StoreKind = StoreKind.PREFERENCES
  override val capabilities: Set<StoreCapability> =
    setOf(
      StoreCapability(ProtocolCapabilities.SNAPSHOT_GET),
      StoreCapability(ProtocolCapabilities.PREFERENCES_WRITE),
      StoreCapability(ProtocolCapabilities.STORE_RESET),
      StoreCapability(ProtocolCapabilities.STORE_CHANGES)
    )
  override val schema = null

  @Volatile
  private var observer: StoreSnapshotObserver? = null

  @Volatile
  var observerHandleClosed: Boolean = false
    private set
  private var current = AdapterSnapshot("fingerprint-1", preferencesTree("before"))

  val hasObserver: Boolean
    get() = observer != null

  override suspend fun snapshot(): AdapterSnapshot = current

  override suspend fun write(
    expectedFingerprint: String,
    operation: WriteOperation
  ): AdapterWriteResult {
    current = AdapterSnapshot("fingerprint-2", preferencesTree("after"))
    observer?.onObservation(AdapterObservation.Snapshot(current))
    return AdapterWriteResult.Applied(current)
  }

  override fun observe(
    scope: CoroutineScope,
    observer: StoreSnapshotObserver
  ): AutoCloseable {
    this.observer = observer
    return AutoCloseable {
      this.observer = null
      observerHandleClosed = true
    }
  }

  fun emit(
    fingerprint: String,
    value: String
  ) {
    current = AdapterSnapshot(fingerprint, preferencesTree(value))
    observer?.onObservation(AdapterObservation.Snapshot(current))
  }

  fun fail() {
    observer?.onObservation(AdapterObservation.Failure(ProtocolErrorCode.STORE_ERROR))
  }
}

private open class RecordingSink : RuntimeStoreChangeSink {
  val payloads = CopyOnWriteArrayList<ResponsePayload>()

  override fun publish(payload: ResponsePayload): Boolean {
    payloads += payload
    return true
  }

  fun boundaries(): List<StoreChangeBoundaryNotification> =
    payloads.filterIsInstance<StoreChangeBoundaryNotification>()
}

private class BlockingRecordingSink : RecordingSink() {
  private val firstNotification = CountDownLatch(1)
  private val release = CountDownLatch(1)

  override fun publish(payload: ResponsePayload): Boolean {
    super.publish(payload)
    if (payload is StoreChangeNotification && firstNotification.count > 0) {
      firstNotification.countDown()
      release.await(2, TimeUnit.SECONDS)
    }
    return true
  }

  fun awaitFirstNotification() {
    check(firstNotification.await(2, TimeUnit.SECONDS)) { "baseline通知が開始されませんでした。" }
  }

  fun release() {
    release.countDown()
  }
}

private fun preferencesTree(value: String): PreferencesTree =
  PreferencesTree(
    InspectorNode(
      path = emptyList(),
      name = "root",
      type = InspectorValueType.ROOT,
      value = null,
      presence = Presence.NOT_APPLICABLE,
      children =
        listOf(
          InspectorNode(
            path = listOf(PreferenceKey("value")),
            name = "value",
            type = InspectorValueType.STRING,
            value = StringValue(value),
            presence = Presence.PRESENT,
            children = emptyList(),
            capabilities = emptySet()
          )
        ),
      capabilities = emptySet()
    )
  )

private fun awaitCondition(
  timeoutMillis: Long = 2_000,
  condition: () -> Boolean
) {
  val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
  while (!condition()) {
    check(System.nanoTime() < deadline) { "条件が${timeoutMillis}ms以内に成立しませんでした。" }
    Thread.sleep(5)
  }
}
