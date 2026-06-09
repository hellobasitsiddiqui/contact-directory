# CD-036: Document advanced scaling roadmap in FEATURES.md

- **Type:** docs
- **Status:** Done (#38)
- **Branch:** `CD-036-features-advanced-scaling`

## Summary
Add an **Advanced scaling (roadmap)** section to `FEATURES.md` — parent feature (durable Postgres +
Flyway) plus the under-load scaling sub-features (app scale-out, vertical, pooling, read replicas, HA,
sharding). Additive only; nothing removed from the feature file.

## Acceptance criteria
- [x] New "Advanced scaling (roadmap)" section in `FEATURES.md`, all items marked ⬜ (planned).
- [x] Sub-features captured as a table; ties to CD-024 / CD-025 + `docs/RELEASE-AND-DEPLOYMENT.md`.
- [x] Index in `tickets/README.md`.

## Notes
Documentation only — no behaviour change. The actual work lives in CD-024 (Postgres + Flyway) and
CD-025 (deploy target).
