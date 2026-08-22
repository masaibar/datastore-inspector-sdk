#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
classifier="$script_dir/classify-ci-changes.sh"
case_count=0

assert_classification() {
  local name="$1"
  local expected="$2"
  shift 2

  local actual
  actual="$($classifier "$@")"
  case_count=$((case_count + 1))

  if [[ "$actual" != "$expected" ]]; then
    echo "分類結果が一致しません: $name" >&2
    echo "expected:" >&2
    printf '%s\n' "$expected" >&2
    echo "actual:" >&2
    printf '%s\n' "$actual" >&2
    exit 1
  fi
}

assert_classification \
  "README" \
  $'heavy=false\nreason=docs-only\nchanged_count=1' \
  --path README.md
assert_classification \
  "docs asset" \
  $'heavy=false\nreason=docs-only\nchanged_count=1' \
  --path docs/assets/datastore-inspector-icon.svg
assert_classification \
  "nested Markdown" \
  $'heavy=false\nreason=docs-only\nchanged_count=1' \
  --path runtime-core/README.md
assert_classification \
  "Kotlin source" \
  $'heavy=true\nreason=source-or-build\nchanged_count=1' \
  --path runtime-core/src/main/kotlin/RuntimeServer.kt
assert_classification \
  "Gradle build" \
  $'heavy=true\nreason=source-or-build\nchanged_count=1' \
  --path build.gradle.kts
assert_classification \
  "workflow" \
  $'heavy=true\nreason=source-or-build\nchanged_count=1' \
  --path .github/workflows/ci.yml
assert_classification \
  "script" \
  $'heavy=true\nreason=source-or-build\nchanged_count=1' \
  --path scripts/verify-public-source.sh
assert_classification \
  "docs and source" \
  $'heavy=true\nreason=source-or-build\nchanged_count=2' \
  --path README.md \
  --path protocol/src/main/kotlin/Protocol.kt
assert_classification \
  "empty diff" \
  $'heavy=true\nreason=no-changes\nchanged_count=0'
assert_classification \
  "manual run" \
  $'heavy=true\nreason=forced-full\nchanged_count=0' \
  --force-full

if "$classifier" --path >/dev/null 2>&1; then
  echo "値なしの--pathを受理してしまいました。" >&2
  exit 1
fi
case_count=$((case_count + 1))

printf 'CI change classifier tests passed: %d cases\n' "$case_count"
