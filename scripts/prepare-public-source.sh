#!/usr/bin/env bash

set -euo pipefail

readonly script_name="$(basename "$0")"
readonly repo_root="$(git -C "$(dirname "$0")/.." rev-parse --show-toplevel)"
readonly output_directory="${1:-$repo_root/build/public-source}"
readonly archive_name="datastore-inspector-sdk-public-source.tar.gz"
readonly checksum_name="${archive_name}.sha256"
readonly archive_path="$output_directory/$archive_name"
readonly checksum_path="$output_directory/$checksum_name"

cd "$repo_root"

fail() {
  printf '%s\n' "${script_name}: $*" >&2
  exit 1
}

git diff --quiet --ignore-submodules -- ||
  fail "tracked working treeに未commit変更があります。"
git diff --cached --quiet --ignore-submodules -- ||
  fail "indexに未commit変更があります。"

"$repo_root/scripts/verify-public-source.sh"

mkdir -p "$output_directory"
rm -f "$archive_path" "$checksum_path"

temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/datastore-inspector-public-source.XXXXXX")"
trap 'rm -rf "$temporary_directory"' EXIT

git archive \
  --format=tar \
  --prefix=datastore-inspector-sdk/ \
  HEAD >"$temporary_directory/source.tar"
gzip -n -c "$temporary_directory/source.tar" >"$archive_path"

git ls-tree -r --name-only HEAD |
  sed 's#^#datastore-inspector-sdk/#' |
  LC_ALL=C sort >"$temporary_directory/expected-files.txt"
tar -tzf "$archive_path" |
  sed '/\/$/d' |
  LC_ALL=C sort >"$temporary_directory/archive-files.txt"
diff -u \
  "$temporary_directory/expected-files.txt" \
  "$temporary_directory/archive-files.txt"

(
  cd "$output_directory"
  shasum -a 256 "$archive_name" >"$checksum_name"
  shasum -a 256 -c "$checksum_name"
)

printf '%s\n' "公開用source archiveを生成しました: $archive_path"
printf '%s\n' "SHA-256: $checksum_path"
