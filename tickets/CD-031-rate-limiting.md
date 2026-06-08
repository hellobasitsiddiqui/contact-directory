# CD-031: Lockout/rate-limit feedback + API rate limiting

- **Type:** backend / security
- **Status:** Open
- **Branch:** `CD-031-rate-limiting`

## Summary
`423 LOCKED` doesn't say how long; only login is throttled. Add lockout feedback and API-wide rate
limiting. See [`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Tier 2.

## Acceptance criteria
- [ ] Include remaining lockout time on `423` (e.g. `Retry-After` header / body field).
- [ ] API-wide per-user/IP rate limiting (`429` + `X-RateLimit-Limit/Remaining/Reset`) — e.g. Bucket4j or at the proxy.
- [ ] Tests / docs.

## Notes
Protects against runaway mobile sync loops and credential attacks on untrusted networks.
