package com.masaibar.datastore.inspector.runtime.core

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.modules.SerializersModule
import java.util.IdentityHashMap

/**
 * 既知のkotlinx.serialization API callを、inspection対象の実Serializer実行中だけ捕捉します。
 *
 * ASM hookはこのobjectのmethodへ既知descriptorを置換します。inspection context外では元のAPIへ
 * そのまま委譲し、値・document・例外を保持しません。
 */
@InternalDataStoreInspectorApi
public object StructuredSerializationCapture {
  private val activeSession = ThreadLocal<CaptureSession?>()

  @JvmStatic
  public fun <T> encodeToByteArray(
    format: BinaryFormat,
    serializer: SerializationStrategy<T>,
    value: T
  ): ByteArray {
    activeSession.get()?.recordEncode(format.serializersModule, serializer, value)
    return format.encodeToByteArray(serializer, value)
  }

  @JvmStatic
  public fun <T> decodeFromByteArray(
    format: BinaryFormat,
    serializer: DeserializationStrategy<T>,
    bytes: ByteArray
  ): T {
    val value = format.decodeFromByteArray(serializer, bytes)
    activeSession.get()?.recordDecode(format.serializersModule, serializer, value)
    return value
  }

  @JvmStatic
  public fun <T> encodeToString(
    format: StringFormat,
    serializer: SerializationStrategy<T>,
    value: T
  ): String {
    activeSession.get()?.recordEncode(format.serializersModule, serializer, value)
    return format.encodeToString(serializer, value)
  }

  @JvmStatic
  public fun <T> decodeFromString(
    format: StringFormat,
    serializer: DeserializationStrategy<T>,
    document: String
  ): T {
    val value = format.decodeFromString(serializer, document)
    activeSession.get()?.recordDecode(format.serializersModule, serializer, value)
    return value
  }

  internal suspend fun <T> captureWrite(
    rootValue: T,
    block: suspend () -> Unit
  ): List<StructuredContract<T>> {
    val session = CaptureSession(rootValue)
    withContext(activeSession.asContextElement(session)) {
      block()
    }
    return session.contractsFor(rootValue)
  }

  internal suspend fun <T> captureRead(block: suspend () -> T): Pair<T, List<StructuredContract<T>>> {
    val session = CaptureSession(null)
    val value =
      withContext(activeSession.asContextElement(session)) {
        block()
      }
    return value to session.contractsFor(value)
  }

  private class CaptureSession(
    private val encodeRoot: Any?
  ) {
    private val lock = Any()
    private val encodeRootIsValue = encodeRoot?.let(::isValueRoot) == true
    private val encoded = mutableListOf<StructuredContract<*>>()
    private var encodedValueRootMatches = 0
    private val decoded = IdentityHashMap<Any, DecodedRootContracts>()
    private var decodedOverflow = false

    fun <T> recordEncode(
      module: SerializersModule,
      strategy: SerializationStrategy<T>,
      value: T
    ) {
      if (!matchesEncodeRoot(value)) return
      val serializer = strategy as? KSerializer<T> ?: return
      synchronized(lock) {
        if (encodeRootIsValue) {
          encodedValueRootMatches =
            (encodedValueRootMatches + 1).coerceAtMost(
              MAX_VALUE_ROOT_MATCHES
            )
        }
        encoded.addDistinctBounded(StructuredContract(serializer, module))
      }
    }

    fun <T> recordDecode(
      module: SerializersModule,
      strategy: DeserializationStrategy<T>,
      value: T
    ) {
      val identity = value as? Any ?: return
      val serializer = strategy as? KSerializer<T> ?: return
      synchronized(lock) {
        if (decodedOverflow) return
        val root =
          decoded[identity] ?: run {
            if (decoded.size >= MAX_DECODED_ROOT_CANDIDATES) {
              decoded.clear()
              decodedOverflow = true
              return
            }
            DecodedRootContracts(
              value = identity,
              valueClass = isValueRoot(identity)
            ).also { decoded[identity] = it }
          }
        root.contracts.addDistinctBounded(StructuredContract(serializer, module))
      }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> contractsFor(root: T): List<StructuredContract<T>> {
      val candidates = synchronized(lock) { candidatesFor(root).toList() }
      val serializers = IdentityHashMap<Any, MutableSet<Any>>()
      return candidates.filter { candidate ->
        serializers
          .getOrPut(candidate.serializer) {
            java.util.Collections.newSetFromMap(IdentityHashMap())
          }.add(candidate.module)
      } as List<StructuredContract<T>>
    }

    private fun matchesEncodeRoot(value: Any?): Boolean =
      value === encodeRoot ||
        (
          encodeRoot != null &&
            encodeRootIsValue &&
            valueLikeEquals(encodeRoot, value)
        )

    private fun <T> candidatesFor(root: T): List<StructuredContract<*>> {
      if (root === encodeRoot) {
        return if (
          !encodeRootIsValue ||
          encodedValueRootMatches == 1
        ) {
          encoded
        } else {
          emptyList()
        }
      }
      val identity = root as? Any ?: return emptyList()
      if (decodedOverflow) return emptyList()
      decoded[identity]?.let { return it.contracts }
      if (!isValueRoot(identity)) return emptyList()
      val matches =
        decoded.values.filter { candidate ->
          candidate.valueClass &&
            valueLikeEquals(identity, candidate.value)
        }
      return when (matches.size) {
        0 -> emptyList()
        1 -> matches.single().contracts
        else -> emptyList()
      }
    }

    private data class DecodedRootContracts(
      val value: Any,
      val valueClass: Boolean,
      val contracts: MutableList<StructuredContract<*>> = mutableListOf()
    )

    private companion object {
      const val MAX_VALUE_ROOT_MATCHES: Int = 2
      const val MAX_DECODED_ROOT_CANDIDATES: Int = 16
    }
  }
}

private fun MutableList<StructuredContract<*>>.addDistinctBounded(candidate: StructuredContract<*>) {
  if (
    size < MAX_CAPTURED_ROOT_CONTRACTS &&
    none { existing ->
      existing.serializer === candidate.serializer &&
        existing.module === candidate.module
    }
  ) {
    add(candidate)
  }
}

@Suppress("NO_REFLECTION_IN_CLASS_PATH")
private val kotlinReflectionSupportsIsValue: Boolean =
  ordinaryFailureOrNull { Any::class.isValue } != null

@Suppress("NO_REFLECTION_IN_CLASS_PATH")
private fun isValueRoot(value: Any): Boolean =
  if (kotlinReflectionSupportsIsValue) {
    ordinaryFailureOrNull { value::class.isValue }
      ?: hasJvmValueClassShape(value.javaClass)
  } else {
    hasJvmValueClassShape(value.javaClass)
  }

private fun hasJvmValueClassShape(type: Class<*>): Boolean {
  val methods = ordinaryFailureOrNull(type::getDeclaredMethods) ?: return false
  val box =
    methods.singleOrNull { method ->
      method.name == "box-impl" &&
        java.lang.reflect.Modifier
          .isStatic(method.modifiers) &&
        method.isSynthetic &&
        method.returnType == type &&
        method.parameterCount == 1
    } ?: return false
  val underlying = box.parameterTypes.single()
  val unbox =
    methods.singleOrNull { method ->
      method.name == "unbox-impl" &&
        !java.lang.reflect.Modifier
          .isStatic(method.modifiers) &&
        method.isSynthetic &&
        method.parameterCount == 0 &&
        method.returnType == underlying
    } ?: return false
  return methods.any { method ->
    method.name == "constructor-impl" &&
      java.lang.reflect.Modifier
        .isStatic(method.modifiers) &&
      method.parameterTypes.contentEquals(arrayOf(underlying)) &&
      method.returnType == unbox.returnType
  }
}

private fun valueLikeEquals(
  expected: Any,
  actual: Any?
): Boolean =
  actual != null &&
    actual::class == expected::class &&
    actual == expected &&
    actual.hashCode() == expected.hashCode()

internal data class StructuredContract<T>(
  val serializer: KSerializer<T>,
  val module: SerializersModule
)

private const val MAX_CAPTURED_ROOT_CONTRACTS: Int = 2
