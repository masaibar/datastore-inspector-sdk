package com.masaibar.datastore.inspector.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

public object ProtocolLimits {
  public const val UNAUTHENTICATED_FRAME_BYTES: Int = 64 * 1024
  public const val AUTHENTICATED_FRAME_BYTES: Int = 16 * 1024 * 1024
  public const val DESCRIPTOR_BYTES: Int = 8 * 1024 * 1024
  public const val VALUE_BYTES: Int = 8 * 1024 * 1024
  public const val MAX_JSON_DEPTH: Int = 64
  public const val MAX_CAPABILITIES: Int = 64
  public const val MAX_CAPABILITY_UTF8_BYTES: Int = 128
  public const val MAX_SESSION_ID_UTF8_BYTES: Int = 128
  public const val MAX_SESSION_TOKEN_UTF8_BYTES: Int = 512
  public const val MAX_REQUEST_ID_UTF8_BYTES: Int = 128
  public const val MAX_PREFERENCE_KEY_UTF8_BYTES: Int = 16 * 1024
  public const val MAX_PREFERENCES_ENTRIES: Int = 4_096
  public const val MAX_SUPPORTED_VALUE_TYPES: Int = 32
  public const val MAX_SUPPORTED_VALUE_TYPE_ID_UTF8_BYTES: Int = 64
  public const val MAX_UNSUPPORTED_REASON_CODE_UTF8_BYTES: Int = 128
  public const val MAX_UNSUPPORTED_REASON_SAFE_MESSAGE_UTF8_BYTES: Int = 1024
}

public enum class ProtocolFailureKind {
  MALFORMED_UTF8,
  MALFORMED_JSON,
  MALFORMED_BASE64,
  INVALID_MODEL,
  INVALID_LENGTH,
  PAYLOAD_TOO_LARGE,
  VERSION_MISMATCH,
  MISSING_CAPABILITY
}

public class ProtocolException(
  public val kind: ProtocolFailureKind,
  message: String,
  cause: Throwable? = null
) : IllegalArgumentException(message, cause)

public object Base64ByteArraySerializer : KSerializer<ByteArray> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("Rfc4648Base64", PrimitiveKind.STRING)

  override fun serialize(
    encoder: Encoder,
    value: ByteArray
  ) {
    if (value.size > ProtocolLimits.VALUE_BYTES) {
      throw SerializationException("byte列が8 MiBを超えています。")
    }
    encoder.encodeString(Base64.getEncoder().encodeToString(value))
  }

  override fun deserialize(decoder: Decoder): ByteArray {
    val encoded = decoder.decodeString()
    val maximumEncodedLength = ((ProtocolLimits.VALUE_BYTES.toLong() + 2L) / 3L) * 4L
    if (encoded.length.toLong() > maximumEncodedLength) {
      throw SerializationException("Base64 byte列が8 MiBを超えています。")
    }
    if (encoded.length % 4 != 0 || !BASE64_PATTERN.matches(encoded)) {
      throw SerializationException("RFC 4648準拠のpadding付きBase64ではありません。")
    }
    val decoded =
      try {
        Base64.getDecoder().decode(encoded)
      } catch (error: IllegalArgumentException) {
        throw SerializationException("Base64をdecodeできません。", error)
      }
    if (decoded.size > ProtocolLimits.VALUE_BYTES ||
      Base64.getEncoder().encodeToString(decoded) != encoded
    ) {
      throw SerializationException("Base64がcanonical形式ではありません。")
    }
    return decoded
  }

  private val BASE64_PATTERN = Regex("^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$")
}

public object ProtocolJson {
  private val json =
    Json {
      classDiscriminator = "type"
      encodeDefaults = true
      explicitNulls = true
      ignoreUnknownKeys = true
    }

  public fun encodeRequest(message: RequestEnvelope): ByteArray {
    ProtocolValidator.validate(message)
    return encode(RequestEnvelope.serializer(), message)
  }

  public fun decodeRequest(payload: ByteArray): RequestEnvelope =
    decode(RequestEnvelope.serializer(), payload).also(ProtocolValidator::validate)

  public fun encodeResponse(message: ResponseEnvelope): ByteArray {
    ProtocolValidator.validate(message, ResponseValidationDirection.ENCODE)
    return encode(ResponseEnvelope.serializer(), message)
  }

  public fun decodeResponse(payload: ByteArray): ResponseEnvelope =
    decode(ResponseEnvelope.serializer(), payload).also { message ->
      ProtocolValidator.validate(message, ResponseValidationDirection.DECODE)
    }

  public fun encodeRequestString(message: RequestEnvelope): String = strictDecodeUtf8(encodeRequest(message))

  public fun decodeRequestString(payload: String): RequestEnvelope = decodeRequest(payload.toByteArray(StandardCharsets.UTF_8))

  public fun encodeResponseString(message: ResponseEnvelope): String = strictDecodeUtf8(encodeResponse(message))

  public fun decodeResponseString(payload: String): ResponseEnvelope = decodeResponse(payload.toByteArray(StandardCharsets.UTF_8))

  private fun <T> encode(
    serializer: KSerializer<T>,
    message: T
  ): ByteArray = json.encodeToString(serializer, message).toByteArray(StandardCharsets.UTF_8)

  private fun <T> decode(
    serializer: KSerializer<T>,
    payload: ByteArray
  ): T {
    val text = strictDecodeUtf8(payload)
    JsonDepthValidator.validate(text, ProtocolLimits.MAX_JSON_DEPTH)
    return try {
      json.decodeFromString(serializer, text)
    } catch (error: SerializationException) {
      val kind =
        if (error.message?.contains("Base64", ignoreCase = true) == true) {
          ProtocolFailureKind.MALFORMED_BASE64
        } else {
          ProtocolFailureKind.MALFORMED_JSON
        }
      throw ProtocolException(kind, "Protocol JSONをdecodeできません。", error)
    }
  }

  private fun strictDecodeUtf8(payload: ByteArray): String {
    val decoder =
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
      decoder.decode(ByteBuffer.wrap(payload)).toString()
    } catch (error: Exception) {
      throw ProtocolException(
        ProtocolFailureKind.MALFORMED_UTF8,
        "UTF-8としてdecodeできません。",
        error
      )
    }
  }
}

public object ProtocolFraming {
  public fun encode(
    payload: ByteArray,
    maximumPayloadBytes: Int
  ): ByteArray {
    validateLength(payload.size.toLong(), maximumPayloadBytes)
    return ByteBuffer
      .allocate(Int.SIZE_BYTES + payload.size)
      .order(ByteOrder.BIG_ENDIAN)
      .putInt(payload.size)
      .put(payload)
      .array()
  }

  public fun decode(
    frame: ByteArray,
    maximumPayloadBytes: Int
  ): ByteArray {
    if (frame.size < Int.SIZE_BYTES) {
      throw ProtocolException(ProtocolFailureKind.INVALID_LENGTH, "length prefixが不足しています。")
    }
    val unsignedLength =
      Integer.toUnsignedLong(
        ByteBuffer
          .wrap(frame, 0, Int.SIZE_BYTES)
          .order(ByteOrder.BIG_ENDIAN)
          .int
      )
    validateLength(unsignedLength, maximumPayloadBytes)
    if (frame.size.toLong() != Int.SIZE_BYTES + unsignedLength) {
      throw ProtocolException(ProtocolFailureKind.INVALID_LENGTH, "frame長とpayload長が一致しません。")
    }
    return frame.copyOfRange(Int.SIZE_BYTES, frame.size)
  }

  private fun validateLength(
    length: Long,
    maximumPayloadBytes: Int
  ) {
    if (length > maximumPayloadBytes.toLong()) {
      throw ProtocolException(
        ProtocolFailureKind.PAYLOAD_TOO_LARGE,
        "payloadが上限を超えています。"
      )
    }
  }
}

private object JsonDepthValidator {
  fun validate(
    json: String,
    maximumDepth: Int
  ) {
    var depth = 0
    var inString = false
    var escaped = false
    json.forEach { character ->
      if (inString) {
        when {
          escaped -> escaped = false
          character == '\\' -> escaped = true
          character == '"' -> inString = false
        }
      } else {
        when (character) {
          '"' -> inString = true
          '{', '[' -> {
            depth += 1
            if (depth > maximumDepth) {
              throw ProtocolException(
                ProtocolFailureKind.MALFORMED_JSON,
                "JSON nesting depthが${maximumDepth}を超えています。"
              )
            }
          }
          '}', ']' -> {
            depth -= 1
            if (depth < 0) {
              throw ProtocolException(ProtocolFailureKind.MALFORMED_JSON, "JSONの括弧が不正です。")
            }
          }
        }
      }
    }
    if (inString || depth != 0) {
      throw ProtocolException(ProtocolFailureKind.MALFORMED_JSON, "JSONが途中で終了しています。")
    }
  }
}
