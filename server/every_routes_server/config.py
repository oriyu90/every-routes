"""YAML + env + CLI config loading. Design 01 §2 compliant."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class TlsConfig:
    cert_file: str | None = None
    key_file: str | None = None


@dataclass
class ServerConfig:
    bind: str = "0.0.0.0"
    port: int = 8787
    data_dir: str = "./data"
    timezone: str = "Asia/Tokyo"
    holiday_region: str = "JP"
    auth_tokens: list[str] = field(default_factory=list)
    tls: TlsConfig = field(default_factory=TlsConfig)
    cors_allow_origins: list[str] = field(default_factory=list)
    max_request_body_bytes: int = 1_048_576
    rate_limit_per_minute: int = 300
    tombstone_gc_days: int = 90


def _load_yaml(path: str | Path | None) -> dict:
    if not path:
        return {}
    p = Path(path)
    if not p.exists():
        raise FileNotFoundError(f"config file not found: {p}")
    try:
        import yaml  # type: ignore
    except ImportError as exc:  # pragma: no cover
        raise RuntimeError("pyyaml is required to load --config YAML") from exc
    data = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError("config YAML must be a mapping")
    return data


def load_config(
    config_path: str | Path | None = None,
    *,
    overrides: dict | None = None,
) -> ServerConfig:
    """Merge YAML file < env vars < explicit overrides."""
    raw = _load_yaml(config_path)
    if overrides:
        raw = {**raw, **{k: v for k, v in overrides.items() if v is not None}}

    # Env vars (highest precedence for secrets).
    if os.environ.get("EVERY_ROUTES_BIND"):
        raw["bind"] = os.environ["EVERY_ROUTES_BIND"]
    if os.environ.get("EVERY_ROUTES_PORT"):
        raw["port"] = int(os.environ["EVERY_ROUTES_PORT"])
    if os.environ.get("EVERY_ROUTES_DATA_DIR"):
        raw["data_dir"] = os.environ["EVERY_ROUTES_DATA_DIR"]
    if os.environ.get("EVERY_ROUTES_AUTH_TOKENS"):
        raw["auth_tokens"] = [t.strip() for t in os.environ["EVERY_ROUTES_AUTH_TOKENS"].split(",") if t.strip()]

    tls_raw = raw.get("tls") or {}
    cfg = ServerConfig(
        bind=str(raw.get("bind", "0.0.0.0")),
        port=int(raw.get("port", 8787)),
        data_dir=str(raw.get("data_dir", "./data")),
        timezone=str(raw.get("timezone", "Asia/Tokyo")),
        holiday_region=str(raw.get("holiday_region", "JP")),
        auth_tokens=list(raw.get("auth_tokens", []) or []),
        tls=TlsConfig(
            cert_file=tls_raw.get("cert_file"),
            key_file=tls_raw.get("key_file"),
        ),
        cors_allow_origins=list(raw.get("cors_allow_origins", []) or []),
        max_request_body_bytes=int(raw.get("max_request_body_bytes", 1_048_576)),
        rate_limit_per_minute=int(raw.get("rate_limit_per_minute", 300)),
        tombstone_gc_days=int(raw.get("tombstone_gc_days", 90)),
    )
    if not cfg.auth_tokens:
        raise ValueError("auth_tokens must contain at least one token (config or EVERY_ROUTES_AUTH_TOKENS)")
    return cfg
