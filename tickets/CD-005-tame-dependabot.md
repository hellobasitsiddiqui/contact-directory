# CD-005: Tame Dependabot (group updates, ignore majors)

- **Type:** chore
- **Status:** In review
- **Branch:** `CD-005-tame-dependabot`

## Summary
Enabling Dependabot opened 11 individual PRs (incl. a Spring Boot 4.0 major). Reduce the noise by
grouping minor/patch updates into one PR per ecosystem, lowering the open-PR limit, and ignoring
major bumps (handle those manually).

## Acceptance criteria
- [x] Maven minor/patch grouped into one PR; majors ignored
- [x] GitHub Actions updates grouped into one PR
- [x] open-pull-requests-limit lowered to 5
- [ ] Existing 11 individual PRs consolidated (Dependabot re-runs on config change; or close manually)

## Notes
Major version bumps (e.g. Spring Boot 4.x, springdoc 3.x) are intentionally excluded — opt back in
per-dependency if/when you want to tackle them.
