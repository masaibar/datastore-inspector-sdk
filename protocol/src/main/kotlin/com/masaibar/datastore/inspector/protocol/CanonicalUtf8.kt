package com.masaibar.datastore.inspector.protocol

import java.nio.charset.StandardCharsets

@InternalDataStoreInspectorProtocolApi
public object CanonicalUtf8 {
  public val comparator: Comparator<String> = Comparator { left, right ->
    compareBytes(left.toByteArray(StandardCharsets.UTF_8), right.toByteArray(StandardCharsets.UTF_8))
  }

  public fun isWellFormed(value: String): Boolean {
    var index = 0
    while (index < value.length) {
      val character = value[index]
      when {
        character.isHighSurrogate() -> {
          if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
          index += 2
        }
        character.isLowSurrogate() -> return false
        else -> index += 1
      }
    }
    return true
  }

  public fun sorted(values: Iterable<String>): List<String> =
    values.sortedWith(comparator)

  private fun compareBytes(left: ByteArray, right: ByteArray): Int {
    val commonLength = minOf(left.size, right.size)
    for (index in 0 until commonLength) {
      val difference = (left[index].toInt() and 0xff) - (right[index].toInt() and 0xff)
      if (difference != 0) return difference
    }
    return left.size - right.size
  }
}
