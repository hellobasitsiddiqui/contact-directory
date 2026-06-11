# CD-052: Docs consistency sweep (post-beta.3 + Android companion repo sync)

- **Type:** docs
- **Status:** Done
- **Branch:** `CD-052-doc-sweep-cross-repo`

## Summary
Multi-agent doc sweep (5 parallel auditors + adversarial verification per finding) across this repo
and the new companion repo
[contact-directory-android](https://github.com/hellobasitsiddiqui/contact-directory-android).
22 findings confirmed; this ticket covers the backend half (the Android half landed as a PR in that
repo).

## Changes
- **README.md** — new **Clients** section pointing at the Android companion repo (native Compose app
  + WebView wrapper, CI-built APKs); Flyway paragraph now references both migrations
  (`V1__init.sql`, `V2__refresh_tokens.sql`); project-structure tree updated for Audit
  controller/service, RefreshTokenService, `application-postgres.yml` and `db/migration/`.
- **COMMON-FEATURES.md** — Profiles row: `test + postgres profiles` (was test-only; inconsistent
  with section 4 crediting the postgres profile).
- **docs/RELEASE-AND-DEPLOYMENT.md** — two→three betas (incl. `v1.0.0-beta.3`), pom version
  `1.0.0-beta.3`, two→three Playwright e2e suites (added silent-token-refresh suite).
- **docs/MOBILE-API-READINESS.md** — status header: Tier 1 (CD-027/CD-028) shipped in beta.3;
  cross-reference to the now-existing Android client; appendix estimate marked superseded (kept for
  the record).

## Acceptance criteria
- [x] No backend doc claims "two betas" / beta.2-era version state
- [x] Backend user-facing docs point readers at the Android companion repo
- [x] MOBILE-API-READINESS no longer presents shipped work (CD-027/028) or the built Android app as future
- [x] e2e suite count consistent across docs (three)
