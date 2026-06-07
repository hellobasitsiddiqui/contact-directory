/* ------------------------------------------------------------------ *
 * Admin activity-log page.
 *
 * Self-contained: shares the localStorage auth keys with app.js / login.js but
 * has its own minimal API layer. Every call here hits the admin-only
 * /api/v1/audit endpoint; a non-admin is bounced by the guard in activity.html.
 * ------------------------------------------------------------------ */

const AUDIT_API = '/api/v1/audit';
const TOKEN_KEY = 'auth_token';
const USER_KEY = 'auth_user';
const PAGE_SIZE = 20;

/** The auditable actions, kept in sync with model/AuditAction.java. */
const AUDIT_ACTIONS = [
  'CONTACT_CREATE',
  'CONTACT_UPDATE',
  'CONTACT_DELETE',
  'CONTACT_RESTORE',
  'CONTACT_PURGE',
  'CONTACT_BULK_DELETE',
  'CONTACT_BULK_FAVORITE',
  'CONTACT_BULK_TAGS',
  'CONTACT_IMPORT',
  'CONTACT_PHOTO_UPDATE',
  'CONTACT_PHOTO_DELETE',
  'USER_ROLE_CHANGE',
  'USER_ENABLED_CHANGE',
  'USER_PASSWORD_RESET',
  'USER_DELETE',
  'AUTH_REGISTER',
  'AUTH_LOGIN',
  'AUTH_PASSWORD_CHANGE',
];

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

/** Current view state: page is zero-based, mirroring Spring's Pageable. */
const state = {
  page: 0,
  totalPages: 0,
  actor: '',
  action: '',
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
    let message = `Request failed (${response.status})`;
    try {
      const body = await response.json();
      if (body && body.message) message = body.message;
    } catch (_) { /* keep default */ }
    const err = new Error(message);
    err.status = response.status;
    throw err;
  }
  if (response.status === 204) return null;
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

let toastTimer = null;
function toast(message) {
  const t = $('toast');
  t.textContent = message;
  t.hidden = false;
  t.classList.add('toast--show');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.classList.remove('toast--show'); }, 3000);
}

function showError(message) {
  const e = $('audit-error');
  e.textContent = message;
  e.hidden = false;
}

/** Absolute, localised timestamp used for the hover title (and as a fallback). */
function formatTimestamp(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleString(undefined, {
      year: 'numeric', month: 'short', day: 'numeric',
      hour: '2-digit', minute: '2-digit',
    });
  } catch (_) {
    return iso;
  }
}

/**
 * Human-friendly relative label: "just now", "5m", "3h", "2d", then a short
 * date once older than a week. Future times degrade gracefully to "just now".
 */
function formatRelative(iso) {
  if (!iso) return '—';
  const then = new Date(iso).getTime();
  if (Number.isNaN(then)) return iso;
  const diffSeconds = Math.round((Date.now() - then) / 1000);
  if (diffSeconds < 45) return 'just now';
  const minutes = Math.round(diffSeconds / 60);
  if (minutes < 60) return `${minutes}m`;
  const hours = Math.round(diffSeconds / 3600);
  if (hours < 24) return `${hours}h`;
  const days = Math.round(diffSeconds / 86400);
  if (days < 7) return `${days}d`;
  try {
    return new Date(then).toLocaleDateString(undefined,
      { year: 'numeric', month: 'short', day: 'numeric' });
  } catch (_) {
    return iso;
  }
}

/**
 * A <time> cell showing the relative label, with the exact timestamp on hover
 * (title) and machine-readable in datetime. Falls back to a plain dash.
 */
function timeCell(iso) {
  if (!iso) return '—';
  const relative = escapeHtml(formatRelative(iso));
  const absolute = escapeHtml(formatTimestamp(iso));
  // aria-label mirrors the hover title so screen readers announce the full
  // date/time, while sighted users keep the compact relative label.
  return `<time datetime="${escapeHtml(iso)}" title="${absolute}" aria-label="${absolute}">${relative}</time>`;
}

/** Human-friendly target label, e.g. "CONTACT #42" or "—" when untargeted. */
function formatTarget(event) {
  if (!event.targetType) return '—';
  return event.targetId != null
    ? `${escapeHtml(event.targetType)} #${escapeHtml(event.targetId)}`
    : escapeHtml(event.targetType);
}

function rowHtml(event) {
  return `
    <tr>
      <td class="users-muted">${timeCell(event.timestamp)}</td>
      <td class="users-username">${escapeHtml(event.actor)}</td>
      <td><span class="audit-action">${escapeHtml(event.action)}</span></td>
      <td class="users-muted">${formatTarget(event)}</td>
      <td>${escapeHtml(event.summary)}</td>
    </tr>`;
}

/** Build the /api/v1/audit query string from the current state. */
function buildQuery() {
  const params = new URLSearchParams();
  params.set('page', String(state.page));
  params.set('size', String(PAGE_SIZE));
  if (state.actor) params.set('actor', state.actor);
  if (state.action) params.set('action', state.action);
  return `${AUDIT_API}?${params.toString()}`;
}

async function loadEvents() {
  $('audit-error').hidden = true;
  try {
    const page = await request(buildQuery(), { method: 'GET' });
    const events = (page && page.content) || [];
    state.totalPages = (page && page.totalPages) || 0;

    const body = $('audit-body');
    body.innerHTML = events.map((e) => rowHtml(e)).join('');
    $('audit-loading').hidden = true;
    $('audit-table').hidden = events.length === 0;
    $('audit-empty').hidden = events.length !== 0;

    updatePagination(page);
  } catch (err) {
    $('audit-loading').hidden = true;
    showError('Could not load activity: ' + err.message);
  }
}

function updatePagination(page) {
  const totalElements = (page && page.totalElements) || 0;
  const totalPages = state.totalPages;
  const human = totalPages === 0 ? 0 : state.page + 1;
  $('page-info').textContent =
    `Page ${human} of ${totalPages} · ${totalElements} event${totalElements === 1 ? '' : 's'}`;
  $('btn-prev').disabled = state.page <= 0;
  $('btn-next').disabled = totalPages === 0 || state.page >= totalPages - 1;
}

function populateActionFilter() {
  const select = $('filter-action');
  const options = AUDIT_ACTIONS
    .map((a) => `<option value="${a}">${a}</option>`)
    .join('');
  select.insertAdjacentHTML('beforeend', options);
}

/** Debounce so typing in the actor box doesn't fire a request per keystroke. */
let actorTimer = null;
function onActorInput(event) {
  if (actorTimer) clearTimeout(actorTimer);
  const value = event.target.value.trim();
  actorTimer = setTimeout(() => {
    state.actor = value;
    state.page = 0;
    loadEvents();
  }, 300);
}

function onActionChange(event) {
  state.action = event.target.value;
  state.page = 0;
  loadEvents();
}

function onClearFilters() {
  state.actor = '';
  state.action = '';
  state.page = 0;
  $('filter-actor').value = '';
  $('filter-action').value = '';
  loadEvents();
}

function onPrev() {
  if (state.page > 0) {
    state.page -= 1;
    loadEvents();
  }
}

function onNext() {
  if (state.page < state.totalPages - 1) {
    state.page += 1;
    loadEvents();
  }
}

function init() {
  const user = auth.user();
  if (user && user.username) {
    const pill = $('current-user');
    pill.textContent = user.username;
    pill.hidden = false;
  }
  populateActionFilter();
  $('btn-logout').addEventListener('click', () => auth.logout());
  $('filter-actor').addEventListener('input', onActorInput);
  $('filter-action').addEventListener('change', onActionChange);
  $('btn-clear-filters').addEventListener('click', onClearFilters);
  $('btn-prev').addEventListener('click', onPrev);
  $('btn-next').addEventListener('click', onNext);
  loadEvents();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true });
} else {
  init();
}
