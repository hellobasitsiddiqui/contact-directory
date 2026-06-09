# CD-006: Users table — search + role/status filter

- **Type:** feature (UI)
- **Status:** In review
- **Branch:** `CD-006-users-table-search`

## Summary
Add a filter bar to the admin Users page so a growing user list stays manageable: text search by
username plus Role and Status filters, applied client-side over the already-fetched list.

## Acceptance criteria
- [ ] Search input (matches username, case-insensitive) on the Users page
- [ ] Role filter (All / USER / ADMIN) and Status filter (All / Enabled / Disabled)
- [ ] Filtering is instant (client-side, no API change); empty state when nothing matches
- [ ] Styling consistent with existing pages; existing tests stay green
