# CD-047: Refresh screenshots for the admin-centric UI

- **Type:** docs
- **Status:** In review
- **Branch:** `CD-047-refresh-screenshots`

## Summary
The committed screenshots predated the admin-centric UX (CD-044): no dashboard, and the admin still
appeared to own contacts. Recapture the whole set from the running app and repoint the docs at it.

## What changed
- New folder **`docs/screenshots-v1.0.0-beta.2/`** with 7 full-page screenshots captured headless via
  Playwright against the documented seed (admin + alice + bob): `01-login`, **`02-dashboard`** (new),
  `03-contacts` (admin "Admin view"), `04-users`, `05-profile`, `06-activity`, `07-dark-mode`.
- Repointed every image link in `README.md`, `FEATURES.md` and `docs/WALKTHROUGH.md` to the new
  folder. The numbering shifted (a `02-dashboard` shot was inserted), so links were remapped
  per-file, not just path-swapped.
- Added the dashboard screenshot to the README grid, the FEATURES "Roles & access control" section
  and WALKTHROUGH §1.
- **Retired** the old `docs/screenshots/` folder (its 6 images are superseded and no longer
  referenced; recoverable from git history).

## Notes
- Capture method: a throwaway `@Tag("e2e")` Playwright test with
  `spring.sql.init.mode=never` (so `DataInitializer` seeds the real alice/bob runtime data rather than
  the test-only `data.sql` single user). The test was deleted after the run — not committed.
- The folder is version-named per the maintainer's preference for a fresh folder per refresh; at GA
  the set can be consolidated under a single canonical `docs/screenshots/` if desired.
