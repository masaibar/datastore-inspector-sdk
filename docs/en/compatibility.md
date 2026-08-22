# Compatibility and known limitations

English | [日本語](../compatibility.md)

## Initial support scope

- Android Studio Panda 4 Patch 1 (`AI-253.32098.37.2534.15336583`)
- Android Studio Quail 2 (`AI-261.25134.95.2612.15822958`)
- JDK 21, Gradle 9.6.1, and AGP 9.2.1
- Runtime AAR and consumer-app manifests using `minSdk` 23, with a verification sample using
  `compileSdk` and `targetSdk` 36
- Inspector connectivity and operation on Android 9 (API 28) or later
- AndroidX DataStore 1.2.1
- Kotlin 2.3.21
- protobuf-javalite 4.35.0 and protobuf Gradle Plugin 0.10.0
- `preferencesDataStore` and typed `dataStore` delegates in the application and reachable
  first-party Android libraries
- Custom DataStore created through typed `dataStore` or `deviceProtectedDataStore`,
  `DataStoreFactory`, `MultiProcessDataStoreFactory`, `FileStorage`, or `OkioStorage`
- Supported creation call sites in dependency artifacts reachable through the application's
  `InstrumentationScope.ALL`
- Debug and custom build types with `debuggable = true`
- Credential-protected SharedPreferences in the main/default process
  (`String`, `Int`, `Long`, `Float`, `Boolean`, and `Set<String>`)

## Out of scope for the initial release

- Non-debuggable variants
- Devices running API 27 or earlier
- Connections to secondary processes
- Device-protected storage, EncryptedSharedPreferences decryption, and automatic detection of
  custom encryption wrappers
- Automatic instrumentation of KMP Android `PreferenceDataStoreFactory.createWithPath` and
  direct Factory creation
- Non-Android KMP targets such as iOS
- Custom DataStore instances that do not pass through a supported creation call site, and paths
  from which the actual instance or Serializer cannot be obtained
- Guessing or directly editing a Custom Serializer's raw binary representation

For Android paths outside automatic instrumentation, a debug-only `registerFallback` API accepts
the same DataStore instance held by the application. The sample and initially guaranteed delegate
paths do not use this fallback.

## Safety boundaries

- Release APKs and AABs must not contain Runtime, Protocol, Provider, instrumentation-hook
  references, or schemas.
- Only debuggable variants contain the versioned Runtime marker. Marker presence, process-running
  state, and availability of an authenticated session are treated separately.
- The server starts only in the main/default process.
- Runtime does not start on API 27 or earlier; only API 28 and later are supported.
- Connections use a private session token and an abstract local socket reachable only through ADB
  forwarding.
- Stale snapshots are not replayed automatically; conflicts are rejected inside the DataStore
  transaction.
- Protocol 1.3 `preferences.replace` replaces the full typed entry set with one `updateData` call
  for DataStore, or one `Editor` and one `commit()` for SharedPreferences. Connections using
  Protocol 1.2 or earlier do not negotiate the capability and are not sent an unknown operation
  subtype.
- Protocol 1.4 `store.changes` is enabled only when both peers advertise the capability.
  Connections using Protocol 1.3 or earlier start neither observers nor a notification writer and
  do not depend on the added notification subtype, logical Store ID, or write correlation.
  Logical Store ID, Store descriptor/snapshot generation, and correlation ID are optional fields
  appended to existing models, so a 1.4 artifact can still decode saved requests and responses
  from 1.3 or earlier. Only a 1.4 Runtime advertising `store.changes` requires descriptor
  generation, and snapshots from the current Runtime return the same incarnation.
- `store.changes` covers Preferences DataStore, Proto DataStore, and standard credential-protected
  SharedPreferences. Custom DataStore does not advertise a subscription capability in 1.4 because
  safe projection and a continuous-observation contract for arbitrary Serializers require
  separate guarantees.
- The first notified state is a baseline candidate, not a history event. When multiple keys change
  in one canonical fingerprint transition, the entire Store produces one notification. If a
  bounded listener or transport queue may lose continuity, Runtime switches to a value-free
  boundary.
- SharedPreferences detects a pre-write fingerprint conflict but does not provide atomic
  compare-and-set. A false `commit()` result or exception is treated as an unknown outcome and is
  not replayed automatically.
- A Custom Serializer is writable only when Runtime can prove a safe projection. Otherwise the
  Store is `Unsupported` with a safe fixed reason code.
- Custom Stores sharing the same original Serializer identity also share an inspection mutex and
  post-start timeout quarantine. A temporary timeout before a queued task starts does not poison
  the shared quarantine, and an old reason is not inherited by a new identity entry after all
  handles have been released.
- For legacy clients that do not advertise the Protocol 1.2 Custom capability, known static Custom
  Stores are listed as `CUSTOM` / `UNSUPPORTED`, with no capabilities, no schema, and a fixed safe
  reason. Snapshots downgrade to the existing wire type `UnsupportedSnapshotInfo`, and writes are
  rejected with `STORE_UNSUPPORTED`, so old clients never receive an unknown
  `CustomDocumentPayload` or Custom operation subtype.

Whenever versions change, rerun the Gradle Plugin TestKit suite, sample application verification,
and release-isolation verification.
