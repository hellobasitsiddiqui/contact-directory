# CD-021: Save the release & deployment plan

- **Type:** docs
- **Status:** Done (#35)
- **Branch:** `CD-021-release-deploy-plan`

## Summary
Persist the plan for turning a `develop` → `master` merge into a real release, and for making a
deployment non-ephemeral, so it can be executed later. No release/deploy action is taken here — this
just records the roadmap.

## Acceptance criteria
- [x] Add `docs/RELEASE-AND-DEPLOYMENT.md` (release hygiene + durable-deploy options + sequence).
- [x] Open backlog tickets for the actionable chunks: CD-022 (release plumbing), CD-023 (runbook),
      CD-024 (Postgres + Flyway), CD-025 (deploy target).
- [x] Index all of the above in `tickets/README.md`.

## Notes
Captures the answer to "if we merge to master that's a release — what do we need (runbook/tag/release
doc), and can we deploy somewhere non-ephemeral?". The actual release/deploy is the maintainer's to do
later via CD-022…CD-025.
