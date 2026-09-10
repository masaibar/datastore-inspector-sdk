package com.masaibar.datastore.inspector.runtime.core

import java.io.Closeable
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class PendingHandshakeWorkers : Closeable {
  private val lock = Any()
  private val clients = mutableSetOf<Closeable>()
  private var closed = false

  // Do not queue handshakes: their read deadline must not wait behind another peer.
  private val executor = ThreadPoolExecutor(
    0,
    4,
    30,
    TimeUnit.SECONDS,
    SynchronousQueue(),
    { task -> Thread(task, "DataStoreInspectorHandshake").apply { isDaemon = true } }
  )

  fun submit(client: Closeable, handshake: () -> Unit) {
    synchronized(lock) {
      if (closed) {
        ordinaryFailureOrNull { client.close() }
        return
      }
      clients.add(client)
      try {
        executor.execute {
          try {
            ordinaryFailureOrNull { client.use { handshake() } }
          } finally {
            synchronized(lock) { clients.remove(client) }
          }
        }
      } catch (_: RejectedExecutionException) {
        clients.remove(client)
        ordinaryFailureOrNull { client.close() }
      }
    }
  }

  override fun close() {
    synchronized(lock) {
      if (closed) return
      closed = true
      clients.forEach { client -> ordinaryFailureOrNull { client.close() } }
      clients.clear()
      executor.shutdownNow()
    }
  }
}
