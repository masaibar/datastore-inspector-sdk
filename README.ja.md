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
導入手順と対応範囲を確認し、このAndroid projectへDataStore Inspector SDKを導入してください。
https://github.com/masaibar/datastore-inspector-sdk

- 最新stable releaseがGradle Plugin PortalとMaven Centralの両方で公開済みであることを確認し、そのversionに固定してください。
- 既存のGradle規約・build設定を維持し、対象Android application moduleへPluginを適用してください。Runtime依存は手動追加しないでください。
- Proto mappingは対応範囲に照らして必要な場合だけ追加してください。対象module・version・mappingが不明なら確認してください。
- debug buildで検証し、変更内容・結果・手動対応が残るストアを報告してください。
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
