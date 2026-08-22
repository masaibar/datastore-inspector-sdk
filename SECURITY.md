# Security Policy

English | [日本語](SECURITY.ja.md)

## Supported versions

Until a longer-term support policy is announced, only the latest published release receives
security fixes.

| Version | Supported |
|---|---|
| Latest published release | Yes |
| Older releases, snapshots, and unpublished builds | No |

## Reporting a vulnerability

Use GitHub's private vulnerability reporting for this repository:

- [Report a vulnerability privately](https://github.com/masaibar/datastore-inspector-sdk/security/advisories/new)

Do not report a suspected vulnerability in a public Issue, Discussion, pull request, or commit.
Allow the repository owner time to investigate and coordinate disclosure before publishing
details.

Include only the information needed to reproduce and assess the issue:

- affected artifact and version
- affected Android and build-tool versions
- security impact and required preconditions
- minimal reproduction steps using dummy data
- a suggested mitigation, if available

Do not include real DataStore or SharedPreferences keys or values, raw app data, session tokens,
device serials, credentials, private repository details, private endpoints, or absolute paths from
a personal environment. Redact logs and screenshots before attaching them.

The repository owner will triage the report and continue coordination through the private advisory.

For the SDK's trust boundaries and release-isolation guarantees, see
[`docs/en/security.md`](docs/en/security.md).
