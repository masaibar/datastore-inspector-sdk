#!/usr/bin/env bash

set -euo pipefail

force_full=false
changed_paths=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    --force-full)
      force_full=true
      shift
      ;;
    --path)
      if [[ $# -lt 2 ]]; then
        echo "--pathには変更pathが必要です。" >&2
        exit 2
      fi
      changed_paths+=("$2")
      shift 2
      ;;
    *)
      echo "不明な引数です: $1" >&2
      exit 2
      ;;
  esac
done

heavy=true
reason="conservative-fallback"

if [[ "$force_full" == true ]]; then
  reason="forced-full"
elif [[ ${#changed_paths[@]} -eq 0 ]]; then
  reason="no-changes"
else
  heavy=false
  reason="docs-only"

  for changed_path in "${changed_paths[@]}"; do
    case "$changed_path" in
      *.md | docs/* | LICENSE | NOTICE)
        ;;
      *)
        heavy=true
        reason="source-or-build"
        break
        ;;
    esac
  done
fi

printf 'heavy=%s\n' "$heavy"
printf 'reason=%s\n' "$reason"
printf 'changed_count=%d\n' "${#changed_paths[@]}"
