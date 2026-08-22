# セキュリティポリシー

[English](SECURITY.md) | 日本語

## サポート対象version

長期的なサポートポリシーを定めるまでは、最新の公開releaseだけにセキュリティ修正を
提供します。

| version | サポート |
|---|---|
| 最新の公開release | あり |
| 過去のrelease、snapshot、未公開build | なし |

## 脆弱性の報告

このrepositoryのGitHub private vulnerability reportingを使ってください。

- [脆弱性を非公開で報告する](https://github.com/masaibar/datastore-inspector-sdk/security/advisories/new)

脆弱性の疑いをpublic Issue、Discussion、pull request、commitで報告しないでください。
詳細を公開する前に、repository ownerが調査し、公開方法を調整するための時間を
確保してください。

再現と影響評価に必要な情報だけを含めてください。

- 影響を受けるartifactとversion
- 影響を受けるAndroidとbuild toolのversion
- セキュリティ上の影響と成立条件
- dummy dataを使った最小限の再現手順
- 対策案（分かる場合）

実際のDataStore／SharedPreferencesのkeyやvalue、raw app data、session token、端末serial、
credential、private repositoryの情報、private endpoint、個人環境の絶対pathを含めないでください。
logとscreenshotは添付前にredactしてください。

repository ownerは報告をtriageし、private advisory上で調整を続けます。

SDKの信頼境界とrelease分離の保証は[`docs/security.md`](docs/security.md)を参照してください。
