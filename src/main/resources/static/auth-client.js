/* ------------------------------------------------------------------ *
 * Shared auth client (CD-028). Loaded BEFORE each page's script.
 *
 * Owns the token lifecycle on the browser side:
 *  - stores the access token, refresh token and access-token expiry
 *    (localStorage, shared keys with every page),
 *  - silently refreshes: proactively when the access token is about to
 *    expire, and reactively on a 401 (then retries the request once),
 *  - serializes refreshes across tabs (navigator.locks when available,
 *    else a per-page single-flight promise),
 *  - real logout: best-effort POST /auth/logout so the refresh session
 *    dies server-side, then clears storage and returns to the login page.
 *
 * Exposed as window.AuthClient — an IIFE so no top-level const collides
 * with the page scripts' own helpers.
 * ------------------------------------------------------------------ */
(function () {
  'use strict';

  const AUTH_API = '/api/v1/auth';
  const KEY_TOKEN = 'auth_token';
  const KEY_USER = 'auth_user';
  const KEY_REFRESH = 'auth_refresh_token';
  const KEY_EXPIRES_AT = 'auth_expires_at';

  /** Refresh this many ms before the access token actually expires. */
  const EXPIRY_SLACK_MS = 60 * 1000;

  /** Per-page single-flight: at most one refresh request in flight. */
  let inflightRefresh = null;

  function token() {
    return localStorage.getItem(KEY_TOKEN);
  }

  function user() {
    try {
      return JSON.parse(localStorage.getItem(KEY_USER) || 'null');
    } catch (_) {
      return null;
    }
  }

  /**
   * Persists a token pair returned by login/register/refresh/change-password.
   * Carries username/role into auth_user when present, otherwise preserves the
   * already-stored identity (refresh responses always include them; defensive).
   */
  function savePair(data) {
    if (!data || !data.token) return;
    localStorage.setItem(KEY_TOKEN, data.token);
    if (typeof data.expiresInMs === 'number') {
      localStorage.setItem(KEY_EXPIRES_AT, String(Date.now() + data.expiresInMs));
    }
    if (data.refreshToken) {
      localStorage.setItem(KEY_REFRESH, data.refreshToken);
    }
    if (data.username && data.role) {
      localStorage.setItem(KEY_USER, JSON.stringify({
        username: data.username,
        role: data.role,
      }));
    }
  }

  function clear() {
    localStorage.removeItem(KEY_TOKEN);
    localStorage.removeItem(KEY_USER);
    localStorage.removeItem(KEY_REFRESH);
    localStorage.removeItem(KEY_EXPIRES_AT);
  }

  /** True when the stored access token expires within the slack window. */
  function isStale() {
    const at = Number(localStorage.getItem(KEY_EXPIRES_AT));
    // Unknown expiry (pre-CD-028 session): treat as fresh; reactive 401
    // handling still rescues the session if the token has in fact expired.
    if (!at) return false;
    return Date.now() >= at - EXPIRY_SLACK_MS;
  }

  /** POSTs the refresh token; saves the rotated pair. Resolves true on success. */
  async function doRefresh() {
    const refreshToken = localStorage.getItem(KEY_REFRESH);
    if (!refreshToken) return false;
    try {
      const response = await fetch(`${AUTH_API}/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken }),
      });
      if (!response.ok) return false;
      const data = await response.json();
      // Storage may have changed during the round-trip. If it was cleared
      // (a logout in another tab), DO NOT resurrect it. If another tab already
      // rotated, a fresher token is in place — keep it, don't clobber.
      const stored = localStorage.getItem(KEY_REFRESH);
      if (stored === null) return false;
      if (stored !== refreshToken) return true;
      savePair(data);
      return true;
    } catch (_) {
      return false; // network error — caller falls back to logout via 401 path
    }
  }

  /**
   * Single refresh shared by every caller (and every tab, where the Web Locks
   * API exists). The skip decision is by token IDENTITY, not the clock: if the
   * access token a caller saw as stale/rejected has ALREADY changed in storage
   * (another caller/tab refreshed it), reuse that instead of refreshing again.
   * A server-rejected-but-locally-"fresh" token (the reactive-401 case) is NOT
   * skipped — its identity is unchanged, so doRefresh actually runs.
   *
   * @param staleToken the access token the caller observed needing replacement
   */
  function tryRefresh(staleToken) {
    if (inflightRefresh) return inflightRefresh;
    const run = async () => {
      if (staleToken && token() !== staleToken) {
        return true; // someone else already refreshed this token
      }
      return doRefresh();
    };
    const locked = (navigator.locks && navigator.locks.request)
      ? navigator.locks.request('auth-refresh', run)
      : run();
    inflightRefresh = Promise.resolve(locked).finally(() => {
      inflightRefresh = null;
    });
    return inflightRefresh;
  }

  /** Refreshes proactively when the access token is near expiry. */
  async function ensureFresh() {
    if (isStale() && localStorage.getItem(KEY_REFRESH)) {
      await tryRefresh(token());
    }
  }

  /**
   * fetch() with the full token lifecycle: proactive refresh, bearer header,
   * and on a 401 one reactive refresh + single retry. Returns the final
   * Response — callers keep their own error shaping. A 401 returned from here
   * means the session is truly dead (no/expired/revoked refresh token).
   */
  async function authFetch(url, opts) {
    await ensureFresh();
    const send = (bearer) => {
      const options = { ...(opts || {}) };
      if (bearer) {
        options.headers = { ...(options.headers || {}), Authorization: `Bearer ${bearer}` };
      }
      return fetch(url, options);
    };
    const bearer = token();
    let response = await send(bearer);
    if (response.status === 401 && localStorage.getItem(KEY_REFRESH)) {
      // Refresh keyed on the token that was actually rejected, then retry with
      // whatever fresh token resulted (this tab's or another's).
      if (await tryRefresh(bearer)) {
        response = await send(token());
      }
    }
    return response;
  }

  /**
   * Real logout: revoke the refresh session server-side (best effort — the
   * redirect must not wait on a dead network), then clear and go to login.
   */
  function logout() {
    const refreshToken = localStorage.getItem(KEY_REFRESH);
    if (refreshToken) {
      try {
        const body = JSON.stringify({ refreshToken });
        // keepalive lets the request outlive the page navigation.
        fetch(`${AUTH_API}/logout`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body,
          keepalive: true,
        }).catch(() => {});
      } catch (_) { /* revocation is best-effort; local sign-out always wins */ }
    }
    clear();
    window.location.replace('login.html');
  }

  window.AuthClient = {
    token,
    user,
    savePair,
    clear,
    ensureFresh,
    tryRefresh,
    authFetch,
    logout,
  };
})();
