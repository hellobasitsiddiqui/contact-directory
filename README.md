# Contact Directory API

A small REST API for managing contacts, built with **Spring Boot 3.3.5**, **Java 21**, and **Maven**.
It stores data in an in-memory **H2** database (seeded with sample contacts on startup), ships with a
lightweight browser **Web UI**, and exposes interactive API documentation via **Swagger UI**.

## Features

- Full CRUD for contacts (create, read, update, partial update, delete)
- Paginated, sortable listing with free-text search across name, email, and company
- One optional photo per contact, uploaded/served/deleted via dedicated sub-resource endpoints
- Bean Validation on request bodies (e.g. required fields, email format, phone pattern)
- Unique-email enforcement with a clear `409 Conflict` response
- Consistent JSON error responses via a global exception handler
- A browser Web UI served from the app for managing contacts and photos

## Tech stack

| Concern        | Choice                                   |
|----------------|------------------------------------------|
| Language       | Java 21                                  |
| Framework      | Spring Boot 3.3.5 (Web, Data JPA)        |
| Persistence    | Spring Data JPA + Hibernate              |
| Database       | H2 (in-memory)                           |
| Validation     | Jakarta Bean Validation                  |
| API docs       | springdoc-openapi (Swagger UI)           |
| Build          | Maven (with wrapper)                      |

## Running the application

From the project root:

```bash
./mvnw spring-boot:run
```

The application starts on **http://localhost:8080**.

On startup the schema is created automatically (`ddl-auto=create-drop`) and three sample
contacts are inserted from `src/main/resources/data.sql`. Because the database is in-memory,
all data is reset every time the application restarts.

### Build a runnable JAR

```bash
./mvnw clean package
java -jar target/contact-directory-0.0.1-SNAPSHOT.jar
```

## Web UI

A lightweight browser UI is served by the app at **http://localhost:8080/** (static assets live in
`src/main/resources/static`). From there you can:

- List, search, sort, and paginate contacts
- Create, edit, and delete contacts
- Upload a photo (with a live preview) or remove an existing one, and see contact avatars in the
  list (initials are shown as a placeholder when a contact has no photo)

The UI talks to the same versioned API described below.

## API endpoints

Base path: `/api/v1/contacts`

| Method   | Path                    | Description                                          | Success status |
|----------|-------------------------|------------------------------------------------------|----------------|
| `POST`   | `/api/v1/contacts`      | Create a new contact                                 | `201 Created`  |
| `GET`    | `/api/v1/contacts`      | List contacts (paginated, sortable, `?search=` text) | `200 OK`       |
| `GET`    | `/api/v1/contacts/{id}` | Get a single contact by id                           | `200 OK`       |
| `PUT`    | `/api/v1/contacts/{id}` | Replace an existing contact                          | `200 OK`       |
| `PATCH`  | `/api/v1/contacts/{id}` | Partially update an existing contact                 | `200 OK`       |
| `DELETE` | `/api/v1/contacts/{id}` | Delete a contact                                     | `204 No Content` |

### Photo endpoints

Each contact can have **one** optional image. The photo bytes are never embedded in the contact
JSON — instead, a contact's response includes a `photoUrl` field (e.g. `/api/v1/contacts/{id}/photo`)
when a photo exists, or `null` when it does not.

| Method   | Path                          | Description                              | Success status   |
|----------|-------------------------------|------------------------------------------|------------------|
| `GET`    | `/api/v1/contacts/{id}/photo` | Download the contact's photo bytes       | `200 OK`         |
| `POST`   | `/api/v1/contacts/{id}/photo` | Upload/replace the contact's photo       | `200 OK`         |
| `DELETE` | `/api/v1/contacts/{id}/photo` | Remove the contact's photo               | `204 No Content` |

Upload details:

- Sent as `multipart/form-data` with the file under the form field name **`file`**.
- Allowed image types: **PNG, JPEG, GIF, WEBP** (`image/png`, `image/jpeg`, `image/jpg`,
  `image/gif`, `image/webp`).
- Maximum file size: **2 MB**.
- `POST` returns the updated contact (so the client receives the new `photoUrl`).
- An unsupported type or empty file returns `400 Bad Request`; a file over the limit returns
  `413 Payload Too Large`.
- `GET` on a contact with no photo (or an unknown id) returns `404 Not Found`.

### Listing query parameters

| Parameter | Default      | Description                                                        |
|-----------|--------------|-------------------------------------------------------------------|
| `search`  | _(none)_     | Free-text match against first name, last name, email, and company |
| `page`    | `0`          | Zero-based page index                                              |
| `size`    | `20`         | Page size                                                         |
| `sort`    | `lastName`   | Sort property and direction, e.g. `sort=lastName,desc`            |

### Example requests

Create a contact:

```bash
curl -X POST http://localhost:8080/api/v1/contacts \
  -H "Content-Type: application/json" \
  -d '{
        "firstName": "Ada",
        "lastName": "Lovelace",
        "email": "ada@example.com",
        "phone": "+44 20 1234 5678",
        "company": "Analytical Engines"
      }'
```

Search and page through results:

```bash
curl "http://localhost:8080/api/v1/contacts?search=acme&page=0&size=10&sort=lastName,asc"
```

Upload a photo for a contact (multipart, field name `file`):

```bash
curl -X POST http://localhost:8080/api/v1/contacts/1/photo \
  -F "file=@./avatar.png"
```

Download a contact's photo:

```bash
curl http://localhost:8080/api/v1/contacts/1/photo --output avatar.png
```

Delete a contact's photo:

```bash
curl -X DELETE http://localhost:8080/api/v1/contacts/1/photo
```

## Interactive docs & tooling

| Tool        | URL                                              |
|-------------|--------------------------------------------------|
| Web UI      | http://localhost:8080/                            |
| Swagger UI  | http://localhost:8080/swagger-ui.html            |
| OpenAPI doc | http://localhost:8080/v3/api-docs                |
| H2 console  | http://localhost:8080/h2-console                 |

A snapshot of the API spec is also checked in at the project root as **`openapi.yaml`** and
**`openapi.json`** for offline reference and tooling (client generation, linting, etc.).

### H2 console connection settings

When the H2 console login page loads, use:

| Field        | Value                              |
|--------------|------------------------------------|
| JDBC URL     | `jdbc:h2:mem:contacts;DB_CLOSE_DELAY=-1` |
| User Name    | `sa`                               |
| Password     | _(leave blank)_                    |

## Project structure

```
src/main/java/com/example/contacts
├── ContactDirectoryApplication.java   # Spring Boot entry point
├── config/        # OpenAPI configuration
├── controller/    # REST controllers
├── service/       # Business logic
├── repository/    # Spring Data JPA repositories
├── model/         # JPA entities
├── dto/           # Request/response records
└── exception/     # Custom exceptions + global handler
src/main/resources
├── application.yml # Configuration (H2, JPA, springdoc)
└── data.sql        # Seed data
```
