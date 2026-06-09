# CD-024: Durable persistence (Postgres + Flyway)

- **Type:** backend / infra
- **Status:** In review
- **Branch:** `CD-024-postgres-flyway`

## Summary
Remove the ephemerality blocker: the app stores data in an H2 file (`./data/contacts.mv.db`) that is
lost on a fresh-filesystem restart. Move production persistence to managed Postgres with versioned
Flyway migrations. See [`../docs/RELEASE-AND-DEPLOYMENT.md`](../docs/RELEASE-AND-DEPLOYMENT.md) §2.1.

## Acceptance criteria
- [x] Add deps: `org.postgresql:postgresql` (runtime), `org.flywaydb:flyway-core`,
      `org.flywaydb:flyway-database-postgresql` (Flyway 10+ needs the per-DB module).
- [x] Add a `postgres` Spring profile (`application-postgres.yml`): datasource URL/user/pass from env;
      `ddl-auto: validate` (Flyway owns the schema); `defer-datasource-initialization: false`.
- [x] Add `src/main/resources/db/migration/V1__init.sql` matching the current entities (generated from
      Hibernate's PostgreSQL dialect → exact types: `timestamp(6) with time zone`, `oid` for the photo
      LOB, identity columns, enum check constraints) + sensible indexes.
- [x] Keep H2 for local dev + tests; Flyway disabled there (`spring.flyway.enabled: false` in both
      `application.yml` and the test config) — inner loop stays zero-setup.
- [x] Two-container `docker-compose.yml` (app + postgres + `pgdata` volume) + `.env.example`; `.env`
      git-ignored.
- [x] Verify the gate (tests) still passes on H2 — **219 tests green, coverage met, BUILD SUCCESS**.

## Validation performed (against a throwaway `postgres:16`)
- Flyway applied V1 on a fresh DB; Hibernate `validate` passed; DataInitializer seeded admin + 3
  sample contacts; app started clean on `:8080`.
- Smoke test: admin login + `GET /api/v1/contacts` → 3.
- Restart on the same DB: Flyway "No migration necessary", no re-seed, contacts still 3 — **data
  persisted** (the durability goal).

## Notes
Built per the saved 2-container preference. Alternative quick demo: a persistent volume on H2 (Option
B) — no schema work but doesn't scale horizontally. Postgres + Flyway is the recommended path. Pairs
with CD-025 (deploy target).
