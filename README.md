<p align="center">
  <img src="docs/assets/datastore-inspector-icon.svg" alt="DataStore Inspector" width="144" height="144" />
</p>

<h1 align="center">DataStore Inspector SDK</h1>

<p align="center">English | <a href="README.ja.md">日本語</a></p>

**Inspect and update SharedPreferences and DataStore values in a running Android app—right from Android Studio.** Spend less time adding temporary logs, building throwaway debug screens, and rebuilding just to verify application state.

[Stetho](https://facebook.github.io/stetho/) and [Flipper](https://github.com/facebook/flipper) made it natural to peek inside a running Android app. Both projects are now archived, but the need for that fast feedback loop remains—especially as application state moves into SharedPreferences and Jetpack DataStore. DataStore Inspector was created to bring that workflow to modern Android state without requiring one-off debugging code.

Add this SDK to your Android application and connect with the DataStore Inspector plugin for Android Studio to use Live Mode:

- Discover Preferences DataStore and persisted SharedPreferences without Inspector-specific setup
- Browse and search stores, including registered Proto DataStore instances, and inspect current values
- Write supported values back to the running app and follow store changes as they happen
- Mutate the actual store instance through official APIs instead of bypassing DataStore transactions

The SDK is added only to supported debuggable variants. Release variants remain untouched.

Store keys, values, and schemas are never sent to an external server. Communication is limited to an authenticated local connection between the device and Android Studio over ADB forwarding. The SDK adds no internet communication, telemetry, or Android network permission.

This SDK is open source so that teams can inspect the code they add to their applications. What the SDK injects into debug builds, what runs inside the application process, how it accesses stores, and what it keeps out of release builds are all visible. This transparency lets you decide whether the SDK is appropriate for your own application instead of asking you to trust a black box.

This repository contains the application-side SDK added to consumer debug builds and executed inside the application process. The Android Studio UI and inspection client are provided by the IDE plugin.

## Modules

- `gradle-plugin`: dependency injection, schema generation, and ASM instrumentation for debuggable variants
- `protocol`: versioned protocol shared by the Android Runtime and inspection clients
- `runtime-core`: Registry, connection, authentication, and mutation safety
- `runtime-preferences`: Preferences DataStore adapter
- `runtime-shared-preferences`: dynamic catalog and standard six-type adapter for persisted SharedPreferences
- `runtime-protobuf`: Proto DataStore adapter
- `sample-app`: minimal consumer showing Preferences, SharedPreferences, and Proto setup while verifying debug-only injection and release isolation

## Quick start

Apply the Plugin to the Android application module. It adds the matching Runtime components only to supported debuggable variants; do not add Runtime artifacts manually.

```kotlin
plugins {
  id("com.android.application")
  id("com.masaibar.datastore-inspector") version "<sdk-version>"
}
```

Preferences DataStore and persisted SharedPreferences are discovered without additional Inspector configuration. For Proto DataStore, register the generated message class and its fully qualified schema name:

```kotlin
dataStoreInspector {
  schemaEntry(
    "com.example.settings.proto.UserSettings",
    "example.settings.UserSettings"
  )
}
```

Build and run a debuggable variant, then select the application from DataStore Inspector in Android Studio. Non-debuggable variants are left untouched. See [`sample-app`](sample-app) for an executable Preferences, SharedPreferences, and Proto example.

## Build

Building this repository from source requires JDK 21 and Android SDK 36. Published Gradle Plugin
and Runtime artifacts target Java 17, so consumer builds do not need to move their Gradle JVM to
JDK 21.

```shell
./gradlew checkSdk --console=plain
./gradle-plugin/gradlew -p gradle-plugin clean checkPlugin --console=plain
```

`checkSdk` also verifies that release APKs and AABs contain no Inspector Runtime, Protocol, Provider, schema, or instrumentation hook.

## Distribution

- Maven Central: `protocol`, `runtime-core`, `runtime-preferences`, `runtime-shared-preferences`, `runtime-protobuf`
- Gradle Plugin Portal: `com.masaibar.datastore-inspector`

See the [publication guide](docs/en/publishing.md) for source-boundary auditing, local consumer verification, public-repository setup, credentials, and release procedures.

## Namespace

- Kotlin/Java package: `com.masaibar.datastore.inspector.*`
- Maven group: `com.masaibar.datastore-inspector`
- Gradle Plugin ID: `com.masaibar.datastore-inspector`

[`gradle/artifact-coordinates.properties`](gradle/artifact-coordinates.properties) is the source of truth for artifact coordinates and the version.

## Documentation

- [Compatibility and known limitations](docs/en/compatibility.md)
- [SDK architecture](docs/en/architecture.md)
- [What the Gradle Plugin changes](docs/en/what-is-injected.md)
- [Custom DataStore inspection](docs/en/custom-datastore.md)
- [Security](docs/en/security.md)
- [Privacy](docs/en/privacy.md)

## License

The SDK, including the Gradle Plugin, Protocol, Runtime modules, and sample code in this repository, is licensed under the [Apache License 2.0](LICENSE).

To report a vulnerability, follow the private reporting instructions in [`SECURITY.md`](SECURITY.md).

Android is a trademark of Google LLC.

The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
