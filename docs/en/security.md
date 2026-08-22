# Security

English | [日本語](../security.md)

## Trust boundary

DataStore Inspector is a debug-only developer tool. It does not support non-debuggable or release
applications and does not use root access to bypass Android application isolation.

## Transport

- Runtime binds an Android abstract local socket, not a public TCP port.
- The inspection client reaches it only through ADB forwarding bound to host loopback.
- Connection metadata is read with `run-as` from the target app's private area.
- A random session token is required before privileged Protocol frames.
- Frame and payload sizes are validated before allocation.
- Protocol 1.4 notifications share the authenticated connection with request/response traffic. A
  single output lock preserves frame boundaries, and only a connection that negotiated
  `store.changes` starts observers or a notification writer.
- Per-Store observation queues and the encoded-frame writer queue are bounded. Queue pressure,
  oversized state, observation failure, and Store removal emit value-free boundaries instead of
  silently presenting a discontinuity as a complete change stream. Queued encoded frames are
  overwritten with zeroes when written, dropped, or closed.

## Mutation

- Runtime uses the existing DataStore instance.
- Writes go through `edit` or `updateData`.
- Stale snapshots are rejected with revision and content-token checks.
- Unknown Proto fields are retained by the supported mutation path.
- Unsupported or schema-mismatched Serializers are not guessed or raw-edited.
- Custom DataStore uses only the same actual Serializer and DataStore instance. Runtime verifies
  determinism; document, value, and persistence round trips; runtime class; `equals`; `hashCode`;
  and actual write bytes. Raw binary data, exceptions, class names, and values are not exposed
  through the Protocol.
- Custom inspection has fixed process-wide limits of two workers, a 64-task queue, and a
  five-second deadline beginning when execution starts. A queued task that has not started and
  times out or is rejected is removed immediately as a transient failure. A timeout or
  cancellation after start, or an actual-write mismatch, quarantines that Store for the current
  process generation. An unknown outcome after mutation starts is not replayed.
- Auto-discovered SharedPreferences are opened lazily through the platform API. Catalog listing
  does not parse values or open a SharedPreferences instance.
- Before opening a SharedPreferences instance and immediately before mutation, Runtime validates
  the framework-authoritative XML (`.bak` before main) as a bounded, well-formed standard
  six-type SharedPreferences structure. DTDs, entities, unknown tags, duplicate keys, and
  malformed values fail closed. Parsed values are discarded because the framework API remains
  authoritative.
- SharedPreferences writes use one global, non-queued `commit()` lane. The single commit runs on
  an independent daemon worker. If the bounded response wait expires, Runtime reports an unknown
  outcome while the worker retains the lane until it actually returns. A false return, exception,
  or wait expiry is never replayed.
- Snapshot, preflight, and post-commit reads use a separate non-queued stage lane and daemon
  worker. Runtime stops waiting at the stage deadline even when framework or filesystem code
  ignores interruption or the application's shared IO pool is saturated. The worker retains the
  lane until it actually returns, preventing blocked-worker accumulation.
- Backing paths, regular-file status, symbolic links, and a 16 MiB raw-file limit are checked
  before open and mutation. Logical snapshots have additional fixed limits for entries, keys,
  strings, Sets, aggregate size, and wire size.
- Either known AndroidX encrypted-preferences keyset marker makes the whole Store unsupported
  before snapshot or mutation. Custom encryption wrappers cannot be identified generically and
  require client-side confirmation.

## Release isolation

`checkSdk` verifies that release variants do not contain Runtime artifacts, Provider declarations,
schema assets, Custom codec/binding/service resources, or instrumentation references.

Report security issues through the private process documented in [`SECURITY.md`](../../SECURITY.md).
