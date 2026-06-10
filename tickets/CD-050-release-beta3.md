# CD-050: Release prep — 1.0.0-beta.3

- **Type:** ci / release
- **Status:** In review
- **Branch:** `CD-050-release-beta3`

## Summary
Third beta. Bundles everything on `develop` since `1.0.0-beta.2`: TLS-readiness (CD-027), refresh
tokens + server-side revocation + real logout (CD-028), the admin-centric screenshot refresh (CD-047),
and two doc-consistency sweeps (CD-048, CD-049).

## Acceptance criteria
- [x] `pom.xml` `1.0.0-beta.2` → `1.0.0-beta.3`.
- [x] `CHANGELOG.md`: roll `[Unreleased]` into a dated `## [1.0.0-beta.3] - 2026-06-10`.
- [ ] Maintainer merges the `develop` → `master` release PR (merge commit) and tags `v1.0.0-beta.3`.

## How 1.0.0-beta.3 gets cut (maintainer)
1. Merge this into `develop`.
2. Merge the `develop` → `master` release PR with **"Create a merge commit"**.
3. Tag `v1.0.0-beta.3` (CLI or GitHub UI "Draft a new release" + tick **pre-release**) → the
   pre-release-aware `release.yml` publishes a pre-release + `:1.0.0-beta.3` image (does NOT move
   `:latest`).

## Notes
Still a beta (hyphenated tag → pre-release). A full-codebase code review (logged separately, kept
local for now) surfaced findings that are **not** blockers for a non-deployed pre-release; they are
queued as follow-up work for a later beta.
