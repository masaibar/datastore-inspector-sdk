# Gradle Pluginが変更する内容

[English](en/what-is-injected.md) | 日本語

Gradle Pluginは、対応するdebuggable variantに対して次の変更を行います。

- Runtime CoreとSharedPreferences catalog adapterを必ず追加する
- dependencyのsignalが存在する場合、対応するDataStore adapterを追加する
- defaultアプリプロセスに、exportしないstartup Providerを生成する
- そのProviderの2つ目のauthorityとして、バージョン付きPackage Managerマーカーを追加する
- 対応するfirst-partyのDataStore作成経路を計装する
- 対応する`DataStoreFactory`／`MultiProcessDataStoreFactory`、`FileStorage`／`OkioStorage`、
  typed delegate、kotlinx.serializationのcall siteを計装する
- 任意のデバッグ専用Custom codec binding providerを生成する
- 該当する場合、到達可能なfirst-party Proto descriptorからJVM class mappingを自動解決し、descriptor assetとschema index v1を生成する
- debuggableなminified fixtureに必要なkeep ruleを追加する

non-debuggable variantに対しては、次の変更を行ってはいけません。

- Inspector RuntimeまたはProtocol artifactを追加する
- startup Providerを追加する
- schema assetを追加する
- applicationまたはlibraryのbytecodeを変換する
- Android permissionを追加する

Providerは従来の`${applicationId}.datastore_inspector_init` authorityを維持し、さらに
`${applicationId}.datastore_inspector_runtime_v1`も宣言します。後者はmarker formatのバージョンであり、
inspection clientはアプリを起動せずに、インストール済みRuntimeを識別できます。これはRuntime
sessionの準備完了を示すものでも、通信Protocolのバージョンでもありません。session metadataと
認証済みhandshakeによって、それぞれを別に確立します。

application variantは`InstrumentationScope.ALL`、Gradle Pluginを直接適用したAndroid library
variantは`InstrumentationScope.PROJECT`を使います。AndroidX DataStore、Kotlin、
kotlinx.coroutines／serialization、Inspector自身のnamespaceは変換しません。対応済みの
owner／method descriptorだけを置換し、未知descriptorは黙って推測しません。

`File`／`Path` producerは作成時に追加評価しません。DataStore本体が通常どおり呼んだ結果から
basenameだけを観測し、絶対pathをmetadataへ保持しません。

`checkSdk`は、debugとdebuggableなstagingにstartup Providerが正確に1つ、SharedPreferences
catalog SPI providerが正確に1つ、生成されたCustom codec binding providerが最大1つ含まれることを
検証します。また、リリースのdependency graph、manifest、APK、AAB、DEX markerをscanし、
Runtime module、hook／codec参照、生成されたbinding／provider、SPI resourceが含まれないことを
確認します。

Pluginはインターネット通信を追加しません。inspection clientとの通信には、ADB forwarding経由で
接続する認証済みlocal socketを使います。
