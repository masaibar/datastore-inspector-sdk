package com.masaibar.datastore.inspector.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Limits shared by the Runtime and inspection client before a custom document is interpreted.
 *
 * The document limit is intentionally lower than [ProtocolLimits.AUTHENTICATED_FRAME_BYTES]
 * because the document must still fit in an escaped Protocol JSON envelope.
 */
public object CustomDocumentLimits {
  public const val MAX_DOCUMENT_UTF8_BYTES: Int = 1024 * 1024
  public const val MAX_JSON_DEPTH: Int = 64
  public const val MAX_JSON_NODES: Int = 100_000
  public const val MAX_JSON_COLLECTION_ENTRIES: Int = 10_000
  public const val MAX_JSON_STRING_UTF8_BYTES: Int = 256 * 1024
  public const val MAX_JSON_NUMBER_CHARACTERS: Int = 1024
  public const val MAX_PROJECTION_ID_UTF8_BYTES: Int = 128
}

@Serializable(with = CustomDocumentFormatSerializer::class)
public enum class CustomDocumentFormat(public val wireName: String) {
  JSON("json"),
  TEXT("text"),
  UNKNOWN("unknown")
}

public object CustomDocumentFormatSerializer : StableEnumSerializer<CustomDocumentFormat>(
  serialName = "CustomDocumentFormat",
  values = CustomDocumentFormat.entries,
  wireName = CustomDocumentFormat::wireName,
  unknown = CustomDocumentFormat.UNKNOWN
)

@Serializable
@SerialName("custom_document")
public data class CustomDocumentPayload(
  val projectionId: String,
  val schemaVersion: Int,
  val format: CustomDocumentFormat,
  val document: String
) : SnapshotPayload

@Serializable
@SerialName("replace_custom_document")
public data class ReplaceCustomDocument(
  val projectionId: String,
  val schemaVersion: Int,
  val format: CustomDocumentFormat,
  val document: String
) : WriteOperation

/**
 * Safe, stable reason codes for a Custom Store becoming read-only.
 *
 * These codes describe an observed fact only. They intentionally carry no class name,
 * exception detail, value, document, raw bytes, or backing path.
 */
@Serializable(with = CustomStoreReasonCodeSerializer::class)
public enum class CustomStoreReasonCode(public val wireName: String) {
  CUSTOM_OUTPUT_NOT_UTF8("CUSTOM_OUTPUT_NOT_UTF8"),
  CUSTOM_OUTPUT_NOT_JSON("CUSTOM_OUTPUT_NOT_JSON"),
  CUSTOM_SERIALIZER_NON_DETERMINISTIC("CUSTOM_SERIALIZER_NON_DETERMINISTIC"),
  CUSTOM_DOCUMENT_ROUND_TRIP_MISMATCH("CUSTOM_DOCUMENT_ROUND_TRIP_MISMATCH"),
  CUSTOM_VALUE_ROUND_TRIP_MISMATCH("CUSTOM_VALUE_ROUND_TRIP_MISMATCH"),
  CUSTOM_VALUE_EQUALITY_TOO_COARSE("CUSTOM_VALUE_EQUALITY_TOO_COARSE"),
  CUSTOM_STRUCTURED_CODEC_NOT_CAPTURED("CUSTOM_STRUCTURED_CODEC_NOT_CAPTURED"),
  CUSTOM_STRUCTURED_CODEC_AMBIGUOUS("CUSTOM_STRUCTURED_CODEC_AMBIGUOUS"),
  CUSTOM_TYPE_ARGUMENTS_UNAVAILABLE("CUSTOM_TYPE_ARGUMENTS_UNAVAILABLE"),
  CUSTOM_CONTEXTUAL_MODULE_UNAVAILABLE("CUSTOM_CONTEXTUAL_MODULE_UNAVAILABLE"),
  CUSTOM_TEXT_UNSAFE("CUSTOM_TEXT_UNSAFE"),
  CUSTOM_TEXT_ROUND_TRIP_MISMATCH("CUSTOM_TEXT_ROUND_TRIP_MISMATCH"),
  CUSTOM_DOCUMENT_TOO_LARGE("CUSTOM_DOCUMENT_TOO_LARGE"),
  CUSTOM_PROBE_TIMEOUT("CUSTOM_PROBE_TIMEOUT"),
  CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE("CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE"),
  CUSTOM_CREATION_ROUTE_UNSUPPORTED("CUSTOM_CREATION_ROUTE_UNSUPPORTED"),
  CUSTOM_ACTUAL_WRITE_MISMATCH("CUSTOM_ACTUAL_WRITE_MISMATCH"),
  UNKNOWN("UNKNOWN")
  ;

  public companion object {
    public fun fromWireName(wireName: String): CustomStoreReasonCode =
      entries.firstOrNull { it.wireName == wireName } ?: UNKNOWN
  }
}

public object CustomStoreReasonCodeSerializer : StableEnumSerializer<CustomStoreReasonCode>(
  serialName = "CustomStoreReasonCode",
  values = CustomStoreReasonCode.entries,
  wireName = CustomStoreReasonCode::wireName,
  unknown = CustomStoreReasonCode.UNKNOWN
)

/**
 * A bounded and non-sensitive explanation of why custom document validation failed.
 */
public enum class CustomDocumentValidationFailure {
  MALFORMED_UTF16,
  DOCUMENT_TOO_LARGE,
  INVALID_JSON,
  DUPLICATE_JSON_KEY,
  NON_FINITE_JSON_NUMBER,
  JSON_DEPTH_LIMIT,
  JSON_NODE_LIMIT,
  JSON_COLLECTION_LIMIT,
  JSON_STRING_LIMIT,
  TEXT_UNSAFE_CONTROL,
  UNSUPPORTED_FORMAT
}

/**
 * Validation error whose message is fixed and safe to show in diagnostics.
 *
 * The offending document and parser internals are never retained as a cause or message.
 */
public class CustomDocumentValidationException(
  public val failure: CustomDocumentValidationFailure
) : IllegalArgumentException(failure.safeMessage())

/**
 * Strict validation shared by Runtime and inspection client for Custom Store documents.
 */
public object CustomDocumentValidation {
  @Throws(CustomDocumentValidationException::class)
  public fun validate(format: CustomDocumentFormat, document: String) {
    validateTransportBounds(document)
    when (format) {
      CustomDocumentFormat.JSON -> StrictJsonDocumentParser(document).validate()
      CustomDocumentFormat.TEXT -> validateText(document)
      CustomDocumentFormat.UNKNOWN -> fail(CustomDocumentValidationFailure.UNSUPPORTED_FORMAT)
    }
  }

  /**
   * Resolves direct serializer output with strict JSON taking precedence over text.
   *
   * JSON-looking input that violates JSON syntax or limits is rejected rather than silently
   * downgraded to a text projection.
   */
  @Throws(CustomDocumentValidationException::class)
  public fun detectFormat(document: String): CustomDocumentFormat {
    try {
      validate(CustomDocumentFormat.JSON, document)
      return CustomDocumentFormat.JSON
    } catch (jsonFailure: CustomDocumentValidationException) {
      if (
        jsonFailure.failure == CustomDocumentValidationFailure.MALFORMED_UTF16 ||
        jsonFailure.failure == CustomDocumentValidationFailure.DOCUMENT_TOO_LARGE ||
        looksLikeJson(document)
      ) {
        throw jsonFailure
      }
    }
    validate(CustomDocumentFormat.TEXT, document)
    return CustomDocumentFormat.TEXT
  }

  internal fun validateTransportBounds(document: String) {
    if (!CanonicalUtf8.isWellFormed(document)) {
      fail(CustomDocumentValidationFailure.MALFORMED_UTF16)
    }
    if (document.encodeToByteArray().size > CustomDocumentLimits.MAX_DOCUMENT_UTF8_BYTES) {
      fail(CustomDocumentValidationFailure.DOCUMENT_TOO_LARGE)
    }
  }

  private fun validateText(document: String) {
    document.forEach { character ->
      val code = character.code
      if (
        (code in 0x00..0x1f && character != '\t' && character != '\n' && character != '\r') ||
        code in 0x7f..0x9f
      ) {
        fail(CustomDocumentValidationFailure.TEXT_UNSAFE_CONTROL)
      }
    }
  }

  private fun looksLikeJson(document: String): Boolean {
    val candidate =
      document.dropWhile { character ->
        character.isWhitespace() || character == '\ufeff'
      }
    if (candidate.isEmpty()) return false
    if (candidate.startsWith("NaN") ||
      candidate.startsWith("Infinity") ||
      candidate.startsWith("+Infinity") ||
      candidate.startsWith("-Infinity")
    ) {
      return true
    }
    return when (candidate.first()) {
      '{', '[', '"', '-', '+' -> true
      '.' -> candidate.getOrNull(1)?.let { it in '0'..'9' } == true
      in '0'..'9' -> true
      't' -> candidate.startsWith("true")
      'f' -> candidate.startsWith("false")
      'n' -> candidate.startsWith("null")
      else -> false
    }
  }
}

private class StrictJsonDocumentParser(
  private val document: String
) {
  private var index: Int = 0
  private var nodeCount: Int = 0

  fun validate() {
    skipWhitespace()
    parseValue(containerDepth = 0)
    skipWhitespace()
    if (index != document.length) {
      fail(CustomDocumentValidationFailure.INVALID_JSON)
    }
  }

  private fun parseValue(containerDepth: Int) {
    incrementNodeCount()
    if (index >= document.length) {
      fail(CustomDocumentValidationFailure.INVALID_JSON)
    }
    when (document[index]) {
      '{' -> parseObject(containerDepth + 1)
      '[' -> parseArray(containerDepth + 1)
      '"' -> parseString(capture = false)
      't' -> consumeLiteral("true")
      'f' -> consumeLiteral("false")
      'n' -> consumeLiteral("null")
      'N', 'I' -> fail(CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER)
      '+' -> {
        if (document.startsWith("+Infinity", index)) {
          fail(CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER)
        }
        fail(CustomDocumentValidationFailure.INVALID_JSON)
      }
      '-' -> {
        if (document.startsWith("-Infinity", index)) {
          fail(CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER)
        }
        parseNumber()
      }
      in '0'..'9' -> parseNumber()
      else -> fail(CustomDocumentValidationFailure.INVALID_JSON)
    }
  }

  private fun parseObject(depth: Int) {
    validateDepth(depth)
    index += 1
    skipWhitespace()
    if (consumeIf('}')) return

    val keys = hashSetOf<String>()
    var entries = 0
    while (true) {
      if (index >= document.length || document[index] != '"') {
        fail(CustomDocumentValidationFailure.INVALID_JSON)
      }
      val key = checkNotNull(parseString(capture = true))
      if (!keys.add(key)) {
        fail(CustomDocumentValidationFailure.DUPLICATE_JSON_KEY)
      }
      skipWhitespace()
      requireCharacter(':')
      skipWhitespace()
      parseValue(depth)
      entries += 1
      validateCollectionSize(entries)
      skipWhitespace()
      when {
        consumeIf('}') -> return
        consumeIf(',') -> {
          skipWhitespace()
          if (index < document.length && document[index] == '}') {
            fail(CustomDocumentValidationFailure.INVALID_JSON)
          }
        }
        else -> fail(CustomDocumentValidationFailure.INVALID_JSON)
      }
    }
  }

  private fun parseArray(depth: Int) {
    validateDepth(depth)
    index += 1
    skipWhitespace()
    if (consumeIf(']')) return

    var entries = 0
    while (true) {
      parseValue(depth)
      entries += 1
      validateCollectionSize(entries)
      skipWhitespace()
      when {
        consumeIf(']') -> return
        consumeIf(',') -> {
          skipWhitespace()
          if (index < document.length && document[index] == ']') {
            fail(CustomDocumentValidationFailure.INVALID_JSON)
          }
        }
        else -> fail(CustomDocumentValidationFailure.INVALID_JSON)
      }
    }
  }

  private fun parseString(capture: Boolean): String? {
    requireCharacter('"')
    val decoded = if (capture) StringBuilder() else null
    var decodedUtf8Bytes = 0
    while (index < document.length) {
      val character = document[index++]
      when {
        character == '"' -> return decoded?.toString()
        character == '\\' -> {
          if (index >= document.length) {
            fail(CustomDocumentValidationFailure.INVALID_JSON)
          }
          when (val escaped = document[index++]) {
            '"', '\\', '/' -> {
              decoded?.append(escaped)
              decodedUtf8Bytes += 1
            }
            'b' -> {
              decoded?.append('\b')
              decodedUtf8Bytes += 1
            }
            'f' -> {
              decoded?.append('\u000c')
              decodedUtf8Bytes += 1
            }
            'n' -> {
              decoded?.append('\n')
              decodedUtf8Bytes += 1
            }
            'r' -> {
              decoded?.append('\r')
              decodedUtf8Bytes += 1
            }
            't' -> {
              decoded?.append('\t')
              decodedUtf8Bytes += 1
            }
            'u' -> {
              val first = readHexCodeUnit()
              when {
                first.isHighSurrogate() -> {
                  if (
                    index + 6 > document.length ||
                    document[index] != '\\' ||
                    document[index + 1] != 'u'
                  ) {
                    fail(CustomDocumentValidationFailure.INVALID_JSON)
                  }
                  index += 2
                  val second = readHexCodeUnit()
                  if (!second.isLowSurrogate()) {
                    fail(CustomDocumentValidationFailure.INVALID_JSON)
                  }
                  decoded?.append(first)?.append(second)
                  decodedUtf8Bytes += 4
                }
                first.isLowSurrogate() ->
                  fail(CustomDocumentValidationFailure.INVALID_JSON)
                else -> {
                  decoded?.append(first)
                  decodedUtf8Bytes += utf8Size(first.code)
                }
              }
            }
            else -> fail(CustomDocumentValidationFailure.INVALID_JSON)
          }
        }
        character.code < 0x20 -> fail(CustomDocumentValidationFailure.INVALID_JSON)
        character.isHighSurrogate() -> {
          if (index >= document.length || !document[index].isLowSurrogate()) {
            fail(CustomDocumentValidationFailure.INVALID_JSON)
          }
          val second = document[index++]
          decoded?.append(character)?.append(second)
          decodedUtf8Bytes += 4
        }
        character.isLowSurrogate() -> fail(CustomDocumentValidationFailure.INVALID_JSON)
        else -> {
          decoded?.append(character)
          decodedUtf8Bytes += utf8Size(character.code)
        }
      }
      if (decodedUtf8Bytes > CustomDocumentLimits.MAX_JSON_STRING_UTF8_BYTES) {
        fail(CustomDocumentValidationFailure.JSON_STRING_LIMIT)
      }
    }
    fail(CustomDocumentValidationFailure.INVALID_JSON)
  }

  private fun parseNumber() {
    val start = index
    consumeIf('-')
    if (index >= document.length) {
      fail(CustomDocumentValidationFailure.INVALID_JSON)
    }
    when (document[index]) {
      '0' -> {
        index += 1
        if (index < document.length && document[index] in '0'..'9') {
          fail(CustomDocumentValidationFailure.INVALID_JSON)
        }
      }
      in '1'..'9' -> consumeDigits()
      else -> fail(CustomDocumentValidationFailure.INVALID_JSON)
    }
    if (consumeIf('.')) {
      if (index >= document.length || document[index] !in '0'..'9') {
        fail(CustomDocumentValidationFailure.INVALID_JSON)
      }
      consumeDigits()
    }
    if (index < document.length && (document[index] == 'e' || document[index] == 'E')) {
      index += 1
      if (index < document.length && (document[index] == '+' || document[index] == '-')) {
        index += 1
      }
      if (index >= document.length || document[index] !in '0'..'9') {
        fail(CustomDocumentValidationFailure.INVALID_JSON)
      }
      consumeDigits()
    }
    if (index - start > CustomDocumentLimits.MAX_JSON_NUMBER_CHARACTERS) {
      fail(CustomDocumentValidationFailure.DOCUMENT_TOO_LARGE)
    }
    requireValueBoundary()
  }

  private fun consumeDigits() {
    while (index < document.length && document[index] in '0'..'9') {
      index += 1
    }
  }

  private fun consumeLiteral(literal: String) {
    if (!document.startsWith(literal, index)) {
      fail(CustomDocumentValidationFailure.INVALID_JSON)
    }
    index += literal.length
    requireValueBoundary()
  }

  private fun requireValueBoundary() {
    if (
      index < document.length &&
      document[index] != ',' &&
      document[index] != ']' &&
      document[index] != '}' &&
      !isJsonWhitespace(document[index])
    ) {
      fail(CustomDocumentValidationFailure.INVALID_JSON)
    }
  }

  private fun readHexCodeUnit(): Char {
    if (index + 4 > document.length) {
      fail(CustomDocumentValidationFailure.INVALID_JSON)
    }
    var value = 0
    repeat(4) {
      val digit = document[index++].asciiHexDigit()
        ?: fail(CustomDocumentValidationFailure.INVALID_JSON)
      value = value * 16 + digit
    }
    return value.toChar()
  }

  private fun incrementNodeCount() {
    nodeCount += 1
    if (nodeCount > CustomDocumentLimits.MAX_JSON_NODES) {
      fail(CustomDocumentValidationFailure.JSON_NODE_LIMIT)
    }
  }

  private fun validateDepth(depth: Int) {
    if (depth > CustomDocumentLimits.MAX_JSON_DEPTH) {
      fail(CustomDocumentValidationFailure.JSON_DEPTH_LIMIT)
    }
  }

  private fun validateCollectionSize(entries: Int) {
    if (entries > CustomDocumentLimits.MAX_JSON_COLLECTION_ENTRIES) {
      fail(CustomDocumentValidationFailure.JSON_COLLECTION_LIMIT)
    }
  }

  private fun requireCharacter(expected: Char) {
    if (index >= document.length || document[index] != expected) {
      fail(CustomDocumentValidationFailure.INVALID_JSON)
    }
    index += 1
  }

  private fun consumeIf(expected: Char): Boolean {
    if (index < document.length && document[index] == expected) {
      index += 1
      return true
    }
    return false
  }

  private fun skipWhitespace() {
    while (index < document.length && isJsonWhitespace(document[index])) {
      index += 1
    }
  }
}

private fun isJsonWhitespace(character: Char): Boolean =
  character == ' ' || character == '\t' || character == '\n' || character == '\r'

private fun utf8Size(codePoint: Int): Int =
  when {
    codePoint <= 0x7f -> 1
    codePoint <= 0x7ff -> 2
    codePoint <= 0xffff -> 3
    else -> 4
  }

private fun Char.asciiHexDigit(): Int? =
  when (this) {
    in '0'..'9' -> code - '0'.code
    in 'a'..'f' -> code - 'a'.code + 10
    in 'A'..'F' -> code - 'A'.code + 10
    else -> null
  }

private fun CustomDocumentValidationFailure.safeMessage(): String =
  when (this) {
    CustomDocumentValidationFailure.MALFORMED_UTF16 ->
      "Custom documentのUnicode表現が不正です。"
    CustomDocumentValidationFailure.DOCUMENT_TOO_LARGE ->
      "Custom documentが上限を超えています。"
    CustomDocumentValidationFailure.INVALID_JSON ->
      "Custom JSON documentがstrict JSONではありません。"
    CustomDocumentValidationFailure.DUPLICATE_JSON_KEY ->
      "Custom JSON documentに重複keyがあります。"
    CustomDocumentValidationFailure.NON_FINITE_JSON_NUMBER ->
      "Custom JSON documentに非有限数があります。"
    CustomDocumentValidationFailure.JSON_DEPTH_LIMIT ->
      "Custom JSON documentの深さが上限を超えています。"
    CustomDocumentValidationFailure.JSON_NODE_LIMIT ->
      "Custom JSON documentのnode数が上限を超えています。"
    CustomDocumentValidationFailure.JSON_COLLECTION_LIMIT ->
      "Custom JSON documentのcollection件数が上限を超えています。"
    CustomDocumentValidationFailure.JSON_STRING_LIMIT ->
      "Custom JSON documentのStringが上限を超えています。"
    CustomDocumentValidationFailure.TEXT_UNSAFE_CONTROL ->
      "Custom text documentに安全に表示できないcontrolがあります。"
    CustomDocumentValidationFailure.UNSUPPORTED_FORMAT ->
      "Custom document formatに対応していません。"
  }

private fun fail(failure: CustomDocumentValidationFailure): Nothing =
  throw CustomDocumentValidationException(failure)
