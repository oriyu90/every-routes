"""Routines: 201 create / 200 update / 409 conflict / ?force / tombstone."""

ADDR = "rt_" + "9f" * 32


def _payload(**over):
    base = {
        "schemaVersion": 1,
        "routineAddress": ADDR,
        "name": "平日",
        "lastModified": "2026-09-10T08:12:33Z",
        "recurrence": {"daysOfWeek": ["MON", "TUE", "WED", "THU", "FRI"], "holiday": "exclude"},
        "blocks": [{"id": "blk_1", "start": "07:00", "end": "07:30", "title": "起床・準備"}],
    }
    base.update(over)
    return base


def test_create_update_no_growth(client, auth_headers):
    r1 = client.put(f"/api/v1/routines/{ADDR}", json=_payload(), headers=auth_headers)
    assert r1.status_code == 201, r1.text
    created_lm = r1.json()["lastModified"]

    r2 = client.put(
        f"/api/v1/routines/{ADDR}",
        json=_payload(name="平日改", lastModified="2030-01-01T00:00:00Z"),
        headers=auth_headers,
    )
    assert r2.status_code == 200, r2.text
    assert r2.json()["name"] == "平日改"
    assert r2.json()["lastModified"] != created_lm

    listing = client.get("/api/v1/routines", headers=auth_headers).json()["routines"]
    assert [x for x in listing if x["routineAddress"] == ADDR and not x["deleted"]].__len__() == 1


def test_conflict_and_force(client, auth_headers):
    assert client.put(f"/api/v1/routines/{ADDR}", json=_payload(), headers=auth_headers).status_code == 201
    current = client.get(f"/api/v1/routines/{ADDR}", headers=auth_headers).json()

    stale = _payload(name="古い", lastModified="2001-01-01T00:00:00Z")
    r = client.put(f"/api/v1/routines/{ADDR}", json=stale, headers=auth_headers)
    assert r.status_code == 409
    assert r.json()["name"] == current["name"]

    r2 = client.put(f"/api/v1/routines/{ADDR}?force=true", json=stale, headers=auth_headers)
    assert r2.status_code == 200
    assert r2.json()["name"] == "古い"


def test_address_mismatch_400(client, auth_headers):
    bad = _payload()
    bad["routineAddress"] = "rt_" + "aa" * 32
    r = client.put(f"/api/v1/routines/{ADDR}", json=bad, headers=auth_headers)
    assert r.status_code == 400


def test_delete_is_tombstone(client, auth_headers):
    assert client.put(f"/api/v1/routines/{ADDR}", json=_payload(), headers=auth_headers).status_code == 201
    assert client.delete(f"/api/v1/routines/{ADDR}", headers=auth_headers).status_code == 200
    assert client.get(f"/api/v1/routines/{ADDR}", headers=auth_headers).status_code == 404
    hidden = client.get("/api/v1/routines", headers=auth_headers).json()["routines"]
    assert all(x["routineAddress"] != ADDR for x in hidden)
    shown = client.get("/api/v1/routines?includeDeleted=true", headers=auth_headers).json()["routines"]
    match = [x for x in shown if x["routineAddress"] == ADDR]
    assert match and match[0]["deleted"] is True


def test_since_filter(client, auth_headers):
    assert client.put(f"/api/v1/routines/{ADDR}", json=_payload(), headers=auth_headers).status_code == 201
    future = client.get("/api/v1/routines?since=2999-01-01T00:00:00Z", headers=auth_headers).json()
    assert future["routines"] == []
    past = client.get("/api/v1/routines?since=2001-01-01T00:00:00Z", headers=auth_headers).json()
    assert any(x["routineAddress"] == ADDR for x in past["routines"])
