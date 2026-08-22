package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.CustomDocumentFormat
import com.masaibar.datastore.inspector.protocol.CustomDocumentPayload
import com.masaibar.datastore.inspector.protocol.ProtocolJson
import com.masaibar.datastore.inspector.protocol.ReplaceCustomDocument
import com.masaibar.datastore.inspector.protocol.ResolvedSnapshotResult
import com.masaibar.datastore.inspector.protocol.ResolvedStoreSnapshot
import com.masaibar.datastore.inspector.protocol.ResponseEnvelope
import com.masaibar.datastore.inspector.protocol.SnapshotResultResponse
import com.masaibar.datastore.inspector.protocol.WriteRequest
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path

class RuntimeCustomContractFixtureSpec :
  DescribeSpec({
    describe("Protocol 1.2 Custom contract fixture") {
      context("RuntimeからclientへCustom snapshot fixtureを生成するとき") {
        val output = contractPath("datastore.inspector.custom.contract.output")
        val expected = runtimeCustomSnapshotResponse()

        it("実Protocol artifactでencode/decode可能な固定fixtureを書き出す") {
          Files.createDirectories(output.parent)
          Files.write(output, ProtocolJson.encodeResponse(expected))

          ProtocolJson.decodeResponse(Files.readAllBytes(output)) shouldBe expected
        }
      }

      context("clientがCustom write fixture検証を要求するとき") {
        val inputProperty =
          System.getProperty("datastore.inspector.custom.contract.input")

        it("実Protocol artifactでprojection metadataとdocumentをdecodeする") {
          if (inputProperty == null) return@it

          val input = Path.of(inputProperty)
          Files.isRegularFile(input) shouldBe true
          val envelope = ProtocolJson.decodeRequest(Files.readAllBytes(input))
          val request = envelope.payload.shouldBeInstanceOf<WriteRequest>()
          val operation =
            request.write.operation.shouldBeInstanceOf<ReplaceCustomDocument>()

          envelope.requestId shouldBe "client-custom-write-1"
          request.write.storeId shouldBe "custom-json-main"
          request.write.expectedRevision shouldBe 9
          request.write.expectedContentToken shouldBe "runtime-custom-opaque-token"
          operation.projectionId shouldBe DIRECT_JSON_PROJECTION_ID
          operation.schemaVersion shouldBe 1
          operation.format shouldBe CustomDocumentFormat.JSON
          operation.document shouldBe """{"counter":43,"label":"sample"}"""
        }
      }
    }
  })

private fun contractPath(property: String): Path = Path.of(checkNotNull(System.getProperty(property)) { "$property が設定されていません。" })

private fun runtimeCustomSnapshotResponse(): ResponseEnvelope =
  ResponseEnvelope(
    requestId = "runtime-custom-snapshot-1",
    payload =
      SnapshotResultResponse(
        ResolvedSnapshotResult(
          ResolvedStoreSnapshot(
            storeId = "custom-json-main",
            revision = 9,
            contentToken = "runtime-custom-opaque-token",
            payload =
              CustomDocumentPayload(
                projectionId = DIRECT_JSON_PROJECTION_ID,
                schemaVersion = 1,
                format = CustomDocumentFormat.JSON,
                document = """{"counter":42,"label":"sample"}"""
              )
          )
        )
      )
  )
