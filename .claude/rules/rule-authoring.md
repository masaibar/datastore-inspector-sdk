---
paths:
  - "**/AGENTS.md"
  - "**/CLAUDE.md"
  - ".claude/rules/**"
  - "docs/**/*.md"
---

# ルール・ドキュメントの記述ルール

ガイダンスファイル（AGENTS.md / CLAUDE.md / .claude/rules/ / docs/）を追加・変更するときの 規約。

## 使い分け

| 保存先 | 用途 | 読み込みタイミング |
|---|---|---|
| ルート `AGENTS.md` | 全ツール共通の正本。全体の背景・Why・不変条件・コマンド | 常時（Codex は直接、Claude Code は `CLAUDE.md` の import 経由） |
| ネスト `AGENTS.md` | 特定ディレクトリ固有の背景・Why・不変条件の正本 | Codex: セッション開始時、root→cwd の経路上にある場合のみ（下記の非対称性を参照） |
| ルート `CLAUDE.md` | Claude Code 固有事項のみの薄いブリッジ | セッション開始時（Claude のみ） |
| ネスト `CLAUDE.md` | 特定ディレクトリの Claude 固有差分（対応するネスト AGENTS.md を import する） | Claude: そのサブツリーのファイルへアクセスした時点（オンデマンド） |
| `.claude/rules/` | path pattern に対応する具体例・実装規約 | Claude: `paths:` にマッチするファイル操作時 / Codex: 索引・Hook 経由 |
| `docs/` | 参照ドキュメント（互換範囲・手順・ADR 等） | 明示的に読んだとき |

### ネスト AGENTS.md とネスト CLAUDE.md は読み込み挙動が異なる

- **ネスト AGENTS.md（Codex）は cwd 基準**: セッション開始時に project root から cwd までの
  経路上のディレクトリだけが対象。repo root で起動したセッションでは、サブディレクトリの
  AGENTS.md は配下のファイルを編集しても読み込まれない（起動時に1回固定）。
- **ネスト CLAUDE.md（Claude Code）はファイルアクセス基準**: cwd と無関係に、そのサブツリーの
  ファイルへアクセスした時点でオンデマンドに読み込まれる。
- Claude Code はネスト AGENTS.md を読まない。ネスト AGENTS.md を追加する場合は、同じ
  ディレクトリに `@AGENTS.md` を import するネスト CLAUDE.md を併置する。
- [Must] この非対称性のため、ディレクトリ限定の規約は原則 `.claude/rules/` の `paths:` で
  表現する。ネスト AGENTS.md は「Codex がそのディレクトリを cwd 起点に作業する運用が実際に
  ある場合」に限って使う（root 起動の Codex には届かないため）。

- [Must] ツール共通の内容は AGENTS.md に、Claude Code 固有の指示だけを CLAUDE.md に書く。
- [Must] `.claude/rules/` は Claude 専用機構のため、他ツールへの導線はルート AGENTS.md の
  生成索引（path guidance index）と Codex Hook で提供する
  （`.claude` は hidden directory で、一般指示だけでは Codex が発見に失敗しやすいため）。
- [Never] 同じ規範を複数ファイルへ重複記載しない。正本を 1 箇所に決め、他からはポインタで
  参照する（重複は必ずドリフトするため）。

## AGENTS.md と .claude/rules/ の責務分離

常時ロードされるファイルは書くほどコンテキストを圧迫する。`.claude/rules/` は必要なときだけ
ロードされる。この特性で分担する。

- **AGENTS.md（ルート・ネスト）**: 毎回必要な事実と背景 — ビルド・検証コマンド、
  アーキテクチャ、不変条件、「なぜこの構成・制約か」
- **`.claude/rules/`**: 特定のファイルを触るときだけ必要な What・How — コーディングパターン、
  命名規則、実装手順、コード例
- [Never] AGENTS.md / CLAUDE.md に実装コード例やコーディングパターンを書かない
  （該当ファイルを触るときだけ必要な情報のため。ビルド・検証コマンドは毎セッション必要な
  事実なので対象外）。コード例が増えてきたら rules への切り出しのサイン（目安: 200 行）。

## 生成索引（path guidance index）

ルート AGENTS.md のマーカー区切りセクションは resolver
（`.agents/scripts/resolve_guidance.py`）が生成する派生物であり、正本ではない。

- [Never] 索引セクションを手編集しない。更新は `--write-index`、検証は `--check-index`（CI でも実行）。
- [Never] rule 本文や背景説明を索引へ複製しない（索引は path → guidance file の対応のみ）。
- [Must] rule の追加・削除・`paths:` 変更をしたら `--write-index` を実行して索引を更新する。

## docs/ の使い方

- 常時読む必要はないが正確さが要る参照情報は `docs/` に置き、AGENTS.md からは 1 行の
  説明付きリンクで参照する。
- [Must] `docs/` 直下の見出しと説明文は原則として日本語で書く。公開利用者向けに英語版を
  併設する文書は同名で `docs/en/` に置き、API 名、型名、識別子、Protocol field など
  原表記が意味を持つ技術用語は両言語とも英語のまま扱う。日本語を正本として読みやすさを
  保ち、公開利用者へ同じ契約を英語でも提供しつつ、翻訳で技術上の意味を変えないためです。
- [Must] 日本語の正本と `docs/en/` の英語版は同じ変更で更新し、相互に言語切替linkを置く。
  独立して更新すると、一方だけ安全境界や対応範囲が古くなり、利用言語によって契約が
  変わるためです。
- [Never] AGENTS.md 内で `@docs/xxx.md` の import 記法を使わない（`@` import は Claude 専用の
  展開記法で、Codex には単なる文字列。ツール間で読み込み内容が非対称になるため）。
  CLAUDE.md 内での使用は可（Claude 固有ファイルのため）。

## ルール記述のルール

- [Must] 公開可否の境界は AGENTS.md の「公開前提と秘密情報」を正本とし、本文だけでなく
  example、command、link、Issue／PR reference にも適用する。値を直接書かなくても参照先や
  path から非公開資産を辿れるためです。
- [Must] ルールには What だけでなく Why を併記する（理由がないルールは将来のエッジケースで
  適用可否を判断できず、形骸化するか過剰適用されるため）。
- [Must] 表記は AGENTS.md の表記ルールに従う（厳守は [Must]、禁止は [Never]。タグは規範にのみ
  付け、文は自然な日本語のままでよい）。
- 書くべきでないもの:
  - コードや git 履歴から導出可能な情報（ディレクトリ一覧・ファイル一覧など）
  - セッション固有・短命な情報
  - 秘密情報（session token・端末 serial などは環境変数や system property で渡す）
