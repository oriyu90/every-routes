# Every routes (Android)

Today-view timeline app + shared-server sync (design `design/02-every-routes-android.md`).

- `app` module: MainActivity (Compose), `model/` (RoutineProfile/Task mirroring `spec/schemas`),
  `buildTodayView()` shared by app + future Glance widget.
- Sync rules: match by `routineAddress` (never by name); last-write-wins on
  `lastModified`; server-only addresses are import candidates, never auto-taken.

```bash
cd android
./gradlew assembleDebug testDebugUnitTest lint
```

Configure server base URL + Bearer token in Settings (server never ships a token).
