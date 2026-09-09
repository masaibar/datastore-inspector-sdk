package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.ErrorResponse
import com.masaibar.datastore.inspector.protocol.HandshakeRequest
import com.masaibar.datastore.inspector.protocol.HandshakeResponse
import com.masaibar.datastore.inspector.protocol.ListStoresRequest
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolException
import com.masaibar.datastore.inspector.protocol.ProtocolFraming
import com.masaibar.datastore.inspector.protocol.ProtocolJson
import com.masaibar.datastore.inspector.protocol.ProtocolLimits
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.RequestEnvelope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

class RuntimeHandshakeSpec : DescribeSpec({
  val session = RuntimeSession("test-session", "test-socket", "test-token")
  val hello = HandshakeRequest(
    ProtocolVersion.CURRENT,
    ProtocolCapabilities.INITIAL,
    session.sessionId,
    session.token
  )
  val request = RequestEnvelope("handshake-1", hello)

  describe("runtimeHandshakeResponse") {
    context("an authenticated compatible client connects to an occupied Runtime") {
      it("returns retryable BUSY with the original request ID") {
        val response = runtimeHandshakeResponse(request, session, occupied = true)!!
        response.requestId shouldBe request.requestId
        response.payload.shouldBeInstanceOf<ErrorResponse>().code shouldBe ProtocolErrorCode.BUSY
        response.payload.shouldBeInstanceOf<ErrorResponse>().retryable shouldBe true
      }
    }
    context("the previous client has disconnected") {
      it("accepts a new authenticated handshake") {
        runtimeHandshakeResponse(request, session, occupied = false)!!
          .payload.shouldBeInstanceOf<HandshakeResponse>().sessionId shouldBe session.sessionId
      }
    }
    listOf(true, false).forEach { occupied ->
      listOf(
        hello.copy(sessionToken = "wrong-token"),
        hello.copy(sessionId = "wrong-session")
      ).forEachIndexed { index, invalidHello ->
        context("credential case $index is invalid and occupied is $occupied") {
          val invalidRequest = request.copy(payload = invalidHello)
          it("returns AUTH_FAILED without revealing occupancy") {
            runtimeHandshakeResponse(invalidRequest, session, occupied)!!
              .payload.shouldBeInstanceOf<ErrorResponse>().code shouldBe ProtocolErrorCode.AUTH_FAILED
          }
        }
      }
    }
    context("the occupied Runtime receives an incompatible authenticated handshake") {
      val incompatible = request.copy(payload = hello.copy(version = ProtocolVersion(99, 0)))
      it("reports version mismatch before occupancy") {
        runtimeHandshakeResponse(incompatible, session, occupied = true)!!
          .payload.shouldBeInstanceOf<ErrorResponse>().code shouldBe ProtocolErrorCode.VERSION_MISMATCH
      }
    }
    context("the first request is a Store operation") {
      val invalid = request.copy(payload = ListStoresRequest)
      it("does not admit the client or respond with Store data") {
        runtimeHandshakeResponse(invalid, session, occupied = true) shouldBe null
      }
    }
  }

  describe("readHandshakeRequest") {
    val frame = ProtocolFraming.encode(ProtocolJson.encodeRequest(request), ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES)
    context("a handshake arrives in fragments before its deadline") {
      lateinit var input: ByteArrayInputStream
      lateinit var timeouts: MutableList<Int>
      var now = 0L
      beforeEach {
        now = 0L
        timeouts = mutableListOf()
        input = object : ByteArrayInputStream(frame) {
          override fun read(): Int {
            now += 100_000_000L
            return super.read()
          }
          override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            now += 100_000_000L
            return super.read(bytes, offset, minOf(length, 100))
          }
        }
      }
      it("decodes the frame while decreasing the remaining read timeout") {
        readHandshakeRequest(input, timeouts::add, { now }) shouldBe request
        timeouts.first() shouldBe 5_000
        timeouts.zipWithNext().all { (previous, next) -> next < previous } shouldBe true
      }
    }
    context("a peer keeps sending bytes beyond the total handshake deadline") {
      lateinit var input: ByteArrayInputStream
      var now = 0L
      beforeEach {
        now = 0L
        input = object : ByteArrayInputStream(frame) {
          override fun read(): Int {
            now += 2_000_000_000L
            return super.read()
          }

          override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            now += 2_000_000_000L
            return super.read(bytes, offset, minOf(length, 1))
          }
        }
      }
      it("times out even though individual reads complete") {
        shouldThrow<SocketTimeoutException> { readHandshakeRequest(input, {}, { now }) }
      }
    }
    context("the peer advertises an oversized unauthenticated frame") {
      val bytes = ByteBuffer.allocate(4).putInt(ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES + 1).array()
      it("rejects the length without allocating or reading the body") {
        shouldThrow<ProtocolException> { readHandshakeRequest(ByteArrayInputStream(bytes), {}) }
      }
    }
    context("the peer closes during the handshake") {
      val bytes = frame.copyOf(3)
      it("propagates EOF so the caller releases the pending socket") {
        shouldThrow<EOFException> { readHandshakeRequest(ByteArrayInputStream(bytes), {}) }
      }
    }
  }
})
