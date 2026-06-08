# CD-037: Document service-decomposition (microservices) roadmap in FEATURES.md

- **Type:** docs
- **Status:** In review
- **Branch:** `CD-037-features-service-decomposition`

## Summary
Add a **Service decomposition — split API by domain (microservices)** entry to the "Advanced scaling
(roadmap)" section of `FEATURES.md`: independently-scalable auth/contacts/admin services behind an API
gateway. Additive only; nothing removed.

## Acceptance criteria
- [x] New roadmap entry (⬜ planned) with the service/endpoint mapping table (auth-service,
      contacts-service, admin-service, api-gateway).
- [x] Includes the trade-off caveat (prefer horizontal scale-out first; DB split is the hard part).
- [x] Index in `tickets/README.md`.

## Notes
Documentation only — no behaviour change. This is a future architectural option, not planned work;
horizontal scale-out (CD-036) is the pragmatic first step before decomposition.
