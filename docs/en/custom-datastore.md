# Custom DataStore inspection

English | [日本語](../custom-datastore.md)

Protocol 1.2 can display and replace typed DataStore values other than Proto and Preferences as a
JSON or text document, but only when Runtime can safely prove the actual Serializer contract. It
never sends raw persistence bytes to the inspection client or guesses a type or encryption format.
A Store for which no safe projection can be resolved without configuration is `Unsupported` with
a fixed reason code.

## Projection priority

Runtime validates candidates in the following order for every snapshot. If multiple candidates
succeed, all of their formats and canonical documents must also match. Only then is the first
candidate selected.

1. `direct-json-v1`: the actual Serializer output is strict UTF-8 JSON
2. `structured-json-v1`: the kotlinx.serialization `KSerializer` and `SerializersModule` captured
   while the actual Serializer runs
3. `generated-json-v1`: the runtime class is non-generic and its generated `KSerializer` can be
   obtained safely
4. `direct-text-v1`: strict UTF-8 text
5. `fallback:<codec-id>:<schema-version>`: an explicitly configured debug-only codec binding

Each candidate encodes the same value twice to prove determinism, then verifies document decode,
runtime class, `equals`, `hashCode`, document re-encode, and a persistence round trip through the
actual Serializer. JSON rejects duplicate keys, non-finite numbers, and invalid UTF-16. The result
is ambiguous instead of guessed if successful candidates disagree on format or document, if
structured capture observes multiple different root contracts, or if debug codecs are duplicated
or ambiguous.

The projection cache retains only the candidate path for the same runtime class and root-contract
generation. The document/value gate is rerun for every snapshot. `structured-json-v1`,
`generated-json-v1`, and fallback codecs must also pass persistence preflight by encoding and
decoding the current value with the actual Serializer. The cache is discarded and priority is
resolved again when Runtime captures a new root contract, observes a runtime-class change, or
observes the actual output become JSON or text.

Only an `@JvmInline` root may account for reboxing caused by a generic call and accept a unique
candidate whose exact runtime class, `equals`, and `hashCode` all match. Ordinary classes require
object-identity equality.

## Debug-only fallback codec

A fallback codec is selected only when none of the four zero-configuration paths can be proven. If
a binding exists, Runtime also probes it when automatic projection succeeds and fails closed if the
format or canonical document disagrees. Place the codec class in a source set used only by
debuggable variants, such as `src/debug`, and give it a public no-argument constructor.

```kotlin
public class SettingsCodec : InspectorCustomCodec<Settings> {
    override val codecId: String = "settings"
    override val schemaVersion: Int = 1
    override val format: CustomDocumentFormat = CustomDocumentFormat.JSON

    override fun encode(value: Settings): String =
        """{"label":${Json.encodeToString(value.label)}}"""

    override fun decode(document: String): Settings {
        val root = Json.parseToJsonElement(document).jsonObject
        return Settings(root.getValue("label").jsonPrimitive.content)
    }

    override fun validate(value: Settings) {
        require(value.label.length <= 256)
    }
}
```

In the application module's Gradle configuration, bind the exact JVM classes of the Serializer,
value, and codec one-to-one.

```kotlin
dataStoreInspector {
    customCodecBinding(
        "com.example.SettingsSerializer",
        "com.example.Settings",
        "com.example.debug.SettingsCodec",
    )
}
```

`codecId` must match `[A-Za-z0-9][A-Za-z0-9._-]{0,63}`, the schema version must be positive, and
the format must be JSON or TEXT. Duplicate provider IDs, duplicate Serializer/value bindings,
providers that throw, and type mismatches all become ambiguous and fail closed. Runtime namespaces
the projection ID, for example as `fallback:settings:1`.

The Gradle Plugin verifies binding type relationships by compiling generated source and generates
the ServiceLoader provider only for debuggable variants. The codec, generated source and resources,
Runtime, Protocol, and hook references are absent from release APKs and AABs.

## Supported creation paths

For AndroidX DataStore 1.2.1, the following known descriptors are instrumented:

- typed `dataStore` and `deviceProtectedDataStore` delegates
- `DataStoreFactory.create` and `createInDeviceProtectedStorage`
- `MultiProcessDataStoreFactory.create`
- Serializer overloads, Storage overloads, and known default overloads of those factories
- `FileStorage` and `OkioStorage` constructors

Application variants use `InstrumentationScope.ALL`, so call sites in reachable project and
dependency classes are covered. Android library variants to which the Plugin is applied directly
transform only `PROJECT`. AndroidX, Kotlin, kotlinx, and the Inspector itself are excluded, and
owner/descriptor pairs not present in the supported table are never guessed.

`File` and `Path` producers are never invoked an additional time to obtain metadata. Runtime
observes a basename only when normal DataStore creation invokes the producer. A Serializer for a
Proto `MessageLite` is not wrapped and is handed to the existing Proto adapter.

A Custom Store whose actual Serializer cannot be captured through a supported creation path uses
`CUSTOM_CREATION_ROUTE_UNSUPPORTED` or `CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE`.
`registerFallback` registers the same instance held by the application; it does not make an
uncaptured Serializer contract guessable.

## Write and timeout boundaries

Custom replace claims the snapshot revision/content token only once and rechecks the current
fingerprint inside the same DataStore instance's `updateData` transaction. It proceeds only when
projection metadata matches and the changed document produces a different value of the same type.
A type whose `equals` ignores a changed field and may cause DataStore to skip the write is reported
as `CUSTOM_VALUE_EQUALITY_TOO_COARSE`.

The candidate is encoded by the actual Serializer into a scratch buffer. It is committed to the
real sink only after decode and exact projection agreement are verified. If the actual Serializer
output does not match the submitted document, Runtime aborts before commit and quarantines the
Store. Resending the same document after success does not produce another write.

The process-wide inspection executor has two workers, a queue of 64 tasks, and a five-second limit
starting after task execution begins. Waiting on the same Store's single flight consumes neither a
worker nor a queue slot, and the five-second budget begins when Serializer processing starts. Queue
rejection and timeout or cancellation while a task is still queued remove the Future immediately
and are transient failures that do not quarantine the Store. Timeout or cancellation after start
quarantines Stores belonging to the same shared entry for the original Serializer identity.

When multiple Stores share the same original Serializer identity, they also share the mutex that
serializes original calls and the poison reason for a post-start timeout. After a timeout, an
inspection already waiting on that mutex rechecks the poison after acquiring it and does not call
the original Serializer. Additional inspections after the timeout are rejected before executor
submission. In contrast, a transient timeout before a queued task starts and Store-specific
reasons such as an actual-write mismatch do not become shared poison and do not stop ordinary
application delegate reads or writes. The shared entry is discarded after all handles close, so a
new handle later created for the same Serializer identity does not inherit an old poison reason.

A timeout before mutation-expectation creation has `operationStarted=false`. A timeout after that
creation and before Runtime observes the Future result, or an unknown exception during commit or
return, has `operationStarted=true`; the inspection client treats the outcome as unknown and does
not replay automatically. After quarantine, list reports `UNSUPPORTED`, no capabilities, and a
fixed reason code, while repeated snapshot and replace requests are rejected.

Ordinary Serializer and codec exceptions are normalized to fixed reasons. A
`CancellationException`, `VirtualMachineError`, `ThreadDeath`, or `LinkageError` anywhere in the
cause chain is not swallowed as an ordinary failure; the deepest exception identity is preserved
and propagated across the executor boundary.

## Protocol and limits

A Custom Store requires `custom.document.get`; replacement additionally requires
`custom.document.replace`. For Protocol 1.0/1.1-equivalent clients that do not advertise Custom
capabilities, known static Custom Stores are still listed as `CUSTOM` / `UNSUPPORTED`, with no
capabilities, no schema, and a fixed safe reason. Snapshot downgrades to the existing wire type
`UnsupportedSnapshotInfo` without invoking an adapter or projection, and write is rejected with
`STORE_UNSUPPORTED`. This prevents old clients from receiving an unknown `CustomDocumentPayload`
or Custom operation subtype. A GET-only client can continue to use list and snapshot; only replace
returns a capability error.

Shared validation limits are:

- document: 1 MiB in UTF-8
- JSON depth: 64; nodes: 100,000; entries in one collection: 10,000
- JSON string: 256 KiB in UTF-8; number token: 1,024 characters
- projection ID: 128 bytes in UTF-8
- persistence scratch buffer: 8 MiB
- structured root contracts: 2; decode candidates: fixed at 16

Reasons, errors, and logs contain no class name, exception, document, value, raw bytes, session
token, or absolute path.

## Diagnosing `Unsupported`

The fixed reason code shown by the inspection client is a classification that contains no
sensitive information. Common checks are:

- `CUSTOM_CREATION_ROUTE_UNSUPPORTED` / `CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE`: verify the
  supported creation paths, `InstrumentationScope`, and whether the actual Serializer can be
  obtained through that path.
- `CUSTOM_STRUCTURED_CODEC_NOT_CAPTURED` / `CUSTOM_TYPE_ARGUMENTS_UNAVAILABLE` /
  `CUSTOM_CONTEXTUAL_MODULE_UNAVAILABLE`: a zero-configuration projection cannot prove the
  contract. If necessary, explicitly configure a fallback codec in a debug source set.
- `CUSTOM_*_ROUND_TRIP_MISMATCH` / `CUSTOM_SERIALIZER_NON_DETERMINISTIC`: verify that the
  Serializer, codec, `equals`, and `hashCode` represent all state reversibly and deterministically.
- `CUSTOM_PROBE_TIMEOUT` / `CUSTOM_ACTUAL_WRITE_MISMATCH`: the Store is quarantined for the current
  process generation. Do not replay automatically; fix the cause, restart the app process, and
  obtain a new snapshot.
