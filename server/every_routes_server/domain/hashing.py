"""content_hash for conflict detection (00 §6.2 rule 2)."""

from __future__ import annotations

import hashlib
import json


def content_hash(payload: dict) -> str:
    canonical = json.dumps(payload, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return hashlib.sha256(canonical.encode("utf-8")).hexdigest()
