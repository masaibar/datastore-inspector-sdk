package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.ErrorResponse
import com.masaibar.datastore.inspector.protocol.HandshakeRequest
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolFraming
import com.masaibar.datastore.inspector.protocol.ProtocolJson
import com.masaibar.datastore.inspector.protocol.ProtocolLimits
import com.masaibar.datastore.inspector.protocol.ProtocolVersion
import com.masaibar.datastore.inspector.protocol.RequestEnvelope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PendingHandshakeWorkersSpec : DescribeSpec({
  describe("PendingHandshakeWorkers") {
    context("two peers have not sent their handshakes") {
      lateinit var fixture: HandshakeFixture
      beforeEach {
        fixture = HandshakeFixture(2)
        fixture.awaitPending()
      }
      afterEach { fixture.close() }

      it("returns BUSY to a healthy peer without waiting for either read deadline") {
        fixture.requestBusy()
      }

      it("closes all pending sockets when the Runtime stops") {
        fixture.workers.close()
        fixture.assertPendingClosed()
      }
    }

    context("all four handshake workers are waiting for peers") {
      lateinit var fixture: HandshakeFixture
      beforeEach {
        fixture = HandshakeFixture(4)
        fixture.awaitPending()
      }
      afterEach { fixture.close() }

      it("closes an excess peer immediately instead of queuing or running it inline") {
        fixture.assertNewPeerRejected()
      }
    }

    context("the Runtime has stopped") {
      lateinit var fixture: HandshakeFixture
      beforeEach {
        fixture = HandshakeFixture(0)
        fixture.workers.close()
      }
      afterEach { fixture.close() }

      it("closes a concurrently accepted peer without starting its handshake") {
        fixture.assertNewPeerRejected()
      }
    }
  }
})

private class HandshakeFixture(pendingCount: Int) : Closeable {
  val workers = PendingHandshakeWorkers()
  private val listener = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
  private val sockets = mutableListOf<Socket>()
  private val pending = CountDownLatch(pendingCount)
  private val session = RuntimeSession("test-session", "test-socket", "test-token")
  private val pendingPeers = List(pendingCount) {
    val (peer, accepted) = connect()
    workers.submit(accepted) {
      pending.countDown()
      readHandshakeRequest(accepted.getInputStream(), { accepted.soTimeout = it })
    }
    peer
  }

  fun awaitPending() {
    pending.await(1, TimeUnit.SECONDS) shouldBe true
  }

  fun requestBusy() {
    val (peer, accepted) = connect()
    val request = RequestEnvelope(
      "healthy-peer",
      HandshakeRequest(ProtocolVersion.CURRENT, ProtocolCapabilities.INITIAL, session.sessionId, session.token)
    )
    peer.getOutputStream().write(
      ProtocolFraming.encode(ProtocolJson.encodeRequest(request), ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES)
    )
    workers.submit(accepted) {
      val handshake = readHandshakeRequest(accepted.getInputStream(), { accepted.soTimeout = it })
      val response = runtimeHandshakeResponse(handshake, session, occupied = true)!!
      accepted.getOutputStream().write(
        ProtocolFraming.encode(ProtocolJson.encodeResponse(response), ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES)
      )
    }
    val response = ProtocolJson.decodeResponse(
      ProtocolFraming.decode(peer.getInputStream().readBytes(), ProtocolLimits.UNAUTHENTICATED_FRAME_BYTES)
    )
    response.requestId shouldBe request.requestId
    response.payload.shouldBeInstanceOf<ErrorResponse>().code shouldBe ProtocolErrorCode.BUSY
  }

  fun assertNewPeerRejected() {
    val (peer, accepted) = connect()
    val started = CountDownLatch(1)
    workers.submit(accepted) { started.countDown() }
    peer.getInputStream().read() shouldBe -1
    started.count shouldBe 1L
  }

  fun assertPendingClosed() {
    pendingPeers.forEach { it.getInputStream().read() shouldBe -1 }
  }

  private fun connect(): Pair<Socket, Socket> {
    val peer = Socket(listener.inetAddress, listener.localPort).apply { soTimeout = 1_000 }
    sockets.add(peer)
    val accepted = listener.accept()
    sockets.add(accepted)
    return peer to accepted
  }

  override fun close() {
    workers.close()
    sockets.forEach(Socket::close)
    listener.close()
  }
}
