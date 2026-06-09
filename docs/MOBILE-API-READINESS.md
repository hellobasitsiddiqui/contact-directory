# Mobile API readiness — findings & roadmap

> **Status: saved findings, not implemented.** This records the audit of reusing the existing REST API
> for a native mobile (iOS/Android) client, so the work can be picked up later. Actionable items are
> tracked as tickets **CD-027 … CD-035** (see [`../tickets/README.md`](../tickets/README.md)).
> Grounded in the codebase as of 1.0.0-beta.2.

## Big picture

The API is **~70% mobile-ready already** because it's a stateless, token-based JSON API — exactly what
mobile wants. The real work is concentrated in **three areas: TLS, the token lifecycle, and a couple
of SPA-shaped endpoints.** Everything else is incremental polish.

**Native vs hybrid (CORS):** a **native** app (Swift/Kotlin) uses the OS HTTP stack, is *not* a
browser, sends no `Origin`, so **CORS does not apply**. Only a **hybrid** app (Capacitor / Cordova /
Ionic / React-Native-WebView) runs in a browser engine and enforces CORS. Build native → ignore CORS.

## ✅ Already mobile-ready — reuse as-is

| Capability | Why it's good for mobile | Evidence |
|---|---|---|
| Stateless JWT in `Authorization: Bearer` | No cookies/sessions, no CORS for native | `JwtAuthenticationFilter` |
| `/api/v1` on every controller | Versioned URLs → can ship `/v2` without breaking installed apps | all controllers |
| Consistent error envelope (`ApiError`: `timestamp/status/error/message/path/errors`) | One shape to parse; field-level validation errors | `GlobalExceptionHandler` |
| ISO-8601 `Instant` timestamps + camelCase fields | Swift `Codable` / Kotlin decode natively | model + DTOs |
| Server-side pagination + search + sort on **contacts** | Bandwidth/battery friendly | `ContactController` (`Page<T>`, `?page/size/sort/search/tag`) |
| Optimistic locking (`@Version` → `412`) + soft-delete + `updatedAt` | The primitives for offline-first sync | `Contact` model |
| OpenAPI 3.1.0 spec | Generate a typed Swift/Kotlin client | `openapi.json`/`openapi.yaml` |
| Brute-force lockout + account-enumeration protection | Login security for hostile networks | `LoginAttemptService` |

## ⚠️ Tier 1 — must change before a real mobile launch

1. **HTTPS/TLS (+ HSTS)** — ✅ **app-side done (CD-027).** iOS App Transport Security rejects plain
   HTTP by default; Android blocks cleartext on API 28+. The app now emits **HSTS** and is
   **proxy-aware** (`forward-headers-strategy=framework`), and a Caddy reverse-proxy overlay
   (`docker-compose.tls.yml`, automatic Let's Encrypt) ships for TLS termination. *Remaining:* wire it
   to a real host + domain as part of the deploy step (**CD-025**).
2. **Token lifecycle** — today there's a **single 24h access JWT, no refresh token, no server-side
   logout/revocation** (`logout()` just clears `localStorage`). A lost device's token stays valid 24h
   with no way to revoke. Add **short access token + refresh token**, `POST /auth/refresh`, a
   server-side **revocation list**, and a real `POST /auth/logout`. → **CD-028**
3. **Secure token storage (client rule)** — the web uses plaintext `localStorage`; **mobile must use
   iOS Keychain / Android Keystore** (e.g. Android `EncryptedSharedPreferences`/DataStore). Pair with
   **certificate pinning** for high-value deployments. (Client-side; documented here, no server work.)

## 🔧 Tier 2 — correctness / scale

4. **Paginate `GET /api/v1/users`** — currently returns a **bare `List<UserResponse>`**; the web admin
   UI paginates/filters/sorts in JavaScript after pulling the whole list. Won't scale on a phone. Make
   it `Page<UserResponse>` with `search`/`sort` like contacts. ⚠️ Breaks the web SPA's `users.js`. → **CD-029**
5. **Larger photo upload + absolute `photoUrl`** — uploads are capped at **2MB** (`spring.servlet.multipart`
   + `ContactController`); phone photos exceed that. Raise to ~5–10MB. Also `photoUrl` is **relative**
   (`/api/v1/contacts/{id}/photo`) — return absolute (or expose a base URL). → **CD-030**
6. **Lockout/rate-limit feedback + API rate limiting** — `423 LOCKED` doesn't say *how long*; add
   `Retry-After`/remaining time. Only login is throttled today — add **API-wide rate limiting**
   (`429` + `X-RateLimit-*`) so a runaway sync loop can't hammer the server. → **CD-031**

## 🚀 Tier 3 — polish for a first-class app

7. **Offline sync + conditional requests** — leverage existing `updatedAt`/`@Version`: add
   `GET /api/v1/contacts/changes?since=…` and/or `ETag`/`If-Modified-Since` (`304`). → **CD-032**
8. **Push notifications** (FCM/APNs) + device-token subscription endpoint. → **CD-033**
9. **App-version gating + mobile bootstrap** — `X-App-Version` → `426 Upgrade Required`; a
   `GET /api/v1/info` returning min-version, upload limits, formats, absolute expiry. → **CD-034**
10. **CORS config** — only if you go **hybrid** (WebView). Configurable bean locked to your origins. → **CD-035**

Smaller niceties (no ticket yet): `X-Request-ID` for cross-boundary debugging; optional biometric
re-auth for sensitive actions; absolute token-expiry timestamp in `/auth/me`.

## Sequencing

1. **CD-027 (TLS)** — unblocks iOS/Android at all.
2. **CD-028 (refresh tokens + revocation)** — the core mobile auth gap (can be additive so the web SPA isn't forced to change day one).
3. **CD-029 / CD-030 / CD-031** — scale & UX correctness.
4. **CD-032 → CD-035** — incremental; most don't touch the web SPA.

Only Tier 1 truly blocks a mobile client; Tier 2–3 are incremental.

## Appendix — effort for a "very basic" native Android app

Scope: **login screen** (POST `/api/v1/auth/login`, store token) + **contacts list** (GET
`/api/v1/contacts` with bearer token, render names) — nothing else.

- **Stack:** Kotlin + Jetpack Compose, Retrofit/OkHttp + Moshi/kotlinx-serialization, one `ViewModel`,
  `EncryptedSharedPreferences` for the token.
- **Work:** project setup; 2 screens; 2 API calls; parse the **`Page<T>`** contacts response (read
  `.content`); handle `401` → back to login.
- **Effort:** **~1 focused day** for a competent Android dev (½ day if using OpenAPI codegen for the
  client + skipping polish; up to ~2 days including secure storage, error states, and first-time
  Android project setup).
- **Server changes needed: none** for this basic app. Caveats: Android blocks **cleartext HTTP** on
  API 28+ — for local dev either point at HTTPS or allow cleartext for your dev host; and the 24h
  token means re-login after a day (fine for a demo).
