package com.masaibar.datastore.inspector.gradle

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import com.google.protobuf.DescriptorProtos.FileOptions
import com.google.protobuf.DescriptorProtos.MessageOptions
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.string.shouldContain

class ProtoJvmClassNameResolverSpec : DescribeSpec() {
  init {
    describe("resolve") {
      context("when Java Lite messages use separate generated files") {
        val descriptorSet =
          descriptorSet(
            FileDescriptorProto.newBuilder()
              .setName("settings/user_settings.proto")
              .setPackage("example.settings")
              .setSyntax("proto3")
              .setOptions(
                FileOptions.newBuilder()
                  .setJavaPackage("com.example.settings.proto")
                  .setJavaMultipleFiles(true)
              )
              .addMessageType(
                DescriptorProto.newBuilder()
                  .setName("UserSettings")
                  .addNestedType(
                    DescriptorProto.newBuilder().setName("NotificationSettings")
                  )
                  .addNestedType(
                    DescriptorProto.newBuilder()
                      .setName("FeatureFlagsEntry")
                      .setOptions(MessageOptions.newBuilder().setMapEntry(true))
                  )
              )
              .build()
          )

        it("maps top-level and nested messages and excludes synthetic map entries") {
          ProtoJvmClassNameResolver.resolve(descriptorSet).shouldContainExactly(
            SchemaMapping(
              generatedJvmClassName = "com.example.settings.proto.UserSettings",
              rootMessageFullName = "example.settings.UserSettings"
            ),
            SchemaMapping(
              generatedJvmClassName =
                "com.example.settings.proto.UserSettings\$NotificationSettings",
              rootMessageFullName =
                "example.settings.UserSettings.NotificationSettings"
            )
          )
        }
      }

      context("when generated messages are nested in the default outer class") {
        val descriptorSet =
          descriptorSet(
            FileDescriptorProto.newBuilder()
              .setName("settings/user_settings.proto")
              .setPackage("example.settings")
              .setSyntax("proto2")
              .addMessageType(
                DescriptorProto.newBuilder()
                  .setName("UserSettings")
                  .addNestedType(DescriptorProto.newBuilder().setName("NestedValue"))
              )
              .build()
          )

        it("uses the Proto package and appends OuterClass for a symbol collision") {
          ProtoJvmClassNameResolver.resolve(descriptorSet).shouldContainExactly(
            SchemaMapping(
              generatedJvmClassName =
                "example.settings.UserSettingsOuterClass\$UserSettings",
              rootMessageFullName = "example.settings.UserSettings"
            ),
            SchemaMapping(
              generatedJvmClassName =
                "example.settings.UserSettingsOuterClass\$UserSettings\$NestedValue",
              rootMessageFullName = "example.settings.UserSettings.NestedValue"
            )
          )
        }
      }

      context("when the outer class name and Java package are explicit") {
        val descriptorSet =
          descriptorSet(
            FileDescriptorProto.newBuilder()
              .setName("settings.proto")
              .setPackage("example.settings")
              .setSyntax("proto3")
              .setOptions(
                FileOptions.newBuilder()
                  .setJavaPackage("com.example.generated")
                  .setJavaOuterClassname("SettingsProto")
              )
              .addMessageType(DescriptorProto.newBuilder().setName("Value"))
              .build()
          )

        it("uses the explicit outer class without deriving a replacement") {
          ProtoJvmClassNameResolver.resolve(descriptorSet).shouldContainExactly(
            SchemaMapping(
              generatedJvmClassName = "com.example.generated.SettingsProto\$Value",
              rootMessageFullName = "example.settings.Value"
            )
          )
        }
      }

      context("when a nested enum conflicts with the default outer class name") {
        val descriptorSet =
          descriptorSet(
            FileDescriptorProto.newBuilder()
              .setName("sample.proto")
              .setSyntax("proto3")
              .addMessageType(
                DescriptorProto.newBuilder()
                  .setName("Container")
                  .addEnumType(
                    com.google.protobuf.DescriptorProtos.EnumDescriptorProto
                      .newBuilder()
                      .setName("Sample")
                  )
              )
              .build()
          )

        it("appends OuterClass and does not add a leading package separator") {
          ProtoJvmClassNameResolver.resolve(descriptorSet).shouldContainExactly(
            SchemaMapping(
              generatedJvmClassName = "SampleOuterClass\$Container",
              rootMessageFullName = "Container"
            )
          )
        }
      }

      context("when a Proto file uses Editions") {
        val descriptorSet =
          descriptorSet(
            FileDescriptorProto.newBuilder()
              .setName("edition_settings.proto")
              .setPackage("example.settings")
              .setSyntax("editions")
              .addMessageType(DescriptorProto.newBuilder().setName("EditionSettings"))
              .build()
          )

        it("does not claim an automatic JVM mapping") {
          ProtoJvmClassNameResolver.resolve(descriptorSet).shouldContainExactly()
        }
      }

      context("when two Proto messages resolve to the same JVM class") {
        val descriptorSet =
          descriptorSet(
            FileDescriptorProto.newBuilder()
              .setName("first.proto")
              .setPackage("example.first")
              .setSyntax("proto3")
              .setOptions(
                FileOptions.newBuilder()
                  .setJavaPackage("com.example.generated")
                  .setJavaMultipleFiles(true)
              )
              .addMessageType(DescriptorProto.newBuilder().setName("Settings"))
              .build(),
            FileDescriptorProto.newBuilder()
              .setName("second.proto")
              .setPackage("example.second")
              .setSyntax("proto3")
              .setOptions(
                FileOptions.newBuilder()
                  .setJavaPackage("com.example.generated")
                  .setJavaMultipleFiles(true)
              )
              .addMessageType(DescriptorProto.newBuilder().setName("Settings"))
              .build()
          )

        it("fails instead of selecting one schema") {
          shouldThrow<IllegalArgumentException> {
            ProtoJvmClassNameResolver.resolve(descriptorSet)
          }.message.orEmpty() shouldContain
            "com.example.generated.Settings -> [example.first.Settings, example.second.Settings]"
        }
      }
    }
  }

  private companion object {
    fun descriptorSet(vararg files: FileDescriptorProto): FileDescriptorSet =
      FileDescriptorSet.newBuilder().addAllFile(files.asList()).build()
  }
}
