# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0-beta.2] - 2026-06-09

### Added
- **Durable persistence** — optional **PostgreSQL + Flyway** profile (`postgres`) with a two-container
  `docker-compose` setup (app + postgres + volume); H2 stays the default for local dev and tests (CD-024).
- **Admin dashboard** — admins land on a user-administration dashboard (user stats + recent activity +
  quick links) instead of the contacts page; sample `USER` accounts (`alice`, `bob`) are seeded on a
  fresh DB so the directory and user management have demo data (CD-044).
- **Postgres-backed browser e2e** (Testcontainers + Playwright) that exercises the real `postgres`
  profile in a browser, guarding the Postgres path in CI (CD-042).

### Changed
- The **admin owns no contacts** — it is a super-user that sees everyone's (an "admin view" banner);
  contacts belong to each user (CD-044).
- Contact photo is stored as PostgreSQL **`bytea`** (not a large object) for portable, transaction-safe
  persistence (CD-024).

### Fixed
- **App was unusable on PostgreSQL** — the tag-filter query (`SELECT DISTINCT … ORDER BY LOWER(t)`) is
  rejected by Postgres (accepted by H2), 500'ing `/contacts/tags` and bouncing every user to login;
  now sorted in-app (CD-042).
- **Contact photos didn't display in the browser** — the avatar `<img>` couldn't send the bearer token;
  now loaded via an authenticated fetch → blob URL (CD-043).

## [1.0.0-beta.1] - 2026-06-09

### Added
- **Contacts** — CRUD, paginated/sortable listing, free-text search, tags, favourites, notes,
  per-contact photo, CSV/JSON import & export, soft-delete + Trash + restore + Undo, bulk actions,
  optimistic concurrency (`@Version` → `412`), and a dark/light theme.
- **Authentication** — JWT register/login, Spring Security, brute-force login lockout (`423`).
- **Authorization** — `USER`/`ADMIN` roles with method security; per-user contact ownership
  (admins see all); per-owner email uniqueness; admin-only permanent delete.
- **Admin user management** — list/role/enable-disable/reset-password/delete, with self-protection.
- **Account self-service** — change own password; profile page.
- **Audit log** — append-only who/what/when trail + admin Activity page (`GET /api/v1/audit`).
- **Observability** — Spring Boot Actuator: `/actuator/health` (public) + `/actuator/metrics` (secured).
- **Admin console UX** — users search/role-status filter, sortable columns, summary stats bar,
  relative timestamps, copy-to-clipboard, styled confirmation dialog, bulk select + bulk actions,
  user detail modal, activity-log filters, client-side pagination.
- **Docs & tooling** — OpenAPI/Swagger, README + walkthrough with screenshots, MIT `LICENSE`,
  this `CHANGELOG`, a plain-text `tickets/` tracker.
- **Engineering** — GitHub Actions CI (coverage gate, CodeQL, dependency review), Dependabot
  (grouped, majors ignored), Docker image, and a Git Flow workflow with protected `master`/`develop`.

### Notes
- First tagged release (a **pre-release**). `master` is the release branch; each release tag
  (e.g. `v1.0.0-beta.1`) is cut from the `develop` → `master` release merge. A stable `v1.0.0` (GA)
  is still to come. Persistence is H2 file-mode and secrets default to dev values — see
  `docs/RELEASE-AND-DEPLOYMENT.md` for the durable-deploy roadmap (Postgres + Flyway, real secrets).
