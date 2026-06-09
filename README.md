# Contact Directory

[![CI](https://github.com/hellobasitsiddiqui/contact-directory/actions/workflows/ci.yml/badge.svg)](https://github.com/hellobasitsiddiqui/contact-directory/actions/workflows/ci.yml)

A full-stack **contact manager** built with **Spring Boot 3.5.14**, **Java 21**, and **Maven** — a
JSON REST API plus a framework-free browser UI. It features **JWT authentication**, **role-based
access** (USER / ADMIN), **per-user contact ownership**, and account **self-service**, all backed by
a persistent file-mode **H2** database and covered by **219 automated tests** (plus a browser
end-to-end walkthrough).

## Screenshots

| Sign in | Admin dashboard — user administration |
|---|---|
| ![Login screen](docs/screenshots-v1.0.0-beta.2/01-login.png) | ![Admin dashboard](docs/screenshots-v1.0.0-beta.2/02-dashboard.png) |

| Contacts (admin view — all users' contacts) | Admin user management — search, filters, stats, bulk & pagination |
|---|---|
| ![Contacts list](docs/screenshots-v1.0.0-beta.2/03-contacts.png) | ![User management](docs/screenshots-v1.0.0-beta.2/04-users.png) |

| My profile | Admin activity log — filter by actor / action / date range |
|---|---|
| ![Profile & change password](docs/screenshots-v1.0.0-beta.2/05-profile.png) | ![Activity log](docs/screenshots-v1.0.0-beta.2/06-activity.png) |

**Dark mode** (theme toggle, saved per browser)

![Contacts in dark mode](docs/screenshots-v1.0.0-beta.2/07-dark-mode.png)

> A step-by-step walkthrough with these screens lives in [docs/WALKTHROUGH.md](docs/WALKTHROUGH.md).

## Features

### Contacts
- Full CRUD (create, read, replace, partial update, delete)
- Paginated, sortable listing with free-text search across name, email, company and phone
- **Tags / categories** and **favourites** (favourites pinned to the top)
- **Soft delete → Trash → restore**, plus permanent delete (admin-only) and an Undo toast
- **Bulk actions** — multi-select to delete / favourite / tag many at once
- **Import / export** — CSV import (with header or positional rows), export to CSV and JSON
- One optional **photo** per contact (upload / serve / delete; initials avatar fallback)
- **Optimistic concurrency** via `@Version` — stale edits return `412 Precondition Failed`
- **Notes**, click-to-action `tel:` / `mailto:` links, and a **dark / light** theme toggle

### Accounts & security
- **JWT authentication** — register / login for a stateless bearer token; styled login page
- **Roles** — `USER` and `ADMIN`, enforced with method security
- **Per-user ownership** — a `USER` sees and manages only their own contacts; an `ADMIN` sees all.
  Email uniqueness is per-owner, and cross-user access returns `404` (never reveals existence)
- **Admin user management** — list users, change roles, enable/disable, reset passwords, delete
  (with self-protection: an admin can't demote, disable or delete their own account)
- **Account self-service** — change your own password and a profile page
- **Brute-force lockout** — repeated failed logins temporarily lock an account (`423 Locked`)
- **Audit log** — contact mutations, user-management actions and logins are recorded; admins get
  an Activity page backed by `GET /api/v1/audit` (filterable by actor and action)

### Admin console UX
- **Users table** — live search + role/status filter, sortable columns, a summary stats bar, and
  **bulk actions** (multi-select → enable/disable/role/delete)
- **User detail modal** — click a row for details + that user's recent activity
- **Activity log** — filter by actor, multi-select action, and date range
- **Relative timestamps** (exact time on hover), **copy-to-clipboard** for usernames/emails, and a
  styled **confirmation dialog** for destructive actions

## Tech stack

| Concern      | Choice                                          |
|--------------|-------------------------------------------------|
| Language     | Java 21                                         |
| Framework    | Spring Boot 3.5.14 (Web, Data JPA, Security)     |
| Auth         | Spring Security + JWT (`io.jsonwebtoken` / jjwt) |
| Persistence  | Spring Data JPA + Hibernate; H2 (file mode)      |
| Validation   | Jakarta Bean Validation                         |
| API docs     | springdoc-openapi (Swagger UI)                  |
| Frontend     | Vanilla HTML / CSS / JS (no framework)           |
| Tests        | JUnit 5 + Spring Test + Mockito; JaCoCo coverage |
| Build        | Maven (wrapper)                                 |

## Running the application

```bash
./mvnw spring-boot:run
```

The app starts on **http://localhost:8080** and opens to the **sign-in screen**.

On first run a default **admin** account is seeded, plus two sample **users** (`alice`, `bob`) who own
a few sample contacts — so the admin's user-management screen and the contact directory both have
something to show. Admins land on a **dashboard** (user administration); regular users land on their
contacts. Data is stored in a **persistent** H2 file database (`./data/contacts.mv.db`), so it
**survives restarts** (the `./data` directory is git-ignored).

### Default logins (dev only)

| Username | Password   | Role  | Lands on  |
|----------|------------|-------|-----------|
| `admin`  | `admin123` | ADMIN | Dashboard |
| `alice`  | `alice123` | USER  | Contacts  |
| `bob`    | `bob123`   | USER  | Contacts  |

> ⚠️ These are local/dev defaults — change or remove them (and the JWT secret) via environment
> variables before deploying (see [Configuration](#configuration)). New users can self-register from
> the **Create account** tab and are created with the `USER` role. The admin owns no contacts; it sees
> everyone's (an "admin view"), and its job is managing users.

### Build a runnable JAR

```bash
./mvnw clean package
java -jar target/*.jar
```

### Run with PostgreSQL (Docker Compose)

Local dev uses embedded **H2** (above). For a durable, production-like stack, run the app against
**PostgreSQL** with **Flyway**-managed schema via the two-container setup (CD-024):

```bash
cp .env.example .env        # then edit: set real DB password + APP_JWT_SECRET
docker compose up --build   # starts app (postgres profile) + postgres + a persistent volume
```

The app runs under the `postgres` Spring profile (`SPRING_PROFILES_ACTIVE=postgres`): Flyway applies
`src/main/resources/db/migration/V1__init.sql`, Hibernate only **validates** the schema, and data is
stored in the `pgdata` volume so it **survives restarts**. H2 stays the default for local dev and
tests.

### Serve over HTTPS (TLS)

The app listens on plain HTTP and emits an **HSTS** header on HTTPS requests, so TLS is terminated by a
reverse proxy in front of it (the usual production pattern; also how PaaS hosts work). A ready-to-use
**Caddy** overlay (automatic Let's Encrypt certificates) ships as `docker-compose.tls.yml`:

```bash
cp .env.example .env        # set DOMAIN to a real hostname pointing at this host
docker compose -f docker-compose.yml -f docker-compose.tls.yml up --build
```

Caddy obtains/renews the certificate for `$DOMAIN` and proxies HTTPS → the app over the internal
network. The overlay makes the stack safe by construction: it enables forwarded-header trust on the app
(`SERVER_FORWARD_HEADERS_STRATEGY=framework`, so the app honours Caddy's `X-Forwarded-Proto=https`), and
the base compose already binds the app's `8080` to **host loopback** so only Caddy (80/443) is
internet-facing. Forwarded-header trust is **off by default** — a directly-exposed app must not trust
client-supplied `X-Forwarded-*`. (For a quick local TLS smoke-test you can set `DOMAIN=localhost`, but
Caddy then serves a cert from its own untrusted CA and the browser may pin HSTS for `localhost` — use a
real domain for anything real.) Picking the actual
host is tracked as **CD-025**; see [docs/RELEASE-AND-DEPLOYMENT.md](docs/RELEASE-AND-DEPLOYMENT.md) §2.

## Authentication flow

All `/api/v1/contacts/**` and `/api/v1/users/**` endpoints require a bearer token. Obtain one from
the auth endpoints, then send it as `Authorization: Bearer <token>`.

```bash
# 1) Log in (or POST the same body to /register to create a USER account)
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

# 2) Use it
curl http://localhost:8080/api/v1/contacts -H "Authorization: Bearer $TOKEN"
```

| Method | Path                          | Description                              | Auth        |
|--------|-------------------------------|------------------------------------------|-------------|
| `POST` | `/api/v1/auth/register`       | Create a USER account, returns a JWT     | public      |
| `POST` | `/api/v1/auth/login`          | Log in, returns a JWT                     | public      |
| `GET`  | `/api/v1/auth/me`             | Current user (username, role, createdAt) | bearer      |
| `POST` | `/api/v1/auth/change-password`| Change your own password                  | bearer      |

## API endpoints

### Contacts — base path `/api/v1/contacts` (bearer token required)

| Method   | Path                              | Description                                | Success |
|----------|-----------------------------------|--------------------------------------------|---------|
| `POST`   | `/api/v1/contacts`                | Create a contact (owned by the caller)     | `201`   |
| `GET`    | `/api/v1/contacts`                | List (paginated, `?search=`, `?tag=`)      | `200`   |
| `GET`    | `/api/v1/contacts/{id}`           | Get one by id                              | `200`   |
| `PUT`    | `/api/v1/contacts/{id}`           | Replace                                     | `200`   |
| `PATCH`  | `/api/v1/contacts/{id}`           | Partial update                             | `200`   |
| `DELETE` | `/api/v1/contacts/{id}`           | Soft-delete (move to trash)                | `204`   |
| `GET`    | `/api/v1/contacts/trash`          | List trashed contacts                      | `200`   |
| `POST`   | `/api/v1/contacts/{id}/restore`   | Restore from trash                         | `200`   |
| `DELETE` | `/api/v1/contacts/{id}/permanent` | Permanently delete (**ADMIN only**)        | `204`   |
| `POST`   | `/api/v1/contacts/bulk/delete`    | Bulk soft-delete                           | `200`   |
| `POST`   | `/api/v1/contacts/bulk/favorite`  | Bulk set/clear favourite                   | `200`   |
| `POST`   | `/api/v1/contacts/bulk/tags`      | Bulk add/remove tags                       | `200`   |
| `GET`    | `/api/v1/contacts/export.csv`     | Export all (CSV)                           | `200`   |
| `GET`    | `/api/v1/contacts/export.json`    | Export all (JSON)                          | `200`   |
| `POST`   | `/api/v1/contacts/import`         | Import contacts from a CSV file            | `200`   |
| `GET`    | `/api/v1/contacts/tags`           | Distinct tags in use                       | `200`   |
| `GET`    | `/api/v1/contacts/{id}/photo`     | Download the contact's photo               | `200`   |
| `POST`   | `/api/v1/contacts/{id}/photo`     | Upload/replace the photo (`multipart`, field `file`) | `200` |
| `DELETE` | `/api/v1/contacts/{id}/photo`     | Remove the photo                           | `204`   |

Photo uploads accept **PNG, JPEG, GIF, WEBP** up to **2 MB**; oversized files return `413`,
unsupported types return `400`.

### User management — base path `/api/v1/users` (**ADMIN only**)

| Method   | Path                                  | Description                |
|----------|---------------------------------------|----------------------------|
| `GET`    | `/api/v1/users`                       | List all users             |
| `PATCH`  | `/api/v1/users/{id}/role`             | Change a user's role       |
| `PATCH`  | `/api/v1/users/{id}/enabled`          | Enable / disable a user    |
| `POST`   | `/api/v1/users/{id}/reset-password`   | Reset a user's password    |
| `DELETE` | `/api/v1/users/{id}`                  | Delete a user              |

### Audit log — base path `/api/v1/audit` (**ADMIN only**)

| Method | Path            | Description                                                       |
|--------|-----------------|------------------------------------------------------------------|
| `GET`  | `/api/v1/audit` | List recorded events (paginated, newest first; `?actor=`, `?action=`) |

### Operational — base path `/actuator`

Spring Boot Actuator endpoints for deployment and monitoring. **Health** is public so
orchestration liveness/readiness probes can poll it unauthenticated; **metrics** require a
bearer token.

| Method | Path                       | Description                                  | Auth   |
|--------|----------------------------|----------------------------------------------|--------|
| `GET`  | `/actuator/health`         | Liveness/readiness status (`UP`/`DOWN`)      | public |
| `GET`  | `/actuator/info`           | App info (name/description from `info.*`)    | public |
| `GET`  | `/actuator/metrics`        | List available meters                        | bearer |
| `GET`  | `/actuator/metrics/{name}` | A single meter's value (e.g. `jvm.memory.used`) | bearer |

Health detail (DB, disk, components) is shown to authenticated callers only
(`management.endpoint.health.show-details: when-authorized`); anonymous probes get just the
top-level status.

### Listing query parameters

| Parameter | Default    | Description                                                         |
|-----------|------------|--------------------------------------------------------------------|
| `search`  | _(none)_   | Free-text match across first name, last name, email, company, phone |
| `tag`     | _(none)_   | Restrict to contacts carrying this tag (case-insensitive)          |
| `page`    | `0`        | Zero-based page index                                               |
| `size`    | `20`       | Page size                                                          |
| `sort`    | `lastName` | Sort property and direction, e.g. `sort=lastName,desc`             |

## Configuration

All defaults are dev-friendly and overridable via environment variables:

| Variable                     | Default                         | Purpose                              |
|------------------------------|---------------------------------|--------------------------------------|
| `APP_JWT_SECRET`             | a `change-me…` placeholder       | HS256 signing secret (≥ 32 bytes)    |
| `APP_JWT_EXPIRATION_MS`      | `86400000` (24h)                | Token lifetime                       |
| `APP_DEFAULT_ADMIN_USERNAME` | `admin`                         | Seeded admin username                |
| `APP_DEFAULT_ADMIN_PASSWORD` | `admin123`                      | Seeded admin password                |
| `app.security.max-login-attempts` | `5`                        | Failed logins before lockout         |
| `app.security.lockout-minutes`    | `15`                       | Lockout duration                     |

## Testing

```bash
./mvnw clean verify   # unit + integration + HTTP e2e, plus the JaCoCo coverage gate
```

**219 tests** across 18 classes (unit + full-stack integration + HTTP end-to-end), including
cross-user isolation, role enforcement, optimistic concurrency, account self-service, lockout and the
Actuator health/metrics surface. A JaCoCo coverage report is written to
`target/site/jacoco/index.html`.

Two **browser end-to-end** suites drive the real web UI in headless Chromium via
[Playwright](https://playwright.dev/java/): `PlaywrightE2eTest` walks login → dashboard → contacts →
users → activity → profile on H2 (saving screenshots + a video to `target/playwright/`), and
`PlaywrightPostgresE2eTest` runs against a **Testcontainers PostgreSQL** to prove the real `postgres`
profile (Flyway + the `bytea` photo round-trip) in a browser. Both are tagged `e2e` and
**excluded from the default build**; they run only on `master`/`develop` (and on demand) via the
[`e2e.yml`](.github/workflows/e2e.yml) workflow. See [CONTRIBUTING.md](CONTRIBUTING.md) to run them
locally.

## Interactive docs & tooling

| Tool        | URL                                     |
|-------------|-----------------------------------------|
| Web UI      | http://localhost:8080/                   |
| Swagger UI  | http://localhost:8080/swagger-ui.html    |
| OpenAPI doc | http://localhost:8080/v3/api-docs        |
| H2 console  | http://localhost:8080/h2-console         |
| Health      | http://localhost:8080/actuator/health    |
| Metrics     | http://localhost:8080/actuator/metrics (bearer token) |

H2 console connection: JDBC URL `jdbc:h2:file:./data/contacts`, user `sa`, blank password.

## Contributing

**Git Flow**, ticket-driven, PR-based workflow with protected `master` + `develop`. In short: create
a `CD-NNN` ticket in [tickets/](tickets/), branch off `develop`, open a PR into `develop` (CI must
pass); an automated agent reviews it and **merges to `develop`**. Releases go `develop` → `master`,
merged by the maintainer. Full details in [CONTRIBUTING.md](CONTRIBUTING.md).

## Project structure

```
src/main/java/com/example/contacts
├── ContactDirectoryApplication.java   # Spring Boot entry point
├── config/        # OpenAPI config + DataInitializer (seeds admin & samples)
├── controller/    # REST controllers (Contact, Auth, User)
├── security/      # JWT service, filter, SecurityConfig, CurrentUserService
├── service/       # Business logic (Contact, User, LoginAttempt, CsvSupport)
├── repository/    # Spring Data JPA repositories
├── model/         # JPA entities (Contact, User, Role)
├── dto/           # Request/response records
└── exception/     # Custom exceptions + global handler
src/main/resources
├── application.yml # Config (H2 file mode, JPA, JWT, security, springdoc)
├── data.sql        # Test-profile seed data
└── static/         # Web UI: login, index, users, profile (HTML/CSS/JS)
```

## License

[MIT](LICENSE) © 2026 Basit Siddiqui. Release notes in [CHANGELOG.md](CHANGELOG.md).
