# CD-010: Copy-to-clipboard buttons (emails / usernames)

- **Type:** feature (UI)
- **Status:** In review
- **Branch:** `CD-010-copy-to-clipboard`

## Summary
Add small copy buttons to copy a contact's email and a user's username to the clipboard.

## Acceptance criteria
- [ ] Copy button next to username (Users table) and email (Contacts table)
- [ ] Uses navigator.clipboard with brief "Copied" feedback; graceful fallback
- [ ] Accessible (aria-label, button); no API change; tests stay green
