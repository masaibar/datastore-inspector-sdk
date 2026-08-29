package com.masaibar.datastore.inspector.gradle

import com.google.protobuf.DescriptorProtos.DescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet

internal data class SchemaMapping(
  val generatedJvmClassName: String,
  val rootMessageFullName: String
)

internal object ProtoJvmClassNameResolver {
  fun resolve(descriptorSet: FileDescriptorSet): List<SchemaMapping> {
    val mappings =
      descriptorSet.fileList
        .filterNot { it.syntax == EDITIONS_SYNTAX }
        .flatMap(::resolveFile)

    val conflict =
      mappings
        .groupBy(SchemaMapping::generatedJvmClassName)
        .entries
        .firstOrNull { (_, entries) ->
          entries.map(SchemaMapping::rootMessageFullName).distinct().size > 1
        }
    require(conflict == null) {
      "Generated JVM class maps to multiple Proto messages: " +
        "${conflict?.key} -> " +
        conflict?.value
          .orEmpty()
          .map(SchemaMapping::rootMessageFullName)
          .distinct()
          .sorted()
    }

    return mappings
      .distinct()
      .sortedWith(
        compareBy(
          SchemaMapping::generatedJvmClassName,
          SchemaMapping::rootMessageFullName
        )
      )
  }

  private fun resolveFile(file: FileDescriptorProto): List<SchemaMapping> {
    val javaPackage =
      if (file.options.hasJavaPackage()) {
        file.options.javaPackage
      } else {
        file.`package`
      }
    val javaPrefix =
      if (file.options.javaMultipleFiles) {
        javaPackage
      } else {
        join(javaPackage, outerClassName(file))
      }
    val topLevelClassSeparator =
      if (file.options.javaMultipleFiles) {
        JAVA_PACKAGE_SEPARATOR
      } else {
        JVM_NESTED_CLASS_SEPARATOR
      }
    return file.messageTypeList.flatMap { message ->
      resolveMessage(
        message = message,
        javaPrefix = javaPrefix,
        topLevelClassSeparator = topLevelClassSeparator,
        protoPackage = file.`package`,
        parentNames = emptyList()
      )
    }
  }

  private fun resolveMessage(
    message: DescriptorProto,
    javaPrefix: String,
    topLevelClassSeparator: String,
    protoPackage: String,
    parentNames: List<String>
  ): List<SchemaMapping> {
    val messageNames = parentNames + message.name
    val current =
      if (message.options.mapEntry) {
        emptyList()
      } else {
        listOf(
          SchemaMapping(
            generatedJvmClassName =
              join(
                prefix = javaPrefix,
                suffix = messageNames.joinToString(JVM_NESTED_CLASS_SEPARATOR),
                separator = topLevelClassSeparator
              ),
            rootMessageFullName =
              join(protoPackage, messageNames.joinToString(PROTO_NAME_SEPARATOR))
          )
        )
      }
    return current +
      message.nestedTypeList.flatMap { nested ->
        resolveMessage(
          message = nested,
          javaPrefix = javaPrefix,
          topLevelClassSeparator = topLevelClassSeparator,
          protoPackage = protoPackage,
          parentNames = messageNames
        )
      }
  }

  private fun outerClassName(file: FileDescriptorProto): String {
    if (file.options.hasJavaOuterClassname()) {
      return file.options.javaOuterClassname
    }
    val fileName = file.name.substringAfterLast('/')
    val protoBaseName =
      when {
        fileName.endsWith(PROTODEVEL_SUFFIX) -> fileName.removeSuffix(PROTODEVEL_SUFFIX)
        fileName.endsWith(PROTO_SUFFIX) -> fileName.removeSuffix(PROTO_SUFFIX)
        else -> fileName
      }
    require(protoBaseName.isNotEmpty()) {
      "Cannot derive the Java outer class name from Proto file: ${file.name}"
    }
    val defaultName = underscoresToCamelCase(protoBaseName)
    require(defaultName.isNotEmpty()) {
      "Cannot derive the Java outer class name from Proto file: ${file.name}"
    }
    return if (hasConflictingClassName(file, defaultName)) {
      defaultName + OUTER_CLASS_SUFFIX
    } else {
      defaultName
    }
  }

  private fun hasConflictingClassName(
    file: FileDescriptorProto,
    className: String
  ): Boolean =
    file.enumTypeList.any { it.name == className } ||
      file.serviceList.any { it.name == className } ||
      file.messageTypeList.any { messageHasConflictingClassName(it, className) }

  private fun messageHasConflictingClassName(
    message: DescriptorProto,
    className: String
  ): Boolean =
    message.name == className ||
      message.enumTypeList.any { it.name == className } ||
      message.nestedTypeList.any { nested ->
        messageHasConflictingClassName(nested, className)
      }

  private fun underscoresToCamelCase(value: String): String =
    buildString {
      var capitalizeNext = true
      value.forEachIndexed { index, character ->
        when (character) {
          in 'a'..'z' -> {
            append(if (capitalizeNext) character.uppercaseChar() else character)
            capitalizeNext = false
          }

          in 'A'..'Z' -> {
            append(
              if (index == 0 && !capitalizeNext) {
                character.lowercaseChar()
              } else {
                character
              }
            )
            capitalizeNext = false
          }

          in '0'..'9' -> {
            append(character)
            capitalizeNext = true
          }

          else -> capitalizeNext = true
        }
      }
      if (value.lastOrNull() == '#') append('_')
    }

  private fun join(
    prefix: String,
    suffix: String,
    separator: String = JAVA_PACKAGE_SEPARATOR
  ): String = if (prefix.isEmpty()) suffix else "$prefix$separator$suffix"

  private const val EDITIONS_SYNTAX = "editions"
  private const val PROTO_SUFFIX = ".proto"
  private const val PROTODEVEL_SUFFIX = ".protodevel"
  private const val OUTER_CLASS_SUFFIX = "OuterClass"
  private const val JVM_NESTED_CLASS_SEPARATOR = "\$"
  private const val JAVA_PACKAGE_SEPARATOR = "."
  private const val PROTO_NAME_SEPARATOR = "."
}
