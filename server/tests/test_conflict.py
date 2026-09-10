"""Unit tests for last-write-wins (00 §6.2)."""

from every_routes_server.domain.conflict import decide_write


def test_newer_wins():
    assert (
        decide_write(
            incoming_last_modified="2026-09-11T00:00:00Z",
            stored_last_modified="2026-09-10T00:00:00Z",
            incoming_payload={"a": 2},
            stored_payload={"a": 1},
        )
        == "write"
    )


def test_older_conflicts():
    assert (
        decide_write(
            incoming_last_modified="2026-09-09T00:00:00Z",
            stored_last_modified="2026-09-10T00:00:00Z",
            incoming_payload={"a": 1},
            stored_payload={"a": 1},
        )
        == "conflict"
    )


def test_same_time_same_content_noop():
    p = {"a": 1}
    assert (
        decide_write(
            incoming_last_modified="2026-09-10T00:00:00Z",
            stored_last_modified="2026-09-10T00:00:00Z",
            incoming_payload=dict(p),
            stored_payload=dict(p),
        )
        == "noop"
    )


def test_same_time_different_content_server_wins():
    assert (
        decide_write(
            incoming_last_modified="2026-09-10T00:00:00Z",
            stored_last_modified="2026-09-10T00:00:00Z",
            incoming_payload={"a": 2},
            stored_payload={"a": 1},
        )
        == "conflict"
    )


def test_force_overrides():
    assert (
        decide_write(
            incoming_last_modified="2001-01-01T00:00:00Z",
            stored_last_modified="2026-09-10T00:00:00Z",
            incoming_payload={},
            stored_payload={},
            force=True,
        )
        == "write"
    )
