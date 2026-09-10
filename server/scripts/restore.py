#!/usr/bin/env python3
"""Restore a dump.py backup with ?force=true (stdlib only)."""

from __future__ import annotations

import argparse
import json
import urllib.request


def _put(base: str, token: str, path: str, payload: dict) -> int:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + path,
        data=data,
        method="PUT",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as res:
        return res.status


def _post(base: str, token: str, path: str, payload: dict) -> int:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        base.rstrip("/") + path,
        data=data,
        method="POST",
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as res:
        return res.status


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--in", dest="inp", required=True)
    args = ap.parse_args()

    backup = json.loads(open(args.inp, encoding="utf-8").read())
    n = 0
    for routine in backup.get("routines", []):
        _put(args.base_url, args.token, f"/api/v1/routines/{routine['routineAddress']}?force=true", routine)
        n += 1
    m = 0
    for task in backup.get("tasks", []):
        if task.get("deleted"):
            continue
        _post(args.base_url, args.token, "/api/v1/tasks", task)
        m += 1
    print(f"restored {n} routines, {m} tasks")


if __name__ == "__main__":
    main()
