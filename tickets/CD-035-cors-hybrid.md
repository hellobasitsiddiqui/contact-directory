# CD-035: CORS config (for hybrid/WebView clients)

- **Type:** backend
- **Status:** Open
- **Branch:** `CD-035-cors-hybrid`

## Summary
No CORS configured. Only needed if a **hybrid** (Capacitor/Cordova/WebView) client or a different-origin
web SPA consumes the API — **native apps don't need it**. See
[`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Big picture / Tier 3.

## Acceptance criteria
- [ ] Configurable CORS bean locked to allowed origins (env-driven; wildcard only for dev).
- [ ] Document that native clients are unaffected.

## Notes
Low priority unless going hybrid. Don't open CORS wide in production.
