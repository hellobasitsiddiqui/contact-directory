# CD-046: Release prep — 1.0.0-beta.2

- **Type:** ci / release
- **Status:** In review
- **Branch:** `CD-046-release-beta2`

## Summary
Prepare the second beta. Everything since `1.0.0-beta.1` (Postgres+Flyway, the Postgres-only bug
fixes, the photo-display fix, the admin-centric UX, the doc sweep) ships as **`1.0.0-beta.2`**.

## Acceptance criteria
- [x] `pom.xml` `1.0.0-beta.1` → `1.0.0-beta.2`.
- [x] `CHANGELOG.md`: roll `[Unreleased]` into a dated `## [1.0.0-beta.2] - 2026-06-09` section.
- [ ] Maintainer merges the `develop` → `master` release PR (merge commit) and tags `v1.0.0-beta.2`.

## How 1.0.0-beta.2 gets cut (maintainer)
1. Merge this into `develop`.
2. Merge the `develop` → `master` release PR with **"Create a merge commit"**.
3. Tag `v1.0.0-beta.2` (CLI or GitHub UI "Draft a new release" + tick **pre-release**) → the
   pre-release-aware `release.yml` publishes a pre-release + `:1.0.0-beta.2` image (does NOT move
   `:latest`).

## Notes
Still a beta (hyphenated tag → pre-release). For GA later, bump to `1.0.0` (no suffix), which also
moves `:latest`. Automation never merges/tags `master`.
