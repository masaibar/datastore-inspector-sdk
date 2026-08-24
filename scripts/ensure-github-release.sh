#!/usr/bin/env bash

set -euo pipefail

readonly script_name="$(basename "$0")"

fail() {
  printf '%s\n' "${script_name}: $*" >&2
  exit 1
}

release_version=""
release_commit=""
repository=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version)
      [[ $# -ge 2 ]] || fail "--versionには値が必要です。"
      release_version="$2"
      shift 2
      ;;
    --commit)
      [[ $# -ge 2 ]] || fail "--commitには値が必要です。"
      release_commit="$2"
      shift 2
      ;;
    --repository)
      [[ $# -ge 2 ]] || fail "--repositoryにはowner/repositoryが必要です。"
      repository="$2"
      shift 2
      ;;
    *)
      fail "不明な引数です: $1"
      ;;
  esac
done

[[ -n "$release_version" ]] || fail "--versionは必須です。"
[[ -n "$release_commit" ]] || fail "--commitは必須です。"
[[ "$repository" =~ ^[^/]+/[^/]+$ ]] || fail "--repositoryはowner/repository形式で指定してください。"
[[ "$release_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.-]+)?$ ]] ||
  fail "release versionはSemVer形式で指定してください。"

readonly repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

readonly coordinates_file="gradle/artifact-coordinates.properties"
[[ -f "$coordinates_file" ]] || fail "artifact座標の正本がありません: $coordinates_file"

readonly configured_version="$(sed -n 's/^version=//p' "$coordinates_file")"
[[ "$configured_version" == "$release_version" ]] ||
  fail "指定versionとartifact座標の正本が一致しません。"
[[ "$configured_version" != *-SNAPSHOT ]] || fail "SNAPSHOT versionはreleaseできません。"

readonly resolved_commit="$(git rev-parse --verify "${release_commit}^{commit}")"
readonly checked_out_commit="$(git rev-parse HEAD)"
[[ "$resolved_commit" == "$checked_out_commit" ]] ||
  fail "指定commitとcheckout commitが一致しません。"

readonly release_tag="v${release_version}"
readonly tag_ref="refs/tags/${release_tag}"
remote_tag_commit=""

query_remote_tag() {
  local output
  local status
  local direct_commit=""
  local peeled_commit=""

  set +e
  output="$(git ls-remote --tags --exit-code -- origin "$tag_ref" "${tag_ref}^{}" 2>&1)"
  status=$?
  set -e

  if [[ $status -eq 2 ]]; then
    remote_tag_commit=""
    return
  fi
  if [[ $status -ne 0 ]]; then
    printf '%s\n' "$output" >&2
    fail "remote tagを照会できませんでした。"
  fi

  while IFS=$'\t' read -r object_id ref_name; do
    [[ -n "$object_id" && -n "$ref_name" ]] || continue
    case "$ref_name" in
      "$tag_ref")
        direct_commit="$object_id"
        ;;
      "${tag_ref}^{}")
        peeled_commit="$object_id"
        ;;
      *)
        fail "予期しないremote refです: $ref_name"
        ;;
    esac
  done <<< "$output"

  remote_tag_commit="${peeled_commit:-$direct_commit}"
  [[ -n "$remote_tag_commit" ]] || fail "remote tagのcommitを解決できませんでした。"
}

query_remote_tag

if [[ -n "$remote_tag_commit" ]]; then
  [[ "$remote_tag_commit" == "$resolved_commit" ]] ||
    fail "${release_tag}は別のcommitを指しています。tagは移動しません。"
  printf '%s\n' "既存の${release_tag}を再利用します。"
else
  if git show-ref --verify --quiet "$tag_ref"; then
    readonly local_tag_commit="$(git rev-list -n 1 "$release_tag")"
    [[ "$local_tag_commit" == "$resolved_commit" ]] ||
      fail "localの${release_tag}が別のcommitを指しています。"
  else
    git \
      -c user.name='github-actions[bot]' \
      -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
      tag -a "$release_tag" "$resolved_commit" -m "Release ${release_tag}"
  fi

  if ! git push origin "${tag_ref}:${tag_ref}"; then
    query_remote_tag
    [[ "$remote_tag_commit" == "$resolved_commit" ]] ||
      fail "${release_tag}をpushできませんでした。"
  fi

  query_remote_tag
  [[ "$remote_tag_commit" == "$resolved_commit" ]] ||
    fail "push後の${release_tag}がrelease commitと一致しません。"
  printf '%s\n' "annotated tag ${release_tag}を作成しました。"
fi

if gh release view "$release_tag" --repo "$repository" >/dev/null 2>&1; then
  printf '%s\n' "既存のGitHub Release ${release_tag}を再利用します。"
else
  if ! gh release create "$release_tag" \
    --repo "$repository" \
    --verify-tag \
    --title "$release_tag" \
    --generate-notes; then
    gh release view "$release_tag" --repo "$repository" >/dev/null 2>&1 ||
      fail "GitHub Release ${release_tag}を作成できませんでした。"
  fi
  printf '%s\n' "GitHub Release ${release_tag}を作成しました。"
fi
