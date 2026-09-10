"""Bearer token verification (constant-time compare). Never log token values."""

from __future__ import annotations

import hmac

from fastapi import Header, HTTPException, Request


def _unauthorized(message: str) -> HTTPException:
    return HTTPException(status_code=401, detail={"error": {"code": "unauthorized", "message": message}})


def check_token(presented: str, valid_tokens: list[str]) -> None:
    for valid in valid_tokens:
        if hmac.compare_digest(presented, valid):
            return
    raise _unauthorized("invalid token")


async def require_auth(request: Request, authorization: str | None = Header(default=None)) -> None:
    cfg = getattr(request.app.state, "config", None)
    valid_tokens: list[str] = getattr(cfg, "auth_tokens", []) if cfg else []
    if not authorization or not authorization.startswith("Bearer "):
        raise _unauthorized("missing bearer token")
    check_token(authorization[len("Bearer ") :].strip(), valid_tokens)
