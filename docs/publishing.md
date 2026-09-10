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

## branch運用

- [Must] release cycleの開始時に、最新のpublic `main`から`release/<version>` branchを作成し、最初のpush前に`gradle/artifact-coordinates.properties`の`version`をbranch名の`<version>`と一致する未公開・非SNAPSHOTのSemVerへ更新して、そのbranchの初期コミットに含める。個別のfeature／fix branchはこのコミットから派生させ、開発・検証を最初から公開予定のversionで揃える。
- [Never] branch作成時のversion更新だけを別の個別PRに分けない。これはrelease branchの初期化として直接コミットする例外であり、通常のfeature／fixは引き続きPRでreviewする。
- [Must] 初期化したversionで[ローカル検証](#ローカル検証)を実行し、初期コミットも最終release PRの確認対象に含める。個別PRの省略によってversion変更の検証・確認を省かない。
- そのversionへ含めるfeature／fix PRは`release/<version>`をbaseにし、CIとreviewを完了してからmergeする。
  個別PRはsquash mergeしてよい。
- [Must] 初期コミットを`release/<version>`へpushし、remote headとlocal HEADの一致を確認したら、public `main`向けのDraft PRを`Release <version>`というtitleで1件だけ作成する。たとえば`release/1.2.1`は`version=1.2.1`とし、titleを`Release 1.2.1`にする。初期化から集約PR作成までを一続きの作業にし、個別PRのmergeに合わせて「含まれる変更」を更新する。公開準備が整った時点でReadyへ変更する。
- 最終release PRはGitHubの`Create a merge commit`でmergeする。`Squash and merge`または`Rebase and merge`を
  使用するとrelease branch内のPR commitをmainの祖先として保持できず、自動生成Release Notesから実際の
  変更PRが欠落し得るため使用しない。
- 通常のfeature／fixをpublic `main`へ直接mergeせず、公開versionへ含める変更をrelease branchへ集約する。
  bootstrap releaseなどの例外は、後述の既存releaseがない手順に限定する。

## release手順

1. [branch運用](#branch運用)に従って初期化・検証済みのrelease branchを使う。
2. 個別PRを`release/<version>`へmergeし、public `main`向けの最終release PRでCI、公開metadata、「含まれる変更」を確認する。非SNAPSHOTのrelease候補では、必要に応じて有効なPlugin Portal credentialを環境変数へ設定して`./gradle-plugin/gradlew -p gradle-plugin publishPlugins --validate-only --console=plain`も実行する。
3. [Must] 最終release PRのmergeをMaven Central／Gradle Plugin Portalへの公開承認として扱い、公開準備が整ってからGitHubの`Create a merge commit`でpublic `main`へmergeする。`Release SDK` workflowが`v<version>`のannotated tagとGitHub Releaseを作成し、同じrun内で`Publish SDK`を呼び出してrelease gateと両公開先へのpublishを自動実行する。通常releaseの手動dispatchは不要とし、二重公開を避ける。
4. GitHub Releaseの「What's Changed」に、前回version以降の個別feature／fix PRが列挙され、最終release PRだけに畳まれていないことを確認する。
5. `Release SDK`の公開jobの成功と、Maven Central／Gradle Plugin Portalの両方で同じversionが公開されたことを確認する。GitHub Releaseの作成だけではSDK公開完了としない。

自動公開は、同じrepositoryの`release/<SemVer>` branchからpublic `main`へmergeされたPRだけを対象にする。branch名と正本のversion、非SNAPSHOT、tagとmerge commitの一致を検証し、既存tagが別commitを指す場合は上書きせず失敗する。公開jobは準備段階で確定したcommit SHAをcheckoutし、tagとの一致を再検証してから既存のrelease gateを実行する。`sdk-publication` environmentのsecretと保護設定は自動実行にも適用する。

`Release SDK`から`Publish SDK`をreusable workflowとして直接呼び出す。`GITHUB_TOKEN`で作ったtag／GitHub Releaseのeventに別workflowの起動を依存させない。tag作成はPR eventのbranch情報とmerge commitを使い、merge commit messageは解析しない。

初回導入時など、version更新が既に`main`へmerge済みでrelease PRが存在しない場合は、GitHub Actionsの`Publish SDK`をpublic `main`から手動実行し、`version`とtarget `all`を指定できる。指定tagがなければ、workflowを実行した`main` commitへannotated tagとGitHub Releaseを作成する。既存tagがあればそのimmutable commitを再利用するため、`main`が先へ進んだ後の部分再試行でも公開sourceは変わらない。Maven Centralはvalidation完了まで待って自動releaseし、その後Gradle Plugin Portalへpublishする。

片方だけが失敗した場合は、両公開先の状態を確認し、成功済みtargetへ同じversionを再publishしない。自動run全体を再実行せず、`Publish SDK`をpublic `main`から同じ`version`、target `maven-central`または`plugin-portal`で手動実行して未公開側だけを再試行する。公開済みartifactは不変なので、内容を直す場合は新しいversionを使う。
