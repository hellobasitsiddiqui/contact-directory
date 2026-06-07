/* ------------------------------------------------------------------ *
 * Account self-service (profile) page.
 *
 * Self-contained: shares the localStorage auth keys with app.js / login.js but
 * has its own minimal API layer. Shows the signed-in user's username, role and
 * member-since (from GET /api/v1/auth/me), and lets them change their own
 * password (POST /api/v1/auth/change-password). The page is auth-guarded by the
 * pre-paint script in profile.html; the API calls remain authenticated.
 * ------------------------------------------------------------------ */

const AUTH_BASE = '/api/v1/auth';
const TOKEN_KEY = 'auth_token';
const USER_KEY = 'auth_user';

const auth = {
  token() {
    return localStorage.getItem(TOKEN_KEY);
  },
  user() {
    try {
      return JSON.parse(localStorage.getItem(USER_KEY) || 'null');
    } catch (_) {
      return null;
    }
  },
  /** Clear credentials and return to the login page. */
  logout() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    window.location.replace('login.html');
  },
};

function $(id) {
  return document.getElementById(id);
}

/** Fetch wrapper: attaches the bearer token, throws on non-2xx, redirects on 401. */
async function request(url, options = {}) {
  const opts = { ...options };
  if (opts.body !== undefined && opts.body !== null && typeof opts.body !== 'string') {
    opts.headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
    opts.body = JSON.stringify(opts.body);
  }
  const token = auth.token();
  if (token) {
    opts.headers = { ...(opts.headers || {}), Authorization: `Bearer ${token}` };
  }

  const response = await fetch(url, opts);
  // An expired/invalid token (or being signed out server-side) -> back to login.
  if (response.status === 401) {
    auth.logout();
    const err = new Error('Session expired — please sign in again.');
    err.status = 401;
    throw err;
  }
  if (!response.ok) {
    let message = `Request failed (${response.status})`;
    let body = null;
    try {
      body = await response.json();
      if (body && body.message) message = body.message;
    } catch (_) { /* keep default */ }
    const err = new Error(message);
    err.status = response.status;
    err.body = body;
    throw err;
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

/* ------------------------------------------------------------------ *
 * Toast (shared #toast element; revealed via the toast--show class).
 * ------------------------------------------------------------------ */
let toastTimer = null;
function toast(message, variant = 'success') {
  const node = $('toast');
  if (!node) return;
  node.textContent = message;
  node.classList.remove('toast--success', 'toast--error');
  node.classList.add(variant === 'error' ? 'toast--error' : 'toast--success');
  node.classList.add('toast--show');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    node.classList.remove('toast--show');
  }, 3500);
}

/* ------------------------------------------------------------------ *
 * Inline message under the password form (errors + success).
 * ------------------------------------------------------------------ */
function showMessage(message, variant) {
  const node = $('password-message');
  if (!node) return;
  node.textContent = message;
  node.classList.remove('profile-message--error', 'profile-message--success');
  node.classList.add(variant === 'error' ? 'profile-message--error' : 'profile-message--success');
  node.hidden = false;
}

function hideMessage() {
  const node = $('password-message');
  if (!node) return;
  node.textContent = '';
  node.hidden = true;
}

/* ------------------------------------------------------------------ *
 * Theme toggle (mirrors app.js so the button behaves consistently).
 * ------------------------------------------------------------------ */
function currentTheme() {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

function updateThemeButton() {
  const btn = $('btn-theme');
  if (!btn) return;
  const dark = currentTheme() === 'dark';
  btn.textContent = dark ? '☀️' : '🌙';
  btn.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
  btn.setAttribute('aria-pressed', dark ? 'true' : 'false');
}

function toggleTheme() {
  const next = currentTheme() === 'dark' ? 'light' : 'dark';
  document.documentElement.dataset.theme = next;
  try {
    localStorage.setItem('theme', next);
  } catch (e) {
    /* ignore storage failures (private mode, etc.) */
  }
  updateThemeButton();
}

/* ------------------------------------------------------------------ *
 * Profile summary (username / role / member-since).
 * ------------------------------------------------------------------ */

/** Format an ISO timestamp for display, or em dash when missing/invalid. */
function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

/** Load the signed-in user's details from /me and render the summary. */
async function loadProfile() {
  // Show what we already know from localStorage immediately, then refresh.
  const cached = auth.user();
  if (cached) {
    if (cached.username) $('profile-username').textContent = cached.username;
    if (cached.role) $('profile-role').textContent = cached.role;
  }
  try {
    const me = await request(`${AUTH_BASE}/me`, { method: 'GET' });
    if (me) {
      $('profile-username').textContent = me.username || '—';
      $('profile-role').textContent = me.role || '—';
      $('profile-created').textContent = formatDate(me.createdAt);
    }
  } catch (err) {
    if (err.status !== 401) {
      toast(err.message || 'Could not load your profile.', 'error');
    }
  }
}

/* ------------------------------------------------------------------ *
 * Change password.
 * ------------------------------------------------------------------ */
function setBusy(busy) {
  const submit = $('password-submit');
  if (submit) {
    submit.disabled = busy;
    submit.textContent = busy ? 'Updating…' : 'Update password';
  }
}

async function submitPassword(event) {
  event.preventDefault();
  hideMessage();

  const currentPassword = $('current-password').value;
  const newPassword = $('new-password').value;
  const confirmPassword = $('confirm-password').value;

  // Client-side checks mirror the server's NotBlank + Size(6..100) rules and add
  // the confirm-match guard, so obvious mistakes never hit the network.
  if (!currentPassword) {
    showMessage('Enter your current password.', 'error');
    return;
  }
  if (newPassword.length < 6) {
    showMessage('New password must be at least 6 characters.', 'error');
    return;
  }
  if (newPassword !== confirmPassword) {
    showMessage('New password and confirmation do not match.', 'error');
    return;
  }

  setBusy(true);
  try {
    await request(`${AUTH_BASE}/change-password`, {
      method: 'POST',
      body: { currentPassword, newPassword },
    });
    $('password-form').reset();
    showMessage('Password updated.', 'success');
    toast('Password updated.', 'success');
  } catch (err) {
    // 400 covers a wrong current password and bean-validation failures; surface
    // the server's message either way. Other statuses fall through to the same.
    const message = err.message || 'Could not change your password.';
    showMessage(message, 'error');
    toast(message, 'error');
  } finally {
    setBusy(false);
  }
}

/* ------------------------------------------------------------------ *
 * Bootstrap.
 * ------------------------------------------------------------------ */
function init() {
  const user = auth.user();
  if (user && user.username) {
    const pill = $('current-user');
    if (pill) {
      pill.textContent = user.username;
      pill.hidden = false;
    }
  }

  const themeBtn = $('btn-theme');
  if (themeBtn) themeBtn.addEventListener('click', toggleTheme);
  updateThemeButton();

  const logoutBtn = $('btn-logout');
  if (logoutBtn) logoutBtn.addEventListener('click', () => auth.logout());

  const form = $('password-form');
  if (form) form.addEventListener('submit', submitPassword);

  loadProfile();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true });
} else {
  init();
}
