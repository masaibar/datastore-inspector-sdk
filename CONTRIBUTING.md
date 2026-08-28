# Contributing

## Pull request targets

- Target `main` for regular changes.
- Target an active `release/<version>` branch only for changes that must ship in that release. Do not open a duplicate PR to `main`; the release branch will carry the reviewed changes forward.
- Merge `release/<version>` into `main` through a pull request to publish the release.

CodeRabbit is configured to automatically review eligible pull requests targeting `main` and `release/*`; drafts and titles containing `WIP` or `DO NOT MERGE` are excluded. This includes release synchronization pull requests. See [the publishing guide](docs/en/publishing.md) for release validation and publication steps.
