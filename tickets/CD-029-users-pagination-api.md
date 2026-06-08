# CD-029: Server-side pagination/filter/sort for /api/v1/users

- **Type:** backend
- **Status:** Open
- **Branch:** `CD-029-users-pagination-api`

## Summary
`GET /api/v1/users` returns a bare `List<UserResponse>`; the web admin UI paginates/filters/sorts in
JS after pulling the whole list. Won't scale on mobile. See
[`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Tier 2.

## Acceptance criteria
- [ ] Return `Page<UserResponse>` with `@PageableDefault(size=20, sort="username")`.
- [ ] Add optional `search` (username/email) `@RequestParam`.
- [ ] Update `users.js` to consume server-side paging (the SPA currently does it client-side). **Breaks web SPA — coordinate.**
- [ ] Tests for paging/filter/sort.

## Notes
Mirrors the Contacts endpoint's existing server-side paging for a consistent list contract.
