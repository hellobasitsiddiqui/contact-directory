# CD-022: Release plumbing (version, tag, Release, release.yml)

- **Type:** ci / release
- **Status:** Open
- **Branch:** `CD-022-release-plumbing`

## Summary
Turn a `develop` → `master` merge into a real, reproducible release. Pure release hygiene — no infra
required. See [`../docs/RELEASE-AND-DEPLOYMENT.md`](../docs/RELEASE-AND-DEPLOYMENT.md) Part 1 for the
full rationale.

## Acceptance criteria
- [ ] Bump `pom.xml` version `0.0.1-SNAPSHOT` → real SemVer (e.g. `1.0.0`), on `develop`.
- [ ] Add `.github/workflows/release.yml` triggered on tag push `v*` that:
  - builds the JAR (`./mvnw -B clean verify`),
  - creates a GitHub Release from the tag + the matching `CHANGELOG.md` section,
  - uploads the JAR as a release asset,
  - builds & pushes a **versioned** GHCR image (`:<version>` and `:latest`).
- [ ] Document the release step in `CHANGELOG.md` (move `[Unreleased]` → dated `## [x.y.z]`).
- [ ] CONTRIBUTING/README note the tag-driven release flow.

## Notes
Tagging the `master` merge commit and pushing the tag is a **maintainer action** (same authority that
merges the release PR). Automation never merges to `master`.
