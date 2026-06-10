# CD-051: Public status / uptime page

- **Type:** feature / observability
- **Status:** Open
- **Branch:** `CD-051-status-page`

## Summary
Add a public **status page** (in the spirit of [status.claude.com](https://status.claude.com/)) — a
no-auth page that tells anyone at a glance whether the service is **operational, degraded, or down**,
per component (API, database, auth). It's the user-facing presentation of the data the app already
produces via Spring Boot Actuator `/actuator/health`.

## Why
`/actuator/health` exists for orchestration probes but isn't human-friendly. A status page gives
users/operators a single URL to check service health (and, later, incident history) without logging in.

## Scope (MVP)
- A static page (e.g. `static/status.html` served at `/status.html`) that polls a public health
  source and renders a coloured **Operational / Degraded / Down** badge overall and per component.
- **Data-source decision:** anonymous `/actuator/health` only returns the top-level `UP`/`DOWN`
  (component detail is `show-details: when-authorized`). To show per-component status publicly without
  exposing internals, add a small curated **public `GET /api/v1/status`** endpoint that returns a
  whitelisted summary (e.g. `{status, components:{api, db, auth}, checkedAt}`) — do NOT just open
  full Actuator detail to anonymous callers.
- Auto-refresh (poll every ~15–30s); accessible, mobile-friendly; works in dark/light theme.

## Stretch (later, separate ticket)
- **Uptime %** over a window (needs periodic health sampling + storage).
- **Incident history** + scheduled-maintenance notices (an incidents table + admin UI to post them).
- Email/webhook subscribe-to-updates (this is what hosted Statuspage/Atlassian products do — likely
  out of scope for a self-hosted app).

## Acceptance criteria
- [ ] Public `GET /api/v1/status` returning a curated component summary (no auth, no internal leakage).
- [ ] `status.html` rendering overall + per-component state with auto-refresh.
- [ ] Linked from the app footer / README; covered by a test (status endpoint shape + a browser e2e).

## Notes
Builds on CD-002 (Actuator health/metrics). Keep the MVP simple — a curated public status endpoint +
a polling page; the incident/uptime-history machinery is a deliberate follow-up.
