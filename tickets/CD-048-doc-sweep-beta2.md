# CD-048: Docs consistency sweep (post-beta.2)

- **Type:** docs
- **Status:** In review
- **Branch:** `CD-048-doc-sweep-beta2`

## Summary
A parallel audit of every doc against ground truth after the v1.0.0-beta.2 release + CD-047 screenshot
refresh. Five docs (README, FEATURES, COMMON-FEATURES, CONTRIBUTING, WALKTHROUGH) were already correct;
fixes landed in four.

## Changes
- **`tickets/README.md`** — CD-045/046/047 were merged but still showed "In review" → `Done (#48/#49/#51)`;
  added the CD-048 row.
- **`docs/RELEASE-AND-DEPLOYMENT.md`** — heavily stale; rewrote to reflect reality: status is no longer
  "planning only", release plumbing (CD-022/038/039) and Postgres+Flyway (CD-024) are **done**, every
  `1.0.0` corrected to `1.0.0-beta.2`, the `:latest`/pre-release behaviour clarified (hyphenated tag →
  pre-release + `:version` only, `:latest` reserved for GA), and the `prod` profile name corrected to
  `postgres`. Remaining work is CD-023 (runbook) and CD-025 (deploy target).
- **`CHANGELOG.md`** — beta.1 Notes said `v1.0.0` is the cut release; corrected to note the cut tags are
  pre-releases (`v1.0.0-beta.N`) with GA still to come.
- **`docs/MOBILE-API-READINESS.md`** — "grounded as of 1.0.0" → "1.0.0-beta.2".

## Verification
- Confirmed the "219 tests / 18 classes" count is **correct** (gate suite, e2e excluded) — not changed.
- No remaining `docs/screenshots/` (old folder) references outside historical ticket records.
- Audit run as a 9-way parallel doc sweep (one auditor per file) against a ground-truth fact sheet.

## Notes
Docs-only; no behaviour change.
