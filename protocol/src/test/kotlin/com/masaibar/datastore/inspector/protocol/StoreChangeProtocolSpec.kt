package com.masaibar.datastore.inspector.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class StoreChangeProtocolSpec :
  DescribeSpec({
    describe("Store change notification wire contract") {
      context("既知のbaseline・change・境界通知を現行Protocolで送るとき") {
        val baseline = changeEnvelope(StoreChangeKind.BASELINE, sequence = 1)
        val change =
          changeEnvelope(
            kind = StoreChangeKind.CHANGE,
            sequence = 2,
            correlationId = "ide-write-68"
          )
        val boundary =
          ResponseEnvelope(
            requestId = "runtime-notification",
            payload =
              StoreChangeBoundaryNotification(
                subscriptionGeneration = 4,
                observedAtEpochMillis = 1_700_000_000_002,
                reason = StoreChangeBoundaryReason.BACKPRESSURE,
                storeId = "runtime-store-1",
                logicalStoreId = "store:logical-main",
                storeGeneration = 0,
                sequence = 3
              )
          )
        val messages = listOf(baseline, change, boundary)

        it("値とsource correlationを失わずround tripする") {
          messages.map { message ->
            ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(message))
          } shouldBe messages
        }
      }

      context("future Runtimeが未知のchange kindとglobal boundary reasonを送るとき") {
        val futureKindJson =
          ProtocolJson.encodeResponseString(changeEnvelope(StoreChangeKind.BASELINE, 1))
            .replace("\"kind\":\"baseline\"", "\"kind\":\"future-kind\"")
        val futureBoundaryJson =
          ProtocolJson.encodeResponseString(
            ResponseEnvelope(
              requestId = "runtime-notification",
              payload =
                StoreChangeBoundaryNotification(
                  subscriptionGeneration = 4,
                  observedAtEpochMillis = 1_700_000_000_003,
                  reason = StoreChangeBoundaryReason.OBSERVATION_FAILED
                )
            )
          ).replace(
            "\"reason\":\"observation_failed\"",
            "\"reason\":\"future-boundary\""
          )

        it("UNKNOWNへfallbackして値を解釈せず境界として受理する") {
          ProtocolJson.decodeResponseString(futureKindJson)
            .payload
            .shouldBeInstanceOf<StoreChangeNotification>()
            .kind shouldBe StoreChangeKind.UNKNOWN
          ProtocolJson.decodeResponseString(futureBoundaryJson)
            .payload
            .shouldBeInstanceOf<StoreChangeBoundaryNotification>()
            .reason shouldBe StoreChangeBoundaryReason.UNKNOWN
        }
      }

      context("current RuntimeがUNKNOWN enumまたは不完全なStore identityを送ろうとするとき") {
        val unknownKind = changeEnvelope(StoreChangeKind.UNKNOWN, sequence = 1)
        val unknownBoundary =
          ResponseEnvelope(
            requestId = "runtime-notification",
            payload =
              StoreChangeBoundaryNotification(
                subscriptionGeneration = 4,
                observedAtEpochMillis = 1_700_000_000_004,
                reason = StoreChangeBoundaryReason.UNKNOWN
              )
          )
        val incompleteBoundary =
          ResponseEnvelope(
            requestId = "runtime-notification",
            payload =
              StoreChangeBoundaryNotification(
                subscriptionGeneration = 4,
                observedAtEpochMillis = 1_700_000_000_005,
                reason = StoreChangeBoundaryReason.STORE_REMOVED,
                storeId = "runtime-store-1"
              )
          )
        val missingGeneration = changeEnvelope(StoreChangeKind.BASELINE, sequence = 1)
          .let { envelope ->
            val notification = envelope.payload as StoreChangeNotification
            envelope.copy(
              payload = notification.copy(
                store = notification.store.copy(generation = null)
              )
            )
          }
        val mismatchedGeneration = changeEnvelope(StoreChangeKind.BASELINE, sequence = 1)
          .let { envelope ->
            val notification = envelope.payload as StoreChangeNotification
            envelope.copy(payload = notification.copy(storeGeneration = 1))
          }
        val invalidMessages = listOf(
          unknownKind,
          unknownBoundary,
          incompleteBoundary,
          missingGeneration,
          mismatchedGeneration
        )

        it("wireへ出す前にfail closedする") {
          invalidMessages.map { message ->
            shouldThrow<ProtocolException> {
              ProtocolJson.encodeResponse(message)
            }.kind
          } shouldBe List(invalidMessages.size) { ProtocolFailureKind.INVALID_MODEL }
        }
      }

      context("IDE writeにcorrelation IDが付くとき") {
        val request =
          RequestEnvelope(
            requestId = "client-write-68",
            payload =
              WriteRequest(
                WritePayload(
                  storeId = "runtime-store-1",
                  expectedRevision = 7,
                  expectedContentToken = "opaque-token",
                  operation = ClearPreferences,
                  correlationId = "ide-write-68"
                )
              )
          )

        it("既存write payloadとの後方互換を保ったままround tripする") {
          ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(request)) shouldBe request
        }
      }
    }
  })

private fun changeEnvelope(
  kind: StoreChangeKind,
  sequence: Long,
  correlationId: String? = null
): ResponseEnvelope =
  ResponseEnvelope(
    requestId = "runtime-notification",
    payload =
      StoreChangeNotification(
        subscriptionGeneration = 4,
        storeGeneration = 0,
        sequence = sequence,
        observedAtEpochMillis = 1_700_000_000_000 + sequence,
        kind = kind,
        store = storeChangeDescriptor(),
        snapshot = emptyPreferencesTree(),
        correlationId = correlationId
      )
  )

private fun storeChangeDescriptor(): StoreDescriptor =
  StoreDescriptor(
    id = "runtime-store-1",
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

private fun emptyPreferencesTree(): PreferencesTree =
  PreferencesTree(
    InspectorNode(
      path = emptyList(),
      name = "root",
      type = InspectorValueType.ROOT,
      value = null,
      presence = Presence.NOT_APPLICABLE,
      children = emptyList(),
      capabilities = emptySet()
    )
  )
