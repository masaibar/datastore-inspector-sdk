# 公開

[English](en/publishing.md) | 日本語

SDKの5つのartifactはMaven Central、Gradle PluginはGradle Plugin Portalへ公開する。
versionとartifact名の正本は
[`gradle/artifact-coordinates.properties`](../gradle/artifact-coordinates.properties)であり、
workflow inputや個別moduleで上書きしない。

## 公開repositoryの初期化

初回public化では、現在のprivate repositoryのGit履歴を公開しない。監査済みのtracked `HEAD`だけを
source candidateにし、新しいrepositoryの初期commitへ投入する。以前の`.git` directory、build output、
IDE設定、`local.properties`、credential、signing materialはコピーしない。

1. private repositoryの`main`で`Prepare Public Source Candidate` workflowを実行する。ローカルでは
   `./scripts/prepare-public-source.sh`で同じarchiveを生成できる。
2. `datastore-inspector-sdk-public-source.tar.gz.sha256`でarchiveを検証する。
3. archiveを一時directoryへ展開し、`.git`、build output、local fileが含まれないことを再確認する。
4. source candidateとchecksumを安全に保持してから、既存repositoryを退避名へ変更または削除し、
   Public repositoryを作成する。
5. 展開したtreeで新しいGit履歴を開始する。初期commitのauthor／committer emailには、公開を許可した
   identityまたはGitHub noreply addressを使う。
6. repository URLを`https://github.com/masaibar/datastore-inspector-sdk`にする。
7. GitHub Actionsを有効にし、public `main`上でCIが成功してから最初のrelease versionへ進む。

source candidate workflowはGitleaksで現在のtracked treeと到達可能な履歴を検査する。一方、archiveは
`git archive HEAD`だけから作られるため、private repositoryのcommit metadataや過去のtreeを含まない。

Public repositoryでは次も設定する。

- `main`の必須checkとbranch protection
- GitHub private vulnerability reportingと、`SECURITY.md`の非公開報告link
- `sdk-publication` environmentと、必要に応じたrequired reviewer

repository名またはownerを変更する場合は、POMのproject／SCM URLとGradle Plugin Portalの
website／VCS URLを先に更新する。

## 外部serviceの準備

### Maven Central

- Central Portalで`com.masaibar` namespaceの所有権を確認する。
- Portal user tokenを発行する。
- release署名用のOpenPGP keyを準備し、public keyをkey serverへ公開する。

### Gradle Plugin Portal

- Plugin Portal accountを作成する。
- publish keyとsecretを発行する。
- Plugin ID `com.masaibar.datastore-inspector`を公開できることを確認する。

GitHubの`sdk-publication` environmentには次のsecretを登録する。

| Secret | 用途 |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Central Portal user tokenのusername |
| `MAVEN_CENTRAL_PASSWORD` | Central Portal user tokenのpassword |
| `SIGNING_IN_MEMORY_KEY` | ASCII-armored形式のOpenPGP private key |
| `SIGNING_IN_MEMORY_KEY_PASSWORD` | OpenPGP private keyのpassword |
| `GRADLE_PUBLISH_KEY` | Gradle Plugin Portalのpublish key |
| `GRADLE_PUBLISH_SECRET` | Gradle Plugin Portalのpublish secret |

secretの値はrepository file、Gradle property file、command line、logへ書かない。

署名用secretを登録または更新したら、GitHub Actionsの`Validate Publication Signing`をpublic
`main`から実行する。このworkflowは5つのMaven publicationについて署名ファイルを生成するが、
Maven Centralへのuploadやreleaseは行わない。成功を確認してからrelease PRをmergeするか、
`Publish SDK`を実行する。

## ローカル検証

JDK 21とAndroid SDK 36で実行する。

```shell
./scripts/verify-public-source.sh
./gradlew checkSdk --console=plain
./gradle-plugin/gradlew -p gradle-plugin clean checkPlugin --console=plain
```

`checkSdk`内の`checkPublications`は次を検証する。

- `protocol`のJARと4 RuntimeのAARを`build/publication-repository`へpublishする。
- POM、Gradle Module Metadata、sources JAR、javadoc JARが揃っていることを確認する。
- Gradle Plugin本体とplugin markerを同じlocal repositoryへpublishする。
- Gradle Plugin／Runtimeのclass fileがJava 17、ProtocolがJava 11であり、Gradle Module Metadataが
  それより新しいJVMを要求しないことを確認する。
- 独立したAndroid consumerがPortalとMaven Centralの代わりにlocal repositoryだけを使い、
  Pluginを適用して公開対象5 artifactを解決する。

CIとrelease workflowはこの後JDK 17へ切り替え、同じlocal publicationだけを使う独立consumerの
debug／release APKをassembleする。Plugin適用と全artifactの解決に加え、release runtime classpathへ
Inspectorが混入しないことも実JDK 17上で検証する。publish処理の前にはJDK 21へ戻す。

ローカルrepositoryはcredentialを使わないため署名を検証しない。実際のMaven Central taskは
Vanniktech pluginが必須checksumを生成し、release workflowが全publicationへのOpenPGP署名を有効にする。
署名済みuploadの最終validationはCentral Portalが行う。

公開用source candidateも確認する場合は次を実行する。

```shell
./scripts/prepare-public-source.sh
shasum -a 256 -c \
  build/public-source/datastore-inspector-sdk-public-source.tar.gz.sha256
```

## release手順

1. `release/<version>` branchで`gradle/artifact-coordinates.properties`の`version`を、未公開かつ
   `-SNAPSHOT`でない値へ更新する。
2. public `main`向けのpull requestでCIと公開metadataを確認する。非SNAPSHOTのrelease候補では、必要に応じて
   有効なPlugin Portal credentialを環境変数へ設定してから
   `./gradle-plugin/gradlew -p gradle-plugin publishPlugins --validate-only --console=plain`も実行する。
3. pull requestをpublic `main`へmergeする。`Create SDK Release` workflowがPR eventのbranch情報と
   merge commitを検証し、`v<version>`のannotated tagとGitHub Releaseを自動作成する。
4. GitHub Actionsの`Publish SDK`をpublic `main`から手動実行し、正本と同じ`version`、target `all`を
   指定する。workflowは公開対象を`v<version>` tagへcheckoutしてからrelease gateとpublishを行う。
5. Maven CentralとGradle Plugin Portalで同じversionの公開を確認する。

自動releaseは、同じrepositoryの`release/<SemVer>` branchからpublic `main`へmergeされたPRだけを対象にする。
merge commit messageを解析しないため、GitHub上のmerge方式には依存しない。入力versionと正本、非SNAPSHOT、
tagとcommitの一致を検証し、既存tagが別commitを指す場合は上書きせず失敗する。

初回導入時など、version更新が既に`main`へmerge済みでrelease PRが存在しない場合は、`Publish SDK`を直接実行
できる。指定tagがなければ、workflowを実行した`main` commitへannotated tagとGitHub Releaseを自動作成する。
既存tagがあればそのimmutable commitを再利用するため、`main`が先へ進んだ後の部分再試行でも公開sourceは
変わらない。Maven Centralはvalidation完了まで待って自動releaseし、その後Gradle Plugin Portalへpublishする。

片方だけが失敗した場合は、成功済みtargetへ同じversionを再publishしない。workflowのtargetを
`maven-central`または`plugin-portal`へ絞り、同じtagを入力して失敗側だけを再試行する。公開済みartifactは
不変なので、内容を直す場合は新しいversionを使う。
