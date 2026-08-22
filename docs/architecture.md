# SDKアーキテクチャ

[English](en/architecture.md) | 日本語

```text
利用側のdebugビルド
        │
        ▼
Gradle Plugin
  ├── Runtimeモジュールを追加
  ├── 対応するDataStore作成経路を計装
  └── Proto schema assetをパッケージ化
        │
        ▼
Androidアプリプロセス
  ├── Runtime Core
  │     ├── 計装で取得したDataStore Registry
  │     └── リクエスト時に走査する動的Store Catalog
  ├── Preferences Adapter
  ├── SharedPreferences Adapter
  ├── Proto Adapter
  ├── Custom projection Adapter
  └── 認証付きLocalServerSocket
        │
        │ ADB forwarding + バージョン付きProtocol
        ▼
ホスト側のinspection client
```

## 責務の境界

SDKはビルド時の計装、アプリプロセス内の動作、DataStoreアダプター、通信Protocolを
所有します。IntelliJ Platform APIは公開せず、UI、Marketplace、entitlement、Snapshot、
Preset、MCPポリシーも含みません。

クライアント実装は公開済みのProtocol artifactを利用し、このリポジトリの責務には含めません。

## Runtimeの安全性

- 生成されたProviderは、debuggableなdefaultプロセスでだけ起動します。
- 同じProviderは、バージョン付きの
  `${applicationId}.datastore_inspector_runtime_v1` Package Managerマーカーも公開します。
  アプリの停止中もマーカーは確認できますが、プロセスや認証済みRuntimeセッションの存在を
  意味するものではありません。
- API 28未満ではProviderがno-opとなり、セッションmetadata、socket、threadを作りません。
  Runtime AARのmanifest `minSdk`は23のままです。
- 接続ごとにランダムなprivate session tokenで認証します。
- ADBはホストのloopback portをabstract local socketへ転送します。
- Preferencesの変更には`DataStore.edit`を使います。
- SharedPreferencesは永続化済みのcredential-protectedファイルだけをカタログへ登録し、
  値はplatform APIを正本として`commit()`を1回だけ使います。frameworkがパース失敗を
  空のMapとして公開する挙動と区別するため、`.bak`を優先する正本XMLはopen前と変更直前に、
  標準6型の構造だけをfail-closedで検証します。
- SharedPreferencesのsnapshot、書込み前検証、事後読取り、`commit()`は、
  それぞれキューを持たないlaneと独立したdaemon workerで実行します。待機期限後も
  workerが終わるまではlaneを保持し、Runtimeの単一クライアントループとworker数を有界に保ちます。
  deadline待機はアプリ共用の`Dispatchers.IO`へdispatchしません。
- SharedPreferencesの比較と書込みはatomicではないため、Protocol 1.1の
  `BEST_EFFORT_NON_ATOMIC` semanticsと結果不明状態を使います。
- Protocol 1.4の`store.changes`を交渉した接続では、Runtimeが接続中だけ各対応Storeを
  購読します。Preferences／Proto DataStoreは既存`data` Flow、SharedPreferencesは
  framework listenerを起点に、観測時点のcanonicalなStore全体を送ります。最初のstateは
  `BASELINE`とし、fingerprintが変わったstateだけを`CHANGE`としてStore単位の連続sequenceへ
  割り当てます。baselineを履歴へ採用するか、変更前後の差分をどう表示するかはクライアントの
  責務です。
- 動的Store catalogは接続中に再照合し、Store incarnationと接続subscriptionのgenerationを
  通知へ含めます。`store.changes`対応descriptorの`generation`と、現在のRuntimeが返すsnapshotの
  `storeGeneration`にも同じincarnationを付け、クライアントがfresh readと復元対象を照合できる
  ようにします。observer、Flow collection、SharedPreferences listener、reconcile job、
  notification writer、保留frameは接続終了時に明示的にdisposeします。
- Store観測queueとsocket writer queueは固定上限です。conflation、backpressure、oversize、
  観測失敗、Store削除で連続stateを保証できない場合は値を含めず境界通知を送り、クライアントが
  欠落区間を通常の変更として誤解しないようにします。
- Inspectorによる書込みには任意のopaque correlation IDを付けられます。Runtimeは同じStoreの
  write結果fingerprintとobserver通知を待ち合わせ、対応する通知だけへIDを返します。
  timelineのsource判定と重複排除はクライアントが所有します。
- Protoの変更には`DataStore.updateData`を使います。
- Customの変更には、捕捉した同じ`DataStore` instanceとその`updateData` transactionを使います。
  Runtimeは成功を返す前に、編集済みdocument、candidate value、元のpersistence round trip、
  実際のSerializer出力を検証します。
- content tokenとrevisionによってstaleな変更を拒否します。
- 未対応のSerializerは明示的にread-onlyのままとします。

Custom projectionの選択、安全性、作成経路、timeout境界は
[`custom-datastore.md`](custom-datastore.md)を正本とします。

## リリースとの境界

Gradle PluginはRuntime dependency、生成asset、Provider、計装をdebuggable variantだけに
接続します。リリース検証ではclasspath、manifest、パッケージ済みasset、class参照、APK、AABの
出力を監査します。
