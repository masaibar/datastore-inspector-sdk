package com.masaibar.datastore.inspector.gradle

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.DescriptorProtos.FileOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
      context("when verifying existing Plugin contracts") {
        it("loads Plugin artifact coordinates from the canonical properties") {
          val coordinates = Properties().apply {
            File("../gradle/artifact-coordinates.properties")
              .inputStream()
              .use(::load)
          }

          (ArtifactCoordinates.GROUP) shouldBe (coordinates.getProperty("group"))
          (ArtifactCoordinates.VERSION) shouldBe (coordinates.getProperty("version"))
        }

        it("applies the Plugin and runs its scaffold task with TestKit") {
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

        it("generates automatic mappings and a deterministic schema index") {
          val common =
            FileDescriptorProto.newBuilder()
              .setName("common/profile.proto")
              .setPackage("sample.common")
              .setSyntax("proto3")
              .setOptions(
                FileOptions.newBuilder()
                  .setJavaPackage("dev.example.common")
                  .setJavaMultipleFiles(true)
              )
              .addMessageType(DescriptorProto.newBuilder().setName("Profile"))
              .build()
          val settings =
            FileDescriptorProto.newBuilder()
              .setName("user_settings.proto")
              .setPackage("sample")
              .setSyntax("proto3")
              .setOptions(
                FileOptions.newBuilder()
                  .setJavaPackage("dev.example")
                  .setJavaMultipleFiles(true)
              )
              .addDependency("common/profile.proto")
              .addMessageType(DescriptorProto.newBuilder().setName("UserSettings"))
              .build()
          val settingsFragment = descriptorFragment(settings)
          val commonFragment = descriptorFragment(common)
          val output = Files.createTempDirectory("datastore-inspector-schema").toFile()
          val reversedOutput =
            Files.createTempDirectory("datastore-inspector-schema-reversed").toFile()

          val entryCount = SchemaIndexProducer.produce(
            fragments = listOf(settingsFragment, commonFragment),
            sourceProtoPaths =
              listOf(
                "/project/src/main/proto/user_settings.proto",
                "/project/src/main/proto/common/profile.proto"
              ),
            explicitMappings = emptyList(),
            outputDirectory = output
          )
          SchemaIndexProducer.produce(
            fragments = listOf(commonFragment, settingsFragment),
            sourceProtoPaths =
              listOf(
                "/project/src/main/proto/common/profile.proto",
                "/project/src/main/proto/user_settings.proto"
              ),
            explicitMappings = emptyList(),
            outputDirectory = reversedOutput
          )

          val index = output.resolve("datastore-inspector/schema-index.json").readText()
          val digest = Regex("[0-9a-f]{64}").find(index)?.value
          withClue("The index does not contain a lowercase SHA-256 digest.") {
            (digest != null) shouldBe true
          }
          val descriptor = output.resolve("datastore-inspector/schemas/$digest.desc")
          entryCount shouldBe 2
          (descriptor.isFile) shouldBe true
          (
            MessageDigest.getInstance("SHA-256")
              .digest(descriptor.readBytes())
              .joinToString("") { "%02x".format(it) }
          ) shouldBe (digest)
          (FileDescriptorSet.parseFrom(descriptor.readBytes()).fileList.map { it.name }) shouldBe (listOf("common/profile.proto", "user_settings.proto"))
          (index.contains("\"codeGenerationMode\": \"JAVA_LITE\"")) shouldBe true
          index shouldContain "dev.example.UserSettings"
          index shouldContain "dev.example.common.Profile"
          index shouldBe
            reversedOutput.resolve("datastore-inspector/schema-index.json").readText()
        }

        it("deduplicates an explicit mapping that matches an automatic mapping") {
          val settings = javaLiteMessageDescriptor()
          val output = Files.createTempDirectory("datastore-inspector-schema-explicit").toFile()

          val entryCount = SchemaIndexProducer.produce(
            fragments = listOf(descriptorFragment(settings)),
            sourceProtoPaths = listOf("/project/src/main/proto/user_settings.proto"),
            explicitMappings = listOf("dev.example.UserSettings=sample.UserSettings"),
            outputDirectory = output
          )

          entryCount shouldBe 1
        }

        it("rejects an explicit mapping that conflicts with an automatic mapping") {
          val settings = javaLiteMessageDescriptor()
          val profile =
            FileDescriptorProto.newBuilder()
              .setName("profile.proto")
              .setPackage("sample")
              .setSyntax("proto3")
              .setOptions(
                FileOptions.newBuilder()
                  .setJavaPackage("dev.example")
                  .setJavaMultipleFiles(true)
              )
              .addMessageType(DescriptorProto.newBuilder().setName("Profile"))
              .build()
          val output = Files.createTempDirectory("datastore-inspector-schema-conflict").toFile()

          shouldThrow<IllegalArgumentException> {
            SchemaIndexProducer.produce(
              fragments = listOf(descriptorFragment(settings, profile)),
              sourceProtoPaths =
                listOf(
                  "/project/src/main/proto/user_settings.proto",
                  "/project/src/main/proto/profile.proto"
                ),
              explicitMappings = listOf("dev.example.UserSettings=sample.Profile"),
              outputDirectory = output
            )
          }.message.orEmpty() shouldContain
            "dev.example.UserSettings -> [sample.Profile, sample.UserSettings]"
        }
      }

      context("when a first-party Proto source imports an external Proto file") {
        val imported =
          FileDescriptorProto.newBuilder()
            .setName("external/profile.proto")
            .setPackage("external")
            .setSyntax("proto3")
            .setOptions(
              FileOptions.newBuilder()
                .setJavaPackage("external.generated")
                .setJavaMultipleFiles(true)
            )
            .addMessageType(DescriptorProto.newBuilder().setName("Profile"))
            .build()
        val settings =
          javaLiteMessageDescriptor().toBuilder()
            .addDependency("external/profile.proto")
            .build()
        val output = Files.createTempDirectory("datastore-inspector-schema-import").toFile()

        it("keeps the imported descriptor but maps only the first-party source") {
          val entryCount =
            SchemaIndexProducer.produce(
              fragments = listOf(descriptorFragment(imported, settings)),
              sourceProtoPaths = listOf("/project/src/main/proto/user_settings.proto"),
              explicitMappings = emptyList(),
              outputDirectory = output
            )

          val index = output.resolve("datastore-inspector/schema-index.json").readText()
          val digest = Regex("[0-9a-f]{64}").find(index)?.value
          val descriptor = output.resolve("datastore-inspector/schemas/$digest.desc")
          entryCount shouldBe 1
          index shouldContain "dev.example.UserSettings"
          (index.contains("external.generated.Profile")) shouldBe false
          (FileDescriptorSet.parseFrom(descriptor.readBytes()).fileList.map { it.name }) shouldBe
            listOf("external/profile.proto", "user_settings.proto")
        }
      }

      context("when detecting the Proto Java generation mode") {
        it("accepts a java builtin that explicitly enables lite") {
          ProtoTaskSchemaSources.hasJavaLiteBuiltin(
            mapOf(
              "builtinsForCaching.java\$0.name" to "java",
              "builtinsForCaching.java\$0.options" to listOf("lite")
            )
          ) shouldBe true
        }

        it("rejects a java builtin without the lite option") {
          ProtoTaskSchemaSources.hasJavaLiteBuiltin(
            mapOf(
              "builtinsForCaching.java\$0.name" to "java",
              "builtinsForCaching.java\$0.options" to emptyList<String>()
            )
          ) shouldBe false
        }

        it("rejects an unknown task input shape") {
          ProtoTaskSchemaSources.hasJavaLiteBuiltin(emptyMap()) shouldBe false
        }
      }
    }
  }

  private companion object {
    fun javaLiteMessageDescriptor(): FileDescriptorProto =
      FileDescriptorProto.newBuilder()
        .setName("user_settings.proto")
        .setPackage("sample")
        .setSyntax("proto3")
        .setOptions(
          FileOptions.newBuilder()
            .setJavaPackage("dev.example")
            .setJavaMultipleFiles(true)
        )
        .addMessageType(DescriptorProto.newBuilder().setName("UserSettings"))
        .build()

    fun descriptorFragment(vararg files: FileDescriptorProto): ByteArray =
      FileDescriptorSet.newBuilder()
        .addAllFile(files.asList())
        .build()
        .toByteArray()
  }
}
