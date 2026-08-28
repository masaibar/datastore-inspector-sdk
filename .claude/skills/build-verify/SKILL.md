---
name: build-verify
description: DataStore Inspector SDKのJDK 21 release gate、独立Gradle Plugin gate、JDK 17 consumer gateを固定手順で実行する。「SDKを検証」「release gate」「build verify」などの文脈で使用する。
allowed-tools:
  - Bash
user-invocable: true
---

# Build Verify

SDKの公開前検証を、CIと同じ責務分割で実行する。

## 実行契約

- repository rootから3つのStepを記載順に実行し、各終了codeを個別に保持する。
- `--tests`などのtest filterを追加しない。Protocol fixtureはフィルタなしの`checkSdk`内で生成・検証する契約である。
- JDK homeは`DATASTORE_INSPECTOR_JDK_21_HOME`と`DATASTORE_INSPECTOR_JDK_17_HOME`から受け取る。未設定時にmachine固有pathへfallbackしない。
- 各Stepは指定されたJDKの`java.specification.version`を確認し、JDK 21はsource build、JDK 17は公開artifact consumer gateだけに使用する。
- 長時間の出力を`tail`や`head`へ渡さず、実行中の全出力を表示する。
- 失敗時も独立したStepは実行して全errorを確認する。後続Stepの成功で先行Stepの失敗を上書きしない。
- cache、build成果物、Gradle daemonを削除・停止せず、別taskへのfallbackで成功扱いしない。

## Step 1: SDK release gate

```shell
# Step sdk-release
jdk21_home="${DATASTORE_INSPECTOR_JDK_21_HOME:?DATASTORE_INSPECTOR_JDK_21_HOMEをJDK 21のhomeへ設定してください。}" && \
jdk21_version="$("$jdk21_home/bin/java" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java\.specification\.version = //p')" && \
printf 'SDK release gate JDK version: %s\n' "$jdk21_version" && \
test "$jdk21_version" = "21" && \
env JAVA_HOME="$jdk21_home" ./gradlew checkSdk --console=plain
```

## Step 2: Gradle Plugin release gate

```shell
# Step gradle-plugin-release
jdk21_home="${DATASTORE_INSPECTOR_JDK_21_HOME:?DATASTORE_INSPECTOR_JDK_21_HOMEをJDK 21のhomeへ設定してください。}" && \
jdk21_version="$("$jdk21_home/bin/java" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java\.specification\.version = //p')" && \
printf 'Gradle Plugin release gate JDK version: %s\n' "$jdk21_version" && \
test "$jdk21_version" = "21" && \
env JAVA_HOME="$jdk21_home" ./gradle-plugin/gradlew -p gradle-plugin clean checkPlugin --console=plain
```

## Step 3: JDK 17 consumer gate

```shell
# Step jdk17-consumer
jdk17_home="${DATASTORE_INSPECTOR_JDK_17_HOME:?DATASTORE_INSPECTOR_JDK_17_HOMEをJDK 17のhomeへ設定してください。}" && \
jdk17_version="$("$jdk17_home/bin/java" -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java\.specification\.version = //p')" && \
printf 'JDK 17 consumer gate JDK version: %s\n' "$jdk17_version" && \
test "$jdk17_version" = "17" && \
publication_group="$(sed -n 's/^group=//p' gradle/artifact-coordinates.properties)" && \
publication_version="$(sed -n 's/^version=//p' gradle/artifact-coordinates.properties)" && \
env JAVA_HOME="$jdk17_home" ./gradlew -p gradle/publication-consumer clean verifyJdk17Consumer \
  -PpublicationRepository="$PWD/build/publication-repository" \
  -PpublicationGroup="$publication_group" \
  -PpublicationVersion="$publication_version" \
  --no-configuration-cache \
  --console=plain
```

## 判定

3つのStepがすべて終了code 0の場合だけ成功とする。失敗時は全出力から、失敗task、test、file、line、root cause、所有moduleを特定して報告する。このSkill内ではsourceを修正しない。
