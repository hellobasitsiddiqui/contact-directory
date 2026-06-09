/* ------------------------------------------------------------------ *
 * Admin dashboard — the admin landing page (user administration).
 *
 * Self-contained: shares the localStorage auth keys with app.js / login.js but
 * has its own minimal API layer. Admin-only; non-admins are bounced by the guard
 * in dashboard.html. Shows user totals + recent activity + quick links.
 * ------------------------------------------------------------------ */

const USERS_API = '/api/v1/users';
const AUDIT_API = '/api/v1/audit';
const TOKEN_KEY = 'auth_token';
const USER_KEY = 'auth_user';

/** How many recent activity events to show. */
const ACTIVITY_LIMIT = 10;

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
  logout() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    window.location.replace('login.html');
  },
};

function $(id) {
  return document.getElementById(id);
}

function escapeHtml(value) {
  return String(value == null ? '' : value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/** Fetch wrapper: attaches the bearer token, throws on non-2xx, redirects on 401. */
async function request(url, options = {}) {
  const opts = { ...options };
  const token = auth.token();
  if (token) {
    opts.headers = { ...(opts.headers || {}), Authorization: `Bearer ${token}` };
  }
  const response = await fetch(url, opts);
  if (response.status === 401) {
    auth.logout();
    throw new Error('Session expired');
  }
  if (!response.ok) {
    throw new Error(`Request failed (${response.status})`);
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

function formatTimestamp(iso) {
  try {
    return new Date(iso).toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
  } catch (_) {
    return iso || '';
  }
}

/** Compact relative label: "just now", "5m", "3h", "2d", then a short date. */
function formatRelative(iso) {
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return '';
  const secs = Math.max(0, Math.floor((Date.now() - then) / 1000));
  if (secs < 45) return 'just now';
  const mins = Math.floor(secs / 60);
  if (mins < 60) return `${mins}m`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours}h`;
  const days = Math.floor(hours / 24);
  if (days < 7) return `${days}d`;
  return new Date(then).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

function showError(message) {
  const e = $('dashboard-error');
  e.textContent = message;
  e.hidden = false;
}

/** Fetch all users and render the totals (total / admins / enabled / disabled). */
async function loadStats() {
  const users = await request(USERS_API);
  const list = Array.isArray(users) ? users : [];
  const admins = list.filter((u) => u.role === 'ADMIN').length;
  const enabled = list.filter((u) => u.enabled).length;
  $('stat-total').textContent = String(list.length);
  $('stat-admins').textContent = String(admins);
  $('stat-enabled').textContent = String(enabled);
  $('stat-disabled').textContent = String(list.length - enabled);
  $('user-stats').hidden = false;
}

/** Fetch the most recent audit events (newest first) and render them. */
async function loadActivity() {
  const loading = $('activity-loading');
  try {
    const page = await request(`${AUDIT_API}?page=0&size=${ACTIVITY_LIMIT}&sort=timestamp,desc`);
    const events = (page && Array.isArray(page.content)) ? page.content : [];
    loading.hidden = true;
    if (events.length === 0) {
      $('activity-empty').hidden = false;
      return;
    }
    const list = $('activity-list');
    list.innerHTML = events.map((e) => `
      <li class="user-activity__item">
        <span class="audit-action">${escapeHtml(e.action)}</span>
        <span class="user-activity__summary">${escapeHtml(e.summary || '')}</span>
        <span class="user-activity__meta">${escapeHtml(e.actor)} ·
          <time datetime="${escapeHtml(e.timestamp)}" title="${escapeHtml(formatTimestamp(e.timestamp))}">${escapeHtml(formatRelative(e.timestamp))}</time>
        </span>
      </li>`).join('');
    list.hidden = false;
  } catch (err) {
    loading.hidden = true;
    const e = $('activity-error');
    e.textContent = 'Could not load recent activity.';
    e.hidden = false;
  }
}

function init() {
  const u = auth.user();
  if (u && u.username) {
    const cu = $('current-user');
    cu.textContent = u.username;
    cu.hidden = false;
  }
  $('btn-logout').addEventListener('click', () => auth.logout());
  loadStats().catch(() => showError('Could not load user statistics.'));
  loadActivity();
}

document.addEventListener('DOMContentLoaded', init);
