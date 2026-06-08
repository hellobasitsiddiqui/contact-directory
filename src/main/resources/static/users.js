/* ------------------------------------------------------------------ *
 * Admin user-management page.
 *
 * Self-contained: shares the localStorage auth keys with app.js / login.js but
 * has its own minimal API layer. Every call here hits the admin-only
 * /api/v1/users endpoints; a non-admin is bounced by the guard in users.html.
 * ------------------------------------------------------------------ */

const USERS_API = '/api/v1/users';
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
  logout() {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(USER_KEY);
    window.location.replace('login.html');
  },
};

/** All users fetched from the API, plus the current client-side filters/sort. */
const state = {
  users: [],
  username: '',
  role: '',
  status: '',
  sortKey: '',
  sortDir: 'asc',
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
 * Ids of the rows currently shown that are eligible for bulk selection, i.e. the
 * filtered list minus the signed-in admin's own row (which has no checkbox).
 */
function selectableIds() {
  const current = auth.user();
  const currentUsername = current ? current.username : '';
  return filteredUsers()
    .filter((u) => u.username !== currentUsername)
    .map((u) => String(u.id));
}

/** Drop any selected ids that are no longer visible under the current filters. */
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
    const ids = selectableIds();
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

/** Select / deselect every currently-shown (filtered, non-self) row. */
function toggleSelectAll(checked) {
  for (const id of selectableIds()) {
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
  renderStats();
  renderSortHeaders();
  $('users-body').innerHTML = matches.map((u) => rowHtml(u, currentUsername)).join('');
  $('users-table').hidden = matches.length === 0;
  const empty = $('users-empty');
  empty.hidden = matches.length !== 0;
  empty.textContent = hasActiveFilter()
    ? 'No users match your filters.'
    : 'No users yet.';
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
  const ok = await confirmDialog({
    title: 'Delete users',
    message: `Delete ${n} selected ${n === 1 ? 'user' : 'users'}? This cannot be undone.`,
    confirmLabel: 'Delete',
    danger: true,
  });
  if (!ok) return;
  await applyBulk((id) => request(`${USERS_API}/${id}`, { method: 'DELETE' }), { past: 'deleted', fail: 'delete' });
}

function onTableClick(event) {
  const btn = event.target.closest('button[data-action]');
  if (!btn) return;
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
    renderUsers();
  }, 200);
}

function onRoleFilterChange(event) {
  state.role = event.target.value;
  renderUsers();
}

function onStatusFilterChange(event) {
  state.status = event.target.value;
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
  $('select-all').addEventListener('change', (e) => toggleSelectAll(e.target.checked));
  $('btn-bulk-enable').addEventListener('click', onBulkEnable);
  $('btn-bulk-disable').addEventListener('click', onBulkDisable);
  $('btn-bulk-admin').addEventListener('click', () => onBulkRole('ADMIN'));
  $('btn-bulk-user').addEventListener('click', () => onBulkRole('USER'));
  $('btn-bulk-delete').addEventListener('click', onBulkDelete);
  $('btn-bulk-clear').addEventListener('click', clearSelection);
  loadUsers();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true });
} else {
  init();
}
