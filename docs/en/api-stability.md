# API stability

English | [日本語](../api-stability.md)

The only Stable consumer APIs in DataStore Inspector SDK are the Gradle Plugin ID `com.masaibar.datastore-inspector`, the `dataStoreInspector` extension, and `schemaEntry` for Proto registration. Do not add Runtime artifacts directly or call Runtime or Protocol classes from application code.

## Classifications

- **Stable**: the Plugin ID, extension, and `schemaEntry` listed above. Breaking changes wait for the next major version.
- **Experimental**: Custom DataStore codecs, fallback registration, and `customCodecBinding`. They require an explicit opt-in to `ExperimentalDataStoreInspectorApi` or `ExperimentalDataStoreInspectorGradleApi` and may change or be removed in a minor version.
- **Internal**: Protocol Kotlin models and codecs, Runtime bridges, adapters and ServiceLoader types, and Gradle tasks and Plugin implementation. Some remain public in bytecode for instrumentation, generated code, or module integration, but they are not consumer APIs. Opting in to an Internal marker is only for SDK integration and carries no compatibility guarantee.

The JVM API and ABI baseline and source-classification gate for published artifacts detect unintended additions, missing classifications, and binary compatibility changes. An independent consumer compiles the Stable DSL with named arguments. Inclusion in the baseline does not make a declaration Stable.

## Versioning

Removing a Stable API requires a documented replacement and deprecation for at least one minor release, and removal waits for the next major release. Backward-compatible additions use a minor version and bug fixes use a patch version. Experimental and Internal APIs do not receive this deprecation period.

## Ending platform support

Removing a documented supported version of Android API level, AGP, Gradle, JDK, Kotlin, DataStore, or another platform dependency is a breaking change. Normally, the SDK announces the removal and a migration path for at least one minor release and removes support in the next major release. If a critical security issue or upstream end of support requires an earlier change, the minor release notes document the reason, affected scope, and last supporting SDK version.

Pin the exact version verified across the GitHub Release, Gradle Plugin Portal, and Maven Central instead of using a dynamic version range.

Protocol wire compatibility is a separate contract from Kotlin API and ABI. The IDE and Runtime verify it through capability negotiation and cross-repository contract fixtures.
