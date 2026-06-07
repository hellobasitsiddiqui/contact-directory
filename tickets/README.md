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
| [CD-004](CD-004-gitflow-develop-wiring.md) | Wire develop into Git Flow (CI triggers + docs) | In review |
