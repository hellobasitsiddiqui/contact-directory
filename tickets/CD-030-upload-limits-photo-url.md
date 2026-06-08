# CD-030: Larger photo upload limit + absolute photoUrl

- **Type:** backend
- **Status:** Open
- **Branch:** `CD-030-upload-limits-photo-url`

## Summary
Photo uploads capped at 2MB (`spring.servlet.multipart` + `ContactController`); phone photos exceed
that. `photoUrl` is relative. See [`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Tier 2.

## Acceptance criteria
- [ ] Raise multipart + `ContactController` photo limit to ~5–10MB (configurable).
- [ ] Return absolute `photoUrl` (or expose a configurable API base URL, e.g. via `/api/v1/info`).
- [ ] Keep client-side compression guidance in the docs.

## Notes
Clients should still compress before upload; absolute URLs allow multi-environment/multi-host deploys.
