#!/usr/bin/env python3
"""Minimal agent example: write tomorrow's task to the shared server (stdlib only)."""

import json
import os
import urllib.request
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

BASE = os.environ.get("EVERY_ROUTES_BASE", "http://127.0.0.1:8787/api/v1")
TOKEN = os.environ["EVERY_ROUTES_TOKEN"]
TITLE = os.environ.get("EVERY_ROUTES_TITLE", "銀行に行く")

tomorrow = (datetime.now(ZoneInfo("Asia/Tokyo")) + timedelta(days=1)).replace(
    hour=10, minute=0, second=0, microsecond=0
)
payload = {"source": "agent", "title": TITLE, "at": tomorrow.isoformat(), "allDay": False}

req = urllib.request.Request(
    f"{BASE}/tasks",
    data=json.dumps(payload).encode(),
    method="POST",
    headers={"Authorization": f"Bearer {TOKEN}", "Content-Type": "application/json"},
)
with urllib.request.urlopen(req) as res:
    print(res.status, res.read().decode())
