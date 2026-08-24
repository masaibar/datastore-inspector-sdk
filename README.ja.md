<p align="center">
  <img src="docs/assets/datastore-inspector-icon.svg" alt="DataStore Inspector" width="144" height="144" />
</p>

<h1 align="center">DataStore Inspector SDK</h1>

<p align="center"><a href="README.md">English</a> | 日本語</p>

**実行中のAndroidアプリのSharedPreferencesとDataStoreを、Android Studioから確認し、その場で書き換える。** 状態を確かめるためだけのログ、一時的なデバッグ画面、再ビルドを減らします。

かつて[Stetho](https://facebook.github.io/stetho/)や[Flipper](https://github.com/facebook/flipper)がもたらした「実行中のアプリ内部を手元で覗く」体験は、Android開発を大きく楽にしてくれました。両プロジェクトがアーカイブされた今も、SharedPreferencesやJetpack DataStoreの値を確認するためにログを足し、状態を変えるために一時的なコードを書いてbuildし直す、という手間は残っています。DataStore Inspectorは、このフィードバックループを短くするために作りました。

このSDKをAndroidアプリへ導入し、Android StudioのDataStore Inspector Pluginから接続すると、Live Modeで次のことができます。

- Preferences DataStoreと永続化済みSharedPreferencesを追加設定なしで検出する
- 登録したProto DataStoreを含むStoreを一覧・検索し、現在の値を確認する
- 対応する値を実行中のアプリへ書き戻し、Storeの変化を追跡する
- アプリが実際に使っているinstanceと公式APIを経由し、DataStoreのtransactionを迂回しない

SDKが追加されるのは対応するdebuggable variantだけです。release variantは変更しません。

Storeのkey、value、schemaを外部サーバーへ送信しません。通信は端末とローカルのAndroid Studio間で、ADB forwardを通した認証済みローカル接続に限定します。SDKはinternet通信、telemetry、Android network permissionを追加しません。

このSDKをオープンソースで公開するのは、アプリへ組み込まれるコードを利用者自身が確認できる状態にするためです。debug buildへ何を追加し、アプリprocess内で何が動き、どのようにStoreへアクセスし、release buildへ何を残さないのかを隠さず公開することで、「自分のアプリへ導入してよいか」を判断できる透明性を担保します。

このリポジトリには、利用者のdebug buildへ追加され、アプリprocess内で動作するSDKを置きます。Android Studio上のUIとinspection clientはIDE Pluginが提供します。

## モジュール

- `gradle-plugin`: debuggable variantへの依存追加、schema生成、ASM instrumentation
- `protocol`: Android Runtimeとinspection clientが共有するversioned Protocol
- `runtime-core`: Registry、接続、認証、競合制御
- `runtime-preferences`: Preferences DataStore adapter
- `runtime-shared-preferences`: 永続化済みSharedPreferencesの動的catalogと標準6型adapter
- `runtime-protobuf`: Proto DataStore adapter
- `sample-app`: Preferences、SharedPreferences、Protoの基本設定を示し、debug専用注入とrelease非混入を検証する最小consumer

## クイックスタート

Android application moduleへPluginを適用します。対応するdebuggable variantだけへ必要なRuntimeが自動追加されるため、Runtime artifactを手動で依存へ追加する必要はありません。

```kotlin
plugins {
  id("com.android.application")
  id("com.masaibar.datastore-inspector") version "<sdk-version>"
}
```

Preferences DataStoreと永続化済みSharedPreferencesは、Inspector固有の追加設定なしで検出されます。Proto DataStoreでは、生成されるmessage classとschema上の完全修飾名を登録します。

```kotlin
dataStoreInspector {
  schemaEntry(
    "com.example.settings.proto.UserSettings",
    "example.settings.UserSettings"
  )
}
```

debuggable variantをbuild・起動し、Android StudioのDataStore Inspectorから対象applicationを選びます。non-debuggable variantは変更されません。実行可能なPreferences、SharedPreferences、Protoの例は[`sample-app`](sample-app)を参照してください。

## ビルド

このrepositoryをsourceからbuildするにはJDK 21とAndroid SDK 36が必要です。公開するGradle Pluginと
Runtime artifactはJava 17をtargetとするため、consumer buildのGradle JVMをJDK 21へ上げる必要はありません。

```shell
./gradlew checkSdk --console=plain
./gradle-plugin/gradlew -p gradle-plugin clean checkPlugin --console=plain
```

release APK／AABへInspector Runtime、Protocol、Provider、schema、instrumentation hookが混入しないことも`checkSdk`で検証します。

## 配布

- Maven Central: `protocol`、`runtime-core`、`runtime-preferences`、`runtime-shared-preferences`、`runtime-protobuf`
- Gradle Plugin Portal: `com.masaibar.datastore-inspector`

公開前のlocal consumer検証、public repositoryの初期設定、credential、release手順は[`docs/publishing.md`](docs/publishing.md)を参照してください。

## namespace

- Kotlin／Java package: `com.masaibar.datastore.inspector.*`
- Maven group: `com.masaibar.datastore-inspector`
- Gradle Plugin ID: `com.masaibar.datastore-inspector`

座標とversionの正本は[`gradle/artifact-coordinates.properties`](gradle/artifact-coordinates.properties)です。

## ドキュメント

- [対応範囲と既知制限](docs/compatibility.md)
- [SDKアーキテクチャ](docs/architecture.md)
- [Gradle Pluginが変更する内容](docs/what-is-injected.md)
- [Custom DataStoreの検査](docs/custom-datastore.md)
- [セキュリティ](docs/security.md)
- [プライバシー](docs/privacy.md)

## ライセンス

Gradle Plugin、Protocol、Runtime module、sample codeを含むこのrepositoryのSDKは、[Apache License 2.0](LICENSE)で提供する。

脆弱性を報告する場合は、[`SECURITY.ja.md`](SECURITY.ja.md)の非公開報告手順に従ってください。

Android is a trademark of Google LLC.

The Android robot is reproduced or modified from work created and shared by Google and used according to terms described in the [Creative Commons 3.0 Attribution License](https://creativecommons.org/licenses/by/3.0/).
