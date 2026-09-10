"""Tasks CRUD (design 01 §4.3). All routes require auth (applied in app.py)."""

from __future__ import annotations

import json
import secrets
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse

from ..domain.conflict import decide_write, parse_rfc3339, utcnow_rfc3339

router = APIRouter()

VALID_SOURCES = {"google_tasks", "agent", "manual"}
VALID_STATUS = {"needsAction", "completed"}


def _store(request: Request):
    return request.app.state.store


def _err(code: str, message: str, status: int) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}})


def _task_date_in_tz(at: str, tz: str) -> str | None:
    try:
        dt = datetime.fromisoformat(at.replace("Z", "+00:00"))
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=ZoneInfo(tz))
        return dt.astimezone(ZoneInfo(tz)).date().isoformat()
    except Exception:
        return None


def _default_range(tz: str) -> tuple[str, str]:
    try:
        today = datetime.now(ZoneInfo(tz)).date()
    except Exception:
        today = datetime.now().date()
    return today.isoformat(), (today + timedelta(days=14)).isoformat()


@router.get("")
def list_tasks(
    request: Request,
    from_: str | None = Query(default=None, alias="from"),
    to: str | None = Query(default=None),
    since: str | None = Query(default=None),
    source: str | None = Query(default=None),
    includeDeleted: bool = Query(default=True),
):
    cfg = request.app.state.config
    if source is not None and source not in VALID_SOURCES:
        return _err("bad_request", f"unknown source {source!r}", 400)
    tasks = _store(request).list_tasks(since=since, source=source, include_deleted=includeDeleted)
    if from_ is None or to is None:
        dfrom, dto = _default_range(cfg.timezone)
        from_ = from_ or dfrom
        to = to or dto
    out = []
    for t in tasks:
        day = _task_date_in_tz(str(t.get("at", "")), cfg.timezone)
        if day is None:
            continue
        if from_ <= day <= to:
            out.append(t)
    out.sort(key=lambda t: (str(t.get("at", "")), str(t.get("id", ""))))
    return {"tasks": out}


@router.post("")
def create_task(request: Request):
    body = getattr(request.state, "json_body", None)
    if not isinstance(body, dict):
        return _err("bad_request", "invalid JSON body", 400)
    source = body.get("source")
    title = body.get("title")
    at = body.get("at")
    if source not in VALID_SOURCES:
        return _err("bad_request", "source must be google_tasks|agent|manual", 400)
    if not title or not at:
        return _err("bad_request", "title and at are required", 400)
    try:
        parse_rfc3339(str(at))
    except Exception:
        return _err("bad_request", "at must be RFC3339 date-time", 400)

    store = _store(request)
    now = utcnow_rfc3339()
    external_id = body.get("externalId")

    # Dedupe on (source, externalId).
    if external_id:
        dup = store.find_by_external(str(source), str(external_id))
        if dup is not None and not dup["payload"].get("deleted"):
            payload = dup["payload"]
            for key in ("title", "at", "allDay", "status"):
                if key in body:
                    payload[key] = body[key]
            payload["lastModified"] = now
            store.put_task(
                {
                    "id": payload["id"],
                    "source": payload["source"],
                    "external_id": payload.get("externalId"),
                    "title": payload["title"],
                    "at": payload["at"],
                    "all_day": bool(payload.get("allDay")),
                    "status": payload.get("status", "needsAction"),
                    "last_modified": now,
                    "deleted": False,
                    "payload": json.dumps(payload, ensure_ascii=False),
                    "updated_at": now,
                }
            )
            return JSONResponse(status_code=200, content=payload)

    task_id = str(body.get("id") or f"tsk_{secrets.token_hex(32)}")
    if store.get_task(task_id) is not None:
        return _err("conflict", "task id already exists", 409)
    payload = {
        "schemaVersion": int(body.get("schemaVersion", 1)),
        "id": task_id,
        "source": source,
        "externalId": external_id,
        "title": title,
        "at": at,
        "allDay": bool(body.get("allDay", False)),
        "status": str(body.get("status", "needsAction")),
        "lastModified": now,
        "deleted": False,
    }
    if payload["status"] not in VALID_STATUS:
        return _err("bad_request", "status must be needsAction|completed", 400)
    # Preserve extra fields verbatim.
    for k, v in body.items():
        if k not in payload:
            payload[k] = v
    store.put_task(
        {
            "id": task_id,
            "source": source,
            "external_id": external_id,
            "title": title,
            "at": at,
            "all_day": payload["allDay"],
            "status": payload["status"],
            "last_modified": now,
            "deleted": False,
            "payload": json.dumps(payload, ensure_ascii=False),
            "created_at": now,
            "updated_at": now,
        }
    )
    return JSONResponse(status_code=201, content=payload)


@router.patch("/{task_id}")
def patch_task(request: Request, task_id: str):
    body = getattr(request.state, "json_body", None)
    if not isinstance(body, dict):
        return _err("bad_request", "invalid JSON body", 400)
    store = _store(request)
    found = store.get_task(task_id)
    if found is None or found["payload"].get("deleted"):
        return _err("not_found", "task not found", 404)
    stored_payload: dict = found["payload"]
    stored_lm: str = str(stored_payload.get("lastModified"))
    incoming_lm = str(body.get("lastModified", stored_lm))
    if "lastModified" in body:
        decision = decide_write(
            incoming_last_modified=incoming_lm,
            stored_last_modified=stored_lm,
            incoming_payload={**stored_payload, **{k: v for k, v in body.items() if k != "lastModified"}},
            stored_payload=stored_payload,
        )
        if decision == "conflict":
            return JSONResponse(status_code=409, content=stored_payload)
    now = utcnow_rfc3339()
    for key in ("title", "at", "allDay", "status"):
        if key in body:
            stored_payload[key] = body[key]
    if stored_payload.get("status") not in VALID_STATUS:
        return _err("bad_request", "status must be needsAction|completed", 400)
    if "at" in body:
        try:
            parse_rfc3339(str(stored_payload["at"]))
        except Exception:
            return _err("bad_request", "at must be RFC3339 date-time", 400)
    stored_payload["lastModified"] = now
    store.put_task(
        {
            "id": task_id,
            "source": stored_payload["source"],
            "external_id": stored_payload.get("externalId"),
            "title": stored_payload["title"],
            "at": stored_payload["at"],
            "all_day": bool(stored_payload.get("allDay")),
            "status": stored_payload["status"],
            "last_modified": now,
            "deleted": False,
            "payload": json.dumps(stored_payload, ensure_ascii=False),
            "updated_at": now,
        }
    )
    return JSONResponse(status_code=200, content=stored_payload)


@router.delete("/{task_id}")
def delete_task(request: Request, task_id: str):
    store = _store(request)
    found = store.get_task(task_id)
    if found is None or found["payload"].get("deleted"):
        return _err("not_found", "task not found", 404)
    now = utcnow_rfc3339()
    result = store.delete_task(task_id, last_modified=now)
    assert result is not None
    return {"id": task_id, "deleted": True, "lastModified": now}
