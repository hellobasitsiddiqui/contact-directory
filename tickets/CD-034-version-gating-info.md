# CD-034: App-version gating + /info bootstrap endpoint

- **Type:** backend
- **Status:** Open
- **Branch:** `CD-034-version-gating-info`

## Summary
No way to gate stale clients or advertise client config. Tier 3 polish. See
[`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Tier 3.

## Acceptance criteria
- [ ] `X-App-Version` header check → `426 Upgrade Required` below a configurable min version.
- [ ] `GET /api/v1/info`: min-supported version, upload limits, supported formats, absolute token expiry.
- [ ] Tests/docs.

## Notes
Lets server and app evolve independently and coordinate forced upgrades.
