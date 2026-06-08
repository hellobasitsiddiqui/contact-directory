# CD-013: User detail modal (details + recent activity)

- **Type:** feature (UI)
- **Status:** In review
- **Branch:** `CD-013-user-detail-modal`

## Summary
Click a user row to open a modal with that user's details and their recent activity.

## Acceptance criteria
- [ ] Row click opens a modal: username, role, status, created (relative + absolute)
- [ ] Recent activity from GET /api/v1/audit?actor=<username> (admin endpoint), with empty state
- [ ] Reuses modal styling; Esc/backdrop close; doesn't fire on action controls; tests green
