# セキュリティ

[English](en/security.md) | 日本語

## 信頼境界

DataStore Inspectorはデバッグ専用の開発者向けツールです。non-debuggableまたはリリースの
アプリケーションには対応せず、Androidアプリケーションの分離を回避するためにroot権限を
使うこともありません。

## 通信

- Runtimeは公開TCPポートではなく、Androidのabstract local socketへbindします。
- inspection clientは、ホストのloopbackへbindしたADB forwardingを経由してのみ接続します。
- 接続metadataは、対象アプリのprivate領域から`run-as`で読み取ります。
- privileged protocol frameを送る前に、ランダムなsession tokenによる認証を要求します。
- frameとpayloadのsizeはメモリ確保前に検証します。
- Protocol 1.4の通知は、リクエスト／レスポンスと同じ認証済み接続を使います。単一のoutput lockで
  frame境界を維持し、`store.changes`を交渉した接続だけがobserverまたはnotification writerを
  起動します。
- Storeごとの観測queueとencoded frame用writer queueには上限があります。queueの逼迫、
  stateのoversize、観測失敗、Store削除が発生した場合、不連続な区間を完全なchange streamに
  見せかけず、値を含まない境界通知を送ります。queue内のencoded frameは、書込み、破棄、
  closeのいずれかが行われた時点でゼロ上書きします。

## データ変更

- Runtimeは既存のDataStore instanceを使います。
- 書込みは`edit`または`updateData`を経由します。
- staleなsnapshotはrevisionとcontent tokenを検証して拒否します。
- 対応する変更経路では、未知のProto fieldを保持します。
- 未対応のSerializerやschemaと一致しないSerializerを推測したり、raw editしたりしません。
- Custom DataStoreは同じ実SerializerとDataStore instanceだけを使い、決定性、
  document/value/persistence round trip、runtime class、`equals`、`hashCode`、
  actual write bytesを検証します。raw binaryや例外、class名、値はProtocolへ出しません。
- Custom inspectionは2 worker・64 queue・実行開始後5秒の固定上限です。未開始queue timeout／
  rejectはtaskを即時除去する一時失敗、開始後timeout／cancelやactual write不一致はそのStoreを
  process generation中隔離します。mutation開始後の結果不明は再送しません。
- 自動検出したSharedPreferencesは、platform APIを通じて必要になった時点でopenします。
  catalogのlistでは値をparseせず、SharedPreferences instanceもopenしません。
- SharedPreferences instanceをopenする前と変更直前に、Runtimeはframeworkが正本とするXML
  （mainより`.bak`を優先）が、上限内でwell-formedな標準6型のSharedPreferences構造であることを
  検証します。DTD、entity、未知のtag、重複key、不正な値はfail-closedで拒否します。
  framework APIを正本とするため、parseした値は破棄します。
- SharedPreferencesの書込みには、globalかつqueueを持たない単一の`commit()` laneを使います。
  1回のcommitは独立したdaemon workerで実行します。上限付きのレスポンス待機が期限切れになった
  場合、Runtimeは結果不明を返し、workerが実際にreturnするまでlaneを保持します。falseのreturn、
  例外、待機期限切れのいずれが起きても再実行しません。
- snapshot、preflight、commit後のreadには、別のqueueを持たないstage laneとdaemon workerを
  使います。frameworkやfilesystemのcodeがinterruptを無視した場合や、アプリケーションの共有
  IO poolが飽和した場合でも、Runtimeはstageのdeadlineで待機を打ち切ります。workerが実際に
  returnするまでlaneを保持することで、blockしたworkerの累積を防ぎます。
- open前と変更前に、backing path、regular fileかどうか、symbolic link、16 MiBのraw file上限を
  検証します。logical snapshotには、entry、key、string、Set、aggregate、wire sizeについても
  固定上限があります。
- 既知のAndroidX encrypted preferences keyset markerがどちらか一方でも存在する場合、snapshotや
  変更の前にStore全体を未対応とします。Custom encryption wrapperは一般的な方法では識別できない
  ため、client側での確認が必要です。

## リリースからの分離

`checkSdk`は、リリースvariantにRuntime artifact、Provider宣言、schema asset、Customの
codec／binding／service resource、計装への参照が含まれないことを検証します。

セキュリティ上の問題は、[`SECURITY.ja.md`](../SECURITY.ja.md)に記載した非公開手順で
報告してください。
