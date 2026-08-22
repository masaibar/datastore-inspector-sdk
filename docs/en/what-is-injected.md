# What the Gradle Plugin changes

English | [日本語](../what-is-injected.md)

For supported debuggable variants, the Gradle Plugin:

- always adds Runtime Core and the SharedPreferences catalog adapter
- adds matching DataStore adapters when dependency signals are present
- generates a non-exported startup Provider in the default app process
- adds a versioned Package Manager marker as a second authority on that Provider
- instruments supported first-party DataStore creation paths
- instruments supported `DataStoreFactory` / `MultiProcessDataStoreFactory`, `FileStorage` /
  `OkioStorage`, typed delegate, and kotlinx.serialization call sites
- generates an optional debug-only Custom codec binding provider
- generates Proto descriptor assets and a schema index when applicable
- adds keep rules required by debuggable minified fixtures

For non-debuggable variants, it must not:

- add Inspector Runtime or Protocol artifacts
- add the startup Provider
- add schema assets
- transform application or library bytecode
- add Android permissions

The Provider retains the legacy `${applicationId}.datastore_inspector_init` authority and also
declares `${applicationId}.datastore_inspector_runtime_v1`. The latter is a marker-format version
that lets an inspection client identify an installed Runtime without starting the app. It is not
a claim that a Runtime session is ready and is not the transport Protocol version; those are
established separately by session metadata and an authenticated handshake.

Application variants use `InstrumentationScope.ALL`; Android library variants to which the Gradle
Plugin is applied directly use `InstrumentationScope.PROJECT`. AndroidX DataStore, Kotlin,
kotlinx.coroutines, kotlinx.serialization, and the Inspector's own namespace are not transformed.
Only supported owner/method descriptors are replaced; unknown descriptors are never guessed
silently.

`File` and `Path` producers are not evaluated an additional time during creation. Runtime observes
only the basename from the result of the DataStore's normal call and retains no absolute path in
metadata.

`checkSdk` verifies that debug and debuggable staging each contain exactly one startup Provider,
one SharedPreferences catalog SPI provider, and at most one generated Custom codec binding
provider. It also scans release dependency graphs, manifests, APKs, AABs, and DEX markers to
ensure that Runtime modules, hook/codec references, generated bindings/providers, and SPI
resources are absent.

The Plugin adds no internet communication. Inspection-client communication uses an authenticated
local socket reached through ADB forwarding.
