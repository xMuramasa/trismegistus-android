#!/usr/bin/env bash
# Copy the design tokens from trismegistus-mac (the owner of tokens.json) and the Endfield palette it references,
# then write design/TOKENS_SOURCE (sha256 of both). A test in the app fails when TOKENS_SOURCE doesn't match the
# copies, and the generator's check fails when the generated Kotlin is older than the copies.
#
#   scripts/sync-tokens.sh [trismegistus-mac dir] [endfield-dotfiles dir]
set -euo pipefail
mac="${1:-$HOME/Projects/trismegistus-mac}"
endfield="${2:-$HOME/Projects/endfield-dotfiles}"
dst="$(cd "$(dirname "$0")/.." && pwd)/design"
[ -f "$mac/design/tokens.json" ] || { echo "no tokens at $mac/design/tokens.json" >&2; exit 1; }
[ -f "$endfield/theme/palette.json" ] || { echo "no palette at $endfield/theme/palette.json" >&2; exit 1; }
cp "$mac/design/tokens.json" "$dst/tokens.json"
cp "$endfield/theme/palette.json" "$dst/endfield-palette.json"
sha() { if command -v sha256sum >/dev/null; then sha256sum "$@"; else shasum -a 256 "$@"; fi; }
{
  echo "tokens.json $(sha "$dst/tokens.json" | cut -d' ' -f1) trismegistus-mac@$(git -C "$mac" rev-parse --short HEAD)"
  echo "endfield-palette.json $(sha "$dst/endfield-palette.json" | cut -d' ' -f1) endfield-dotfiles@$(git -C "$endfield" rev-parse --short HEAD)"
} > "$dst/TOKENS_SOURCE"
echo "tokens synced"; cat "$dst/TOKENS_SOURCE"
