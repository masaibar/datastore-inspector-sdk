package com.masaibar.datastore.inspector.runtime.protobuf

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

internal data class VerifiedSchemaIndex(
  val entries: List<VerifiedSchemaEntry>
)

internal data class VerifiedSchemaEntry(
  val generatedJvmClassName: String,
  val rootMessageFullName: String,
  val descriptorDigestSha256: String,
  val descriptorAssetPath: String,
  val descriptorBytes: ByteArray
) {
  override fun equals(other: Any?): Boolean =
    other is VerifiedSchemaEntry &&
      generatedJvmClassName == other.generatedJvmClassName &&
      rootMessageFullName == other.rootMessageFullName &&
      descriptorDigestSha256 == other.descriptorDigestSha256 &&
      descriptorAssetPath == other.descriptorAssetPath &&
      descriptorBytes.contentEquals(other.descriptorBytes)

  override fun hashCode(): Int =
    31 *
      (
        31 *
          (
            31 *
              (31 * generatedJvmClassName.hashCode() + rootMessageFullName.hashCode()) +
              descriptorDigestSha256.hashCode()
          ) +
          descriptorAssetPath.hashCode()
      ) +
      descriptorBytes.contentHashCode()
}

internal object SchemaIndexConsumer {
  private const val MAX_DESCRIPTOR_BYTES: Int = 8 * 1024 * 1024
  private val allowedIndexKeys = setOf("formatVersion", "entries")
  private val allowedEntryKeys =
    setOf(
      "generatedJvmClassName",
      "rootMessageFullName",
      "codeGenerationMode",
      "descriptorDigestSha256",
      "descriptorAssetPath"
    )
  private val descriptorPathPattern =
    Regex("^datastore-inspector/schemas/([0-9a-f]{64})\\.desc$")

  public fun load(
    indexBytes: ByteArray,
    assetLoader: (String) -> ByteArray?
  ): VerifiedSchemaIndex {
    val root =
      runCatching {
        Json.parseToJsonElement(indexBytes.decodeToString()).jsonObject
      }.getOrElse { error ->
        throw IllegalArgumentException("schema index JSONが不正です。", error)
      }
    require(root.keys == allowedIndexKeys) { "schema indexのfieldが不正です: ${root.keys}" }
    require(root.requiredInt("formatVersion") == 1) { "未知のschema index formatVersionです。" }
    val entries = root.requiredArray("entries").map { parseEntry(it.jsonObject, assetLoader) }
    require(entries.isNotEmpty()) { "schema index entryがありません。" }
    require(entries.map { it.generatedJvmClassName }.distinct().size == entries.size) {
      "同じgenerated JVM classに複数entryがあります。"
    }
    return VerifiedSchemaIndex(entries)
  }

  private fun parseEntry(
    objectValue: JsonObject,
    assetLoader: (String) -> ByteArray?
  ): VerifiedSchemaEntry {
    require(objectValue.keys == allowedEntryKeys) {
      "schema entryのfieldが不正です: ${objectValue.keys}"
    }
    val generatedClass = objectValue.requiredString("generatedJvmClassName")
    val rootMessage = objectValue.requiredString("rootMessageFullName")
    require(objectValue.requiredString("codeGenerationMode") == "JAVA_LITE") {
      "codeGenerationModeはJAVA_LITEである必要があります。"
    }
    val digest = objectValue.requiredString("descriptorDigestSha256")
    require(Regex("^[0-9a-f]{64}$").matches(digest)) { "descriptor digestが不正です。" }
    val path = objectValue.requiredString("descriptorAssetPath")
    val pathDigest = descriptorPathPattern.matchEntire(path)?.groupValues?.get(1)
    require(pathDigest == digest) { "descriptor asset pathが不正です。" }
    val bytes = requireNotNull(assetLoader(path)) { "descriptor assetがありません: $path" }
    require(bytes.size <= MAX_DESCRIPTOR_BYTES) { "descriptor bundleが8 MiBを超えています。" }
    require(sha256(bytes) == digest) { "descriptor digestが一致しません。" }
    validateDescriptor(bytes, rootMessage)
    return VerifiedSchemaEntry(generatedClass, rootMessage, digest, path, bytes.copyOf())
  }

  private fun validateDescriptor(bytes: ByteArray, rootMessage: String) {
    val descriptorSet =
      runCatching { FileDescriptorSet.parseFrom(bytes) }.getOrElse { error ->
        throw IllegalArgumentException("descriptor bundleが破損しています。", error)
      }
    val fileNames = descriptorSet.fileList.map { it.name }
    require(fileNames.size == fileNames.distinct().size) { "descriptor bundleに重複protoがあります。" }
    val knownFiles = fileNames.toSet()
    descriptorSet.fileList.forEach { descriptor ->
      val missing = descriptor.dependencyList.toSet() - knownFiles
      require(missing.isEmpty()) { "descriptor import closureが不足しています: $missing" }
    }
    val messages =
      buildSet {
        descriptorSet.fileList.forEach { file ->
          collectMessages(file.`package`, file.messageTypeList, this)
        }
      }
    require(rootMessage in messages) { "root messageがdescriptorにありません: $rootMessage" }
  }

  private fun collectMessages(
    prefix: String,
    messages: List<DescriptorProto>,
    destination: MutableSet<String>
  ) {
    messages.forEach { message ->
      val fullName = if (prefix.isEmpty()) message.name else "$prefix.${message.name}"
      destination += fullName
      collectMessages(fullName, message.nestedTypeList, destination)
    }
  }

  private fun JsonObject.requiredString(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content
      ?.takeIf(String::isNotBlank)
      ?: throw IllegalArgumentException("${name}がありません。")

  private fun JsonObject.requiredInt(name: String): Int =
    runCatching { getValue(name).jsonPrimitive.int }
      .getOrElse { throw IllegalArgumentException("${name}がありません。", it) }

  private fun JsonObject.requiredArray(name: String): JsonArray =
    runCatching { getValue(name).jsonArray }
      .getOrElse { throw IllegalArgumentException("${name}がありません。", it) }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
