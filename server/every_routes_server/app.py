"""ASGI app factory (FastAPI). Design 01 §2-§4, §8."""

from __future__ import annotations

import json
import time
from collections import defaultdict
from pathlib import Path

from fastapi import Depends, FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .api import meta, routines, tasks
from .auth import require_auth
from .config import ServerConfig
from .storage.sqlite import SqliteStore


def create_app(config: ServerConfig, *, store: SqliteStore | None = None) -> FastAPI:
    app = FastAPI(title="Every routes server", version="1.0.0")
    app.state.config = config
    app.state.store = store or SqliteStore(Path(config.data_dir) / "every-routes.db")

    if config.cors_allow_origins:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=config.cors_allow_origins,
            allow_methods=["*"],
            allow_headers=["*"],
        )

    # -- body-size guard + JSON pre-parse (so routers see request.state.json_body) --
    @app.middleware("http")
    async def guards(request: Request, call_next):
        if request.url.path.startswith("/api/"):
            clen = request.headers.get("content-length")
            if clen and clen.isdigit() and int(clen) > config.max_request_body_bytes:
                return JSONResponse(
                    status_code=413,
                    content={"error": {"code": "payload_too_large", "message": "request body too large"}},
                )
            # Simple per-process rate limit (per client IP, sliding minute window).
            now = time.time()
            bucket = app.state._rate_buckets.setdefault(request.client.host if request.client else "?", [])
            cutoff = now - 60.0
            while bucket and bucket[0] < cutoff:
                bucket.pop(0)
            if len(bucket) >= config.rate_limit_per_minute:
                return JSONResponse(
                    status_code=429,
                    content={"error": {"code": "rate_limited", "message": "too many requests"}},
                )
            bucket.append(now)

            if request.method in ("PUT", "POST", "PATCH"):
                raw = await request.body()
                if len(raw) > config.max_request_body_bytes:
                    return JSONResponse(
                        status_code=413,
                        content={"error": {"code": "payload_too_large", "message": "request body too large"}},
                    )
                if raw:
                    try:
                        request.state.json_body = json.loads(raw.decode("utf-8"))
                    except Exception:
                        return JSONResponse(
                            status_code=400,
                            content={"error": {"code": "bad_request", "message": "invalid JSON body"}},
                        )
                else:
                    request.state.json_body = {}
        start = time.time()
        response = await call_next(request)
        # Minimal access log (no tokens).
        elapsed_ms = (time.time() - start) * 1000
        print(f"{request.method} {request.url.path} -> {response.status_code} ({elapsed_ms:.1f}ms)", flush=True)
        return response

    app.state._rate_buckets: dict[str, list[float]] = defaultdict(list)

    app.include_router(meta.router, prefix="/api/v1")
    app.include_router(
        routines.router, prefix="/api/v1/routines", dependencies=[Depends(require_auth)]
    )
    app.include_router(tasks.router, prefix="/api/v1/tasks", dependencies=[Depends(require_auth)])

    return app
