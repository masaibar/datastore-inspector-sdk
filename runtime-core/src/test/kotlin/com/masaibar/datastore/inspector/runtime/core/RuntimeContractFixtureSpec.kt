package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.InspectorNode
import com.masaibar.datastore.inspector.protocol.InspectorValueType
import com.masaibar.datastore.inspector.protocol.IntValue
import com.masaibar.datastore.inspector.protocol.MutatePreferences
import com.masaibar.datastore.inspector.protocol.PreferenceKey
import com.masaibar.datastore.inspector.protocol.PreferenceValueTypeIds
import com.masaibar.datastore.inspector.protocol.PreferencesTree
import com.masaibar.datastore.inspector.protocol.Presence
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolJson
import com.masaibar.datastore.inspector.protocol.PutPreference
import com.masaibar.datastore.inspector.protocol.ResolvedSnapshotResult
import com.masaibar.datastore.inspector.protocol.ResolvedStoreSnapshot
import com.masaibar.datastore.inspector.protocol.ResponseEnvelope
import com.masaibar.datastore.inspector.protocol.SnapshotResultResponse
import com.masaibar.datastore.inspector.protocol.StorageScope
import com.masaibar.datastore.inspector.protocol.StoreBackend
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreChangeKind
import com.masaibar.datastore.inspector.protocol.StoreChangeNotification
import com.masaibar.datastore.inspector.protocol.StoreDescriptor
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StoreSemantics
import com.masaibar.datastore.inspector.protocol.StoreStatus
import com.masaibar.datastore.inspector.protocol.WriteConsistency
import com.masaibar.datastore.inspector.protocol.WriteRequest
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class RuntimeContractFixtureSpec : DescribeSpec() {
  init {
    describe("RuntimeContractFixture") {
      context("既存の契約を検証するとき") {
        it("Runtime側でclient向けsnapshot contract fixtureを生成する") {
          val output = contractPath("datastore.inspector.contract.output")
          val changeOutput =
            contractPath("datastore.inspector.store.change.contract.output")
          Files.createDirectories(output.parent)
          val expected = runtimeSnapshotResponse()
          val expectedChange = runtimeStoreChangeNotification()
          Files.write(output, ProtocolJson.encodeResponse(expected))
          Files.write(changeOutput, ProtocolJson.encodeResponse(expectedChange))

          (ProtocolJson.decodeResponse(Files.readAllBytes(output))) shouldBe (expected)
          (ProtocolJson.decodeResponse(Files.readAllBytes(changeOutput))) shouldBe (expectedChange)
        }

        it("client生成のwrite requestをRuntimeの実Protocol artifactでdecodeする") {
          val inputProperty = System.getProperty("datastore.inspector.contract.input") ?: return@it
          val input = Path.of(inputProperty)
          withClue("write contract fixtureが存在しません。") { (Files.isRegularFile(input)) shouldBe true }
          val envelope = ProtocolJson.decodeRequest(Files.readAllBytes(input))
          (envelope.requestId) shouldBe ("client-write-1")
          val request = (envelope.payload).shouldBeInstanceOf<WriteRequest>()
          val operation = (request.write.operation).shouldBeInstanceOf<MutatePreferences>()
          (request.write.correlationId) shouldBe ("ide-write-correlation-1")
          (operation.mutation) shouldBe (PutPreference("launch_count", IntValue(43)))
        }
      }
    }
  }

  private fun contractPath(property: String): Path =
    Path.of(checkNotNull(System.getProperty(property)) { "$property が設定されていません。" })

  private fun runtimeSnapshotResponse(): ResponseEnvelope {
    val leaf =
      InspectorNode(
        path = listOf(PreferenceKey("launch_count")),
        name = "launch_count",
        type = InspectorValueType.INT,
        value = IntValue(42),
        presence = Presence.PRESENT,
        children = emptyList(),
        capabilities = emptySet()
      )
    val root =
      InspectorNode(
        path = emptyList(),
        name = "root",
        type = InspectorValueType.ROOT,
        value = null,
        presence = Presence.NOT_APPLICABLE,
        children = listOf(leaf),
        capabilities = emptySet()
      )
    return ResponseEnvelope(
      requestId = "runtime-snapshot-1",
      payload =
        SnapshotResultResponse(
          ResolvedSnapshotResult(
            ResolvedStoreSnapshot(
              storeId = "preferences-main",
              revision = 7,
              contentToken = "runtime-opaque-token",
              payload = PreferencesTree(root),
              storeGeneration = 0
            )
          )
        )
    )
  }

  private fun runtimeStoreChangeNotification(): ResponseEnvelope {
    val snapshot =
      (runtimeSnapshotResponse().payload).shouldBeInstanceOf<SnapshotResultResponse>()
        .result
        .let { result -> (result).shouldBeInstanceOf<ResolvedSnapshotResult>().snapshot.payload }
    return ResponseEnvelope(
      requestId = RuntimeNotificationPublisher.NOTIFICATION_REQUEST_ID,
      payload =
        StoreChangeNotification(
          subscriptionGeneration = 3,
          storeGeneration = 0,
          sequence = 2,
          observedAtEpochMillis = 1_700_000_000_068,
          kind = StoreChangeKind.CHANGE,
          store =
            StoreDescriptor(
              id = "preferences-main",
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
              logicalId = "store:preferences-main",
              generation = 0
            ),
          snapshot = snapshot
        )
    )
  }
}
