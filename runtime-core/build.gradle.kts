plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.maven.publish)
}

android {
  namespace = "com.masaibar.datastore.inspector.runtime.core"
  compileSdk = 36

  defaultConfig {
    minSdk = 23
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

dependencies {
  api(project(":protocol"))
  implementation(libs.datastore.core)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.serialization.json)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.kotlinx.serialization.cbor)
  testImplementation(libs.protobuf.javalite)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  dependsOn(":sample-app:testDebugUnitTest")
  val writeContractInput =
    providers.gradleProperty("datastoreInspectorWriteContractInput")
      .map { rootProject.file(it) }
  val customWriteContractInput =
    providers.gradleProperty("datastoreInspectorCustomWriteContractInput")
      .map { rootProject.file(it) }
  val runtimeContractOutput =
    rootProject.layout.buildDirectory
      .file("contracts/runtime-snapshot-response.json")
  val runtimeCustomContractOutput =
    rootProject.layout.buildDirectory
      .file("contracts/runtime-custom-snapshot-response.json")
  val runtimeStoreChangeContractOutput =
    rootProject.layout.buildDirectory
      .file("contracts/runtime-store-change-notification.json")
  if (name == "testDebugUnitTest") {
    outputs
      .file(runtimeContractOutput)
      .withPropertyName("runtimeContractFixture")
    outputs
      .file(runtimeCustomContractOutput)
      .withPropertyName("runtimeCustomContractFixture")
    outputs
      .file(runtimeStoreChangeContractOutput)
      .withPropertyName("runtimeStoreChangeContractFixture")
    writeContractInput.orNull?.let { input ->
      inputs
        .file(input)
        .withPropertyName("writeContractFixture")
        .withPathSensitivity(PathSensitivity.NONE)
    }
    customWriteContractInput.orNull?.let { input ->
      inputs
        .file(input)
        .withPropertyName("customWriteContractFixture")
        .withPathSensitivity(PathSensitivity.NONE)
    }
  }
  systemProperty(
    "datastore.inspector.contract.output",
    runtimeContractOutput
      .get()
      .asFile
      .absolutePath
  )
  systemProperty(
    "datastore.inspector.custom.contract.output",
    runtimeCustomContractOutput
      .get()
      .asFile
      .absolutePath
  )
  systemProperty(
    "datastore.inspector.store.change.contract.output",
    runtimeStoreChangeContractOutput
      .get()
      .asFile
      .absolutePath
  )
  writeContractInput.orNull?.let { input ->
    systemProperty(
      "datastore.inspector.contract.input",
      input.absolutePath
    )
  }
  customWriteContractInput.orNull?.let { input ->
    systemProperty(
      "datastore.inspector.custom.contract.input",
      input.absolutePath
    )
  }
}
