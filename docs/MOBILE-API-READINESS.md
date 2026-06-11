# Mobile API readiness — findings & roadmap

> **Status: Tier 1 implemented and released** — CD-027 (TLS-ready) and CD-028 (refresh tokens) shipped
> in **1.0.0-beta.3**; the remaining items stay tracked as tickets **CD-029 … CD-035** (see
> [`../tickets/README.md`](../tickets/README.md)). This records the original audit of reusing the
> existing REST API for a native mobile (iOS/Android) client. Originally grounded in the codebase as
> of 1.0.0-beta.2.
>
> **Update:** a native Android client (and a WebView wrapper) has since been **built and shipped** —
> see [contact-directory-android](https://github.com/hellobasitsiddiqui/contact-directory-android):
> login, contacts (search/pagination), full CRUD, favourites, trash, profile, and silent token
> refresh, with CI-built APKs and Maestro UI tests.

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
2. **Token lifecycle** — ✅ **done (CD-028).** Login/register return a **15m access JWT + opaque
   rotating refresh token** (14d sliding, stored hashed, family-based reuse/theft detection);
   `POST /auth/refresh` rotates, `POST /auth/logout` revokes server-side ("log out lost device"),
   and password change/reset, disable & delete revoke sessions. The web SPA refreshes silently.
   *Mobile note:* store the refresh token in Keychain/Keystore and refresh on 401 or proactively.
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

1. ✅ **CD-027 (TLS)** — done app-side (HSTS + proxy awareness + Caddy overlay); a real host/domain
   lands with the deploy step (CD-025).
2. ✅ **CD-028 (refresh tokens + revocation)** — done; the core mobile auth gap is closed.
3. **CD-029 / CD-030 / CD-031** — scale & UX correctness.
4. **CD-032 → CD-035** — incremental; most don't touch the web SPA.

**Tier 1 is complete** — the server no longer blocks a mobile client (point it at an HTTPS host).
Tier 2–3 are incremental.

## Appendix — effort for a "very basic" native Android app

> **Superseded:** the app was built (and went well beyond this scope — full CRUD, trash, favourites,
> profile, silent refresh) at
> [contact-directory-android](https://github.com/hellobasitsiddiqui/contact-directory-android).
> The estimate below is kept for the record; it proved about right for the basic scope.

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
  API 28+ — for local dev either point at HTTPS or allow cleartext for your dev host; and the access
  token now lasts **15 minutes** (CD-028), so to stay signed in beyond that, store the **refresh
  token** from the login response and call `POST /auth/refresh` when a request 401s (one extra call;
  the refresh token lasts 14 days). The simplest demo can skip refresh and just re-login on a 401.
