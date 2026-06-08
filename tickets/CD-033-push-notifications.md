# CD-033: Push notifications (FCM/APNs)

- **Type:** backend
- **Status:** Open
- **Branch:** `CD-033-push-notifications`

## Summary
No real-time push today. Tier 3 polish. See
[`../docs/MOBILE-API-READINESS.md`](../docs/MOBILE-API-READINESS.md) §Tier 3.

## Acceptance criteria
- [ ] Device-token subscription endpoint (`POST /api/v1/subscriptions`: deviceToken, platform).
- [ ] Publish to FCM (Android) / APNs (iOS) on relevant mutations.
- [ ] Secrets via env; tests/docs.

## Notes
Large effort. Alternative until then is client polling (battery-costly) — prefer CD-032 delta sync first.
