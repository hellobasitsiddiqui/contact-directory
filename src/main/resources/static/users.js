/* ------------------------------------------------------------------ *
 * Admin user-management page.
 *
 * Self-contained: shares the localStorage auth keys with app.js / login.js but
 * has its own minimal API layer. Every call here hits the admin-only
 * /api/v1/users endpoints; a non-admin is bounced by the guard in users.html.
 * ------------------------------------------------------------------ */

const USERS_API = '/api/v1/users';
const AUDIT_API = '/api/v1/audit';
const TOKEN_KEY = 'auth_token';
const USER_KEY = 'auth_user';

/** How many recent activity events to show in the user detail modal. */
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

/** Page-size options for the selector; the default is the first marked current. */
const PAGE_SIZES = [10, 25, 50, 100];
const DEFAULT_PAGE_SIZE = 25;

/** All users fetched from the API, plus the current client-side filters/sort. */
const state = {
  users: [],
  username: '',
  role: '',
  status: '',
  sortKey: '',
  sortDir: 'asc',
  // Client-side pagination over the filtered + sorted list. `page` is 0-based.
  page: 0,
  pageSize: DEFAULT_PAGE_SIZE,
};

/** Ids of users currently ticked via the row checkboxes (Set of String ids). */
const selectedIds = new Set();

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
  if (opts.body !== undefined && opts.body !== null && typeof opts.body !== 'string') {
    opts.headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
    opts.body = JSON.stringify(opts.body);
  }
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
function toast(message, variant = 'success') {
  const t = $('toast');
  t.textContent = message;
  t.hidden = false;
  t.classList.remove('toast--success', 'toast--error');
  t.classList.add(variant === 'error' ? 'toast--error' : 'toast--success');
  t.classList.add('toast--show');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.classList.remove('toast--show'); }, 3000);
}

function showError(message) {
  const e = $('users-error');
  e.textContent = message;
  e.hidden = false;
}

/**
 * Copy text to the clipboard, resolving to true on success. Prefers the async
 * Clipboard API; falls back to a hidden textarea + execCommand('copy') where
 * navigator.clipboard is unavailable (older browsers / non-secure contexts).
 */
function copyToClipboard(text) {
  const value = String(text == null ? '' : text);
  if (navigator.clipboard && window.isSecureContext) {
    return navigator.clipboard.writeText(value).then(() => true).catch(() => false);
  }
  return new Promise((resolve) => {
    try {
      const ta = document.createElement('textarea');
      ta.value = value;
      ta.setAttribute('readonly', '');
      ta.style.position = 'fixed';
      ta.style.top = '-1000px';
      ta.style.opacity = '0';
      document.body.appendChild(ta);
      ta.select();
      const ok = document.execCommand('copy');
      document.body.removeChild(ta);
      resolve(ok);
    } catch (_) {
      resolve(false);
    }
  });
}

/**
 * Copy the button's data-copy value and give brief feedback: a "Copied" state on
 * the button plus a toast. Shows an error toast when the clipboard is blocked.
 */
function handleCopy(btn) {
  const value = btn.dataset.copy || '';
  copyToClipboard(value).then((ok) => {
    if (!ok) {
      toast('Could not copy to clipboard', 'error');
      return;
    }
    btn.classList.add('btn-copy--done');
    btn.textContent = '✓';
    btn.setAttribute('aria-label', 'Copied');
    btn.title = 'Copied';
    if (btn._copyTimer) clearTimeout(btn._copyTimer);
    btn._copyTimer = setTimeout(() => {
      btn.classList.remove('btn-copy--done');
      btn.textContent = '⧉';
      const original = btn.dataset.copyLabel || 'Copy';
      btn.setAttribute('aria-label', original);
      btn.title = original;
    }, 1500);
    toast('Copied to clipboard');
  });
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

function rowHtml(user, currentUsername) {
  const isSelf = user.username === currentUsername;
  const selfTag = isSelf ? ' <span class="users-self">(you)</span>' : '';
  const statusBadge = user.enabled
    ? '<span class="users-badge users-badge--on">Enabled</span>'
    : '<span class="users-badge users-badge--off">Disabled</span>';

  const roleSelect = `
    <select class="users-role" data-id="${user.id}" ${isSelf ? 'disabled' : ''}
            aria-label="Role for ${escapeHtml(user.username)}">
      <option value="USER" ${user.role === 'USER' ? 'selected' : ''}>USER</option>
      <option value="ADMIN" ${user.role === 'ADMIN' ? 'selected' : ''}>ADMIN</option>
    </select>`;

  const toggleBtn = `
    <button type="button" class="btn btn--sm btn--secondary" data-action="toggle"
            data-id="${user.id}" data-enabled="${user.enabled}" ${isSelf ? 'disabled' : ''}>
      ${user.enabled ? 'Disable' : 'Enable'}
    </button>`;
  const resetBtn = `
    <button type="button" class="btn btn--sm btn--secondary" data-action="reset"
            data-id="${user.id}" data-username="${escapeHtml(user.username)}">
      Reset password
    </button>`;
  const deleteBtn = `
    <button type="button" class="btn btn--sm btn--danger" data-action="delete"
            data-id="${user.id}" data-username="${escapeHtml(user.username)}" ${isSelf ? 'disabled' : ''}>
      Delete
    </button>`;

  const copyBtn = `
    <button type="button" class="btn-copy" data-action="copy"
            data-copy="${escapeHtml(user.username)}"
            data-copy-label="Copy username"
            aria-label="Copy username" title="Copy username">⧉</button>`;

  // Own row is excluded from bulk selection: it renders an empty cell, no checkbox.
  const selectCell = isSelf
    ? '<td class="cell-select"></td>'
    : `<td class="cell-select">
        <input type="checkbox" class="row-select" data-id="${user.id}"
               ${selectedIds.has(String(user.id)) ? 'checked' : ''}
               aria-label="Select ${escapeHtml(user.username)}" />
      </td>`;

  return `
    <tr data-id="${user.id}">
      ${selectCell}
      <td class="users-username">
        <span class="cell-copyable">${escapeHtml(user.username)}${selfTag}${copyBtn}</span>
      </td>
      <td>${roleSelect}</td>
      <td>${statusBadge}</td>
      <td class="users-muted">${timeCell(user.createdAt)}</td>
      <td class="users-table__actions">${toggleBtn} ${resetBtn} ${deleteBtn}</td>
    </tr>`;
}

/** Apply the current filters to the fetched list (all client-side, no API call). */
function filteredUsers() {
  const term = state.username.trim().toLowerCase();
  const users = Array.isArray(state.users) ? state.users : [];
  return users.filter((u) => {
    if (term && !String(u.username || '').toLowerCase().includes(term)) return false;
    if (state.role && u.role !== state.role) return false;
    if (state.status === 'enabled' && !u.enabled) return false;
    if (state.status === 'disabled' && u.enabled) return false;
    return true;
  });
}

/** True when any client-side filter is currently narrowing the list. */
function hasActiveFilter() {
  return !!(state.username.trim() || state.role || state.status);
}

/**
 * Ids of all filtered (non-self) rows eligible for bulk selection — across every
 * page. Selections persist as you page, so this is the set a selection may
 * legitimately span; pruneSelection() uses it to drop only rows the filters hid.
 */
function selectableIds() {
  const current = auth.user();
  const currentUsername = current ? current.username : '';
  return filteredUsers()
    .filter((u) => u.username !== currentUsername)
    .map((u) => String(u.id));
}

/**
 * Ids of the selectable rows actually rendered on the current page. Drives the
 * select-all checkbox (whose label is "Select all users on this page") and its
 * toggle, so "select all" means this page, while selections still carry across
 * pages.
 */
function visibleSelectableIds() {
  const current = auth.user();
  const currentUsername = current ? current.username : '';
  return pagedUsers(sortedUsers(filteredUsers()))
    .filter((u) => u.username !== currentUsername)
    .map((u) => String(u.id));
}

/** Drop any selected ids that are no longer in the filtered set (across pages). */
function pruneSelection() {
  const visible = new Set(selectableIds());
  for (const id of [...selectedIds]) {
    if (!visible.has(id)) selectedIds.delete(id);
  }
}

/**
 * Show/hide the bulk bar and sync its count + the select-all checkbox state to
 * the current selection (scoped to the rows currently shown).
 */
function refreshBulkBar() {
  const n = selectedIds.size;
  const bar = $('bulk-bar');
  if (bar) bar.hidden = n === 0;
  const count = $('bulk-count');
  if (count) count.textContent = `${n} selected`;

  const selectAll = $('select-all');
  if (selectAll) {
    // Scoped to the current page: "select all" ticks what's on screen.
    const ids = visibleSelectableIds();
    const selectedShown = ids.filter((id) => selectedIds.has(id)).length;
    selectAll.checked = ids.length > 0 && selectedShown === ids.length;
    selectAll.indeterminate = selectedShown > 0 && selectedShown < ids.length;
  }
}

/** Toggle a single row's selection. */
function toggleRowSelect(id, checked) {
  const key = String(id);
  if (checked) selectedIds.add(key);
  else selectedIds.delete(key);
  refreshBulkBar();
}

/** Select / deselect every selectable row on the current page. */
function toggleSelectAll(checked) {
  for (const id of visibleSelectableIds()) {
    if (checked) selectedIds.add(id);
    else selectedIds.delete(id);
  }
  renderUsers();
}

/** Clear the whole selection and hide the bulk bar. */
function clearSelection() {
  selectedIds.clear();
  renderUsers();
}

/** Comparable value for a user under the given sort key. */
function sortValue(user, key) {
  switch (key) {
    case 'username':
      return String(user.username || '').toLowerCase();
    case 'role':
      return String(user.role || '').toLowerCase();
    case 'status':
      // Enabled before Disabled when ascending.
      return user.enabled ? 0 : 1;
    case 'created': {
      const t = user.createdAt ? new Date(user.createdAt).getTime() : 0;
      return Number.isNaN(t) ? 0 : t;
    }
    default:
      return 0;
  }
}

/** Sort a copy of the list by the active column; stable, no-op when no sort set. */
function sortedUsers(users) {
  if (!state.sortKey) return users;
  const dir = state.sortDir === 'desc' ? -1 : 1;
  return users
    .map((u, i) => [u, i])
    .sort((a, b) => {
      const av = sortValue(a[0], state.sortKey);
      const bv = sortValue(b[0], state.sortKey);
      if (av < bv) return -1 * dir;
      if (av > bv) return 1 * dir;
      return a[1] - b[1]; // keep original order for ties
    })
    .map((pair) => pair[0]);
}

/** Total number of pages for `count` rows at the current page size (min 1). */
function pageCount(count) {
  return Math.max(1, Math.ceil(count / state.pageSize));
}

/**
 * Clamp state.page into [0, last page] for the given match count — e.g. after a
 * filter shrinks the list past the current page. Mutates state.page in place.
 */
function clampPage(count) {
  const last = pageCount(count) - 1;
  state.page = Math.min(Math.max(state.page, 0), last);
}

/** The slice of `users` that falls on the current page. */
function pagedUsers(users) {
  const start = state.page * state.pageSize;
  return users.slice(start, start + state.pageSize);
}

/**
 * Update the pagination bar: "Page X of Y / N users", Prev/Next disabled at the
 * ends, and hide the whole bar when there is nothing to page through. With a
 * single page the Prev/Next nav is hidden (it would only ever be disabled),
 * while the per-page selector and page-info stay useful.
 */
function renderPagination(matchCount) {
  const bar = $('users-pagination');
  if (!bar) return;
  if (matchCount === 0) {
    bar.hidden = true;
    return;
  }
  bar.hidden = false;

  const pages = pageCount(matchCount);
  const noun = matchCount === 1 ? 'user' : 'users';
  $('page-info').textContent = `Page ${state.page + 1} of ${pages} / ${matchCount} ${noun}`;

  const nav = $('users-pagination').querySelector('.pagination__nav');
  if (nav) nav.hidden = pages === 1;
  const prev = $('btn-prev-page');
  const next = $('btn-next-page');
  if (prev) prev.disabled = state.page <= 0;
  if (next) next.disabled = state.page >= pages - 1;
}

/** Reflect the active sort onto the header buttons (indicator + aria-sort). */
function renderSortHeaders() {
  document.querySelectorAll('.users-sort').forEach((btn) => {
    const th = btn.closest('th');
    const active = btn.dataset.sort === state.sortKey;
    const indicator = btn.querySelector('.users-sort__indicator');
    if (active) {
      btn.classList.add('users-sort--active');
      if (indicator) indicator.textContent = state.sortDir === 'desc' ? '▼' : '▲';
      if (th) th.setAttribute('aria-sort', state.sortDir === 'desc' ? 'descending' : 'ascending');
    } else {
      btn.classList.remove('users-sort--active');
      if (indicator) indicator.textContent = '';
      if (th) th.setAttribute('aria-sort', 'none');
    }
  });
}

/**
 * Update the summary stats bar. Counts always reflect the full fetched list
 * (state.users), not the current filter, so they read as totals for the account.
 */
function renderStats() {
  const users = Array.isArray(state.users) ? state.users : [];
  const admins = users.filter((u) => u.role === 'ADMIN').length;
  const enabled = users.filter((u) => u.enabled).length;
  $('stat-total').textContent = users.length;
  $('stat-admins').textContent = admins;
  $('stat-enabled').textContent = enabled;
  $('stat-disabled').textContent = users.length - enabled;
  $('users-stats').hidden = users.length === 0;
}

/** Render the table from the fetched list + current filters; shows an empty state. */
function renderUsers() {
  const current = auth.user();
  const currentUsername = current ? current.username : '';
  const matches = sortedUsers(filteredUsers());
  // Keep the page in range first (e.g. a filter shrank the list), then slice it.
  clampPage(matches.length);
  const visible = pagedUsers(matches);
  renderStats();
  renderSortHeaders();
  $('users-body').innerHTML = visible.map((u) => rowHtml(u, currentUsername)).join('');
  $('users-table').hidden = matches.length === 0;
  const empty = $('users-empty');
  empty.hidden = matches.length !== 0;
  empty.textContent = hasActiveFilter()
    ? 'No users match your filters.'
    : 'No users yet.';
  renderPagination(matches.length);
  // Selection may reference rows that filtering has hidden; drop them, then
  // resync the bulk bar + select-all checkbox to what is actually shown.
  pruneSelection();
  refreshBulkBar();
}

async function loadUsers() {
  try {
    state.users = await request(USERS_API, { method: 'GET' });
    $('users-loading').hidden = true;
    renderUsers();
  } catch (err) {
    $('users-loading').hidden = true;
    showError('Could not load users: ' + err.message);
  }
}

async function changeRole(id, role) {
  try {
    await request(`${USERS_API}/${id}/role`, { method: 'PATCH', body: { role } });
    toast(`Role updated to ${role}`);
  } catch (err) {
    showError(err.message);
    loadUsers(); // revert the select to the true value
  }
}

async function toggleEnabled(id, currentlyEnabled) {
  try {
    await request(`${USERS_API}/${id}/enabled`, {
      method: 'PATCH', body: { enabled: !currentlyEnabled },
    });
    toast(currentlyEnabled ? 'Account disabled' : 'Account enabled');
    loadUsers();
  } catch (err) {
    showError(err.message);
  }
}

async function resetPassword(id, username) {
  const pw = window.prompt(`New password for "${username}" (min 6 characters):`);
  if (pw === null) return;
  if (pw.length < 6) {
    showError('Password must be at least 6 characters.');
    return;
  }
  try {
    await request(`${USERS_API}/${id}/reset-password`, { method: 'POST', body: { password: pw } });
    toast(`Password reset for ${username}`);
  } catch (err) {
    showError(err.message);
  }
}

async function deleteUser(id, username) {
  const ok = await confirmDialog({
    title: 'Delete user',
    message: `Delete user "${username}"? This cannot be undone.`,
    confirmLabel: 'Delete',
    danger: true,
  });
  if (!ok) return;
  try {
    await request(`${USERS_API}/${id}`, { method: 'DELETE' });
    toast(`Deleted ${username}`);
    loadUsers();
  } catch (err) {
    showError(err.message);
  }
}

/* ------------------------------------------------------------------ *
 * User detail modal (details + recent activity)
 *
 * A row click opens a read-only card with the user's details and their most
 * recent audit events (GET /api/v1/audit?actor=<username>). Reuses the shared
 * .modal / .detail__* styling; Esc and a backdrop click both close it. Clicks
 * on the row's interactive controls (buttons, selects, checkboxes) are handled
 * elsewhere and never reach here, so the modal won't open on those.
 * ------------------------------------------------------------------ */

/** The element focused before the modal opened, restored on close. */
let modalTrigger = null;

/**
 * The username whose activity is currently being requested. A late/slow
 * response only renders if it still matches — so closing the modal or opening
 * another row's detail before the fetch resolves won't render stale activity.
 */
let requestedActivityUser = null;

function openUserModal() {
  const dialog = $('user-modal');
  if (typeof dialog.showModal === 'function') dialog.showModal();
  else dialog.setAttribute('open', '');
}

function closeUserModal() {
  // Drop any in-flight activity request so a late response can't render into
  // the closed (or subsequently reopened) modal.
  requestedActivityUser = null;
  const dialog = $('user-modal');
  if (typeof dialog.close === 'function') dialog.close();
  else dialog.removeAttribute('open');
}

/**
 * Return focus to whatever opened the modal. Wired to the dialog's 'close'
 * event so every close path (button, backdrop, Esc) restores focus uniformly,
 * matching the contacts page (app.js).
 */
function restoreTriggerFocus() {
  const target = modalTrigger && document.body.contains(modalTrigger) ? modalTrigger : null;
  modalTrigger = null;
  if (target && typeof target.focus === 'function') target.focus();
}

/** A single recent-activity <li>: action token, summary, relative time. */
function activityItemHtml(event) {
  const summary = event.summary ? `<span class="user-activity__summary">${escapeHtml(event.summary)}</span>` : '';
  return `
    <li class="user-activity__item">
      <span class="audit-action">${escapeHtml(event.action)}</span>
      ${summary}
      <span class="user-activity__time">${timeCell(event.timestamp)}</span>
    </li>`;
}

/**
 * Fetch and render the actor's most recent audit events into the modal,
 * toggling the loading / empty / error states. Best-effort: a failed fetch
 * shows an inline message but leaves the details above intact.
 */
async function loadUserActivity(username) {
  const loading = $('user-activity-loading');
  const list = $('user-activity-list');
  const empty = $('user-activity-empty');
  const error = $('user-activity-error');
  loading.hidden = false;
  list.hidden = true;
  empty.hidden = true;
  error.hidden = true;
  list.innerHTML = '';

  requestedActivityUser = username;
  try {
    const params = new URLSearchParams({
      actor: username,
      page: '0',
      size: String(ACTIVITY_LIMIT),
    });
    const page = await request(`${AUDIT_API}?${params.toString()}`, { method: 'GET' });
    // Bail if the modal moved on (closed, or another row opened) while we waited.
    if (requestedActivityUser !== username) return;
    const events = (page && page.content) || [];
    loading.hidden = true;
    if (events.length === 0) {
      empty.hidden = false;
      return;
    }
    list.innerHTML = events.map((e) => activityItemHtml(e)).join('');
    list.hidden = false;
  } catch (err) {
    if (requestedActivityUser !== username) return;
    loading.hidden = true;
    error.textContent = `Could not load activity: ${err.message}`;
    error.hidden = false;
  }
}

/** Populate the modal's detail rows for the given user (XSS-safe). */
function renderUserDetail(user) {
  $('user-modal-title').textContent = user.username || 'User';
  $('user-modal-username').textContent = user.username || '—';
  $('user-modal-role').textContent = user.role || '—';
  $('user-modal-status').innerHTML = user.enabled
    ? '<span class="users-badge users-badge--on">Enabled</span>'
    : '<span class="users-badge users-badge--off">Disabled</span>';
  // Created: relative label with the absolute timestamp on hover (via timeCell).
  $('user-modal-created').innerHTML = timeCell(user.createdAt);
}

/** Open the detail modal for a user id from the fetched list. */
function openUserDetail(id) {
  const user = (state.users || []).find((u) => String(u.id) === String(id));
  if (!user) return;
  modalTrigger = document.activeElement;
  renderUserDetail(user);
  openUserModal();
  loadUserActivity(user.username);
}

/* ------------------------------------------------------------------ *
 * Bulk actions
 *
 * No bulk API exists, so each action loops the existing per-user endpoint over
 * the current selection (sequentially, to keep server load predictable), tallies
 * successes/failures, then clears + reloads and toasts a summary.
 * ------------------------------------------------------------------ */

/**
 * Run `op(id, user)` for every selected user, returning {ok, failed, skipped}.
 * `op` may skip a user by returning false (counted as skipped, neither ok nor
 * failed) — used to avoid no-op calls, e.g. enabling an already-enabled account.
 */
async function runBulk(op) {
  const ids = [...selectedIds];
  const byId = new Map((state.users || []).map((u) => [String(u.id), u]));
  let ok = 0;
  let failed = 0;
  let skipped = 0;
  for (const id of ids) {
    const user = byId.get(String(id));
    try {
      const did = await op(id, user);
      if (did === false) skipped += 1;
      else ok += 1;
    } catch (_) {
      failed += 1;
    }
  }
  return { ok, failed, skipped };
}

/**
 * Toast a summary line for a finished bulk run. `verb` describes the action as
 * {past, fail}, e.g. {past:'enabled', fail:'enable'}, so success and failure
 * copy each read naturally regardless of tense (e.g. role's 'set to ADMIN').
 */
function bulkSummary(result, verb) {
  const { ok, failed, skipped = 0 } = result;
  const noun = ok === 1 ? 'user' : 'users';
  if (ok === 0 && failed === 0 && skipped > 0) {
    toast('No changes needed.');
  } else if (failed === 0) {
    toast(`${ok} ${noun} ${verb.past}.`);
  } else if (ok === 0) {
    toast(`Could not ${verb.fail} ${failed} ${failed === 1 ? 'user' : 'users'}.`, 'error');
  } else {
    toast(`${ok} ${noun} ${verb.past}; ${failed} failed.`, 'error');
  }
}

/** Apply a bulk run, then clear the selection, reload, and report the outcome. */
async function applyBulk(op, verb) {
  if (selectedIds.size === 0) return;
  $('users-error').hidden = true;
  const result = await runBulk(op);
  selectedIds.clear();
  await loadUsers();
  bulkSummary(result, verb);
}

function onBulkEnable() {
  applyBulk((id, user) => {
    if (user && user.enabled) return false; // already enabled
    return request(`${USERS_API}/${id}/enabled`, { method: 'PATCH', body: { enabled: true } });
  }, { past: 'enabled', fail: 'enable' });
}

function onBulkDisable() {
  applyBulk((id, user) => {
    if (user && !user.enabled) return false; // already disabled
    return request(`${USERS_API}/${id}/enabled`, { method: 'PATCH', body: { enabled: false } });
  }, { past: 'disabled', fail: 'disable' });
}

function onBulkRole(role) {
  applyBulk((id, user) => {
    if (user && user.role === role) return false; // already that role
    return request(`${USERS_API}/${id}/role`, { method: 'PATCH', body: { role } });
  }, { past: `set to ${role}`, fail: 'update' });
}

async function onBulkDelete() {
  if (selectedIds.size === 0) return;
  const n = selectedIds.size;
  // A selection can span pages while only the current page's rows are on screen.
  // Spell out how many selected users aren't visible so a delete here can't
  // surprise the operator with rows they can't see.
  const onPage = new Set(visibleSelectableIds());
  const offPage = [...selectedIds].filter((id) => !onPage.has(id)).length;
  const offPageNote = offPage > 0 ? ` (${offPage} on other pages)` : '';
  const ok = await confirmDialog({
    title: 'Delete users',
    message: `Delete ${n} selected ${n === 1 ? 'user' : 'users'}${offPageNote}? This cannot be undone.`,
    confirmLabel: 'Delete',
    danger: true,
  });
  if (!ok) return;
  await applyBulk((id) => request(`${USERS_API}/${id}`, { method: 'DELETE' }), { past: 'deleted', fail: 'delete' });
}

function onTableClick(event) {
  const btn = event.target.closest('button[data-action]');
  if (btn) {
    $('users-error').hidden = true;
    if (btn.dataset.action === 'copy') {
      handleCopy(btn);
      return;
    }
    const id = btn.dataset.id;
    if (btn.dataset.action === 'toggle') {
      toggleEnabled(id, btn.dataset.enabled === 'true');
    } else if (btn.dataset.action === 'reset') {
      resetPassword(id, btn.dataset.username);
    } else if (btn.dataset.action === 'delete') {
      deleteUser(id, btn.dataset.username);
    }
    return;
  }

  // A click anywhere else on a row opens the detail modal — unless it landed on
  // an interactive control (the role <select> or the row checkbox), which carry
  // their own behaviour and must not also open the modal.
  if (event.target.closest('select, input, label')) return;
  const row = event.target.closest('tr[data-id]');
  if (row && $('users-body').contains(row)) {
    openUserDetail(row.dataset.id);
  }
}

function onTableChange(event) {
  const checkbox = event.target.closest('input.row-select');
  if (checkbox) {
    toggleRowSelect(checkbox.dataset.id, checkbox.checked);
    return;
  }
  const select = event.target.closest('select.users-role');
  if (!select) return;
  $('users-error').hidden = true;
  changeRole(select.dataset.id, select.value);
}

/** Debounce so typing in the username box doesn't re-render on every keystroke. */
let usernameTimer = null;
function onUsernameInput(event) {
  if (usernameTimer) clearTimeout(usernameTimer);
  const value = event.target.value;
  usernameTimer = setTimeout(() => {
    state.username = value;
    state.page = 0; // a new filter starts at the first page
    renderUsers();
  }, 200);
}

function onRoleFilterChange(event) {
  state.role = event.target.value;
  state.page = 0;
  renderUsers();
}

function onStatusFilterChange(event) {
  state.status = event.target.value;
  state.page = 0;
  renderUsers();
}

function onSortClick(event) {
  const btn = event.target.closest('.users-sort');
  if (!btn) return;
  const key = btn.dataset.sort;
  if (state.sortKey === key) {
    // Same column: toggle direction.
    state.sortDir = state.sortDir === 'asc' ? 'desc' : 'asc';
  } else {
    // New column: start ascending.
    state.sortKey = key;
    state.sortDir = 'asc';
  }
  state.page = 0; // re-sorting returns to the first page
  renderUsers();
}

/** Page-size change: keep the first visible row in view by recomputing the page. */
function onPageSizeChange(event) {
  const size = Number(event.target.value);
  if (!PAGE_SIZES.includes(size)) return;
  // Anchor on the first row currently shown so the new page size keeps it visible.
  const firstRow = state.page * state.pageSize;
  state.pageSize = size;
  state.page = Math.floor(firstRow / size);
  renderUsers();
}

function onPrevPage() {
  if (state.page <= 0) return;
  state.page -= 1;
  renderUsers();
}

function onNextPage() {
  // Only the count matters here; sorting wouldn't change the length, so skip it.
  const matchCount = filteredUsers().length;
  if (state.page >= pageCount(matchCount) - 1) return;
  state.page += 1;
  renderUsers();
}

function onClearFilters() {
  if (usernameTimer) clearTimeout(usernameTimer);
  state.username = '';
  state.role = '';
  state.status = '';
  // Clear also resets sort so the table returns to its default state
  // (renderSortHeaders, called via renderUsers, clears the column indicators).
  state.sortKey = '';
  state.sortDir = 'asc';
  state.page = 0; // back to the first page
  $('filter-username').value = '';
  $('filter-role').value = '';
  $('filter-status').value = '';
  renderUsers();
}

function init() {
  const user = auth.user();
  if (user && user.username) {
    const pill = $('current-user');
    pill.textContent = user.username;
    pill.hidden = false;
  }
  $('btn-logout').addEventListener('click', () => auth.logout());
  $('users-body').addEventListener('click', onTableClick);
  $('users-body').addEventListener('change', onTableChange);
  $('users-table').tHead.addEventListener('click', onSortClick);
  $('filter-username').addEventListener('input', onUsernameInput);
  $('filter-role').addEventListener('change', onRoleFilterChange);
  $('filter-status').addEventListener('change', onStatusFilterChange);
  $('btn-clear-filters').addEventListener('click', onClearFilters);
  // Sync the page-size control to state, then wire pagination. Like the other
  // controls below, page-size is always present in users.html, so it's bound
  // unguarded.
  $('page-size').value = String(state.pageSize);
  $('page-size').addEventListener('change', onPageSizeChange);
  $('btn-prev-page').addEventListener('click', onPrevPage);
  $('btn-next-page').addEventListener('click', onNextPage);
  $('select-all').addEventListener('change', (e) => toggleSelectAll(e.target.checked));
  $('btn-bulk-enable').addEventListener('click', onBulkEnable);
  $('btn-bulk-disable').addEventListener('click', onBulkDisable);
  $('btn-bulk-admin').addEventListener('click', () => onBulkRole('ADMIN'));
  $('btn-bulk-user').addEventListener('click', () => onBulkRole('USER'));
  $('btn-bulk-delete').addEventListener('click', onBulkDelete);
  $('btn-bulk-clear').addEventListener('click', clearSelection);

  // User detail modal: close via header ×, footer button, backdrop click, or Esc
  // (native <dialog> handles Esc itself). Focus restore lives on the 'close'
  // event so every close path funnels through one canonical handler.
  const userModal = $('user-modal');
  $('btn-user-modal-close').addEventListener('click', closeUserModal);
  $('btn-user-modal-done').addEventListener('click', closeUserModal);
  userModal.addEventListener('click', (event) => {
    if (event.target === userModal) closeUserModal();
  });
  userModal.addEventListener('close', restoreTriggerFocus);

  loadUsers();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true });
} else {
  init();
}
