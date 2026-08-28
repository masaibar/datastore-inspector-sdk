package com.masaibar.datastore.inspector.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class ProtocolVersion(
  val major: Int,
  val minor: Int
) {
  public companion object {
    public val CURRENT: ProtocolVersion = ProtocolVersion(1, 4)
  }
}

@InternalDataStoreInspectorProtocolApi
public object ProtocolCapabilities {
  public const val STORES_LIST: String = "stores.list"
  public const val SNAPSHOT_GET: String = "snapshot.get"
  public const val SCHEMA_GET: String = "schema.get"
  public const val PREFERENCES_WRITE: String = "preferences.write"
  public const val PREFERENCES_REPLACE: String = "preferences.replace"
  public const val PROTO_REPLACE: String = "proto.replace"
  public const val STORE_RESET: String = "store.reset"
  public const val SHARED_PREFERENCES_INSPECT: String = "shared_preferences.inspect"
  public const val CUSTOM_DOCUMENT_GET: String = "custom.document.get"
  public const val CUSTOM_DOCUMENT_REPLACE: String = "custom.document.replace"
  public const val STORE_CHANGES: String = "store.changes"

  public val REQUIRED_READ: Set<String> = setOf(STORES_LIST, SNAPSHOT_GET)
  public val CUSTOM_DOCUMENT: Set<String> =
    setOf(CUSTOM_DOCUMENT_GET, CUSTOM_DOCUMENT_REPLACE)
  public val INITIAL: Set<String> =
    setOf(
      STORES_LIST,
      SNAPSHOT_GET,
      SCHEMA_GET,
      PREFERENCES_WRITE,
      PREFERENCES_REPLACE,
      PROTO_REPLACE,
      STORE_RESET,
      SHARED_PREFERENCES_INSPECT,
      CUSTOM_DOCUMENT_GET,
      CUSTOM_DOCUMENT_REPLACE,
      STORE_CHANGES
    )
}

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class RequestEnvelope(
  val requestId: String,
  val payload: RequestPayload
)

@Serializable
@InternalDataStoreInspectorProtocolApi
public sealed interface RequestPayload

@Serializable
@SerialName("handshake")
@InternalDataStoreInspectorProtocolApi
public data class HandshakeRequest(
  val version: ProtocolVersion,
  val capabilities: Set<String>,
  val sessionId: String,
  val sessionToken: String
) : RequestPayload

@Serializable
@SerialName("list_stores")
@InternalDataStoreInspectorProtocolApi
public data object ListStoresRequest : RequestPayload

@Serializable
@SerialName("get_snapshot")
@InternalDataStoreInspectorProtocolApi
public data class GetSnapshotRequest(val storeId: String) : RequestPayload

@Serializable
@SerialName("get_schema")
@InternalDataStoreInspectorProtocolApi
public data class GetSchemaRequest(val schemaId: String) : RequestPayload

@Serializable
@SerialName("write")
@InternalDataStoreInspectorProtocolApi
public data class WriteRequest(val write: WritePayload) : RequestPayload

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class ResponseEnvelope(
  val requestId: String,
  val payload: ResponsePayload
)

@Serializable
@InternalDataStoreInspectorProtocolApi
public sealed interface ResponsePayload

@Serializable
@SerialName("handshake_accepted")
@InternalDataStoreInspectorProtocolApi
public data class HandshakeResponse(
  val version: ProtocolVersion,
  val negotiatedCapabilities: Set<String>,
  val sessionId: String
) : ResponsePayload

@Serializable
@SerialName("stores_listed")
@InternalDataStoreInspectorProtocolApi
public data class ListStoresResult(val stores: List<StoreDescriptor>) : ResponsePayload

@Serializable
@SerialName("snapshot_result")
@InternalDataStoreInspectorProtocolApi
public data class SnapshotResultResponse(val result: GetSnapshotResult) : ResponsePayload

@Serializable
@SerialName("schema_result")
@InternalDataStoreInspectorProtocolApi
public data class GetSchemaResult(
  val schemaId: String,
  val descriptorDigestSha256: String,
  @Serializable(with = Base64ByteArraySerializer::class)
  val descriptorBytes: ByteArray
) : ResponsePayload {
  override fun equals(other: Any?): Boolean =
    other is GetSchemaResult &&
      schemaId == other.schemaId &&
      descriptorDigestSha256 == other.descriptorDigestSha256 &&
      descriptorBytes.contentEquals(other.descriptorBytes)

  override fun hashCode(): Int =
    31 * (31 * schemaId.hashCode() + descriptorDigestSha256.hashCode()) +
      descriptorBytes.contentHashCode()
}

@Serializable
@SerialName("write_result")
@InternalDataStoreInspectorProtocolApi
public data class WriteResultResponse(val result: WriteResult) : ResponsePayload

@Serializable
@SerialName("error")
@InternalDataStoreInspectorProtocolApi
public data class ErrorResponse(
  val code: ProtocolErrorCode,
  val safeMessage: String,
  val retryable: Boolean,
  val operationStarted: Boolean? = null
) : ResponsePayload

@Serializable(with = StoreChangeKindSerializer::class)
@InternalDataStoreInspectorProtocolApi
public enum class StoreChangeKind(public val wireName: String) {
  BASELINE("baseline"),
  CHANGE("change"),
  UNKNOWN("unknown")
}

@InternalDataStoreInspectorProtocolApi
public object StoreChangeKindSerializer : StableEnumSerializer<StoreChangeKind>(
  serialName = "StoreChangeKind",
  values = StoreChangeKind.entries,
  wireName = StoreChangeKind::wireName,
  unknown = StoreChangeKind.UNKNOWN
)

@Serializable(with = StoreChangeBoundaryReasonSerializer::class)
@InternalDataStoreInspectorProtocolApi
public enum class StoreChangeBoundaryReason(public val wireName: String) {
  BACKPRESSURE("backpressure"),
  OVERSIZED_STATE("oversized_state"),
  OBSERVATION_FAILED("observation_failed"),
  STORE_REMOVED("store_removed"),
  UNKNOWN("unknown")
}

@InternalDataStoreInspectorProtocolApi
public object StoreChangeBoundaryReasonSerializer : StableEnumSerializer<StoreChangeBoundaryReason>(
  serialName = "StoreChangeBoundaryReason",
  values = StoreChangeBoundaryReason.entries,
  wireName = StoreChangeBoundaryReason::wireName,
  unknown = StoreChangeBoundaryReason.UNKNOWN
)

/**
 * Authenticated connectionでRuntimeが観測した、その時点の1 Store全体のcanonical stateです。
 * revision/content tokenを持たず、write leaseとして再利用できません。
 */
@Serializable
@SerialName("store_change")
@InternalDataStoreInspectorProtocolApi
public data class StoreChangeNotification(
  val subscriptionGeneration: Long,
  val storeGeneration: Long,
  val sequence: Long,
  val observedAtEpochMillis: Long,
  val kind: StoreChangeKind,
  val store: StoreDescriptor,
  val snapshot: SnapshotPayload,
  val correlationId: String? = null
) : ResponsePayload

/** 値を含めず、欠落・oversize・Store lifecycleの境界だけを伝える通知です。 */
@Serializable
@SerialName("store_change_boundary")
@InternalDataStoreInspectorProtocolApi
public data class StoreChangeBoundaryNotification(
  val subscriptionGeneration: Long,
  val observedAtEpochMillis: Long,
  val reason: StoreChangeBoundaryReason,
  val storeId: String? = null,
  val logicalStoreId: String? = null,
  val storeGeneration: Long? = null,
  val sequence: Long? = null
) : ResponsePayload

@Serializable
@InternalDataStoreInspectorProtocolApi
public enum class ProtocolErrorCode {
  @SerialName("AUTH_FAILED") AUTH_FAILED,

  @SerialName("VERSION_MISMATCH") VERSION_MISMATCH,

  @SerialName("UNSUPPORTED_CAPABILITY") UNSUPPORTED_CAPABILITY,

  @SerialName("INVALID_REQUEST") INVALID_REQUEST,

  @SerialName("PAYLOAD_TOO_LARGE") PAYLOAD_TOO_LARGE,

  @SerialName("TYPE_MISMATCH") TYPE_MISMATCH,

  @SerialName("KEY_NOT_FOUND") KEY_NOT_FOUND,

  @SerialName("STORE_NOT_FOUND") STORE_NOT_FOUND,

  @SerialName("STORE_NOT_READY") STORE_NOT_READY,

  @SerialName("STORE_UNSUPPORTED") STORE_UNSUPPORTED,

  @SerialName("STORE_ERROR") STORE_ERROR,

  @SerialName("STORE_CATALOG_LIMIT") STORE_CATALOG_LIMIT,

  @SerialName("STORE_NAME_UNSUPPORTED") STORE_NAME_UNSUPPORTED,

  @SerialName("PREFERENCES_SNAPSHOT_LIMIT") PREFERENCES_SNAPSHOT_LIMIT,

  @SerialName("SCHEMA_NOT_FOUND") SCHEMA_NOT_FOUND,

  @SerialName("SCHEMA_MISMATCH") SCHEMA_MISMATCH,

  @SerialName("STALE_SNAPSHOT") STALE_SNAPSHOT,

  @SerialName("CUSTOM_DOCUMENT_INVALID") CUSTOM_DOCUMENT_INVALID,

  @SerialName("CUSTOM_PROJECTION_MISMATCH") CUSTOM_PROJECTION_MISMATCH,

  @SerialName("CUSTOM_ACTUAL_WRITE_MISMATCH") CUSTOM_ACTUAL_WRITE_MISMATCH,

  @SerialName("CUSTOM_OPERATION_TIMEOUT") CUSTOM_OPERATION_TIMEOUT,

  @SerialName("BUSY") BUSY,

  @SerialName("INTERNAL_ERROR") INTERNAL_ERROR
}

@Serializable(with = StoreKindSerializer::class)
@InternalDataStoreInspectorProtocolApi
public enum class StoreKind(public val wireName: String) {
  PREFERENCES("preferences"),
  PROTO("proto"),
  CUSTOM("custom"),
  UNKNOWN("unknown")
}

@Serializable(with = StoreStatusSerializer::class)
@InternalDataStoreInspectorProtocolApi
public enum class StoreStatus(public val wireName: String) {
  DECLARED("declared"),
  RESOLVED("resolved"),
  UNSUPPORTED("unsupported"),
  ERROR("error"),
  UNKNOWN("unknown")
}

@InternalDataStoreInspectorProtocolApi
public object StoreKindSerializer : StableEnumSerializer<StoreKind>(
  serialName = "StoreKind",
  values = StoreKind.entries,
  wireName = StoreKind::wireName,
  unknown = StoreKind.UNKNOWN
)

@InternalDataStoreInspectorProtocolApi
public object StoreStatusSerializer : StableEnumSerializer<StoreStatus>(
  serialName = "StoreStatus",
  values = StoreStatus.entries,
  wireName = StoreStatus::wireName,
  unknown = StoreStatus.UNKNOWN
)

@Serializable(with = StoreBackendSerializer::class)
@InternalDataStoreInspectorProtocolApi
public enum class StoreBackend(public val wireName: String) {
  DATASTORE("datastore"),
  SHARED_PREFERENCES("shared_preferences"),
  UNKNOWN("unknown")
}

@Serializable(with = StorageScopeSerializer::class)
@InternalDataStoreInspectorProtocolApi
public enum class StorageScope(public val wireName: String) {
  CREDENTIAL_PROTECTED("credential_protected"),
  DEVICE_PROTECTED("device_protected"),
  UNSPECIFIED("unspecified"),
  UNKNOWN("unknown")
}

@Serializable(with = WriteConsistencySerializer::class)
@InternalDataStoreInspectorProtocolApi
public enum class WriteConsistency(public val wireName: String) {
  ATOMIC_TRANSACTIONAL("atomic_transactional"),
  BEST_EFFORT_NON_ATOMIC("best_effort_non_atomic"),
  UNKNOWN("unknown")
}

@InternalDataStoreInspectorProtocolApi
public object StoreBackendSerializer : StableEnumSerializer<StoreBackend>(
  serialName = "StoreBackend",
  values = StoreBackend.entries,
  wireName = StoreBackend::wireName,
  unknown = StoreBackend.UNKNOWN
)

@InternalDataStoreInspectorProtocolApi
public object StorageScopeSerializer : StableEnumSerializer<StorageScope>(
  serialName = "StorageScope",
  values = StorageScope.entries,
  wireName = StorageScope::wireName,
  unknown = StorageScope.UNKNOWN
)

@InternalDataStoreInspectorProtocolApi
public object WriteConsistencySerializer : StableEnumSerializer<WriteConsistency>(
  serialName = "WriteConsistency",
  values = WriteConsistency.entries,
  wireName = WriteConsistency::wireName,
  unknown = WriteConsistency.UNKNOWN
)

@InternalDataStoreInspectorProtocolApi
public object PreferenceValueTypeIds {
  public const val STRING: String = "string"
  public const val INT: String = "int"
  public const val LONG: String = "long"
  public const val FLOAT: String = "float"
  public const val DOUBLE: String = "double"
  public const val BOOLEAN: String = "boolean"
  public const val STRING_SET: String = "string_set"
  public const val BYTES: String = "bytes"

  public val DATASTORE: Set<String> =
    setOf(STRING, INT, LONG, FLOAT, DOUBLE, BOOLEAN, STRING_SET, BYTES)
  public val SHARED_PREFERENCES: Set<String> =
    setOf(STRING, INT, LONG, FLOAT, BOOLEAN, STRING_SET)
}

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class StoreSemantics(
  val backend: StoreBackend,
  val storageScope: StorageScope,
  val supportedValueTypes: Set<String>,
  val writeConsistency: WriteConsistency
)

@InternalDataStoreInspectorProtocolApi
public abstract class StableEnumSerializer<T : Enum<T>>(
  serialName: String,
  private val values: List<T>,
  private val wireName: (T) -> String,
  private val unknown: T
) : KSerializer<T> {
  final override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor(serialName, PrimitiveKind.STRING)

  final override fun serialize(encoder: Encoder, value: T) {
    encoder.encodeString(wireName(value))
  }

  final override fun deserialize(decoder: Decoder): T {
    val encoded = decoder.decodeString()
    return values.firstOrNull { wireName(it) == encoded } ?: unknown
  }
}

@Serializable
@JvmInline
@InternalDataStoreInspectorProtocolApi
public value class StoreCapability(public val id: String)

@Serializable
@JvmInline
@InternalDataStoreInspectorProtocolApi
public value class NodeCapability(public val id: String)

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class StoreDescriptor(
  val id: String,
  val name: String,
  val fileName: String?,
  val kind: StoreKind,
  val status: StoreStatus,
  val capabilities: Set<StoreCapability>,
  val schema: ProtoSchemaRef? = null,
  val unsupportedReason: UnsupportedReason? = null,
  val semantics: StoreSemantics? = null,
  /** process再起動後も同じ論理Storeを識別する、pathを含まないopaque IDです。 */
  val logicalId: String? = null,
  /** 同じlogical StoreのRuntime内incarnationです。Store change対応時は必須です。 */
  val generation: Long? = null
)

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class UnsupportedReason(
  val code: String,
  val safeMessage: String,
  val retryable: Boolean
) {
  public constructor(
    code: CustomStoreReasonCode,
    safeMessage: String,
    retryable: Boolean
  ) : this(code.wireName, safeMessage, retryable)
}

@Serializable
@InternalDataStoreInspectorProtocolApi
public sealed interface GetSnapshotResult

@Serializable
@SerialName("resolved")
@InternalDataStoreInspectorProtocolApi
public data class ResolvedSnapshotResult(
  val snapshot: ResolvedStoreSnapshot
) : GetSnapshotResult

@Serializable
@SerialName("unsupported_info")
@InternalDataStoreInspectorProtocolApi
public data class UnsupportedSnapshotInfo(
  val storeId: String,
  val reason: UnsupportedReason
) : GetSnapshotResult

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class ResolvedStoreSnapshot(
  val storeId: String,
  val revision: Long,
  val contentToken: String,
  val payload: SnapshotPayload,
  /** snapshotを発行したStore incarnationです。旧Runtimeからの応答ではnullです。 */
  val storeGeneration: Long? = null
)

@Serializable
@InternalDataStoreInspectorProtocolApi
public sealed interface SnapshotPayload

@Serializable
@SerialName("preferences_tree")
@InternalDataStoreInspectorProtocolApi
public data class PreferencesTree(val root: InspectorNode) : SnapshotPayload

@Serializable
@SerialName("proto_raw")
@InternalDataStoreInspectorProtocolApi
public data class ProtoRaw(
  val schema: ProtoSchemaRef,
  @Serializable(with = Base64ByteArraySerializer::class)
  val valueBytes: ByteArray
) : SnapshotPayload {
  override fun equals(other: Any?): Boolean =
    other is ProtoRaw && schema == other.schema && valueBytes.contentEquals(other.valueBytes)

  override fun hashCode(): Int = 31 * schema.hashCode() + valueBytes.contentHashCode()
}

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class ProtoSchemaRef(
  val schemaId: String,
  val rootMessageFullName: String,
  val descriptorDigestSha256: String
)

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class InspectorNode(
  val path: List<PathSegment>,
  val name: String,
  val type: InspectorValueType,
  val value: InspectorValue?,
  val presence: Presence,
  val children: List<InspectorNode>,
  val capabilities: Set<NodeCapability>
)

@Serializable
@InternalDataStoreInspectorProtocolApi
public sealed interface PathSegment

@Serializable
@SerialName("preference_key")
@InternalDataStoreInspectorProtocolApi
public data class PreferenceKey(val key: String) : PathSegment

@Serializable
@InternalDataStoreInspectorProtocolApi
public enum class InspectorValueType {
  @SerialName("root") ROOT,

  @SerialName("string") STRING,

  @SerialName("int") INT,

  @SerialName("long") LONG,

  @SerialName("float") FLOAT,

  @SerialName("double") DOUBLE,

  @SerialName("boolean") BOOLEAN,

  @SerialName("string_set") STRING_SET,

  @SerialName("bytes") BYTES
}

@Serializable
@InternalDataStoreInspectorProtocolApi
public enum class Presence {
  @SerialName("present") PRESENT,

  @SerialName("not_applicable") NOT_APPLICABLE
}

@Serializable
@InternalDataStoreInspectorProtocolApi
public sealed interface InspectorValue

@Serializable
@SerialName("string")
@InternalDataStoreInspectorProtocolApi
public data class StringValue(val value: String) : InspectorValue

@Serializable
@SerialName("int")
@InternalDataStoreInspectorProtocolApi
public data class IntValue(val value: Int) : InspectorValue

@Serializable
@SerialName("long")
@InternalDataStoreInspectorProtocolApi
public data class LongValue(val value: Long) : InspectorValue

@Serializable
@SerialName("float")
@InternalDataStoreInspectorProtocolApi
public data class FloatValue(val rawBitsHex: String) : InspectorValue

@Serializable
@SerialName("double")
@InternalDataStoreInspectorProtocolApi
public data class DoubleValue(val rawBitsHex: String) : InspectorValue

@Serializable
@SerialName("boolean")
@InternalDataStoreInspectorProtocolApi
public data class BooleanValue(val value: Boolean) : InspectorValue

@Serializable
@SerialName("string_set")
@InternalDataStoreInspectorProtocolApi
public data class StringSetValue(val values: List<String>) : InspectorValue

@Serializable
@SerialName("bytes")
@InternalDataStoreInspectorProtocolApi
public data class BytesValue(
  @Serializable(with = Base64ByteArraySerializer::class)
  val value: ByteArray
) : InspectorValue {
  override fun equals(other: Any?): Boolean =
    other is BytesValue && value.contentEquals(other.value)

  override fun hashCode(): Int = value.contentHashCode()
}

@Serializable
@InternalDataStoreInspectorProtocolApi
public sealed interface InspectorMutation

@Serializable
@SerialName("put")
@InternalDataStoreInspectorProtocolApi
public data class PutPreference(
  val key: String,
  val value: InspectorValue
) : InspectorMutation

@Serializable
@SerialName("delete")
@InternalDataStoreInspectorProtocolApi
public data class DeletePreference(val key: String) : InspectorMutation

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class PreferenceEntry(
  val key: String,
  val value: InspectorValue
)

@Serializable
@InternalDataStoreInspectorProtocolApi
public data class WritePayload(
  val storeId: String,
  val expectedRevision: Long,
  val expectedContentToken: String,
  val operation: WriteOperation,
  val correlationId: String? = null
)

@Serializable
@InternalDataStoreInspectorProtocolApi
public sealed interface WriteOperation

@Serializable
@SerialName("mutate_preferences")
@InternalDataStoreInspectorProtocolApi
public data class MutatePreferences(val mutation: InspectorMutation) : WriteOperation

@Serializable
@SerialName("replace_preferences")
@InternalDataStoreInspectorProtocolApi
public data class ReplacePreferences(val entries: List<PreferenceEntry>) : WriteOperation

@Serializable
@SerialName("replace_proto_bytes")
@InternalDataStoreInspectorProtocolApi
public data class ReplaceProtoBytes(
  val schema: ProtoSchemaRef,
  @Serializable(with = Base64ByteArraySerializer::class)
  val valueBytes: ByteArray
) : WriteOperation {
  override fun equals(other: Any?): Boolean =
    other is ReplaceProtoBytes &&
      schema == other.schema &&
      valueBytes.contentEquals(other.valueBytes)

  override fun hashCode(): Int = 31 * schema.hashCode() + valueBytes.contentHashCode()
}

@Serializable
@SerialName("clear_preferences")
@InternalDataStoreInspectorProtocolApi
public data object ClearPreferences : WriteOperation

@Serializable
@SerialName("reset_store")
@InternalDataStoreInspectorProtocolApi
public data object ResetStore : WriteOperation

@Serializable
@InternalDataStoreInspectorProtocolApi
public sealed interface WriteResult

@Serializable
@SerialName("success")
@InternalDataStoreInspectorProtocolApi
public data class WriteSuccess(val snapshot: ResolvedStoreSnapshot) : WriteResult

@Serializable
@SerialName("conflict")
@InternalDataStoreInspectorProtocolApi
public data class WriteConflict(val currentSnapshot: ResolvedStoreSnapshot) : WriteResult

@Serializable
@SerialName("applied_snapshot_unavailable")
@InternalDataStoreInspectorProtocolApi
public data class WriteAppliedSnapshotUnavailable(
  val storeId: String
) : WriteResult

@Serializable(with = WriteOutcomeReasonSerializer::class)
@InternalDataStoreInspectorProtocolApi
public enum class WriteOutcomeReason(public val wireName: String) {
  PERSISTENCE_NOT_CONFIRMED("persistence_not_confirmed"),
  UNKNOWN("unknown")
}

@InternalDataStoreInspectorProtocolApi
public object WriteOutcomeReasonSerializer : StableEnumSerializer<WriteOutcomeReason>(
  serialName = "WriteOutcomeReason",
  values = WriteOutcomeReason.entries,
  wireName = WriteOutcomeReason::wireName,
  unknown = WriteOutcomeReason.UNKNOWN
)

@Serializable
@SerialName("outcome_unknown")
@InternalDataStoreInspectorProtocolApi
public data class WriteOutcomeUnknown(
  val reason: WriteOutcomeReason,
  val currentSnapshot: ResolvedStoreSnapshot? = null
) : WriteResult
