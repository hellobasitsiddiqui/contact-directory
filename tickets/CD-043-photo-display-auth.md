# CD-043: Contact photos don't display in the browser (img can't authenticate)

- **Type:** bug / frontend
- **Status:** In review
- **Branch:** `CD-043-photo-display-auth`

## Summary
Uploaded contact photos **never rendered in the web UI**. `ContactResponse` returns
`photoUrl = /api/v1/contacts/{id}/photo`, and the frontend set `img.src = photoUrl` directly. That
endpoint requires a **Bearer JWT** (not in `SecurityConfig.PUBLIC_GET`), but an `<img>` tag can't send
the `Authorization` header (the token lives in `localStorage`, attached only by the `request()` fetch
helper). So the browser issued an unauthenticated GET → **401** → broken/blank avatar. Pre-existing;
affected H2 and Postgres alike. Surfaced by the CD-042 Postgres Playwright test.

## Fix
New `setAuthedImageSrc(img, url, onUrl)` helper in `app.js`: fetches the photo **with the bearer
token**, wraps the bytes in an object URL, and assigns that to `img.src` (revoking the URL once the
image decodes, to avoid leaking blobs). Applied to:
- `avatarElement` (contacts table + detail modal)
- `showExistingPhoto` (edit-modal preview; tracks the object URL via `previewObjectUrl` so
  `revokePreviewUrl()` frees it on hide/reset)

Access control is unchanged — the endpoint stays authenticated; only the fetch carries the token.

## Acceptance criteria
- [x] Load avatar photos via authenticated `fetch` → `Blob` → object URL (with revocation).
- [x] Apply to table avatars, the detail modal, and the edit-modal preview.
- [x] Strengthen the CD-042 Postgres Playwright test to assert the avatar image actually **loads**
      (`naturalWidth > 0`).

## Verification
- `PlaywrightPostgresE2eTest` (real Postgres): after upload, the avatar image **decodes**
  (`naturalWidth > 0`) and is visible — screenshot shows the uploaded photo rendered.
- No regression: `mvn verify` H2 gate **219 green**; the existing H2 `PlaywrightE2eTest` walkthrough
  still passes (the placeholder path for photo-less contacts is unchanged).

## Notes
Alternative (rejected): making the photo GET endpoint public would expose per-user contact photos
without auth. The authenticated-fetch-to-blob approach keeps access control intact.
