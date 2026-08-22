package com.masaibar.datastore.inspector.gradle

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import kotlin.io.path.writeText

class DataStoreInspectorPluginSpec : DescribeSpec() {
  init {
    describe("DataStoreInspectorPlugin") {
      context("既存の契約を検証するとき") {
        it("Plugin実行時のartifact座標は正本から生成される") {
          val coordinates = Properties().apply {
            File("../gradle/artifact-coordinates.properties")
              .inputStream()
              .use(::load)
          }

          (ArtifactCoordinates.GROUP) shouldBe (coordinates.getProperty("group"))
          (ArtifactCoordinates.VERSION) shouldBe (coordinates.getProperty("version"))
        }

        it("TestKitでPluginを適用してscaffold taskを実行できる") {
          val projectDir = Files.createTempDirectory("datastore-inspector-plugin-test")
          projectDir.resolve("settings.gradle.kts").writeText(
            """rootProject.name = "test-consumer""""
          )
          projectDir.resolve("build.gradle.kts").writeText(
            """
            plugins {
                id("com.masaibar.datastore-inspector")
            }
            """.trimIndent()
          )

          val result =
            GradleRunner.create()
              .withProjectDir(projectDir.toFile())
              .withPluginClasspath()
              .withArguments(
                "dataStoreInspectorInfo",
                "--stacktrace",
                "--console=plain"
              )
              .build()

          (result.task(":dataStoreInspectorInfo")?.outcome) shouldBe (TaskOutcome.SUCCESS)
          (result.output.contains("DataStore Inspector Gradle Plugin scaffold is active.")) shouldBe true
        }

        it("schema producerがimport closureと決定的なindexを生成する") {
          val common =
            FileDescriptorProto.newBuilder()
              .setName("common/profile.proto")
              .setPackage("sample.common")
              .addMessageType(DescriptorProto.newBuilder().setName("Profile"))
              .build()
          val settings =
            FileDescriptorProto.newBuilder()
              .setName("user_settings.proto")
              .setPackage("sample")
              .addDependency("common/profile.proto")
              .addMessageType(DescriptorProto.newBuilder().setName("UserSettings"))
              .build()
          val fragment =
            FileDescriptorSet.newBuilder()
              .addFile(settings)
              .addFile(common)
              .build()
              .toByteArray()
          val output = Files.createTempDirectory("datastore-inspector-schema").toFile()

          SchemaIndexProducer.produce(
            fragments = listOf(fragment),
            mappings = listOf("dev.example.UserSettings=sample.UserSettings"),
            outputDirectory = output
          )

          val index = output.resolve("datastore-inspector/schema-index.json").readText()
          val digest = Regex("[0-9a-f]{64}").find(index)?.value
          withClue("indexに小文字SHA-256 digestがありません。") { (digest != null) shouldBe true }
          val descriptor = output.resolve("datastore-inspector/schemas/$digest.desc")
          (descriptor.isFile) shouldBe true
          (
            MessageDigest.getInstance("SHA-256")
              .digest(descriptor.readBytes())
              .joinToString("") { "%02x".format(it) }
          ) shouldBe (digest)
          (FileDescriptorSet.parseFrom(descriptor.readBytes()).fileList.map { it.name }) shouldBe (listOf("common/profile.proto", "user_settings.proto"))
          (index.contains("\"codeGenerationMode\": \"JAVA_LITE\"")) shouldBe true
        }
      }
    }
  }
}
