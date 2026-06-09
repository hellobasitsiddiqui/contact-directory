# CD-042: Postgres integration test in CI (Testcontainers)

- **Type:** testing / ci
- **Status:** Open
- **Branch:** `CD-042-postgres-integration-test`

## Summary
Close the test-reality gap raised by the CD-024 adversarial review: the whole suite runs on **H2**
(Flyway disabled), so the `postgres` profile — Flyway `V1`, Hibernate `validate`, and Postgres-specific
behaviour (e.g. the `bytea` photo path) — is **never exercised in CI**. It was only validated manually.

## Acceptance criteria
- [ ] Add `org.testcontainers:postgresql` (+ junit-jupiter) test deps (Spring Boot manages the version).
- [ ] Add a `@SpringBootTest @ActiveProfiles("postgres")` IT using a Testcontainers `postgres:16`
      (via `@ServiceConnection`) that proves: app boots (Flyway `V1` applies + `validate` passes),
      basic CRUD works, and the **photo lifecycle round-trips** (upload → fetch byte-for-byte → delete).
- [ ] Tag it (e.g. `postgres`) and exclude it from the default `mvn verify` gate (like the `e2e` tag),
      so local devs without Docker aren't blocked.
- [ ] Run it in a dedicated CI job (Docker is available on `ubuntu-latest`).

## Notes
Spun out of CD-024. The review confirmed (HIGH) that H2 BLOB ≠ Postgres `bytea`/`oid` and Flyway DDL
is invisible to the H2 gate — this gives a permanent guard so future schema/migration changes are
caught before merge.
