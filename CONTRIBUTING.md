# Contributing

## Pull request targets

- Target `main` for regular changes.
- Target an active `release/<version>` branch only for changes that must ship in that release. Do not open a duplicate PR to `main`; the release branch will carry the reviewed changes forward.
- Merge `release/<version>` into `main` through a pull request to publish the release. Add `[skip review]` to that PR title only when every included change was already reviewed through pull requests targeting the release branch. If the release branch contains any unreviewed change, omit the keyword so CodeRabbit reviews the complete release PR.

CodeRabbit automatically reviews pull requests targeting `main` and `release/*`. See [the publishing guide](docs/en/publishing.md) for release validation and publication steps.
