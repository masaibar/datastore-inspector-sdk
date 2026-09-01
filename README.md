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
Add the latest stable DataStore Inspector SDK release to this Android project by following the official documentation.

Official sources:
- Repository and installation guide: https://github.com/masaibar/datastore-inspector-sdk
- Latest stable GitHub Release API: https://api.github.com/repos/masaibar/datastore-inspector-sdk/releases/latest
- Gradle Plugin Portal: https://plugins.gradle.org/plugin/com.masaibar.datastore-inspector
- Maven Central runtime-core metadata: https://repo1.maven.org/maven2/com/masaibar/datastore%2Dinspector/runtime-core/maven-metadata.xml
- Maven Central runtime-shared-preferences metadata: https://repo1.maven.org/maven2/com/masaibar/datastore%2Dinspector/runtime-shared-preferences/maven-metadata.xml
- Maven Central runtime-preferences metadata: https://repo1.maven.org/maven2/com/masaibar/datastore%2Dinspector/runtime-preferences/maven-metadata.xml
- Maven Central runtime-protobuf metadata: https://repo1.maven.org/maven2/com/masaibar/datastore%2Dinspector/runtime-protobuf/maven-metadata.xml
- Compatibility and supported configurations: https://github.com/masaibar/datastore-inspector-sdk/blob/main/docs/en/compatibility.md

Requirements:
- Read the installation guide and compatibility document before changing files.
- Read `tag_name` from the latest stable GitHub Release, remove only its leading `v`, verify that the exact version is available from the Gradle Plugin Portal and all four Maven Central metadata URLs above, and pin it. If any source disagrees or cannot be verified, ask me instead of guessing or selecting another version.
- Inspect the existing Gradle structure and preserve its conventions, including Version Catalogs and convention plugins. Apply `com.masaibar.datastore-inspector` only to the target Android application module and ensure its dependency repositories include `mavenCentral()` without duplicating repository declarations. If more than one application module exists and the target is unclear, ask me before changing files.
- Do not add Runtime artifacts manually or change Gradle, AGP, Kotlin, Android SDK, `org.gradle.configureondemand`, configuration-cache settings, or unrelated files.
- Do not add a Proto schema mapping by default. If the Proto schema is outside the documented automatic mapping scope, ask me for the generated class and fully qualified Proto message name instead of guessing.
- Run the smallest relevant debug build for the application module. If it fails, diagnose and report the cause without weakening existing build settings or release isolation.
- Report the selected exact version, changed files, verification command and result, and any remaining manual step.
```

### Configure manually

The consumer project's dependency repositories must include `mavenCentral()` because the Plugin does not add repositories.

Replace `<latest-stable-version>` with the newest exact version published to both the Gradle Plugin Portal and Maven Central.

With Version Catalog:

```toml
[versions]
datastore-inspector = "<latest-stable-version>"

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
  id("com.masaibar.datastore-inspector") version "<latest-stable-version>"
}
```

Apply the Plugin only to the Android application module. It automatically adds the required Runtime components to supported debuggable variants.

### Proto DataStore

Supported Proto2 and Proto3 Java Lite DataStore instances need no schema registration; applying the Plugin is enough.

Build and run a debuggable variant, then select the application in DataStore Inspector. See [`sample-app`](sample-app) for an executable example.

See the [support scope](docs/en/compatibility.md) for supported configurations, limitations, and the explicit `schemaEntry` mapping.

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
