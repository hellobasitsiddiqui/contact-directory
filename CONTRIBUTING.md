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
5. **Review** — an automated review agent reviews the PR and posts findings as a comment.
6. **Merge** — the **maintainer merges** once CI is green and the review is addressed.
   (Automation never merges.)

## Branch protection on `master`

- Pull request required before merging (no direct pushes, admins included).
- Required status checks must pass: **Build, test & coverage gate** and **OpenAPI spec drift check**.
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
./mvnw -B clean verify   # tests + coverage gate (matches CI)
```

If a change alters the REST API, regenerate the OpenAPI spec so the drift check passes, and update
the docs ([README.md](README.md), [docs/WALKTHROUGH.md](docs/WALKTHROUGH.md),
[FEATURES.md](FEATURES.md)) and [COMMON-FEATURES.md](COMMON-FEATURES.md) when a baseline capability
changes.
