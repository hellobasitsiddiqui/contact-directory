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
| [CD-002](CD-002-actuator-health-metrics.md) | Add Actuator health checks & metrics | Open |
| [CD-003](CD-003-license-and-changelog.md) | Add LICENSE and CHANGELOG | Open |
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
| [CD-015](CD-015-users-pagination.md) | Users table — client-side pagination | Open (rolled over) |
| [CD-016](CD-016-docs-sweep.md) | Docs sweep for the UI-features batch | In review |
