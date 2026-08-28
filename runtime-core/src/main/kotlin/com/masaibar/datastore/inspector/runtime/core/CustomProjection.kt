@file:OptIn(ExperimentalDataStoreInspectorApi::class)

package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.CustomDocumentFormat
import com.masaibar.datastore.inspector.protocol.CustomDocumentValidation
import com.masaibar.datastore.inspector.protocol.CustomDocumentValidationException
import com.masaibar.datastore.inspector.protocol.CustomDocumentValidationFailure
import com.masaibar.datastore.inspector.protocol.CustomStoreReasonCode
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializerOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal data class ProjectedDocument<T>(
  val projection: CustomProjection<T>,
  val document: String
) {
  val fingerprint: String
    get() =
      sha256(
        buildString {
          append(projection.projectionId)
          append('\u0000')
          append(projection.schemaVersion)
          append('\u0000')
          append(projection.format.name)
          append('\u0000')
          append(document)
        }.encodeToByteArray()
      )
}

internal interface CustomProjection<T> {
  val projectionId: String
  val schemaVersion: Int
  val format: CustomDocumentFormat
  val requiresPersistencePreflight: Boolean
    get() = true

  suspend fun encode(value: T): String

  suspend fun decode(document: String): T

  /** direct JSON/textがactual persistence bytesをdocumentへ正規化する高速経路です。 */
  fun documentFromPersistence(bytes: ByteArray): String? = null
}

internal data class CachedProjection<T>(
  val runtimeClass: Class<*>?,
  val contractGeneration: Long,
  val projection: CustomProjection<T>
)

internal class CustomProjectionResolver<T>(
  private val handle: CustomInspectionHandle<T>
) {
  suspend fun resolve(current: T): ProjectedDocument<T> {
    handle.requireAvailable()
    handle.cachedProjectionFor(current)?.let { cached ->
      try {
        val routeCheck = checkCachedRoute(cached, current)
        if (!routeCheck.preferred) {
          handle.invalidateProjectionCache()
          return@let
        }
        return probe(
          projection = cached,
          current = current,
          persistencePreflightCompleted =
            routeCheck.persistencePreflightCompleted
        )
      } catch (error: CustomInspectionFailure) {
        error.rethrowInspectionControlFlow()
        handle.invalidateProjectionCache()
      }
    }
    val resolved = resolveUncached(current)
    handle.cacheProjection(current, resolved.projection)
    return resolved
  }

  private suspend fun checkCachedRoute(
    cached: CustomProjection<T>,
    current: T
  ): CachedRouteCheck {
    if (cached.projectionId == DIRECT_JSON_PROJECTION_ID) {
      return CachedRouteCheck(preferred = true)
    }
    val actualBytes =
      try {
        handle.encodeForInspection(current)
      } catch (error: Throwable) {
        error.rethrowInspectionControlFlow()
        return CachedRouteCheck(
          preferred = handle.cachedProjectionFor(current) === cached
        )
      }
    if (handle.cachedProjectionFor(current) !== cached) {
      return CachedRouteCheck(preferred = false)
    }
    var persistencePreflightCompleted = false
    if (cached.requiresPersistencePreflight) {
      val persisted =
        try {
          handle.decodeForInspection(actualBytes)
        } catch (error: Throwable) {
          error.rethrowInspectionControlFlow()
          throw CustomInspectionFailure(
            CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
          )
        }
      if (!sameValue(current, persisted)) {
        throw CustomInspectionFailure(
          CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
        )
      }
      if (handle.cachedProjectionFor(current) !== cached) {
        return CachedRouteCheck(preferred = false)
      }
      persistencePreflightCompleted = true
    }
    val actualDocument =
      try {
        strictUtf8(actualBytes)
      } catch (error: CharacterCodingException) {
        error.rethrowInspectionControlFlow()
        return CachedRouteCheck(
          preferred = true,
          persistencePreflightCompleted = persistencePreflightCompleted
        )
      }
    val actualFormat =
      try {
        CustomDocumentValidation.detectFormat(actualDocument)
      } catch (error: CustomDocumentValidationException) {
        error.rethrowInspectionControlFlow()
        return CachedRouteCheck(
          preferred = true,
          persistencePreflightCompleted = persistencePreflightCompleted
        )
      }
    val preferred =
      when {
        cached.projectionId == DIRECT_TEXT_PROJECTION_ID ->
          actualFormat == CustomDocumentFormat.TEXT
        cached.projectionId.startsWith(FALLBACK_PROJECTION_PREFIX) -> false
        else -> actualFormat != CustomDocumentFormat.JSON
      }
    return CachedRouteCheck(
      preferred = preferred,
      persistencePreflightCompleted = persistencePreflightCompleted
    )
  }

  @OptIn(InternalSerializationApi::class)
  private suspend fun resolveUncached(current: T): ProjectedDocument<T> {
    handle.requireAvailable()
    var fallbackReason = CustomStoreReasonCode.CUSTOM_STRUCTURED_CODEC_NOT_CAPTURED
    var directText: DirectTextProjection<T>? = null
    val successful = mutableListOf<ProjectedDocument<T>>()

    suspend fun collect(projection: CustomProjection<T>): CustomInspectionFailure? =
      try {
        successful += probe(projection, current)
        null
      } catch (failure: CustomInspectionFailure) {
        failure.rethrowInspectionControlFlow()
        failure
      }

    val initialBytes =
      try {
        handle.encodeForInspection(current)
      } catch (failure: CustomInspectionFailure) {
        failure.rethrowInspectionControlFlow()
        throw failure
      } catch (error: Throwable) {
        error.rethrowInspectionControlFlow()
        throw CustomInspectionFailure(
          CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
        )
      }
    val initialDocument =
      try {
        strictUtf8(initialBytes)
      } catch (error: CharacterCodingException) {
        error.rethrowInspectionControlFlow()
        null
      }
    if (initialDocument == null) {
      fallbackReason = CustomStoreReasonCode.CUSTOM_OUTPUT_NOT_UTF8
    } else {
      try {
        when (CustomDocumentValidation.detectFormat(initialDocument)) {
          CustomDocumentFormat.JSON -> {
            collect(DirectJsonProjection(handle))
              ?.let { failure -> fallbackReason = failure.reason }
          }
          CustomDocumentFormat.TEXT ->
            directText = DirectTextProjection(handle)
          CustomDocumentFormat.UNKNOWN ->
            fallbackReason = CustomStoreReasonCode.CUSTOM_OUTPUT_NOT_JSON
        }
      } catch (error: CustomDocumentValidationException) {
        error.rethrowInspectionControlFlow()
        fallbackReason = error.failure.toReasonCode()
      }
    }

    val captured = handle.structuredContracts()
    if (captured.size > 1) {
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_STRUCTURED_CODEC_AMBIGUOUS
      )
    } else if (captured.size == 1) {
      collect(
        StructuredProjection(
          projectionId = STRUCTURED_PROJECTION_ID,
          contract = captured.single()
        )
      )?.let { failure ->
        fallbackReason = failure.reason
      }
    }

    if (current == null) {
      fallbackReason = CustomStoreReasonCode.CUSTOM_TYPE_ARGUMENTS_UNAVAILABLE
    } else if (current.javaClass.typeParameters.isNotEmpty()) {
      fallbackReason = CustomStoreReasonCode.CUSTOM_TYPE_ARGUMENTS_UNAVAILABLE
    } else {
      val generated =
        try {
          @Suppress("UNCHECKED_CAST")
          current::class.serializerOrNull() as? KSerializer<T>
        } catch (error: Throwable) {
          error.rethrowInspectionControlFlow()
          null
        }
      if (generated != null) {
        collect(
          StructuredProjection(
            projectionId = GENERATED_PROJECTION_ID,
            contract =
              StructuredContract(
                generated,
                kotlinx.serialization.modules.EmptySerializersModule()
              )
          )
        )?.let { failure ->
          fallbackReason =
            if (
              failure.reason ==
              CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
            ) {
              CustomStoreReasonCode.CUSTOM_CONTEXTUAL_MODULE_UNAVAILABLE
            } else {
              failure.reason
            }
        }
      }
    }

    if (directText != null) {
      collect(directText)
        ?.let { failure -> fallbackReason = failure.reason }
    }

    when (
      val codec =
        InspectorCustomCodecRegistry.resolve(
          handle.originalSerializer.javaClass,
          current?.javaClass ?: handle.defaultValue?.javaClass ?: Any::class.java
        )
    ) {
      CodecResolution.Ambiguous -> {
        throw CustomInspectionFailure(
          CustomStoreReasonCode.CUSTOM_STRUCTURED_CODEC_AMBIGUOUS
        )
      }
      CodecResolution.None -> Unit
      is CodecResolution.Resolved -> {
        @Suppress("UNCHECKED_CAST")
        val binding = codec.binding as InspectorCustomCodecBinding<Any>

        @Suppress("UNCHECKED_CAST")
        val failure =
          collect(CodecProjection(binding) as CustomProjection<T>)
        if (failure != null && successful.isEmpty()) throw failure
      }
    }

    if (successful.isEmpty()) throw CustomInspectionFailure(fallbackReason)
    return selectConsistent(successful)
  }

  private fun selectConsistent(
    successful: List<ProjectedDocument<T>>
  ): ProjectedDocument<T> {
    val preferred = successful.first()
    if (
      successful.drop(1).any { candidate ->
        candidate.projection.format != preferred.projection.format ||
          candidate.document != preferred.document
      }
    ) {
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_STRUCTURED_CODEC_AMBIGUOUS
      )
    }
    return preferred
  }

  suspend fun probe(
    projection: CustomProjection<T>,
    current: T,
    persistencePreflightCompleted: Boolean = false
  ): ProjectedDocument<T> {
    val first = safely { projection.encode(current) }
    val second = safely { projection.encode(current) }
    if (first != second) {
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_SERIALIZER_NON_DETERMINISTIC
      )
    }
    validateDocument(projection.format, first)
    val decoded = safely { projection.decode(first) }
    if (!sameValue(current, decoded)) {
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
      )
    }
    val roundTrip = safely { projection.encode(decoded) }
    if (first != roundTrip) {
      throw CustomInspectionFailure(
        if (projection.format == CustomDocumentFormat.TEXT) {
          CustomStoreReasonCode.CUSTOM_TEXT_ROUND_TRIP_MISMATCH
        } else {
          CustomStoreReasonCode.CUSTOM_DOCUMENT_ROUND_TRIP_MISMATCH
        }
      )
    }
    if (projection.requiresPersistencePreflight && !persistencePreflightCompleted) {
      val persistenceBytes = safely { handle.encodeForInspection(decoded) }
      val persisted = safely { handle.decodeForInspection(persistenceBytes) }
      if (!sameValue(decoded, persisted)) {
        throw CustomInspectionFailure(
          CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
        )
      }
    }
    return ProjectedDocument(projection, first)
  }

  private suspend fun <R> safely(block: suspend () -> R): R =
    try {
      block()
    } catch (failure: CustomInspectionFailure) {
      failure.rethrowInspectionControlFlow()
      throw failure
    } catch (error: CustomDocumentValidationException) {
      error.rethrowInspectionControlFlow()
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_DOCUMENT_ROUND_TRIP_MISMATCH
      )
    } catch (error: Throwable) {
      error.rethrowInspectionControlFlow()
      throw CustomInspectionFailure(
        CustomStoreReasonCode.CUSTOM_VALUE_ROUND_TRIP_MISMATCH
      )
    }
}

private class DirectJsonProjection<T>(
  private val handle: CustomInspectionHandle<T>
) : CustomProjection<T> {
  override val projectionId: String = DIRECT_JSON_PROJECTION_ID
  override val schemaVersion: Int = 1
  override val format: CustomDocumentFormat = CustomDocumentFormat.JSON
  override val requiresPersistencePreflight: Boolean = false

  override suspend fun encode(value: T): String = canonicalJson(strictUtf8(handle.encodeForInspection(value)))

  override suspend fun decode(document: String): T = handle.decodeForInspection(canonicalJson(document).encodeToByteArray())

  override fun documentFromPersistence(bytes: ByteArray): String = canonicalJson(strictUtf8(bytes))
}

private class DirectTextProjection<T>(
  private val handle: CustomInspectionHandle<T>
) : CustomProjection<T> {
  override val projectionId: String = DIRECT_TEXT_PROJECTION_ID
  override val schemaVersion: Int = 1
  override val format: CustomDocumentFormat = CustomDocumentFormat.TEXT
  override val requiresPersistencePreflight: Boolean = false

  override suspend fun encode(value: T): String =
    strictUtf8(handle.encodeForInspection(value)).also {
      CustomDocumentValidation.validate(CustomDocumentFormat.TEXT, it)
    }

  override suspend fun decode(document: String): T = handle.decodeForInspection(document.encodeToByteArray())

  override fun documentFromPersistence(bytes: ByteArray): String =
    strictUtf8(bytes).also {
      CustomDocumentValidation.validate(CustomDocumentFormat.TEXT, it)
    }
}

@OptIn(ExperimentalSerializationApi::class)
private class StructuredProjection<T>(
  override val projectionId: String,
  private val contract: StructuredContract<T>
) : CustomProjection<T> {
  override val schemaVersion: Int = 1
  override val format: CustomDocumentFormat = CustomDocumentFormat.JSON
  private val json =
    Json {
      serializersModule = contract.module
      encodeDefaults = true
      explicitNulls = true
      ignoreUnknownKeys = false
      isLenient = false
      allowSpecialFloatingPointValues = false
      allowStructuredMapKeys = false
      prettyPrint = false
      allowTrailingComma = false
    }

  override suspend fun encode(value: T): String = json.encodeToString(contract.serializer, value).let(::canonicalJson)

  override suspend fun decode(document: String): T = json.decodeFromString(contract.serializer, canonicalJson(document))
}

private class CodecProjection<T : Any>(
  private val binding: InspectorCustomCodecBinding<T>
) : CustomProjection<T> {
  override val projectionId: String =
    fallbackProjectionId(binding.codec.codecId, binding.codec.schemaVersion)
  override val schemaVersion: Int = binding.codec.schemaVersion
  override val format: CustomDocumentFormat =
    when (binding.codec.format) {
      InspectorCustomDocumentFormat.JSON -> CustomDocumentFormat.JSON
      InspectorCustomDocumentFormat.TEXT -> CustomDocumentFormat.TEXT
    }

  override suspend fun encode(value: T): String {
    binding.codec.validate(value)
    val encoded = binding.codec.encode(value)
    validateDocument(format, encoded)
    return if (format == CustomDocumentFormat.JSON) canonicalJson(encoded) else encoded
  }

  override suspend fun decode(document: String): T {
    validateDocument(format, document)
    val decoded = binding.codec.decode(document)
    require(binding.valueClass.isInstance(decoded))
    binding.codec.validate(decoded)
    return decoded
  }
}

private data class CachedRouteCheck(
  val preferred: Boolean,
  val persistencePreflightCompleted: Boolean = false
)

private fun validateDocument(
  format: CustomDocumentFormat,
  document: String
) {
  try {
    CustomDocumentValidation.validate(format, document)
  } catch (error: CustomDocumentValidationException) {
    error.rethrowInspectionControlFlow()
    throw CustomInspectionFailure(error.failure.toReasonCode())
  }
}

private fun CustomDocumentValidationFailure.toReasonCode(): CustomStoreReasonCode =
  when (this) {
    CustomDocumentValidationFailure.MALFORMED_UTF16 ->
      CustomStoreReasonCode.CUSTOM_OUTPUT_NOT_UTF8
    CustomDocumentValidationFailure.DOCUMENT_TOO_LARGE,
    CustomDocumentValidationFailure.JSON_DEPTH_LIMIT,
    CustomDocumentValidationFailure.JSON_NODE_LIMIT,
    CustomDocumentValidationFailure.JSON_COLLECTION_LIMIT,
    CustomDocumentValidationFailure.JSON_STRING_LIMIT
    ->
      CustomStoreReasonCode.CUSTOM_DOCUMENT_TOO_LARGE
    CustomDocumentValidationFailure.TEXT_UNSAFE_CONTROL ->
      CustomStoreReasonCode.CUSTOM_TEXT_UNSAFE
    CustomDocumentValidationFailure.INVALID_JSON,
    CustomDocumentValidationFailure.DUPLICATE_JSON_KEY,
    CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER,
    CustomDocumentValidationFailure.UNSUPPORTED_FORMAT
    ->
      CustomStoreReasonCode.CUSTOM_OUTPUT_NOT_JSON
  }

private fun strictUtf8(bytes: ByteArray): String {
  val decoder =
    StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
  return decoder.decode(ByteBuffer.wrap(bytes)).toString()
}

private val canonicalJsonCodec =
  Json {
    isLenient = false
    allowSpecialFloatingPointValues = false
  }

private fun canonicalJson(document: String): String {
  CustomDocumentValidation.validate(CustomDocumentFormat.JSON, document)
  val element: JsonElement = canonicalJsonCodec.parseToJsonElement(document)
  return canonicalJsonCodec.encodeToString(JsonElement.serializer(), element)
}

internal const val DIRECT_JSON_PROJECTION_ID: String = "direct-json-v1"
internal const val STRUCTURED_PROJECTION_ID: String = "structured-json-v1"
internal const val GENERATED_PROJECTION_ID: String = "generated-json-v1"
internal const val DIRECT_TEXT_PROJECTION_ID: String = "direct-text-v1"
private const val FALLBACK_PROJECTION_PREFIX: String = "fallback:"
