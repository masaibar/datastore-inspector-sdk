# DataStore Inspector SDK エージェントガイダンス

## ガイダンスの正本

このファイルは、Codex と Claude Code を含む全ツールに共通するガイダンスの正本です。
Claude Code 固有の設定だけを `CLAUDE.md` に置きます。

- [Must] `AGENTS.md`、`CLAUDE.md`、`.claude/rules/`、`docs/` を変更する前に
  [ルール・ドキュメントの記述ルール](.claude/rules/rule-authoring.md)を読む。
  正本の責務を分け、ツール間の重複とドリフトを防ぐためです。

## 公開前提と秘密情報

このリポジトリは public repository として公開します。tracked file、fixture、生成した review
artifact、commit metadata、すべての Git 履歴は誰でも閲覧・複製できる公開情報として扱います。

- [Never] password、API key、access token、private key、signing material、credential を含む設定、
  公開許可のない endpoint／host 情報を追加しない。Git は削除前の内容も履歴へ保持し、working
  tree から消すだけでは漏えいを解消できないためです。
- [Never] 公開許可のない repository／project の名称、URL、Issue／PR identifier、checkout
  path、source／artifact への参照を追加しない。直接の秘密値を含まなくても、公開物から
  非公開資産の存在、配置、開発履歴を辿れるためです。
- [Never] 個人の home directory を含む絶対 path、端末 serial、内部 IP／hostname、公開許可の
  ない個人情報・顧客データ・source・文書を追加しない。公開 repository の clone や artifact
  から個人環境や非公開資産を推測できないようにするためです。
- [Must] local／CI で必要な秘密値は environment variable、system property、secret store、
  または `.gitignore` 対象の local file から注入し、repository には値を含まない placeholder
  や `.example` だけを置く。再現可能な設定手順と秘密値を分離するためです。
- [Must] sample、test fixture、文書では明らかな dummy value を使い、実在 credential と同じ値や
  live credential と誤認される形式を使わない。例示や test data からの誤漏えいを防ぐためです。
- [Never] 秘密値を command output、log、test report、screenshot、review artifact、agent への
  入力へ展開しない。調査時も値を redact し、漏えい経路を増やさないためです。
- [Must] commit／公開前に staged diff と到達可能な全 Git 履歴を secret scanner で確認する。
  漏えいの疑いがあれば共有を止め、credential を revoke／rotate してから全 branch・tag・履歴
  から除去し、再走査する。履歴の書き換えだけでは既に取得された秘密値を無効化できないためです。
- [Must] commit author email を含む Git metadata には、公開を許可した identity または GitHub
  noreply address を使う。source file に含まれなくても commit と一緒に公開されるためです。

## コミュニケーション

- [Must] ユーザーへの返信は日本語で行う。
- [Must] ユーザーからの指示を受けたとき無言で動き始めるのではなく、今から何をしようとしているかを表明してから動くこと。
- [Must] ユーザーへ質問・確認する前に、判断材料、選択肢の比較、推奨と根拠を充分に提示する。
- [Must] 質問文は「上記」などの参照に頼らず、単体で理解できる内容にする。

## 公開成果物の言語

このリポジトリは世界中の利用者と contributor が読む公開成果物です。実装・運用で検索可能な
共通語を保ちつつ、日本語は翻訳や日本語固有の検証へ明示的に分離します。

- [Must] 新規に追加または変更する source code の identifier、file name、comment、test name／message、非localizeの
  error message、script／CI の step name と output は英語で書く。利用者が issue、log、source を
  同じ語彙で検索でき、英語話者も障害調査と変更へ参加できるようにするためです。
- [Must] repository に永続化する branch name、commit message、Issue／PR の title・description、
  review comment は英語で書く。公開履歴だけを読んでも変更理由と議論を追えるようにするためです。
- [Must] 新規に追加または変更する利用者向け文字列は英語を既定値にし、日本語は localization
  resource へ分離する。
  表示文字列を source code へ直接埋め込むと、locale の追加と翻訳の更新が困難になるためです。
- [Must] 日本語そのものを検証する fixture／test data、固有名詞、日本固有仕様の入力値では、目的に
  必要な範囲だけ日本語を使う。日本語が挙動の一部である case まで英訳すると検証対象が変わるためです。
- [Must] 既存の日本語 comment、test name、script／CI output は、その箇所を変更するときに意味を
  保って英語へ移行する。既存資産を段階的に揃え、無関係な差分を増やさないためです。
- [Never] 既存資産の一括英語化を、無関係な feature／fix と同じ PR に混ぜない。bulk migration が
  必要なら独立した PR にし、意味変更と機械的翻訳を分離して review できるようにするためです。
- [Must] `AGENTS.md` などの agent guidance と利用者向け文書は
  [ルール・ドキュメントの記述ルール](.claude/rules/rule-authoring.md)に従う。日本語の正本と英語版を
  対で管理する既存方針を、source code の英語規約で上書きしないためです。

## 所有範囲

このリポジトリは、Android build に追加されるものと Android application process 内で
動くものを所有します。対象は Gradle Plugin と ASM instrumentation、Runtime と DataStore
adapter、inspection client と共有する transport Protocol、利用者向けの最小sample application、
公開artifactの互換性gateです。製品固有の網羅的E2E fixtureは公開sourceへ置きません。

- [Never] host-side client implementation への依存を追加したり、その source をこの
  リポジトリへコピーしたりしない。独立して配布する SDK と client の境界は versioned
  Protocol だからです。
- [Never] Gradle Plugin、Runtime、Protocol に licensing 判定を追加しない。entitlement は
  host-side product の責務であり、consumer application に入る SDK の責務ではないためです。

## 不変条件

- [Must] Kotlin／Java namespace は `com.masaibar.datastore.inspector` を使う。公開 artifact、
  instrumentation target、生成コードの識別を一貫させるためです。
- [Must] artifact 座標と version は `gradle/artifact-coordinates.properties` を正本にする。
  root build と独立した Gradle Plugin build の座標を一致させるためです。
- [Must] instrumentation、Runtime dependency、Provider、schema asset は debuggable variant
  だけに追加する。Inspector を release APK／AAB へ混入させないことが製品の安全境界だからです。
- [Never] internet 通信、telemetry、Android network permission を追加しない。通信経路は
  private session token で認証した local socket と ADB forward に限定するためです。
- [Never] DataStore／SharedPreferences の key、field、value、backing path、raw data、
  session token を log に出さない。利用者データと接続 secret を端末外へ漏らさないためです。
- [Must] 実行中の store を変更するときは、実際の instance と公式 API
  （`DataStore.edit`、`DataStore.updateData`、platform SharedPreferences API）を使う。
  serializer と transaction の整合性を迂回しないためです。
- [Must] 未対応 serializer や schema 不一致を推測して raw edit せず、明示的に read-only とする。
  復元不能なデータ破損を避けるためです。
- [Must] Protocol の変更は backward-compatible にするか、明示的に version を上げる。
  SDK と接続 client は独立して release され、一時的に異なる version が接続し得るためです。

## 検証

source buildにはJDK 21とAndroid SDK 36を使用します。SDK全体と、独立buildであるGradle Pluginの
両方を検証します。例外として`gradle/publication-consumer`の互換性gateだけはJDK 17で実行し、
公開artifactがconsumerのGradle JVMをJDK 21へ引き上げないことを保証します。

```shell
./gradlew checkSdk --console=plain
./gradle-plugin/gradlew -p gradle-plugin clean checkPlugin --console=plain
```

- [Must] integration build／test が失敗したら、親 agent が全 error を確認して root cause と
  所有範囲を特定してから修正を委任する。近接ファイルの作者へ機械的に戻すと、横断的な原因を
  誤診するためです。
- [Never] 長時間の build／test／検証を `tail` などへ pipe して進捗を隠さない。失敗の全体像と
  停滞箇所を確認できなくなるためです。

## 参照ドキュメント

- [SDK architecture](docs/architecture.md): build-time、app-process、client の所有境界と Runtime safety。
- [対応範囲と既知制限](docs/compatibility.md): 保証 version、対象 API、未対応経路、安全境界。
- [What the Gradle Plugin changes](docs/what-is-injected.md): debug への注入内容と release 非混入条件。
- [Security](docs/security.md): transport、mutation、release isolation の trust boundary。
- [Privacy](docs/privacy.md): 端末データ、log、telemetry に関する制約。
- [Publication](docs/publishing.md): public repository初期化、公開metadata、credential、release手順。
