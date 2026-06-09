# CD-045: Docs consistency sweep (post CD-024…CD-044)

- **Type:** docs
- **Status:** In review
- **Branch:** `CD-045-doc-sweep`

## Summary
Consistency pass over the markdown docs after the Postgres/Flyway, photo-fix and admin-UX merges.

## Acceptance criteria
- [x] Finalize stale ticket-index statuses → Done (CD-024 #44, CD-042 #45, CD-043 #46, CD-044 #47).
- [x] `CHANGELOG.md` `[Unreleased]` populated with the post-`1.0.0-beta.1` work (Postgres+Flyway, admin
      dashboard + sample users, Postgres e2e, tag-query fix, photo-display fix, bytea).
- [x] Reflect **two browser e2e suites** (H2 `PlaywrightE2eTest` + `PlaywrightPostgresE2eTest`) and the
      optional **Postgres profile** across README / FEATURES / CONTRIBUTING / COMMON-FEATURES.
- [x] `docs/WALKTHROUGH.md` notes the **admin dashboard** landing + sample users + the "Admin view".

## Notes
Docs only — no behaviour change. Screenshots (`docs/screenshots/`) still show the pre-dashboard UI; a
full screenshot refresh (to add the dashboard) is a separate follow-up.
