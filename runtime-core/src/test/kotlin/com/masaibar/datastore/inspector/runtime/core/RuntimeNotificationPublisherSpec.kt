package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.BytesValue
import com.masaibar.datastore.inspector.protocol.InspectorNode
import com.masaibar.datastore.inspector.protocol.InspectorValueType
import com.masaibar.datastore.inspector.protocol.PreferenceKey
import com.masaibar.datastore.inspector.protocol.PreferenceValueTypeIds
import com.masaibar.datastore.inspector.protocol.PreferencesTree
import com.masaibar.datastore.inspector.protocol.Presence
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolFraming
import com.masaibar.datastore.inspector.protocol.ProtocolJson
import com.masaibar.datastore.inspector.protocol.ProtocolLimits
import com.masaibar.datastore.inspector.protocol.ResponseEnvelope
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreBackend
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreChangeBoundaryNotification
import com.masaibar.datastore.inspector.protocol.StoreChangeBoundaryReason
import com.masaibar.datastore.inspector.protocol.StoreChangeKind
import com.masaibar.datastore.inspector.protocol.StoreChangeNotification
import com.masaibar.datastore.inspector.protocol.StoreDescriptor
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StoreSemantics
import com.masaibar.datastore.inspector.protocol.StoreStatus
import com.masaibar.datastore.inspector.protocol.StringValue
import com.masaibar.datastore.inspector.protocol.WriteConsistency
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class RuntimeNotificationPublisherSpec :
  DescribeSpec({
    describe("Runtime notification writer boundary") {
      context("canonical Store stateのframed JSONが16 MiBを超えるとき") {
        lateinit var output: ByteArrayOutputStream
        lateinit var publisher: RuntimeNotificationPublisher
        lateinit var oversized: StoreChangeNotification

        beforeEach {
          output = ByteArrayOutputStream()
          publisher =
            RuntimeNotificationPublisher(
              output = output,
              outputLock = Any(),
              subscriptionGeneration = 9,
              onWriteFailure = {}
            )
          oversized =
            notification(
              sequence = 1,
              snapshot =
                bytesPreferencesTree(
                  first = ByteArray(ProtocolLimits.VALUE_BYTES) { 0x41 },
                  second = ByteArray(ProtocolLimits.VALUE_BYTES) { 0x42 }
                )
            )
        }

        afterEach {
          publisher.close()
        }

        it("値を送らず同じStore identityとsequenceのOVERSIZED_STATE境界を送る") {
          publisher.publish(oversized) shouldBe true
          awaitPublisherCondition { output.size() > 0 }

          val framed = output.toByteArray()
          val payloads = decodeFrames(framed)
          val boundary =
            payloads.single()
              .payload
              .shouldBeInstanceOf<StoreChangeBoundaryNotification>()
          boundary.reason shouldBe StoreChangeBoundaryReason.OVERSIZED_STATE
          boundary.storeId shouldBe oversized.store.id
          boundary.logicalStoreId shouldBe oversized.store.logicalId
          boundary.sequence shouldBe oversized.sequence
          framed.size shouldBeLessThan 1_024
        }
      }

      context("socket writeが停止したままnotification queue容量を超えるとき") {
        lateinit var output: BlockingOutputStream
        lateinit var publisher: RuntimeNotificationPublisher

        beforeEach {
          output = BlockingOutputStream()
          publisher =
            RuntimeNotificationPublisher(
              output = output,
              outputLock = Any(),
              subscriptionGeneration = 9,
              onWriteFailure = {}
            )
        }

        afterEach {
          output.release()
          publisher.close()
        }

        it("古いqueued stateを排出しglobal BACKPRESSURE境界から最新stateを再開する") {
          publisher.publish(notification(sequence = 1)) shouldBe true
          output.awaitFirstWrite()
          (2..10).forEach { sequence ->
            publisher.publish(notification(sequence = sequence.toLong())) shouldBe true
          }
          output.release()
          awaitPublisherCondition { decodeFrames(output.bytes()).size == 3 }

          val payloads = decodeFrames(output.bytes()).map(ResponseEnvelope::payload)
          payloads shouldHaveSize 3
          payloads.first()
            .shouldBeInstanceOf<StoreChangeNotification>()
            .sequence shouldBe 1L
          payloads[1]
            .shouldBeInstanceOf<StoreChangeBoundaryNotification>()
            .reason shouldBe StoreChangeBoundaryReason.BACKPRESSURE
          payloads.last()
            .shouldBeInstanceOf<StoreChangeNotification>()
            .sequence shouldBe 10L
        }
      }
    }
  })

private fun notification(
  sequence: Long,
  snapshot: PreferencesTree = stringPreferencesTree("value-$sequence")
): StoreChangeNotification =
  StoreChangeNotification(
    subscriptionGeneration = 9,
    storeGeneration = 0,
    sequence = sequence,
    observedAtEpochMillis = 1_700_000_000_000 + sequence,
    kind = if (sequence == 1L) StoreChangeKind.BASELINE else StoreChangeKind.CHANGE,
    store = notificationDescriptor(),
    snapshot = snapshot
  )

private fun notificationDescriptor(): StoreDescriptor =
  StoreDescriptor(
    id = "runtime-store",
    name = "main",
    fileName = "main.preferences_pb",
    kind = StoreKind.PREFERENCES,
    status = StoreStatus.RESOLVED,
    capabilities =
      setOf(
        StoreCapability(ProtocolCapabilities.SNAPSHOT_GET),
        StoreCapability(ProtocolCapabilities.STORE_CHANGES)
      ),
    semantics =
      StoreSemantics(
        backend = StoreBackend.DATASTORE,
        storageScope = StorageScope.CREDENTIAL_PROTECTED,
        supportedValueTypes = PreferenceValueTypeIds.DATASTORE,
        writeConsistency = WriteConsistency.ATOMIC_TRANSACTIONAL
      ),
    logicalId = "store:logical-main",
    generation = 0
  )

private fun stringPreferencesTree(value: String): PreferencesTree =
  preferencesTree(
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
    )
  )

private fun bytesPreferencesTree(
  first: ByteArray,
  second: ByteArray
): PreferencesTree =
  preferencesTree(
    listOf(
      bytesNode("first", first),
      bytesNode("second", second)
    )
  )

private fun bytesNode(
  name: String,
  value: ByteArray
): InspectorNode =
  InspectorNode(
    path = listOf(PreferenceKey(name)),
    name = name,
    type = InspectorValueType.BYTES,
    value = BytesValue(value),
    presence = Presence.PRESENT,
    children = emptyList(),
    capabilities = emptySet()
  )

private fun preferencesTree(children: List<InspectorNode>): PreferencesTree =
  PreferencesTree(
    InspectorNode(
      path = emptyList(),
      name = "root",
      type = InspectorValueType.ROOT,
      value = null,
      presence = Presence.NOT_APPLICABLE,
      children = children,
      capabilities = emptySet()
    )
  )

private class BlockingOutputStream : OutputStream() {
  private val delegate = ByteArrayOutputStream()
  private val blockFirst = AtomicBoolean(true)
  private val firstWrite = CountDownLatch(1)
  private val release = CountDownLatch(1)

  override fun write(value: Int) {
    write(byteArrayOf(value.toByte()))
  }

  override fun write(
    bytes: ByteArray,
    offset: Int,
    length: Int
  ) {
    if (blockFirst.compareAndSet(true, false)) {
      firstWrite.countDown()
      release.await(2, TimeUnit.SECONDS)
    }
    synchronized(delegate) {
      delegate.write(bytes, offset, length)
    }
  }

  fun awaitFirstWrite() {
    check(firstWrite.await(2, TimeUnit.SECONDS)) { "notification writeが開始されませんでした。" }
  }

  fun release() {
    release.countDown()
  }

  fun bytes(): ByteArray = synchronized(delegate) { delegate.toByteArray() }
}

private fun decodeFrames(bytes: ByteArray): List<ResponseEnvelope> {
  val decoded = mutableListOf<ResponseEnvelope>()
  var offset = 0
  while (offset < bytes.size) {
    if (bytes.size - offset < Int.SIZE_BYTES) return emptyList()
    val length =
      ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .int
    if (length < 0 || bytes.size - offset - Int.SIZE_BYTES < length) return emptyList()
    val frame = bytes.copyOfRange(offset, offset + Int.SIZE_BYTES + length)
    decoded +=
      ProtocolJson.decodeResponse(
        ProtocolFraming.decode(frame, ProtocolLimits.AUTHENTICATED_FRAME_BYTES)
      )
    offset += Int.SIZE_BYTES + length
  }
  return decoded
}

private fun awaitPublisherCondition(
  timeoutMillis: Long = 3_000,
  condition: () -> Boolean
) {
  val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
  while (!condition()) {
    check(System.nanoTime() < deadline) { "条件が${timeoutMillis}ms以内に成立しませんでした。" }
    Thread.sleep(5)
  }
}
