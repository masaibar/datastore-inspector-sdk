plugins {
    id("com.android.application")
    id("com.masaibar.datastore-inspector")
}

dataStoreInspector {
    schemaEntry(
        generatedJvmClassName = "com.example.settings.UserSettings",
        rootMessageFullName = "example.settings.UserSettings",
    )
}

val publicationGroup =
    providers.gradleProperty("publicationGroup")
        .orNull
        ?: error("publicationGroupを指定してください。")
val publicationVersion =
    providers.gradleProperty("publicationVersion")
        .orNull
        ?: error("publicationVersionを指定してください。")

android {
    namespace = "com.masaibar.datastore.inspector.publication.consumer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.masaibar.datastore.inspector.publication.consumer"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    debugImplementation("$publicationGroup:runtime-protobuf:$publicationVersion")
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        tasks.register("verifyPublishedRuntimeClasspath") {
            group = "verification"
            description = "公開repositoryだけからPlugin適用と全SDK artifactの解決を検証します。"
            doLast {
                val resolvedComponents =
                    variant.runtimeConfiguration
                        .incoming
                        .resolutionResult
                        .allComponents
                        .map { component -> component.id.displayName }
                        .toSet()
                val expectedComponents =
                    listOf(
                        "protocol",
                        "runtime-core",
                        "runtime-preferences",
                        "runtime-shared-preferences",
                        "runtime-protobuf",
                    ).map { artifactId ->
                        "$publicationGroup:$artifactId:$publicationVersion"
                    }
                val missingComponents =
                    expectedComponents.filter { expected ->
                        resolvedComponents.none { resolved ->
                            resolved == expected || resolved.startsWith("$expected:")
                        }
                    }
                check(missingComponents.isEmpty()) {
                    "公開SDK artifactを解決できません: $missingComponents\n" +
                        "解決結果: ${resolvedComponents.sorted()}"
                }
                logger.lifecycle(
                    "公開済みGradle PluginとSDK artifactの独立consumer検証に成功しました。",
                )
            }
        }
    }

    onVariants(selector().withBuildType("release")) { variant ->
        tasks.register("verifyPublishedReleaseClasspath") {
            group = "verification"
            description = "release runtime classpathにDataStore Inspectorが入らないことを検証します。"
            doLast {
                val resolvedComponents =
                    variant.runtimeConfiguration
                        .incoming
                        .resolutionResult
                        .allComponents
                        .map { component -> component.id.displayName }
                        .toSet()
                val leakedComponents =
                    resolvedComponents.filter { resolved ->
                        resolved.startsWith("$publicationGroup:")
                    }
                check(leakedComponents.isEmpty()) {
                    "release runtime classpathへ公開SDK artifactが混入しています: " +
                        leakedComponents.sorted()
                }
                logger.lifecycle("公開済みGradle Pluginのrelease分離検証に成功しました。")
            }
        }
    }
}

tasks.register("verifyJdk17Consumer") {
    group = "verification"
    description = "JDK 17で公開artifactを使う独立consumerをassemble・検証します。"
    check(JavaVersion.current() == JavaVersion.VERSION_17) {
        "verifyJdk17ConsumerはJDK 17で実行してください。現在: ${JavaVersion.current()}"
    }
    dependsOn(
        "assembleDebug",
        "assembleRelease",
        "verifyPublishedRuntimeClasspath",
        "verifyPublishedReleaseClasspath",
    )
}
