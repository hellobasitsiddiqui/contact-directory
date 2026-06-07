# Contact Directory

[![CI](https://github.com/hellobasitsiddiqui/contact-directory/actions/workflows/ci.yml/badge.svg)](https://github.com/hellobasitsiddiqui/contact-directory/actions/workflows/ci.yml)

A full-stack **contact manager** built with **Spring Boot 3.3.5**, **Java 21**, and **Maven** — a
JSON REST API plus a framework-free browser UI. It features **JWT authentication**, **role-based
access** (USER / ADMIN), **per-user contact ownership**, and account **self-service**, all backed by
a persistent file-mode **H2** database and covered by **178 automated tests**.

## Screenshots

| Sign in | Contacts (admin view) |
|---|---|
| ![Login screen](docs/screenshots/01-login.png) | ![Contacts list](docs/screenshots/02-contacts.png) |

| Admin user management | My profile |
|---|---|
| ![User management](docs/screenshots/03-users.png) | ![Profile & change password](docs/screenshots/04-profile.png) |

**Admin activity log (audit trail)**

![Activity log](docs/screenshots/05-activity.png)

**Dark mode** (theme toggle, saved per browser)

![Contacts in dark mode](docs/screenshots/06-dark-mode.png)

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

## Tech stack

| Concern      | Choice                                          |
|--------------|-------------------------------------------------|
| Language     | Java 21                                         |
| Framework    | Spring Boot 3.3.5 (Web, Data JPA, Security)      |
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

On first run a default admin account is seeded and the directory is populated with a few sample
contacts. Data is stored in a **persistent** H2 file database (`./data/contacts.mv.db`), so it
**survives restarts** (the `./data` directory is git-ignored).

### Default login

| Username | Password   | Role  |
|----------|------------|-------|
| `admin`  | `admin123` | ADMIN |

> ⚠️ These are local/dev defaults. Override them (and the JWT secret) via environment variables
> before deploying — see [Configuration](#configuration). New users can self-register from the
> **Create account** tab and are created with the `USER` role.

### Build a runnable JAR

```bash
./mvnw clean package
java -jar target/contact-directory-0.0.1-SNAPSHOT.jar
```

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
./mvnw clean test
```

**178 tests** across 14 classes (unit + full-stack integration), including cross-user isolation,
role enforcement, optimistic concurrency, account self-service and lockout. A JaCoCo coverage report
is written to `target/site/jacoco/index.html`.

## Interactive docs & tooling

| Tool        | URL                                     |
|-------------|-----------------------------------------|
| Web UI      | http://localhost:8080/                   |
| Swagger UI  | http://localhost:8080/swagger-ui.html    |
| OpenAPI doc | http://localhost:8080/v3/api-docs        |
| H2 console  | http://localhost:8080/h2-console         |

H2 console connection: JDBC URL `jdbc:h2:file:./data/contacts`, user `sa`, blank password.

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
