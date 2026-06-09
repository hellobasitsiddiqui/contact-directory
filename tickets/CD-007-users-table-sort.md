# CD-007: Users table — sortable columns

- **Type:** feature (UI)
- **Status:** In review
- **Branch:** `CD-007-users-table-sort`

## Summary
Let admins sort the Users table by clicking column headers, composing with the CD-006 search/filter.

## Acceptance criteria
- [ ] Click a header (Username / Role / Status / Created) to sort by it; click again toggles asc/desc
- [ ] Active column shows a ▲/▼ indicator and sets aria-sort
- [ ] Sorting is client-side and composes with the existing search/filter
- [ ] No API change; existing tests stay green
