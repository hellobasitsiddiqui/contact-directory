# CD-027: HTTPS/TLS + HSTS

- **Type:** infra / security
- **Status:** Open
- **Branch:** `CD-027-tls-hsts`

## Summary
API is HTTP-only on `:8080`. iOS ATS rejects plain HTTP; Android blocks cleartext on API 28+.
Tier 1 mobile blocker. See [`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Tier 1.

## Acceptance criteria
- [ ] Terminate TLS (reverse proxy / load balancer, or `server.ssl`) — coordinate with the deploy target (CD-025).
- [ ] Add HSTS in `SecurityConfig` (`headers.httpStrictTransportSecurity()`).
- [ ] Document the HTTPS expectation in the runbook (CD-023).

## Notes
Overlaps the deploy work; TLS is usually terminated at the proxy/LB in production.
