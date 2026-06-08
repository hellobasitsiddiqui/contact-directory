# Tickets

Plain-text issue tracker (no external system). One markdown file per ticket, created from
[`TEMPLATE.md`](TEMPLATE.md). See [../CONTRIBUTING.md](../CONTRIBUTING.md) for the full workflow.

- **ID:** `CD-NNN` (zero-padded, increment from the highest existing).
- **Branch:** `CD-NNN-short-slug`, off `develop` · **Commit/PR:** `CD-NNN: summary` · PR base: `develop`.
- **Status:** `Open` → `In progress` → `In review` → `Done` (set `Done` when the PR merges).

## Index

| Ticket | Title | Status |
|--------|-------|--------|
| [CD-001](CD-001-contribution-workflow.md) | Establish Git Flow (feature → develop → master) + branch protection | Done (#12) |
| [CD-002](CD-002-actuator-health-metrics.md) | Add Actuator health checks & metrics | Done (#29) |
| [CD-003](CD-003-license-and-changelog.md) | Add LICENSE and CHANGELOG | Done (#30) |
| [CD-004](CD-004-gitflow-develop-wiring.md) | Wire develop into Git Flow (CI triggers + docs) | Done (#13) |
| [CD-005](CD-005-tame-dependabot.md) | Tame Dependabot (group updates, ignore majors) | Done (#14) |
| [CD-006](CD-006-users-table-search.md) | Users table — search + role/status filter | Done (#15) |
| [CD-007](CD-007-users-table-sort.md) | Users table — sortable columns | Done (#18) |
| [CD-008](CD-008-users-stats-bar.md) | Users page — summary stats bar | Done (#19) |
| [CD-009](CD-009-relative-timestamps.md) | Relative timestamps (absolute on hover) | Done (#20) |
| [CD-010](CD-010-copy-to-clipboard.md) | Copy-to-clipboard buttons (emails / usernames) | Done (#21) |
| [CD-011](CD-011-confirm-dialog.md) | Styled confirmation dialog (replace native confirm) | Done (#22) |
| [CD-012](CD-012-users-bulk-actions.md) | Users table — bulk select + bulk actions | Done (#23) |
| [CD-013](CD-013-user-detail-modal.md) | User detail modal (details + recent activity) | Done (#24) |
| [CD-014](CD-014-activity-filters.md) | Activity log — multi-select + date-range filter | Done (#25) |
| [CD-015](CD-015-users-pagination.md) | Users table — client-side pagination | Done (#28) |
| [CD-016](CD-016-docs-sweep.md) | Docs sweep for the UI-features batch | Done (#26) |
| [CD-017](CD-017-http-e2e.md) | HTTP-level e2e on a running app (RANDOM_PORT) | Done (#31) |
| [CD-018](CD-018-playwright-e2e.md) | Playwright browser e2e (screenshots + videos, develop/master) | Done (#32) |
| [CD-019](CD-019-refresh-screenshots.md) | Refresh docs screenshots for the new UI | Done (#33) |
| [CD-020](CD-020-docs-consistency.md) | Docs consistency sweep (counts, statuses, next-steps) | In review |
| [CD-021](CD-021-release-deploy-plan.md) | Save release & deployment plan (docs/RELEASE-AND-DEPLOYMENT.md) | In review |
| [CD-022](CD-022-release-plumbing.md) | Release plumbing (version, tag, Release, release.yml) | In review |
| [CD-023](CD-023-runbook.md) | Operations runbook | Open |
| [CD-024](CD-024-postgres-flyway.md) | Durable persistence (Postgres + Flyway) | Open |
| [CD-025](CD-025-deploy-target.md) | Deploy target + optional deploy.yml | Open |
| [CD-026](CD-026-mobile-api-findings.md) | Save mobile API readiness findings (docs/MOBILE-API-READINESS.md) | In review |
| [CD-027](CD-027-tls-hsts.md) | HTTPS/TLS + HSTS (mobile blocker) | Open |
| [CD-028](CD-028-refresh-tokens.md) | Refresh tokens + server-side revocation + logout | Open |
| [CD-029](CD-029-users-pagination-api.md) | Server-side pagination/filter/sort for /api/v1/users | Open |
| [CD-030](CD-030-upload-limits-photo-url.md) | Larger photo upload limit + absolute photoUrl | Open |
| [CD-031](CD-031-rate-limiting.md) | Lockout/rate-limit feedback + API rate limiting | Open |
| [CD-032](CD-032-offline-sync-etag.md) | Offline sync — delta endpoint + ETag | Open |
| [CD-033](CD-033-push-notifications.md) | Push notifications (FCM/APNs) | Open |
| [CD-034](CD-034-version-gating-info.md) | App-version gating + /info bootstrap endpoint | Open |
| [CD-035](CD-035-cors-hybrid.md) | CORS config (for hybrid/WebView clients) | Open |
