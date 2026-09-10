"""Routines CRUD (design 01 §4.2). All routes require auth (applied in app.py)."""

from __future__ import annotations

import json

from fastapi import APIRouter, Query, Request
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from ..domain.conflict import decide_write, utcnow_rfc3339
from ..domain.hashing import content_hash
from ..domain.models import RoutineProfile

router = APIRouter()


def _store(request: Request):
    return request.app.state.store


def _err(code: str, message: str, status: int) -> JSONResponse:
    return JSONResponse(status_code=status, content={"error": {"code": code, "message": message}})


@router.get("")
def list_routines(
    request: Request,
    since: str | None = Query(default=None),
    includeDeleted: bool = Query(default=False),
):
    items = _store(request).list_routines(since=since, include_deleted=includeDeleted)
    return {"routines": items}


@router.get("/{routine_address}")
def get_routine(request: Request, routine_address: str):
    found = _store(request).get_routine(routine_address)
    if found is None or found["record"]["deleted"]:
        return _err("not_found", "routine not found", 404)
    return found["payload"]


@router.put("/{routine_address}")
def put_routine(request: Request, routine_address: str, force: bool = Query(default=False)):
    body = request.state.json_body if hasattr(request.state, "json_body") else None
    if body is None:
        return _err("bad_request", "invalid JSON body", 400)
    if body.get("routineAddress") != routine_address:
        return _err("address_mismatch", "path and body routineAddress must match", 400)
    try:
        RoutineProfile.model_validate(body)
    except ValidationError as exc:
        return _err("invalid_payload", f"routine validation failed: {exc.errors()[:1]}", 400)

    store = _store(request)
    existing = store.get_routine(routine_address)
    now = utcnow_rfc3339()

    if existing is None or existing["record"]["deleted"]:
        # (Re)create. If it was a tombstone, treat as update path only when
        # client explicitly forces or is newer; simplest: recreate fresh.
        payload = dict(body)
        payload["lastModified"] = now
        payload.pop("deleted", None)
        record = {
            "routine_address": routine_address,
            "name": payload.get("name", ""),
            "last_modified": now,
            "schema_version": int(payload.get("schemaVersion", 1)),
            "payload": json.dumps(payload, ensure_ascii=False),
            "content_hash": content_hash(payload),
            "deleted": False,
            "updated_at": now,
        }
        if existing is not None:  # was tombstone: archive + overwrite
            store.archive_history(routine_address, existing["record"]["last_modified"], existing["payload"])
        else:
            record["created_at"] = now
        store.put_routine(record)
        status = 201 if existing is None else 200
        return JSONResponse(status_code=status, content=payload)

    stored_payload: dict = existing["payload"]
    stored_lm: str = existing["record"]["last_modified"]
    incoming_lm: str = str(body.get("lastModified", stored_lm))
    decision = decide_write(
        incoming_last_modified=incoming_lm,
        stored_last_modified=stored_lm,
        incoming_payload=body,
        stored_payload=stored_payload,
        force=force,
    )
    if decision == "conflict":
        return JSONResponse(status_code=409, content=stored_payload)
    if decision == "noop":
        return JSONResponse(status_code=200, content=stored_payload)

    store.archive_history(routine_address, stored_lm, stored_payload)
    payload = dict(body)
    payload["lastModified"] = now
    payload.pop("deleted", None)
    store.put_routine(
        {
            "routine_address": routine_address,
            "name": payload.get("name", ""),
            "last_modified": now,
            "schema_version": int(payload.get("schemaVersion", 1)),
            "payload": json.dumps(payload, ensure_ascii=False),
            "content_hash": content_hash(payload),
            "deleted": False,
            "updated_at": now,
        }
    )
    return JSONResponse(status_code=200, content=payload)


@router.delete("/{routine_address}")
def delete_routine(request: Request, routine_address: str):
    store = _store(request)
    existing = store.get_routine(routine_address)
    if existing is None or existing["record"]["deleted"]:
        return _err("not_found", "routine not found", 404)
    now = utcnow_rfc3339()
    store.archive_history(routine_address, existing["record"]["last_modified"], existing["payload"])
    store.delete_routine(routine_address, last_modified=now)
    return {"routineAddress": routine_address, "deleted": True, "lastModified": now}
