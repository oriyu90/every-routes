#!/usr/bin/env python3
"""Validate spec/examples against spec/schemas, and lint openapi.yaml.

Works without third-party packages: uses jsonschema when available (full
draft-07 validation, incl. format checks) and falls back to a lightweight
built-in validator otherwise (types + enums). Returns non-zero on failure.

This is the single source of truth for spec self-consistency and runs in CI
(spec-validate.yml) on every PR. It is also invoked by validate-spec.sh when uv/jsonschema
is present for a stricter check, but this file is the canonical validator.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SPEC_DIR = ROOT / "spec"


def _try_jsonschema():
    try:
        import jsonschema  # type: ignore

        return jsonschema
    except Exception:
        return None


def _type_ok(value, t):
    if isinstance(t, list):
        return any(_type_ok(value, x) for x in t)
    if value is None:
        return False  # null only allowed via explicit "null" type in schema list
    if t == "object":
        return isinstance(value, dict)
    if t == "array":
        return isinstance(value, list)
    if t == "string":
        return isinstance(value, str)
    if t in ("integer",):
        # bool is a subclass of int; exclude it.
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if t == "number":
        return isinstance(value, (int, float)) and not isinstance(value, bool)
    if t == "boolean":
        return isinstance(value, bool)
    return True


def _validate_node(node: dict, value):
    """Lightweight draft-07 subset validator. Returns list of error strings."""
    errors = []

    t = node.get("type")
    if value is None:
        # Allow null only when the schema type list includes "null".
        if isinstance(t, str) and t != "null":
            errors.append(f"expected type {t}, got null")
        elif isinstance(t, list) and "null" not in t:
            errors.append(f"type {t} does not allow null")
    elif _type_ok(value, t):
        pass
    else:
        errors.append(f"expected type {t}, got {type(value).__name__}")

    if "enum" in node and value not in node["enum"]:
        errors.append(f"value {value!r} not in enum {node['enum']}")

    if "pattern" in node and isinstance(value, str):
        if not re.search(node["pattern"], value):  # noqa: F841 (search semantics)
            if not re.fullmatch(node["pattern"], value):
                errors.append(f"value {value!r} does not match pattern {node['pattern']!r}")

    if isinstance(value, dict):
        props = node.get("properties", {}) or {}
        for key in value:  # extra keys allowed (server keeps payload verbatim)
            pass
        for req in node.get("required", []) or []:
            if req not in value:
                errors.append(f"missing required key {req!r}")
        for k, v in value.items():
            if k in props:
                errors.extend(f"{k}: {e}" for e in _validate_node(props[k], v))

    if isinstance(value, list) and "items" in node:
        for i, item in enumerate(value):
            errors.extend(f"[{i}]: {e}" for e in _validate_node(node["items"], item))

    return errors


def validate_examples():
    failures = []
    js = _try_jsonschema()

    schemas_dir = SPEC_DIR / "schemas"
    examples_dir = SPEC_DIR / "examples"

    schema_map = {p.stem: json.loads(p.read_text(encoding="utf-8")) for p in schemas_dir.glob("*.schema.json")}

    # Map each example to the schema it should satisfy.
    mapping = {
        "routine-profile": ("RoutineProfile", ["routine-weekday.json", "routine-holiday.json"]),
        "task": ("Task", [p.name for p in examples_dir.glob("*.json") if not p.stem.startswith("routine-")]),
    }

    for schema_key, (title, example_files) in mapping.items():
        if title not in {s.get("title") for s in schema_map.values()}:
            failures.append(f"schema {schema_key!r} not found with title {title!r}")
            continue
        schema = next(s for s in schema_map.values() if s.get("title") == title)
        validator = None
        if js is not None:
            try:
                fmt_checker = getattr(js, "Draft7Validator", None)

                def _is_iso(s):
                    try:
                        from datetime import datetime

                        datetime.fromisoformat(str(s).replace("Z", "+00:00"))
                        return True
                    except Exception:
                        return False

                if fmt_checker is not None:
                    validator = js.Draft7Validator(schema, format_checker=js.FormatChecker())

            except Exception:
                validator = None

        for name in example_files:
            path = examples_dir / name
            if not path.exists():
                failures.append(f"example {name!r} missing")
                continue
            data = json.loads(path.read_text(encoding="utf-8"))

            if validator is not None:
                for err in sorted(validator.iter_errors(data), key=lambda e: list(e.path)):
                    loc = ".".join(str(p) for p in err.absolute_path) or "<root>"
                    failures.append(f"{name}: {loc}: {err.message}")
            else:
                for e in _validate_node(schema, data):
                    failures.append(f"{name}: {e}")

    return failures


def lint_openapi():
    """Cheap structural checks on openapi.yaml (no yaml dep required)."""

    failures = []
    path = SPEC_DIR / "openapi.yaml"
    if not path.exists():
        return ["spec/openapi.yaml missing"]

    text = path.read_text(encoding="utf-8")
    if "openapi:" not in text:
        failures.append("openapi.yaml missing 'openapi:' version field")

    try:
        import yaml  # type: ignore

        doc = yaml.safe_load(text) or {}
    except Exception as exc:  # pragma: no cover - optional dep
        return [f"openapi.yaml could not be parsed ({exc})"]

    if doc.get("info", {}).get("version") != "1.0.0":
        failures.append(f"openapi info.version is {doc.get('info', {}).get('version')!r}, expected '1.0.0'")

    paths = doc.get("paths", {}) or {}
    expected_paths = {"/health", "/info", "/routines", "/routines/{routineAddress}", "/tasks", "/tasks/{id}"}
    missing = expected_paths - set(paths)
    if missing:
        failures.append(f"openapi.yaml missing paths {sorted(missing)}")

    routines = paths.get("/routines", {})
    if not (isinstance(routines, dict) and "get" in routines):
        failures.append("openapi.yaml /routines GET missing")

    return failures


def main() -> int:
    all_failures = []

    ex = validate_examples()
    if ex:
        print("== spec/examples validation ==")

    all_failures.extend(ex)

    oa = lint_openapi()
    if ex or oa:
        print("== spec/openapi.yaml ==")

    all_failures.extend(oa)

    if not (ex or oa):
        print("spec: all examples valid, openapi.yaml OK")

    for f in all_failures:
        print(f"  FAIL {f}", file=sys.stderr)

    if all_failures:
        print(f"\n{len(all_failures)} spec problem(s) found", file=sys.stderr)
        return 1

    print("spec OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
