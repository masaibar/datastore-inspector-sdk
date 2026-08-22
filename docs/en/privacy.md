# Privacy

English | [日本語](../privacy.md)

The SDK sends no telemetry and does not connect to the internet.

DataStore and SharedPreferences keys, fields, values, descriptors, and connection metadata remain
between the local Android device, ADB, and the local inspection client. Runtime must not log
values, logical Store names, backing paths, raw XML, session tokens, or raw connection metadata.

Protocol 1.4 Store-change notifications carry the canonical state required by the local client to
compute before/after changes. Runtime retains only bounded in-memory observation and encoded-frame
queues for the authenticated connection; it does not persist timeline data. Encoded frames are
overwritten with zeroes after write, drop, or connection close, and all observers and listeners
are disposed with the connection. Oversize and gap boundaries contain Store identity metadata but
no keys or values.

The stable logical Store ID is an opaque identifier derived from SHA-256. Its input may distinguish
process, declaration, backend, scope, and logical name, but the wire value never contains the
original name or backing path. Write correlation IDs are client-generated opaque identifiers and
must not encode keys or values.

Custom projection capture retains at most two identities consisting only of the root Serializer
and `SerializersModule`; it retains no document or value. Decode candidates are also discarded as
soon as they exceed the fixed bound, failing closed. Unsupported reasons and Protocol errors use
only fixed codes and contain no Serializer or value class name, exception message, document, raw
persistence bytes, or absolute path.

The SDK does not persist client-side snapshots, presets, audit history, or licensing state. Client
implementations own those concerns and require their own privacy review before release.
