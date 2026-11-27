#!/usr/bin/env bash
set -euo pipefail

app=${1:?Pass banama or launcher}
case "$app" in
  banama) app_dir=Banama ;;
  launcher) app_dir=Launcher ;;
  *) printf 'Unknown app: %s\n' "$app" >&2; exit 1 ;;
esac

target=$(git rev-parse --verify "${2:-HEAD}^{commit}")
range=$target
if previous=$(git describe --tags --first-parent --abbrev=0 --match "$app-*" --exclude "$app-$target" "$target" 2>/dev/null); then
  range="$previous..$target"
fi

merge_pr='^Merge pull request #([0-9]+) from '
changes=$(
  git log --first-parent -z --format='%s%x00%b' "$range" -- "$app_dir/" ':(top,glob)*' \
    ".github/workflows/release-$app.yml" .github/release-notes.sh |
  while IFS= read -r -d '' subject && IFS= read -r -d '' body; do
    if [[ "$subject" =~ $merge_pr && -n "$body" ]]; then
      printf -- '- %s (#%s)\n' "${body%%$'\n'*}" "${BASH_REMATCH[1]}"
    else
      printf -- '- %s\n' "$subject"
    fi
  done
)

printf '## Changes\n\n%s\n' "${changes:-- No changes since the previous release.}"
