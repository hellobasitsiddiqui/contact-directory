# CD-025: Deploy target + optional deploy.yml

- **Type:** infra / ci
- **Status:** Open
- **Branch:** `CD-025-deploy-target`

## Summary
Run the GHCR image on a real, long-lived host with durable Postgres and real secrets, and optionally
automate deployment. See [`../docs/RELEASE-AND-DEPLOYMENT.md`](../docs/RELEASE-AND-DEPLOYMENT.md)
§2.2–2.4.

## Acceptance criteria
- [ ] Choose a host (Fly.io / Render / Railway / Cloud Run / App Runner / Azure Container Apps / VPS).
- [ ] Provision managed Postgres (pairs with CD-024) and set secrets: `APP_JWT_SECRET`,
      `APP_DEFAULT_ADMIN_PASSWORD`, DB creds.
- [ ] Deploy the versioned GHCR image; wire `/actuator/health` as liveness/readiness probe.
- [ ] Confirm data survives a restart/redeploy (non-ephemeral).
- [ ] (Optional) `.github/workflows/deploy.yml` — `workflow_dispatch` first, then on release published.

## Notes
Depends on CD-024 (durable DB) for a real environment. The image and health endpoint already exist.
