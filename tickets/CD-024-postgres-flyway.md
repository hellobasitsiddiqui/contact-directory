# CD-024: Durable persistence (Postgres + Flyway)

- **Type:** backend / infra
- **Status:** Open
- **Branch:** `CD-024-postgres-flyway`

## Summary
Remove the ephemerality blocker: the app stores data in an H2 file (`./data/contacts.mv.db`) that is
lost on a fresh-filesystem restart. Move production persistence to managed Postgres with versioned
Flyway migrations. See [`../docs/RELEASE-AND-DEPLOYMENT.md`](../docs/RELEASE-AND-DEPLOYMENT.md) §2.1.

## Acceptance criteria
- [ ] Add deps: `org.postgresql:postgresql` (runtime), `org.flywaydb:flyway-core`.
- [ ] Add a `prod`/`postgres` Spring profile: datasource URL/user/pass from env;
      `spring.jpa.hibernate.ddl-auto=validate` (Flyway owns the schema).
- [ ] Add `src/main/resources/db/migration/V1__init.sql` matching the current entities.
- [ ] Keep H2 for local dev + tests (existing profile) — inner loop stays zero-setup.
- [ ] Verify the gate (tests) still passes on H2.

## Notes
Alternative for a quick single-instance demo: a persistent volume mounted at `./data` (Option B) — no
schema work but doesn't scale horizontally. Postgres is the recommended path.
