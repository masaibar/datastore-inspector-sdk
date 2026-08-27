<p align="center">
  <img src="docs/assets/datastore-inspector-icon.svg" alt="DataStore Inspector" width="144" height="144" />
</p>

<h1 align="center">DataStore Inspector SDK</h1>

<p align="center"><a href="README.md">English</a> | 日本語</p>

**実行中のAndroidアプリのSharedPreferencesとDataStoreを、Android Studioから確認・編集します。** 状態確認のための一時的なログ、デバッグ画面、再ビルドを減らします。

[Stetho](https://facebook.github.io/stetho/)や[Flipper](https://github.com/facebook/flipper)が提供した「実行中のアプリを覗く」体験を、SharedPreferencesとJetpack DataStore向けに引き継ぎます。

- Preferences DataStoreと永続化済みSharedPreferencesを自動検出
- Preferences、SharedPreferences、登録したProto DataStoreを一覧・検索・編集
- Storeの変更を追跡し、アプリが使うinstanceを公式API経由で更新

SDKは対応するdebuggable variantだけへ追加され、release variantは変更しません。Storeのkey、value、schemaを外部サーバーへ送信せず、internet通信、telemetry、Android network permissionも追加しません。端末との通信はADB forwardを通した認証済みローカル接続に限定します。

## 導入

### AI coding agentを使う

Android projectを開き、次のpromptをcoding agentへ渡します。

```text
このAndroid projectへDataStore Inspector SDKの最新stable releaseを導入してください。

- GitHub Releasesでdraft／prereleaseではない最新versionを探し、同じversionがGradle Plugin PortalとMaven Centralで公開済みであることを確認してexact versionへ固定してください。確認できなければ推測せず私へ質問してください。
- 既存のGradle構成に合わせ、com.masaibar.datastore-inspector PluginをAndroid application moduleだけへ適用してください。
- Runtime artifactを手動追加せず、Gradle／AGP／Kotlin／Android SDKのversionや無関係なファイルを変更しないでください。
- Proto schema mappingが必要なら、generated message classと完全修飾Proto message名を推測せず私へ確認してください。
- 対象moduleの最小debug buildで検証し、変更ファイルと結果を報告してください。
```

### 手動で設定する

Version Catalogを使う場合:

```toml
[versions]
datastore-inspector = "1.0.0"

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
  id("com.masaibar.datastore-inspector") version "1.0.0"
}
```

PluginはAndroid application moduleだけへ適用してください。必要なRuntimeは対応するdebuggable variantへ自動追加されます。

### Proto DataStore

Proto DataStoreを使う場合だけ、generated message classと完全修飾Proto message名を登録します。

```kotlin
dataStoreInspector {
  schemaEntry(
    "com.example.settings.proto.UserSettings",
    "example.settings.UserSettings"
  )
}
```

debuggable variantをbuild・起動し、Android StudioのDataStore Inspectorからapplicationを選びます。実行例は[`sample-app`](sample-app)を参照してください。

## 詳細

- [対応範囲と既知制限](docs/compatibility.md)
- [Gradle Pluginが変更する内容](docs/what-is-injected.md)
- [Custom DataStoreの検査](docs/custom-datastore.md)
- [セキュリティ](docs/security.md)／[プライバシー](docs/privacy.md)

## ライセンス

[Apache License 2.0](LICENSE)。脆弱性は[`SECURITY.ja.md`](SECURITY.ja.md)の手順で非公開報告してください。

Android is a trademark of Google LLC.

The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
