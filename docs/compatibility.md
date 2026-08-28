# 対応範囲と既知制限

[English](en/compatibility.md) | 日本語

## 初期版の対応範囲

- Android Studio Panda 4 Patch 1（`AI-253.32098.37.2534.15336583`）
- Android Studio Quail 3 Patch 1（`AI-261.26222.65.2613.16025427`）
- consumerのGradle JVMはJDK 17、SDKのsource build／releaseはJDK 21
- 公開Gradle Plugin／Runtimeのbytecode targetはJava 17、ProtocolはJava 11
- 検証基準のGradle 9.6.1、AGP 9.2.1
- Runtime AAR／consumer appのmanifest minSdk 23、compileSdk／targetSdk 36の検証sample
- Inspectorの接続・動作保証端末はAndroid 9（API 28）以上
- AndroidX DataStore 1.2.1
- Kotlin 2.3.21
- protobuf-javalite 4.35.0、protobuf Gradle Plugin 0.10.0
- applicationと到達可能なfirst-party Android libraryの`preferencesDataStore`／typed `dataStore` delegate
- typed `dataStore`／`deviceProtectedDataStore`、`DataStoreFactory`、
  `MultiProcessDataStoreFactory`、`FileStorage`、`OkioStorage`を通るCustom DataStore
- applicationの`InstrumentationScope.ALL`で到達する依存artifact内の対応済み作成call site
- `debuggable = true`のdebug／custom build type
- main/default processのcredential-protected SharedPreferences
  （`String`／`Int`／`Long`／`Float`／`Boolean`／`Set<String>`）

## 初期版の対象外

- `debuggable`ではないvariant
- API 27以下の端末
- secondary processへの接続
- device-protected storage、EncryptedSharedPreferencesの復号、custom暗号化wrapperの自動判定
- KMP Androidの`PreferenceDataStoreFactory.createWithPath`とFactory直接生成の自動計装
- iOSなどAndroid以外のKMP target
- 対応済み作成call siteを通らないCustom DataStore、実instance／Serializerを取得できない経路
- Custom serializerのraw binaryを推測または直接編集すること

自動計装対象外のAndroid経路には、利用者が保持する同じDataStore instanceを渡すdebug専用`registerDataStoreInspectorFallback`を用意しています。呼び出し側は`@OptIn(ExperimentalDataStoreInspectorApi::class)`でexperimental APIの利用を明示します。sampleと初期保証delegate経路はfallbackを使いません。

## 安全境界

- release APK／AABへRuntime、Protocol、Provider、hook参照、schemaを入れない。
- debuggable variantだけにversion付きRuntime markerを含め、markerの存在とprocess起動状態、
  認証済みsessionの接続可否を別々に扱う。
- main/default processだけでserverを起動する。
- API 27以下ではRuntimeを起動せず、API 28以上だけを対象にする。
- private session tokenとabstract local socketを使い、ADB forward経由だけで接続する。
- stale snapshotを自動再送せず、DataStore transaction内で競合を拒否する。
- Protocol 1.3の`preferences.replace`は、typed entry全体をDataStoreでは1回の
  `updateData`、SharedPreferencesでは1個の`Editor`と1回の`commit()`で置換する。
  Protocol 1.2以下との接続ではcapabilityを交渉せず、未知のoperation subtypeを送らない。
- Protocol 1.4の`store.changes`は、双方がcapabilityを広告した接続だけで有効にする。
  Protocol 1.3以下との接続ではobserverとnotification writerを開始せず、追加した
  notification subtype、logical Store ID、write correlationを旧clientの前提にしない。
  logical Store ID、Store descriptor／snapshot generation、correlation IDは既存model末尾のoptional
  fieldなので、1.4 artifactは1.3以前の保存済みrequest／responseもdecodeできる。`store.changes`を
  広告する1.4 Runtimeだけはdescriptor generationを必須とし、current Runtimeのsnapshotにも同じ
  incarnationを返す。
- `store.changes`はPreferences DataStore、Proto DataStore、credential-protectedの標準
  SharedPreferencesを対象とする。Custom DataStoreはprojectionの安全性と任意serializerの
  継続観測契約が別途必要なため、1.4では購読capabilityを広告しない。
- 通知の最初のstateは履歴eventではなくbaseline候補である。複数keyが同じcanonical
  fingerprint遷移で変わった場合はStore全体で1通知とし、listener／transportのbounded
  queueで欠落し得る場合は値なしのboundaryへ切り替える。
- SharedPreferencesは事前fingerprint競合を検出するがatomic compare-and-setとはせず、
  `commit()` false／例外を結果不明として自動再送しない。
- Custom serializerは安全なprojectionを証明できた場合だけ編集可能にし、証明できない場合は
  安全なreason code付きのUnsupportedにする。
- 同じoriginal serializer identityを共有するCustom Storeはinspection mutexと実行開始後timeoutの
  quarantineを共有する。queue内で未開始の一時timeoutは共有quarantineにせず、全handle解放後の
  新しいidentity entryへ古いreasonを引き継がない。
- Protocol 1.2のCustom capabilityを広告しない旧clientには既知のstatic Custom Storeを
  `CUSTOM` / `UNSUPPORTED`、capability空、schemaなし、固定safe reasonのdescriptorとして返す。
  snapshotは既存wire型の`UnsupportedSnapshotInfo`へ縮退し、writeは`STORE_UNSUPPORTED`で
  拒否するため、未知の`CustomDocumentPayload`やCustom operation subtypeを旧clientへ送らない。

version変更時はGradle PluginのTestKit、sample application、release分離検証を
すべて再実行します。
