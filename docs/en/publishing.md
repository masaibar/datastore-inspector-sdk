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
the release tag.

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

## Release procedure

1. Change `version` in `gradle/artifact-coordinates.properties` to an unpublished value that does
   not end in `-SNAPSHOT`.
2. Review CI and publication metadata in a pull request. For a non-SNAPSHOT release candidate,
   optionally provide valid Plugin Portal credentials through environment variables and run
   `./gradle-plugin/gradlew -p gradle-plugin publishPlugins --validate-only --console=plain`.
3. Merge the change into the public `main` branch.
4. Create and push an annotated `v<version>` tag for the merge commit. Use an approved public
   identity for tagger metadata, and never move that version tag to another commit.
5. Run `Publish SDK` from the public `main`, using the same `version` and target `all`. The workflow
   checks out the `v<version>` tag as the source to publish.
6. Confirm that the same version is available from Maven Central and the Gradle Plugin Portal.

The workflow can start only from public `main`. Before uploading, it verifies that the input matches
the source-of-truth version, the version is not a SNAPSHOT, and the checked-out commit matches the
`v<version>` tag. A workflow fix can therefore come from `main` while the published source stays
fixed to the immutable tag. Maven Central is validated and released first, followed by the Gradle
Plugin Portal.

If only one target fails, do not republish the same version to the successful target. Rerun the
workflow with the same tag input and target `maven-central` or `plugin-portal` for only the failed side.
Published artifacts are immutable; use a new version if their contents must change.
