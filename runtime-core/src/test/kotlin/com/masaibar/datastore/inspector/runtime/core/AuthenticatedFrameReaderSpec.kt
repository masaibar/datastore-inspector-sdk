package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.ListStoresRequest
import com.masaibar.datastore.inspector.protocol.ProtocolFraming
import com.masaibar.datastore.inspector.protocol.ProtocolJson
import com.masaibar.datastore.inspector.protocol.ProtocolLimits
import com.masaibar.datastore.inspector.protocol.RequestEnvelope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class AuthenticatedFrameReaderSpec :
  DescribeSpec({
    describe("readAuthenticatedRequest") {
      context("認証済み接続がframeの先頭byteを待っているとき") {
        lateinit var appliedTimeouts: MutableList<Int>
        lateinit var input: DataInputStream

        beforeEach {
          val encoded =
            ProtocolFraming.encode(
              ProtocolJson.encodeRequest(RequestEnvelope("request-1", ListStoresRequest)),
              ProtocolLimits.AUTHENTICATED_FRAME_BYTES
            )
          appliedTimeouts = mutableListOf()
          var firstRead = true
          input =
            DataInputStream(
              object : ByteArrayInputStream(encoded) {
                override fun read(): Int {
                  if (firstRead) {
                    appliedTimeouts shouldBe listOf(0)
                    firstRead = false
                  }
                  return super.read()
                }
              }
            )
        }

        it("アイドル中はtimeoutせずframe受信開始後だけ30秒を適用する") {
          val request =
            readAuthenticatedRequest(
              input = input,
              maximum = ProtocolLimits.AUTHENTICATED_FRAME_BYTES,
              setReadTimeout = appliedTimeouts::add
            )

          appliedTimeouts shouldBe listOf(0, 30_000)
          request.requestId shouldBe "request-1"
          request.payload.shouldBeInstanceOf<ListStoresRequest>()
        }
      }
    }
  })
