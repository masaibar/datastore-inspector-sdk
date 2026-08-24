plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.maven.publish)
}

android {
  namespace = "com.masaibar.datastore.inspector.runtime.protobuf"
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
  api(project(":runtime-core"))
  implementation(libs.datastore.core)
  implementation(libs.protobuf.javalite)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  dependsOn(":sample-app:generateDebugDataStoreInspectorSchema")
  systemProperty(
    "datastore.inspector.schema.fixture",
    rootProject.layout.projectDirectory
      .dir("sample-app/build/generated/datastoreInspectorSchema/debug")
      .asFile
      .absolutePath
  )
}
