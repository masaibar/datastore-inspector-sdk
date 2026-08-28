package com.masaibar.datastore.inspector.runtime.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
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
import com.masaibar.datastore.inspector.protocol.ReplacePreferences
import com.masaibar.datastore.inspector.protocol.ResetStore
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.StringSetValue
import com.masaibar.datastore.inspector.protocol.StringValue
import com.masaibar.datastore.inspector.protocol.WriteOperation
import com.masaibar.datastore.inspector.runtime.core.AdapterObservation
import com.masaibar.datastore.inspector.runtime.core.AdapterResolution
import com.masaibar.datastore.inspector.runtime.core.AdapterSnapshot
import com.masaibar.datastore.inspector.runtime.core.AdapterWriteResult
import com.masaibar.datastore.inspector.runtime.core.InternalDataStoreInspectorApi
import com.masaibar.datastore.inspector.runtime.core.PreferencesSnapshotEncoder
import com.masaibar.datastore.inspector.runtime.core.StoreAdapter
import com.masaibar.datastore.inspector.runtime.core.StoreAdapterFactory
import com.masaibar.datastore.inspector.runtime.core.StoreCandidate
import com.masaibar.datastore.inspector.runtime.core.StoreSnapshotObserver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@InternalDataStoreInspectorApi
public class PreferencesStoreAdapterFactory : StoreAdapterFactory {
  override val providerId: String = "preferences-v1"

  @Suppress("UNCHECKED_CAST")
  override fun create(candidate: StoreCandidate): AdapterResolution {
    if (candidate.declaration.kindHint != StoreKind.PREFERENCES) return AdapterResolution.NotApplicable
    val store = candidate.instance as? DataStore<*> ?: return AdapterResolution.Error("DataStore instanceではありません。")
    return AdapterResolution.Resolved(PreferencesStoreAdapter(store as DataStore<Preferences>))
  }
}

internal class PreferencesStoreAdapter(
  private val store: DataStore<Preferences>
) : StoreAdapter {
  override val kind: StoreKind = StoreKind.PREFERENCES
  override val capabilities: Set<StoreCapability> = setOf(
    StoreCapability(ProtocolCapabilities.SNAPSHOT_GET),
    StoreCapability(ProtocolCapabilities.PREFERENCES_WRITE),
    StoreCapability(ProtocolCapabilities.PREFERENCES_REPLACE),
    StoreCapability(ProtocolCapabilities.STORE_RESET),
    StoreCapability(ProtocolCapabilities.STORE_CHANGES)
  )
  override val schema = null

  override suspend fun snapshot(): AdapterSnapshot = encode(store.data.first())

  override suspend fun write(
    expectedFingerprint: String,
    operation: WriteOperation
  ): AdapterWriteResult {
    var conflict = false
    val updated = store.updateData { current ->
      if (encode(current).fingerprint != expectedFingerprint) {
        conflict = true
        current
      } else {
        applyOperation(current.toMutablePreferences(), operation).toPreferences()
      }
    }
    val snapshot = encode(updated)
    return if (conflict) AdapterWriteResult.Conflict(snapshot) else AdapterWriteResult.Applied(snapshot)
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
    val job =
      scope.launch {
        try {
          store.data.collect { value ->
            publish(AdapterObservation.Snapshot(encode(value)))
          }
        } catch (error: CancellationException) {
          throw error
        } catch (_: Exception) {
          publish(AdapterObservation.Failure(ProtocolErrorCode.STORE_ERROR))
        }
      }
    return AutoCloseable {
      synchronized(observerLock) { active = false }
      job.cancel()
    }
  }

  private fun applyOperation(
    preferences: MutablePreferences,
    operation: WriteOperation
  ): MutablePreferences {
    when (operation) {
      is MutatePreferences -> when (val mutation = operation.mutation) {
        is PutPreference -> put(preferences, mutation.key, mutation.value)
        is DeletePreference -> {
          val existing = preferences.asMap().keys.firstOrNull { it.name == mutation.key }
            ?: throw IllegalArgumentException("Preferences keyがありません。")
          preferences.remove(existing)
        }
      }
      is ReplacePreferences -> {
        preferences.clear()
        operation.entries.forEach { entry ->
          put(preferences, entry.key, entry.value)
        }
      }
      ClearPreferences, ResetStore -> preferences.clear()
      else -> throw IllegalArgumentException("Preferencesでは実行できない操作です。")
    }
    return preferences
  }

  private fun put(preferences: MutablePreferences, keyName: String, value: InspectorValue) {
    val existing = preferences.asMap().entries.firstOrNull { it.key.name == keyName }?.value
    if (existing != null && !sameType(existing, value)) {
      throw IllegalArgumentException("同名keyの型が一致しません。")
    }
    when (value) {
      is StringValue -> preferences[stringPreferencesKey(keyName)] = value.value
      is IntValue -> preferences[intPreferencesKey(keyName)] = value.value
      is LongValue -> preferences[longPreferencesKey(keyName)] = value.value
      is FloatValue -> preferences[floatPreferencesKey(keyName)] = value.rawBitsHex.toUInt(16).toInt().let(Float::fromBits)
      is DoubleValue -> preferences[doublePreferencesKey(keyName)] = value.rawBitsHex.toULong(16).toLong().let(Double::fromBits)
      is BooleanValue -> preferences[booleanPreferencesKey(keyName)] = value.value
      is StringSetValue -> preferences[stringSetPreferencesKey(keyName)] = value.values.toSet()
      is BytesValue -> preferences[byteArrayPreferencesKey(keyName)] = value.value.copyOf()
    }
  }

  private fun sameType(existing: Any, value: InspectorValue): Boolean = when (value) {
    is StringValue -> existing is String
    is IntValue -> existing is Int
    is LongValue -> existing is Long
    is FloatValue -> existing is Float
    is DoubleValue -> existing is Double
    is BooleanValue -> existing is Boolean
    is StringSetValue -> existing is Set<*>
    is BytesValue -> existing is ByteArray
  }

  private fun encode(preferences: Preferences): AdapterSnapshot =
    PreferencesSnapshotEncoder.encode(
      preferences.asMap().entries.associate { (key, value) ->
        key.name to value.toInspectorValue()
      }
    )

  private fun Any.toInspectorValue(): InspectorValue =
    when (this) {
      is String -> StringValue(this)
      is Int -> IntValue(this)
      is Long -> LongValue(this)
      is Float -> FloatValue("%08x".format(toRawBits()))
      is Double -> DoubleValue("%016x".format(toRawBits()))
      is Boolean -> BooleanValue(this)
      is Set<*> -> StringSetValue(map { it as String })
      is ByteArray -> BytesValue(copyOf())
      else -> throw IllegalArgumentException("未対応のPreferences value型です。")
    }
}
