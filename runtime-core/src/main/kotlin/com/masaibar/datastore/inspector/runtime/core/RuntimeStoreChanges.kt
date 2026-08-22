package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ResponsePayload
import com.masaibar.datastore.inspector.protocol.StoreChangeBoundaryNotification
import com.masaibar.datastore.inspector.protocol.StoreChangeBoundaryReason
import com.masaibar.datastore.inspector.protocol.StoreChangeKind
import com.masaibar.datastore.inspector.protocol.StoreChangeNotification
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface RuntimeStoreChangeSink {
  /** falseならconnection writerが利用不能で、購読を終了する必要があります。 */
  fun publish(payload: ResponsePayload): Boolean
}

internal class WriteCorrelationTracker(
  private val nowMillis: () -> Long = System::currentTimeMillis
) : AutoCloseable {
  internal data class Ticket(
    val id: Long,
    val storeId: String,
    val correlationId: String,
    val completion: CompletableDeferred<String?>
  )

  private data class Completed(
    val correlationId: String,
    val createdAtMillis: Long
  )

  private val lock = Any()
  private val inFlight = mutableMapOf<String, Ticket>()
  private val completed = LinkedHashMap<Pair<String, String>, Completed>()
  private var nextTicketId = 0L
  private var closed = false

  fun begin(
    storeId: String,
    correlationId: String?
  ): Ticket? {
    if (correlationId == null) return null
    return synchronized(lock) {
      if (closed) return@synchronized null
      inFlight.remove(storeId)?.completion?.complete(null)
      nextTicketId += 1
      Ticket(
        id = nextTicketId,
        storeId = storeId,
        correlationId = correlationId,
        completion = CompletableDeferred()
      ).also { ticket -> inFlight[storeId] = ticket }
    }
  }

  fun complete(
    ticket: Ticket?,
    fingerprint: String?
  ) {
    if (ticket == null) return
    synchronized(lock) {
      if (inFlight[ticket.storeId]?.id == ticket.id) {
        inFlight.remove(ticket.storeId)
      }
      if (!closed && fingerprint != null) {
        pruneCompletedLocked()
        completed[ticket.storeId to fingerprint] =
          Completed(ticket.correlationId, nowMillis())
        while (completed.size > MAX_COMPLETED) {
          completed.remove(completed.keys.first())
        }
      }
    }
    ticket.completion.complete(fingerprint)
  }

  suspend fun claim(
    storeId: String,
    fingerprint: String
  ): String? {
    val pending = synchronized(lock) { inFlight[storeId] }
    if (pending != null) {
      val completedFingerprint =
        withTimeoutOrNull(CORRELATION_WAIT_MILLIS) {
          pending.completion.await()
        }
      if (completedFingerprint == fingerprint) {
        synchronized(lock) { completed.remove(storeId to fingerprint) }
        return pending.correlationId
      }
    }
    return synchronized(lock) {
      pruneCompletedLocked()
      completed.remove(storeId to fingerprint)?.correlationId
    }
  }

  override fun close() {
    val pending =
      synchronized(lock) {
        if (closed) return
        closed = true
        completed.clear()
        inFlight.values.toList().also { inFlight.clear() }
      }
    pending.forEach { ticket -> ticket.completion.complete(null) }
  }

  private fun pruneCompletedLocked() {
    val cutoff = nowMillis() - COMPLETED_TTL_MILLIS
    val iterator = completed.iterator()
    while (iterator.hasNext()) {
      if (iterator.next().value.createdAtMillis <= cutoff) iterator.remove()
    }
  }

  private companion object {
    const val CORRELATION_WAIT_MILLIS = 30_000L
    const val COMPLETED_TTL_MILLIS = 30_000L
    const val MAX_COMPLETED = 64
  }
}

internal class RuntimeStoreChangeCoordinator(
  private val stores: RuntimeStoreService,
  private val context: RuntimeConnectionContext,
  private val sink: RuntimeStoreChangeSink,
  private val subscriptionGeneration: Long,
  private val nowMillis: () -> Long = System::currentTimeMillis,
  private val reconcileIntervalMillis: Long = DEFAULT_RECONCILE_INTERVAL_MILLIS
) : AutoCloseable {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lock = Any()
  private val subscriptions = LinkedHashMap<StoreKey, ObservedStore>()
  private val closed = AtomicBoolean(false)
  private val catalogFailureActive = AtomicBoolean(false)
  private val reconcileJob: Job =
    scope.launch {
      while (isActive) {
        reconcile()
        delay(reconcileIntervalMillis)
      }
    }

  override fun close() {
    if (!closed.compareAndSet(false, true)) return
    reconcileJob.cancel()
    val owned = synchronized(lock) {
      subscriptions.values.toList().also { subscriptions.clear() }
    }
    owned.forEach(ObservedStore::close)
    scope.cancel()
  }

  private fun reconcile() {
    if (closed.get()) return
    val targets =
      try {
        stores.observationTargets(context).also {
          catalogFailureActive.set(false)
        }
      } catch (_: Exception) {
        if (catalogFailureActive.compareAndSet(false, true)) {
          sink.publish(
            StoreChangeBoundaryNotification(
              subscriptionGeneration = subscriptionGeneration,
              observedAtEpochMillis = nowMillis(),
              reason = StoreChangeBoundaryReason.OBSERVATION_FAILED
            )
          )
        }
        return
      }
    val targetsByKey = targets.associateBy { target -> StoreKey(target.descriptor.id, target.generation) }
    val removed = mutableListOf<ObservedStore>()
    val added = mutableListOf<RuntimeObservableStore>()
    synchronized(lock) {
      val iterator = subscriptions.entries.iterator()
      while (iterator.hasNext()) {
        val (key, observed) = iterator.next()
        if (key !in targetsByKey) {
          removed += observed
          iterator.remove()
        }
      }
      targetsByKey.forEach { (key, target) ->
        val existing = subscriptions[key]
        if (existing == null) {
          added += target
        } else {
          existing.updateDescriptor(target.descriptor)
        }
      }
    }
    removed.forEach { observed ->
      val removalBoundary = observed.removalBoundary()
      observed.close()
      sink.publish(removalBoundary)
    }
    added.forEach { target ->
      val observed =
        ObservedStore(
          target = target,
          stores = stores,
          sink = sink,
          subscriptionGeneration = subscriptionGeneration,
          scope = scope,
          nowMillis = nowMillis
        )
      val key = StoreKey(target.descriptor.id, target.generation)
      val accepted = synchronized(lock) {
        if (closed.get() || subscriptions.containsKey(key)) {
          false
        } else {
          subscriptions[key] = observed
          true
        }
      }
      if (accepted) observed.start() else observed.close()
    }
  }

  private data class StoreKey(
    val storeId: String,
    val generation: Long
  )

  private class ObservedStore(
    target: RuntimeObservableStore,
    private val stores: RuntimeStoreService,
    private val sink: RuntimeStoreChangeSink,
    private val subscriptionGeneration: Long,
    private val scope: CoroutineScope,
    private val nowMillis: () -> Long
  ) : AutoCloseable {
    private val stateLock = Any()
    private val channel = Channel<SequencedObservation>(OBSERVATION_QUEUE_CAPACITY)
    private val closed = AtomicBoolean(false)
    internal val failed = AtomicBoolean(false)
    private val adapter = target.adapter
    private val storeGeneration = target.generation
    private var descriptor = target.descriptor
    private var lastFingerprint: String? = null
    private var sequence = 0L
    private var observerHandle: AutoCloseable? = null
    private val actor =
      scope.launch {
        for (observation in channel) {
          when (observation) {
            is SequencedObservation.Snapshot -> publishSnapshot(observation)
            is SequencedObservation.Failure -> publishFailure(observation)
          }
        }
      }

    fun start() {
      if (closed.get()) return
      observerHandle =
        adapter.observe(scope) { observation ->
          accept(observation)
        }
      if (observerHandle == null) {
        failed.set(true)
        accept(AdapterObservation.Failure(ProtocolErrorCode.STORE_UNSUPPORTED))
      }
    }

    fun updateDescriptor(updated: com.masaibar.datastore.inspector.protocol.StoreDescriptor) {
      synchronized(stateLock) { descriptor = updated }
    }

    fun removalBoundary(): StoreChangeBoundaryNotification {
      val identity = synchronized(stateLock) {
        sequence += 1
        Triple(descriptor.id, checkNotNull(descriptor.logicalId), sequence)
      }
      return StoreChangeBoundaryNotification(
        subscriptionGeneration = subscriptionGeneration,
        observedAtEpochMillis = nowMillis(),
        reason = StoreChangeBoundaryReason.STORE_REMOVED,
        storeId = identity.first,
        logicalStoreId = identity.second,
        storeGeneration = storeGeneration,
        sequence = identity.third
      )
    }

    override fun close() {
      if (!closed.compareAndSet(false, true)) return
      ordinaryFailureOrNull {
        observerHandle?.close()
        Unit
      }
      observerHandle = null
      channel.close()
      actor.cancel()
    }

    private fun accept(observation: AdapterObservation) {
      if (closed.get()) return
      val sequenced =
        synchronized(stateLock) {
          when (observation) {
            is AdapterObservation.Snapshot -> {
              if (observation.snapshot.fingerprint == lastFingerprint) return
              val kind =
                if (lastFingerprint == null) {
                  StoreChangeKind.BASELINE
                } else {
                  StoreChangeKind.CHANGE
                }
              lastFingerprint = observation.snapshot.fingerprint
              sequence += 1
              SequencedObservation.Snapshot(
                sequence = sequence,
                observedAtEpochMillis = nowMillis(),
                kind = kind,
                descriptor = descriptor,
                snapshot = observation.snapshot
              )
            }
            is AdapterObservation.Failure -> {
              failed.set(true)
              sequence += 1
              SequencedObservation.Failure(
                sequence = sequence,
                observedAtEpochMillis = nowMillis(),
                descriptor = descriptor
              )
            }
          }
        }
      if (!channel.trySend(sequenced).isSuccess) {
        val boundary = synchronized(stateLock) {
          sequence += 1
          StoreChangeBoundaryNotification(
            subscriptionGeneration = subscriptionGeneration,
            observedAtEpochMillis = nowMillis(),
            reason = StoreChangeBoundaryReason.BACKPRESSURE,
            storeId = descriptor.id,
            logicalStoreId = checkNotNull(descriptor.logicalId),
            storeGeneration = storeGeneration,
            sequence = sequence
          )
        }
        sink.publish(boundary)
      }
    }

    private suspend fun publishSnapshot(observation: SequencedObservation.Snapshot) {
      val correlationId =
        stores.claimWriteCorrelation(
          observation.descriptor.id,
          observation.snapshot.fingerprint
        )
      if (
        !sink.publish(
          StoreChangeNotification(
            subscriptionGeneration = subscriptionGeneration,
            storeGeneration = storeGeneration,
            sequence = observation.sequence,
            observedAtEpochMillis = observation.observedAtEpochMillis,
            kind = observation.kind,
            store = observation.descriptor,
            snapshot = observation.snapshot.payload,
            correlationId = correlationId
          )
        )
      ) {
        failed.set(true)
      }
    }

    private fun publishFailure(observation: SequencedObservation.Failure) {
      if (
        !sink.publish(
          StoreChangeBoundaryNotification(
            subscriptionGeneration = subscriptionGeneration,
            observedAtEpochMillis = observation.observedAtEpochMillis,
            reason = StoreChangeBoundaryReason.OBSERVATION_FAILED,
            storeId = observation.descriptor.id,
            logicalStoreId = checkNotNull(observation.descriptor.logicalId),
            storeGeneration = storeGeneration,
            sequence = observation.sequence
          )
        )
      ) {
        failed.set(true)
      }
    }

    private sealed interface SequencedObservation {
      val sequence: Long
      val descriptor: com.masaibar.datastore.inspector.protocol.StoreDescriptor

      data class Snapshot(
        override val sequence: Long,
        val observedAtEpochMillis: Long,
        val kind: StoreChangeKind,
        override val descriptor: com.masaibar.datastore.inspector.protocol.StoreDescriptor,
        val snapshot: AdapterSnapshot
      ) : SequencedObservation

      data class Failure(
        override val sequence: Long,
        val observedAtEpochMillis: Long,
        override val descriptor: com.masaibar.datastore.inspector.protocol.StoreDescriptor
      ) : SequencedObservation
    }
  }

  private companion object {
    const val DEFAULT_RECONCILE_INTERVAL_MILLIS = 1_000L
    const val OBSERVATION_QUEUE_CAPACITY = 8
  }
}
