# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-06-08

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
- First tagged release. `master` is the release branch; `v1.0.0` is cut from the `develop` → `master`
  release merge. Persistence is H2 file-mode and secrets default to dev values — see
  `docs/RELEASE-AND-DEPLOYMENT.md` for the durable-deploy roadmap (Postgres + Flyway, real secrets).
