package com.masaibar.datastore.inspector.protocol

@InternalDataStoreInspectorProtocolApi
public data class NegotiatedProtocol(
  val version: ProtocolVersion,
  val capabilities: Set<String>
)

internal enum class ResponseValidationDirection {
  ENCODE,
  DECODE
}

@InternalDataStoreInspectorProtocolApi
public object ProtocolNegotiation {
  public fun negotiate(
    local: ProtocolVersion,
    localCapabilities: Set<String>,
    remote: ProtocolVersion,
    remoteCapabilities: Set<String>
  ): NegotiatedProtocol {
    if (local.major != remote.major) {
      throw ProtocolException(
        ProtocolFailureKind.VERSION_MISMATCH,
        "Protocol major versionが一致しません。"
      )
    }
    val negotiatedVersion =
      ProtocolVersion(local.major, minOf(local.minor, remote.minor))
    val common =
      (localCapabilities intersect remoteCapabilities)
        .filterTo(linkedSetOf()) { capability ->
          capability.isAvailableAt(negotiatedVersion)
        }
    val missing = ProtocolCapabilities.REQUIRED_READ - common
    if (missing.isNotEmpty()) {
      throw ProtocolException(
        ProtocolFailureKind.MISSING_CAPABILITY,
        "必須read capabilityが不足しています: ${missing.sorted()}"
      )
    }
    return NegotiatedProtocol(
      version = negotiatedVersion,
      capabilities = common
    )
  }

  private fun String.isAvailableAt(version: ProtocolVersion): Boolean =
    when (this) {
      ProtocolCapabilities.SHARED_PREFERENCES_INSPECT ->
        version.major > 1 || (version.major == 1 && version.minor >= 1)
      ProtocolCapabilities.CUSTOM_DOCUMENT_GET,
      ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE
      ->
        version.major > 1 || (version.major == 1 && version.minor >= 2)
      ProtocolCapabilities.PREFERENCES_REPLACE ->
        version.major > 1 || (version.major == 1 && version.minor >= 3)
      ProtocolCapabilities.STORE_CHANGES ->
        version.major > 1 || (version.major == 1 && version.minor >= 4)
      else -> true
    }
}

@InternalDataStoreInspectorProtocolApi
public object ProtocolValidator {
  public fun validate(envelope: RequestEnvelope) {
    validateIdentifier(envelope.requestId, ProtocolLimits.MAX_REQUEST_ID_UTF8_BYTES, "requestId")
    when (val payload = envelope.payload) {
      is HandshakeRequest -> validateHandshake(payload)
      is GetSnapshotRequest -> requireNotBlank(payload.storeId, "storeId")
      is GetSchemaRequest -> requireNotBlank(payload.schemaId, "schemaId")
      is WriteRequest -> validate(payload.write)
      ListStoresRequest -> Unit
    }
  }

  public fun validate(envelope: ResponseEnvelope) {
    validate(envelope, ResponseValidationDirection.DECODE)
  }

  internal fun validate(
    envelope: ResponseEnvelope,
    direction: ResponseValidationDirection
  ) {
    validateIdentifier(envelope.requestId, ProtocolLimits.MAX_REQUEST_ID_UTF8_BYTES, "requestId")
    when (val payload = envelope.payload) {
      is HandshakeResponse -> {
        requireModel(payload.version.major >= 0 && payload.version.minor >= 0) {
          "Protocol versionは0以上である必要があります。"
        }
        validateCapabilities(payload.negotiatedCapabilities)
        validateIdentifier(payload.sessionId, ProtocolLimits.MAX_SESSION_ID_UTF8_BYTES, "sessionId")
      }
      is ListStoresResult -> payload.stores.forEach(::validate)
      is SnapshotResultResponse -> validate(payload.result, direction)
      is GetSchemaResult -> {
        requireNotBlank(payload.schemaId, "schemaId")
        validateDigest(payload.descriptorDigestSha256)
        requireModel(payload.descriptorBytes.size <= ProtocolLimits.DESCRIPTOR_BYTES) {
          "descriptor bundleが8 MiBを超えています。"
        }
      }
      is WriteResultResponse ->
        when (val result = payload.result) {
          is WriteSuccess -> validate(result.snapshot, direction)
          is WriteConflict -> validate(result.currentSnapshot, direction)
          is WriteAppliedSnapshotUnavailable -> requireNotBlank(result.storeId, "storeId")
          is WriteOutcomeUnknown ->
            result.currentSnapshot?.let { snapshot ->
              validate(snapshot, direction)
            }
        }
      is StoreChangeNotification -> validate(payload, direction)
      is StoreChangeBoundaryNotification -> validate(payload, direction)
      is ErrorResponse -> requireNotBlank(payload.safeMessage, "safeMessage")
    }
  }

  private fun validateHandshake(value: HandshakeRequest) {
    requireModel(value.version.major >= 0 && value.version.minor >= 0) {
      "Protocol versionは0以上である必要があります。"
    }
    validateCapabilities(value.capabilities)
    validateIdentifier(value.sessionId, ProtocolLimits.MAX_SESSION_ID_UTF8_BYTES, "sessionId")
    validateIdentifier(
      value.sessionToken,
      ProtocolLimits.MAX_SESSION_TOKEN_UTF8_BYTES,
      "sessionToken"
    )
  }

  private fun validateCapabilities(capabilities: Set<String>) {
    requireModel(capabilities.size <= ProtocolLimits.MAX_CAPABILITIES) {
      "capabilityが${ProtocolLimits.MAX_CAPABILITIES}件を超えています。"
    }
    capabilities.forEach {
      validateIdentifier(it, ProtocolLimits.MAX_CAPABILITY_UTF8_BYTES, "capability")
    }
  }

  private fun validate(descriptor: StoreDescriptor) {
    requireNotBlank(descriptor.id, "store id")
    requireNotBlank(descriptor.name, "store name")
    descriptor.logicalId?.let { logicalId ->
      validateIdentifier(
        logicalId,
        ProtocolLimits.MAX_REQUEST_ID_UTF8_BYTES,
        "logical store id"
      )
    }
    descriptor.generation?.let { generation ->
      requireModel(generation >= 0) { "Store generationは0以上である必要があります。" }
    }
    descriptor.schema?.let(::validate)
    descriptor.unsupportedReason?.let(::validate)
    descriptor.semantics?.let(::validate)
    val capabilityIds = descriptor.capabilities.mapTo(linkedSetOf(), StoreCapability::id)
    val semantics = descriptor.semantics
    val preferencesReplace = ProtocolCapabilities.PREFERENCES_REPLACE in capabilityIds
    val customGet = ProtocolCapabilities.CUSTOM_DOCUMENT_GET in capabilityIds
    val customReplace = ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE in capabilityIds
    val storeChanges = ProtocolCapabilities.STORE_CHANGES in capabilityIds
    if (preferencesReplace) {
      requireModel(
        descriptor.kind == StoreKind.PREFERENCES &&
          descriptor.status == StoreStatus.RESOLVED &&
          semantics != null &&
          semantics.backend in
          setOf(StoreBackend.DATASTORE, StoreBackend.SHARED_PREFERENCES) &&
          semantics.writeConsistency != WriteConsistency.UNKNOWN
      ) {
        "Preferences replaceにはresolved Preferencesと既知のmutation semanticsが必要です。"
      }
    }
    if (customGet || customReplace) {
      requireModel(
        descriptor.kind == StoreKind.CUSTOM &&
          descriptor.status == StoreStatus.RESOLVED
      ) {
        "Custom document capabilityはRESOLVED Custom Storeだけが公開できます。"
      }
    }
    if (customReplace) {
      requireModel(
        customGet &&
          descriptor.semantics?.backend == StoreBackend.DATASTORE &&
          descriptor.semantics.writeConsistency ==
          WriteConsistency.ATOMIC_TRANSACTIONAL
      ) {
        "Custom replaceにはread capabilityとatomic DataStore semanticsが必要です。"
      }
    }
    if (storeChanges) {
      requireModel(
        descriptor.status == StoreStatus.RESOLVED &&
          descriptor.logicalId != null &&
          descriptor.generation != null &&
          ProtocolCapabilities.SNAPSHOT_GET in capabilityIds &&
          descriptor.kind in setOf(StoreKind.PREFERENCES, StoreKind.PROTO)
      ) {
        "Store change購読にはresolved Preferences/Protoとsnapshot capabilityが必要です。"
      }
    }
    if (
      descriptor.kind == StoreKind.UNKNOWN ||
      descriptor.status == StoreStatus.UNKNOWN ||
      descriptor.semantics?.backend == StoreBackend.UNKNOWN ||
      descriptor.semantics?.writeConsistency == WriteConsistency.UNKNOWN
    ) {
      val writeCapabilities =
        setOf(
          ProtocolCapabilities.PREFERENCES_WRITE,
          ProtocolCapabilities.PREFERENCES_REPLACE,
          ProtocolCapabilities.PROTO_REPLACE,
          ProtocolCapabilities.STORE_RESET,
          ProtocolCapabilities.CUSTOM_DOCUMENT_REPLACE
        )
      requireModel(descriptor.capabilities.none { it.id in writeCapabilities }) {
        "未知のStore kind/statusはread-onlyである必要があります。"
      }
    }
  }

  private fun validate(semantics: StoreSemantics) {
    requireModel(semantics.supportedValueTypes.size <= ProtocolLimits.MAX_SUPPORTED_VALUE_TYPES) {
      "supported value typeが${ProtocolLimits.MAX_SUPPORTED_VALUE_TYPES}件を超えています。"
    }
    semantics.supportedValueTypes.forEach { typeId ->
      requireModel(CanonicalUtf8.isWellFormed(typeId)) {
        "supported value type IDのUTF-16が不正です。"
      }
      requireModel(
        typeId.isNotEmpty() &&
          typeId.all { character -> character.code in 0x21..0x7e } &&
          typeId.encodeToByteArray().size <= ProtocolLimits.MAX_SUPPORTED_VALUE_TYPE_ID_UTF8_BYTES
      ) {
        "supported value type IDがASCII ${ProtocolLimits.MAX_SUPPORTED_VALUE_TYPE_ID_UTF8_BYTES} byte以内ではありません。"
      }
    }
  }

  private fun validate(
    result: GetSnapshotResult,
    direction: ResponseValidationDirection
  ) {
    when (result) {
      is ResolvedSnapshotResult -> validate(result.snapshot, direction)
      is UnsupportedSnapshotInfo -> {
        requireNotBlank(result.storeId, "storeId")
        validate(result.reason)
      }
    }
  }

  private fun validate(
    snapshot: ResolvedStoreSnapshot,
    direction: ResponseValidationDirection
  ) {
    requireNotBlank(snapshot.storeId, "storeId")
    requireModel(snapshot.revision >= 0) { "revisionは0以上である必要があります。" }
    snapshot.storeGeneration?.let { generation ->
      requireModel(generation >= 0) { "Store generationは0以上である必要があります。" }
    }
    requireNotBlank(snapshot.contentToken, "contentToken")
    validate(snapshot.payload, direction)
  }

  private fun validate(
    payload: SnapshotPayload,
    direction: ResponseValidationDirection
  ) {
    when (payload) {
      is PreferencesTree -> validateRoot(payload.root)
      is ProtoRaw -> {
        validate(payload.schema)
        requireModel(payload.valueBytes.size <= ProtocolLimits.VALUE_BYTES) {
          "Proto valueが8 MiBを超えています。"
        }
      }
      is CustomDocumentPayload ->
        validate(
          payload,
          allowUnknownFormat = direction == ResponseValidationDirection.DECODE
        )
    }
  }

  private fun validate(
    notification: StoreChangeNotification,
    direction: ResponseValidationDirection
  ) {
    requireModel(notification.subscriptionGeneration >= 0) {
      "subscription generationは0以上である必要があります。"
    }
    requireModel(notification.storeGeneration >= 0) {
      "Store generationは0以上である必要があります。"
    }
    requireModel(notification.store.generation == notification.storeGeneration) {
      "Store change通知のdescriptor generationが一致しません。"
    }
    requireModel(notification.sequence >= 1) {
      "Store sequenceは1以上である必要があります。"
    }
    requireModel(notification.observedAtEpochMillis >= 0) {
      "観測時刻は0以上である必要があります。"
    }
    requireModel(
      direction == ResponseValidationDirection.DECODE ||
        notification.kind != StoreChangeKind.UNKNOWN
    ) {
      "encode時のStore change kindは既知である必要があります。"
    }
    validate(notification.store)
    requireModel(
      notification.store.logicalId != null &&
        notification.store.capabilities.any {
          it.id == ProtocolCapabilities.STORE_CHANGES
        }
    ) {
      "Store change通知にはlogical IDと購読capabilityが必要です。"
    }
    validate(notification.snapshot, direction)
    val payloadMatchesKind =
      when (notification.store.kind) {
        StoreKind.PREFERENCES -> notification.snapshot is PreferencesTree
        StoreKind.PROTO -> notification.snapshot is ProtoRaw
        StoreKind.CUSTOM, StoreKind.UNKNOWN -> false
      }
    requireModel(payloadMatchesKind) {
      "Store change通知のStore kindとsnapshot payloadが一致しません。"
    }
    notification.correlationId?.let { correlationId ->
      validateIdentifier(
        correlationId,
        ProtocolLimits.MAX_REQUEST_ID_UTF8_BYTES,
        "correlationId"
      )
    }
  }

  private fun validate(
    notification: StoreChangeBoundaryNotification,
    direction: ResponseValidationDirection
  ) {
    requireModel(notification.subscriptionGeneration >= 0) {
      "subscription generationは0以上である必要があります。"
    }
    requireModel(notification.observedAtEpochMillis >= 0) {
      "観測時刻は0以上である必要があります。"
    }
    requireModel(
      direction == ResponseValidationDirection.DECODE ||
        notification.reason != StoreChangeBoundaryReason.UNKNOWN
    ) {
      "encode時のStore change boundary reasonは既知である必要があります。"
    }
    val hasStore = notification.storeId != null
    requireModel(
      if (hasStore) {
        notification.logicalStoreId != null &&
          notification.storeGeneration != null &&
          notification.sequence != null
      } else {
        notification.logicalStoreId == null &&
          notification.storeGeneration == null &&
          notification.sequence == null &&
          notification.reason in
          setOf(
            StoreChangeBoundaryReason.BACKPRESSURE,
            StoreChangeBoundaryReason.OBSERVATION_FAILED,
            StoreChangeBoundaryReason.UNKNOWN
          )
      }
    ) {
      "Store change boundaryのStore identityが不完全です。"
    }
    notification.storeId?.let { requireNotBlank(it, "storeId") }
    notification.logicalStoreId?.let { logicalId ->
      validateIdentifier(
        logicalId,
        ProtocolLimits.MAX_REQUEST_ID_UTF8_BYTES,
        "logical store id"
      )
    }
    notification.storeGeneration?.let { generation ->
      requireModel(generation >= 0) { "Store generationは0以上である必要があります。" }
    }
    notification.sequence?.let { sequence ->
      requireModel(sequence >= 1) { "Store sequenceは1以上である必要があります。" }
    }
  }

  private fun validate(reason: UnsupportedReason) {
    validateIdentifier(
      reason.code,
      ProtocolLimits.MAX_UNSUPPORTED_REASON_CODE_UTF8_BYTES,
      "unsupported reason code"
    )
    requireModel(reason.code.all { character -> character.code in 0x21..0x7e }) {
      "unsupported reason codeは表示可能ASCIIである必要があります。"
    }
    validateIdentifier(
      reason.safeMessage,
      ProtocolLimits.MAX_UNSUPPORTED_REASON_SAFE_MESSAGE_UTF8_BYTES,
      "unsupported reason safeMessage"
    )
  }

  private fun validate(schema: ProtoSchemaRef) {
    requireNotBlank(schema.schemaId, "schemaId")
    requireNotBlank(schema.rootMessageFullName, "rootMessageFullName")
    validateDigest(schema.descriptorDigestSha256)
  }

  private fun validate(write: WritePayload) {
    requireNotBlank(write.storeId, "storeId")
    requireModel(write.expectedRevision >= 0) { "expectedRevisionは0以上である必要があります。" }
    requireNotBlank(write.expectedContentToken, "expectedContentToken")
    write.correlationId?.let { correlationId ->
      validateIdentifier(
        correlationId,
        ProtocolLimits.MAX_REQUEST_ID_UTF8_BYTES,
        "correlationId"
      )
    }
    when (val operation = write.operation) {
      is MutatePreferences -> validate(operation.mutation)
      is ReplacePreferences -> validate(operation)
      is ReplaceProtoBytes -> {
        validate(operation.schema)
        requireModel(operation.valueBytes.size <= ProtocolLimits.VALUE_BYTES) {
          "Proto valueが8 MiBを超えています。"
        }
      }
      is ReplaceCustomDocument -> validate(operation, allowUnknownFormat = false)
      ClearPreferences, ResetStore -> Unit
    }
  }

  private fun validate(
    document: CustomDocumentPayload,
    allowUnknownFormat: Boolean
  ) {
    validateCustomDocument(
      projectionId = document.projectionId,
      schemaVersion = document.schemaVersion,
      format = document.format,
      document = document.document,
      allowUnknownFormat = allowUnknownFormat
    )
  }

  private fun validate(
    document: ReplaceCustomDocument,
    allowUnknownFormat: Boolean
  ) {
    validateCustomDocument(
      projectionId = document.projectionId,
      schemaVersion = document.schemaVersion,
      format = document.format,
      document = document.document,
      allowUnknownFormat = allowUnknownFormat
    )
  }

  private fun validateCustomDocument(
    projectionId: String,
    schemaVersion: Int,
    format: CustomDocumentFormat,
    document: String,
    allowUnknownFormat: Boolean
  ) {
    validateIdentifier(
      projectionId,
      CustomDocumentLimits.MAX_PROJECTION_ID_UTF8_BYTES,
      "projectionId"
    )
    requireModel(projectionId.all { character -> character.code in 0x21..0x7e }) {
      "projectionIdは表示可能ASCIIである必要があります。"
    }
    requireModel(schemaVersion >= 0) { "schemaVersionは0以上である必要があります。" }
    try {
      if (format == CustomDocumentFormat.UNKNOWN && allowUnknownFormat) {
        CustomDocumentValidation.validateTransportBounds(document)
      } else {
        CustomDocumentValidation.validate(format, document)
      }
    } catch (error: CustomDocumentValidationException) {
      val kind =
        when (error.failure) {
          CustomDocumentValidationFailure.DOCUMENT_TOO_LARGE,
          CustomDocumentValidationFailure.JSON_DEPTH_LIMIT,
          CustomDocumentValidationFailure.JSON_NODE_LIMIT,
          CustomDocumentValidationFailure.JSON_COLLECTION_LIMIT,
          CustomDocumentValidationFailure.JSON_STRING_LIMIT
          -> ProtocolFailureKind.PAYLOAD_TOO_LARGE
          else -> ProtocolFailureKind.INVALID_MODEL
        }
      throw ProtocolException(
        kind = kind,
        message = error.message ?: "Custom documentが不正です。"
      )
    }
  }

  private fun validate(mutation: InspectorMutation) {
    when (mutation) {
      is PutPreference -> {
        validatePreferenceKey(mutation.key)
        validate(mutation.value)
      }
      is DeletePreference -> validatePreferenceKey(mutation.key)
    }
  }

  private fun validate(replacement: ReplacePreferences) {
    requireModel(replacement.entries.size <= ProtocolLimits.MAX_PREFERENCES_ENTRIES) {
      "Preferences replacementが${ProtocolLimits.MAX_PREFERENCES_ENTRIES}件を超えています。"
    }
    val keys = replacement.entries.map(PreferenceEntry::key)
    requireModel(keys == CanonicalUtf8.sorted(keys.distinct())) {
      "Preferences replacementは重複なし・unsigned UTF-8 byte順である必要があります。"
    }
    replacement.entries.forEach { entry ->
      validatePreferenceKey(entry.key)
      validate(entry.value)
    }
  }

  private fun validateRoot(root: InspectorNode) {
    requireModel(root.type == InspectorValueType.ROOT) { "Preferences rootのtypeがROOTではありません。" }
    requireModel(root.value == null) { "Preferences rootはvalueを持てません。" }
    requireModel(root.path.isEmpty()) { "Preferences rootのpathは空である必要があります。" }
    requireModel(root.presence == Presence.NOT_APPLICABLE) {
      "Preferences rootのpresenceが不正です。"
    }
    val keys = mutableSetOf<String>()
    root.children.forEach { child ->
      validateLeaf(child)
      val key = (child.path.single() as PreferenceKey).key
      requireModel(keys.add(key)) { "Preferences treeに重複keyがあります。" }
    }
  }

  private fun validateLeaf(node: InspectorNode) {
    requireModel(node.type != InspectorValueType.ROOT) { "leafのtypeがROOTです。" }
    val nodeValue =
      node.value
        ?: throw ProtocolException(
          ProtocolFailureKind.INVALID_MODEL,
          "leafのvalueがありません。"
        )
    requireModel(node.children.isEmpty()) { "Preferences leafはchildrenを持てません。" }
    requireModel(node.presence == Presence.PRESENT) { "Preferences leafのpresenceが不正です。" }
    requireModel(node.path.size == 1 && node.path.single() is PreferenceKey) {
      "Preferences leafのpathが不正です。"
    }
    val key = (node.path.single() as PreferenceKey).key
    validatePreferenceKey(key)
    requireModel(node.name == key) { "Preferences leafのnameとkeyが一致しません。" }
    validate(nodeValue)
    requireModel(node.type == nodeValue.valueType()) { "InspectorNode typeとvalue subtypeが一致しません。" }
  }

  private fun validate(value: InspectorValue) {
    when (value) {
      is FloatValue ->
        requireModel(Regex("^[0-9a-f]{8}$").matches(value.rawBitsHex)) {
          "Float raw bitsは小文字8桁hexである必要があります。"
        }
      is DoubleValue ->
        requireModel(Regex("^[0-9a-f]{16}$").matches(value.rawBitsHex)) {
          "Double raw bitsは小文字16桁hexである必要があります。"
        }
      is StringSetValue -> {
        value.values.forEach { element ->
          requireModel(CanonicalUtf8.isWellFormed(element)) {
            "StringSet要素のUTF-16が不正です。"
          }
        }
        requireModel(
          value.values == CanonicalUtf8.sorted(value.values.distinct())
        ) { "StringSetは重複なし・unsigned UTF-8 byte順である必要があります。" }
      }
      is BytesValue ->
        requireModel(value.value.size <= ProtocolLimits.VALUE_BYTES) {
          "Preferences byte valueが8 MiBを超えています。"
        }
      is StringValue ->
        requireModel(CanonicalUtf8.isWellFormed(value.value)) {
          "String valueのUTF-16が不正です。"
        }
      is IntValue, is LongValue, is BooleanValue -> Unit
    }
  }

  private fun InspectorValue.valueType(): InspectorValueType =
    when (this) {
      is StringValue -> InspectorValueType.STRING
      is IntValue -> InspectorValueType.INT
      is LongValue -> InspectorValueType.LONG
      is FloatValue -> InspectorValueType.FLOAT
      is DoubleValue -> InspectorValueType.DOUBLE
      is BooleanValue -> InspectorValueType.BOOLEAN
      is StringSetValue -> InspectorValueType.STRING_SET
      is BytesValue -> InspectorValueType.BYTES
    }

  private fun validatePreferenceKey(key: String) {
    requireModel(CanonicalUtf8.isWellFormed(key)) { "Preferences keyのUTF-16が不正です。" }
    requireModel(key.encodeToByteArray().size <= ProtocolLimits.MAX_PREFERENCE_KEY_UTF8_BYTES) {
      "Preferences keyが上限を超えています。"
    }
  }

  private fun validateDigest(digest: String) {
    requireModel(Regex("^[0-9a-f]{64}$").matches(digest)) {
      "SHA-256 digestは小文字64桁hexである必要があります。"
    }
  }

  private fun requireNotBlank(
    value: String,
    name: String
  ) {
    requireModel(value.isNotBlank()) { "${name}が空です。" }
  }

  private fun validateIdentifier(
    value: String,
    maxBytes: Int,
    name: String
  ) {
    requireNotBlank(value, name)
    requireModel(CanonicalUtf8.isWellFormed(value)) { "${name}のUTF-16が不正です。" }
    requireModel(value.encodeToByteArray().size <= maxBytes) { "${name}が上限を超えています。" }
  }

  private inline fun requireModel(
    condition: Boolean,
    message: () -> String
  ) {
    if (!condition) {
      throw ProtocolException(ProtocolFailureKind.INVALID_MODEL, message())
    }
  }
}
