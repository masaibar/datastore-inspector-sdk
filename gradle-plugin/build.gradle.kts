import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.tasks.Jar
import java.util.Properties

plugins {
  `java-gradle-plugin`
  id("org.jetbrains.kotlin.jvm") version "2.3.21"
  id("com.gradle.plugin-publish") version "2.1.1"
  id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

ktlint {
  version.set("1.8.0")
  verbose.set(true)
  outputToConsole.set(true)
  ignoreFailures.set(false)
  filter {
    exclude("**/generated/**")
  }
}

val artifactCoordinatesFile =
  rootProject.layout.projectDirectory
    .file("../gradle/artifact-coordinates.properties")
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

val generatedCoordinatesDirectory =
  layout.buildDirectory.dir("generated/sources/artifactCoordinates/kotlin")
val artifactGroup = artifactCoordinate("group")
val artifactVersion = artifactCoordinate("version")
val coordinatePattern = Regex("[A-Za-z0-9._-]+")
check(coordinatePattern.matches(artifactGroup)) { "artifact groupに未対応の文字があります。" }
check(coordinatePattern.matches(artifactVersion)) { "artifact versionに未対応の文字があります。" }
val generateArtifactCoordinates =
  tasks.register<Copy>("generateArtifactCoordinates") {
    group = "build setup"
    description = "artifact座標の正本からPlugin実行時の定数を生成します。"
    inputs.file(artifactCoordinatesFile)
    from(layout.projectDirectory.dir("src/main/artifact-coordinates")) {
      expand(
        mapOf(
          "artifactGroup" to artifactGroup,
          "artifactVersion" to artifactVersion
        )
      )
    }
    into(generatedCoordinatesDirectory)
  }

group = artifactGroup
version = artifactVersion

base {
  archivesName.set(artifactCoordinate("gradlePluginArtifact"))
}

kotlin {
  jvmToolchain(21)
  sourceSets.named("main") {
    kotlin.srcDir(generatedCoordinatesDirectory)
  }
}

tasks.named("compileKotlin") {
  dependsOn(generateArtifactCoordinates)
}

tasks
  .matching {
    it.name == "runKtlintCheckOverMainSourceSet" ||
      it.name == "runKtlintFormatOverMainSourceSet"
  }.configureEach {
    dependsOn(generateArtifactCoordinates)
  }

tasks.withType<Jar>().configureEach {
  if (name == "sourcesJar") {
    dependsOn(generateArtifactCoordinates)
  }
}

dependencyLocking {
  lockAllConfigurations()
}

dependencies {
  compileOnly("com.android.tools.build:gradle:9.2.1")
  implementation("com.google.protobuf:protobuf-java:4.35.0")
  testImplementation(gradleTestKit())
  testImplementation("com.android.tools.build:gradle:9.2.1")
  testImplementation("io.kotest:kotest-runner-junit5-jvm:6.2.3")
  testImplementation("io.kotest:kotest-assertions-core-jvm:6.2.3")
  testImplementation("org.ow2.asm:asm:9.9")
}

gradlePlugin {
  website = "https://github.com/masaibar/datastore-inspector-sdk"
  vcsUrl = "https://github.com/masaibar/datastore-inspector-sdk.git"
  plugins {
    create("dataStoreInspector") {
      id = "com.masaibar.datastore-inspector"
      implementationClass =
        "com.masaibar.datastore.inspector.gradle.DataStoreInspectorPlugin"
      displayName = "DataStore Inspector"
      description =
        "Connects DataStore Inspector to debuggable Android variants without affecting release builds."
      tags = listOf("android", "android-studio", "datastore", "debugging", "inspection")
    }
  }
}

publishing {
  repositories {
    maven {
      name = "publicationVerification"
      url =
        rootProject.layout.projectDirectory
          .dir("../build/publication-repository")
          .asFile
          .toURI()
    }
  }
}

tasks.test {
  useJUnitPlatform()
  filter {
    excludeTestsMatching(
      "com.masaibar.datastore.inspector.gradle." +
        "ExternalAarInstrumentationIntegrationSpec"
    )
  }
}

val instrumentationPerformanceReport =
  layout.buildDirectory.file(
    "reports/datastore-inspector/instrumentation-performance.txt"
  )
val testSourceSet = sourceSets.named("test")
val instrumentationPerformanceGate =
  tasks.register<Test>("instrumentationPerformanceGate") {
    group = "verification"
    description =
      "実consumerのALL scope計装class数とtask wall timeをbudget検証します。"
    dependsOn(tasks.testClasses)
    testClassesDirs = testSourceSet.get().output.classesDirs
    classpath = testSourceSet.get().runtimeClasspath
    useJUnitPlatform()
    filter {
      includeTestsMatching(
        "com.masaibar.datastore.inspector.gradle." +
          "ExternalAarInstrumentationIntegrationSpec"
      )
    }
    systemProperty(
      "datastore.inspector.instrumentation.performance.report",
      instrumentationPerformanceReport.get().asFile.absolutePath
    )
    outputs.file(instrumentationPerformanceReport)
    shouldRunAfter(tasks.test)
  }

tasks.register("checkPlugin") {
  group = "verification"
  description =
    "Gradle Pluginの独立build、TestKit、schema producerを検証します。"
  dependsOn("assemble", "test", instrumentationPerformanceGate, "validatePlugins", "ktlintCheck")
}
