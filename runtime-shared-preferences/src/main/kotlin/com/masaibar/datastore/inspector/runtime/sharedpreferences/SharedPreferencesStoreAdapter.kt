package com.masaibar.datastore.inspector.runtime.sharedpreferences

import android.content.SharedPreferences
import com.masaibar.datastore.inspector.protocol.BooleanValue
import com.masaibar.datastore.inspector.protocol.BytesValue
import com.masaibar.datastore.inspector.protocol.ClearPreferences
import com.masaibar.datastore.inspector.protocol.DeletePreference
import com.masaibar.datastore.inspector.protocol.DoubleValue
import com.masaibar.datastore.inspector.protocol.FloatValue
import com.masaibar.datastore.inspector.protocol.InspectorValue
import com.masaibar.datastore.inspector.protocol.IntValue
import com.masaibar.datastore.inspector.protocol.LongValue
import com.masaibar.datastore.inspector.protocol.MutatePreferences
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.PutPreference
import com.masaibar.datastore.inspector.protocol.ReplaceCustomDocument
import com.masaibar.datastore.inspector.protocol.ReplacePreferences
import com.masaibar.datastore.inspector.protocol.ReplaceProtoBytes
import com.masaibar.datastore.inspector.protocol.ResetStore
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StringSetValue
import com.masaibar.datastore.inspector.protocol.StringValue
import com.masaibar.datastore.inspector.protocol.UnsupportedReason
import com.masaibar.datastore.inspector.protocol.WriteOperation
import com.masaibar.datastore.inspector.protocol.WriteOutcomeReason
import com.masaibar.datastore.inspector.runtime.core.AdapterObservation
import com.masaibar.datastore.inspector.runtime.core.AdapterSnapshot
import com.masaibar.datastore.inspector.runtime.core.AdapterWriteResult
import com.masaibar.datastore.inspector.runtime.core.PreferencesSnapshotEncoder
import com.masaibar.datastore.inspector.runtime.core.StoreAdapter
import com.masaibar.datastore.inspector.runtime.core.StoreAdapterException
import com.masaibar.datastore.inspector.runtime.core.StoreSnapshotObserver
import com.masaibar.datastore.inspector.runtime.core.StoreSnapshotUnsupportedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class SharedPreferencesStoreAdapter(
  private val backingFiles: SharedPreferencesBackingFiles,
  private val preferences: () -> SharedPreferences,
  private val stageTimeoutMillis: Long = DEFAULT_STAGE_TIMEOUT_MILLIS,
  private val commitWaitTimeoutMillis: Long = DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS
) : StoreAdapter {
  init {
    require(stageTimeoutMillis > 0) { "stageTimeoutMillis must be positive." }
    require(commitWaitTimeoutMillis > 0) { "commitWaitTimeoutMillis must be positive." }
  }

  override val kind: StoreKind = StoreKind.PREFERENCES
  override val capabilities: Set<StoreCapability> = SHARED_PREFERENCES_CAPABILITIES
  override val schema = null
  override val semantics = SHARED_PREFERENCES_SEMANTICS
  override val requiredCapabilities: Set<String> =
    setOf(ProtocolCapabilities.SHARED_PREFERENCES_INSPECT)

  override suspend fun snapshot(): AdapterSnapshot =
    runBoundedStage { readSnapshot().snapshot }

  override suspend fun write(
    expectedFingerprint: String,
    operation: WriteOperation
  ): AdapterWriteResult {
    if (!commitLane.tryAcquire()) {
      throw StoreAdapterException(
        ProtocolErrorCode.BUSY,
        retryable = true,
        operationStarted = false
      )
    }
    var commitLaneHandedOff = false
    try {
      val editor =
        when (
          val prepared =
            runBoundedStage {
              prepareWrite(expectedFingerprint, operation)
            }
        ) {
          is PreparedWrite.Conflict ->
            return AdapterWriteResult.Conflict(prepared.currentSnapshot)
          is PreparedWrite.Ready -> prepared.editor
        }

      // commit()自体は一度だけ独立workerで完走させる。待機期限を超えたら結果不明を
      // 返してRuntimeの単一client loopを解放する一方、workerが実際に終わるまでは
      // laneを渡したままにし、後続mutationをqueueせずBUSYで拒否する。
      val commitDeadlineNanos = deadlineAfter(commitWaitTimeoutMillis)
      val commitAttempt =
        CommitAttempt(editor, commitLane::release).also(CommitAttempt::start)
      fun handOffCommitLane() {
        commitLaneHandedOff = true
        if (commitAttempt.detach()) commitLane.release()
      }
      val commitResult =
        try {
          commitAttempt.awaitUntil(commitDeadlineNanos)
        } catch (error: CancellationException) {
          handOffCommitLane()
          throw error
        } catch (_: Throwable) {
          handOffCommitLane()
          return AdapterWriteResult.OutcomeUnknown(
            reason = WriteOutcomeReason.PERSISTENCE_NOT_CONFIRMED,
            currentSnapshot = null
          )
        }
      if (commitResult == null) {
        handOffCommitLane()
        return AdapterWriteResult.OutcomeUnknown(
          reason = WriteOutcomeReason.PERSISTENCE_NOT_CONFIRMED,
          currentSnapshot = null
        )
      }
      if (commitResult.exceptionOrNull() != null) {
        return AdapterWriteResult.OutcomeUnknown(
          reason = WriteOutcomeReason.PERSISTENCE_NOT_CONFIRMED,
          currentSnapshot = readSnapshotOrNull()
        )
      }
      val committed = commitResult.getOrThrow()
      if (!committed) {
        return AdapterWriteResult.OutcomeUnknown(
          reason = WriteOutcomeReason.PERSISTENCE_NOT_CONFIRMED,
          currentSnapshot = readSnapshotOrNull()
        )
      }
      val current = readSnapshotOrNull()
      return if (current == null) {
        AdapterWriteResult.AppliedSnapshotUnavailable
      } else {
        AdapterWriteResult.Applied(current)
      }
    } catch (error: StoreAdapterException) {
      throw if (error.operationStarted == null) {
        StoreAdapterException(
          code = error.code,
          retryable = error.retryable,
          operationStarted = false,
          cause = error
        )
      } else {
        error
      }
    } finally {
      if (!commitLaneHandedOff) commitLane.release()
    }
  }

  override fun observe(
    scope: CoroutineScope,
    observer: StoreSnapshotObserver
  ): AutoCloseable {
    val observerLock = Any()
    var active = true
    fun publish(observation: AdapterObservation) {
      synchronized(observerLock) {
        if (active) observer.onObservation(observation)
      }
    }
    val signals = Channel<Unit>(Channel.CONFLATED)
    val observedPreferences = AtomicReference<SharedPreferences?>(null)
    val listener =
      SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        signals.trySend(Unit)
      }
    val job =
      scope.launch {
        try {
          val initial =
            runBoundedStage {
              backingFiles.validateStandardXml()
              rejectEncryptedStore()
              val opened = preferences()
              opened.registerOnSharedPreferenceChangeListener(listener)
              observedPreferences.set(opened)
              readSnapshot(opened).snapshot
            }
          publish(AdapterObservation.Snapshot(initial))
          for (ignored in signals) {
            publish(AdapterObservation.Snapshot(snapshot()))
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: StoreAdapterException) {
          publish(AdapterObservation.Failure(error.code))
        } catch (_: StoreSnapshotUnsupportedException) {
          publish(AdapterObservation.Failure(ProtocolErrorCode.STORE_UNSUPPORTED))
        } catch (_: Exception) {
          publish(AdapterObservation.Failure(ProtocolErrorCode.STORE_ERROR))
        } finally {
          observedPreferences.getAndSet(null)
            ?.unregisterOnSharedPreferenceChangeListener(listener)
          signals.close()
        }
      }
    return AutoCloseable {
      synchronized(observerLock) { active = false }
      observedPreferences.getAndSet(null)
        ?.unregisterOnSharedPreferenceChangeListener(listener)
      signals.close()
      job.cancel()
    }
  }

  private fun prepareWrite(
    expectedFingerprint: String,
    operation: WriteOperation
  ): PreparedWrite {
    val before = readSnapshot()
    if (before.snapshot.fingerprint != expectedFingerprint) {
      return PreparedWrite.Conflict(before.snapshot)
    }
    val afterValues = applyToCopy(before.values, operation)
    PreferencesSnapshotEncoder.encode(
      afterValues,
      ProtocolErrorCode.PREFERENCES_SNAPSHOT_LIMIT
    )

    backingFiles.validateStandardXml()
    rejectEncryptedStore()
    val editor =
      try {
        before.preferences.edit()
      } catch (error: Exception) {
        throw preCommitFailure(error)
      }
    applyToEditor(editor, operation)
    return PreparedWrite.Ready(editor)
  }

  private fun readSnapshot(openedPreferences: SharedPreferences? = null): ReadSnapshot {
    backingFiles.validateStandardXml()
    rejectEncryptedStore()
    val opened =
      openedPreferences ?: try {
        preferences()
      } catch (error: Exception) {
        throw StoreAdapterException(
          ProtocolErrorCode.STORE_ERROR,
          retryable = true,
          operationStarted = false,
          cause = error
        )
      }
    val raw =
      try {
        @Suppress("UNCHECKED_CAST")
        (opened.all as Map<*, *>).entries.map { entry ->
          entry.key to entry.value
        }
      } catch (error: Exception) {
        throw StoreAdapterException(
          ProtocolErrorCode.STORE_ERROR,
          retryable = true,
          operationStarted = false,
          cause = error
        )
      }
    val values = LinkedHashMap<String, InspectorValue>(raw.size)
    raw.forEach { (rawKey, rawValue) ->
      val key = rawKey as? String ?: unsupported()
      if (key in ENCRYPTED_MARKERS) encrypted()
      if (values.containsKey(key)) unsupported()
      values[key] = rawValue.toInspectorValue()
    }
    return ReadSnapshot(
      preferences = opened,
      values = values,
      snapshot =
        PreferencesSnapshotEncoder.encode(
          values,
          ProtocolErrorCode.PREFERENCES_SNAPSHOT_LIMIT
        )
    )
  }

  private suspend fun readSnapshotOrNull(): AdapterSnapshot? =
    try {
      runBoundedStage { readSnapshot().snapshot }
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      null
    }

  private suspend fun <T> runBoundedStage(block: () -> T): T {
    if (!stageLane.tryAcquire()) {
      throw StoreAdapterException(
        ProtocolErrorCode.BUSY,
        retryable = true,
        operationStarted = false
      )
    }
    var stageLaneHandedOff = false
    val stageDeadlineNanos = deadlineAfter(stageTimeoutMillis)
    val attempt = StageAttempt(block, stageLane::release).also { it.start() }
    fun handOffStageLane() {
      stageLaneHandedOff = true
      if (attempt.detach()) stageLane.release()
    }
    try {
      val result =
        try {
          // deadlineはworker起動前のSystem.nanoTimeで開始し、coroutine testの
          // 仮想時刻やアプリ共有Dispatchers.IOの空きに依存させない。
          attempt.awaitUntil(stageDeadlineNanos)
        } catch (error: CancellationException) {
          handOffStageLane()
          throw error
        } catch (error: Throwable) {
          handOffStageLane()
          throw error
        }
      if (result == null) {
        handOffStageLane()
        throw StoreAdapterException(
          ProtocolErrorCode.BUSY,
          retryable = true,
          operationStarted = false
        )
      }
      return result.getOrThrow()
    } finally {
      if (!stageLaneHandedOff) stageLane.release()
    }
  }

  private fun applyToCopy(
    current: Map<String, InspectorValue>,
    operation: WriteOperation
  ): Map<String, InspectorValue> {
    val updated = LinkedHashMap(current)
    when (operation) {
      is MutatePreferences ->
        when (val mutation = operation.mutation) {
          is PutPreference -> {
            val value = mutation.value.copyForSharedPreferences()
            current[mutation.key]?.let { existing ->
              if (!existing.sameSharedPreferencesType(value)) typeMismatch()
            }
            updated[mutation.key] = value
          }
          is DeletePreference -> {
            if (updated.remove(mutation.key) == null) keyNotFound()
          }
        }
      is ReplacePreferences -> {
        updated.clear()
        operation.entries.forEach { entry ->
          require(!updated.containsKey(entry.key)) {
            "Preferences replacementに重複keyがあります。"
          }
          updated[entry.key] = entry.value.copyForSharedPreferences()
        }
      }
      ClearPreferences, ResetStore -> updated.clear()
      is ReplaceCustomDocument -> unsupportedOperation()
      is ReplaceProtoBytes -> unsupportedOperation()
    }
    return updated
  }

  private fun applyToEditor(
    editor: SharedPreferences.Editor,
    operation: WriteOperation
  ) {
    try {
      when (operation) {
        is MutatePreferences ->
          when (val mutation = operation.mutation) {
            is PutPreference -> editor.put(mutation.key, mutation.value)
            is DeletePreference -> editor.remove(mutation.key)
          }
        is ReplacePreferences -> {
          editor.clear()
          operation.entries.forEach { entry ->
            editor.put(entry.key, entry.value)
          }
        }
        ClearPreferences, ResetStore -> editor.clear()
        is ReplaceCustomDocument -> unsupportedOperation()
        is ReplaceProtoBytes -> unsupportedOperation()
      }
    } catch (error: StoreAdapterException) {
      throw error
    } catch (error: Exception) {
      throw preCommitFailure(error)
    }
  }

  private fun SharedPreferences.Editor.put(
    key: String,
    value: InspectorValue
  ) {
    when (value) {
      is StringValue -> putString(key, value.value)
      is IntValue -> putInt(key, value.value)
      is LongValue -> putLong(key, value.value)
      is FloatValue -> putFloat(key, Float.fromBits(value.rawBitsHex.toUInt(16).toInt()))
      is BooleanValue -> putBoolean(key, value.value)
      is StringSetValue -> putStringSet(key, LinkedHashSet(value.values))
      is DoubleValue, is BytesValue -> typeMismatch()
    }
  }

  private fun Any?.toInspectorValue(): InspectorValue =
    when (this) {
      null -> unsupported()
      is String -> StringValue(this)
      is Int -> IntValue(this)
      is Long -> LongValue(this)
      is Float -> FloatValue("%08x".format(toRawBits()))
      is Boolean -> BooleanValue(this)
      is Set<*> -> {
        val copied =
          map { element ->
            element as? String ?: unsupported()
          }
        StringSetValue(copied)
      }
      else -> unsupported()
    }

  private fun InspectorValue.copyForSharedPreferences(): InspectorValue =
    when (this) {
      is StringValue -> copy()
      is IntValue -> copy()
      is LongValue -> copy()
      is FloatValue -> copy()
      is BooleanValue -> copy()
      is StringSetValue -> StringSetValue(values.toList())
      is DoubleValue, is BytesValue -> typeMismatch()
    }

  private fun InspectorValue.sameSharedPreferencesType(other: InspectorValue): Boolean =
    this::class == other::class

  private fun rejectEncryptedStore() {
    if (backingFiles.containsEncryptedMarker()) encrypted()
  }

  private fun preCommitFailure(error: Throwable): StoreAdapterException =
    error as? StoreAdapterException
      ?: StoreAdapterException(
        ProtocolErrorCode.STORE_ERROR,
        retryable = true,
        operationStarted = false,
        cause = error
      )

  private fun unsupported(): Nothing =
    throw StoreAdapterException(
      ProtocolErrorCode.STORE_UNSUPPORTED,
      operationStarted = false
    )

  private fun encrypted(): Nothing =
    throw StoreSnapshotUnsupportedException(
      UnsupportedReason(
        code = "ENCRYPTED_SHARED_PREFERENCES",
        safeMessage = "暗号化されたSharedPreferencesは表示・編集できません。",
        retryable = false
      )
    )

  private fun typeMismatch(): Nothing =
    throw StoreAdapterException(
      ProtocolErrorCode.TYPE_MISMATCH,
      operationStarted = false
    )

  private fun keyNotFound(): Nothing =
    throw StoreAdapterException(
      ProtocolErrorCode.KEY_NOT_FOUND,
      operationStarted = false
    )

  private fun unsupportedOperation(): Nothing =
    throw StoreAdapterException(
      ProtocolErrorCode.STORE_UNSUPPORTED,
      operationStarted = false
    )

  private data class ReadSnapshot(
    val preferences: SharedPreferences,
    val values: Map<String, InspectorValue>,
    val snapshot: AdapterSnapshot
  )

  private sealed interface PreparedWrite {
    data class Conflict(
      val currentSnapshot: AdapterSnapshot
    ) : PreparedWrite

    data class Ready(
      val editor: SharedPreferences.Editor
    ) : PreparedWrite
  }

  private class CommitAttempt(
    private val editor: SharedPreferences.Editor,
    private val releaseLane: () -> Unit
  ) {
    private val lock = Any()
    private val finished = CountDownLatch(1)
    private val result = AtomicReference<Result<Boolean>?>(null)
    private var completed = false
    private var detached = false

    fun start() {
      Thread(
        {
          result.set(runCatching { editor.commit() })
          val release = synchronized(lock) {
            completed = true
            detached
          }
          finished.countDown()
          if (release) releaseLane()
        },
        COMMIT_THREAD_NAME
      ).apply {
        isDaemon = true
        start()
      }
    }

    suspend fun awaitUntil(deadlineNanos: Long): Result<Boolean>? {
      val remainingNanos = deadlineNanos - System.nanoTime()
      return if (
        remainingNanos > 0 &&
        runInterruptible {
          finished.await(remainingNanos, TimeUnit.NANOSECONDS)
        }
      ) {
        checkNotNull(result.get()) { "commit result is missing." }
      } else {
        null
      }
    }

    /**
     * Returns true when the worker already completed before the hand-off, so the caller must
     * release the lane. Otherwise the worker owns the eventual release.
     */
    fun detach(): Boolean = synchronized(lock) {
      detached = true
      completed
    }
  }

  private class StageAttempt<T>(
    private val block: () -> T,
    private val releaseLane: () -> Unit
  ) {
    private val lock = Any()
    private val finished = CountDownLatch(1)
    private val result = AtomicReference<Result<T>?>(null)
    private var completed = false
    private var detached = false

    fun start() {
      Thread(
        {
          result.set(runCatching(block))
          val release = synchronized(lock) {
            completed = true
            detached
          }
          finished.countDown()
          if (release) releaseLane()
        },
        STAGE_THREAD_NAME
      ).apply {
        isDaemon = true
        start()
      }
    }

    suspend fun awaitUntil(deadlineNanos: Long): Result<T>? {
      val remainingNanos = deadlineNanos - System.nanoTime()
      return if (
        remainingNanos > 0 &&
        runInterruptible {
          finished.await(remainingNanos, TimeUnit.NANOSECONDS)
        }
      ) {
        checkNotNull(result.get()) { "stage result is missing." }
      } else {
        null
      }
    }

    /**
     * Returns true when the worker already completed before the hand-off, so the caller must
     * release the lane. Otherwise the worker owns the eventual release.
     */
    fun detach(): Boolean = synchronized(lock) {
      detached = true
      completed
    }
  }

  private companion object {
    fun deadlineAfter(timeoutMillis: Long): Long =
      System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)

    const val DEFAULT_STAGE_TIMEOUT_MILLIS = 5_000L
    const val DEFAULT_COMMIT_WAIT_TIMEOUT_MILLIS = 15_000L
    const val COMMIT_THREAD_NAME = "DataStoreInspectorSharedPreferencesCommit"
    const val STAGE_THREAD_NAME = "DataStoreInspectorSharedPreferencesStage"
    val commitLane = Semaphore(1)
    val stageLane = Semaphore(1)
    val ENCRYPTED_MARKERS =
      setOf(
        "__androidx_security_crypto_encrypted_prefs_key_keyset__",
        "__androidx_security_crypto_encrypted_prefs_value_keyset__"
      )
  }
}
