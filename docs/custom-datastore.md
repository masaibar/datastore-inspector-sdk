# Custom DataStoreの検査

[English](en/custom-datastore.md) | 日本語

Protocol 1.2では、Proto／Preferences以外のtyped DataStoreを、実Serializerの契約を安全に
証明できた場合だけJSONまたはtext documentとして表示・置換できます。raw persistence bytesを
inspection clientへ送ったり、型や暗号化形式を推測したりはしません。設定なしで安全なprojectionを解決できない
Storeは、固定reason code付きのUnsupportedになります。

## projectionの優先順位

Runtimeはsnapshotごとに次の順で候補を検証します。成功候補が複数ある場合はformatとcanonical
documentがすべて一致することも必須とし、一致した場合だけ先頭候補を採用します。

1. `direct-json-v1`: 実Serializerの出力がstrict UTF-8 JSON
2. `structured-json-v1`: 実Serializer実行中に捕捉したkotlinx.serializationの
   `KSerializer`と`SerializersModule`
3. `generated-json-v1`: runtime classがnon-genericで生成`KSerializer`を安全に取得可能
4. `direct-text-v1`: strict UTF-8 text
5. `fallback:<codec-id>:<schema-version>`: 明示したdebug-only codec binding

各候補は同じ値のencodeを2回実行して決定性を確認し、document decode、runtime class、
`equals`、`hashCode`、document再encode、実Serializerでのpersistence round tripを検証します。
JSONはduplicate key、非有限数、不正UTF-16を拒否します。成功候補のformatまたはdocumentが
一致しない場合、structured captureが複数の異なるroot contractを観測した場合、debug codecが
重複または曖昧な場合は、優先候補が安全でも推測せずambiguousになります。

projection cacheが保持するのは、同じruntime classとroot contract generationに対する候補経路
だけです。snapshotごとにdocument/value gateを再実行し、`structured-json-v1`、
`generated-json-v1`、fallback codecは現在値を実Serializerでencode/decodeするpersistence
preflightも必ず通します。新しいroot contractの捕捉、runtime classの変化、またはactual outputの
JSON／textへの昇格を観測した場合はcacheを破棄して優先順位を解決し直します。

`@JvmInline` rootだけはgeneric callによる再boxingを考慮し、exact runtime class、
`equals`、`hashCode`がすべて一致する一意候補を許可します。通常classはobject identity一致が
必須です。

## デバッグ専用のfallback codec

ゼロ設定の4経路を証明できない場合だけfallback codecを採用します。bindingが存在する場合は
auto projection成功時にも候補としてprobeし、formatとcanonical documentの不一致をfail closed
します。codec classは`src/debug`などdebuggable variantだけのsource setへ置き、public no-arg
constructorを持たせます。

```kotlin
public class SettingsCodec : InspectorCustomCodec<Settings> {
    override val codecId: String = "settings"
    override val schemaVersion: Int = 1
    override val format: CustomDocumentFormat = CustomDocumentFormat.JSON

    override fun encode(value: Settings): String =
        """{"label":${Json.encodeToString(value.label)}}"""

    override fun decode(document: String): Settings {
        val root = Json.parseToJsonElement(document).jsonObject
        return Settings(root.getValue("label").jsonPrimitive.content)
    }

    override fun validate(value: Settings) {
        require(value.label.length <= 256)
    }
}
```

application moduleのGradle設定では、Serializer、value、codecのexact JVM classを1対1で
bindingします。

```kotlin
dataStoreInspector {
    customCodecBinding(
        "com.example.SettingsSerializer",
        "com.example.Settings",
        "com.example.debug.SettingsCodec",
    )
}
```

`codecId`は`[A-Za-z0-9][A-Za-z0-9._-]{0,63}`、schema versionは正数、
formatはJSONまたはTEXTです。同じprovider IDや同じSerializer/value bindingの重複、
例外を投げるprovider、型不一致はすべてambiguousとしてfail closedします。Runtimeが
projection IDを`fallback:settings:1`のようにnamespace化します。

Gradle Pluginはbindingの型関係を生成sourceのcompileで検証し、ServiceLoader providerを
debuggable variantだけへ生成します。codec、generated source/resource、Runtime、Protocol、
hook参照はrelease APK／AABへ入りません。

## 対応する作成経路

AndroidX DataStore 1.2.1について、次の既知descriptorを計装します。

- typed `dataStore`／`deviceProtectedDataStore` delegate
- `DataStoreFactory.create`／`createInDeviceProtectedStorage`
- `MultiProcessDataStoreFactory.create`
- 上記factoryのSerializer overloadとStorage overload、既知のdefault overload
- `FileStorage`／`OkioStorage` constructor

application variantは`InstrumentationScope.ALL`なので到達するproject／dependency classの
call siteが対象です。Pluginを直接適用したAndroid library variantは`PROJECT`だけを変換します。
AndroidX、Kotlin、kotlinx、Inspector自身は除外し、対応表にないowner／descriptorは推測しません。

`File`／`Path` producerをmetadata取得のために追加実行することはありません。通常のDataStore
作成がproducerを呼んだ時だけbasenameを観測します。Proto `MessageLite`のSerializerはwrapせず、
従来のProto adapterへ渡します。

対応作成経路を通らず実Serializerを捕捉できないCustom Storeは
`CUSTOM_CREATION_ROUTE_UNSUPPORTED`または`CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE`です。
`registerFallback`は利用者が保持する同じinstanceの登録手段ですが、捕捉できなかった
Serializer契約を推測可能にするものではありません。

## 書込みとtimeoutの境界

Custom replaceはsnapshotのrevision／content tokenを1回だけclaimし、同じDataStore instanceの
`updateData` transaction内でも現在のfingerprintを再確認します。projection metadataが一致し、
変更documentが別の同型valueになる場合だけ進みます。`equals`が変更fieldを無視してDataStoreが
writeをskipし得る型は`CUSTOM_VALUE_EQUALITY_TOO_COARSE`です。

candidateはscratch bufferへ実Serializerでencodeされ、decodeとprojection完全一致を確認してから
実sinkへcommitされます。actual serializer outputが提出documentと一致しない場合はcommit前に
abortし、Storeを隔離します。成功後に同じdocumentを再送しても追加writeしません。

inspection executorはprocess全体で2 worker、queue 64、task実行開始後5秒です。同じStoreの
single-flight待機はworkerとqueueを消費せず、5秒budgetはSerializer処理を開始してから数えます。
queue rejectと未開始queue timeout/cancelはFutureを即時除去する一時失敗で、そのStoreを隔離
しません。開始後timeout/cancelは、対象Storeと同じoriginal Serializer identityの共有entryに
属するStoreを隔離します。

同じoriginal Serializer identityを複数Storeが共有するときは、original callを直列化するmutexと
実行開始後timeoutのpoison reasonもidentity単位で共有します。timeout後に同じmutexを待っていた
inspectionは、mutex取得後にpoisonを再確認してoriginal Serializerを呼びません。timeout後の
追加inspectionもexecutor投入前に拒否します。一方、queue内で未開始の一時timeoutや
actual-write mismatchなどのStore固有reasonは共有poisonにせず、通常applicationの
delegate read／writeも止めません。全handleがcloseされると共有entryを破棄するため、その後に
同じserializer identityへ作る新しいhandleへ古いpoisonを引き継ぎません。

mutation expectation作成前のtimeoutは`operationStarted=false`です。作成後からFuture結果を
Runtimeが観測するまでのtimeout、commit/return時の未知例外は`operationStarted=true`で、
inspection clientは結果不明として自動再送しません。隔離後のlistは`UNSUPPORTED`、capability空、固定reason
codeとなり、snapshotとreplaceの再送を拒否します。

Serializer／codecの通常例外は固定reasonへ正規化しますが、cause chain内の
`CancellationException`、`VirtualMachineError`、`ThreadDeath`、`LinkageError`は通常失敗として
握り潰さず、最深の例外identityを保ってexecutor境界へ伝播します。

## Protocolと上限

Custom Storeには`custom.document.get`が必要で、置換にはさらに
`custom.document.replace`が必要です。Custom capabilityを広告しないProtocol 1.0／1.1相当の
clientにも、既知のstatic Custom Storeは`CUSTOM` / `UNSUPPORTED`、capability空、schemaなし、
固定safe reasonのdescriptorとしてlistします。snapshotはadapterやprojectionを呼ばず既存wire型の
`UnsupportedSnapshotInfo`へ縮退し、writeは`STORE_UNSUPPORTED`で拒否します。これにより
`CustomDocumentPayload`やCustom operation subtypeを旧clientへ送りません。GETだけのclientは
従来どおりlist／snapshotを利用でき、replaceだけがcapability errorになります。

共有validation上限は次のとおりです。

- document: UTF-8で1 MiB
- JSON depth: 64、node: 100,000、1 collection: 10,000 entry
- JSON string: UTF-8で256 KiB、number token: 1,024文字
- projection ID: UTF-8で128 byte
- persistence scratch buffer: 8 MiB
- structured root contract: 最大2件、decode候補: 固定16件

reason、error、logにはclass名、例外、document、value、raw bytes、session token、絶対pathを
含めません。

## `Unsupported`の切り分け

inspection clientに表示される固定reason codeは機微情報を含まない分類です。代表的な確認先は次のとおりです。

- `CUSTOM_CREATION_ROUTE_UNSUPPORTED`／`CUSTOM_SERIALIZER_CAPTURE_UNAVAILABLE`:
  対応済み作成経路と`InstrumentationScope`、実Serializerを取得できる経路かを確認する。
- `CUSTOM_STRUCTURED_CODEC_NOT_CAPTURED`／`CUSTOM_TYPE_ARGUMENTS_UNAVAILABLE`／
  `CUSTOM_CONTEXTUAL_MODULE_UNAVAILABLE`: ゼロ設定projectionで契約を証明できないため、
  必要ならdebug source setのfallback codecを明示する。
- `CUSTOM_*_ROUND_TRIP_MISMATCH`／`CUSTOM_SERIALIZER_NON_DETERMINISTIC`:
  Serializer、codec、`equals`／`hashCode`が全状態を可逆かつ決定的に表しているかを確認する。
- `CUSTOM_PROBE_TIMEOUT`／`CUSTOM_ACTUAL_WRITE_MISMATCH`: Storeは現在のprocess generation中
  隔離される。自動再送せず、原因を直してapp processを再起動してから新しいsnapshotを取得する。
