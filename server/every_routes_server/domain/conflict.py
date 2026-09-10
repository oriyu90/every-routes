"""last-write-wins decision (00 §6.2, 01 §6)."""

from __future__ import annotations

from datetime import datetime, timezone

from .hashing import content_hash


def parse_rfc3339(value: str) -> datetime:
    dt = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if dt.tzinfo is None:
        dt = dt.replace(tzinfo=timezone.utc)
    return dt.astimezone(timezone.utc)


def utcnow_rfc3339() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def decide_write(
    *,
    incoming_last_modified: str,
    stored_last_modified: str,
    incoming_payload: dict,
    stored_payload: dict,
    force: bool = False,
) -> str:
    """Return 'write' | 'noop' | 'conflict'."""
    if force:
        return "write"
    try:
        incoming_dt = parse_rfc3339(incoming_last_modified)
        stored_dt = parse_rfc3339(stored_last_modified)
    except Exception:
        return "conflict"
    if incoming_dt > stored_dt:
        return "write"
    if incoming_dt < stored_dt:
        return "conflict"
    # Same timestamp: compare content hash; server wins on mismatch.
    if content_hash(incoming_payload) == content_hash(stored_payload):
        return "noop"
    return "conflict"
