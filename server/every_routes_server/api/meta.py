"""GET /health (open), GET /info (auth). Design 01 §4.1."""

from __future__ import annotations

from datetime import datetime, timezone

from fastapi import APIRouter, Depends, Request

from ..auth import require_auth

router = APIRouter()


def _utcnow() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


@router.get("/health")
def health():
    return {"status": "ok"}


@router.get("/info", dependencies=[Depends(require_auth)])
def info(request: Request):
    cfg = request.app.state.config
    return {
        "timezone": cfg.timezone,
        "holidayRegion": cfg.holiday_region,
        "schemaVersions": {"routine": 1, "task": 1},
        "serverTime": _utcnow(),
    }
