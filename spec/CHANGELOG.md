# Every routes — Spec Changelog

API / data-model versioning. Breaking changes add a new major path (`/api/v2`) and coexist
with `/api/v1`. Record every `schemaVersion` / API change here.

## 1.0.0 (initial)
- `openapi.yaml` v1.0.0: `/health`, `/info`; routines CRUD (`GET /routines[/{addr}]`);
  tasks `GET/POST/PATCH/DELETE`. Base path `/api/v1`, Bearer auth, RFC3339 times
  (`lastModified` always UTC; task `at` is tz-aware). Error shape `{ error: { code, message } }`.
- `schemas/routine-profile.schema.json` v1 (`RoutineProfile`, draft-07).
  `routineAddress = rt_ + 64 hex`; recurrence `daysOfWeek`/`holiday (exclude|include|only)`;
  blocks with `"HH:MM"` start/end.
- `schemas/task.schema.json` v1 (`Task`, draft-07). sources: google_tasks/agent/manual;
  `externalId` nullable (dedupe key with source); status needsAction/completed.
- `schemas/error.schema.json` v1 (`Error`).
- Conflict rule: last-write-wins on `lastModified`; older write rejected with 409 returning
  current server data; same timestamp + differing content → 409 (server wins); `?force=true`
  overrides. Tombstones logically deleted; GC after N days (default 90).
