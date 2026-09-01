package com.masaibar.datastore.inspector.gradle

import com.google.protobuf.DescriptorProtos.FileDescriptorProto
import com.google.protobuf.DescriptorProtos.FileDescriptorSet
import org.gradle.api.DefaultTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import javax.inject.Inject

private val GENERATED_JVM_CLASS_NAME: Regex =
  Regex("^[A-Za-z_][A-Za-z0-9_$]*(\\.[A-Za-z_][A-Za-z0-9_$]*)*$")
private val PROTO_MESSAGE_FULL_NAME: Regex =
  Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*$")

@StableDataStoreInspectorGradleApi
public abstract class DataStoreInspectorExtension @Inject constructor(objects: ObjectFactory) {
  internal val schemaMappings: ListProperty<String> =
    objects.listProperty(String::class.java).convention(emptyList())
  internal val customCodecBindings: ListProperty<String> =
    objects.listProperty(String::class.java).convention(emptyList())

  @StableDataStoreInspectorGradleApi
  public fun schemaEntry(generatedJvmClassName: String, rootMessageFullName: String) {
    require(GENERATED_JVM_CLASS_NAME.matches(generatedJvmClassName)) {
      "Invalid generated JVM class name: $generatedJvmClassName"
    }
    require(PROTO_MESSAGE_FULL_NAME.matches(rootMessageFullName)) {
      "Invalid fully qualified Proto message name: $rootMessageFullName"
    }
    schemaMappings.add("$generatedJvmClassName=$rootMessageFullName")
  }

  /**
   * debug variantへだけ生成するCustom DataStore codec bindingです。
   *
   * 生成Java sourceが`InspectorCustomCodec<ValueClass>`の型関係とcodecのpublic no-arg
   * constructorをcompile時に検証します。class名の文字列自体をRuntimeへ渡すことはありません。
   */
  @ExperimentalDataStoreInspectorGradleApi
  public fun customCodecBinding(
    serializerClassName: String,
    valueClassName: String,
    codecClassName: String
  ) {
    listOf(serializerClassName, valueClassName, codecClassName).forEach { className ->
      require(CLASS_NAME.matches(className)) {
        "codec bindingのJVM class名が不正です: $className"
      }
    }
    customCodecBindings.add(
      listOf(serializerClassName, valueClassName, codecClassName).joinToString("=")
    )
  }

  private companion object {
    val CLASS_NAME: Regex =
      Regex("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+$")
  }
}

@CacheableTask
@InternalDataStoreInspectorGradleApi
public abstract class GenerateCustomCodecBindingsTask : DefaultTask() {
  @get:Input
  public abstract val bindings: ListProperty<String>

  @get:OutputDirectory
  public abstract val javaOutputDirectory: DirectoryProperty

  @get:OutputDirectory
  public abstract val resourcesOutputDirectory: DirectoryProperty

  @TaskAction
  public fun generate() {
    CustomCodecBindingSourceProducer.produce(
      mappings = bindings.get(),
      javaOutputDirectory = javaOutputDirectory.get().asFile,
      resourcesOutputDirectory = resourcesOutputDirectory.get().asFile
    )
    logger.lifecycle(
      "DataStore Inspector debug codec binding生成成功: ${bindings.get().size} entry"
    )
  }
}

internal object CustomCodecBindingSourceProducer {
  const val PROVIDER_CLASS: String =
    "com.masaibar.datastore.inspector.generated.GeneratedCustomCodecBindingProvider"
  const val SERVICE_INTERFACE: String =
    "com.masaibar.datastore.inspector.runtime.core.InspectorCustomCodecBindingProvider"

  fun produce(
    mappings: List<String>,
    javaOutputDirectory: java.io.File,
    resourcesOutputDirectory: java.io.File
  ) {
    javaOutputDirectory.deleteRecursively()
    resourcesOutputDirectory.deleteRecursively()
    if (mappings.isEmpty()) return

    val parsed = mappings.map(::parse)
    val duplicate =
      parsed.groupBy { it.serializerClassName to it.valueClassName }
        .entries
        .firstOrNull { (_, values) -> values.size > 1 }
    require(duplicate == null) {
      "同じserializer/valueへ複数codec bindingがあります: ${duplicate?.key}"
    }

    val entries =
      parsed.sortedWith(
        compareBy<CodecBindingMapping>(
          CodecBindingMapping::serializerClassName,
          CodecBindingMapping::valueClassName
        )
      ).joinToString(",\n") { mapping ->
        """
                new com.masaibar.datastore.inspector.runtime.core.InspectorCustomCodecBinding<>(
                    ${mapping.serializerClassName}.class,
                    ${mapping.valueClassName}.class,
                    new ${mapping.codecClassName}()
                )
        """.trimIndent().prependIndent("            ")
      }
    val source =
      """
            package com.masaibar.datastore.inspector.generated;

            public final class GeneratedCustomCodecBindingProvider
                    implements com.masaibar.datastore.inspector.runtime.core.InspectorCustomCodecBindingProvider {
                @Override
                public String getProviderId() {
                    return "gradle-generated-custom-codec-v1";
                }

                @Override
                public java.util.List<com.masaibar.datastore.inspector.runtime.core.InspectorCustomCodecBinding<?>> bindings() {
                    return java.util.Arrays.asList(
            $entries
                    );
                }
            }
      """.trimIndent() + "\n"
    javaOutputDirectory
      .resolve(PROVIDER_CLASS.replace('.', '/') + ".java")
      .apply {
        parentFile.mkdirs()
        writeText(source)
      }
    resourcesOutputDirectory
      .resolve("META-INF/services/$SERVICE_INTERFACE")
      .apply {
        parentFile.mkdirs()
        writeText("$PROVIDER_CLASS\n")
      }
  }

  private fun parse(value: String): CodecBindingMapping {
    val parts = value.split('=')
    require(parts.size == 3 && parts.all(String::isNotBlank)) {
      "codec binding mappingが不正です。"
    }
    return CodecBindingMapping(parts[0], parts[1], parts[2])
  }

  private data class CodecBindingMapping(
    val serializerClassName: String,
    val valueClassName: String,
    val codecClassName: String
  )
}

@CacheableTask
@InternalDataStoreInspectorGradleApi
public abstract class GenerateDataStoreInspectorSchemaTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  public abstract val descriptorFragments: ConfigurableFileCollection

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  public abstract val protoSources: ConfigurableFileCollection

  @get:Input
  public abstract val schemaMappings: ListProperty<String>

  @get:OutputDirectory
  public abstract val outputDirectory: DirectoryProperty

  @TaskAction
  public fun generate() {
    val fragments = descriptorFragments.files.sortedBy { it.absolutePath }
    val explicitMappings = schemaMappings.get()
    if (fragments.isEmpty()) {
      outputDirectory.get().asFile.deleteRecursively()
      logger.lifecycle(
        "DataStore Inspector schema generation skipped: no descriptor fragments"
      )
      return
    }
    val entryCount = SchemaIndexProducer.produce(
      fragments = fragments.map { it.readBytes() },
      sourceProtoPaths = protoSources.files.map { it.invariantSeparatorsPath },
      explicitMappings = explicitMappings,
      outputDirectory = outputDirectory.get().asFile
    )
    if (entryCount == 0) {
      logger.lifecycle(
        "DataStore Inspector schema generation skipped: no supported Proto messages"
      )
    } else {
      logger.lifecycle(
        "DataStore Inspector schema generation succeeded: $entryCount entries, " +
          "${fragments.size} descriptor fragments, " +
          "${explicitMappings.size} explicit mappings"
      )
    }
  }
}

internal object SchemaIndexProducer {
  private const val MAX_DESCRIPTOR_BYTES = 8 * 1024 * 1024

  fun produce(
    fragments: List<ByteArray>,
    sourceProtoPaths: List<String>,
    explicitMappings: List<String>,
    outputDirectory: java.io.File
  ): Int {
    require(fragments.isNotEmpty()) { "No descriptor fragments were provided." }
    val filesByName = linkedMapOf<String, ByteArray>()
    fragments.forEach { fragment ->
      FileDescriptorSet.parseFrom(fragment).fileList.forEach { descriptor ->
        val bytes = descriptor.toByteArray()
        val previous = filesByName.putIfAbsent(descriptor.name, bytes)
        require(previous == null || previous.contentEquals(bytes)) {
          "Proto files with the same name have conflicting content: ${descriptor.name}"
        }
      }
    }
    val descriptorSet =
      FileDescriptorSet.newBuilder()
        .addAllFile(
          filesByName.toSortedMap().values.map(FileDescriptorProto::parseFrom)
        )
        .build()
    val merged = descriptorSet.toByteArray()
    require(merged.size <= MAX_DESCRIPTOR_BYTES) {
      "The descriptor bundle exceeds 8 MiB."
    }
    val digest = sha256(merged)
    val parsedExplicitMappings = explicitMappings.map(::parseMapping)
    require(
      parsedExplicitMappings
        .map(SchemaMapping::generatedJvmClassName)
        .distinct()
        .size == parsedExplicitMappings.size
    ) {
      "Multiple explicit entries target the same generated JVM class."
    }
    val messageNames = collectMessageNames(descriptorSet)
    parsedExplicitMappings.forEach { mapping ->
      require(mapping.rootMessageFullName in messageNames) {
        "The descriptor bundle does not contain message: " +
          mapping.rootMessageFullName
      }
    }
    val firstPartyDescriptorNames =
      resolveFirstPartyDescriptorNames(
        sourceProtoPaths = sourceProtoPaths,
        descriptorNames = filesByName.keys
      )
    val firstPartyDescriptorSet =
      FileDescriptorSet.newBuilder()
        .addAllFile(
          descriptorSet.fileList.filter { it.name in firstPartyDescriptorNames }
        )
        .build()
    val mappings =
      mergeMappings(
        automaticMappings = ProtoJvmClassNameResolver.resolve(firstPartyDescriptorSet),
        explicitMappings = parsedExplicitMappings
      )

    outputDirectory.deleteRecursively()
    if (mappings.isEmpty()) return 0

    val assetPath = "datastore-inspector/schemas/$digest.desc"
    outputDirectory.resolve(assetPath).apply {
      parentFile.mkdirs()
      writeBytes(merged)
    }
    val entries =
      mappings.joinToString(",\n") { mapping ->
        """
                {
                  "generatedJvmClassName": "${mapping.generatedJvmClassName}",
                  "rootMessageFullName": "${mapping.rootMessageFullName}",
                  "codeGenerationMode": "JAVA_LITE",
                  "descriptorDigestSha256": "$digest",
                  "descriptorAssetPath": "$assetPath"
                }
        """.trimIndent().prependIndent("    ")
      }
    outputDirectory.resolve("datastore-inspector/schema-index.json").apply {
      parentFile.mkdirs()
      writeText(
        buildString {
          appendLine("{")
          appendLine("  \"formatVersion\": 1,")
          appendLine("  \"entries\": [")
          appendLine(entries)
          appendLine("  ]")
          appendLine("}")
        }
      )
    }
    return mappings.size
  }

  private fun mergeMappings(
    automaticMappings: List<SchemaMapping>,
    explicitMappings: List<SchemaMapping>
  ): List<SchemaMapping> {
    val entriesByClass =
      (automaticMappings + explicitMappings)
        .groupBy(SchemaMapping::generatedJvmClassName)
    val conflict =
      entriesByClass.entries.firstOrNull { (_, entries) ->
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
    return entriesByClass.values
      .map { entries -> entries.first() }
      .sortedBy(SchemaMapping::generatedJvmClassName)
  }

  private fun collectMessageNames(set: FileDescriptorSet): Set<String> =
    buildSet {
      set.fileList.forEach { file ->
        fun addMessages(prefix: String, messages: List<com.google.protobuf.DescriptorProtos.DescriptorProto>) {
          messages.forEach { message ->
            val fullName = if (prefix.isEmpty()) message.name else "$prefix.${message.name}"
            add(fullName)
            addMessages(fullName, message.nestedTypeList)
          }
        }
        addMessages(file.`package`, file.messageTypeList)
      }
    }

  private fun resolveFirstPartyDescriptorNames(
    sourceProtoPaths: List<String>,
    descriptorNames: Set<String>
  ): Set<String> =
    sourceProtoPaths.mapTo(linkedSetOf()) { sourcePath ->
      descriptorNames
        .filter { descriptorName ->
          sourcePath == descriptorName || sourcePath.endsWith("/$descriptorName")
        }
        .maxByOrNull(String::length)
        ?: error("No descriptor was generated for Proto source: $sourcePath")
    }

  private fun parseMapping(mapping: String): SchemaMapping {
    val parts = mapping.split('=', limit = 2)
    require(
      parts.size == 2 &&
        GENERATED_JVM_CLASS_NAME.matches(parts[0]) &&
        PROTO_MESSAGE_FULL_NAME.matches(parts[1])
    ) {
      "Schema mapping must contain valid generated JVM and Proto message names."
    }
    return SchemaMapping(
      generatedJvmClassName = parts[0],
      rootMessageFullName = parts[1]
    )
  }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** application moduleへ1回適用するGradle Pluginです。 */
@InternalDataStoreInspectorGradleApi
public class DataStoreInspectorPlugin : Plugin<Project> {
  override fun apply(target: Project) {
    val extension =
      target.extensions.create(
        "dataStoreInspector",
        DataStoreInspectorExtension::class.java
      )
    target.tasks.register("dataStoreInspectorInfo") { task ->
      task.group = "datastore inspector"
      task.description = "DataStore Inspector Gradle Pluginの状態を表示します。"
      task.doLast {
        task.logger.lifecycle("DataStore Inspector Gradle Plugin scaffold is active.")
      }
    }

    target.pluginManager.withPlugin("com.android.application") {
      AndroidVariantIntegration.configureApplication(target, extension)
    }
    target.pluginManager.withPlugin("com.android.library") {
      AndroidVariantIntegration.configureLibrary(target)
    }
  }
}
