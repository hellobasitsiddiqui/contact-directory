# CD-001: Establish Git Flow (ticket → feature → develop → master) + branch protection

- **Type:** chore
- **Status:** In review
- **Branch:** `CD-001-contribution-workflow`

## Summary
Move from direct commits on `master` to a Git Flow, ticket-driven, PR-based workflow: feature
branches → `develop` (automation merges after CI + review) → `master` via release PR (maintainer
merges). Both branches protected; an automated agent reviews every PR.

## Acceptance criteria
- [x] Plain-text ticket system under `tickets/` (README + template + this ticket)
- [x] `CONTRIBUTING.md` documenting the flow and conventions
- [x] `master` protected: PR required, required status checks, no force-push/deletion
- [x] `develop` integration branch created, protected, and set as the default branch
- [x] CI handles branches/PRs cleanly (PRs run CI; `master`/`develop` run on merge; no double-runs)
- [x] An agent reviews the PR and posts findings
- [x] Docs updated (README workflow section, COMMON-FEATURES statuses)
- [ ] Maintainer merges the PR

## Notes
Automation never merges — the maintainer merges once CI is green and review is addressed.
Required checks: "Build, test & coverage gate (Java 21)" and "OpenAPI spec drift check".
