"""Tombstone GC (design 01 §5, §8). Physically purges old tombstones."""

from __future__ import annotations

from datetime import datetime, timedelta, timezone


def gc_cutoff(*, days: int) -> str:
    cutoff = datetime.now(timezone.utc) - timedelta(days=days)
    return cutoff.isoformat().replace("+00:00", "Z")


def purge_old_tombstones(store, *, days: int) -> int:
    return store.purge_tombstones(older_than=gc_cutoff(days=days))
