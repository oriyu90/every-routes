"""CLI: every-routes-server --config ./every-routes.yaml [--data-dir ...] [--port ...]."""

from __future__ import annotations

import argparse

from .config import load_config


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="every-routes-server", description="Every routes server (shared hub)")
    p.add_argument("--config", default=None, help="Path to YAML config file")
    p.add_argument("--data-dir", default=None, help="SQLite + logs location (overrides config)")
    p.add_argument("--port", type=int, default=None, help="TCP port (overrides config)")
    p.add_argument("--bind", default=None, help="Bind address (overrides config)")
    return p


def main(argv: list[str] | None = None) -> None:
    args = build_parser().parse_args(argv)
    overrides = {"data_dir": args.data_dir, "port": args.port, "bind": args.bind}
    cfg = load_config(args.config, overrides=overrides)

    from .app import create_app

    app = create_app(cfg)

    import uvicorn

    ssl_cert = cfg.tls.cert_file
    ssl_key = cfg.tls.key_file
    uvicorn.run(
        app,
        host=cfg.bind,
        port=cfg.port,
        ssl_certfile=ssl_cert,
        ssl_keyfile=ssl_key,
        log_level="info",
    )


if __name__ == "__main__":  # pragma: no cover
    main()
