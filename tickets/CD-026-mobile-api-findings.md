# CD-026: Save mobile API readiness findings

- **Type:** docs
- **Status:** In review
- **Branch:** `CD-026-mobile-readiness`

## Summary
Persist the mobile-reuse audit of the REST API so it can be implemented later. No behaviour change.

## Acceptance criteria
- [x] Add `docs/MOBILE-API-READINESS.md` (already-ready / Tier 1 must-change / Tier 2–3 polish +
      Android-app effort note).
- [x] Open backlog tickets for actionable items: CD-027 (TLS), CD-028 (refresh tokens), CD-029
      (users pagination), CD-030 (uploads + photoUrl), CD-031 (rate limiting), CD-032 (sync/ETag),
      CD-033 (push), CD-034 (version gating + /info), CD-035 (CORS).
- [x] Index all of the above in `tickets/README.md`.

## Notes
Findings from the four-dimension audit (auth/token, transport/CORS, API design, mobile gaps).
