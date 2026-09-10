"""GET /health (open), GET /info (auth), 401 without token."""


def test_health_open(client):
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_health_with_token_also_ok(client, auth_headers):
    r = client.get("/api/v1/health", headers=auth_headers)
    assert r.status_code == 200


def test_info_requires_auth(client):
    assert client.get("/api/v1/info").status_code == 401


def test_info_with_auth(client, auth_headers):
    r = client.get("/api/v1/info", headers=auth_headers)
    assert r.status_code == 200
    body = r.json()
    assert body["holidayRegion"] == "JP"
    assert body["schemaVersions"] == {"routine": 1, "task": 1}
    assert "serverTime" in body
