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
Add DataStore Inspector SDK to this Android project using the installation guide and compatibility notes:
https://github.com/masaibar/datastore-inspector-sdk

- Verify that the latest stable release is published on both Gradle Plugin Portal and Maven Central, and pin that version.
- Preserve the existing Gradle conventions and build settings. Apply the Plugin to the target Android application module; do not add Runtime dependencies manually.
- Add Proto mappings only when required by the documented support scope. Ask before proceeding if the target, version, or mapping is unclear.
- Run a debug build and report the changes, results, and any stores that still need manual setup.
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
