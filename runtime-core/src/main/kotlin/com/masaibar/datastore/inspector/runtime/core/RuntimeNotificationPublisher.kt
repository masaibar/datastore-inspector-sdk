package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.ProtocolFraming
import com.masaibar.datastore.inspector.protocol.ProtocolJson
import com.masaibar.datastore.inspector.protocol.ProtocolLimits
import com.masaibar.datastore.inspector.protocol.ResponseEnvelope
import com.masaibar.datastore.inspector.protocol.ResponsePayload
import com.masaibar.datastore.inspector.protocol.StoreChangeBoundaryNotification
import com.masaibar.datastore.inspector.protocol.StoreChangeBoundaryReason
import com.masaibar.datastore.inspector.protocol.StoreChangeNotification
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal class RuntimeNotificationPublisher(
  private val output: OutputStream,
  private val outputLock: Any,
  private val subscriptionGeneration: Long,
  private val onWriteFailure: () -> Unit,
  private val nowMillis: () -> Long = System::currentTimeMillis
) : RuntimeStoreChangeSink, AutoCloseable {
  private val running = AtomicBoolean(true)
  private val queuedBytes = AtomicLong(0)
  private val queue = ArrayBlockingQueue<OwnedFrame>(MAX_QUEUED_FRAMES)
  private val queueLock = Any()
  private val writer =
    Thread(::writeLoop, "DataStoreInspectorNotifications").apply {
      isDaemon = true
      start()
    }

  override fun publish(payload: ResponsePayload): Boolean {
    if (!running.get()) return false
    val frame = encodeBounded(payload) ?: return false
    synchronized(queueLock) {
      if (!running.get()) {
        frame.close()
        return false
      }
      val needsEviction =
        queue.remainingCapacity() == 0 ||
          queuedBytes.get() + frame.size > MAX_QUEUED_BYTES
      if (needsEviction) {
        drainLocked()
        val boundary = encode(
          StoreChangeBoundaryNotification(
            subscriptionGeneration = subscriptionGeneration,
            observedAtEpochMillis = nowMillis(),
            reason = StoreChangeBoundaryReason.BACKPRESSURE
          )
        )
        if (boundary != null) enqueueLocked(boundary)
      }
      if (!enqueueLocked(frame)) {
        frame.close()
        return false
      }
    }
    return true
  }

  override fun close() {
    if (!running.compareAndSet(true, false)) return
    writer.interrupt()
    synchronized(queueLock) { drainLocked() }
    if (Thread.currentThread() !== writer) {
      try {
        writer.join(WRITER_CLOSE_WAIT_MILLIS)
      } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
      }
    }
  }

  private fun writeLoop() {
    try {
      while (running.get() || queue.isNotEmpty()) {
        val frame = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
        synchronized(queueLock) {
          queuedBytes.addAndGet(-frame.size.toLong())
        }
        frame.use { owned ->
          synchronized(outputLock) {
            output.write(owned.bytes)
            output.flush()
          }
        }
      }
    } catch (_: InterruptedException) {
      // close()が所有する通常終了です。
    } catch (_: Exception) {
      if (running.compareAndSet(true, false)) onWriteFailure()
    } finally {
      synchronized(queueLock) { drainLocked() }
    }
  }

  private fun encodeBounded(payload: ResponsePayload): OwnedFrame? {
    val encoded =
      try {
        ProtocolJson.encodeResponse(
          ResponseEnvelope(NOTIFICATION_REQUEST_ID, payload)
        )
      } catch (_: Exception) {
        val change = payload as? StoreChangeNotification ?: return null
        return encode(change.oversizedBoundary())
      }
    return try {
      if (encoded.size <= ProtocolLimits.AUTHENTICATED_FRAME_BYTES) {
        OwnedFrame(
          ProtocolFraming.encode(
            encoded,
            ProtocolLimits.AUTHENTICATED_FRAME_BYTES
          )
        )
      } else {
        val change = payload as? StoreChangeNotification ?: return null
        encode(change.oversizedBoundary())
      }
    } finally {
      encoded.fill(0)
    }
  }

  private fun encode(payload: ResponsePayload): OwnedFrame? {
    val encoded =
      try {
        ProtocolJson.encodeResponse(
          ResponseEnvelope(NOTIFICATION_REQUEST_ID, payload)
        )
      } catch (_: Exception) {
        return null
      }
    return try {
      if (encoded.size > ProtocolLimits.AUTHENTICATED_FRAME_BYTES) {
        null
      } else {
        OwnedFrame(
          ProtocolFraming.encode(
            encoded,
            ProtocolLimits.AUTHENTICATED_FRAME_BYTES
          )
        )
      }
    } finally {
      encoded.fill(0)
    }
  }

  private fun StoreChangeNotification.oversizedBoundary(): StoreChangeBoundaryNotification =
    StoreChangeBoundaryNotification(
      subscriptionGeneration = subscriptionGeneration,
      observedAtEpochMillis = nowMillis(),
      reason = StoreChangeBoundaryReason.OVERSIZED_STATE,
      storeId = store.id,
      logicalStoreId = store.logicalId,
      storeGeneration = storeGeneration,
      sequence = sequence
    )

  private fun enqueueLocked(frame: OwnedFrame): Boolean {
    val accepted = queue.offer(frame)
    if (accepted) queuedBytes.addAndGet(frame.size.toLong())
    return accepted
  }

  private fun drainLocked() {
    while (true) {
      val frame = queue.poll() ?: break
      queuedBytes.addAndGet(-frame.size.toLong())
      frame.close()
    }
  }

  private class OwnedFrame(
    val bytes: ByteArray
  ) : AutoCloseable {
    val size: Int
      get() = bytes.size

    override fun close() {
      bytes.fill(0)
    }
  }

  internal companion object {
    const val NOTIFICATION_REQUEST_ID: String = "runtime-notification"
    const val MAX_QUEUED_FRAMES: Int = 8
    const val MAX_QUEUED_BYTES: Long =
      ProtocolLimits.AUTHENTICATED_FRAME_BYTES.toLong() * 2L
    const val WRITER_CLOSE_WAIT_MILLIS: Long = 1_000L
  }
}
