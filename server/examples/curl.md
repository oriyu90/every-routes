# curl examples (LAN/VPN, Bearer token)

```bash
BASE=http://192.0.2.10:8787/api/v1
TOKEN=change-me-please

curl -s $BASE/health
curl -s -H "Authorization: Bearer $TOKEN" $BASE/info

# Routines
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/routines?includeDeleted=true"
curl -s -H "Authorization: Bearer $TOKEN" $BASE/routines/rt_<64hex>
curl -s -X PUT -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  --data @../../spec/examples/routine-weekday.json \
  $BASE/routines/rt_9f2c4b892a7cb4961f8c75d940fb324aeb9dc0123456789abcdef0123456789a

# Tasks (agent writes "tomorrow 10:00")
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"source":"agent","title":"銀行に行く","at":"2026-09-11T10:00:00+09:00"}' \
  $BASE/tasks
curl -s -H "Authorization: Bearer $TOKEN" "$BASE/tasks?from=2026-09-11&to=2026-09-12"
```
