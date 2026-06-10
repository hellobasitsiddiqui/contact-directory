/* ------------------------------------------------------------------ *
 * Admin activity-log page.
 *
 * Self-contained: shares the localStorage auth keys with app.js / login.js but
 * has its own minimal API layer. Every call here hits the admin-only
 * /api/v1/audit endpoint; a non-admin is bounced by the guard in activity.html.
 *
 * Filtering is entirely client-side: a single larger window of recent events
 * is fetched once, then narrowed by actor, action (multi-select) and date range
 * without further round-trips. No backend/spec change.
 * ------------------------------------------------------------------ */

const AUDIT_API = '/api/v1/audit';
const TOKEN_KEY = 'auth_token';
const USER_KEY = 'auth_user';

/** How many recent events to pull in one go and then filter in the browser. */
const FETCH_WINDOW = 500;

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
  'AUTH_LOGOUT',
  'AUTH_TOKEN_REUSE',
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
    // Revokes the refresh session server-side, clears storage, goes to login.
    AuthClient.logout();
  },
};

/**
 * View state. `events` is the full fetched window; the filter fields narrow it
 * client-side. `actions` is a Set of selected action names (empty = all).
 */
const state = {
  events: [],
  actor: '',
  actions: new Set(),
  from: '',
  to: '',
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

/** Fetch wrapper: bearer token + silent refresh via AuthClient (CD-028). */
async function request(url, options = {}) {
  const opts = { ...options };
  const response = await AuthClient.authFetch(url, opts);
  // Still 401 after the silent refresh: the session is truly dead.
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

/**
 * Narrow the fetched window by the active filters. The date range is inclusive
 * and interpreted in the viewer's local timezone (the date inputs are local
 * calendar days): `from` is the start of that day, `to` the end of it.
 */
function filteredEvents() {
  const actor = state.actor.toLowerCase();
  const fromMs = state.from ? new Date(`${state.from}T00:00:00`).getTime() : null;
  const toMs = state.to ? new Date(`${state.to}T23:59:59.999`).getTime() : null;

  return state.events.filter((e) => {
    if (actor && !String(e.actor || '').toLowerCase().includes(actor)) return false;
    if (state.actions.size && !state.actions.has(e.action)) return false;
    if (fromMs != null || toMs != null) {
      const t = new Date(e.timestamp).getTime();
      if (Number.isNaN(t)) return false;
      if (fromMs != null && t < fromMs) return false;
      if (toMs != null && t > toMs) return false;
    }
    return true;
  });
}

/** Render the table + "N of M" count from the fetched window and active filters. */
function render() {
  const total = state.events.length;
  const matches = filteredEvents();

  $('audit-body').innerHTML = matches.map((e) => rowHtml(e)).join('');
  $('audit-table').hidden = matches.length === 0;

  const count = $('audit-count');
  count.textContent = `Showing ${matches.length} of ${total} event${total === 1 ? '' : 's'}`;
  count.hidden = total === 0;

  const empty = $('audit-empty');
  empty.hidden = matches.length !== 0;
  empty.textContent = total === 0
    ? 'No activity recorded.'
    : 'No activity matches your filters.';
}

async function loadEvents() {
  $('audit-error').hidden = true;
  try {
    const url = `${AUDIT_API}?page=0&size=${FETCH_WINDOW}`
      + '&sort=timestamp,desc';
    const page = await request(url, { method: 'GET' });
    state.events = (page && page.content) || [];
    $('audit-loading').hidden = true;
    render();
  } catch (err) {
    $('audit-loading').hidden = true;
    showError('Could not load activity: ' + err.message);
  }
}

/* ---------- Action multi-select ----------------------------------------- */

/**
 * Build the checkbox list inside the action dropdown.
 * `a` is interpolated unescaped: values come only from the hardcoded
 * AUDIT_ACTIONS constant (trusted), never user/API data. If AUDIT_ACTIONS
 * ever becomes server-driven, wrap it in escapeHtml(a).
 */
function populateActionMenu() {
  const menu = $('action-menu');
  menu.innerHTML = AUDIT_ACTIONS.map((a) => `
    <label class="multiselect__option">
      <input type="checkbox" value="${a}" />
      <span class="audit-action">${a}</span>
    </label>`).join('');
}

/** Sync the toggle button label to the current selection. */
function updateActionToggleLabel() {
  const n = state.actions.size;
  $('action-toggle').textContent = n === 0
    ? 'All actions'
    : `${n} action${n === 1 ? '' : 's'}`;
}

function setActionMenuOpen(open) {
  $('action-menu').hidden = !open;
  $('action-toggle').setAttribute('aria-expanded', String(open));
}

function onActionToggleClick() {
  setActionMenuOpen($('action-menu').hidden);
}

function onActionMenuChange(event) {
  const box = event.target.closest('input[type="checkbox"]');
  if (!box) return;
  if (box.checked) state.actions.add(box.value);
  else state.actions.delete(box.value);
  updateActionToggleLabel();
  render();
}

/** Close the dropdown when clicking outside it. */
function onDocumentClick(event) {
  if (!event.target.closest('#action-multiselect')) setActionMenuOpen(false);
}

/** Escape closes the dropdown and returns focus to the toggle. */
function onMultiselectKeydown(event) {
  if (event.key !== 'Escape' || $('action-menu').hidden) return;
  setActionMenuOpen(false);
  $('action-toggle').focus();
}

/* ---------- Other filters ----------------------------------------------- */

/** Debounce so typing in the actor box doesn't re-render per keystroke. */
let actorTimer = null;
function onActorInput(event) {
  if (actorTimer) clearTimeout(actorTimer);
  const value = event.target.value.trim();
  actorTimer = setTimeout(() => {
    state.actor = value;
    render();
  }, 200);
}

function onFromChange(event) {
  state.from = event.target.value;
  render();
}

function onToChange(event) {
  state.to = event.target.value;
  render();
}

function onClearFilters() {
  state.actor = '';
  state.actions.clear();
  state.from = '';
  state.to = '';
  $('filter-actor').value = '';
  $('filter-from').value = '';
  $('filter-to').value = '';
  $('action-menu').querySelectorAll('input[type="checkbox"]')
    .forEach((box) => { box.checked = false; });
  updateActionToggleLabel();
  setActionMenuOpen(false);
  render();
}

function init() {
  const user = auth.user();
  if (user && user.username) {
    const pill = $('current-user');
    pill.textContent = user.username;
    pill.hidden = false;
  }
  populateActionMenu();
  updateActionToggleLabel();
  $('btn-logout').addEventListener('click', () => auth.logout());
  $('filter-actor').addEventListener('input', onActorInput);
  $('action-toggle').addEventListener('click', onActionToggleClick);
  $('action-menu').addEventListener('change', onActionMenuChange);
  $('filter-from').addEventListener('change', onFromChange);
  $('filter-to').addEventListener('change', onToChange);
  $('btn-clear-filters').addEventListener('click', onClearFilters);
  $('action-multiselect').addEventListener('keydown', onMultiselectKeydown);
  document.addEventListener('click', onDocumentClick);
  loadEvents();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true });
} else {
  init();
}
