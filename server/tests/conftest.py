"""Test client with temp SQLite DB and fixed bearer token."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient

from every_routes_server.app import create_app
from every_routes_server.config import ServerConfig

TEST_TOKEN = "test-token-please-change"


@pytest.fixture()
def client(tmp_path):
    cfg = ServerConfig(data_dir=str(tmp_path / "data"), auth_tokens=[TEST_TOKEN])
    app = create_app(cfg)
    with TestClient(app) as c:
        yield c


@pytest.fixture()
def auth_headers():
    return {"Authorization": f"Bearer {TEST_TOKEN}"}
