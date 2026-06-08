# Contributing & workflow

This project follows a **Git Flow** style, ticket-driven, PR-based workflow:

- **`master`** — release branch (protected). Updated only by **release PRs from `develop`**, which the **maintainer merges** (and tags).
- **`develop`** — integration branch and the **default branch** (protected). Feature PRs land here; **automation merges** them once CI is green and the review is posted.
- **`CD-NNN-short-slug`** — one feature branch per ticket, branched off `develop`.

## The flow

1. **Ticket** — every piece of work has a plain-text ticket in [`tickets/`](tickets/) (no external
   tracker). IDs are `CD-NNN`. Create one from [`tickets/TEMPLATE.md`](tickets/TEMPLATE.md).
2. **Branch** — one branch per ticket, `CD-NNN-short-slug`, branched off the latest **`develop`**.
3. **Commit** — reference the ticket: `CD-NNN: imperative summary`.
4. **Pull request** — open a PR into **`develop`**. CI runs automatically (tests + coverage gate +
   OpenAPI drift + build/package; CodeQL + dependency review on top).
5. **Review** — an automated review agent reviews the PR and posts findings as a comment (advisory).
6. **Merge to `develop`** — once CI is green and the review is posted, **automation merges** the
   feature PR into `develop`.
7. **Release** — periodically a **`develop` → `master`** release PR is opened; the **maintainer**
   merges it, then **tags** the merge commit (`git tag -a vX.Y.Z -m "vX.Y.Z" && git push origin vX.Y.Z`).
   The pushed `v*` tag triggers [`.github/workflows/release.yml`](.github/workflows/release.yml), which
   builds the JAR, publishes a **GitHub Release** (notes from the matching `CHANGELOG.md` section) with
   the JAR attached, and pushes a **versioned** GHCR image (`:X.Y.Z` and `:latest`). Bump `pom.xml`
   and move `CHANGELOG.md`'s `[Unreleased]` items into a dated `## [X.Y.Z]` section **on `develop`**
   *before* the release PR, so they ride into `master` with the merge. Automation never merges or tags
   `master`. See [`docs/RELEASE-AND-DEPLOYMENT.md`](docs/RELEASE-AND-DEPLOYMENT.md) for the full
   release-hygiene and durable-deploy roadmap.

## Branch protection

Both `master` and `develop` are protected: a pull request is required (no direct pushes), the
required status check **Build, test & coverage gate** must pass, and force-pushes / branch deletion
are disabled. The OpenAPI drift check, CodeQL, packaging and dependency review also run on every PR
but are **advisory** (not merge-blocking), so a transient infra flake can't lock merges. `strict` is
off (PRs aren't forced up-to-date) to avoid re-run churn.

- **`master`** also enforces protection for admins (release gate) — only the maintainer merges release PRs.
- **`develop`** lets automation merge feature PRs once the required check is green.

## Conventions

| Thing | Convention | Example |
|---|---|---|
| Ticket ID | `CD-NNN` (zero-padded) | `CD-002` |
| Branch | `CD-NNN-short-slug` | `CD-002-actuator-health-metrics` |
| Commit | `CD-NNN: summary` | `CD-002: add Actuator health and metrics` |
| PR title | `CD-NNN: summary` | `CD-002: add Actuator health and metrics` |

## Local checks before pushing

```bash
./mvnw -B clean verify   # tests + coverage gate (the required CI gate)

# If you changed the REST API, regenerate the spec and confirm it's current:
./mvnw -q -DskipTests package && java -jar target/*.jar >/tmp/app.log 2>&1 &
sleep 20
curl -s localhost:8080/v3/api-docs | python3 -m json.tool > openapi.json
python3 .github/scripts/check_openapi_drift.py openapi.json openapi.json   # sanity; CI re-checks vs live
```

If a change alters the REST API, regenerate the OpenAPI spec so the drift check passes, and update
the docs ([README.md](README.md), [docs/WALKTHROUGH.md](docs/WALKTHROUGH.md),
[FEATURES.md](FEATURES.md)) and [COMMON-FEATURES.md](COMMON-FEATURES.md) when a baseline capability
changes.

## Browser end-to-end tests (Playwright)

There are two e2e layers. The **HTTP e2e** (`HttpEndToEndTest`) runs as part of the default
`mvn verify` gate above. The **browser e2e** (`PlaywrightE2eTest`) drives the real UI in headless
Chromium and saves screenshots + a video to `target/playwright/`. It is tagged `e2e` and
**deliberately excluded from `mvn verify`** (it needs a downloaded browser and is slow), so it does
**not** run on feature PRs.

- **Trigger policy:** the browser e2e runs **only** on pushes to `master`/`develop` (post-merge) and
  on manual `workflow_dispatch`, via [`.github/workflows/e2e.yml`](.github/workflows/e2e.yml). That
  workflow installs the browser and uploads the screenshots/video as a `playwright-evidence` artifact.
- **Run it locally** (one-off browser install, then the tagged suite):

  ```bash
  ./mvnw exec:java -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.classpathScope=test -Dexec.args="install chromium"
  ./mvnw test -Dtest.excludedGroups= -Dgroups=e2e   # clears the default tag exclusion
  ```
