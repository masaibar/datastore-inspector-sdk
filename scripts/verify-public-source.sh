#!/usr/bin/env bash

set -euo pipefail

readonly script_name="$(basename "$0")"
readonly repo_root="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)"

cd "$repo_root"

fail() {
  printf '%s\n' "${script_name}: $*" >&2
  exit 1
}

require_tracked_file() {
  local path="$1"
  git ls-files --error-unmatch "$path" >/dev/null 2>&1 ||
    fail "公開に必要なtracked fileがありません: $path"
}

for required_file in \
  LICENSE \
  README.md \
  README.ja.md \
  SECURITY.md \
  SECURITY.ja.md; do
  require_tracked_file "$required_file"
done

while IFS= read -r japanese_document; do
  english_document="docs/en/${japanese_document#docs/}"
  require_tracked_file "$english_document"

  japanese_basename="$(basename "$japanese_document")"
  grep -Fq "en/$japanese_basename" "$japanese_document" ||
    fail "日本語文書に英語版へのlinkがありません: $japanese_document"
  grep -Fq "../$japanese_basename" "$english_document" ||
    fail "英語文書に日本語版へのlinkがありません: $english_document"
done < <(git ls-files ':(glob)docs/*.md')

readonly forbidden_path_pattern='(^|/)(\.idea|\.gradle|\.kotlin|build|\.codex)(/|$)|(^|/)(local|signing|secrets?|credentials?|keystore)\.properties$|(^|/)credentials?\.[^/]+\.json$|(^|/)\.env($|\.)|\.(jks|keystore|p12|pfx|pem|key)$'
while IFS= read -r -d '' tracked_path; do
  if [[ "$tracked_path" =~ $forbidden_path_pattern ]]; then
    fail "公開対象にlocal output／credential候補が含まれています: $tracked_path"
  fi
done < <(git ls-files -z)

readonly forbidden_content_pattern='(/Users/[^/[:space:]]+/|/home/[^/[:space:]]+/|[A-Za-z]:\\Users\\|IdeaProjects|/private/tmp/|datastore-inspector-ide|masaibar/datastore-inspector@|github\.com/masaibar/datastore-inspector(/|$))'
if git grep -I -n -E "$forbidden_content_pattern" -- . \
  ':(exclude)scripts/verify-public-source.sh'; then
  fail "公開対象にprivate repository参照または個人環境pathが含まれています。"
fi

readonly unqualified_work_item_pattern='(issue|pull request|pr)[[:space:]]*#[0-9]+'
if git grep -I -i -n -E "$unqualified_work_item_pattern" -- . \
  ':(exclude)scripts/verify-public-source.sh'; then
  fail "公開対象に出所を確認できないIssue／PR identifierが含まれています。"
fi

if git grep -I -n -E \
  -e '-----BEGIN (OPENSSH|RSA|DSA|EC|PGP) PRIVATE KEY-----' -- .; then
  fail "公開対象にprivate keyらしき内容が含まれています。"
fi

printf '%s\n' "公開source境界を確認しました。"
