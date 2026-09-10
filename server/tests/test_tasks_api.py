"""Tasks: POST create / externalId dedupe / PATCH conflict / DELETE tombstone."""


def test_agent_task_roundtrip(client, auth_headers):
    r = client.post(
        "/api/v1/tasks",
        json={"source": "agent", "title": "銀行に行く", "at": "2026-09-11T10:00:00+09:00"},
        headers=auth_headers,
    )
    assert r.status_code == 201, r.text
    task_id = r.json()["id"]

    listing = client.get("/api/v1/tasks?from=2026-09-11&to=2026-09-11", headers=auth_headers).json()
    assert any(t["id"] == task_id for t in listing["tasks"])

    done = client.patch(f"/api/v1/tasks/{task_id}", json={"status": "completed"}, headers=auth_headers)
    assert done.status_code == 200
    assert done.json()["status"] == "completed"


def test_external_id_dedupe(client, auth_headers):
    body = {
        "source": "google_tasks",
        "externalId": "EXT123",
        "title": "歯医者",
        "at": "2026-09-11T15:00:00+09:00",
    }
    r1 = client.post("/api/v1/tasks", json=body, headers=auth_headers)
    assert r1.status_code == 201
    r2 = client.post("/api/v1/tasks", json={**body, "title": "歯医者(更新)"}, headers=auth_headers)
    assert r2.status_code == 200
    assert r2.json()["id"] == r1.json()["id"]
    assert r2.json()["title"] == "歯医者(更新)"


def test_patch_conflict_returns_server(client, auth_headers):
    r = client.post(
        "/api/v1/tasks",
        json={"source": "manual", "title": "買い物", "at": "2026-09-12T10:00:00+09:00"},
        headers=auth_headers,
    )
    task_id = r.json()["id"]
    server_lm = r.json()["lastModified"]

    # Fresh update moves server clock forward.
    client.patch(f"/api/v1/tasks/{task_id}", json={"title": "買い物2"}, headers=auth_headers)
    stale = client.patch(
        f"/api/v1/tasks/{task_id}",
        json={"title": "古い", "lastModified": server_lm},
        headers=auth_headers,
    )
    assert stale.status_code == 409
    assert stale.json()["title"] == "買い物2"


def test_delete_tombstone_visible(client, auth_headers):
    r = client.post(
        "/api/v1/tasks",
        json={"source": "manual", "title": "消す", "at": "2026-09-12T10:00:00+09:00"},
        headers=auth_headers,
    )
    task_id = r.json()["id"]
    assert client.delete(f"/api/v1/tasks/{task_id}", headers=auth_headers).status_code == 200
    with_deleted = client.get("/api/v1/tasks?from=2026-09-12&to=2026-09-12", headers=auth_headers).json()
    assert any(t["id"] == task_id and t["deleted"] for t in with_deleted["tasks"])
    hidden = client.get(
        "/api/v1/tasks?from=2026-09-12&to=2026-09-12&includeDeleted=false", headers=auth_headers
    ).json()
    assert all(t["id"] != task_id for t in hidden["tasks"])
