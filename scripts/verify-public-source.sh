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

assert_no_tracked_content_match() {
  local failure_message="$1"
  shift

  local grep_status
  if git grep "$@"; then
    fail "$failure_message"
  else
    grep_status=$?
    if ((grep_status != 1)); then
      fail "Unable to inspect tracked content (git grep exited with status $grep_status)."
    fi
  fi
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

readonly forbidden_content_pattern='(/Users|/home)/[^/[:space:]]+|(^|[^[:alnum:]_])[A-Za-z]:[/\\]|IdeaProjects|/Applications/|/Library/Java/JavaVirtualMachines/|/opt/homebrew/|/Volumes/|/(private/)?tmp/|/(private/)?var/folders/|datastore-inspector-ide|(^|[^[:alnum:]_.-])masaibar/datastore-inspector(\.git)?([^[:alnum:]_.-]|$)'
assert_no_tracked_content_match \
  "Published source contains a private repository reference or a machine-specific path." \
  -a -n -E "$forbidden_content_pattern" -- . \
  ':(exclude)scripts/verify-public-source.sh'

history_matches="$(
  git log --all --text --format='%H' -G"$forbidden_content_pattern" -- . \
    ':(exclude)scripts/verify-public-source.sh'
)" || fail "Unable to inspect reachable Git file history."
if [[ -n "$history_matches" ]]; then
  fail "Reachable Git file history contains a private repository reference or a machine-specific path."
fi

commit_messages="$(git log --all --format='%B')" || fail "Unable to inspect reachable Git commit messages."
if [[ "$commit_messages" =~ $forbidden_content_pattern ]]; then
  fail "Reachable Git commit messages contain a private repository reference or a machine-specific path."
fi

readonly unqualified_work_item_pattern='(issue|pull request|pr)[[:space:]]*#[0-9]+'
assert_no_tracked_content_match \
  "Published source contains an unqualified issue or pull request identifier." \
  -a -i -n -E "$unqualified_work_item_pattern" -- . \
  ':(exclude)scripts/verify-public-source.sh'

assert_no_tracked_content_match \
  "Published source contains content resembling a private key." \
  -a -n -E -e '-----BEGIN (OPENSSH|RSA|DSA|EC|PGP) PRIVATE KEY-----' -- .

printf '%s\n' "公開source境界を確認しました。"
