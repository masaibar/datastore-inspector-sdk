package com.masaibar.datastore.inspector.protocol

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ProtocolResponseBoundaryValidationSpec :
  DescribeSpec({
    describe("Custom document responseの方向別validation") {
      context("current Runtimeが既知formatのsnapshotをencodeするとき") {
        val responses =
          listOf(
            customSnapshotResponse(
              format = CustomDocumentFormat.JSON,
              document = """{"label":"sample"}"""
            ),
            customSnapshotResponse(
              format = CustomDocumentFormat.TEXT,
              document = "sample\ntext"
            )
          )

        it("JSONとTEXTをencode・decodeできる") {
          responses.map { response ->
            ProtocolJson.decodeResponse(
              ProtocolJson.encodeResponse(response)
            )
          } shouldBe responses
        }
      }

      context("current RuntimeがUNKNOWN formatのsnapshotをencodeするとき") {
        val response =
          customSnapshotResponse(
            format = CustomDocumentFormat.UNKNOWN,
            document = "raw future document"
          )

        it("raw documentをwireへ出す前に拒否する") {
          shouldThrow<ProtocolException> {
            ProtocolJson.encodeResponse(response)
          }.kind shouldBe ProtocolFailureKind.INVALID_MODEL
        }
      }

      context("future peerから未知formatのsnapshotをdecodeするとき") {
        val atLimit = "a".repeat(CustomDocumentLimits.MAX_DOCUMENT_UTF8_BYTES)

        it("UNKNOWNへfallbackし1 MiB境界までmodel内に保持する") {
          val envelope =
            ProtocolJson.decodeResponseString(
              futureCustomSnapshotLiteral(atLimit)
            )
          val payload =
            envelope.payload
              .shouldBeInstanceOf<SnapshotResultResponse>()
              .result
              .shouldBeInstanceOf<ResolvedSnapshotResult>()
              .snapshot
              .payload
              .shouldBeInstanceOf<CustomDocumentPayload>()

          payload.format shouldBe CustomDocumentFormat.UNKNOWN
          payload.document shouldBe atLimit
        }

        it("1 MiBを超えるraw documentはdecode時にも拒否する") {
          val error =
            shouldThrow<ProtocolException> {
              ProtocolJson.decodeResponseString(
                futureCustomSnapshotLiteral("$atLimit!")
              )
            }

          error.kind shouldBe ProtocolFailureKind.PAYLOAD_TOO_LARGE
        }
      }
    }

    describe("UnsupportedReasonのwire境界") {
      val carriers = UnsupportedReasonCarrier.entries

      context("codeとsafeMessageがUTF-8 byte上限ちょうどのとき") {
        val reason =
          UnsupportedReason(
            code = "c".repeat(ProtocolLimits.MAX_UNSUPPORTED_REASON_CODE_UTF8_BYTES),
            safeMessage =
              utf8TextAtLimit(
                ProtocolLimits.MAX_UNSUPPORTED_REASON_SAFE_MESSAGE_UTF8_BYTES
              ),
            retryable = false
          )

        it("Store descriptorとUnsupported snapshotの双方でround tripする") {
          carriers.map { carrier ->
            val response = unsupportedReasonResponse(carrier, reason)
            ProtocolJson.decodeResponse(
              ProtocolJson.encodeResponse(response)
            )
          } shouldBe carriers.map { carrier -> unsupportedReasonResponse(carrier, reason) }
        }
      }

      context("lowercase legacy codeと日本語safeMessageのとき") {
        val reason =
          UnsupportedReason(
            code = "custom_serializer",
            safeMessage = "未対応serializerです。",
            retryable = true
          )

        it("既存wire表現を変更せず受理する") {
          carriers.map { carrier ->
            val response = unsupportedReasonResponse(carrier, reason)
            ProtocolJson.decodeResponse(
              ProtocolJson.encodeResponse(response)
            )
          } shouldBe carriers.map { carrier -> unsupportedReasonResponse(carrier, reason) }
        }
      }

      context("codeまたはsafeMessageが空かcodeが表示可能ASCIIでないとき") {
        val invalidReasons =
          listOf(
            UnsupportedReason("", "safe", false),
            UnsupportedReason("CUSTOM_REASON", "", false),
            UnsupportedReason("CUSTOM REASON", "safe", false),
            UnsupportedReason("カスタム", "safe", false)
          )

        it("両carrierのencodeをfail closedする") {
          carriers.flatMap { carrier ->
            invalidReasons.map { reason ->
              shouldThrow<ProtocolException> {
                ProtocolJson.encodeResponse(
                  unsupportedReasonResponse(carrier, reason)
                )
              }.kind
            }
          } shouldBe
            List(carriers.size * invalidReasons.size) {
              ProtocolFailureKind.INVALID_MODEL
            }
        }
      }

      context("codeまたはsafeMessageがUTF-8 byte上限を超えるとき") {
        val oversizedReasons =
          listOf(
            UnsupportedReason(
              code =
                "c".repeat(
                  ProtocolLimits.MAX_UNSUPPORTED_REASON_CODE_UTF8_BYTES + 1
                ),
              safeMessage = "safe",
              retryable = false
            ),
            UnsupportedReason(
              code = "CUSTOM_REASON",
              safeMessage =
                utf8TextAtLimit(
                  ProtocolLimits.MAX_UNSUPPORTED_REASON_SAFE_MESSAGE_UTF8_BYTES
                ) + "!",
              retryable = false
            )
          )

        it("両carrierのencodeを拒否する") {
          carriers.flatMap { carrier ->
            oversizedReasons.map { reason ->
              shouldThrow<ProtocolException> {
                ProtocolJson.encodeResponse(
                  unsupportedReasonResponse(carrier, reason)
                )
              }.kind
            }
          } shouldBe
            List(carriers.size * oversizedReasons.size) {
              ProtocolFailureKind.INVALID_MODEL
            }
        }

        it("両carrierのdecodeも拒否する") {
          carriers.flatMap { carrier ->
            oversizedReasons.map { reason ->
              shouldThrow<ProtocolException> {
                ProtocolJson.decodeResponseString(
                  unsupportedReasonLiteral(carrier, reason.code, reason.safeMessage)
                )
              }.kind
            }
          } shouldBe
            List(carriers.size * oversizedReasons.size) {
              ProtocolFailureKind.INVALID_MODEL
            }
        }
      }

      context("codeまたはsafeMessageにunpaired surrogateが含まれるとき") {
        val malformed = charArrayOf(0xd800.toChar()).concatToString()
        val malformedReasons =
          listOf(
            UnsupportedReason(malformed, "safe", false),
            UnsupportedReason("CUSTOM_REASON", malformed, false)
          )
        val malformedJsonFields =
          listOf(
            "\\uD800" to "safe",
            "CUSTOM_REASON" to "\\uD800"
          )

        it("両carrierのencodeでUTF-8置換せず拒否する") {
          carriers.flatMap { carrier ->
            malformedReasons.map { reason ->
              shouldThrow<ProtocolException> {
                ProtocolJson.encodeResponse(
                  unsupportedReasonResponse(carrier, reason)
                )
              }.kind
            }
          } shouldBe
            List(carriers.size * malformedReasons.size) {
              ProtocolFailureKind.INVALID_MODEL
            }
        }

        it("両carrierのdecodeでもUTF-8置換せず拒否する") {
          carriers.flatMap { carrier ->
            malformedJsonFields.map { (code, safeMessage) ->
              shouldThrow<ProtocolException> {
                ProtocolJson.decodeResponseString(
                  unsupportedReasonLiteral(carrier, code, safeMessage)
                )
              }.kind
            }
          } shouldBe
            List(carriers.size * malformedJsonFields.size) {
              ProtocolFailureKind.INVALID_MODEL
            }
        }
      }
    }
  })

private enum class UnsupportedReasonCarrier {
  STORE_DESCRIPTOR,
  SNAPSHOT
}

private fun customSnapshotResponse(
  format: CustomDocumentFormat,
  document: String
): ResponseEnvelope =
  ResponseEnvelope(
    requestId = "custom-snapshot",
    payload =
      SnapshotResultResponse(
        ResolvedSnapshotResult(
          ResolvedStoreSnapshot(
            storeId = "custom",
            revision = 1,
            contentToken = "opaque",
            payload =
              CustomDocumentPayload(
                projectionId = "projection-v1",
                schemaVersion = 1,
                format = format,
                document = document
              )
          )
        )
      )
  )

private fun futureCustomSnapshotLiteral(document: String): String =
  """
    {
      "requestId":"future-custom-snapshot",
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
              "document":"$document"
            }
          }
        }
      }
    }
  """.trimIndent()

private fun unsupportedReasonResponse(
  carrier: UnsupportedReasonCarrier,
  reason: UnsupportedReason
): ResponseEnvelope =
  ResponseEnvelope(
    requestId = "unsupported-reason",
    payload =
      when (carrier) {
        UnsupportedReasonCarrier.STORE_DESCRIPTOR ->
          ListStoresResult(
            listOf(
              StoreDescriptor(
                id = "custom",
                name = "custom",
                fileName = "custom.bin",
                kind = StoreKind.CUSTOM,
                status = StoreStatus.UNSUPPORTED,
                capabilities = emptySet(),
                unsupportedReason = reason
              )
            )
          )
        UnsupportedReasonCarrier.SNAPSHOT ->
          SnapshotResultResponse(
            UnsupportedSnapshotInfo(
              storeId = "custom",
              reason = reason
            )
          )
      }
  )

private fun unsupportedReasonLiteral(
  carrier: UnsupportedReasonCarrier,
  code: String,
  safeMessage: String
): String =
  when (carrier) {
    UnsupportedReasonCarrier.STORE_DESCRIPTOR ->
      """
            {
              "requestId":"unsupported-reason",
              "payload":{
                "type":"stores_listed",
                "stores":[{
                  "id":"custom",
                  "name":"custom",
                  "fileName":"custom.bin",
                  "kind":"custom",
                  "status":"unsupported",
                  "capabilities":[],
                  "schema":null,
                  "unsupportedReason":{
                    "code":"$code",
                    "safeMessage":"$safeMessage",
                    "retryable":false
                  },
                  "semantics":null
                }]
              }
            }
      """.trimIndent()
    UnsupportedReasonCarrier.SNAPSHOT ->
      """
            {
              "requestId":"unsupported-reason",
              "payload":{
                "type":"snapshot_result",
                "result":{
                  "type":"unsupported_info",
                  "storeId":"custom",
                  "reason":{
                    "code":"$code",
                    "safeMessage":"$safeMessage",
                    "retryable":false
                  }
                }
              }
            }
      """.trimIndent()
  }

private fun utf8TextAtLimit(maximumBytes: Int): String {
  val threeByteCharacters = "界".repeat(maximumBytes / 3)
  return threeByteCharacters +
    "a".repeat(maximumBytes - threeByteCharacters.encodeToByteArray().size)
}
