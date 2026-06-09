# CD-004: Wire develop into Git Flow (CI triggers + 3-tier docs)

- **Type:** chore
- **Status:** In review
- **Branch:** `CD-004-gitflow-develop-wiring`

## Summary
CD-001 (#12) was merged to `master` before the Git Flow wiring landed, so `develop` only triggers CI
on `master` and the docs don't yet describe the three-tier flow. This lands that wiring on `develop`.

## Acceptance criteria
- [x] CI / CodeQL / dependency-review trigger on `master` **and** `develop` (push + PR)
- [x] CONTRIBUTING / README / COMMON-FEATURES describe feature → develop → master (Git Flow)
- [x] CD-001 marked Done; tickets index updated
- [ ] Merged into `develop` by automation (CI green + review posted)

## Notes
First change to actually exercise "automation merges to develop". A `develop → master` release PR
(maintainer-merged) will propagate this to `master`.
