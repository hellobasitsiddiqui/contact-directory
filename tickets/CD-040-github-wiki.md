# CD-040: Publish docs to the GitHub Wiki (optional mirror)

- **Type:** docs / ci
- **Status:** Open
- **Branch:** `CD-040-github-wiki`

## Summary
Optionally surface the project docs on the repo's **GitHub Wiki** as a browsable landing page. The
in-repo docs (`README`, `docs/*`, `FEATURES`, `tickets/`) stay the **source of truth**; the wiki is a
mirror, kept in sync by automation so it never drifts.

## GitHub Wiki vs Confluence
Same *idea*, different scale. GitHub Wiki = a lightweight, **git-backed** Markdown wiki (each wiki is a
separate `…wiki.git` repo), free, co-located with the code, flat-ish sidebar, repo-tied permissions.
Confluence = a heavier enterprise platform (spaces / page-trees, macros, templates, Jira integration,
granular permissions, paid). For this project the GitHub Wiki is plenty.

## Acceptance criteria
- [ ] Enable the wiki and **create the first page once in the UI** (this initialises the `…wiki.git`).
- [ ] Decide which docs to mirror (e.g. `README.md`, `docs/*.md`, `FEATURES.md`, `COMMON-FEATURES.md`).
- [ ] Add `.github/workflows/wiki-sync.yml` that pushes the selected docs to the wiki repo on merge to
      `master`/`develop` (so the wiki always reflects committed docs — no manual copy-paste).
- [ ] Note in `CONTRIBUTING.md` that the wiki is generated/mirrored and **edited in-repo, not on the wiki**.

## Notes
The wiki lives outside the PR/CI/branch-protection flow, so treating it as a read-only mirror (not an
editable source) avoids drift. Low priority — nice-to-have presentation layer.
