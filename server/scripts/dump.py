#!/usr/bin/env python3
"""Dump all routines + tasks to JSON (backup). Uses only stdlib."""

from __future__ import annotations

import argparse
import json
import urllib.request


def _get(base: str, token: str, path: str) -> dict:
    req = urllib.request.Request(base.rstrip("/") + path, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(req) as res:
        return json.loads(res.read().decode("utf-8"))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--out", required=True)
    args = ap.parse_args()

    routines = _get(args.base_url, args.token, "/api/v1/routines?includeDeleted=true")["routines"]
    full = []
    for meta in routines:
        if meta.get("deleted"):
            continue
        full.append(_get(args.base_url, args.token, f"/api/v1/routines/{meta['routineAddress']}"))
    tasks = _get(args.base_url, args.token, "/api/v1/tasks?from=2000-01-01&to=2100-01-01")["tasks"]
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump({"routines": full, "tasks": tasks}, f, ensure_ascii=False, indent=2)
    print(f"dumped {len(full)} routines, {len(tasks)} tasks -> {args.out}")


if __name__ == "__main__":
    main()
