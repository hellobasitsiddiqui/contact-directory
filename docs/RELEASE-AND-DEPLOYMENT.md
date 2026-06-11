# Release & deployment plan

> **Status: mostly implemented.** Release hygiene (CD-022) and durable Postgres + Flyway persistence
> (CD-024) are **done and released**; three pre-releases (`v1.0.0-beta.1`, `v1.0.0-beta.2`,
> `v1.0.0-beta.3`) have been cut. What remains is an operations **runbook** (CD-023) and a real **deploy target** + optional
> `deploy.yml` (CD-025). Tracked as tickets **CD-022 … CD-025** (see
> [`../tickets/README.md`](../tickets/README.md)).

## TL;DR

- In Git Flow, **merging `develop` → `master` _is_ the release** — and a real release now also adds a
  **version**, a **tag**, a dated **CHANGELOG** entry, and a **GitHub Release** with artifacts. This is
  wired: `release.yml` (idempotent, pre-release-aware) turns a `v*` tag into a Release + JAR + versioned
  GHCR image. ✅ (CD-022, CD-038, CD-039)
- Persistence is no longer a blocker. The default is durable **H2 file mode**
  (`./data/contacts.mv.db`); for a real/shared deployment an optional **`postgres` profile**
  (PostgreSQL + **Flyway**) ships with a two-container `docker-compose.yml`. ✅ (CD-024)
- Still open: a short **runbook** (CD-023) and an actual **deploy target** with real secrets, plus an
  optional CD workflow (CD-025).

---

## Part 1 — Make the release "real" (release hygiene)

Merging the release PR is necessary but not sufficient. A release others can trust and reproduce needs
the following.

> **Part 1 status: ✅ implemented (CD-022, CD-038, CD-039).** `pom.xml` is `1.0.0-beta.3`,
> `CHANGELOG.md` has a dated section for each cut beta, and
> `.github/workflows/release.yml` (idempotent, **pre-release-aware**) cuts the Release + JAR + versioned
> GHCR image on a `v*` tag — from a CLI tag *or* the GitHub UI. Three pre-releases have been cut
> (`v1.0.0-beta.1`, `v1.0.0-beta.2`, `v1.0.0-beta.3`). The remaining manual steps per release are the
> maintainer merge of the `develop` → `master` PR and pushing the version tag. A stable **`v1.0.0`
> (GA)** is still to come.

### 1.1 Version bump

- ✅ `pom.xml` is `1.0.0-beta.3` (was `0.0.1-SNAPSHOT`); each bump is done **on `develop`** so it flows
  into `master` via the release PR.
- Follow [SemVer](https://semver.org/): `MAJOR.MINOR.PATCH`, with a `-beta.N` suffix for pre-releases.
  Dropping the suffix (plain `1.0.0`) is the GA.

### 1.2 Git tag

- Tag the merge commit on `master`, e.g.
  `git tag -a v1.0.0-beta.2 -m "v1.0.0-beta.2" && git push origin v1.0.0-beta.2` — or use the GitHub UI
  ("Draft a new release"). A **hyphenated** tag (`-beta.N`) is published as a GitHub **pre-release**.
- The tag is the immutable pointer a release/build is cut from. **Maintainer action** (same hand that
  merges the release PR).

### 1.3 CHANGELOG

- `CHANGELOG.md` follows *Keep a Changelog* with an `[Unreleased]` section.
- On release, move the `[Unreleased]` items under a new dated heading (e.g.
  `## [1.0.0-beta.2] - YYYY-MM-DD`) and reset `[Unreleased]` to empty.

### 1.4 GitHub Release

- Cut a **GitHub Release** from the tag (e.g. `v1.0.0-beta.2`) with notes (the CHANGELOG section) and
  the built **JAR** attached. `release.yml` does this automatically and adds `--prerelease` for
  hyphenated tags.

### 1.5 `release.yml` automation (so the above is one button)

`.github/workflows/release.yml` is triggered on tag push (`v*`) and:

1. builds the JAR (`./mvnw -B clean verify`),
2. creates **or updates** (idempotent, CD-038) the GitHub Release from the tag + CHANGELOG section,
   marking it `--prerelease` for hyphenated tags (CD-039),
3. uploads the JAR as a release asset,
4. builds & pushes a **versioned** image to GHCR (e.g. `:1.0.0-beta.2`). For a **stable**
   (non-prerelease) tag it also moves `:latest`; **pre-release** tags push only the `:version` tag and
   leave `:latest` untouched (reserved for GA).

> Net effect: `git push origin v1.0.0-beta.2` (or a UI release) → a complete, reproducible release with
> notes + JAR + image.

### 1.6 Runbook ⬜ (CD-023)

A short operational doc (deploy steps, required env/secrets, health-check URL, smoke test, rollback) so
a release can be deployed and, if needed, **rolled back** without tribal knowledge. See Part 2 for the
deploy mechanics it would reference.

---

## Part 2 — Make a deployment non-ephemeral (durable & deployable)

The app runs fine in a container today (CI builds & pushes an image to GHCR, and release tags push a
versioned image). State is handled (Part 2.1); what remains for a *real* deployment is **secrets** and
**a host**.

### 2.1 Persistence — solved ✅ (CD-024)

- Local dev defaults to **H2 file mode** at `./data/contacts.mv.db`, which survives restarts (`./data`
  is git-ignored). Tests use an isolated in-memory H2.
- For a real/shared deployment, the shipped **`postgres` Spring profile** points at PostgreSQL with
  **Flyway** owning the schema. Either way, data is durable.

| Option | What | When |
|---|---|---|
| **A — PostgreSQL + Flyway** (recommended) ✅ shipped | The `postgres` profile: `spring.datasource.*` from env, `ddl-auto=validate`, Flyway applies `db/migration/V1__init.sql`, contact photo stored as `bytea`. A two-container `docker-compose.yml` (app + Postgres + `pgdata` volume, env via `.env` / `.env.example`) runs it. | Real/shared/multi-instance deployments. The proper answer. |
| **B — Persistent volume (H2)** | Keep H2 but mount `./data` on a persistent volume so the file survives restarts. | Quick single-instance demo that must keep data. Doesn't scale horizontally. |

> The `docker-compose.yml` shipped with Option A is usable directly on a VPS (`cp .env.example .env`,
> edit secrets, `docker compose up --build`). Local dev and tests keep using H2, so the inner loop is
> unchanged.

### 2.2 Real secrets (no dev defaults in prod)

Today's defaults are knowingly public for dev: `admin/admin123` (plus seeded sample users
`alice`/`bob`) and a `change-me` JWT secret. A real deployment must override, via env, at minimum:

- `APP_JWT_SECRET` — a long random secret (rotate-able).
- `APP_DEFAULT_ADMIN_PASSWORD` — strong; change immediately after first login.
- DB credentials (Option A): `SPRING_DATASOURCE_URL` / `_USERNAME` / `_PASSWORD` (the compose file wires
  these from `.env`).

Set these as the host's secret store / env vars — **never** commit them.

### 2.3 Where to deploy (the image is ready)

The GHCR image can run on any container host. Cheapest-to-richest:

| Target | Notes |
|---|---|
| **Fly.io / Render / Railway** | Easiest: point at the image, add a managed Postgres add-on, set env secrets. Good first home. |
| **Google Cloud Run / AWS App Runner** | Serverless containers; scale-to-zero; pair with Cloud SQL / RDS. |
| **Azure Container Apps** | Matches the wider HMCTS/Azure tooling; pair with Azure DB for Postgres. |
| **Plain VPS (Docker Compose)** | Most control, most ops. The shipped `docker-compose.yml` (app + Postgres + volume) runs as-is. |

The app exposes **`/actuator/health`** (public) — wire it as the platform's liveness/readiness probe.
Metrics (`/actuator/metrics`, `/actuator/prometheus` if enabled) stay authenticated.

**HTTPS/TLS (CD-027).** The app emits **HSTS** on HTTPS requests and can run behind a TLS-terminating
reverse proxy (the usual production pattern, and how PaaS hosts terminate TLS for you). A ready-to-use
**Caddy** overlay with automatic Let's Encrypt certs ships as `docker-compose.tls.yml` + `Caddyfile`:
`docker compose -f docker-compose.yml -f docker-compose.tls.yml up --build` (set `DOMAIN` in `.env`).
The overlay is safe by construction — it enables forwarded-header trust on the app
(`SERVER_FORWARD_HEADERS_STRATEGY=framework`, so it honours the proxy's `X-Forwarded-Proto=https`), and
the base compose binds the app's `8080` to **host loopback** so only Caddy is internet-facing. That
trust is **off by default**: a directly-exposed app must not honour client-supplied `X-Forwarded-*`.
Standing up the actual host is the deploy step (CD-025).

### 2.4 Optional: `deploy.yml` (continuous deployment) ⬜ (CD-025)

Once a host is chosen, a `.github/workflows/deploy.yml` (triggered on release published, or on push to
`master`) can deploy the freshly built image to that host automatically — turning release into deploy.
Keep it manual/`workflow_dispatch` first; promote to automatic once confident.

---

## Recommended sequence

1. ✅ **CD-022 — Release plumbing:** version bump, `release.yml` (tag → Release + JAR + versioned GHCR
   image), CHANGELOG release step. *(Done; extended by CD-038 idempotency + CD-039 pre-release support.)*
2. ⬜ **CD-023 — Runbook:** `docs/RUNBOOK.md` — deploy/rollback/smoke-test/secrets, referencing Part 2.
3. ✅ **CD-024 — Durable persistence:** PostgreSQL + Flyway behind the `postgres` profile (H2 file mode
   stays the local/dev/test default). *(Done, released in `v1.0.0-beta.2`.)*
4. ⬜ **CD-025 — Deploy target + `deploy.yml`:** pick a host, provision Postgres + secrets, deploy the
   image, wire `/actuator/health` probes, optional CD workflow.

Steps 1 and 3 are done. Step 2 is pure release hygiene and can be done anytime. Step 4 is needed when
you actually want a long-lived, data-retaining environment.

## What already exists (don't redo)

- ✅ Containerised: multi-stage `Dockerfile`; CI builds & pushes a commit-tagged image to GHCR.
- ✅ Health endpoint: `/actuator/health` public (CD-002) — ready to use as a probe.
- ✅ `CHANGELOG.md` (Keep a Changelog) + `LICENSE` (CD-003).
- ✅ Protected `master` with maintainer-only release merges; `develop` integration (CD-001/CD-004).
- ✅ Test/coverage **gate** (`mvn verify`, browser e2e excluded) gating what reaches `master`; the
  three Playwright browser e2e suites (H2 walkthrough + Testcontainers Postgres + silent token
  refresh) run separately via `e2e.yml` on `develop`/`master`.
- ✅ Release plumbing (CD-022): version, dated CHANGELOG, **idempotent** `release.yml` (CD-038),
  **pre-release-aware** (CD-039) — works from a CLI tag *or* the GitHub UI; three betas cut.
- ✅ Durable persistence (CD-024): optional `postgres` profile (PostgreSQL + Flyway, `V1__init.sql`,
  `bytea` photos) + two-container `docker-compose.yml`.
- ✅ TLS-ready (CD-027): app is proxy-aware (`forward-headers-strategy`) + emits HSTS; a Caddy
  reverse-proxy overlay (`docker-compose.tls.yml` + `Caddyfile`, automatic Let's Encrypt) ships for
  HTTPS. Wiring it on a real host is the deploy step (CD-025).

## What's missing (this plan)

- ⬜ Runbook (CD-023).
- ⬜ A real deploy target + optional `deploy.yml` (CD-025) — including wiring the shipped TLS overlay
  (CD-027) to the chosen host + domain.
