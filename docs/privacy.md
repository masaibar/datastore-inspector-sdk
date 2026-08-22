# プライバシー

[English](en/privacy.md) | 日本語

SDKはテレメトリを送信せず、インターネットにも接続しません。

DataStoreおよびSharedPreferencesのkey、field、value、descriptor、接続metadataは、ローカルの
Android端末、ADB、ローカルのinspection clientの間だけで扱います。Runtimeはvalue、logical Store名、
backing path、raw XML、session token、raw connection metadataをログへ出してはいけません。

Protocol 1.4のStore変更通知は、ローカルクライアントが変更前後の差分を計算するために必要な
canonical stateを運びます。Runtimeが保持するのは、認証済み接続に対する上限付きのin-memory
観測queueとencoded frame queueだけであり、timeline dataは永続化しません。encoded frameは
書込み、破棄、接続終了の後にゼロで上書きし、すべてのobserverとlistenerも接続終了時に
disposeします。oversize境界とgap境界にはStore identity metadataを含めますが、keyやvalueは
含めません。

安定したlogical Store IDは、SHA-256から導出するopaqueな識別子です。入力によってprocess、宣言、
backend、scope、logical nameを区別する場合がありますが、wire valueに元のnameやbacking pathが
含まれることはありません。write correlation IDはクライアントが生成するopaqueな識別子であり、
keyやvalueをencodeしてはいけません。

Custom projection captureはroot serializerと`SerializersModule`のidentityだけを最大2件保持し、
documentやvalueを保持しません。decode候補も固定上限を超えた時点で破棄してfail closedします。
Unsupported reasonとProtocol errorは固定codeだけで、Serializer／value class名、例外message、
document、raw persistence bytes、絶対pathを含みません。

SDKはクライアント側のsnapshot、preset、audit history、licensing stateを永続化しません。
これらはクライアント実装側の責務であり、リリース前にクライアント側で個別の
プライバシーレビューが必要です。
