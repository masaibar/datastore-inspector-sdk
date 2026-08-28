import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.maven.publish)
  `java-library`
}

kotlin {
  jvmToolchain(21)

  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_11)
    optIn.add("com.masaibar.datastore.inspector.protocol.InternalDataStoreInspectorProtocolApi")
  }
}

java {
  sourceCompatibility = JavaVersion.VERSION_11
  targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
  api(libs.kotlinx.serialization.json)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
}

tasks.test {
  useJUnitPlatform()
}
