#!/usr/bin/env bash
# Generate a minimal HTML doc from spec/openapi.yaml (no extra deps).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IN="$ROOT/spec/openapi.yaml"
OUT="${1:-$ROOT/spec/openapi.html}"
python3 - "$IN" "$OUT" <<'PY'
import html, sys
src, out = sys.argv[1], sys.argv[2]
text = open(src, encoding="utf-8").read()
page = f"""<!doctype html><meta charset="utf-8"><title>Every routes API</title>
<h1>Every routes API</h1><p>Source: <code>spec/openapi.yaml</code></p>
<pre>{html.escape(text)}</pre>"""
open(out, "w", encoding="utf-8").write(page)
print(f"wrote {out}")
PY
