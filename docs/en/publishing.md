# Publication

English | [日本語](../publishing.md)

The five SDK artifacts are published to Maven Central, and the Gradle Plugin is published to the
Gradle Plugin Portal. [`gradle/artifact-coordinates.properties`](../../gradle/artifact-coordinates.properties)
is the source of truth for the version and artifact names. Do not override them in workflow inputs
or individual modules.

## Initializing the public repository

Do not publish the Git history of the current private repository. The first public commit must be
created from an audited source candidate containing only the tracked `HEAD`. Do not copy the old
`.git` directory, build outputs, IDE settings, `local.properties`, credentials, or signing material.

1. Run the `Prepare Public Source Candidate` workflow on the private repository's `main` branch.
   `./scripts/prepare-public-source.sh` creates the same archive locally.
2. Verify the archive with `datastore-inspector-sdk-public-source.tar.gz.sha256`.
3. Extract the archive into a temporary directory and confirm again that it contains no `.git`
   directory, build output, or local file.
4. Keep the source candidate and checksum safely before renaming or deleting the existing
   repository and creating the public repository.
5. Start a new Git history from the extracted tree. Use an approved public identity or a GitHub
   noreply address for the initial commit's author and committer email.
6. Set the repository URL to `https://github.com/masaibar/datastore-inspector-sdk`.
7. Enable GitHub Actions and require CI to pass on the public `main` before preparing the first
   release version.

The source-candidate workflow uses Gitleaks to inspect the current tracked tree and reachable
history. The archive itself is created only from `git archive HEAD`, so it contains neither commit
metadata nor any earlier private tree.

Configure the following for the public repository as well:

- required checks and branch protection for `main`
- GitHub private vulnerability reporting and a working private-report link in `SECURITY.md`
- an `sdk-publication` environment, with a required reviewer if appropriate

If the repository owner or name changes, update the project and SCM URLs in the POM and the website
and VCS URLs for the Gradle Plugin Portal before publication.

## Preparing external services

### Maven Central

- Verify ownership of the `com.masaibar` namespace in Central Portal.
- Create a Portal user token.
- Create an OpenPGP release-signing key and publish its public key to a key server.

### Gradle Plugin Portal

- Create a Plugin Portal account.
- Create a publish key and secret.
- Confirm that Plugin ID `com.masaibar.datastore-inspector` can be published.

Add these secrets to the GitHub `sdk-publication` environment:

| Secret | Purpose |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user-token username |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user-token password |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored OpenPGP private key |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | OpenPGP private-key password |
| `GRADLE_PUBLISH_KEY` | Gradle Plugin Portal publish key |
| `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portal publish secret |

Never put secret values in repository files, Gradle property files, command lines, or logs.

After adding or rotating the signing secrets, run `Validate Publication Signing` from the public
`main` branch in GitHub Actions. The workflow generates signatures for all five Maven publications
without uploading or releasing anything to Maven Central. Confirm that it succeeds before creating
a release by merging a release pull request or running `Publish SDK`.

## Local verification

Use JDK 21 and Android SDK 36.

```shell
./scripts/verify-public-source.sh
./gradlew checkSdk --console=plain
./gradle-plugin/gradlew -p gradle-plugin clean checkPlugin --console=plain
```

`checkPublications`, which is part of `checkSdk`, verifies the following:

- The Protocol JAR and four Runtime AARs are published to `build/publication-repository`.
- Each publication includes a POM, Gradle Module Metadata, sources JAR, and javadoc JAR.
- The Gradle Plugin implementation and plugin marker are published to the same local repository.
- Gradle Plugin and Runtime class files target Java 17, Protocol targets Java 11, and Gradle Module
  Metadata does not require a newer JVM than the corresponding artifact.
- An independent Android consumer uses only the local repository in place of the Portal and Maven
  Central, applies the Plugin, and resolves all five public SDK artifacts.

CI and the release workflow then switch to JDK 17 and assemble debug and release APKs for an
independent consumer using only those local publications. This proves Plugin application, all
artifact resolution, and release runtime-classpath isolation on an actual JDK 17. The release
workflow switches back to JDK 21 before publishing.

The local repository does not use credentials, so it does not prove signing. For a real Maven
Central upload, the Vanniktech plugin generates the required checksums and the release workflow
enables OpenPGP signing for every publication. Central Portal performs the final validation of the
signed upload.

To inspect the public source candidate too, run:

```shell
./scripts/prepare-public-source.sh
shasum -a 256 -c \
  build/public-source/datastore-inspector-sdk-public-source.tar.gz.sha256
```

## Branch workflow

- [Must] At the start of a release cycle, create `release/<version>` from the latest public `main`. Before its first push, set `version` in `gradle/artifact-coordinates.properties` to the unpublished, non-SNAPSHOT SemVer matching `<version>` in the branch name and include this update in the branch's initialization commit. Branch individual features and fixes from that commit so development and validation use the intended release version from the start.
- [Never] Open a separate pull request solely for this initial version update. Directly committing it is an exception for release branch initialization; ordinary features and fixes still require pull request review.
- [Must] Run [local verification](#local-verification) with the initialized version and include the initialization commit in the final release pull request's review scope. Omitting a separate pull request does not waive version validation or review.
- Target every feature or fix pull request for that version at `release/<version>`, and merge it only
  after CI and review succeed. Individual pull requests may use squash merge.
- [Must] Push the initialization commit to `release/<version>`, verify that the remote head matches local HEAD, then create exactly one Draft pull request targeting public `main` with the title `Release <version>`. For example, `release/1.2.1` uses `version=1.2.1` and the title `Release 1.2.1`. Treat initialization and creation of the release pull request as one operation, and update its included-change list as individual pull requests merge. Mark it ready for review when release preparation is complete.
- Merge the final release pull request with GitHub's `Create a merge commit`. Do not use `Squash and
  merge` or `Rebase and merge`: they do not preserve the release branch pull-request commits as
  ancestors of `main`, so generated release notes can omit the actual change pull requests.
- Do not merge ordinary features or fixes directly into public `main`. Aggregate changes for a
  published version on its release branch. Limit exceptions to the bootstrap procedure below when no
  release pull request exists.

## Release procedure

1. Use a release branch initialized and validated according to the [branch workflow](#branch-workflow).
2. Merge individual pull requests into `release/<version>`, then inspect CI, publication metadata, and the included-change list in the final pull request to public `main`. For a non-SNAPSHOT release candidate, optionally provide valid Plugin Portal credentials through environment variables and run `./gradle-plugin/gradlew -p gradle-plugin publishPlugins --validate-only --console=plain`.
3. [Must] Treat merging the final release pull request as approval to publish to Maven Central and the Gradle Plugin Portal. Once publication is ready, merge it into public `main` with GitHub's `Create a merge commit`. The `Release SDK` workflow creates an annotated `v<version>` tag and a GitHub Release, then calls `Publish SDK` within the same run to execute the release gates and publish to both destinations automatically. Do not manually dispatch a normal release, to avoid duplicate publication.
4. Confirm that the GitHub Release's What's Changed section lists the individual feature and fix pull requests since the previous version instead of collapsing them into the final release pull request.
5. Confirm that the publication job in `Release SDK` succeeds and that the same version is available from both Maven Central and the Gradle Plugin Portal. Creating the GitHub Release alone does not complete SDK publication.

Automatic publication applies only to merged pull requests from a `release/<SemVer>` branch in the same repository to public `main`. The workflow validates the branch version against the source of truth, rejects SNAPSHOT versions, checks that the tag matches the merge commit, and refuses to move an existing tag to another commit. The publication job checks out the commit SHA fixed during preparation and verifies it against the tag before running the existing release gates. The secrets and protection rules of the `sdk-publication` environment also apply to automatic runs.

`Release SDK` calls `Publish SDK` directly as a reusable workflow. It does not rely on events from a tag or GitHub Release created with `GITHUB_TOKEN` to start another workflow. Tag creation uses the branch and merge commit from pull request event metadata rather than parsing the merge commit message.

For a bootstrap release where the version has already reached `main` without a release pull request, manually run `Publish SDK` from public `main` with the requested `version` and target `all`. If the requested tag is absent, the workflow creates an annotated tag and a GitHub Release for the selected `main` commit. If the tag already exists, the workflow reuses that immutable commit, so a partial retry continues to publish the same source even after `main` advances. Maven Central is validated and released first, followed by the Gradle Plugin Portal.

If only one target fails, check both destinations and do not republish the same version to the successful target. Instead of rerunning the entire automatic run, manually dispatch `Publish SDK` from public `main` with the same `version` and target `maven-central` or `plugin-portal` for only the unpublished side. Published artifacts are immutable; use a new version if their contents must change.
