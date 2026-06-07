/* ------------------------------------------------------------------ *
 * Login / register page logic.
 *
 * Talks to the public /api/v1/auth endpoints, stores the returned JWT in
 * localStorage, then redirects to the main app. Shares the storage keys with
 * app.js (auth_token / auth_user).
 * ------------------------------------------------------------------ */

const AUTH_BASE = '/api/v1/auth';
const TOKEN_KEY = 'auth_token';
const USER_KEY = 'auth_user';

let mode = 'login'; // 'login' | 'register'

const els = {};

function $(id) {
  return document.getElementById(id);
}

function cacheEls() {
  els.tabLogin = $('tab-login');
  els.tabRegister = $('tab-register');
  els.form = $('auth-form');
  els.subtitle = $('auth-subtitle');
  els.username = $('username');
  els.password = $('password');
  els.error = $('auth-error');
  els.submit = $('auth-submit');
  els.hint = $('login-hint');
}

function setMode(next) {
  mode = next;
  const isLogin = mode === 'login';

  els.tabLogin.classList.toggle('is-active', isLogin);
  els.tabRegister.classList.toggle('is-active', !isLogin);
  els.tabLogin.setAttribute('aria-selected', String(isLogin));
  els.tabRegister.setAttribute('aria-selected', String(!isLogin));

  els.subtitle.textContent = isLogin
    ? 'Sign in to manage your contacts.'
    : 'Create an account to get started.';
  els.submit.textContent = isLogin ? 'Sign in' : 'Create account';
  els.password.setAttribute('autocomplete', isLogin ? 'current-password' : 'new-password');
  els.hint.hidden = !isLogin;
  hideError();
}

function showError(message) {
  els.error.textContent = message;
  els.error.hidden = false;
}

function hideError() {
  els.error.textContent = '';
  els.error.hidden = true;
}

function setBusy(busy) {
  els.submit.disabled = busy;
  els.submit.textContent = busy
    ? (mode === 'login' ? 'Signing in…' : 'Creating…')
    : (mode === 'login' ? 'Sign in' : 'Create account');
}

async function submit(event) {
  event.preventDefault();
  hideError();

  const username = els.username.value.trim();
  const password = els.password.value;

  if (username.length < 3) {
    showError('Username must be at least 3 characters.');
    return;
  }
  if (password.length < 6) {
    showError('Password must be at least 6 characters.');
    return;
  }

  setBusy(true);
  try {
    const endpoint = mode === 'login' ? '/login' : '/register';
    const response = await fetch(`${AUTH_BASE}${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password }),
    });

    if (!response.ok) {
      // 423 LOCKED: too many failed attempts. Lead with a clear lockout message,
      // but still prefer the server's message when it provides one.
      let message = response.status === 423
        ? 'Account locked — too many failed attempts. Try again later.'
        : (mode === 'login'
          ? 'Invalid username or password.'
          : 'Could not create account.');
      try {
        const body = await response.json();
        if (body && body.message) message = body.message;
      } catch (_) { /* keep default */ }
      showError(message);
      setBusy(false);
      return;
    }

    const data = await response.json();
    localStorage.setItem(TOKEN_KEY, data.token);
    localStorage.setItem(USER_KEY, JSON.stringify({
      username: data.username,
      role: data.role,
    }));
    window.location.replace('index.html');
  } catch (err) {
    showError('Network error — could not reach the server.');
    setBusy(false);
  }
}

function init() {
  cacheEls();
  els.tabLogin.addEventListener('click', () => setMode('login'));
  els.tabRegister.addEventListener('click', () => setMode('register'));
  els.form.addEventListener('submit', submit);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true });
} else {
  init();
}
