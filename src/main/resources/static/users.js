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
function toast(message) {
  const t = $('toast');
  t.textContent = message;
  t.hidden = false;
  t.classList.add('toast--show');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { t.classList.remove('toast--show'); }, 3000);
}

function showError(message) {
  const e = $('users-error');
  e.textContent = message;
  e.hidden = false;
}

function formatDate(iso) {
  if (!iso) return '—';
  try {
    return new Date(iso).toLocaleDateString(undefined,
      { year: 'numeric', month: 'short', day: 'numeric' });
  } catch (_) {
    return iso;
  }
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

  return `
    <tr data-id="${user.id}">
      <td class="users-username">${escapeHtml(user.username)}${selfTag}</td>
      <td>${roleSelect}</td>
      <td>${statusBadge}</td>
      <td class="users-muted">${formatDate(user.createdAt)}</td>
      <td class="users-table__actions">${toggleBtn} ${resetBtn} ${deleteBtn}</td>
    </tr>`;
}

async function loadUsers() {
  const current = auth.user();
  const currentUsername = current ? current.username : '';
  try {
    const users = await request(USERS_API, { method: 'GET' });
    const body = $('users-body');
    body.innerHTML = users.map((u) => rowHtml(u, currentUsername)).join('');
    $('users-loading').hidden = true;
    $('users-table').hidden = false;
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
  if (!window.confirm(`Delete user "${username}"? This cannot be undone.`)) return;
  try {
    await request(`${USERS_API}/${id}`, { method: 'DELETE' });
    toast(`Deleted ${username}`);
    loadUsers();
  } catch (err) {
    showError(err.message);
  }
}

function onTableClick(event) {
  const btn = event.target.closest('button[data-action]');
  if (!btn) return;
  $('users-error').hidden = true;
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
  const select = event.target.closest('select.users-role');
  if (!select) return;
  $('users-error').hidden = true;
  changeRole(select.dataset.id, select.value);
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
  loadUsers();
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true });
} else {
  init();
}
