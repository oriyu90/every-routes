"""SQLite implementation of the storage interface."""

from __future__ import annotations

import json
import sqlite3
import threading
from datetime import datetime, timezone
from pathlib import Path

SCHEMA = """
CREATE TABLE IF NOT EXISTS routines (
  routine_address TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  last_modified TEXT NOT NULL,
  schema_version INTEGER NOT NULL DEFAULT 1,
  payload TEXT NOT NULL,
  content_hash TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_routines_last_modified ON routines(last_modified);

CREATE TABLE IF NOT EXISTS tasks (
  id TEXT PRIMARY KEY,
  source TEXT NOT NULL,
  external_id TEXT,
  title TEXT NOT NULL,
  at TEXT NOT NULL,
  all_day INTEGER NOT NULL DEFAULT 0,
  status TEXT NOT NULL,
  last_modified TEXT NOT NULL,
  deleted INTEGER NOT NULL DEFAULT 0,
  payload TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_tasks_at ON tasks(at);
CREATE INDEX IF NOT EXISTS idx_tasks_last_modified ON tasks(last_modified);
CREATE UNIQUE INDEX IF NOT EXISTS idx_tasks_source_external ON tasks(source, external_id);

CREATE TABLE IF NOT EXISTS routine_history (
  routine_address TEXT NOT NULL,
  last_modified TEXT NOT NULL,
  payload TEXT NOT NULL,
  replaced_at TEXT NOT NULL
);
"""


def _now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


class SqliteStore:
    """Thread-safe minimal wrapper (one connection + lock; WAL mode)."""

    def __init__(self, db_path: str | Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._lock = threading.Lock()
        self._conn = sqlite3.connect(str(self.db_path), check_same_thread=False)
        self._conn.row_factory = sqlite3.Row
        with self._lock, self._conn:
            self._conn.executescript(SCHEMA)
            self._conn.execute("PRAGMA journal_mode=WAL")

    # -- routines --
    def list_routines(self, *, since: str | None = None, include_deleted: bool = False) -> list[dict]:
        q = "SELECT routine_address, name, last_modified, schema_version, deleted FROM routines WHERE 1=1"
        args: list = []
        if since:
            q += " AND last_modified > ?"
            args.append(since)
        if not include_deleted:
            q += " AND deleted = 0"
        q += " ORDER BY last_modified ASC"
        with self._lock:
            rows = self._conn.execute(q, args).fetchall()
        return [
            {
                "routineAddress": r["routine_address"],
                "name": r["name"],
                "lastModified": r["last_modified"],
                "schemaVersion": r["schema_version"],
                "deleted": bool(r["deleted"]),
            }
            for r in rows
        ]

    def get_routine(self, address: str) -> dict | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM routines WHERE routine_address = ?", (address,)).fetchone()
        if row is None:
            return None
        payload = json.loads(row["payload"])
        payload["deleted"] = bool(row["deleted"])
        return {
            "record": {
                "routine_address": row["routine_address"],
                "name": row["name"],
                "last_modified": row["last_modified"],
                "schema_version": row["schema_version"],
                "content_hash": row["content_hash"],
                "deleted": bool(row["deleted"]),
            },
            "payload": payload,
        }

    def put_routine(self, record: dict) -> None:
        with self._lock, self._conn:
            self._conn.execute(
                """INSERT INTO routines
                   (routine_address, name, last_modified, schema_version, payload, content_hash, deleted, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(routine_address) DO UPDATE SET
                     name=excluded.name, last_modified=excluded.last_modified,
                     schema_version=excluded.schema_version, payload=excluded.payload,
                     content_hash=excluded.content_hash, deleted=excluded.deleted,
                     updated_at=excluded.updated_at""",
                (
                    record["routine_address"],
                    record["name"],
                    record["last_modified"],
                    record.get("schema_version", 1),
                    record["payload"],
                    record["content_hash"],
                    1 if record.get("deleted") else 0,
                    record.get("created_at", _now()),
                    record.get("updated_at", _now()),
                ),
            )

    def delete_routine(self, address: str, *, last_modified: str) -> dict | None:
        existing = self.get_routine(address)
        if existing is None:
            return None
        rec = existing["record"]
        payload = existing["payload"]
        payload["deleted"] = True
        now = last_modified
        with self._lock, self._conn:
            self._conn.execute(
                "UPDATE routines SET deleted=1, last_modified=?, updated_at=? WHERE routine_address=?",
                (now, now, address),
            )
        rec["deleted"] = True
        rec["last_modified"] = now
        return {"record": rec, "payload": payload}

    def archive_history(self, address: str, last_modified: str, payload: dict) -> None:
        with self._lock, self._conn:
            self._conn.execute(
                "INSERT INTO routine_history (routine_address, last_modified, payload, replaced_at) VALUES (?, ?, ?, ?)",
                (address, last_modified, json.dumps(payload, ensure_ascii=False), _now()),
            )

    # -- tasks --
    def list_tasks(self, *, since=None, source=None, include_deleted=True) -> list[dict]:
        q = "SELECT payload, deleted FROM tasks WHERE 1=1"
        args: list = []
        if since:
            q += " AND last_modified > ?"
            args.append(since)
        if source:
            q += " AND source = ?"
            args.append(source)
        if not include_deleted:
            q += " AND deleted = 0"
        q += " ORDER BY last_modified ASC"
        with self._lock:
            rows = self._conn.execute(q, args).fetchall()
        out = []
        for r in rows:
            p = json.loads(r["payload"])
            p["deleted"] = bool(r["deleted"])
            out.append(p)
        return out

    def get_task(self, task_id: str) -> dict | None:
        with self._lock:
            row = self._conn.execute("SELECT * FROM tasks WHERE id = ?", (task_id,)).fetchone()
        if row is None:
            return None
        payload = json.loads(row["payload"])
        payload["deleted"] = bool(row["deleted"])
        return {"record": dict(row), "payload": payload}

    def find_by_external(self, source: str, external_id: str) -> dict | None:
        with self._lock:
            row = self._conn.execute(
                "SELECT * FROM tasks WHERE source = ? AND external_id = ?", (source, external_id)
            ).fetchone()
        if row is None:
            return None
        payload = json.loads(row["payload"])
        payload["deleted"] = bool(row["deleted"])
        return {"record": dict(row), "payload": payload}

    def put_task(self, record: dict) -> None:
        with self._lock, self._conn:
            self._conn.execute(
                """INSERT INTO tasks
                   (id, source, external_id, title, at, all_day, status, last_modified, deleted, payload, created_at, updated_at)
                   VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                   ON CONFLICT(id) DO UPDATE SET
                     source=excluded.source, external_id=excluded.external_id, title=excluded.title,
                     at=excluded.at, all_day=excluded.all_day, status=excluded.status,
                     last_modified=excluded.last_modified, deleted=excluded.deleted,
                     payload=excluded.payload, updated_at=excluded.updated_at""",
                (
                    record["id"],
                    record["source"],
                    record.get("external_id"),
                    record["title"],
                    record["at"],
                    1 if record.get("all_day") else 0,
                    record["status"],
                    record["last_modified"],
                    1 if record.get("deleted") else 0,
                    record["payload"],
                    record.get("created_at", _now()),
                    record.get("updated_at", _now()),
                ),
            )

    def delete_task(self, task_id: str, *, last_modified: str) -> dict | None:
        existing = self.get_task(task_id)
        if existing is None:
            return None
        payload = existing["payload"]
        payload["deleted"] = True
        payload["lastModified"] = last_modified
        rec = existing["record"]
        with self._lock, self._conn:
            self._conn.execute(
                "UPDATE tasks SET deleted=1, last_modified=?, updated_at=?, payload=? WHERE id=?",
                (last_modified, last_modified, json.dumps(payload, ensure_ascii=False), task_id),
            )
        return {"record": rec, "payload": payload}

    def purge_tombstones(self, *, older_than: str) -> int:
        with self._lock, self._conn:
            cur1 = self._conn.execute(
                "DELETE FROM routines WHERE deleted=1 AND last_modified < ?", (older_than,)
            )
            n1 = cur1.rowcount or 0
            cur2 = self._conn.execute(
                "DELETE FROM tasks WHERE deleted=1 AND last_modified < ?", (older_than,)
            )
            n2 = cur2.rowcount or 0
        return n1 + n2
