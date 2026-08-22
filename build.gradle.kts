import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.TaskAction
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import java.io.File
import java.util.Properties
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

val checkPublications =
  tasks.register("checkPublications") {
    group = "verification"
    description = "Maven Central／Plugin Portal向けpublicationをローカル検証します。"
    dependsOn(verifyPublishedSdkMetadata, verifyPublishedSdkConsumer)
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
    gradle.includedBuild("gradle-plugin").task(":checkPlugin")
  )
}
