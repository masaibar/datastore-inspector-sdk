package com.masaibar.datastore.inspector.runtime.protobuf

import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.security.MessageDigest

class SchemaIndexConsumerContractSpec : DescribeSpec() {
  private val fixtureRoot =
    File(
      checkNotNull(System.getProperty("datastore.inspector.schema.fixture")) {
        "The schema fixture path is not configured."
      }
    )

  init {
    describe("SchemaIndexConsumerContract") {
      context("when verifying the generated schema contract") {
        it("loads every automatic mapping generated for the sample schema") {
          val indexFile = fixtureRoot.resolve("datastore-inspector/schema-index.json")
          withClue("The schema index does not exist: $indexFile") {
            (indexFile.isFile) shouldBe true
          }
          val verified =
            SchemaIndexConsumer.load(indexFile.readBytes()) { path ->
              fixtureRoot.resolve(path).takeIf(File::isFile)?.readBytes()
            }
          (
            verified.entries.associate {
              it.generatedJvmClassName to it.rootMessageFullName
            }
          ) shouldBe (
            mapOf(
              "com.masaibar.datastore.inspector.sample.proto.UserSettings" to
                "datastore.inspector.sample.UserSettings",
              "com.masaibar.datastore.inspector.sample.proto.UserSettings\$NotificationSettings" to
                "datastore.inspector.sample.UserSettings.NotificationSettings",
              "com.masaibar.datastore.inspector.sample.proto.common.Profile" to
                "datastore.inspector.sample.common.Profile"
            )
          )
          verified.entries.forEach { entry ->
            (
              MessageDigest.getInstance("SHA-256")
                .digest(entry.descriptorBytes)
                .joinToString("") { "%02x".format(it) }
            ) shouldBe (entry.descriptorDigestSha256)
          }
        }

        it("rejects a missing asset, path traversal, and a digest mismatch") {
          val validIndex =
            fixtureRoot.resolve("datastore-inspector/schema-index.json").readText()
          shouldThrow<IllegalArgumentException> {
            SchemaIndexConsumer.load(validIndex.encodeToByteArray()) { null }
          }
          val traversal =
            validIndex.replace(
              Regex("datastore-inspector/schemas/[0-9a-f]{64}\\.desc"),
              "../secret.desc"
            )
          shouldThrow<IllegalArgumentException> {
            SchemaIndexConsumer.load(traversal.encodeToByteArray()) { path ->
              fixtureRoot.resolve(path).takeIf(File::isFile)?.readBytes()
            }
          }
          val digestMismatch = validIndex.replaceFirst(Regex("[0-9a-f]{64}"), "0".repeat(64))
          shouldThrow<IllegalArgumentException> {
            SchemaIndexConsumer.load(digestMismatch.encodeToByteArray()) { path ->
              fixtureRoot.resolve(path).takeIf(File::isFile)?.readBytes()
            }
          }
        }

        it("rejects duplicate classes, oversized descriptors, and missing imports") {
          val validIndex = fixtureRoot.resolve("datastore-inspector/schema-index.json").readText()
          val entry = Regex("\\{\\s*\"generatedJvmClassName\".*?\\n\\s*}", RegexOption.DOT_MATCHES_ALL)
            .find(validIndex)?.value ?: error("Cannot extract a schema fixture entry.")
          val duplicate = validIndex.replace(entry, "$entry,\n$entry")
          shouldThrow<IllegalArgumentException> {
            SchemaIndexConsumer.load(duplicate.encodeToByteArray()) { path ->
              fixtureRoot.resolve(path).takeIf(File::isFile)?.readBytes()
            }
          }
          shouldThrow<IllegalArgumentException> {
            SchemaIndexConsumer.load(validIndex.encodeToByteArray()) { ByteArray(8 * 1024 * 1024 + 1) }
          }

          val descriptorPath =
            Regex("datastore-inspector/schemas/[0-9a-f]{64}\\.desc").find(validIndex)?.value
              ?: error("The descriptor path is missing.")
          val descriptor = fixtureRoot.resolve(descriptorPath).readBytes()
          val set = FileDescriptorSet.parseFrom(descriptor)
          val withoutImport =
            FileDescriptorSet.newBuilder()
              .addAllFile(set.fileList.filterNot { it.name == "common/profile.proto" })
              .build()
              .toByteArray()
          val digest = sha256(withoutImport)
          val missingImportIndex =
            validIndex
              .replace(Regex("[0-9a-f]{64}"), digest)
          shouldThrow<IllegalArgumentException> {
            SchemaIndexConsumer.load(missingImportIndex.encodeToByteArray()) { withoutImport }
          }
        }
      }
    }
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
      .digest(bytes)
      .joinToString("") { "%02x".format(it) }
}
