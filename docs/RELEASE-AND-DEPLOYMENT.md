# Release & deployment plan

> **Status: planning only — nothing here is wired up yet.** This is the saved roadmap for turning a
> `develop` → `master` merge into a real, repeatable release and (optionally) a non-ephemeral
> deployment. Pick it up when you're ready; the actionable chunks are tracked as tickets
> **CD-022 … CD-025** (see [`../tickets/README.md`](../tickets/README.md)).

## TL;DR

- In Git Flow, **merging `develop` → `master` _is_ the release** — but a bare merge is informal.
  A real release adds a **version**, a **tag**, a dated **CHANGELOG** entry, a **GitHub Release**
  with artifacts, and a **runbook**. (→ CD-022, CD-023)
- The app is currently **ephemeral**: it stores data in an **H2 file DB** baked next to the process.
  Any container restart/redeploy on a fresh filesystem loses everything. To deploy somewhere durable
  you must move persistence off the local file — **managed Postgres + Flyway** (recommended) or a
  **persistent volume** — and set **real secrets**. (→ CD-024, CD-025)

---

## Part 1 — Make the release "real" (release hygiene)

Merging the release PR is necessary but not sufficient. A release others can trust and reproduce
needs the following.

> **Part 1 status: ✅ implemented (CD-022).** `pom.xml` is now `1.0.0`, `CHANGELOG.md` has a dated
> `## [1.0.0]` section, and `.github/workflows/release.yml` (idempotent) cuts the Release + JAR +
> versioned GHCR image on a `v*` tag. The remaining manual steps are the maintainer merge of the
> `develop` → `master` PR and pushing the `v1.0.0` tag.

### 1.1 Version bump

- ✅ `pom.xml` is `1.0.0` (was `0.0.1-SNAPSHOT`); the bump was done **on `develop`** so it flows into
  `master` via the release PR.
- Follow [SemVer](https://semver.org/): `MAJOR.MINOR.PATCH` for future bumps.

### 1.2 Git tag

- Tag the merge commit on `master`: `git tag -a v1.0.0 -m "v1.0.0" && git push origin v1.0.0`.
- The tag is the immutable pointer a release/build is cut from. **Maintainer action** (same hand that
  merges the release PR).

### 1.3 CHANGELOG

- `CHANGELOG.md` already follows *Keep a Changelog* with an `[Unreleased]` section.
- On release, move the `[Unreleased]` items under a new dated heading: `## [1.0.0] - YYYY-MM-DD`,
  and reset `[Unreleased]` to empty.

### 1.4 GitHub Release

- Cut a **GitHub Release** from tag `v1.0.0` with notes (the CHANGELOG section) and attach the built
  **JAR** so the artifact is downloadable and pinned to the tag.

### 1.5 `release.yml` automation (so the above is one button)

A `.github/workflows/release.yml` triggered on tag push (`v*`) that:

1. builds the JAR (`./mvnw -B clean verify`),
2. creates the GitHub Release from the tag + CHANGELOG section,
3. uploads the JAR as a release asset,
4. builds & pushes a **versioned** image to GHCR (`:1.0.0` **and** `:latest`) — CI already pushes a
   commit-tagged image, this adds the semver tag.

> Net effect: `git push origin v1.0.0` → a complete, reproducible release with notes + JAR + image.

### 1.6 Runbook

A short operational doc (deploy steps, required env/secrets, health-check URL, smoke test, rollback)
so a release can be deployed and, if needed, **rolled back** without tribal knowledge. See Part 2 for
the deploy mechanics it would reference.

---

## Part 2 — Make a deployment non-ephemeral (durable & deployable)

The app runs fine in a container today (the CI already builds & pushes an image to GHCR). The blocker
for a *real* deployment is **state**, then **secrets**, then **a host**.

### 2.1 The blocker: H2 file mode

- Data lives in `./data/contacts.mv.db` — a file **inside the container/working dir**.
- On most container hosts the filesystem is **ephemeral**: a redeploy/restart starts from the image,
  so **all users, contacts and audit history vanish**. This is fine for local/demo, fatal for a real
  environment.

**Two ways to fix it:**

| Option | What | When |
|---|---|---|
| **A — Managed Postgres + Flyway** (recommended) | Point Spring at a managed Postgres; add **Flyway** migrations so the schema is versioned and created on boot. | Real/shared/multi-instance deployments. The proper answer. |
| **B — Persistent volume** | Keep H2 but mount `./data` on a persistent volume so the file survives restarts. | Quick single-instance demo that must keep data. Doesn't scale horizontally. |

**Option A sketch:**

- Add `org.postgresql:postgresql` (runtime) and `org.flywaydb:flyway-core` deps.
- Add a `prod`/`postgres` Spring profile: `spring.datasource.url/username/password` from env,
  `spring.jpa.hibernate.ddl-auto=validate` (Flyway owns the schema, not Hibernate).
- Add `src/main/resources/db/migration/V1__init.sql` (and onward) — the versioned schema.
- Keep H2 for local dev + tests (existing profile) so the inner loop stays zero-setup.

### 2.2 Real secrets (no dev defaults in prod)

Today's defaults are knowingly public for dev: `admin/admin123` and a `change-me` JWT secret. A real
deployment must override, via env, at minimum:

- `APP_JWT_SECRET` — a long random secret (rotate-able).
- `APP_DEFAULT_ADMIN_PASSWORD` — strong; change immediately after first login.
- DB credentials (Option A): `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD`.

Set these as the host's secret store / env vars — **never** commit them.

### 2.3 Where to deploy (the image is ready)

The GHCR image can run on any container host. Cheapest-to-richest:

| Target | Notes |
|---|---|
| **Fly.io / Render / Railway** | Easiest: point at the image, add a managed Postgres add-on, set env secrets. Good first home. |
| **Google Cloud Run / AWS App Runner** | Serverless containers; scale-to-zero; pair with Cloud SQL / RDS. |
| **Azure Container Apps** | Matches the wider HMCTS/Azure tooling; pair with Azure DB for Postgres. |
| **Plain VPS (Docker Compose)** | Most control, most ops. App + Postgres + volume in one compose file. |

The app already exposes **`/actuator/health`** (public) — wire it as the platform's liveness/readiness
probe. Metrics (`/actuator/metrics`, `/actuator/prometheus` if enabled) stay authenticated.

### 2.4 Optional: `deploy.yml` (continuous deployment)

Once a host is chosen, a `.github/workflows/deploy.yml` (triggered on release published, or on push to
`master`) can deploy the freshly built image to that host automatically — turning release into deploy.
Keep it manual/`workflow_dispatch` first; promote to automatic once confident.

---

## Recommended sequence

1. **CD-022 — Release plumbing:** version bump (→ `1.0.0`), `release.yml` (tag → Release + JAR +
   versioned GHCR image), CHANGELOG release step. *(Release hygiene; no infra needed.)*
2. **CD-023 — Runbook:** `docs/RUNBOOK.md` — deploy/rollback/smoke-test/secrets, referencing Part 2.
3. **CD-024 — Durable persistence:** Postgres + Flyway behind a `prod` profile (H2 stays for dev/test).
4. **CD-025 — Deploy target + `deploy.yml`:** pick a host, provision Postgres + secrets, deploy the
   image, wire `/actuator/health` probes, optional CD workflow.

Steps 1–2 are pure release hygiene and can be done anytime. Steps 3–4 are only needed when you
actually want a long-lived, data-retaining environment.

## What already exists (don't redo)

- ✅ Containerised: multi-stage `Dockerfile`; CI builds & pushes a commit-tagged image to GHCR.
- ✅ Health endpoint: `/actuator/health` public (CD-002) — ready to use as a probe.
- ✅ `CHANGELOG.md` (Keep a Changelog) + `LICENSE` (CD-003).
- ✅ Protected `master` with maintainer-only release merges; `develop` integration (CD-001/CD-004).
- ✅ Test/coverage gate + e2e evidence (HTTP + Playwright) gating what reaches `master`.
- ✅ Release plumbing (CD-022): version `1.0.0`, dated CHANGELOG, idempotent `release.yml` (tag `v*` →
  GitHub Release + JAR + versioned GHCR image — works from a CLI tag *or* the GitHub UI).

## What's missing (this plan)

- ⬜ Runbook (CD-023).
- ⬜ Durable DB — Postgres + Flyway (or volume) (CD-024).
- ⬜ A real deploy target + optional `deploy.yml` (CD-025).
