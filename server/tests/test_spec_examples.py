"""spec/examples round-trip: upload each example through the real API."""

from __future__ import annotations

import json
from pathlib import Path

SPEC = Path(__file__).resolve().parents[2] / "spec" / "examples"


def test_routine_examples_roundtrip(client, auth_headers):
    for name in ("routine-weekday.json", "routine-holiday.json"):
        example = json.loads((SPEC / name).read_text(encoding="utf-8"))
        addr = example["routineAddress"]
        r = client.put(f"/api/v1/routines/{addr}", json=example, headers=auth_headers)
        assert r.status_code in (200, 201), f"{name}: {r.text}"
        fetched = client.get(f"/api/v1/routines/{addr}", headers=auth_headers)
        assert fetched.status_code == 200
        assert fetched.json()["routineAddress"] == addr
        assert fetched.json()["name"] == example["name"]


def test_task_examples_roundtrip(client, auth_headers):
    for name in ("task-agent.json", "task-google.json"):
        example = json.loads((SPEC / name).read_text(encoding="utf-8"))
        r = client.post(
            "/api/v1/tasks",
            json={
                "id": example["id"],
                "source": example["source"],
                "externalId": example.get("externalId"),
                "title": example["title"],
                "at": example["at"],
                "allDay": example["allDay"],
                "status": example["status"],
            },
            headers=auth_headers,
        )
        assert r.status_code in (200, 201), f"{name}: {r.text}"
        assert r.json()["title"] == example["title"]
