package com.masaibar.datastore.inspector.runtime.protobuf

import android.content.Context
import androidx.datastore.core.DataStore
import com.google.protobuf.MessageLite
import com.masaibar.datastore.inspector.protocol.GetSchemaResult
import com.masaibar.datastore.inspector.protocol.ProtoRaw
import com.masaibar.datastore.inspector.protocol.ProtoSchemaRef
import com.masaibar.datastore.inspector.protocol.ProtocolCapabilities
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ReplaceProtoBytes
import com.masaibar.datastore.inspector.protocol.ResetStore
import com.masaibar.datastore.inspector.protocol.StoreCapability
import com.masaibar.datastore.inspector.protocol.StoreKind
import com.masaibar.datastore.inspector.protocol.UnsupportedReason
import com.masaibar.datastore.inspector.protocol.WriteOperation
import com.masaibar.datastore.inspector.runtime.core.AdapterObservation
import com.masaibar.datastore.inspector.runtime.core.AdapterResolution
import com.masaibar.datastore.inspector.runtime.core.AdapterSnapshot
import com.masaibar.datastore.inspector.runtime.core.AdapterWriteResult
import com.masaibar.datastore.inspector.runtime.core.StoreAdapter
import com.masaibar.datastore.inspector.runtime.core.StoreAdapterFactory
import com.masaibar.datastore.inspector.runtime.core.StoreCandidate
import com.masaibar.datastore.inspector.runtime.core.StoreSnapshotObserver
import com.masaibar.datastore.inspector.runtime.core.sha256
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

public class ProtobufStoreAdapterFactory(
  private val schemaIndex: VerifiedSchemaIndex? = null
) : StoreAdapterFactory {
  override val providerId: String = "protobuf-lite-v1"

  override fun initialize(context: Context): StoreAdapterFactory {
    if (schemaIndex != null) return this
    val loaded = runCatching {
      SchemaIndexConsumer.load(
        context.assets.open("datastore-inspector/schema-index.json").use { it.readBytes() }
      ) { path ->
        runCatching { context.assets.open(path).use { it.readBytes() } }.getOrNull()
      }
    }.getOrNull()
    return ProtobufStoreAdapterFactory(loaded)
  }

  @Suppress("UNCHECKED_CAST")
  override fun create(candidate: StoreCandidate): AdapterResolution {
    if (candidate.declaration.kindHint != StoreKind.PROTO) return AdapterResolution.NotApplicable
    val index = schemaIndex ?: return AdapterResolution.Unsupported(
      UnsupportedReason("SCHEMA_NOT_FOUND", "対応するProto schemaがありません。", false)
    )
    val entry = index.entries.firstOrNull {
      it.generatedJvmClassName ==
        (candidate.declaration.valueClassName ?: candidate.declaration.owner)
    } ?: return AdapterResolution.Unsupported(
      UnsupportedReason("SCHEMA_NOT_FOUND", "対応するProto schemaがありません。", false)
    )
    val store = candidate.instance as? DataStore<*> ?: return AdapterResolution.Error("DataStore instanceではありません。")
    return AdapterResolution.Resolved(
      ProtobufStoreAdapter(store as DataStore<MessageLite>, entry)
    )
  }
}

public class ProtobufStoreAdapter(
  private val store: DataStore<MessageLite>,
  private val schemaEntry: VerifiedSchemaEntry
) : StoreAdapter {
  override val kind: StoreKind = StoreKind.PROTO
  override val capabilities: Set<StoreCapability> = setOf(
    StoreCapability(ProtocolCapabilities.SNAPSHOT_GET),
    StoreCapability(ProtocolCapabilities.SCHEMA_GET),
    StoreCapability(ProtocolCapabilities.PROTO_REPLACE),
    StoreCapability(ProtocolCapabilities.STORE_RESET),
    StoreCapability(ProtocolCapabilities.STORE_CHANGES)
  )
  override val schema: ProtoSchemaRef = ProtoSchemaRef(
    schemaId = schemaEntry.descriptorDigestSha256,
    rootMessageFullName = schemaEntry.rootMessageFullName,
    descriptorDigestSha256 = schemaEntry.descriptorDigestSha256
  )

  override suspend fun snapshot(): AdapterSnapshot = encode(store.data.first())

  override suspend fun write(
    expectedFingerprint: String,
    operation: WriteOperation
  ): AdapterWriteResult {
    var conflict = false
    val updated = store.updateData { current ->
      if (sha256(current.toByteArray()) != expectedFingerprint) {
        conflict = true
        current
      } else {
        when (operation) {
          is ReplaceProtoBytes -> {
            require(operation.schema == schema) { "Proto schemaが一致しません。" }
            @Suppress("UNCHECKED_CAST")
            current.parserForType.parseFrom(operation.valueBytes) as MessageLite
          }
          ResetStore -> current.defaultInstanceForType
          else -> throw IllegalArgumentException("Protoでは実行できない操作です。")
        }
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

  override fun schema(schemaId: String): GetSchemaResult? =
    if (schemaId == schema.schemaId) {
      GetSchemaResult(
        schemaId = schema.schemaId,
        descriptorDigestSha256 = schema.descriptorDigestSha256,
        descriptorBytes = schemaEntry.descriptorBytes.copyOf()
      )
    } else {
      null
    }

  private fun encode(value: MessageLite): AdapterSnapshot {
    require(value.javaClass.name == schemaEntry.generatedJvmClassName) {
      "Proto value型がschema indexと一致しません。"
    }
    val bytes = value.toByteArray()
    return AdapterSnapshot(sha256(bytes), ProtoRaw(schema, bytes))
  }
}
