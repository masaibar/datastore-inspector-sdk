import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.protobuf)
  id("com.masaibar.datastore-inspector")
}

val protobufRuntimeVersion =
  extensions.getByType<VersionCatalogsExtension>()
    .named("libs")
    .findVersion("protobuf")
    .orElseThrow()
    .requiredVersion
val sampleApplicationId = "com.masaibar.datastore.inspector.sample"

android {
  namespace = sampleApplicationId
  compileSdk = 37

  defaultConfig {
    applicationId = sampleApplicationId
    minSdk = 23
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }

  buildFeatures {
    compose = true
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.lifecycle.viewmodel.ktx)
  implementation(libs.datastore.preferences)
  implementation(libs.datastore.core)
  implementation(libs.protobuf.javalite)
  debugImplementation(libs.androidx.compose.ui.tooling)
  testImplementation(project(":protocol"))
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
}

protobuf {
  protoc {
    artifact = "com.google.protobuf:protoc:$protobufRuntimeVersion"
  }
  generateProtoTasks {
    all().configureEach {
      builtins {
        maybeCreate("java").option("lite")
      }
    }
  }
}

dataStoreInspector {
  schemaEntry(
    "com.masaibar.datastore.inspector.sample.proto.UserSettings",
    "datastore.inspector.sample.UserSettings"
  )
}

tasks.withType<Test>().configureEach {
  useJUnitPlatform()
  dependsOn("generateDebugDataStoreInspectorSchema")
  val protoWriteContractInput =
    providers.gradleProperty("datastoreInspectorProtoWriteContractInput")
      .map { rootProject.file(it) }
  val protoContractOutput =
    rootProject.layout.buildDirectory
      .file("contracts/runtime-proto-snapshot-response.json")
  val protoSchemaOutput =
    rootProject.layout.buildDirectory
      .file("contracts/runtime-proto-schema.desc")
  val protoSchemaFixture =
    layout.buildDirectory
      .dir("generated/datastoreInspectorSchema/debug")
  if (name == "testDebugUnitTest") {
    inputs
      .dir(protoSchemaFixture)
      .withPropertyName("protoSchemaFixture")
      .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs
      .file(protoContractOutput)
      .withPropertyName("runtimeProtoContractFixture")
    outputs
      .file(protoSchemaOutput)
      .withPropertyName("runtimeProtoSchemaFixture")
    protoWriteContractInput.orNull?.let { input ->
      inputs
        .file(input)
        .withPropertyName("protoWriteContractFixture")
        .withPathSensitivity(PathSensitivity.NONE)
    }
  }
  systemProperty(
    "datastore.inspector.proto.contract.output",
    protoContractOutput.get().asFile.absolutePath
  )
  systemProperty(
    "datastore.inspector.proto.schema.output",
    protoSchemaOutput.get().asFile.absolutePath
  )
  systemProperty(
    "datastore.inspector.proto.schema.fixture",
    protoSchemaFixture.get().asFile.absolutePath
  )
  protoWriteContractInput.orNull?.let { input ->
    systemProperty(
      "datastore.inspector.proto.contract.input",
      input.absolutePath
    )
  }
}

abstract class VerifyReleaseIsolation : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val releaseArtifacts: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val mergedReleaseManifest: RegularFileProperty

  @get:Input
  abstract val runtimeComponentIds: ListProperty<String>

  @get:OutputFile
  abstract val auditReport: RegularFileProperty

  private fun ByteArray.containsAscii(value: String): Boolean {
    val expected = value.encodeToByteArray()
    if (size < expected.size) return false
    return (0..size - expected.size).any { offset ->
      expected.indices.all { index ->
        this[offset + index] == expected[index]
      }
    }
  }

  @TaskAction
  fun verify() {
    val forbiddenCoordinates =
      listOf(
        ":protocol:",
        ":runtime-core:",
        ":runtime-preferences:",
        ":runtime-protobuf:",
        ":runtime-shared-preferences:"
      )
    val inspectorComponents =
      runtimeComponentIds.get().filter { component ->
        component.startsWith(
          "com.masaibar.datastore-inspector:"
        ) &&
          forbiddenCoordinates.any(component::contains)
      }
    check(inspectorComponents.isEmpty()) {
      "release runtime classpathへInspector artifactが混入しました: " +
        inspectorComponents
    }

    val forbiddenEntryPrefixes =
      listOf(
        "datastore-inspector/",
        "com/masaibar/datastore/inspector/runtime/",
        "com/masaibar/datastore/inspector/protocol/",
        "com/masaibar/datastore/inspector/generated/",
        "META-INF/services/" +
          "com.masaibar.datastore.inspector.runtime.core." +
          "InspectorCustomCodecBindingProvider"
      )
    val forbiddenBinaryMarkers =
      listOf(
        "Lcom/masaibar/datastore/inspector/runtime/",
        "Lcom/masaibar/datastore/inspector/protocol/",
        "datastore_inspector_init",
        "datastore_inspector_",
        "DataStoreInspectorRuntime",
        "registerGenerated",
        "registerFallback",
        "PreferencesDataStoreDelegateBridge",
        "TypedDataStoreDelegateBridge",
        "DataStoreCreationBridge",
        "StructuredSerializationCapture",
        "InspectorCustomCodec",
        "InspectorCustomCodecBinding",
        "InspectorCustomCodecBindingProvider",
        "GeneratedCustomCodecBindingProvider",
        "gradle-generated-custom-codec-v1",
        "datastoreInspectorInstrumentation",
        "custom-datastore-v1",
        "typed-delegate-default-v1",
        "single-factory-serializer-default-v1"
      )
    val findings = mutableListOf<String>()

    val artifacts = releaseArtifacts.files.sortedBy { it.name }
    check(artifacts.map { it.extension }.toSet() == setOf("aab", "apk")) {
      "release APKとAABの両方が必要です: ${artifacts.map { it.name }}"
    }
    artifacts.forEach { artifact ->
      ZipFile(artifact).use { archive ->
        archive.entries().asSequence()
          .filterNot { it.isDirectory }
          .forEach { entry ->
            if (forbiddenEntryPrefixes.any(entry.name::contains)) {
              findings += "${artifact.name}!/${entry.name}"
            }
            val bytes = archive.getInputStream(entry).readBytes()
            forbiddenBinaryMarkers
              .filter { marker -> bytes.containsAscii(marker) }
              .forEach { marker ->
                findings += "${artifact.name}!/${entry.name}:$marker"
              }
          }
      }
    }

    val manifest = mergedReleaseManifest.get().asFile.readText()
    listOf(
      "DataStoreInspectorInitProvider",
      "datastore_inspector_init",
      "com.masaibar.datastore.inspector.runtime"
    ).forEach { marker ->
      if (marker in manifest) findings += "merged release manifest:$marker"
    }
    check(findings.isEmpty()) {
      "release APK／AABへInspector要素が混入しました: $findings"
    }

    val report = buildString {
      appendLine("DataStore Inspector release artifact監査")
      appendLine("判定: PASS")
      appendLine("release runtime Inspector component: 0")
      appendLine("merged manifest Inspector marker: 0")
      artifacts.forEach { artifact ->
        val digest = MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
          .joinToString("") { byte -> "%02x".format(byte) }
        appendLine("${artifact.name} SHA-256: $digest")
      }
      appendLine(
        "APK／AAB Runtime・Protocol・hook・codec・binding・asset・service entry marker: 0"
      )
    }
    auditReport.get().asFile.apply {
      parentFile.mkdirs()
      writeText(report)
    }

    logger.lifecycle(
      "release artifact分離検証成功: APK／AABのRuntime／Protocol依存・" +
        "hook・codec・binding参照・Provider・schema asset・service entryなし"
    )
  }
}

abstract class VerifyDebugSchemaPackaging : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val debugApk: RegularFileProperty

  @TaskAction
  fun verify() {
    fun ByteArray.containsAscii(value: String): Boolean {
      val expected = value.encodeToByteArray()
      if (size < expected.size) return false
      return (0..size - expected.size).any { offset ->
        expected.indices.all { index -> this[offset + index] == expected[index] }
      }
    }
    ZipFile(debugApk.get().asFile).use { apk ->
      val indexEntry = apk.getEntry("assets/datastore-inspector/schema-index.json")
      check(indexEntry != null) {
        "debug APKにschema indexがありません。"
      }
      val index = apk.getInputStream(indexEntry).bufferedReader().use { it.readText() }
      check(
        index.contains(
          "com.masaibar.datastore.inspector.sample.proto.UserSettings"
        )
      ) { "schema indexにgenerated JVM class mappingがありません。" }
      check(
        apk.entries().asSequence().any {
          it.name.startsWith("assets/datastore-inspector/schemas/") &&
            it.name.endsWith(".desc")
        }
      ) { "debug APKにdescriptor bundleがありません。" }
      check(
        apk.entries().asSequence()
          .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
          .any { entry ->
            apk.getInputStream(entry).use { input ->
              input.readBytes().containsAscii(
                "com/masaibar/datastore/inspector/sample/proto/UserSettings"
              )
            }
          }
      ) { "schema indexが示すgenerated JVM classがdebug APKにありません。" }
    }
    logger.lifecycle("debug schema packaging検証成功: index・descriptorあり")
  }
}

abstract class VerifyDebugRuntimePackaging : DefaultTask() {
  @get:Input
  abstract val expectedApplicationId: Property<String>

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val mergedManifest: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val debugApk: RegularFileProperty

  @TaskAction
  fun verify() {
    val manifest = mergedManifest.get().asFile.readText()
    val manifestApplicationId =
      Regex("""<manifest[^>]*\bpackage="([^"]+)"""")
        .find(manifest)
        ?.groupValues
        ?.get(1)
        ?: error("merged manifestにapplicationIdがありません。")
    check(manifestApplicationId == expectedApplicationId.get()) {
      "sample-appのapplicationIdが期待値と一致しません。"
    }
    check("android:minSdkVersion=\"23\"" in manifest) {
      "sample-appのconsumer minSdkが23ではありません。"
    }
    check(
      manifest.windowed("DataStoreInspectorInitProvider".length)
        .count { it == "DataStoreInspectorInitProvider" } == 1
    ) {
      "Inspector Providerは1件である必要があります。"
    }
    val providerStart = manifest.indexOf("DataStoreInspectorInitProvider")
    val providerElement =
      manifest.substring(providerStart, manifest.indexOf("/>", providerStart))
    check("android:exported=\"false\"" in providerElement) {
      "Inspector Providerが非exportedではありません。"
    }
    check("android:process=" !in providerElement) {
      "Inspector Providerがdefault process以外へ分離されています。"
    }
    val authorities =
      Regex("""android:authorities="([^"]+)"""")
        .find(providerElement)
        ?.groupValues
        ?.get(1)
        ?.split(';')
        .orEmpty()
    check(
      authorities.toSet() ==
        setOf(
          "$manifestApplicationId.datastore_inspector_init",
          "$manifestApplicationId.datastore_inspector_runtime_v1"
        )
    ) {
      "Inspector Provider authoritiesがapplicationIdへ追従していません。"
    }
    ZipFile(debugApk.get().asFile).use { apk ->
      val adapterService =
        "META-INF/services/" +
          "com.masaibar.datastore.inspector.runtime.core.StoreAdapterFactory"
      val adapterProviders =
        apk.getInputStream(apk.getEntry(adapterService) ?: error("Adapter SPIがありません。"))
          .bufferedReader()
          .readLines()
          .map(String::trim)
          .filter(String::isNotEmpty)
          .toSet()
      check(
        adapterProviders ==
          setOf(
            "com.masaibar.datastore.inspector.runtime.preferences.PreferencesStoreAdapterFactory",
            "com.masaibar.datastore.inspector.runtime.protobuf.ProtobufStoreAdapterFactory"
          )
      ) {
        "公開sampleのAdapter SPIが不正です: $adapterProviders"
      }

      val catalogService =
        "META-INF/services/" +
          "com.masaibar.datastore.inspector.runtime.core.StoreCatalogProvider"
      val catalogProviders =
        apk.getInputStream(apk.getEntry(catalogService) ?: error("Catalog SPIがありません。"))
          .bufferedReader()
          .readLines()
          .map(String::trim)
          .filter(String::isNotEmpty)
      check(
        catalogProviders ==
          listOf(
            "com.masaibar.datastore.inspector.runtime.sharedpreferences." +
              "SharedPreferencesStoreCatalogProvider"
          )
      ) {
        "公開sampleのSharedPreferences Catalog SPIが不正です: $catalogProviders"
      }

      val codecService =
        "META-INF/services/" +
          "com.masaibar.datastore.inspector.runtime.core." +
          "InspectorCustomCodecBindingProvider"
      check(apk.getEntry(codecService) == null) {
        "公開sampleへCustom codec providerが混入しました。"
      }

      val dexBytes =
        apk.entries().asSequence()
          .filter { it.name.startsWith("classes") && it.name.endsWith(".dex") }
          .flatMap { entry -> apk.getInputStream(entry).readBytes().asSequence() }
          .toList()
          .toByteArray()
      listOf(
        "DataStoreInspectorInitProvider",
        "datastore_inspector_",
        "primary_preferences",
        "user_settings.pb",
        "sample_preferences"
      ).forEach { marker ->
        check(dexBytes.containsAscii(marker)) {
          "公開sampleのdebug APKに基本markerがありません: $marker"
        }
      }
    }
    logger.lifecycle(
      "公開sampleのdebug packaging検証成功: Preferences・SharedPreferences・Protoのみ"
    )
  }

  private fun ByteArray.containsAscii(value: String): Boolean {
    val expected = value.encodeToByteArray()
    if (size < expected.size) return false
    return (0..size - expected.size).any { offset ->
      expected.indices.all { index -> this[offset + index] == expected[index] }
    }
  }
}

abstract class VerifyPublicSampleIntegration : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val instrumentationReport: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val transformedStoreClass: RegularFileProperty

  @get:Input
  abstract val declaredInspectorDependencies: ListProperty<String>

  @TaskAction
  fun verify() {
    val classBytes = transformedStoreClass.get().asFile.readBytes()
    listOf(
      "com/masaibar/datastore/inspector/runtime/preferences/PreferencesDataStoreDelegateBridge",
      "com/masaibar/datastore/inspector/runtime/core/TypedDataStoreDelegateBridge"
    ).forEach { bridge ->
      check(classBytes.containsAscii(bridge)) {
        "公開sampleの基本DataStore delegateが計装されていません: $bridge"
      }
    }

    val report = instrumentationReport.get().asFile.readText()
    listOf(
      "variant debug: ALL計装",
      "variant release: non-debuggable、計装・依存注入なし",
      "preferences adapter: true",
      "protobuf adapter: true",
      "reachable first-party projects: " +
        "[:protocol, :runtime-core, :runtime-preferences, :runtime-protobuf, " +
        ":runtime-shared-preferences, :sample-app]",
      "schema debug: descriptor projects=[:sample-app(debug)]",
      "instrumentation budget: target classes <= 25000, elapsed <= 30000 ms"
    ).forEach { expected ->
      check(expected in report) { "公開sampleの計装reportに不足があります: $expected" }
    }
    val componentsByVariant =
      declaredInspectorDependencies.get().groupBy(
        keySelector = { it.substringBefore('=') },
        valueTransform = { it.substringAfter('=') }
      )
    listOf(
      "runtime-core",
      "runtime-shared-preferences",
      "runtime-preferences",
      "runtime-protobuf"
    ).forEach { artifact ->
      check(componentsByVariant["debug"].orEmpty().any { artifact in it }) {
        "debugへ$artifact が注入されていません。"
      }
      check(componentsByVariant["release"].orEmpty().none { artifact in it }) {
        "releaseへ$artifact が混入しました。"
      }
    }

    logger.lifecycle("公開sampleの基本consumer integration検証成功")
  }

  private fun ByteArray.containsAscii(value: String): Boolean {
    val expected = value.encodeToByteArray()
    if (size < expected.size) return false
    return (0..size - expected.size).any { offset ->
      expected.indices.all { index -> this[offset + index] == expected[index] }
    }
  }
}

abstract class VerifyCleanConsumer : DefaultTask() {
  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val sampleBuildScript: RegularFileProperty

  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sampleSources: ConfigurableFileCollection

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val transformedStoreClass: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val mergedManifest: RegularFileProperty

  @get:InputFile
  @get:PathSensitive(PathSensitivity.NONE)
  abstract val debugApk: RegularFileProperty

  @TaskAction
  fun verify() {
    val dependenciesBlock =
      sampleBuildScript.get().asFile.readText()
        .substringAfter("dependencies {")
        .substringBefore("\n}")
    check("debugImplementation(project(\":runtime-" !in dependenciesBlock) {
      "sample-appにInspector Runtimeの手動debug依存が残っています。"
    }

    val forbiddenConsumerSymbols =
      listOf(
        "DataStoreInspectorRuntime",
        "registerFallback"
      )
    sampleSources.files.forEach { source ->
      val text = source.readText()
      forbiddenConsumerSymbols.forEach { symbol ->
        check(symbol !in text) {
          "${source.name} に公開sample外のsymbolが残っています: $symbol"
        }
      }
    }

    check(!transformedStoreClass.get().asFile.readBytes().containsAscii("registerFallback")) {
      "公開sampleがfallback登録を直接呼び出しています。"
    }

    val manifest = mergedManifest.get().asFile.readText()
    check("DataStoreInspectorInitProvider" in manifest) {
      "debug manifestに自動起動Providerがありません。"
    }
    logger.lifecycle(
      "Clean consumer検証成功: 基本sample・Plugin自動注入・Provider自動起動"
    )
  }

  private fun ByteArray.containsAscii(value: String): Boolean {
    val expected = value.encodeToByteArray()
    if (size < expected.size) return false
    return (0..size - expected.size).any { offset ->
      expected.indices.all { index -> this[offset + index] == expected[index] }
    }
  }
}

val verifyReleaseIsolation =
  tasks.register<VerifyReleaseIsolation>("verifyReleaseIsolation") {
    dependsOn("assembleRelease", "bundleRelease")
    releaseArtifacts.from(
      layout.buildDirectory.file("outputs/apk/release/sample-app-release-unsigned.apk"),
      layout.buildDirectory.file("outputs/bundle/release/sample-app-release.aab")
    )
    mergedReleaseManifest.set(
      layout.buildDirectory.file(
        "intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml"
      )
    )
    auditReport.set(
      layout.buildDirectory.file(
        "reports/datastore-inspector/release-artifact-audit.txt"
      )
    )
  }

tasks.register<VerifyDebugSchemaPackaging>("verifyDebugSchemaPackaging") {
  dependsOn("assembleDebug")
  debugApk.set(layout.buildDirectory.file("outputs/apk/debug/sample-app-debug.apk"))
}

tasks.register<VerifyDebugRuntimePackaging>("verifyDebugRuntimePackaging") {
  dependsOn("assembleDebug")
  expectedApplicationId.set(sampleApplicationId)
  mergedManifest.set(
    layout.buildDirectory.file(
      "intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
    )
  )
  debugApk.set(layout.buildDirectory.file("outputs/apk/debug/sample-app-debug.apk"))
}

val verifyProductionIntegration =
  tasks.register<VerifyPublicSampleIntegration>("verifyProductionIntegration") {
    dependsOn(
      "assembleDebug",
      "generateDataStoreInspectorInstrumentationReport"
    )
    instrumentationReport.set(
      layout.buildDirectory.file("reports/datastore-inspector/instrumentation.txt")
    )
    transformedStoreClass.set(
      layout.buildDirectory.file(
        "intermediates/classes/debug/transformDebugClassesWithAsm/dirs/" +
          "com/masaibar/datastore/inspector/sample/SampleStoresKt.class"
      )
    )
  }

tasks.register<VerifyCleanConsumer>("verifyCleanConsumer") {
  dependsOn("assembleDebug", verifyProductionIntegration)
  sampleBuildScript.set(layout.projectDirectory.file("build.gradle.kts"))
  sampleSources.from(
    fileTree(layout.projectDirectory.dir("src")) {
      include("**/*.kt", "**/AndroidManifest.xml")
    }
  )
  transformedStoreClass.set(
    layout.buildDirectory.file(
      "intermediates/classes/debug/transformDebugClassesWithAsm/dirs/" +
        "com/masaibar/datastore/inspector/sample/SampleStoresKt.class"
    )
  )
  mergedManifest.set(
    layout.buildDirectory.file(
      "intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml"
    )
  )
  debugApk.set(layout.buildDirectory.file("outputs/apk/debug/sample-app-debug.apk"))
}

androidComponents {
  onVariants(selector().all()) { variant ->
    verifyProductionIntegration.configure {
      declaredInspectorDependencies.addAll(
        providers.provider {
          configurations
            .getByName("${variant.name}Implementation")
            .dependencies
            .map { dependency ->
              val identity =
                if (dependency is org.gradle.api.artifacts.ProjectDependency) {
                  dependency.path
                } else {
                  "${dependency.group}:${dependency.name}"
                }
              "${variant.name}=$identity"
            }
            .sorted()
        }
      )
    }
  }
  onVariants(selector().withBuildType("release")) { variant ->
    verifyReleaseIsolation.configure {
      runtimeComponentIds.set(
        providers.provider {
          variant.runtimeConfiguration
            .incoming
            .resolutionResult
            .allComponents
            .map { it.id.displayName }
            .sorted()
        }
      )
    }
  }
}
