# CD-028: Refresh tokens + server-side revocation + logout

- **Type:** backend / security
- **Status:** Open
- **Branch:** `CD-028-refresh-tokens`

## Summary
Single 24h access JWT, no refresh, no server-side revocation/logout. A lost device's token stays valid
for 24h. Core mobile auth gap. See [`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Tier 1.

## Acceptance criteria
- [ ] Short-lived access token (~15m) + longer refresh token (~7–30d, rotated on use).
- [ ] `POST /api/v1/auth/refresh` issues a new access (and refresh) token.
- [ ] Server-side revocation list (DB/cache w/ TTL); validate bearer tokens against it.
- [ ] `POST /api/v1/auth/logout` invalidates the current/refresh token; enables "log out lost device".
- [ ] Keep existing login working so the change is additive (web SPA can migrate later).

## Notes
The single biggest mobile auth improvement. Additive design avoids forcing the web SPA to change day one.
