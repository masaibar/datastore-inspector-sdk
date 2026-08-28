import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import java.io.File
import java.io.InputStream
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

data class PublishedArtifactMetadata(
  val artifactProperty: String,
  val displayName: String,
  val description: String,
  val extension: String
)

abstract class VerifyMavenPublicationRepository : DefaultTask() {
  @get:InputDirectory
  abstract val repositoryDirectory: DirectoryProperty

  @get:Input
  abstract val groupId: Property<String>

  @get:Input
  abstract val publicationVersion: Property<String>

  @get:Input
  abstract val repositoryUrl: Property<String>

  @get:Input
  abstract val expectedArtifacts: MapProperty<String, String>

  @TaskAction
  fun verify() {
    val groupPath = groupId.get().replace('.', '/')
    expectedArtifacts.get().forEach { (artifactId, extension) ->
      val coordinateDirectory =
        repositoryDirectory.get().asFile
          .resolve(groupPath)
          .resolve(artifactId)
          .resolve(publicationVersion.get())
      check(coordinateDirectory.isDirectory) {
        "公開artifactのdirectoryがありません: $coordinateDirectory"
      }
      val publishedFiles = coordinateDirectory.listFiles().orEmpty().filter { it.isFile }
      fun latestArtifact(suffix: String, excludeClassifiers: Boolean = false): File {
        val candidates =
          publishedFiles.filter { file ->
            file.name.startsWith("$artifactId-") &&
              file.name.endsWith(suffix) &&
              (
                !excludeClassifiers ||
                  ("-sources.jar" !in file.name && "-javadoc.jar" !in file.name)
              )
          }
        val artifact = candidates.maxByOrNull(File::lastModified)
        check(artifact != null && artifact.length() > 0L) {
          "公開artifactがありません、または空です: " +
            "$coordinateDirectory/$artifactId-*$suffix"
        }
        return artifact
      }

      latestArtifact(".$extension", excludeClassifiers = extension == "jar")
      latestArtifact("-sources.jar")
      latestArtifact("-javadoc.jar")
      val pomFile = latestArtifact(".pom")
      val moduleMetadataFile = latestArtifact(".module")

      val documentBuilderFactory =
        DocumentBuilderFactory.newInstance().apply {
          setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
          isExpandEntityReferences = false
        }
      val pom = documentBuilderFactory.newDocumentBuilder().parse(pomFile)
      fun requiredText(tagName: String): String {
        val value = pom.getElementsByTagName(tagName).item(0)?.textContent?.trim().orEmpty()
        check(value.isNotEmpty()) { "POMに${tagName}がありません: $pomFile" }
        return value
      }

      check(requiredText("groupId") == groupId.get()) {
        "POMのgroupIdが正本と一致しません: $pomFile"
      }
      check(requiredText("artifactId") == artifactId) {
        "POMのartifactIdが正本と一致しません: $pomFile"
      }
      check(requiredText("version") == publicationVersion.get()) {
        "POMのversionが正本と一致しません: $pomFile"
      }
      requiredText("name")
      requiredText("description")
      check(requiredText("url") == repositoryUrl.get()) {
        "POMのproject URLが公開repositoryと一致しません: $pomFile"
      }
      requiredText("license")
      requiredText("developer")
      requiredText("scm")

      val groupIds = pom.getElementsByTagName("groupId")
      repeat(groupIds.length) { index ->
        val value = groupIds.item(index).textContent.trim()
        check(value != "datastore-inspector-sdk" && !value.startsWith("project ")) {
          "POMにproject依存が残っています: $pomFile ($value)"
        }
      }
      val moduleMetadata = moduleMetadataFile.readText()
      check("project :" !in moduleMetadata) {
        "Gradle Module Metadataにproject依存が残っています: $pomFile"
      }
    }
  }
}

abstract class VerifyPublishedJvmCompatibility : DefaultTask() {
  @get:InputDirectory
  abstract val repositoryDirectory: DirectoryProperty

  @get:Input
  abstract val groupId: Property<String>

  @get:Input
  abstract val publicationVersion: Property<String>

  @get:Input
  abstract val artifactExtensions: MapProperty<String, String>

  @get:Input
  abstract val expectedClassMajorVersions: MapProperty<String, Int>

  @get:Input
  abstract val requiredMetadataJvmVersions: MapProperty<String, Int>

  @get:OutputFile
  abstract val reportFile: RegularFileProperty

  @TaskAction
  fun verify() {
    val groupPath = groupId.get().replace('.', '/')
    val version = publicationVersion.get()
    val reportLines = mutableListOf<String>()
    artifactExtensions.get().forEach { (artifactId, extension) ->
      val coordinateDirectory =
        repositoryDirectory.get().asFile
          .resolve(groupPath)
          .resolve(artifactId)
          .resolve(version)
      val artifactFile = coordinateDirectory.resolve("$artifactId-$version.$extension")
      check(artifactFile.isFile) { "JVM互換性を検証するartifactがありません: $artifactFile" }

      val classMajorVersions = readClassMajorVersions(artifactFile, extension)
      check(classMajorVersions.isNotEmpty()) {
        "JVM互換性を検証できるclassがartifact内にありません: $artifactFile"
      }
      val expectedClassMajor = expectedClassMajorVersions.get().getValue(artifactId)
      val actualClassMajors = classMajorVersions.toSortedSet()
      check(actualClassMajors == setOf(expectedClassMajor)) {
        "${artifactId}のclass file versionが期待値と一致しません。" +
          "期待: $expectedClassMajor、実際: $actualClassMajors"
      }

      val moduleMetadataFile = coordinateDirectory.resolve("$artifactId-$version.module")
      check(moduleMetadataFile.isFile) {
        "Gradle Module Metadataがありません: $moduleMetadataFile"
      }
      val metadataJvmVersions =
        JVM_VERSION_ATTRIBUTE_REGEX
          .findAll(moduleMetadataFile.readText())
          .map { match -> match.groupValues[1].toInt() }
          .toSortedSet()
      val maximumSupportedJvmVersion = classMajorToJavaVersion(expectedClassMajor)
      check(metadataJvmVersions.none { it > maximumSupportedJvmVersion }) {
        "${artifactId}のGradle Module Metadataが" +
          "Java ${maximumSupportedJvmVersion}を超えています: " +
          metadataJvmVersions
      }
      requiredMetadataJvmVersions.get()[artifactId]?.let { requiredJvmVersion ->
        check(metadataJvmVersions == setOf(requiredJvmVersion)) {
          "${artifactId}のGradle Module Metadata JVM versionが期待値と一致しません。" +
            "期待: $requiredJvmVersion、実際: $metadataJvmVersions"
        }
      }

      reportLines +=
        "$artifactId: classMajor=$actualClassMajors, metadataJvm=$metadataJvmVersions"
    }

    val output = reportFile.get().asFile
    output.parentFile.mkdirs()
    output.writeText(reportLines.joinToString(separator = "\n", postfix = "\n"))
    logger.lifecycle("公開artifactのJVM互換性検証に成功しました: $output")
  }

  private fun readClassMajorVersions(
    artifactFile: File,
    extension: String
  ): List<Int> =
    when (extension) {
      "jar" -> readClassesFromZip(artifactFile)
      "aar" -> readClassesFromAar(artifactFile)
      else -> error("未対応のJVM artifact拡張子です: $extension")
    }

  private fun readClassesFromZip(artifactFile: File): List<Int> {
    val versions = mutableListOf<Int>()
    ZipFile(artifactFile).use { archive ->
      val entries = archive.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (!entry.isDirectory && entry.name.endsWith(".class")) {
          archive.getInputStream(entry).use { input ->
            versions += readClassMajorVersion(input, "$artifactFile!/${entry.name}")
          }
        }
      }
    }
    return versions
  }

  private fun readClassesFromAar(artifactFile: File): List<Int> {
    val versions = mutableListOf<Int>()
    ZipFile(artifactFile).use { archive ->
      val entries = archive.entries()
      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        if (entry.isDirectory) continue
        when {
          entry.name.endsWith(".class") ->
            archive.getInputStream(entry).use { input ->
              versions += readClassMajorVersion(input, "$artifactFile!/${entry.name}")
            }
          entry.name.endsWith(".jar") ->
            ZipInputStream(archive.getInputStream(entry)).use { nestedArchive ->
              var nestedEntry = nestedArchive.nextEntry
              while (nestedEntry != null) {
                if (!nestedEntry.isDirectory && nestedEntry.name.endsWith(".class")) {
                  versions +=
                    readClassMajorVersion(
                      nestedArchive,
                      "$artifactFile!/${entry.name}!/${nestedEntry.name}"
                    )
                }
                nestedEntry = nestedArchive.nextEntry
              }
            }
        }
      }
    }
    return versions
  }

  private fun readClassMajorVersion(
    input: InputStream,
    source: String
  ): Int {
    val header = ByteArray(8)
    var offset = 0
    while (offset < header.size) {
      val read = input.read(header, offset, header.size - offset)
      check(read >= 0) { "class headerが途中で終了しました: $source" }
      offset += read
    }
    check(
      (header[0].toInt() and 0xFF) == 0xCA &&
        (header[1].toInt() and 0xFF) == 0xFE &&
        (header[2].toInt() and 0xFF) == 0xBA &&
        (header[3].toInt() and 0xFF) == 0xBE
    ) {
      "class magicが不正です: $source"
    }
    return ((header[6].toInt() and 0xFF) shl 8) or (header[7].toInt() and 0xFF)
  }

  private fun classMajorToJavaVersion(classMajor: Int): Int = classMajor - 44

  companion object {
    private val JVM_VERSION_ATTRIBUTE_REGEX =
      Regex("""\"org\.gradle\.jvm\.version\"\s*:\s*(\d+)""")
  }
}

abstract class VerifyProtocolWireCompatibility : DefaultTask() {
  @get:Input
  abstract val currentProtocolVersion: Property<String>

  @get:Classpath
  abstract val previousProtocolClasspath: ConfigurableFileCollection

  @get:Classpath
  abstract val currentProtocolClasspath: ConfigurableFileCollection

  @get:InputFile
  abstract val previousRuntimeResponse: RegularFileProperty

  @get:InputFile
  abstract val previousIdeRequest: RegularFileProperty

  @get:InputFile
  abstract val currentRuntimeResponse: RegularFileProperty

  @get:InputFile
  abstract val currentIdeRequest: RegularFileProperty

  @get:OutputFile
  abstract val reportFile: RegularFileProperty

  @TaskAction
  fun verify() {
    val previousResponse = previousRuntimeResponse.get().asFile
    val previousRequest = previousIdeRequest.get().asFile
    val currentResponse = currentRuntimeResponse.get().asFile
    val currentRequest = currentIdeRequest.get().asFile

    decode(previousProtocolClasspath, "decodeResponse", previousResponse)
    decode(previousProtocolClasspath, "decodeRequest", previousRequest)
    decode(previousProtocolClasspath, "decodeResponse", currentResponse)
    decode(previousProtocolClasspath, "decodeRequest", currentRequest)
    decode(currentProtocolClasspath, "decodeResponse", previousResponse)
    decode(currentProtocolClasspath, "decodeRequest", previousRequest)
    decode(currentProtocolClasspath, "decodeRequest", currentRequest)

    val output = reportFile.get().asFile
    output.parentFile.mkdirs()
    output.writeText(
      "protocol 0.2.0: previous request/response and current IDE request/Runtime response decoded\n" +
        "protocol ${currentProtocolVersion.get()}: previous request/response and current IDE request decoded\n"
    )
    logger.lifecycle(
      "Protocol 0.2.0/${currentProtocolVersion.get()} wire cross-compatibility passed: $output"
    )
  }

  private fun decode(
    classpath: ConfigurableFileCollection,
    methodName: String,
    fixture: File
  ) {
    URLClassLoader(
      classpath.files.map(File::toURI).map { it.toURL() }.toTypedArray(),
      ClassLoader.getPlatformClassLoader()
    ).use { classLoader ->
      try {
        val protocolJson =
          classLoader.loadClass("com.masaibar.datastore.inspector.protocol.ProtocolJson")
        val instance = protocolJson.getField("INSTANCE").get(null)
        protocolJson
          .getMethod(methodName, ByteArray::class.java)
          .invoke(instance, fixture.readBytes())
      } catch (error: InvocationTargetException) {
        throw IllegalStateException(
          "${fixture.name} failed $methodName: ${error.targetException.message}",
          error.targetException
        )
      }
    }
  }
}

abstract class VerifySourceApiClassifications : DefaultTask() {
  @get:org.gradle.api.tasks.Internal
  abstract val projectDirectory: DirectoryProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sourceFiles: ConfigurableFileCollection

  @get:Input
  abstract val requiredMemberClassifications: MapProperty<String, String>

  @get:OutputFile
  abstract val reportFile: RegularFileProperty

  @TaskAction
  fun verify() {
    val markers =
      setOf(
        "StableDataStoreInspectorGradleApi",
        "ExperimentalDataStoreInspectorGradleApi",
        "InternalDataStoreInspectorGradleApi",
        "ExperimentalDataStoreInspectorApi",
        "InternalDataStoreInspectorApi",
        "InternalDataStoreInspectorProtocolApi"
      )
    val markerDefinition = Regex("^public annotation class (?:${markers.joinToString("|")})$")
    val unclassified = mutableListOf<String>()
    val files = sourceFiles.files.filter(File::isFile).sortedBy(File::getPath)
    fun annotationsBefore(lines: List<String>, index: Int): List<String> =
      lines.subList(0, index)
        .asReversed()
        .dropWhile(String::isBlank)
        .takeWhile { candidate -> candidate.trimStart().startsWith("@") }
        .map(String::trim)

    files.forEach { source ->
      val lines = source.readLines()
      lines.forEachIndexed { index, line ->
        if (!line.startsWith("public ") || markerDefinition.matches(line.trim())) return@forEachIndexed
        val annotations = annotationsBefore(lines, index)
        if (annotations.none { annotation -> markers.any { marker -> annotation.startsWith("@$marker") } }) {
          unclassified += "${source.relativeTo(projectDirectory.get().asFile)}:${index + 1}: ${line.trim()}"
        }
      }
    }
    check(unclassified.isEmpty()) {
      "Published top-level declarations must be classified as Stable, Experimental, or Internal:\n" +
        unclassified.joinToString("\n")
    }

    requiredMemberClassifications.get().toSortedMap().forEach { (location, marker) ->
      val separator = location.lastIndexOf('#')
      check(separator > 0 && separator < location.lastIndex) {
        "Invalid member classification location: $location"
      }
      val relativePath = location.substring(0, separator)
      val memberName = location.substring(separator + 1)
      val source = projectDirectory.get().asFile.resolve(relativePath)
      check(source in files) { "Classified member source is not an input: $relativePath" }
      val lines = source.readLines()
      val declaration = Regex("^\\s*public\\s+fun\\s+${Regex.escape(memberName)}\\s*\\(")
      val matches = lines.indices.filter { index -> declaration.containsMatchIn(lines[index]) }
      check(matches.size == 1) {
        "Expected exactly one public function named $memberName in $relativePath, found ${matches.size}."
      }
      val annotations = annotationsBefore(lines, matches.single())
      check(annotations.any { annotation -> annotation.startsWith("@$marker") }) {
        "$relativePath#$memberName must be classified with @$marker."
      }
    }

    val optInMarkers =
      setOf(
        "ExperimentalDataStoreInspectorGradleApi",
        "InternalDataStoreInspectorGradleApi",
        "ExperimentalDataStoreInspectorApi",
        "InternalDataStoreInspectorApi",
        "InternalDataStoreInspectorProtocolApi"
      )
    optInMarkers.forEach { marker ->
      val markerSource = files.firstOrNull { source -> "public annotation class $marker" in source.readText() }
      check(markerSource != null) { "API marker is missing: $marker" }
      val markerText = markerSource.readText()
      val declaration = markerText.indexOf("public annotation class $marker")
      val previousMarker = markerText.lastIndexOf("public annotation class ", declaration - 1)
      val prefix = markerText.substring(maxOf(previousMarker + 1, declaration - 800), declaration)
      check("@RequiresOptIn(" in prefix && "level = RequiresOptIn.Level.ERROR" in prefix) {
        "API marker must require an ERROR-level opt-in: $marker"
      }
    }

    val output = reportFile.get().asFile
    output.parentFile.mkdirs()
    output.writeText(
      "classified top-level declarations: ${files.size} source files\n" +
        "classified public members: ${requiredMemberClassifications.get().size}\n"
    )
    logger.lifecycle("Published source API classifications are complete: $output")
  }
}

plugins {
  base
  alias(libs.plugins.ktlint)
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.android.library) apply false
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.kotlin.serialization) apply false
  alias(libs.plugins.protobuf) apply false
  alias(libs.plugins.maven.publish) apply false
}

val sdkKtlintVersion = libs.versions.ktlint.get()

fun KtlintExtension.configureSdkKtlint(ktlintVersion: String) {
  version.set(ktlintVersion)
  verbose.set(true)
  outputToConsole.set(true)
  ignoreFailures.set(false)
  filter {
    exclude("**/generated/**")
  }
}

ktlint {
  configureSdkKtlint(sdkKtlintVersion)
}

val artifactCoordinatesFile =
  rootProject.layout.projectDirectory
    .file("gradle/artifact-coordinates.properties")
    .asFile
val artifactCoordinates =
  Properties().apply {
    check(artifactCoordinatesFile.isFile) {
      "artifact座標の正本がありません: $artifactCoordinatesFile"
    }
    artifactCoordinatesFile.inputStream().use(::load)
  }

fun artifactCoordinate(name: String): String =
  artifactCoordinates.getProperty(name)?.takeIf(String::isNotBlank)
    ?: error("artifact座標の正本に${name}がありません。")

group = artifactCoordinate("group")
version = artifactCoordinate("version")

subprojects {
  group = rootProject.group
  version = rootProject.version

  pluginManager.apply("org.jlleitschuh.gradle.ktlint")
  extensions.configure<KtlintExtension> {
    configureSdkKtlint(sdkKtlintVersion)
  }

  dependencyLocking {
    lockAllConfigurations()
  }
}

val publicRepositoryUrl = "https://github.com/masaibar/datastore-inspector-sdk"
val publicationRepositoryDirectory = layout.buildDirectory.dir("publication-repository")
val previousProtocolVersion = "0.2.0"
val previousProtocolRuntimeClasspath =
  configurations.create("previousProtocolRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
  }
val currentProtocolRuntimeClasspath =
  configurations.create("currentProtocolRuntimeClasspath") {
    isCanBeConsumed = false
    isCanBeResolved = true
  }
dependencies {
  add(
    previousProtocolRuntimeClasspath.name,
    "${artifactCoordinate("group")}:${artifactCoordinate("protocolArtifact")}:$previousProtocolVersion"
  )
  add(currentProtocolRuntimeClasspath.name, project(":protocol"))
}
val publishedArtifacts =
  linkedMapOf(
    ":protocol" to
      PublishedArtifactMetadata(
        artifactProperty = "protocolArtifact",
        displayName = "DataStore Inspector Protocol",
        description = "Versioned transport protocol shared by DataStore Inspector clients and runtimes.",
        extension = "jar"
      ),
    ":runtime-core" to
      PublishedArtifactMetadata(
        artifactProperty = "runtimeCoreArtifact",
        displayName = "DataStore Inspector Runtime Core",
        description = "Android runtime, registry, transport, and mutation safety for DataStore Inspector.",
        extension = "aar"
      ),
    ":runtime-preferences" to
      PublishedArtifactMetadata(
        artifactProperty = "runtimePreferencesArtifact",
        displayName = "DataStore Inspector Preferences Runtime",
        description = "Preferences DataStore adapter for DataStore Inspector.",
        extension = "aar"
      ),
    ":runtime-shared-preferences" to
      PublishedArtifactMetadata(
        artifactProperty = "runtimeSharedPreferencesArtifact",
        displayName = "DataStore Inspector SharedPreferences Runtime",
        description = "SharedPreferences catalog and adapter for DataStore Inspector.",
        extension = "aar"
      ),
    ":runtime-protobuf" to
      PublishedArtifactMetadata(
        artifactProperty = "runtimeProtobufArtifact",
        displayName = "DataStore Inspector Proto Runtime",
        description = "Proto DataStore adapter and schema support for DataStore Inspector.",
        extension = "aar"
      )
  )

publishedArtifacts.forEach { (projectPath, metadata) ->
  project(projectPath) {
    pluginManager.withPlugin("com.vanniktech.maven.publish") {
      extensions.configure<MavenPublishBaseExtension> {
        if (projectPath == ":protocol") {
          configure(
            KotlinJvm(
              javadocJar = JavadocJar.Empty(),
              sourcesJar = SourcesJar.Sources()
            )
          )
        } else {
          configure(
            AndroidSingleVariantLibrary(
              javadocJar = JavadocJar.Empty(),
              sourcesJar = SourcesJar.Sources(),
              variant = "release"
            )
          )
        }
        coordinates(
          groupId = artifactCoordinate("group"),
          artifactId = artifactCoordinate(metadata.artifactProperty),
          version = artifactCoordinate("version")
        )
        pom {
          name.set(metadata.displayName)
          description.set(metadata.description)
          url.set(publicRepositoryUrl)
          inceptionYear.set("2026")
          licenses {
            license {
              name.set("The Apache License, Version 2.0")
              url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
              distribution.set("repo")
            }
          }
          developers {
            developer {
              id.set("masaibar")
              name.set("masaibar")
              url.set("https://github.com/masaibar")
            }
          }
          scm {
            url.set(publicRepositoryUrl)
            connection.set("scm:git:https://github.com/masaibar/datastore-inspector-sdk.git")
            developerConnection.set(
              "scm:git:ssh://git@github.com/masaibar/datastore-inspector-sdk.git"
            )
          }
        }
      }
      extensions.configure<PublishingExtension> {
        repositories {
          maven {
            name = "publicationVerification"
            url = publicationRepositoryDirectory.get().asFile.toURI()
          }
        }
      }
    }
  }
}

val publishSdkToPublicationVerificationRepository =
  tasks.register("publishSdkToPublicationVerificationRepository") {
    group = "publishing"
    description = "公開対象SDK artifactをローカル検証repositoryへpublishします。"
    dependsOn(
      publishedArtifacts.keys.map { projectPath ->
        "$projectPath:publishAllPublicationsToPublicationVerificationRepository"
      }
    )
  }

val verifyPublishedSdkMetadata =
  tasks.register<VerifyMavenPublicationRepository>("verifyPublishedSdkMetadata") {
    group = "verification"
    description = "公開対象SDKのartifact、POM、Gradle Module Metadataを検証します。"
    dependsOn(publishSdkToPublicationVerificationRepository)
    repositoryDirectory.set(publicationRepositoryDirectory)
    groupId.set(artifactCoordinate("group"))
    publicationVersion.set(artifactCoordinate("version"))
    repositoryUrl.set(publicRepositoryUrl)
    expectedArtifacts.set(
      publishedArtifacts.values.associate { metadata ->
        artifactCoordinate(metadata.artifactProperty) to metadata.extension
      }
    )
  }

val publishGradlePluginToPublicationVerificationRepository =
  gradle.includedBuild("gradle-plugin")
    .task(":publishAllPublicationsToPublicationVerificationRepository")

val verifyPublishedJvmCompatibility =
  tasks.register<VerifyPublishedJvmCompatibility>("verifyPublishedJvmCompatibility") {
    group = "verification"
    description = "公開対象JAR／AARとGradle Module MetadataのJVM互換性を検証します。"
    dependsOn(
      publishSdkToPublicationVerificationRepository,
      publishGradlePluginToPublicationVerificationRepository
    )
    repositoryDirectory.set(publicationRepositoryDirectory)
    groupId.set(artifactCoordinate("group"))
    publicationVersion.set(artifactCoordinate("version"))
    artifactExtensions.set(
      publishedArtifacts.values.associate { metadata ->
        artifactCoordinate(metadata.artifactProperty) to metadata.extension
      } + (artifactCoordinate("gradlePluginArtifact") to "jar")
    )
    expectedClassMajorVersions.set(
      publishedArtifacts.values.associate { metadata ->
        artifactCoordinate(metadata.artifactProperty) to
          if (metadata.artifactProperty == "protocolArtifact") 55 else 61
      } + (artifactCoordinate("gradlePluginArtifact") to 61)
    )
    requiredMetadataJvmVersions.set(
      mapOf(
        artifactCoordinate("protocolArtifact") to 11,
        artifactCoordinate("gradlePluginArtifact") to 17
      )
    )
    reportFile.set(layout.buildDirectory.file("reports/publication-jvm-compatibility.txt"))
  }

val verifySourceApiClassifications =
  tasks.register<VerifySourceApiClassifications>("verifySourceApiClassifications") {
    group = "verification"
    description = "Verifies published top-level declarations and documented Gradle DSL members have API classifications."
    projectDirectory.set(layout.projectDirectory)
    sourceFiles.from(
      listOf(
        "protocol/src/main/kotlin",
        "runtime-core/src/main/kotlin",
        "runtime-preferences/src/main/kotlin",
        "runtime-protobuf/src/main/kotlin",
        "runtime-shared-preferences/src/main/kotlin",
        "gradle-plugin/src/main/kotlin"
      ).map { path -> fileTree(path) { include("**/*.kt") } }
    )
    requiredMemberClassifications.set(
      mapOf(
        "gradle-plugin/src/main/kotlin/com/masaibar/datastore/inspector/gradle/DataStoreInspectorPlugin.kt#schemaEntry" to
          "StableDataStoreInspectorGradleApi",
        "gradle-plugin/src/main/kotlin/com/masaibar/datastore/inspector/gradle/DataStoreInspectorPlugin.kt#customCodecBinding" to
          "ExperimentalDataStoreInspectorGradleApi"
      )
    )
    reportFile.set(layout.buildDirectory.file("reports/source-api-classifications.txt"))
  }

val verifyPublishedSdkConsumer =
  tasks.register<Exec>("verifyPublishedSdkConsumer") {
    group = "verification"
    description = "独立consumerから公開済みGradle PluginとSDK artifactを解決します。"
    dependsOn(
      publishSdkToPublicationVerificationRepository,
      publishGradlePluginToPublicationVerificationRepository
    )
    val consumerDirectory = layout.projectDirectory.dir("gradle/publication-consumer")
    inputs.dir(consumerDirectory)
    inputs.dir(publicationRepositoryDirectory)
    inputs.property("publicationGroup", artifactCoordinate("group"))
    inputs.property("publicationVersion", artifactCoordinate("version"))
    workingDir(consumerDirectory)
    commandLine(
      rootProject.file("gradlew").absolutePath,
      "clean",
      "verifyPublishedRuntimeClasspath",
      "-PpublicationRepository=${publicationRepositoryDirectory.get().asFile.absolutePath}",
      "-PpublicationGroup=${artifactCoordinate("group")}",
      "-PpublicationVersion=${artifactCoordinate("version")}",
      "--no-configuration-cache",
      "--console=plain"
    )
  }

val verifyProtocolWireCompatibility =
  tasks.register<VerifyProtocolWireCompatibility>("verifyProtocolWireCompatibility") {
    group = "verification"
    description = "Cross-decodes old and current request/response fixtures with Protocol 0.2.0 and the current version."
    dependsOn(":runtime-core:testDebugUnitTest", ":protocol:jar")
    currentProtocolVersion.set(artifactCoordinate("version"))
    previousProtocolClasspath.from(previousProtocolRuntimeClasspath)
    currentProtocolClasspath.from(currentProtocolRuntimeClasspath)
    previousRuntimeResponse.set(
      layout.projectDirectory.file(
        "gradle/wire-compatibility-fixtures/v0.2.0/runtime-snapshot-response.json"
      )
    )
    previousIdeRequest.set(
      layout.projectDirectory.file(
        "gradle/wire-compatibility-fixtures/v0.2.0/ide-write-request.json"
      )
    )
    currentRuntimeResponse.set(
      layout.buildDirectory.file("contracts/runtime-snapshot-response.json")
    )
    currentIdeRequest.set(
      layout.projectDirectory.file(
        "gradle/wire-compatibility-fixtures/v1.0.0/ide-write-request.json"
      )
    )
    reportFile.set(layout.buildDirectory.file("reports/protocol-wire-compatibility.txt"))
  }

val checkPublications =
  tasks.register("checkPublications") {
    group = "verification"
    description = "Maven Central／Plugin Portal向けpublicationをローカル検証します。"
    dependsOn(
      verifyPublishedSdkMetadata,
      verifyPublishedJvmCompatibility,
      verifyPublishedSdkConsumer,
      verifySourceApiClassifications
    )
  }

tasks.register("checkSdk") {
  group = "verification"
  description = "SDK全体のtest、lint、debug統合、release分離を検証します。"
  dependsOn(
    "ktlintCheck",
    subprojects.map { "${it.path}:ktlintCheck" },
    ":protocol:test",
    ":protocol:jar",
    ":runtime-core:assemble",
    ":runtime-core:testDebugUnitTest",
    ":runtime-preferences:assemble",
    ":runtime-preferences:testDebugUnitTest",
    ":runtime-shared-preferences:assemble",
    ":runtime-shared-preferences:testDebugUnitTest",
    ":runtime-protobuf:assemble",
    ":runtime-protobuf:testDebugUnitTest",
    ":sample-app:testDebugUnitTest",
    ":sample-app:verifyDebugSchemaPackaging",
    ":sample-app:verifyDebugRuntimePackaging",
    ":sample-app:verifyProductionIntegration",
    ":sample-app:verifyCleanConsumer",
    ":sample-app:verifyReleaseIsolation",
    ":sample-app:lint",
    ":runtime-core:lint",
    ":runtime-preferences:lint",
    ":runtime-shared-preferences:lint",
    ":runtime-protobuf:lint",
    checkPublications,
    verifyProtocolWireCompatibility,
    gradle.includedBuild("gradle-plugin").task(":checkPlugin")
  )
}
