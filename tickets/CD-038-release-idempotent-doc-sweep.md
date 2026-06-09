# CD-038: Make release.yml idempotent + doc sweep

- **Type:** ci / docs
- **Status:** In review
- **Branch:** `CD-038-release-idempotent-doc-sweep`

## Summary
Make `release.yml` work whether the tag is pushed from the CLI **or** created via the GitHub UI, and
sweep the docs for facts that went stale after 1.0.0 / the Dependabot bumps.

## Acceptance criteria
- [x] `release.yml`: idempotent Release step — create if absent, else attach the JAR (`gh release
      upload --clobber`).
- [x] Fix stale stack version in docs: Spring Boot `3.3.5` → `3.5.14` (README ×2, FEATURES).
- [x] README run command uses `target/*.jar` (was the `0.0.1-SNAPSHOT` jar name).
- [x] `COMMON-FEATURES.md`: Release automation ⬜ → ✅ (`release.yml`).
- [x] `docs/RELEASE-AND-DEPLOYMENT.md`: mark Part 1 (CD-022) implemented; note CLI-or-UI tagging.
- [x] `CONTRIBUTING.md`: document the GitHub-UI tagging option.
- [x] Finalize stale ticket-index statuses (CD-020/021/022/026/036/037 → Done) + their ticket files.

## Notes
Docs + CI only — no app behaviour change.
