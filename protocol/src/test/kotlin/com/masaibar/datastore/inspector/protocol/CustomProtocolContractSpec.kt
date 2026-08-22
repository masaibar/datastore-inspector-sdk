package com.masaibar.datastore.inspector.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class CustomProtocolContractSpec : DescribeSpec({
  describe("Protocol 1.2 Custom document wire contract") {
    context("Runtimeからclientへstrict JSON snapshotを送るとき") {
      val payload =
        CustomDocumentPayload(
          projectionId = "direct-json-v1",
          schemaVersion = 1,
          format = CustomDocumentFormat.JSON,
          document = """{"counter":42,"label":"sample"}"""
        )
      val response =
        ResponseEnvelope(
          requestId = "runtime-custom-snapshot-1",
          payload =
            SnapshotResultResponse(
              ResolvedSnapshotResult(
                ResolvedStoreSnapshot(
                  storeId = "custom-json-main",
                  revision = 9,
                  contentToken = "runtime-custom-opaque-token",
                  payload = payload
                )
              )
            )
        )

      it("Custom payloadとwire field名を損失なくround tripする") {
        val encoded = ProtocolJson.encodeResponseString(response)
        val decoded = ProtocolJson.decodeResponseString(encoded)

        decoded shouldBe response
        encoded shouldContain """"type":"custom_document""""
        encoded shouldContain """"projectionId":"direct-json-v1""""
        encoded shouldContain """"schemaVersion":1"""
        encoded shouldContain """"format":"json""""
        encoded shouldContain """"document":"{\"counter\":42,\"label\":\"sample\"}""""
      }
    }

    context("clientからRuntimeへstrict text replacementを送るとき") {
      val operation =
        ReplaceCustomDocument(
          projectionId = "direct-text-v1",
          schemaVersion = 3,
          format = CustomDocumentFormat.TEXT,
          document = "hello\nworld"
        )
      val request =
        RequestEnvelope(
          requestId = "client-custom-write-1",
          payload =
            WriteRequest(
              WritePayload(
                storeId = "custom-text-main",
                expectedRevision = 4,
                expectedContentToken = "client-custom-opaque-token",
                operation = operation
              )
            )
        )

      it("Replace operationとwire field名を損失なくround tripする") {
        val encoded = ProtocolJson.encodeRequestString(request)
        val decoded = ProtocolJson.decodeRequestString(encoded)

        decoded shouldBe request
        encoded shouldContain """"type":"replace_custom_document""""
        encoded shouldContain """"projectionId":"direct-text-v1""""
        encoded shouldContain """"schemaVersion":3"""
        encoded shouldContain """"format":"text""""
        encoded shouldContain """"document":"hello\nworld""""
      }
    }

    context("将来追加されたCustom document formatのsnapshotを受信するとき") {
      val literal =
        """
                {
                  "requestId":"future-format",
                  "payload":{
                    "type":"snapshot_result",
                    "result":{
                      "type":"resolved",
                      "snapshot":{
                        "storeId":"custom",
                        "revision":1,
                        "contentToken":"opaque",
                        "payload":{
                          "type":"custom_document",
                          "projectionId":"future-v1",
                          "schemaVersion":1,
                          "format":"future_format",
                          "document":"opaque"
                        }
                      }
                    }
                  }
                }
        """.trimIndent()

      it("UNKNOWNへfallbackしてread-only表示できるmodelを保つ") {
        val envelope = ProtocolJson.decodeResponseString(literal)
        val snapshot =
          envelope.payload
            .shouldBeInstanceOf<SnapshotResultResponse>()
            .result
            .shouldBeInstanceOf<ResolvedSnapshotResult>()
            .snapshot
        val payload = snapshot.payload.shouldBeInstanceOf<CustomDocumentPayload>()

        payload.format shouldBe CustomDocumentFormat.UNKNOWN
        payload.document shouldBe "opaque"
      }
    }

    context("未知formatをreplacementへ指定するとき") {
      val request =
        RequestEnvelope(
          requestId = "unknown-write",
          payload =
            WriteRequest(
              WritePayload(
                storeId = "custom",
                expectedRevision = 1,
                expectedContentToken = "opaque",
                operation =
                  ReplaceCustomDocument(
                    projectionId = "future-v1",
                    schemaVersion = 1,
                    format = CustomDocumentFormat.UNKNOWN,
                    document = "opaque"
                  )
              )
            )
        )

      it("writeをfail closedする") {
        shouldThrow<ProtocolException> {
          ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(request))
        }.kind shouldBe ProtocolFailureKind.INVALID_MODEL
      }
    }

    context("Custom reasonをUnsupported responseへ載せるとき") {
      val response =
        ResponseEnvelope(
          requestId = "unsupported-custom",
          payload =
            SnapshotResultResponse(
              UnsupportedSnapshotInfo(
                storeId = "custom",
                reason =
                  UnsupportedReason(
                    code = CustomStoreReasonCode.CUSTOM_OUTPUT_NOT_JSON,
                    safeMessage = "自動編集できません。",
                    retryable = false
                  )
              )
            )
        )

      it("safeなstable codeだけをwireへ出す") {
        val encoded = ProtocolJson.encodeResponseString(response)

        ProtocolJson.decodeResponseString(encoded) shouldBe response
        encoded shouldContain """"code":"CUSTOM_OUTPUT_NOT_JSON""""
        CustomStoreReasonCode.fromWireName("CUSTOM_OUTPUT_NOT_JSON") shouldBe
          CustomStoreReasonCode.CUSTOM_OUTPUT_NOT_JSON
        CustomStoreReasonCode.fromWireName("FUTURE_REASON") shouldBe
          CustomStoreReasonCode.UNKNOWN
      }
    }

    context("Custom replacement固有のerror responseを受信するとき") {
      val codes =
        listOf(
          ProtocolErrorCode.CUSTOM_DOCUMENT_INVALID,
          ProtocolErrorCode.CUSTOM_PROJECTION_MISMATCH,
          ProtocolErrorCode.CUSTOM_ACTUAL_WRITE_MISMATCH,
          ProtocolErrorCode.CUSTOM_OPERATION_TIMEOUT
        )
      val responses =
        codes.mapIndexed { index, code ->
          ResponseEnvelope(
            requestId = "custom-error-$index",
            payload =
              ErrorResponse(
                code = code,
                safeMessage = "Custom document処理に失敗しました。",
                retryable = false,
                operationStarted = false
              )
          )
        }

      it("全codeをstable nameでround tripする") {
        responses.map { response ->
          val decoded =
            ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(response))
          decoded.payload.shouldBeInstanceOf<ErrorResponse>().code
        } shouldBe codes
      }
    }

    context("従来のPreferences request literalをProtocol 1.2で受信するとき") {
      val literal =
        """
                {
                  "requestId":"legacy-write",
                  "payload":{
                    "type":"write",
                    "write":{
                      "storeId":"preferences",
                      "expectedRevision":3,
                      "expectedContentToken":"legacy-token",
                      "operation":{
                        "type":"mutate_preferences",
                        "mutation":{
                          "type":"put",
                          "key":"counter",
                          "value":{"type":"int","value":4}
                        }
                      }
                    }
                  }
                }
        """.trimIndent()

      it("既存wireを変更せずdecodeする") {
        val request =
          ProtocolJson.decodeRequestString(literal)
            .payload
            .shouldBeInstanceOf<WriteRequest>()

        request.write.storeId shouldBe "preferences"
        request.write.operation.shouldBeInstanceOf<MutatePreferences>()
      }
    }
  }

  describe("Protocol 1.2 capability negotiation") {
    context("新clientと新SDKがCustom capabilityをadvertiseするとき") {
      val negotiated =
        ProtocolNegotiation.negotiate(
          local = ProtocolVersion.CURRENT,
          localCapabilities = ProtocolCapabilities.INITIAL,
          remote = ProtocolVersion(1, 2),
          remoteCapabilities = ProtocolCapabilities.INITIAL
        )

      it("minor 1_2とCustom read・replaceを交渉する") {
        negotiated.version shouldBe ProtocolVersion(1, 2)
        negotiated.capabilities
          .filter { it in ProtocolCapabilities.CUSTOM_DOCUMENT }
          .toSet()
          .shouldContainExactlyInAnyOrder(ProtocolCapabilities.CUSTOM_DOCUMENT)
      }
    }

    context("新SDKとProtocol 1_1 clientが誤ってCustom capabilityもadvertiseするとき") {
      val negotiated =
        ProtocolNegotiation.negotiate(
          local = ProtocolVersion.CURRENT,
          localCapabilities = ProtocolCapabilities.INITIAL,
          remote = ProtocolVersion(1, 1),
          remoteCapabilities = ProtocolCapabilities.INITIAL
        )

      it("minor gateでCustom subtype送出を防ぐ") {
        negotiated.version shouldBe ProtocolVersion(1, 1)
        negotiated.capabilities.intersect(ProtocolCapabilities.CUSTOM_DOCUMENT) shouldBe
          emptySet()
        (ProtocolCapabilities.SHARED_PREFERENCES_INSPECT in negotiated.capabilities) shouldBe true
      }
    }

    context("新clientとProtocol 1_0 SDKが従来capabilityだけをadvertiseするとき") {
      val legacyCapabilities =
        ProtocolCapabilities.INITIAL -
          ProtocolCapabilities.SHARED_PREFERENCES_INSPECT -
          ProtocolCapabilities.CUSTOM_DOCUMENT -
          ProtocolCapabilities.PREFERENCES_REPLACE -
          ProtocolCapabilities.STORE_CHANGES
      val negotiated =
        ProtocolNegotiation.negotiate(
          local = ProtocolVersion.CURRENT,
          localCapabilities = ProtocolCapabilities.INITIAL,
          remote = ProtocolVersion(1, 0),
          remoteCapabilities = legacyCapabilities
        )

      it("従来のPreferences・Proto contractだけを維持する") {
        negotiated.version shouldBe ProtocolVersion(1, 0)
        negotiated.capabilities shouldBe legacyCapabilities
      }
    }
  }

  describe("ProtocolValidator Custom metadata") {
    context("projectionIdが非ASCIIまたはschemaVersionが負のとき") {
      val invalidOperations =
        listOf(
          ReplaceCustomDocument(
            projectionId = "機微-class-name",
            schemaVersion = 1,
            format = CustomDocumentFormat.JSON,
            document = "{}"
          ),
          ReplaceCustomDocument(
            projectionId = "direct-json-v1",
            schemaVersion = -1,
            format = CustomDocumentFormat.JSON,
            document = "{}"
          )
        )
      val requests =
        invalidOperations.mapIndexed { index, operation ->
          RequestEnvelope(
            requestId = "invalid-metadata-$index",
            payload =
              WriteRequest(
                WritePayload(
                  storeId = "custom",
                  expectedRevision = 1,
                  expectedContentToken = "opaque",
                  operation = operation
                )
              )
          )
        }

      it("wire contract IDとして拒否する") {
        requests.map { request ->
          shouldThrow<ProtocolException> {
            ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(request))
          }.kind
        } shouldBe
          listOf(
            ProtocolFailureKind.INVALID_MODEL,
            ProtocolFailureKind.INVALID_MODEL
          )
      }
    }

    context("Custom JSON syntax違反と上限超過をProtocol payloadへ指定するとき") {
      val invalidDocuments =
        listOf(
          """{"duplicate":1,"duplicate":2}""",
          "\"" + "a".repeat(CustomDocumentLimits.MAX_JSON_STRING_UTF8_BYTES + 1) + "\""
        )
      val requests =
        invalidDocuments.mapIndexed { index, document ->
          RequestEnvelope(
            requestId = "invalid-document-$index",
            payload =
              WriteRequest(
                WritePayload(
                  storeId = "custom",
                  expectedRevision = 1,
                  expectedContentToken = "opaque",
                  operation =
                    ReplaceCustomDocument(
                      projectionId = "direct-json-v1",
                      schemaVersion = 1,
                      format = CustomDocumentFormat.JSON,
                      document = document
                    )
                )
              )
          )
        }

      it("INVALID_MODELとPAYLOAD_TOO_LARGEを区別する") {
        requests.map { request ->
          shouldThrow<ProtocolException> {
            ProtocolJson.decodeRequest(ProtocolJson.encodeRequest(request))
          }.kind
        } shouldBe
          listOf(
            ProtocolFailureKind.INVALID_MODEL,
            ProtocolFailureKind.PAYLOAD_TOO_LARGE
          )
      }
    }

    context("Custom capabilityとStore descriptorの状態・semanticsが矛盾するとき") {
      val atomicSemantics =
        StoreSemantics(
          backend = StoreBackend.DATASTORE,
          storageScope = StorageScope.CREDENTIAL_PROTECTED,
          supportedValueTypes = emptySet(),
          writeConsistency = WriteConsistency.ATOMIC_TRANSACTIONAL
        )
      val invalidDescriptors =
        listOf(
          customDescriptor(
            capabilities =
              setOf(
                StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE)
              ),
            semantics = atomicSemantics
          ),
          customDescriptor(
            status = StoreStatus.UNSUPPORTED,
            capabilities =
              setOf(
                StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_GET),
                StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE)
              ),
            semantics = atomicSemantics
          ),
          customDescriptor(
            capabilities =
              setOf(
                StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_GET),
                StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE)
              ),
            semantics =
              atomicSemantics.copy(
                writeConsistency = WriteConsistency.BEST_EFFORT_NON_ATOMIC
              )
          ),
          customDescriptor(
            kind = StoreKind.PROTO,
            capabilities =
              setOf(
                StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_GET)
              ),
            semantics = atomicSemantics
          )
        )
      val responses =
        invalidDescriptors.mapIndexed { index, descriptor ->
          ResponseEnvelope(
            requestId = "invalid-custom-descriptor-$index",
            payload = ListStoresResult(listOf(descriptor))
          )
        }

      it("replace-only・Unsupported・non-atomic・non-Customをfail closedする") {
        responses.map { response ->
          shouldThrow<ProtocolException> {
            ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(response))
          }.kind
        } shouldBe List(responses.size) { ProtocolFailureKind.INVALID_MODEL }
      }
    }

    context("Custom readとreplaceがatomic RESOLVED descriptorへ揃うとき") {
      val descriptor =
        customDescriptor(
          capabilities =
            setOf(
              StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_GET),
              StoreCapability(ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE)
            ),
          semantics =
            StoreSemantics(
              backend = StoreBackend.DATASTORE,
              storageScope = StorageScope.CREDENTIAL_PROTECTED,
              supportedValueTypes = emptySet(),
              writeConsistency = WriteConsistency.ATOMIC_TRANSACTIONAL
            )
        )
      val response =
        ResponseEnvelope(
          requestId = "valid-custom-descriptor",
          payload = ListStoresResult(listOf(descriptor))
        )

      it("descriptorをround tripする") {
        ProtocolJson.decodeResponse(ProtocolJson.encodeResponse(response)) shouldBe response
      }
    }
  }
})

private fun customDescriptor(
  kind: StoreKind = StoreKind.CUSTOM,
  status: StoreStatus = StoreStatus.RESOLVED,
  capabilities: Set<StoreCapability>,
  semantics: StoreSemantics
): StoreDescriptor =
  StoreDescriptor(
    id = "custom",
    name = "custom",
    fileName = "custom.preferences_pb",
    kind = kind,
    status = status,
    capabilities = capabilities,
    semantics = semantics
  )
