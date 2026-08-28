# APIの安定性

[English](en/api-stability.md) | 日本語

DataStore Inspector SDKの利用者向けStable APIは、Gradle Plugin ID `com.masaibar.datastore-inspector`、`dataStoreInspector` extension、Proto登録用の`schemaEntry`だけです。Runtime artifactを直接追加したり、Runtime／Protocolのclassをアプリから呼び出したりしないでください。

## 区分

- **Stable**: 上記のPlugin ID、extension、`schemaEntry`。互換性を壊す変更は次のmajor versionまで行いません。
- **Experimental**: Custom DataStoreのcodec、fallback登録、`customCodecBinding`。`ExperimentalDataStoreInspectorApi`または`ExperimentalDataStoreInspectorGradleApi`への明示的なopt-inが必要で、minor versionでも変更・削除する場合があります。
- **Internal**: ProtocolのKotlin model／codec、Runtimeのbridge／adapter／ServiceLoader型、Gradle taskとPlugin実装。計装、生成code、module間連携のためpublic bytecodeとして存在するものがありますが、利用者向けAPIではありません。Internal markerへのopt-inはSDK内部の連携だけを目的とし、互換性を保証しません。

source分類gateは利用者向けAPIの分類漏れを検出します。独立consumerはStable DSLをnamed argument込みでcompileし、Plugin ID、extension、`schemaEntry`のsource互換性を検証します。互換性を保証しないInternal bytecodeはbaselineへ固定しません。

## Versioning

Stable APIを廃止する場合はreplacementを示し、少なくとも1つのminor releaseでdeprecated期間を設け、削除は次のmajor releaseで行います。後方互換な追加はminor、bug fixはpatchです。Experimental／Internal APIにはこのdeprecation期間を適用しません。

## サポート対象の終了

公開文書で対応を保証したAndroid API level、AGP、Gradle、JDK、Kotlin、DataStoreなどのversionを対象外へ変更することはbreaking changeとして扱います。通常は少なくとも1つのminor releaseで終了予定と代替手段を告知し、削除は次のmajor releaseで行います。重大なsecurity問題やupstreamのサポート終了によって早期変更が必要な場合は、minor releaseのrelease noteに理由、影響範囲、最後に対応するSDK versionを明記します。

依存versionはdynamic rangeを使わず、GitHub Release、Gradle Plugin Portal、Maven Centralで確認できた同じexact versionへ固定してください。

Protocolのwire互換性はKotlin API／ABIとは別の契約です。IDEとRuntimeはcapability negotiationとcross-repository contract fixtureで互換性を検証します。
