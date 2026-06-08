# CD-019: Refresh docs screenshots for the new UI

- **Type:** docs
- **Status:** In review
- **Branch:** `CD-019-refresh-screenshots`

## Summary
The committed screenshots predated the admin-UI batch (CD-006..CD-015). Refresh them from the
current app (reusing the Playwright/CI evidence captures) so README/WALKTHROUGH/FEATURES show the
real, current UI (search/filters/stats/bulk/pagination on Users; filters on Activity).

## Acceptance criteria
- [x] docs/screenshots refreshed from current UI (login, contacts, users, profile, activity)
- [x] README/WALKTHROUGH captions reflect the richer admin UI
