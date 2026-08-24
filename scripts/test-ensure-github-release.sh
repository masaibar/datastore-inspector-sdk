#!/usr/bin/env bash

set -euo pipefail

readonly script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly release_script="$script_dir/ensure-github-release.sh"
readonly temp_root="$(mktemp -d)"

cleanup() {
  rm -rf "$temp_root"
}
trap cleanup EXIT

readonly origin_repo="$temp_root/origin.git"
readonly work_repo="$temp_root/work"
readonly fake_bin="$temp_root/bin"
readonly gh_state="$temp_root/gh-state"
readonly gh_log="$temp_root/gh-log"

git init --bare "$origin_repo" >/dev/null
git init -b main "$work_repo" >/dev/null
mkdir -p "$work_repo/gradle" "$fake_bin"

printf '%s\n' 'version=0.2.0' > "$work_repo/gradle/artifact-coordinates.properties"
git -C "$work_repo" add gradle/artifact-coordinates.properties
git -C "$work_repo" \
  -c user.name='Release Test' \
  -c user.email='release-test@example.invalid' \
  commit -m 'Prepare release' >/dev/null
git -C "$work_repo" remote add origin "$origin_repo"
git -C "$work_repo" push -u origin main >/dev/null

cat > "$fake_bin/gh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail

if [[ "$1" == "release" && "$2" == "view" ]]; then
  [[ -f "$FAKE_GH_STATE" ]]
  exit
fi

if [[ "$1" == "release" && "$2" == "create" ]]; then
  printf '%s\n' "$*" >> "$FAKE_GH_LOG"
  touch "$FAKE_GH_STATE"
  exit 0
fi

printf 'unexpected gh command: %s\n' "$*" >&2
exit 2
EOF
chmod +x "$fake_bin/gh"

export PATH="$fake_bin:$PATH"
export FAKE_GH_STATE="$gh_state"
export FAKE_GH_LOG="$gh_log"

readonly first_commit="$(git -C "$work_repo" rev-parse HEAD)"

(
  cd "$work_repo"
  "$release_script" \
    --version 0.2.0 \
    --commit "$first_commit" \
    --repository example/sdk
)

readonly tag_object_type="$(git --git-dir="$origin_repo" cat-file -t refs/tags/v0.2.0)"
readonly tagged_commit="$(git --git-dir="$origin_repo" rev-parse 'refs/tags/v0.2.0^{}')"
[[ "$tag_object_type" == "tag" ]] || {
  echo "release tagがannotated tagではありません。" >&2
  exit 1
}
[[ "$tagged_commit" == "$first_commit" ]] || {
  echo "release tagが指定commitを指していません。" >&2
  exit 1
}
[[ "$(wc -l < "$gh_log" | tr -d ' ')" == "1" ]] || {
  echo "GitHub Releaseの作成回数が一致しません。" >&2
  exit 1
}

(
  cd "$work_repo"
  "$release_script" \
    --version 0.2.0 \
    --commit "$first_commit" \
    --repository example/sdk
)

[[ "$(wc -l < "$gh_log" | tr -d ' ')" == "1" ]] || {
  echo "再実行でGitHub Releaseを重複作成しました。" >&2
  exit 1
}

printf '%s\n' 'new content' > "$work_repo/changed.txt"
git -C "$work_repo" add changed.txt
git -C "$work_repo" \
  -c user.name='Release Test' \
  -c user.email='release-test@example.invalid' \
  commit -m 'Advance main' >/dev/null
readonly second_commit="$(git -C "$work_repo" rev-parse HEAD)"

if (
  cd "$work_repo"
  "$release_script" \
    --version 0.2.0 \
    --commit "$second_commit" \
    --repository example/sdk
) >/dev/null 2>&1; then
  echo "既存tagの別commitへの移動を許可してしまいました。" >&2
  exit 1
fi

if (
  cd "$work_repo"
  "$release_script" \
    --version 0.2.1 \
    --commit "$second_commit" \
    --repository example/sdk
) >/dev/null 2>&1; then
  echo "正本と異なるversionを許可してしまいました。" >&2
  exit 1
fi

printf '%s\n' 'GitHub release tests passed: 4 cases'
