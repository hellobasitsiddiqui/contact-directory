# CD-001: Establish ticket → branch → PR workflow + protect master

- **Type:** chore
- **Status:** In review
- **Branch:** `CD-001-contribution-workflow`

## Summary
Move from direct commits on `master` to a ticket-driven, PR-based workflow with a protected `master`
branch and an automated PR review step, so changes are reviewed and CI-gated before merge.

## Acceptance criteria
- [x] Plain-text ticket system under `tickets/` (README + template + this ticket)
- [x] `CONTRIBUTING.md` documenting the flow and conventions
- [x] `master` protected: PR required, required status checks, no force-push/deletion
- [x] CI handles branches/PRs cleanly (PRs run CI; `master` runs on merge; no double-runs)
- [x] An agent reviews the PR and posts findings
- [x] Docs updated (README workflow section, COMMON-FEATURES statuses)
- [ ] Maintainer merges the PR

## Notes
Automation never merges — the maintainer merges once CI is green and review is addressed.
Required checks: "Build, test & coverage gate (Java 21)" and "OpenAPI spec drift check".
