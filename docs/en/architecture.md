# SDK architecture

English | [日本語](../architecture.md)

```text
Consumer debug build
        │
        ▼
Gradle Plugin
  ├── adds Runtime modules
  ├── instruments supported DataStore creation paths
  └── packages Proto schema assets
        │
        ▼
Android app process
  ├── Runtime Core
  │     ├── instrumentation-derived DataStore Registry
  │     └── dynamic Store Catalog scanned on request
  ├── Preferences Adapter
  ├── SharedPreferences Adapter
  ├── Proto Adapter
  ├── Custom projection Adapter
  └── authenticated LocalServerSocket
        │
        │ ADB forwarding + versioned Protocol
        ▼
Host inspection client
```

## Ownership boundary

The SDK owns build-time instrumentation, app-process behavior, DataStore adapters, and the
transport Protocol. It exposes no IntelliJ Platform API and contains no UI, Marketplace,
entitlement, Snapshot, Preset, or MCP policy.

Client implementations consume the published Protocol artifact and remain outside this
repository.

## Runtime safety

- The generated Provider starts only in a debuggable default process.
- The same Provider exposes the versioned
  `${applicationId}.datastore_inspector_runtime_v1` Package Manager marker. The marker remains
  visible while the app is stopped, but does not imply that a process or authenticated Runtime
  session exists.
- Below API 28, the Provider is a no-op and creates no session metadata, socket, or thread. The
  Runtime AAR manifest still declares `minSdk` 23.
- A random private session token authenticates each connection.
- ADB forwards a host loopback port to an abstract local socket.
- Preferences mutations use `DataStore.edit`.
- Runtime catalogs only persisted credential-protected SharedPreferences files and treats the
  platform API as the source of truth for values, using exactly one `commit()`. To distinguish
  framework parse failures that are exposed as an empty map, Runtime validates, with fail-closed
  behavior, the standard six-type structure of the authoritative XML, preferring `.bak`, before
  open and immediately before mutation.
- SharedPreferences snapshots, write preflight, post-read, and `commit()` each run on a
  non-queued lane with a detached daemon worker. The lane remains held after the response
  deadline until its worker finishes, keeping the Runtime's single client loop and worker count
  bounded. Deadline waits are not dispatched to the application's shared `Dispatchers.IO`.
- SharedPreferences comparison and write are not atomic, so Protocol 1.1 uses
  `BEST_EFFORT_NON_ATOMIC` semantics and an unknown-outcome state.
- On connections that negotiate Protocol 1.4 `store.changes`, Runtime subscribes to each
  supported Store only while that connection is active. Preferences and Proto DataStore reuse
  the existing `data` Flow; SharedPreferences starts from a framework listener. Each observation
  sends the canonical full Store state. The first state is `BASELINE`; only states whose
  fingerprint changed become `CHANGE` entries in a per-Store continuous sequence. Whether to
  include the baseline in history and how to present before/after differences are client
  responsibilities.
- The dynamic Store catalog is reconciled while connected. Notifications carry both the Store
  incarnation and the connection-subscription generation. The same incarnation is placed in the
  `generation` of `store.changes` descriptors and in `storeGeneration` on snapshots returned by
  the current Runtime, allowing the client to match a fresh read with its restore target.
  Observers, Flow collections, SharedPreferences listeners, reconciliation jobs, the notification
  writer, and pending frames are explicitly disposed when the connection ends.
- Store observation queues and the socket-writer queue have fixed bounds. If conflation,
  backpressure, oversized state, observation failure, or Store removal prevents a continuous
  state sequence, Runtime sends a value-free boundary notification so the client cannot mistake
  the missing interval for an ordinary change.
- Inspector writes may include an optional opaque correlation ID. Runtime matches the write-result
  fingerprint with observer notifications for the same Store and returns the ID only on the
  corresponding notification. Timeline source classification and deduplication are client
  responsibilities.
- Proto mutations use `DataStore.updateData`.
- Custom mutations use the same captured `DataStore` instance and its `updateData` transaction.
  Runtime verifies the edited document, candidate value, original persistence round trip, and
  actual Serializer output before reporting success.
- Content tokens and revisions reject stale mutations.
- Unsupported Serializers remain explicitly read-only.

[`custom-datastore.md`](custom-datastore.md) is the source of truth for Custom projection
selection, safety, creation paths, and timeout boundaries.

## Release boundary

The Gradle Plugin connects Runtime dependencies, generated assets, Provider, and instrumentation
only to debuggable variants. Release verification audits the classpath, manifest, packaged assets,
class references, APK output, and AAB output.
