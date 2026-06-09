# CD-039: Pre-release support + cut 1.0.0-beta.1

- **Type:** ci / release
- **Status:** In review
- **Branch:** `CD-039-prerelease-beta`

## Summary
Ship the first release as a **pre-release (beta)** rather than GA. Set the version to `1.0.0-beta.1`
and make `release.yml` pre-release-aware.

## Acceptance criteria
- [x] `pom.xml` version `1.0.0` → `1.0.0-beta.1`.
- [x] `CHANGELOG.md`: heading `## [1.0.0]` → `## [1.0.0-beta.1] - 2026-06-09`.
- [x] `release.yml`: detect a hyphenated SemVer (pre-release) → pass `--prerelease` to the GitHub
      Release **and** push only the `:<version>` image (do NOT move `:latest`). Stable tags behave as
      before (`:<version>` + `:latest`).
- [x] `CONTRIBUTING.md`: document the pre-release tag convention.

## How to cut 1.0.0-beta.1 (maintainer)
1. Merge this into `develop`.
2. Merge the `develop` → `master` release PR (#27) with a **merge commit**.
3. Tag `v1.0.0-beta.1` (CLI `git tag` + push, or GitHub UI "Draft a new release" with the
   **pre-release** box) → `release.yml` publishes a pre-release + `:1.0.0-beta.1` image.

## Notes
Beta (not alpha) because the app is feature-complete. Next iterations: `-beta.2`, then drop the suffix
to `1.0.0` for GA (which will then also move `:latest`).
