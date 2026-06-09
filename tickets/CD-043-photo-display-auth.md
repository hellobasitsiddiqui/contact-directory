# CD-043: Contact photos don't display in the browser (img can't authenticate)

- **Type:** bug / frontend
- **Status:** Open
- **Branch:** `CD-043-photo-display-auth`

## Summary
Uploaded contact photos **never render in the web UI**. `ContactResponse` returns
`photoUrl = /api/v1/contacts/{id}/photo`, and the frontend sets `img.src = photoUrl` directly
(`avatarElement`, `showExistingPhoto`). That endpoint requires a **Bearer JWT** (it's not in
`SecurityConfig.PUBLIC_GET`), but an `<img>` tag can't send the `Authorization` header (the token lives
in `localStorage` and is only attached by the `request()` fetch helper). So the browser issues an
unauthenticated GET → **401** → the avatar shows a broken/blank image. Pre-existing; affects H2 and
Postgres alike. Surfaced by the CD-042 Postgres Playwright test.

## Acceptance criteria
- [ ] Load avatar photos via an **authenticated `fetch`** → `Blob` → object URL, and set `img.src` to
      that (revoke the object URL appropriately to avoid leaks). Apply to `avatarElement` (table +
      detail) and `showExistingPhoto` (edit preview). Keep `avatarElement` usable where it's called
      synchronously (build the `<img>`, then async-load + set `src`).
- [ ] Verify photos render in both the contacts table and the detail/edit modal.
- [ ] Strengthen the CD-042 Playwright test to assert the avatar image actually **loads**
      (`naturalWidth > 0`) once this lands.

## Notes
Alternative (rejected): making the photo GET endpoint public would expose per-user contact photos
without auth — not acceptable. The authenticated-fetch-to-blob approach keeps access control intact.
