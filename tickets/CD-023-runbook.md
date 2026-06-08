# CD-023: Operations runbook

- **Type:** docs
- **Status:** Open
- **Branch:** `CD-023-runbook`

## Summary
Write `docs/RUNBOOK.md` so a release can be deployed, smoke-tested and rolled back without tribal
knowledge. See [`../docs/RELEASE-AND-DEPLOYMENT.md`](../docs/RELEASE-AND-DEPLOYMENT.md) Part 2.

## Acceptance criteria
- [ ] Deploy steps (pull the versioned GHCR image, set env, start).
- [ ] Required env/secrets table: `APP_JWT_SECRET`, `APP_DEFAULT_ADMIN_PASSWORD`, DB creds.
- [ ] Health check: `GET /actuator/health` (expected `200 {"status":"UP"}`).
- [ ] Smoke test: login as admin, list/create a contact.
- [ ] Rollback: redeploy previous version tag / previous image.

## Notes
Depends conceptually on the deploy target (CD-025) but can be drafted generically first and tightened
once a host is chosen.
