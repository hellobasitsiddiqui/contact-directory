# CD-028: Refresh tokens + server-side revocation + real logout

- **Type:** feature / security
- **Status:** In review
- **Branch:** `CD-028-refresh-tokens`

## Summary
Tier-1 mobile blocker (with CD-027). Replaces the single 24h access JWT with a proper token
lifecycle: a **short-lived access JWT (15m default)** plus an **opaque rotating refresh token
(14d sliding)** stored hashed, rotated on every use, revocable server-side, with **family-based
reuse (theft) detection**. Design chosen by a 3-design × 3-judge panel (opaque-rotating-DB beat
JWT+denylist and version-stamp alternatives on security, codebase fit and mobile-readiness).

## Acceptance criteria (from the original backlog ticket)
- [x] Short-lived access token (~15m) + longer refresh token (rotated on use).
- [x] `POST /api/v1/auth/refresh` issues a new access + refresh token.
- [x] Server-side revocation state, validated on refresh (DB-backed, hashed at rest).
- [x] `POST /api/v1/auth/logout` invalidates the refresh session ("log out lost device").
- [x] Existing login stays working; the API change is additive (web SPA migrated in this ticket).

## What shipped
- **`refresh_tokens` table** (Flyway `V2__refresh_tokens.sql` on Postgres — also widens the
  `audit_events.action` CHECK; ddl-auto on H2): SHA-256 hash only, `family_id` lineage,
  `used_at` / `revoked_at` / `revoke_reason`.
- **`POST /api/v1/auth/refresh`** — atomic claim (`UPDATE … WHERE used_at IS NULL`), child minted in
  the same family, fresh pair returned (access JWT carries the user's **current** role). Replay
  within the 30s grace window = benign concurrent retry (sibling); beyond it = **theft: whole
  family revoked**, audited `AUTH_TOKEN_REUSE`. One generic 401 for every failure mode.
- **`POST /api/v1/auth/logout`** — revokes the whole family; idempotent 204 always; audited
  `AUTH_LOGOUT`.
- **Login/register** return the pair (additive `refreshToken` + `refreshExpiresInMs`); login purges
  stale rows and enforces `max-sessions-per-user` (oldest evicted).
- **Lifecycle revocation:** self change-password (revoke all + fresh pair returned so the current
  session continues), admin reset-password, disable; delete hard-deletes rows.
- **JWT filter fix (latent 500):** a valid access token for a just-deleted user now yields a clean
  401 (uncaught `UsernameNotFoundException` before). Found by the design panel; regression-tested.
- **SPA silent refresh:** shared `static/auth-client.js` (proactive refresh near expiry, reactive
  once-on-401 + retry, cross-tab single-flight via `navigator.locks`, real logout with `keepalive`).
  All five page wrappers delegate to it; `login.js`/`profile.js` store pairs through it.
- **Config (`app.jwt.*`):** `expiration-ms` default now **900000 (15m)**; new
  `refresh-expiration-ms` (14d), `refresh-reuse-grace-seconds` (30), `max-sessions-per-user` (10).
- **Tests:** `RefreshTokenLifecycleTest` (mutable-`Clock` MockMvc: rotation, grace vs theft, expiry,
  logout idempotency + family kill, change/reset/disable/delete revocation, token confusion both
  directions) + `PlaywrightSilentRefreshE2eTest` (3s tokens: browser session survives expiry,
  logout is real).

## Trade-offs / future hardening (deliberate)
- An access token stays valid ≤15m after logout/revocation (no per-request denylist —
  proportionate). Known cheap upgrade if needed: per-user `token_version` stamp.
- Web SPA keeps tokens in `localStorage` (pre-existing XSS trade-off). Exit path: HttpOnly-cookie
  refresh transport for the web client — candidate future ticket.
