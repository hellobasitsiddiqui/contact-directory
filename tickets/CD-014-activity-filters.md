# CD-014: Activity log — action multi-select + date-range filter

- **Type:** feature (UI)
- **Status:** In review
- **Branch:** `CD-014-activity-filters`

## Summary
Richer client-side filtering on the admin Activity log: multi-select action filter and a date range,
over a larger fetched window (no backend change).

## Acceptance criteria
- [ ] Fetch a larger recent window once; filter client-side by actor, action (multi-select), date range
- [ ] Shows "N of M" count + empty state; reuses timeCell + design tokens
- [ ] No backend/spec change; tests stay green
