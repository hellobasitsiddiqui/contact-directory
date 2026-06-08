# CD-018: Playwright browser e2e (screenshots + videos)

- **Type:** test
- **Status:** In review
- **Branch:** `CD-018-playwright-e2e`

## Summary
Java Playwright browser e2e that drives the real frontend and saves screenshots + videos as
human-reviewable evidence. Runs ONLY on develop/master (a dedicated CI workflow), never on feature
PRs, and is excluded from the default build/gate.

## Acceptance criteria
- [x] Playwright (Java) test tagged `e2e`; @SpringBootTest(RANDOM_PORT); drives login/contacts/users/activity/profile
- [x] Captures numbered screenshots + video to target/playwright/**; assertions per page
- [x] Default `mvn verify` still green and does NOT run the browser tests (tag-excluded)
- [x] .github/workflows/e2e.yml: push to master/develop + manual only; installs browser; uploads evidence artifact
- [x] COMMON-FEATURES documents both e2e styles; CONTRIBUTING notes the trigger policy
