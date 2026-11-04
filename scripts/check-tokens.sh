#!/usr/bin/env bash
# Design-token guard, after trismegistus-mac's. Run from anywhere; exits non-zero when
#   (a) ui/theme/Tokens.kt or Glyphs.kt differs from what scripts/gen-tokens.py makes of design/ now, or
#   (b) a Kotlin file under ui/ outside ui/theme writes a raw colour (`Color(0x…)`) or a raw `.dp` / `.sp` literal.
#       0 and 1 (the hairline) are allowed. Files listed in scripts/token-allowlist.txt (one path from the repo root
#       per line, then `#` and why) are skipped.
set -euo pipefail
cd "$(dirname "$0")/.."
theme=app/src/main/kotlin/dev/xmuramasa/trismegistus/ui/theme
status=0

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
python3 -I scripts/gen-tokens.py "$tmp" > /dev/null
for f in Tokens.kt Glyphs.kt; do
    if ! diff -u "$theme/$f" "$tmp/$f"; then
        echo "stale: $theme/$f (run python3 scripts/gen-tokens.py)"
        status=1
    fi
done

python3 -I - scripts/token-allowlist.txt "$theme" <<'EOF' || status=1
import pathlib, re, sys

allow_file, theme = pathlib.Path(sys.argv[1]), sys.argv[2]
allowed = set()
if allow_file.exists():
    for line in allow_file.read_text().splitlines():
        path = line.split("#")[0].strip()
        if path:
            allowed.add(path)
patterns = [
    ("raw colour", re.compile(r"\bColor\(\s*(0x[0-9A-Fa-f]+)")),
    ("raw size", re.compile(r"(?<![\w.])(\d+(?:\.\d+)?)f?\.(?:dp|sp)\b")),
]
found = 0
ui = pathlib.Path(theme).parent
for path in sorted(ui.rglob("*.kt")):
    p = str(path)
    if p.startswith(theme + "/") or p in allowed:
        continue
    for n, line in enumerate(path.read_text().splitlines(), 1):
        code = re.sub(r'"(?:[^"\\]|\\.)*"', '""', line).split("//")[0]  # strings and comments aside
        for kind, rx in patterns:
            for m in rx.finditer(code):
                if kind == "raw size" and float(m.group(1)) in (0, 1):
                    continue
                print(f"{p}:{n}: {kind} {m.group(1)}: {line.strip()}")
                found += 1
if found:
    print(f"{found} raw literals: use the tokens in {theme}/Tokens.kt (design/)")
    sys.exit(1)
EOF
exit $status
