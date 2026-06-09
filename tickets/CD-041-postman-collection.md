# CD-041: Postman collection (generated from OpenAPI) — optional

- **Type:** docs / tooling
- **Status:** Open
- **Branch:** `CD-041-postman-collection`

## Summary
Optionally provide a **Postman collection** for manual / exploratory API testing and sharing.

## Do we actually need it?
**Probably not.** The contract and testing are already covered:
- **Swagger UI** (from `openapi.json`) gives an interactive "try it" client.
- **HTTP e2e** (`HttpEndToEndTest`) + **Playwright** e2e exercise real flows.
- The README has `curl` examples.

Postman's distinct value is *shareable saved requests* for non-dev / QA / exploratory use — which
overlaps Swagger UI heavily. So this is **low priority**; only pursue it if a QA or external-consumer
workflow actually wants it.

## Acceptance criteria (only if pursued)
- [ ] **Generate** the collection from `openapi.json` (e.g. openapi-to-postman) — do **not** hand-maintain.
- [ ] Commit it under `docs/` (or `postman/`) and link it from the README.
- [ ] (Optional) Run it in CI via Newman as an extra smoke check.

## Notes
Revisit if/when there's a real consumer who prefers Postman over Swagger UI. Until then, Swagger UI is
the recommended way to explore the API.
