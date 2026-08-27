<p align="center">
  <img src="docs/assets/datastore-inspector-icon.svg" alt="DataStore Inspector" width="144" height="144" />
</p>

<h1 align="center">DataStore Inspector SDK</h1>

<p align="center">English | <a href="README.ja.md">日本語</a></p>

**Inspect and edit SharedPreferences and DataStore values in a running Android app from Android Studio.** Reduce temporary logs, throwaway debug screens, and rebuilds used only to inspect state.

DataStore Inspector brings the "inspect a running app" workflow pioneered by [Stetho](https://facebook.github.io/stetho/) and [Flipper](https://github.com/facebook/flipper) to SharedPreferences and Jetpack DataStore.

- Automatically discover Preferences DataStore and persisted SharedPreferences
- Browse, search, and edit Preferences, SharedPreferences, and registered Proto DataStore instances
- Track store changes and update the application's actual instances through official APIs

The SDK is added only to supported debuggable variants and leaves release variants untouched. It does not send store keys, values, or schemas to external servers, and adds no internet communication, telemetry, or Android network permission. Device communication is limited to an authenticated local connection over ADB forwarding.

## Install

### Use an AI coding agent

Open the Android project and give your coding agent this prompt:

```text
Add the latest stable DataStore Inspector SDK release to this Android project.

- Find the newest non-draft, non-prerelease GitHub Release, verify that the same version is available from both the Gradle Plugin Portal and Maven Central, and pin that exact version. If you cannot verify it, ask me instead of guessing.
- Follow the existing Gradle conventions and apply the com.masaibar.datastore-inspector Plugin only to the Android application module.
- Do not add Runtime artifacts manually or change Gradle, AGP, Kotlin, Android SDK versions, or unrelated files.
- If a Proto schema mapping is needed, ask me for the generated message class and fully qualified Proto message name instead of guessing.
- Run the smallest debug build for the target module, then report the changed files and result.
```

### Configure manually

With Version Catalog:

```toml
[versions]
datastore-inspector = "1.0.0"

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
  id("com.masaibar.datastore-inspector") version "1.0.0"
}
```

Apply the Plugin only to the Android application module. It automatically adds the required Runtime components to supported debuggable variants.

### Proto DataStore

Only Proto DataStore requires registration of the generated message class and fully qualified Proto message name:

```kotlin
dataStoreInspector {
  schemaEntry(
    "com.example.settings.proto.UserSettings",
    "example.settings.UserSettings"
  )
}
```

Build and run a debuggable variant, then select the application in DataStore Inspector. See [`sample-app`](sample-app) for an executable example.

## Learn more

- [Compatibility and known limitations](docs/en/compatibility.md)
- [What the Gradle Plugin changes](docs/en/what-is-injected.md)
- [Custom DataStore inspection](docs/en/custom-datastore.md)
- [Security](docs/en/security.md) / [Privacy](docs/en/privacy.md)

## License

[Apache License 2.0](LICENSE). Report vulnerabilities privately by following [`SECURITY.md`](SECURITY.md).

Android is a trademark of Google LLC.

The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
