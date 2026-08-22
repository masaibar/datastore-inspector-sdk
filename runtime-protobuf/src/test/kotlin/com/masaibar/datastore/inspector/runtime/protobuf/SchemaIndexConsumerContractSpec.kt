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
        "schema fixture pathが設定されていません。"
      }
    )

  init {
    describe("SchemaIndexConsumerContract") {
      context("既存の契約を検証するとき") {
        it("Gradle Pluginが生成したgolden schemaをRuntime consumerで検証する") {
          val indexFile = fixtureRoot.resolve("datastore-inspector/schema-index.json")
          withClue("schema indexがありません: $indexFile") { (indexFile.isFile) shouldBe true }
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
                "datastore.inspector.sample.UserSettings"
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

        it("欠落assetとpath traversalとdigest不一致を拒否する") {
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

        it("重複classと8MiB超過とimport欠落を拒否する") {
          val validIndex = fixtureRoot.resolve("datastore-inspector/schema-index.json").readText()
          val entry = Regex("\\{\\s*\"generatedJvmClassName\".*?\\n\\s*}", RegexOption.DOT_MATCHES_ALL)
            .find(validIndex)?.value ?: error("fixture entryを抽出できません。")
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
              ?: error("descriptor pathがありません。")
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
