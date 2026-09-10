#!/usr/bin/env bash
# Validate spec/examples against schemas and lint openapi.yaml. Delegates to the
# Python validator (tools/validate-spec.py). Setups a minimal venv with jsonschema+pyyaml
# when available for the strictest checks; otherwise falls back to a dependency-free run.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VALIDATOR="$ROOT/tools/validate-spec.py"

PYTHON_BIN="${PYTHON:-python3}"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
    echo "python3 not found on PATH." >&2
    exit 1
fi

if command -v uv >/dev/null 2>&1; then
    # Strict path: jsonschema + pyyaml installed in a throwaway venv.
    uv run --no-project \
        --with jsonschema==4.* --with pyyaml==6.* \
        python "$VALIDATOR"
    exit $?
fi

# Fallback: run with the system interpreter (no extra deps; validator degrades gracefully).
"$PYTHON_BIN" "$VALIDATOR"
