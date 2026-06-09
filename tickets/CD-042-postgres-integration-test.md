# CD-042: Postgres integration test in CI (Testcontainers + Playwright)

- **Type:** testing / ci / bugfix
- **Status:** In review
- **Branch:** `CD-042-postgres-integration-test`

## Summary
Close the test-reality gap from the CD-024 review: the whole suite runs on **H2** (Flyway disabled),
so the `postgres` profile — Flyway `V1`, Hibernate `validate`, and Postgres-specific behaviour — was
**never exercised automatically**. Adds a Testcontainers + Playwright e2e against real Postgres.

## 🔴 Critical bug this test found (and fixes)
The tag-filter query was **invalid on PostgreSQL**: `SELECT DISTINCT t ... ORDER BY LOWER(t)` — Postgres
rejects an `ORDER BY` expression that isn't in the `DISTINCT` select list (`ERROR: for SELECT DISTINCT,
ORDER BY expressions must appear in select list`). H2 accepted it, so every prior test passed. On
Postgres `GET /api/v1/contacts/tags` 500'd → the SPA bounced **every user back to the login page** →
**the app was unusable on Postgres.** Fixed by dropping the SQL ordering and sorting case-insensitively
in `ContactService.listTags` (portable). Files: `ContactRepository.findDistinctTags[ByOwnerId]`,
`ContactService.listTags`.

## Acceptance criteria
- [x] Add Testcontainers deps (`spring-boot-testcontainers`, `org.testcontainers:postgresql`,
      `org.testcontainers:junit-jupiter`) — BOM-managed.
- [x] `PlaywrightPostgresE2eTest` — `@SpringBootTest @ActiveProfiles("postgres") @Testcontainers`
      with `postgres:16` via `@ServiceConnection`. Drives the **browser** (login → contacts list loads
      on Postgres → admin nav), proving Flyway `V1` + `validate` + the `/contacts/tags` fix, and
      **round-trips a contact photo** through `bytea` (upload → fetch byte-for-byte) — proving the
      CD-024 `bytea` mapping over real Postgres.
- [x] Tagged `e2e`, excluded from `mvn verify`; runs in the existing `e2e.yml` (ubuntu-latest has
      Docker for Testcontainers — no workflow change needed).
- [x] H2 gate still green (219 tests) after the query change.

## Notes
- The contact **photo upload is driven via Playwright's API request client**, not the file-input UI:
  headless Chromium rejects the multipart upload to the local HTTP/1.1 server with
  `net::ERR_H2_OR_QUIC_REQUIRED` (a browser networking quirk — the same upload works via curl and a
  real browser; the app code is correct). The API client hits the identical server endpoint, so the
  `bytea` persistence is still proven.
- **Follow-up [[CD-043]]**: the avatar `<img>` can't display authenticated photos in the browser
  (`img.src` points at the bearer-protected endpoint). Real, pre-existing product bug — see CD-043.
