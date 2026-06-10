# CD-049: Post-CD-028 review loop (doc sweep + code/security review)

- **Type:** docs / review
- **Status:** In review
- **Branch:** `CD-049-review-loop`

## Summary
One full review loop after CD-027 + CD-028 landed on `develop`: doc sweep → code review → doc sweep →
security review. Done inline (no agent fan-out) to stay token-light. Converged in a single pass.

## Findings & fixes
**Doc sweep (the only phase with fixes):** CD-027 + CD-028 added test classes, so the stat claims went
stale.
- Test count **219 → 233**, **18 → 20 classes** (gate suite, e2e excluded): README (×2), FEATURES (×2).
- Browser e2e suites **two → three** (added `PlaywrightSilentRefreshE2eTest`): README, FEATURES,
  CONTRIBUTING, COMMON-FEATURES.
- `docs/MOBILE-API-READINESS.md` appendix: the "24h token → re-login after a day" note now reflects the
  15-minute access token + refresh-token flow (CD-028).
- `COMMON-FEATURES.md` security rows CD-027 left stale: **TLS / HTTPS** `⬜ (deploy concern)` →
  `➖ (TLS-ready: HSTS + Caddy overlay; live host is the deploy step)`; **Security headers** now notes
  HSTS + frame-options.

**Code review:** clean. Re-checked the auth-client.js refresh logic (per-page single-flight + cross-tab
`navigator.locks` + identity-based skip + storage-clear guard compose correctly) and the `rotate()`
claimed==0 path (PC cleared → fresh re-read → 401 only if revoked/gone). No new issues.

**Security review:** clean. `permitAll` surface is the public auth endpoints + public OpenAPI spec only;
the raw refresh token is never logged and is stored only as a SHA-256 hash; CSRF-disabled + stateless +
body-token (not cookie) keeps the new endpoints non-CSRF-able. The one real security finding (a
`rotate()` revocation TOCTOU) was already fixed under CD-028.

## Notes
Counts verified from the clean gate surefire reports (e2e classes excluded). No code changed in this
ticket — docs only.
