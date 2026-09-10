# Every routes server

Shared hub for routine profiles and tasks (design `design/01-every-routes-server.md`).
Base path `/api/v1`, JSON, Bearer auth, RFC3339 times (`lastModified` UTC).

## Quick start

```bash
cd server
python -m venv .venv && . .venv/bin/activate
pip install -e ".[dev]"
cp config.example.yaml every-routes.yaml   # edit auth_tokens
every-routes-server --config every-routes.yaml
# or: python -m every_routes_server --data-dir ./data --port 8787
```

Health: `GET /api/v1/health` (open). Everything else needs
`Authorization: Bearer <token>` from `auth_tokens`.

## API cheat sheet

- `GET /api/v1/info` — timezone, holidayRegion, schemaVersions, serverTime
- `GET /api/v1/routines?since=&includeDeleted=` — list metas (import candidates)
- `GET /api/v1/routines/{addr}` — full profile
- `PUT /api/v1/routines/{addr}[?force=true]` — create (201) / update (200) / conflict (409 + server data)
- `DELETE /api/v1/routines/{addr}` — tombstone
- `GET /api/v1/tasks?from=YYYY-MM-DD&to=&since=&source=&includeDeleted=` (default range today〜+14d, default includeDeleted=true)
- `POST /api/v1/tasks` — create (201); `(source, externalId)` match updates (200)
- `PATCH /api/v1/tasks/{id}` — partial update; stale `lastModified` → 409
- `DELETE /api/v1/tasks/{id}` — tombstone

Conflict rule: last-write-wins on `lastModified`; same timestamp + differing content → server wins (409); `?force=true` overwrites.

## Backup

```bash
python scripts/dump.py --base-url http://lan-host:8787 --token $TOKEN --out backup.json
python scripts/restore.py --base-url http://lan-host:8787 --token $TOKEN --in backup.json
```

Copy `data_dir/every-routes.db` for a filesystem backup. Tombstones older than
`tombstone_gc_days` (default 90) may be physically purged.
