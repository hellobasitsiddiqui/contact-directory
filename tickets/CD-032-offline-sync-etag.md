# CD-032: Offline sync — delta endpoint + ETag

- **Type:** backend
- **Status:** Open
- **Branch:** `CD-032-offline-sync-etag`

## Summary
Leverage existing `updatedAt`/`@Version` for offline-first mobile. Tier 3 polish. See
[`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Tier 3.

## Acceptance criteria
- [ ] `GET /api/v1/contacts/changes?since=<ISO-8601>` returning only contacts with `updatedAt > since` (+ a sync token).
- [ ] `ETag` on contacts list + `If-Modified-Since`/`If-None-Match` → `304 Not Modified`.
- [ ] Tests.

## Notes
Big cellular-bandwidth win for sync; conflict detection already exists via `@Version` → `412`.
