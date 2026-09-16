#!/usr/bin/env bash
# Every relative link in the repository's markdown must point at a file that exists.
#
# The `docs` job of ci.yml runs this on a documentation-only PR, where it is the whole run: no toolchain, no cache,
# a second of runner time. URLs, anchors and mailto: links are left alone — only paths are checked, with any
# `#anchor` suffix trimmed off first. Written for bash 3.2 as well (no `mapfile`), so it also runs on a Mac.
set -euo pipefail

broken=$(mktemp)
trap 'rm -f "$broken"' EXIT

git ls-files '*.md' | while IFS= read -r file; do
  dir=$(dirname "$file")
  # ](target) — the closing half of a markdown link; images share the shape, so they are checked too.
  { grep -oE '\]\([^)]+\)' "$file" || true; } | sed -E 's/^\]\(//; s/\)$//' | while IFS= read -r target; do
    case "$target" in
      http://* | https://* | mailto:* | \#* | '') continue ;;
    esac
    path=${target%%#*} # drop an #anchor
    path=${path%% *}   # drop a "title" after the path
    [ -n "$path" ] || continue
    if [ ! -e "$dir/$path" ]; then
      echo "::error file=$file::broken relative link: $target"
      echo "$file $target" >>"$broken"
    fi
  done
done

count=$(wc -l <"$broken" | tr -d ' ')
if [ "$count" -ne 0 ]; then
  echo "$count relative link(s) do not resolve."
  exit 1
fi
echo "All relative markdown links resolve."
