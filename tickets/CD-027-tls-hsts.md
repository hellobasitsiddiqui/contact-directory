# CD-027: HTTPS/TLS + HSTS

- **Type:** infra / security
- **Status:** In review
- **Branch:** `CD-027-tls-hsts`

## Summary
API was HTTP-only on `:8080`. iOS ATS rejects plain HTTP; Android blocks cleartext on API 28+.
Tier 1 mobile blocker. See [`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Tier 1.
This ticket makes the app **TLS-ready**; standing TLS up on a real host is the deploy step (CD-025).

## Acceptance criteria
- [x] Terminate TLS — ship a reverse-proxy recipe: a **Caddy** overlay (`docker-compose.tls.yml` +
      `Caddyfile`) with automatic Let's Encrypt certs, and make the app **proxy-aware**
      (`server.forward-headers-strategy=framework`) so it sees `X-Forwarded-Proto=https`. (Wiring it to
      a specific host/domain is CD-025.)
- [x] Add **HSTS** in `SecurityConfig` (`headers.httpStrictTransportSecurity()`): `max-age=1y`,
      `includeSubDomains`, `preload`. Only emitted on secure requests (correct for HTTP local dev).
- [~] Document the HTTPS expectation — done in README ("Serve over HTTPS (TLS)") and
      `docs/RELEASE-AND-DEPLOYMENT.md` §2.3; the dedicated runbook is CD-023 and will reference these.

## Implementation
- `SecurityConfig` — explicit HSTS header writer (only emitted on secure requests).
- `application.yml` — `forward-headers-strategy: ${SERVER_FORWARD_HEADERS_STRATEGY:none}`. **Off by
  default** so a directly-exposed app never trusts client-supplied `X-Forwarded-*` (a review caught that
  baking `framework` in globally would let a direct client to `:8080` spoof the proto/host).
- `docker-compose.yml` — the app's `8080` is bound to **host loopback** (`127.0.0.1:8080:8080`), not
  `0.0.0.0`, so it's reachable for local use but never network-exposed (Compose concatenates `ports`
  across files, so an overlay can't un-publish a base port — the bind must live in the base).
- `docker-compose.tls.yml` + `Caddyfile` — opt-in TLS overlay (default `docker compose up` unchanged):
  Caddy terminates TLS and sets `SERVER_FORWARD_HEADERS_STRATEGY=framework` on the app, reaching it over
  the internal network; only Caddy (80/443) is internet-facing. `.env.example` gains `DOMAIN`.
- Tests: `SecurityIntegrationTest` (HSTS present on secure request, absent on plain HTTP) +
  `ForwardedHeadersHstsTest` (with `framework`, `X-Forwarded-Proto: https` → HSTS; no header → none).

## Notes
TLS is terminated at the proxy/LB in production (also how PaaS hosts work); the app stays HTTP behind it
but treats forwarded-HTTPS requests as secure — only when explicitly placed behind a trusted proxy.
Reviewed adversarially (security/infra/regression lenses); the forwarded-header trust scoping above is
the fix from that review.
