# CD-012: Users table — bulk select + bulk actions

- **Type:** feature (UI)
- **Status:** In review
- **Branch:** `CD-012-users-bulk-actions`

## Summary
Select multiple users and apply an action to all (enable/disable/role/delete), client-side via the
existing per-user endpoints.

## Acceptance criteria
- [ ] Row checkboxes + select-all (over the currently filtered rows); own row excluded
- [ ] Bulk bar (count + Disable/Enable/Make ADMIN/Make USER/Delete); Delete uses confirmDialog
- [ ] Applies via existing endpoints (loop), reloads, toast summary; no backend change; tests green
