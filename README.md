<p align="center">
  <img src="docs/assets/datastore-inspector-icon.svg" alt="DataStore Inspector" width="144" height="144" />
</p>

<h1 align="center">DataStore Inspector SDK</h1>

<p align="center">English | <a href="README.ja.md">日本語</a></p>

**Inspect and edit SharedPreferences and DataStore values in a running Android app from Android Studio.** Reduce temporary logs, throwaway debug screens, and rebuilds used only to inspect state.

DataStore Inspector was born from a desire to bring back the excellent developer experience once offered by [Stetho](https://facebook.github.io/stetho/) and [Flipper](https://github.com/facebook/flipper): inspecting and updating values in a running app, now for SharedPreferences and Jetpack DataStore.

- Automatically discover supported Preferences DataStore, Proto DataStore, and persisted SharedPreferences instances ([support scope](docs/en/compatibility.md))
- Browse, search, and edit supported Preferences, Proto, and SharedPreferences values
- Track supported store changes and update the application's actual instances through official APIs

The SDK is added only to supported debuggable variants and leaves release variants untouched. It does not send store keys, values, or schemas to external servers, and adds no internet communication, telemetry, or Android network permission. Device communication is limited to an authenticated local connection over ADB forwarding.

## Install

### Use an AI coding agent

Open the Android project and give your coding agent this prompt:

```text
Add the latest stable DataStore Inspector SDK release to this Android project.

- Find the newest non-draft, non-prerelease GitHub Release, verify that the same version is available from both the Gradle Plugin Portal and Maven Central, and pin that exact version. If you cannot verify it, ask me instead of guessing.
- Follow the existing Gradle conventions and apply the com.masaibar.datastore-inspector Plugin only to the Android application module.
- Do not add Runtime artifacts manually or change Gradle, AGP, Kotlin, Android SDK versions, or unrelated files.
- Do not add a Proto schema mapping by default. If automatic mapping is unavailable for a descriptor-backed schema, ask me for the generated message class and fully qualified Proto message name instead of guessing.
- Run the smallest debug build for the target module, then report the changed files and result.
```

### Configure manually

The consumer project's dependency repositories must include `mavenCentral()` because the Plugin does not add repositories.

With Version Catalog:

```toml
[versions]
datastore-inspector = "1.1.0"

[plugins]
datastore-inspector = { id = "com.masaibar.datastore-inspector", version.ref = "datastore-inspector" }
```

```kotlin
plugins {
  id("com.android.application")
  alias(libs.plugins.datastore.inspector)
}
```

Without Version Catalog:

```kotlin
plugins {
  id("com.android.application")
  id("com.masaibar.datastore-inspector") version "1.1.0"
}
```

Apply the Plugin only to the Android application module. It automatically adds the required Runtime components to supported debuggable variants.

### Proto DataStore

For supported Proto DataStore, no additional configuration is required. The Plugin maps Proto2 and Proto3 Java Lite messages from descriptors collected for the application and reachable first-party Android project modules. It supports `java_package`, `java_outer_classname`, `java_multiple_files`, nested messages, and default outer-class name collisions.

Automatic mapping does not cover schemas available only inside external AARs or JARs without collected descriptors, custom code generators, the full Java protobuf runtime, Editions 2024 or later, or debuggable variants that obfuscate generated class names. See the [support scope](docs/en/compatibility.md).

The Stable `schemaEntry` API remains available as an explicit mapping when the descriptor is collected but automatic naming is outside the supported scope:

```kotlin
dataStoreInspector {
  schemaEntry(
    generatedJvmClassName = "com.example.settings.proto.UserSettings",
    rootMessageFullName = "example.settings.UserSettings"
  )
}
```

`schemaEntry` does not supply a missing descriptor, so it cannot enable schemas that exist only in an external binary.

Build and run a debuggable variant, then select the application in DataStore Inspector. See [`sample-app`](sample-app) for an executable example.

## Learn more

- [Compatibility and known limitations](docs/en/compatibility.md)
- [API stability and versioning](docs/en/api-stability.md)
- [What the Gradle Plugin changes](docs/en/what-is-injected.md)
- [Custom DataStore inspection](docs/en/custom-datastore.md)
- [Security](docs/en/security.md) / [Privacy](docs/en/privacy.md)

## License

[Apache License 2.0](LICENSE). Report vulnerabilities privately by following [`SECURITY.md`](SECURITY.md).

Android is a trademark of Google LLC.

The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
