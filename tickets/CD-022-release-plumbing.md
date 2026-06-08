# CD-022: Release plumbing (version, tag, Release, release.yml)

- **Type:** ci / release
- **Status:** In review
- **Branch:** `CD-022-release-plumbing`

## Summary
Turn a `develop` → `master` merge into a real, reproducible release. Pure release hygiene — no infra
required. See [`../docs/RELEASE-AND-DEPLOYMENT.md`](../docs/RELEASE-AND-DEPLOYMENT.md) Part 1 for the
full rationale. Prepares the **1.0.0** release.

## Acceptance criteria
- [x] Bump `pom.xml` version `0.0.1-SNAPSHOT` → `1.0.0`, on `develop`.
- [x] Add `.github/workflows/release.yml` triggered on tag push `v*` that:
  - builds the JAR (`./mvnw -B clean verify`),
  - creates a GitHub Release from the tag + the matching `CHANGELOG.md` section,
  - uploads the JAR as a release asset,
  - builds & pushes a **versioned** GHCR image (`:<version>` and `:latest`).
- [x] Document the release in `CHANGELOG.md` (`[Unreleased]` → dated `## [1.0.0] - 2026-06-08`).
- [x] CONTRIBUTING notes the tag-driven release flow.

## Notes
Tagging the `master` merge commit and pushing the tag is a **maintainer action** (same authority that
merges the release PR). Automation never merges to `master`.

**To cut 1.0.0 once this is on `develop`:** the maintainer merges the `develop` → `master` release PR
(#27), then `git tag -a v1.0.0 -m "v1.0.0" <merge-commit> && git push origin v1.0.0`. The pushed tag
triggers `release.yml` → GitHub Release + JAR + `ghcr.io/.../contact-directory:1.0.0`.
