# Contact Directory — Features

A complete map of what the app does, grouped by area. Built incrementally, each area committed
separately. **219 tests passing** (plus a Playwright browser e2e walkthrough). For setup and API reference see the [README](README.md); for a
click-through tour see [docs/WALKTHROUGH.md](docs/WALKTHROUGH.md).

Legend: ✅ done · 🔄 in progress · ⬜ planned

---

## Core contact management

| # | Feature | Status |
|---|---------|--------|
| 1 | Search & filter — live search by name, company, phone, email | ✅ |
| 2 | Tags / categories — label contacts (Friend, Work, Client, Family) + filter | ✅ |
| 3 | Favourite / star — pin important contacts to the top | ✅ |
| 4 | Import / export — CSV import (bulk add), export CSV/JSON | ✅ |
| 5 | Avatar / initials — auto-coloured initials circle, or photo upload | ✅ |
| 6 | Sort controls — name A–Z, recently added, etc. | ✅ |
| 7 | Contact detail modal — full profile card (details, notes, links) | ✅ |
| 8 | Notes field — free-text notes per contact | ✅ |
| 9 | Click-to-action — `tel:` / `mailto:` links | ✅ |
| 10 | Dark / light mode toggle — saved to `localStorage` | ✅ |

![Contacts list](docs/screenshots/02-contacts.png)

Same screen in **dark mode** (toggle saved per browser):

![Contacts in dark mode](docs/screenshots/06-dark-mode.png)

## Safe management

| Feature | Status |
|---------|--------|
| Soft delete + Trash + Undo — `DELETE` soft-deletes; `GET /trash`, restore, delete-forever; Undo toast | ✅ |
| Bulk actions — multi-select rows; bulk favourite / tag / delete (`POST /bulk/*`) | ✅ |
| Optimistic concurrency — `@Version` on contacts; stale edits return `412 Precondition Failed` | ✅ |

## Authentication

| Feature | Status |
|---------|--------|
| JWT login & registration — stateless bearer tokens; styled sign-in / create-account page | ✅ |
| Spring Security — protected REST API; JSON `401` / `403` responses | ✅ |
| Brute-force lockout — repeated failed logins lock an account (`423 Locked`) | ✅ |

![Sign in](docs/screenshots/01-login.png)

## Roles & access control

| Feature | Status |
|---------|--------|
| Roles — `USER` and `ADMIN`, enforced with method security | ✅ |
| Per-user ownership — a `USER` sees/manages only their own contacts; an `ADMIN` sees all | ✅ |
| Per-owner email uniqueness — two users can each have the same email; cross-user access → `404` | ✅ |
| Admin-only permanent delete — irreversible purge restricted to admins | ✅ |
| Admin user management — list users, change role, enable/disable, reset password, delete | ✅ |
| Self-protection — an admin can't demote, disable or delete their own account | ✅ |

![Admin user management](docs/screenshots/03-users.png)

## Account self-service

| Feature | Status |
|---------|--------|
| Change password — current + new with confirmation | ✅ |
| Profile page — username, role and member-since | ✅ |

![Profile & change password](docs/screenshots/04-profile.png)

## Audit log (edit history)

| Feature | Status |
|---------|--------|
| Append-only audit trail — records who did what and when | ✅ |
| Coverage — contact create/edit/delete/restore/bulk/import, user-management actions, logins & registrations | ✅ |
| Admin Activity page — `GET /api/v1/audit`, newest-first, filterable by actor and action | ✅ |
| Resilient recording — an audit-write failure never breaks the underlying action | ✅ |

![Admin activity log](docs/screenshots/05-activity.png)

## Observability (health & metrics)

| Feature | Status |
|---------|--------|
| Spring Boot Actuator — `health`, `info`, `metrics` exposed over HTTP | ✅ |
| `/actuator/health` public — orchestration liveness/readiness probes (no token) | ✅ |
| `/actuator/metrics` secured — requires a bearer token | ✅ |
| Health detail shown to authenticated callers only (`show-details: when-authorized`) | ✅ |

---

## Admin & UI enhancements

Admin-console UX improvements delivered as CD-006…CD-014 via the Git Flow:

| Feature | Status |
|---------|--------|
| Users table — search + role/status filter | ✅ |
| Users table — sortable columns | ✅ |
| Users page — summary stats bar (total / admins / enabled / disabled) | ✅ |
| Relative timestamps (absolute on hover) across Users + Activity | ✅ |
| Copy-to-clipboard buttons (username / email) | ✅ |
| Styled confirmation dialog (replaces native confirm) | ✅ |
| Users table — bulk select + bulk actions | ✅ |
| User detail modal (details + recent activity) | ✅ |
| Activity log — actor + multi-select action + date-range filters | ✅ |
| Users table — client-side pagination (page-size + Prev/Next) | ✅ |

## Platform notes

- **Stack:** Spring Boot 3.3.5, Java 21, Spring Data JPA + Hibernate, Spring Security + JWT, vanilla
  HTML/CSS/JS frontend, springdoc OpenAPI.
- **Persistence:** H2 **file mode** (`./data/contacts.mv.db`) — data survives restarts; tests use an
  isolated in-memory H2.
- **Tests:** **219** across 18 classes (unit + full-stack + HTTP e2e), incl. cross-user isolation,
  role enforcement, optimistic concurrency, account self-service, lockout, audit and Actuator
  health/metrics coverage. A separate Playwright **browser e2e** (`PlaywrightE2eTest`, tag-excluded
  from the default build) drives the real UI and saves screenshots + video; it runs only on
  `master`/`develop` via [`e2e.yml`](.github/workflows/e2e.yml).

## Possible next steps

- ⬜ LICENSE + CHANGELOG (CD-003)
- ⬜ Hibernate Envers — field-level revision history with one-click restore
- ⬜ Forgot-password flow (needs SMTP wired)
- ⬜ Richer contacts — multiple emails/phones/addresses, vCard import/export
