# Contributing & workflow

This project uses a lightweight, **ticket-driven, PR-based** workflow. `master` is **protected** —
no direct pushes; every change lands through a pull request with green CI.

## The flow

1. **Ticket** — every piece of work has a plain-text ticket in [`tickets/`](tickets/) (no external
   tracker). IDs are `CD-NNN`. Create one from [`tickets/TEMPLATE.md`](tickets/TEMPLATE.md).
2. **Branch** — one branch per ticket, named `CD-NNN-short-slug`
   (e.g. `CD-002-actuator-health-metrics`). Branch off the latest `master`.
3. **Commit** — reference the ticket: `CD-NNN: imperative summary`.
4. **Pull request** — open a PR into `master`. CI runs automatically (tests + coverage gate +
   OpenAPI drift + build/package; CodeQL + dependency review on top).
5. **Review** — an automated review agent reviews the PR and posts findings as a comment. The review
   is **advisory** (a comment, not an enforced approval gate).
6. **Merge** — the **maintainer merges** once CI is green, having considered the review.
   (Automation never merges.)

## Branch protection on `master`

- Pull request required before merging (no direct pushes, admins included).
- Required status check: **Build, test & coverage gate** must pass. The OpenAPI drift check, CodeQL,
  packaging and dependency review also run on every PR but are **advisory** (not merge-blocking) —
  so a transient infra flake can't lock merges while admin enforcement is on.
- `strict` is off (PRs aren't forced up-to-date with `master`) to avoid re-run churn on a low-traffic repo.
- Force-pushes and branch deletion are disabled.

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
