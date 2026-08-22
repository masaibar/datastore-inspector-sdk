package com.masaibar.datastore.inspector.runtime.core

import com.masaibar.datastore.inspector.protocol.BooleanValue
import com.masaibar.datastore.inspector.protocol.BytesValue
import com.masaibar.datastore.inspector.protocol.CanonicalUtf8
import com.masaibar.datastore.inspector.protocol.DoubleValue
import com.masaibar.datastore.inspector.protocol.FloatValue
import com.masaibar.datastore.inspector.protocol.InspectorNode
import com.masaibar.datastore.inspector.protocol.InspectorValue
import com.masaibar.datastore.inspector.protocol.InspectorValueType
import com.masaibar.datastore.inspector.protocol.IntValue
import com.masaibar.datastore.inspector.protocol.LongValue
import com.masaibar.datastore.inspector.protocol.NodeCapability
import com.masaibar.datastore.inspector.protocol.PreferenceKey
import com.masaibar.datastore.inspector.protocol.PreferencesTree
import com.masaibar.datastore.inspector.protocol.Presence
import com.masaibar.datastore.inspector.protocol.ProtocolErrorCode
import com.masaibar.datastore.inspector.protocol.ProtocolLimits
import com.masaibar.datastore.inspector.protocol.StringSetValue
import com.masaibar.datastore.inspector.protocol.StringValue
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

public object PreferencesSnapshotLimits {
  public const val MAX_ENTRIES: Int = ProtocolLimits.MAX_PREFERENCES_ENTRIES
  public const val MAX_BACKING_FILE_BYTES: Long = 16L * 1024L * 1024L
  public const val MAX_STRING_UTF8_BYTES: Int = 1024 * 1024
  public const val MAX_SET_ELEMENTS: Int = 4_096
  public const val MAX_TOTAL_SET_ELEMENTS: Int = 16_384
  public const val MAX_CANONICAL_BYTES: Long = 4L * 1024L * 1024L
  public const val MAX_ESTIMATED_WIRE_BYTES: Long = 12L * 1024L * 1024L
}

public object PreferencesSnapshotEncoder {
  private const val FORMAT_VERSION: Int = 1
  private val editCapabilities =
    setOf(NodeCapability("edit"), NodeCapability("delete"))

  public fun encode(
    values: Map<String, InspectorValue>,
    limitErrorCode: ProtocolErrorCode = ProtocolErrorCode.PAYLOAD_TOO_LARGE
  ): AdapterSnapshot {
    if (values.size > PreferencesSnapshotLimits.MAX_ENTRIES) {
      limitExceeded(limitErrorCode)
    }
    val entries = values.map { (key, value) ->
      canonicalEntry(key, value, limitErrorCode)
    }
      .sortedWith { left, right -> CanonicalUtf8.comparator.compare(left.key, right.key) }
    var canonicalBytes = 8L
    var totalSetElements = 0
    var estimatedWireBytes = 512L
    entries.forEach { entry ->
      canonicalBytes =
        checkedAdd(
          canonicalBytes,
          Int.SIZE_BYTES + entry.keyBytes.size.toLong() + 1L,
          limitErrorCode
        )
      estimatedWireBytes = checkedAdd(
        estimatedWireBytes,
        256L + 2L * estimatedJsonStringBytes(entry.key),
        limitErrorCode
      )
      when (val value = entry.value) {
        is StringValue -> {
          val bytes = utf8Value(value.value, limitErrorCode)
          canonicalBytes =
            checkedAdd(
              canonicalBytes,
              Int.SIZE_BYTES + bytes.size.toLong(),
              limitErrorCode
            )
          estimatedWireBytes = checkedAdd(
            estimatedWireBytes,
            estimatedJsonStringBytes(value.value),
            limitErrorCode
          )
        }
        is StringSetValue -> {
          if (value.values.size > PreferencesSnapshotLimits.MAX_SET_ELEMENTS) {
            limitExceeded(limitErrorCode)
          }
          totalSetElements += value.values.size
          if (totalSetElements > PreferencesSnapshotLimits.MAX_TOTAL_SET_ELEMENTS) {
            limitExceeded(limitErrorCode)
          }
          canonicalBytes =
            checkedAdd(
              canonicalBytes,
              Int.SIZE_BYTES.toLong(),
              limitErrorCode
            )
          value.values.forEach { element ->
            val bytes = utf8Value(element, limitErrorCode)
            canonicalBytes =
              checkedAdd(
                canonicalBytes,
                Int.SIZE_BYTES + bytes.size.toLong(),
                limitErrorCode
              )
            estimatedWireBytes = checkedAdd(
              estimatedWireBytes,
              16L + estimatedJsonStringBytes(element),
              limitErrorCode
            )
          }
        }
        is BytesValue -> {
          canonicalBytes =
            checkedAdd(
              canonicalBytes,
              Int.SIZE_BYTES + value.value.size.toLong(),
              limitErrorCode
            )
          estimatedWireBytes = checkedAdd(
            estimatedWireBytes,
            ((value.value.size.toLong() + 2L) / 3L) * 4L,
            limitErrorCode
          )
        }
        is IntValue, is FloatValue ->
          canonicalBytes = checkedAdd(canonicalBytes, 4L, limitErrorCode)
        is LongValue, is DoubleValue ->
          canonicalBytes = checkedAdd(canonicalBytes, 8L, limitErrorCode)
        is BooleanValue ->
          canonicalBytes = checkedAdd(canonicalBytes, 1L, limitErrorCode)
      }
      if (
        canonicalBytes > PreferencesSnapshotLimits.MAX_CANONICAL_BYTES ||
        estimatedWireBytes > PreferencesSnapshotLimits.MAX_ESTIMATED_WIRE_BYTES
      ) {
        limitExceeded(limitErrorCode)
      }
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update("datastore-inspector-preferences".toByteArray(StandardCharsets.US_ASCII))
    digest.updateInt(FORMAT_VERSION)
    digest.updateInt(entries.size)
    entries.forEach { entry ->
      digest.updateSized(entry.keyBytes)
      digest.updateValue(entry.value)
    }
    val nodes = entries.map { entry ->
      InspectorNode(
        path = listOf(PreferenceKey(entry.key)),
        name = entry.key,
        type = entry.value.type(),
        value = entry.value,
        presence = Presence.PRESENT,
        children = emptyList(),
        capabilities = editCapabilities
      )
    }
    return AdapterSnapshot(
      fingerprint = digest.digest().toHex(),
      payload =
        PreferencesTree(
          InspectorNode(
            path = emptyList(),
            name = "Preferences",
            type = InspectorValueType.ROOT,
            value = null,
            presence = Presence.NOT_APPLICABLE,
            children = nodes,
            capabilities = emptySet()
          )
        )
    )
  }

  private fun canonicalEntry(
    key: String,
    value: InspectorValue,
    limitErrorCode: ProtocolErrorCode
  ): CanonicalEntry {
    if (!CanonicalUtf8.isWellFormed(key)) unsupported()
    val keyBytes = key.toByteArray(StandardCharsets.UTF_8)
    if (keyBytes.size > ProtocolLimits.MAX_PREFERENCE_KEY_UTF8_BYTES) {
      limitExceeded(limitErrorCode)
    }
    val copiedValue =
      when (value) {
        is StringValue -> {
          utf8Value(value.value, limitErrorCode)
          value
        }
        is StringSetValue -> {
          if (value.values.any { !CanonicalUtf8.isWellFormed(it) }) unsupported()
          val sorted = CanonicalUtf8.sorted(value.values.distinct())
          StringSetValue(sorted)
        }
        is BytesValue -> BytesValue(value.value.copyOf())
        is IntValue,
        is LongValue,
        is FloatValue,
        is DoubleValue,
        is BooleanValue
        -> value
      }
    return CanonicalEntry(key, keyBytes, copiedValue)
  }

  private fun utf8Value(
    value: String,
    limitErrorCode: ProtocolErrorCode
  ): ByteArray {
    if (!CanonicalUtf8.isWellFormed(value)) unsupported()
    return value.toByteArray(StandardCharsets.UTF_8).also { bytes ->
      if (bytes.size > PreferencesSnapshotLimits.MAX_STRING_UTF8_BYTES) {
        limitExceeded(limitErrorCode)
      }
    }
  }

  private fun MessageDigest.updateValue(value: InspectorValue) {
    when (value) {
      is StringValue -> {
        update(byteArrayOf(1))
        updateSized(value.value.toByteArray(StandardCharsets.UTF_8))
      }
      is IntValue -> {
        update(byteArrayOf(2))
        updateInt(value.value)
      }
      is LongValue -> {
        update(byteArrayOf(3))
        updateLong(value.value)
      }
      is FloatValue -> {
        update(byteArrayOf(4))
        updateInt(value.rawBitsHex.toUInt(16).toInt())
      }
      is DoubleValue -> {
        update(byteArrayOf(5))
        updateLong(value.rawBitsHex.toULong(16).toLong())
      }
      is BooleanValue -> {
        update(byteArrayOf(6))
        update(byteArrayOf(if (value.value) 1 else 0))
      }
      is StringSetValue -> {
        update(byteArrayOf(7))
        updateInt(value.values.size)
        value.values.forEach { element ->
          updateSized(element.toByteArray(StandardCharsets.UTF_8))
        }
      }
      is BytesValue -> {
        update(byteArrayOf(8))
        updateSized(value.value)
      }
    }
  }

  private fun MessageDigest.updateSized(bytes: ByteArray) {
    updateInt(bytes.size)
    update(bytes)
  }

  private fun MessageDigest.updateInt(value: Int) {
    update(
      ByteBuffer.allocate(Int.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(value)
        .array()
    )
  }

  private fun MessageDigest.updateLong(value: Long) {
    update(
      ByteBuffer.allocate(Long.SIZE_BYTES)
        .order(ByteOrder.BIG_ENDIAN)
        .putLong(value)
        .array()
    )
  }

  private fun InspectorValue.type(): InspectorValueType =
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

  private fun estimatedJsonStringBytes(value: String): Long {
    var total = 2L
    var index = 0
    while (index < value.length) {
      val character = value[index]
      total +=
        when (character) {
          '"', '\\', '\b', '\u000c', '\n', '\r', '\t' -> 2L
          in '\u0000'..'\u001f' -> 6L
          else -> {
            val codePoint = Character.codePointAt(value, index)
            when {
              codePoint <= 0x7f -> 1L
              codePoint <= 0x7ff -> 2L
              codePoint <= 0xffff -> 3L
              else -> 4L
            }.also {
              index += Character.charCount(codePoint) - 1
            }
          }
        }
      index++
    }
    return total
  }

  private fun checkedAdd(
    left: Long,
    right: Long,
    limitErrorCode: ProtocolErrorCode
  ): Long {
    if (right > Long.MAX_VALUE - left) limitExceeded(limitErrorCode)
    return left + right
  }

  private fun limitExceeded(errorCode: ProtocolErrorCode): Nothing =
    throw StoreAdapterException(errorCode)

  private fun unsupported(): Nothing =
    throw StoreAdapterException(ProtocolErrorCode.STORE_UNSUPPORTED)

  private data class CanonicalEntry(
    val key: String,
    val keyBytes: ByteArray,
    val value: InspectorValue
  )
}
