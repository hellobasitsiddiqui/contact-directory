# Contact Directory — Feature Roadmap

Rolling out one feature at a time, committing each. Status updated as work lands.

| # | Feature | Status |
|---|---------|--------|
| 1 | Search & filter — live search by name, company, phone, email | ✅ done |
| 2 | Tags / categories — label contacts (Friend, Work, Client, Family) + filter | ✅ done |
| 3 | Favourite / star — pin important contacts to the top | ✅ done |
| 4 | Import / export — CSV import (bulk add), export CSV/JSON | ✅ done |
| 5 | Avatar / initials — auto coloured initials circle, or photo upload | ✅ done |
| 6 | Sort controls — name A–Z, recently added, last contacted | ✅ done |
| 7 | Contact detail modal — full profile card (details, notes, social links) | ✅ done |
| 8 | Notes field — freetext notes per contact | ✅ done |
| 9 | Click-to-action — tel:/mailto:/maps links | ✅ done |
| 10 | Dark / light mode toggle — saved to localStorage | ✅ done |

Legend: ⬜ pending · 🔄 in progress · ✅ done · ⏭️ skipped (with reason)

## Post-rollout — "safe-management" pack (one-shot multi-agent build)

| Feature | Status |
|---------|--------|
| Soft delete + Trash + Undo — `DELETE` soft-deletes; `GET /trash`, restore, delete-forever; Undo toast | ✅ done |
| Bulk actions — multi-select rows; bulk favourite / tag / delete (`POST /bulk/*`) | ✅ done |
| Optimistic concurrency — `@Version` on contacts; stale edits return `412` | ✅ done |

Also: persistence switched to **H2 file mode** (`./data/contacts.mv.db`); tests stay on isolated in-memory H2. Suite: **75 tests passing**.
