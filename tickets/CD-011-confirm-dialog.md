# CD-011: Styled confirmation dialog (replace native confirm)

- **Type:** feature (UI)
- **Status:** In review
- **Branch:** `CD-011-confirm-dialog`

## Summary
Replace native window.confirm() for destructive actions with a styled, accessible modal dialog.

## Acceptance criteria
- [ ] Reusable confirm dialog (title/message/confirm label/danger style) returning a Promise<boolean>
- [ ] Used for delete-user (Users) and delete/permanent-delete contact (Contacts)
- [ ] Esc cancels, focusable buttons, consistent styling; no API change; tests stay green
