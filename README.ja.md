<p align="center">
  <img src="docs/assets/datastore-inspector-icon.svg" alt="DataStore Inspector" width="144" height="144" />
</p>

<h1 align="center">DataStore Inspector SDK</h1>

<p align="center"><a href="README.md">English</a> | 日本語</p>

**実行中のAndroidアプリのSharedPreferencesとDataStoreを、Android Studioから確認・編集します。** 状態確認のための一時的なログ、デバッグ画面、再ビルドを減らします。

DataStore Inspectorは、今はなき[Stetho](https://facebook.github.io/stetho/)や[Flipper](https://github.com/facebook/flipper)が提供してくれていた、実行中のアプリの値を確認・更新できるという素晴らしい開発者体験を、SharedPreferencesとJetpack DataStore向けに取り戻したいという思いから生まれました。

- 対応するPreferences DataStore、Proto DataStore、永続化済みSharedPreferencesを自動検出（[対応範囲](docs/compatibility.md)）
- 対応するPreferences、Proto、SharedPreferencesの値を一覧・検索・編集
- 対応するStoreの変更を追跡し、アプリが使うinstanceを公式API経由で更新

SDKは対応するdebuggable variantだけへ追加され、release variantは変更しません。Storeのkey、value、schemaを外部サーバーへ送信せず、internet通信、telemetry、Android network permissionも追加しません。端末との通信はADB forwardを通した認証済みローカル接続に限定します。

## 導入

### AI coding agentを使う

Android projectを開き、次のpromptをcoding agentへ渡します。

```text
公式ドキュメントに従い、このAndroid projectへDataStore Inspector SDKの最新stable releaseを導入してください。

公式情報:
- Repositoryと導入手順: https://github.com/masaibar/datastore-inspector-sdk
- 最新stable GitHub Release API: https://api.github.com/repos/masaibar/datastore-inspector-sdk/releases/latest
- Gradle Plugin Portal: https://plugins.gradle.org/plugin/com.masaibar.datastore-inspector
- Maven Central protocol metadata: https://repo1.maven.org/maven2/com/masaibar/datastore%2Dinspector/protocol/maven-metadata.xml
- Maven Central runtime-core metadata: https://repo1.maven.org/maven2/com/masaibar/datastore%2Dinspector/runtime-core/maven-metadata.xml
- Maven Central runtime-shared-preferences metadata: https://repo1.maven.org/maven2/com/masaibar/datastore%2Dinspector/runtime-shared-preferences/maven-metadata.xml
- Maven Central runtime-preferences metadata: https://repo1.maven.org/maven2/com/masaibar/datastore%2Dinspector/runtime-preferences/maven-metadata.xml
- Maven Central runtime-protobuf metadata: https://repo1.maven.org/maven2/com/masaibar/datastore%2Dinspector/runtime-protobuf/maven-metadata.xml
- 対応範囲と対応構成: https://github.com/masaibar/datastore-inspector-sdk/blob/main/docs/compatibility.md

要件:
- ファイルを変更する前に導入手順と対応範囲を読んでください。
- 最新stable GitHub Releaseの`tag_name`を取得し、先頭の`v`だけを除いたexact versionがGradle Plugin Portalと上記5つのMaven Central metadata URLのすべてで公開済みであることを確認して固定してください。情報が一致しない場合や確認できない場合は、推測したり別versionを選んだりせず私へ質問してください。
- 既存のGradle構成を調べ、Version Catalogやconvention pluginを含む既存の規約を維持してください。`com.masaibar.datastore-inspector`は対象のAndroid application moduleだけへ適用し、dependency repositoryに`mavenCentral()`がなければ既存宣言と重複しない形で追加してください。application moduleが複数あり対象が不明なら、ファイルを変更する前に私へ質問してください。
- Runtime artifactを手動追加せず、Gradle／AGP／Kotlin／Android SDKのversion、`org.gradle.configureondemand`、configuration cache設定、無関係なファイルを変更しないでください。
- Proto schema mappingを既定では追加しないでください。Proto schemaがドキュメント記載の自動mapping範囲外なら、generated classと完全修飾Proto message名を推測せず私へ確認してください。
- Android application moduleに対する最小のdebug buildで検証してください。失敗した場合は、既存のbuild設定やrelease分離を弱めずに原因を診断して報告してください。
- 選択したexact version、変更ファイル、検証commandと結果、残っている手動作業を報告してください。
```

### 手動で設定する

Pluginはrepositoryを追加しないため、consumer projectのdependency repositoryに`mavenCentral()`が必要です。

`<latest-stable-version>`は、Gradle Plugin PortalとMaven Centralの両方で公開済みの最新exact versionへ置き換えてください。

Version Catalogを使う場合:

```toml
[versions]
datastore-inspector = "<latest-stable-version>"

[plugins]
datastore-inspector = { id = "com.masaibar.datastore-inspector", version.ref = "datastore-inspector" }
```

```kotlin
plugins {
  id("com.android.application")
  alias(libs.plugins.datastore.inspector)
}
```

Version Catalogを使わない場合:

```kotlin
plugins {
  id("com.android.application")
  id("com.masaibar.datastore-inspector") version "<latest-stable-version>"
}
```

PluginはAndroid application moduleだけへ適用してください。必要なRuntimeは対応するdebuggable variantへ自動追加されます。

### Proto DataStore

対応するProto2／Proto3 Java Lite DataStoreはschema登録不要で、Pluginを適用するだけです。

debuggable variantをbuild・起動し、Android StudioのDataStore Inspectorからapplicationを選びます。実行例は[`sample-app`](sample-app)を参照してください。

対応構成、制限、`schemaEntry`による明示mappingは[対応範囲](docs/compatibility.md)を参照してください。

## 詳細

- [対応範囲と既知制限](docs/compatibility.md)
- [APIの安定性とversioning](docs/api-stability.md)
- [Gradle Pluginが変更する内容](docs/what-is-injected.md)
- [Custom DataStoreの検査](docs/custom-datastore.md)
- [セキュリティ](docs/security.md)／[プライバシー](docs/privacy.md)

## ライセンス

[Apache License 2.0](LICENSE)。脆弱性は[`SECURITY.ja.md`](SECURITY.ja.md)の手順で非公開報告してください。

Android is a trademark of Google LLC.

The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
