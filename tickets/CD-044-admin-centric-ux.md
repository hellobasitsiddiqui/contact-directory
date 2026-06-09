# CD-044: Admin-centric UX — dashboard landing, de-couple admin from contacts, seed sample users

- **Type:** feature / frontend / backend
- **Status:** In review
- **Branch:** `CD-044-admin-centric-ux`

## Summary
Feedback: "admin is about managing the users of the app, not about owning contacts." The admin landed
on the contacts page and user administration was buried behind a nav link. This reframes the admin
experience around user management.

## Changes (all three)
1. **Admin dashboard + landing** — new `dashboard.html` + `dashboard.js`: user stats
   (total / admins / enabled / disabled from `GET /api/v1/users`), recent activity (top 10 from
   `GET /api/v1/audit`), and quick-action links to Users / Activity / Contacts. `login.js` now routes
   **ADMIN → `dashboard.html`**, **USER → `index.html`**. Auth-gated client-side (admin-only) like the
   other pages, and added to `SecurityConfig.PUBLIC_GET` so the browser can serve it.
2. **De-couple admin from contacts** — `DataInitializer` no longer seeds contacts under the admin; the
   contacts page shows an **"Admin view — showing all users’ contacts"** banner for admins (the brand
   title stays "Contact Directory"). The admin remains a super-user who can see/moderate all contacts.
3. **Seed sample users** — `DataInitializer` seeds two `USER` accounts (`alice`/`alice123`,
   `bob`/`bob123`) on a fresh DB and gives them the sample contacts (alice: Jane + John; bob: Maria),
   so the Users page and the directory both have demo data. Idempotent (skips if any contact or any
   non-admin user already exists — e.g. the test profile's `data.sql`).

## Acceptance criteria
- [x] Admin lands on a user-administration dashboard; regular users still land on contacts.
- [x] Admin owns no seeded contacts; sample users (alice/bob) own them; admin still sees all.
- [x] Sample `USER` accounts seeded so the Users page isn't empty out of the box.
- [x] `dashboard.html`/`dashboard.js` whitelisted in `SecurityConfig.PUBLIC_GET`.
- [x] Playwright e2e (H2 walkthrough + Postgres) updated for the new landing — both green.
- [x] H2 gate **219** green; coverage met.

## Verification
- `mvn verify` (H2 gate): 219 tests, BUILD SUCCESS.
- Both Playwright e2e green: H2 walkthrough now login → **dashboard** → contacts → users → activity →
  profile; Postgres test login → dashboard → contacts → photo round-trip. Screenshot of the dashboard
  shows 2 users / 1 admin / recent activity / quick actions.
- A wiring bug the e2e caught: `dashboard.html`/`.js` had to be added to `PUBLIC_GET` (static page GETs
  carry no bearer token, so they 401'd) — fixed.

## Follow-up
- `docs/WALKTHROUGH.md` screenshots could be refreshed to show the dashboard (the e2e already captures
  one). Tracked as a docs-refresh follow-up if desired.
