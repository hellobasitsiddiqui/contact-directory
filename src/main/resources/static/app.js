'use strict';

/*
 * Contact Directory UI
 * --------------------
 * Vanilla ES2020, no frameworks, no build step. Served same-origin by Spring
 * Boot from /static, so all API calls use relative '/api/...' paths.
 *
 * Sections:
 *   1. Constants & state
 *   2. Element references
 *   3. API layer (fetch + error parsing)
 *   4. Rendering (XSS-safe)
 *   5. Loading the list (load)
 *   6. Toast
 *   7. Modal (create/edit) + inline field errors
 *   8. Delete confirmation
 *   9. Event wiring
 */

/* ------------------------------------------------------------------ *
 * 1. Constants & state
 * ------------------------------------------------------------------ */

const API_BASE = '/api/v1/contacts';
const SEARCH_DEBOUNCE_MS = 300;

/* ------------------------------------------------------------------ *
 * Auth helpers — JWT bearer token stored in localStorage by login.js.
 * ------------------------------------------------------------------ */
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
  /** Revoke the refresh session server-side, clear credentials, go to login. */
  logout() {
    AuthClient.logout();
  },
};

/** Client-side photo upload constraints — mirror the server limits. */
const ALLOWED_PHOTO_TYPES = [
  'image/png',
  'image/jpeg',
  'image/jpg',
  'image/gif',
  'image/webp',
];
const MAX_PHOTO_BYTES = 2 * 1024 * 1024; // 2MB

/** Fields that have a dedicated inline error span in the form. */
const ERROR_FIELDS = ['firstName', 'lastName', 'email', 'phone'];

/** All editable fields submitted in a create/update body. */
const FORM_FIELDS = ['firstName', 'lastName', 'email', 'phone', 'company'];

const state = {
  page: 0,
  size: 20,
  sort: 'lastName,asc',
  search: '',
  tag: '',
};

/** Preset tag suggestions offered in the modal (free-form tags also allowed). */
const PRESET_TAGS = ['Friend', 'Work', 'Client', 'Family'];

/**
 * Whether the Trash view is active. When true, load() fetches GET /trash and
 * rows show Restore / Delete-forever instead of Edit / Delete; the bulk bar and
 * New Contact button are hidden.
 */
let viewingTrash = false;

/**
 * Ids of contacts currently selected via the row checkboxes (Set of String ids).
 * Survives renderRows() (which rebuilds the tbody); reconciled after each load().
 */
const selectedIds = new Set();

/* ------------------------------------------------------------------ *
 * 2. Element references
 * ------------------------------------------------------------------ */

const $ = (id) => document.getElementById(id);

const el = {
  // Header
  btnNew: $('btn-new'),
  btnTheme: $('btn-theme'),
  btnLogout: $('btn-logout'),
  currentUser: $('current-user'),
  contactsScope: $('contacts-scope'),
  linkUsers: $('link-users'),
  linkActivity: $('link-activity'),
  // Toolbar
  searchInput: $('search-input'),
  btnClearSearch: $('btn-clear-search'),
  pageSize: $('page-size'),
  sortSelect: $('sort-select'),
  tagFilter: $('tag-filter'),
  btnImport: $('btn-import'),
  importFile: $('import-file'),
  btnTrash: $('btn-trash'),
  // Bulk action bar
  selectAll: $('select-all'),
  bulkBar: $('bulk-bar'),
  bulkCount: $('bulk-count'),
  btnBulkFavorite: $('btn-bulk-favorite'),
  btnBulkUnfavorite: $('btn-bulk-unfavorite'),
  btnBulkTag: $('btn-bulk-tag'),
  btnBulkDelete: $('btn-bulk-delete'),
  btnBulkClear: $('btn-bulk-clear'),
  // List
  contactsBody: $('contacts-body'),
  // States
  loading: $('loading'),
  emptyState: $('empty-state'),
  // Pagination
  btnPrev: $('btn-prev'),
  btnNext: $('btn-next'),
  pageInfo: $('page-info'),
  // Toast
  toast: $('toast'),
  // Contact modal
  contactModal: $('contact-modal'),
  contactForm: $('contact-form'),
  modalTitle: $('modal-title'),
  fieldId: $('field-id'),
  fieldFirstName: $('field-firstName'),
  fieldLastName: $('field-lastName'),
  fieldEmail: $('field-email'),
  fieldPhone: $('field-phone'),
  fieldCompany: $('field-company'),
  fieldTagInput: $('field-tag-input'),
  tagChips: $('tag-chips'),
  fieldNotes: $('field-notes'),
  fieldPhoto: $('field-photo'),
  photoPreview: $('photo-preview'),
  btnRemovePhoto: $('btn-remove-photo'),
  errFirstName: $('err-firstName'),
  errLastName: $('err-lastName'),
  errEmail: $('err-email'),
  errPhone: $('err-phone'),
  errPhoto: $('err-photo'),
  btnSave: $('btn-save'),
  btnCancel: $('btn-cancel'),
  // Delete confirmation uses the shared confirmDialog() — no static markup.
  // Detail modal
  detailModal: $('detail-modal'),
  detailAvatar: $('detail-avatar'),
  detailName: $('detail-name'),
  detailFav: $('detail-fav'),
  detailEmail: $('detail-email'),
  detailPhone: $('detail-phone'),
  detailCompany: $('detail-company'),
  detailTags: $('detail-tags'),
  detailNotesRow: $('detail-notes-row'),
  detailNotes: $('detail-notes'),
  detailCreated: $('detail-created'),
  detailUpdated: $('detail-updated'),
  btnDetailClose: $('btn-detail-close'),
  btnDetailEdit: $('btn-detail-edit'),
};

/** Map a logical field name to its input / error elements. */
function fieldInput(name) {
  return el['field' + name.charAt(0).toUpperCase() + name.slice(1)];
}
function fieldError(name) {
  return el['err' + name.charAt(0).toUpperCase() + name.slice(1)];
}

/* ------------------------------------------------------------------ *
 * 3. API layer
 * ------------------------------------------------------------------ */

/**
 * Parse a non-2xx response into an Error carrying .status and .body so callers
 * can branch on validation (400), conflict (409) and not-found (404).
 */
async function buildApiError(response) {
  let body = null;
  try {
    const text = await response.text();
    body = text ? JSON.parse(text) : null;
  } catch (_) {
    body = null;
  }
  const message =
    (body && body.message) || `Request failed (${response.status})`;
  const err = new Error(message);
  err.status = response.status;
  err.body = body;
  return err;
}

/** Shared fetch wrapper: sets JSON headers for bodies, throws ApiError on failure. */
async function request(url, options = {}) {
  const opts = { ...options };
  // FormData is sent as-is: the browser sets multipart Content-Type + boundary.
  // Other non-null bodies are treated as JSON.
  const isFormData =
    typeof FormData !== 'undefined' && opts.body instanceof FormData;
  if (opts.body !== undefined && opts.body !== null && !isFormData) {
    opts.headers = { 'Content-Type': 'application/json', ...(opts.headers || {}) };
    if (typeof opts.body !== 'string') {
      opts.body = JSON.stringify(opts.body);
    }
  }

  // AuthClient attaches the bearer token and silently refreshes it (proactively
  // near expiry; reactively once on a 401, then retries) — CD-028.
  let response;
  try {
    response = await AuthClient.authFetch(url, opts);
  } catch (networkErr) {
    const err = new Error('Network error — could not reach the server.');
    err.status = 0;
    err.body = null;
    err.cause = networkErr;
    throw err;
  }

  // Still 401 after the silent refresh: the session is truly dead -> login.
  if (response.status === 401) {
    auth.logout();
    const err = new Error('Session expired — please sign in again.');
    err.status = 401;
    err.body = null;
    throw err;
  }

  if (!response.ok) {
    throw await buildApiError(response);
  }

  if (response.status === 204) return null;

  // Some endpoints may return an empty body; guard JSON parsing.
  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

function buildListUrl(s) {
  const params = new URLSearchParams();
  if (s.search) params.set('search', s.search);
  if (s.tag) params.set('tag', s.tag);
  params.set('page', String(s.page));
  params.set('size', String(s.size));
  params.set('sort', s.sort);
  return `${API_BASE}?${params.toString()}`;
}

function listContacts(s) {
  return request(buildListUrl(s), { method: 'GET' });
}

function getContact(id) {
  return request(`${API_BASE}/${encodeURIComponent(id)}`, { method: 'GET' });
}

function createContact(body) {
  return request(API_BASE, { method: 'POST', body });
}

function updateContact(id, body) {
  return request(`${API_BASE}/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body,
  });
}

function deleteContact(id) {
  return request(`${API_BASE}/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/** Restore a soft-deleted contact (POST /{id}/restore -> 200 ContactResponse). */
function restoreContact(id) {
  return request(`${API_BASE}/${encodeURIComponent(id)}/restore`, {
    method: 'POST',
  });
}

/** Permanently (hard) delete a contact (DELETE /{id}/permanent -> 204). */
function purgeContact(id) {
  return request(`${API_BASE}/${encodeURIComponent(id)}/permanent`, {
    method: 'DELETE',
  });
}

/** List soft-deleted contacts (GET /trash). Trash has its own minimal URL. */
function listTrash(s) {
  const params = new URLSearchParams();
  params.set('page', String(s.page));
  params.set('size', String(s.size));
  return request(`${API_BASE}/trash?${params.toString()}`, { method: 'GET' });
}

/** Bulk soft-delete contacts; returns BulkResult {affected}. */
function bulkDelete(ids) {
  return request(`${API_BASE}/bulk/delete`, { method: 'POST', body: { ids } });
}

/** Bulk set favourite flag; returns BulkResult {affected}. */
function bulkFavorite(ids, favorite) {
  return request(`${API_BASE}/bulk/favorite`, {
    method: 'POST',
    body: { ids, favorite },
  });
}

/** Bulk add/remove tags; returns BulkResult {affected}. */
function bulkTags(ids, addTags, removeTags) {
  return request(`${API_BASE}/bulk/tags`, {
    method: 'POST',
    body: { ids, addTags, removeTags },
  });
}

/** Partially update a contact (used by the favourite star toggle). */
function patchContact(id, body) {
  return request(`${API_BASE}/${encodeURIComponent(id)}`, {
    method: 'PATCH',
    body,
  });
}

/**
 * Upload a contact's photo via multipart/form-data. The field name MUST be
 * "file" to match the server's @RequestParam. We deliberately do NOT set a
 * Content-Type header — the browser adds it with the correct multipart
 * boundary. Returns the updated ContactResponse (carrying photoUrl).
 */
function uploadPhoto(id, file) {
  const form = new FormData();
  form.append('file', file);
  return request(`${API_BASE}/${encodeURIComponent(id)}/photo`, {
    method: 'POST',
    body: form,
  });
}

function deletePhoto(id) {
  return request(`${API_BASE}/${encodeURIComponent(id)}/photo`, {
    method: 'DELETE',
  });
}

/** Fetch the distinct, sorted set of tags in use (for the filter dropdown). */
function listTags() {
  return request(`${API_BASE}/tags`, { method: 'GET' });
}

/** Upload a CSV file for bulk import; returns the {imported, skipped, errors} summary. */
function importContacts(file) {
  const form = new FormData();
  form.append('file', file);
  return request(`${API_BASE}/import`, { method: 'POST', body: form });
}

/* ------------------------------------------------------------------ *
 * 4. Rendering (XSS-safe)
 * ------------------------------------------------------------------ */

/** Coerce a possibly null/blank value to a display string, defaulting to em dash. */
function display(value) {
  if (value === null || value === undefined) return '—';
  const str = String(value).trim();
  return str === '' ? '—' : str;
}

function fullName(contact) {
  const name = [contact.firstName, contact.lastName]
    .filter((p) => p !== null && p !== undefined && String(p).trim() !== '')
    .join(' ')
    .trim();
  return name || '(no name)';
}

/**
 * Build a <td>. data-label drives the stacked card layout on narrow screens.
 * Text is always set via textContent so contact data can never inject markup.
 */
function makeCell(label, text) {
  const td = document.createElement('td');
  td.setAttribute('data-label', label);
  td.textContent = text;
  return td;
}

/** Build a click-to-action href. 'tel' keeps only + and digits; 'mailto' as-is. */
function actionHref(value, scheme) {
  const v = String(value).trim();
  return scheme === 'tel' ? 'tel:' + v.replace(/[^+0-9]/g, '') : 'mailto:' + v;
}

/**
 * Copy text to the clipboard, returning a Promise that resolves to true on
 * success. Prefers the async Clipboard API; falls back to a hidden textarea +
 * execCommand('copy') for older browsers or non-secure contexts where
 * navigator.clipboard is unavailable.
 */
function copyToClipboard(text) {
  const value = String(text == null ? '' : text);
  if (navigator.clipboard && window.isSecureContext) {
    return navigator.clipboard.writeText(value).then(() => true).catch(() => false);
  }
  // Legacy fallback: a transient off-screen textarea.
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
 * A small icon button that copies `value` to the clipboard. The click is wired
 * via delegation (data-action="copy" / data-copy) and must not bubble up to the
 * row detail handler. `label` describes what is being copied for the aria-label.
 */
function makeCopyButton(value, label) {
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = 'btn-copy';
  btn.dataset.action = 'copy';
  btn.dataset.copy = String(value == null ? '' : value);
  btn.textContent = '⧉';
  btn.dataset.copyLabel = `Copy ${label}`;
  btn.setAttribute('aria-label', `Copy ${label}`);
  btn.title = `Copy ${label}`;
  return btn;
}

/**
 * Handle a click on a copy button: copy its data-copy value, then give brief
 * visual feedback (a "Copied" state on the button plus a toast). Falls back to
 * an error toast when the clipboard is unavailable.
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

/**
 * A table cell rendering email/phone as a click-to-action link. The href is set
 * via the .href PROPERTY (never an HTML string) so values can't inject markup.
 * Clicking the link does not also open the row's detail modal. When `copyLabel`
 * is given, a copy-to-clipboard button is rendered alongside the link.
 */
function makeContactLinkCell(label, value, scheme, copyLabel) {
  const td = document.createElement('td');
  td.setAttribute('data-label', label);
  const v = value == null ? '' : String(value).trim();
  if (v === '') {
    td.textContent = '—';
    return td;
  }
  const wrap = document.createElement('span');
  wrap.className = 'cell-copyable';
  const a = document.createElement('a');
  a.className = 'link';
  a.href = actionHref(v, scheme);
  a.textContent = v;
  a.addEventListener('click', (e) => e.stopPropagation());
  wrap.appendChild(a);
  if (copyLabel) {
    wrap.appendChild(makeCopyButton(v, copyLabel));
  }
  td.appendChild(wrap);
  return td;
}

/** Render a value into a node as a click-to-action link, or em dash if empty. */
function setContactLink(node, value, scheme) {
  const v = value == null ? '' : String(value).trim();
  if (v === '') {
    node.textContent = '—';
    return;
  }
  const a = document.createElement('a');
  a.className = 'link';
  a.href = actionHref(v, scheme);
  a.textContent = v;
  node.replaceChildren(a);
}

/** Initials from first/last name (e.g. "Ada Lovelace" -> "AL"), uppercased. */
function initials(contact) {
  const first = (contact.firstName || '').trim();
  const last = (contact.lastName || '').trim();
  const text = (first.charAt(0) + last.charAt(0)).toUpperCase();
  return text || '?';
}

/**
 * Curated palette for initials avatars — all dark/saturated enough to read
 * with white text. The colour is chosen deterministically per contact.
 */
const AVATAR_COLORS = [
  '#1d4ed8', // blue
  '#4338ca', // indigo
  '#6d28d9', // violet
  '#7e22ce', // purple
  '#a21caf', // fuchsia
  '#be185d', // pink
  '#be123c', // rose
  '#b91c1c', // red
  '#c2410c', // orange
  '#15803d', // green
  '#0f766e', // teal
  '#0e7490', // cyan
];

/**
 * Pick a stable background colour for a contact's initials placeholder. The
 * same contact (by email, falling back to name) always maps to the same colour.
 */
function avatarColor(contact) {
  const key = (contact.email || fullName(contact) || '').toLowerCase();
  let hash = 0;
  for (let i = 0; i < key.length; i++) {
    hash = (hash * 31 + key.charCodeAt(i)) | 0;
  }
  return AVATAR_COLORS[Math.abs(hash) % AVATAR_COLORS.length];
}

/**
 * Leading cell with a star button that toggles the contact's favourite state.
 * The glyph (★ filled / ☆ outline) is set via textContent; the action is wired
 * through event delegation using data-action / data-id (no inline handlers).
 */
function makeStarCell(contact) {
  const td = document.createElement('td');
  td.className = 'cell-star';
  td.setAttribute('data-label', 'Favourite');

  const btn = document.createElement('button');
  btn.type = 'button';
  btn.className = contact.favorite ? 'btn-star btn-star--on' : 'btn-star';
  btn.dataset.action = 'favorite';
  btn.dataset.id = String(contact.id);
  btn.textContent = contact.favorite ? '★' : '☆';
  btn.setAttribute('aria-pressed', contact.favorite ? 'true' : 'false');
  btn.setAttribute(
    'aria-label',
    contact.favorite
      ? `Remove ${fullName(contact)} from favourites`
      : `Add ${fullName(contact)} to favourites`
  );
  btn.title = contact.favorite ? 'Unfavourite' : 'Favourite';

  td.appendChild(btn);
  return td;
}

/**
 * Leading cell holding either the contact's photo or an initials placeholder.
 * The <img> src is assigned via the property (never built into an HTML string)
 * and initials are set via textContent, so contact data can never inject markup.
 */
/**
 * Point an <img> at a photo served by an authenticated endpoint. A plain
 * `<img src>` can't carry the Bearer token, so fetch the bytes with it, wrap
 * them in an object URL and assign that. Without an `onUrl` callback the URL is
 * revoked once the image has decoded (the rendered image is unaffected), which
 * avoids leaking blobs for the long-lived table / detail avatars.
 *
 * @param {HTMLImageElement} img the image element to populate
 * @param {string} url the photo URL (e.g. /api/v1/contacts/1/photo)
 * @param {(objectUrl: string) => void} [onUrl] receive the object URL instead of
 *        auto-revoking (so a caller can revoke it later, e.g. the modal preview)
 */
function setAuthedImageSrc(img, url, onUrl) {
  // authFetch attaches the bearer token and silently refreshes when needed,
  // so avatars keep loading past access-token expiry (CD-028).
  AuthClient.authFetch(url, {})
    .then((res) => (res.ok ? res.blob() : Promise.reject(new Error(`HTTP ${res.status}`))))
    .then((blob) => {
      const objectUrl = URL.createObjectURL(blob);
      if (onUrl) {
        onUrl(objectUrl);
      } else {
        img.addEventListener('load', () => URL.revokeObjectURL(objectUrl), { once: true });
      }
      img.src = objectUrl;
    })
    .catch(() => {
      /* Non-critical: leave the avatar blank if the photo can't be loaded. */
    });
}

/**
 * Build the avatar element for a contact: an <img> when a photo exists, else a
 * coloured initials placeholder. Shared by the table cell and the detail modal.
 */
function avatarElement(contact, extraClass) {
  if (contact.photoUrl) {
    const img = document.createElement('img');
    img.className = 'avatar' + (extraClass ? ' ' + extraClass : '');
    img.alt = '';
    setAuthedImageSrc(img, contact.photoUrl);
    return img;
  }
  const span = document.createElement('span');
  span.className = 'avatar avatar--placeholder' + (extraClass ? ' ' + extraClass : '');
  span.style.backgroundColor = avatarColor(contact);
  span.setAttribute('aria-hidden', 'true');
  span.textContent = initials(contact);
  return span;
}

function makeAvatarCell(contact) {
  const td = document.createElement('td');
  td.className = 'cell-avatar';
  td.setAttribute('data-label', 'Photo');
  td.appendChild(avatarElement(contact));
  return td;
}

/**
 * Leading select cell holding a per-row checkbox. The checkbox reflects the
 * current selectedIds membership; its change/click is handled via delegation and
 * must NOT bubble up to open the row detail modal.
 */
function makeSelectCell(contact) {
  const td = document.createElement('td');
  td.className = 'cell-select';
  td.setAttribute('data-label', 'Select');

  const cb = document.createElement('input');
  cb.type = 'checkbox';
  cb.className = 'row-select';
  cb.dataset.id = String(contact.id);
  cb.checked = selectedIds.has(String(contact.id));
  cb.setAttribute('aria-label', `Select ${fullName(contact)}`);

  td.appendChild(cb);
  return td;
}

/**
 * Build the action cell for a row. In the normal list this is Edit/Delete; in
 * the Trash view it is Restore / Delete-forever (the latter reuses the confirm
 * modal via the purge path).
 */
function makeActionsCell(contact) {
  const td = document.createElement('td');
  td.className = 'cell-actions';
  td.setAttribute('data-label', 'Actions');

  if (viewingTrash) {
    const restoreBtn = document.createElement('button');
    restoreBtn.type = 'button';
    restoreBtn.className = 'btn btn--ghost btn--sm';
    restoreBtn.dataset.action = 'restore';
    restoreBtn.dataset.id = String(contact.id);
    restoreBtn.textContent = 'Restore';
    restoreBtn.setAttribute('aria-label', `Restore ${fullName(contact)}`);

    const purgeBtn = document.createElement('button');
    purgeBtn.type = 'button';
    purgeBtn.className = 'btn btn--ghost btn--danger btn--sm';
    purgeBtn.dataset.action = 'purge';
    purgeBtn.dataset.id = String(contact.id);
    purgeBtn.textContent = 'Delete forever';
    purgeBtn.setAttribute(
      'aria-label',
      `Permanently delete ${fullName(contact)}`
    );

    td.append(restoreBtn, purgeBtn);
    return td;
  }

  const editBtn = document.createElement('button');
  editBtn.type = 'button';
  editBtn.className = 'btn btn--ghost btn--sm';
  editBtn.dataset.action = 'edit';
  editBtn.dataset.id = String(contact.id);
  editBtn.textContent = 'Edit';
  editBtn.setAttribute('aria-label', `Edit ${fullName(contact)}`);

  const deleteBtn = document.createElement('button');
  deleteBtn.type = 'button';
  deleteBtn.className = 'btn btn--ghost btn--danger btn--sm';
  deleteBtn.dataset.action = 'delete';
  deleteBtn.dataset.id = String(contact.id);
  deleteBtn.textContent = 'Delete';
  deleteBtn.setAttribute('aria-label', `Delete ${fullName(contact)}`);

  td.append(editBtn, deleteBtn);
  return td;
}

/**
 * Cell rendering the contact's tags as small chips. Each chip's label is set
 * via textContent so tag values can never inject markup. Shows an em dash when
 * the contact has no tags.
 */
function makeTagsCell(contact) {
  const td = document.createElement('td');
  td.className = 'cell-tags';
  td.setAttribute('data-label', 'Tags');

  const tags = Array.isArray(contact.tags) ? contact.tags : [];
  if (tags.length === 0) {
    td.textContent = '—';
    return td;
  }

  const wrap = document.createElement('div');
  wrap.className = 'tag-chips tag-chips--row';
  for (const tag of tags) {
    const chip = document.createElement('span');
    chip.className = 'tag-chip';
    chip.textContent = tag;
    wrap.appendChild(chip);
  }
  td.appendChild(wrap);
  return td;
}

function makeRow(contact) {
  const tr = document.createElement('tr');
  tr.dataset.id = String(contact.id);
  tr.append(
    makeSelectCell(contact),
    makeStarCell(contact),
    makeAvatarCell(contact),
    makeCell('Name', fullName(contact)),
    makeContactLinkCell('Email', contact.email, 'mailto', 'email'),
    makeContactLinkCell('Phone', contact.phone, 'tel'),
    makeCell('Company', display(contact.company)),
    makeTagsCell(contact),
    makeActionsCell(contact)
  );
  return tr;
}

function renderRows(contacts) {
  const tbody = el.contactsBody;
  tbody.replaceChildren();
  const frag = document.createDocumentFragment();
  for (const contact of contacts) {
    frag.appendChild(makeRow(contact));
  }
  tbody.appendChild(frag);
}

/* ------------------------------------------------------------------ *
 * 4b. Bulk selection
 * ------------------------------------------------------------------ */

/** Ids of the contacts on the currently loaded page (as Strings). */
function currentPageIds() {
  if (!currentPage || !Array.isArray(currentPage.content)) return [];
  return currentPage.content.map((c) => String(c.id));
}

/**
 * Show/hide the bulk bar and sync its count + the select-all checkbox state to
 * the current selection. The Trash view never shows the bulk bar.
 */
function refreshBulkBar() {
  const n = selectedIds.size;

  if (el.bulkBar) {
    el.bulkBar.hidden = viewingTrash || n === 0;
  }
  if (el.bulkCount) {
    el.bulkCount.textContent = `${n} selected`;
  }

  if (el.selectAll) {
    const pageIds = currentPageIds();
    const selectedOnPage = pageIds.filter((id) => selectedIds.has(id)).length;
    el.selectAll.checked = pageIds.length > 0 && selectedOnPage === pageIds.length;
    el.selectAll.indeterminate =
      selectedOnPage > 0 && selectedOnPage < pageIds.length;
  }
}

/** Reflect selectedIds onto the rendered row checkboxes (after a load/render). */
function syncRowCheckboxes() {
  const boxes = el.contactsBody.querySelectorAll('input.row-select');
  boxes.forEach((cb) => {
    cb.checked = selectedIds.has(String(cb.dataset.id));
  });
}

/** Toggle a single row's selection. */
function toggleRowSelect(id, checked) {
  const key = String(id);
  if (checked) selectedIds.add(key);
  else selectedIds.delete(key);
  refreshBulkBar();
}

/** Select / deselect every contact on the CURRENT page only. */
function toggleSelectAll(checked) {
  for (const id of currentPageIds()) {
    if (checked) selectedIds.add(id);
    else selectedIds.delete(id);
  }
  syncRowCheckboxes();
  refreshBulkBar();
}

/** Clear the whole selection and hide the bulk bar. */
function clearSelection() {
  selectedIds.clear();
  syncRowCheckboxes();
  if (el.selectAll) {
    el.selectAll.checked = false;
    el.selectAll.indeterminate = false;
  }
  refreshBulkBar();
}

/* ------------------------------------------------------------------ *
 * 5. Loading the list
 * ------------------------------------------------------------------ */

/** Holds the most recently loaded page so callers (delete) can inspect it. */
let currentPage = null;

function setLoading(isLoading) {
  el.loading.hidden = !isLoading;
}

function updatePagination(page) {
  const totalPages = page.totalPages || 0;
  const totalElements = page.totalElements || 0;
  const humanPage = totalPages === 0 ? 0 : page.number + 1;
  const noun = totalElements === 1 ? 'contact' : 'contacts';

  el.pageInfo.textContent = `Page ${humanPage} of ${totalPages} — ${totalElements} ${noun}`;

  // first/last come from the API; fall back to computed values defensively.
  const isFirst = page.first !== undefined ? page.first : page.number === 0;
  const isLast =
    page.last !== undefined ? page.last : page.number >= totalPages - 1;

  el.btnPrev.disabled = isFirst || totalElements === 0;
  el.btnNext.disabled = isLast || totalElements === 0;
}

async function load() {
  setLoading(true);
  el.emptyState.hidden = true;
  el.btnPrev.disabled = true;
  el.btnNext.disabled = true;

  try {
    const page = viewingTrash
      ? await listTrash(state)
      : await listContacts(state);
    currentPage = page;

    const content = Array.isArray(page.content) ? page.content : [];
    renderRows(content);

    const isEmpty = page.empty !== undefined ? page.empty : content.length === 0;
    el.emptyState.hidden = !isEmpty;

    updatePagination(page);

    // Rows were rebuilt: re-reflect any surviving selection and resync the bar.
    syncRowCheckboxes();
    refreshBulkBar();
  } catch (err) {
    currentPage = null;
    renderRows([]);
    el.emptyState.hidden = true;
    el.pageInfo.textContent = '';
    refreshBulkBar();
    toast(err.message || 'Failed to load contacts.', 'error');
  } finally {
    setLoading(false);
  }
}

/* ------------------------------------------------------------------ *
 * 6. Toast
 * ------------------------------------------------------------------ */

let toastTimer = null;

/**
 * Show a transient message. variant: 'success' | 'error'.
 * Reuses the single #toast element; restarts the fade timer on each call.
 *
 * Optional `action` = { label, fn } renders a button after the message (e.g. an
 * "Undo" affordance). The label is set via textContent and the handler via
 * addEventListener — never innerHTML — so it stays XSS-safe. 2-arg calls keep
 * working: with no action the toast is just the message text.
 */
function toast(message, variant = 'success', action) {
  const node = el.toast;

  // replaceChildren (not textContent) so we can append an action button without
  // a later 2-arg call wiping it — each call rebuilds the toast contents.
  const text = document.createTextNode(message);
  node.replaceChildren(text);

  if (action && action.label && typeof action.fn === 'function') {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'toast__action';
    btn.textContent = action.label;
    btn.addEventListener('click', () => {
      // Dismiss the toast immediately; the action decides what happens next.
      if (toastTimer) clearTimeout(toastTimer);
      node.classList.remove('toast--show');
      action.fn();
    });
    node.appendChild(btn);
  }

  node.classList.remove('toast--success', 'toast--error');
  node.classList.add(variant === 'error' ? 'toast--error' : 'toast--success');
  node.classList.add('toast--show');

  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    node.classList.remove('toast--show');
  }, 3500);
}

/* ------------------------------------------------------------------ *
 * 7. Contact modal (create / edit)
 * ------------------------------------------------------------------ */

/**
 * Element that opened the most recent dialog (e.g. #btn-new or a row button).
 * Focus is returned here when the dialog closes so keyboard / screen-reader
 * users keep their place. Falls back to #btn-new if the trigger is gone.
 */
let lastTrigger = null;

/* --- Photo selection state (modal) --------------------------------- *
 * selectedPhotoFile : a newly chosen File pending upload, or null.
 * pendingPhotoRemoval : in edit mode, true once the user removes an
 *                       existing photo so save issues a DELETE.
 * previewObjectUrl : the object URL currently shown in #photo-preview
 *                    (only when previewing a freshly selected File), so we
 *                    can revoke it and avoid leaking blobs.
 */
let selectedPhotoFile = null;
let pendingPhotoRemoval = false;
let previewObjectUrl = null;

/** Revoke and forget any object URL we created for the preview image. */
function revokePreviewUrl() {
  if (previewObjectUrl) {
    URL.revokeObjectURL(previewObjectUrl);
    previewObjectUrl = null;
  }
}

/** Hide the preview image and detach its source. */
function hidePhotoPreview() {
  if (!el.photoPreview) return;
  revokePreviewUrl();
  el.photoPreview.hidden = true;
  el.photoPreview.removeAttribute('src');
}

/** Reset all photo widgets + state to the empty baseline (no selection). */
function resetPhotoState() {
  selectedPhotoFile = null;
  pendingPhotoRemoval = false;
  if (el.fieldPhoto) el.fieldPhoto.value = '';
  if (el.errPhoto) el.errPhoto.textContent = '';
  hidePhotoPreview();
  if (el.btnRemovePhoto) el.btnRemovePhoto.hidden = true;
}

/** Client-side validation mirroring the server's type/size rules. */
function validatePhotoFile(file) {
  const type = (file.type || '').toLowerCase();
  if (!ALLOWED_PHOTO_TYPES.includes(type)) {
    return 'Unsupported image type. Allowed: PNG, JPEG, GIF, WEBP.';
  }
  if (file.size > MAX_PHOTO_BYTES) {
    return 'Image too large (max 2MB).';
  }
  return null;
}

/** Show an existing (already-saved) photo by URL in the modal preview. */
function showExistingPhoto(url) {
  if (!el.photoPreview) return;
  revokePreviewUrl();
  // The saved photo is behind the authenticated endpoint; fetch it with the
  // token and track the object URL so revokePreviewUrl() frees it on hide/reset.
  setAuthedImageSrc(el.photoPreview, url, (objectUrl) => { previewObjectUrl = objectUrl; });
  el.photoPreview.hidden = false;
  if (el.btnRemovePhoto) el.btnRemovePhoto.hidden = false;
}

/* --- Tag editing state (modal) ------------------------------------- *
 * selectedTags : tags currently staged in the form (insertion order kept).
 * tagsTouched  : true once the user adds/removes a tag, so the in-flight edit
 *                refresh (openEdit's fresh fetch) won't clobber their edits.
 */
let selectedTags = [];
let tagsTouched = false;

/**
 * The favourite flag of the contact being edited. The modal has no favourite
 * control (favouriting happens via the row star), so we carry the current value
 * through so a PUT (full replace) preserves it. false for a new contact.
 */
let currentFavorite = false;

/**
 * The optimistic-concurrency version of the contact being edited. Carried
 * through the edit modal so a PUT/PATCH can send it for the server's stale-check.
 * null for a brand-new contact (create) so no version assertion is sent.
 */
let currentVersion = null;

/** Render the staged tags as removable chips inside #tag-chips (XSS-safe). */
function renderTagChips() {
  const wrap = el.tagChips;
  if (!wrap) return;
  wrap.replaceChildren();
  for (const tag of selectedTags) {
    const chip = document.createElement('span');
    chip.className = 'tag-chip';

    const label = document.createElement('span');
    label.textContent = tag;

    const remove = document.createElement('button');
    remove.type = 'button';
    remove.className = 'tag-chip__remove';
    remove.dataset.tag = tag;
    remove.setAttribute('aria-label', `Remove tag ${tag}`);
    remove.textContent = '×';

    chip.append(label, remove);
    wrap.appendChild(chip);
  }
}

/** Add a tag (trimmed; deduped case-insensitively). */
function addTag(raw) {
  const tag = String(raw || '').trim();
  if (!tag) return;
  const exists = selectedTags.some((t) => t.toLowerCase() === tag.toLowerCase());
  if (!exists) {
    selectedTags.push(tag);
    tagsTouched = true;
    renderTagChips();
  }
}

/** Remove a tag (case-insensitive match). */
function removeTag(tag) {
  const before = selectedTags.length;
  selectedTags = selectedTags.filter(
    (t) => t.toLowerCase() !== String(tag).toLowerCase()
  );
  if (selectedTags.length !== before) {
    tagsTouched = true;
    renderTagChips();
  }
}

/**
 * Rebuild the tag filter <select> from the tags currently in use, preserving
 * the active selection when it still exists.
 */
async function populateTagFilter() {
  if (!el.tagFilter) return;
  let tags = [];
  try {
    const result = await listTags();
    if (Array.isArray(result)) tags = result;
  } catch (_) {
    tags = [];
  }
  el.tagFilter.replaceChildren();
  const all = document.createElement('option');
  all.value = '';
  all.textContent = 'All tags';
  el.tagFilter.appendChild(all);
  for (const tag of tags) {
    const opt = document.createElement('option');
    opt.value = tag;
    opt.textContent = tag;
    el.tagFilter.appendChild(opt);
  }
  // Restore selection; if the active tag is gone, fall back to "All tags".
  el.tagFilter.value = state.tag || '';
  if (el.tagFilter.value !== (state.tag || '')) {
    state.tag = '';
  }
}

function restoreTriggerFocus() {
  const target =
    lastTrigger && document.body.contains(lastTrigger) ? lastTrigger : el.btnNew;
  lastTrigger = null;
  if (target && typeof target.focus === 'function') target.focus();
}

function clearFieldErrors() {
  for (const name of ERROR_FIELDS) {
    const errNode = fieldError(name);
    if (errNode) errNode.textContent = '';
    const input = fieldInput(name);
    if (input) input.removeAttribute('aria-invalid');
  }
}

function setFieldError(name, message) {
  const errNode = fieldError(name);
  const input = fieldInput(name);
  if (errNode) errNode.textContent = message;
  if (input) input.setAttribute('aria-invalid', 'true');
}

/** Map a 400 validation error body's `errors` map onto inline spans. */
function applyValidationErrors(errors) {
  if (!errors || typeof errors !== 'object') return;
  let firstInvalid = null;
  for (const [field, message] of Object.entries(errors)) {
    if (ERROR_FIELDS.includes(field)) {
      setFieldError(field, message);
      if (!firstInvalid) firstInvalid = fieldInput(field);
    } else {
      // No dedicated span (e.g. company) — surface it as a toast so it's not lost.
      toast(`${field}: ${message}`, 'error');
    }
  }
  if (firstInvalid) firstInvalid.focus();
}

function resetForm() {
  el.contactForm.reset();
  el.fieldId.value = '';
  clearFieldErrors();
  resetPhotoState();
  selectedTags = [];
  tagsTouched = false;
  currentFavorite = false;
  currentVersion = null;
  if (el.fieldTagInput) el.fieldTagInput.value = '';
  renderTagChips();
}

function fillForm(contact) {
  el.fieldId.value = contact.id != null ? String(contact.id) : '';
  el.fieldFirstName.value = contact.firstName || '';
  el.fieldLastName.value = contact.lastName || '';
  el.fieldEmail.value = contact.email || '';
  el.fieldPhone.value = contact.phone || '';
  el.fieldCompany.value = contact.company || '';
  if (el.fieldNotes) el.fieldNotes.value = contact.notes || '';

  // Carry the favourite flag through so a PUT (full replace) preserves it.
  currentFavorite = !!contact.favorite;

  // Carry the version through for the optimistic-concurrency check. The fresh
  // getContact() refill in openEdit lands last and wins, so we send the
  // authoritative version. null when absent (e.g. a brand-new contact).
  currentVersion = contact.version != null ? contact.version : null;

  // Reflect saved tags — but don't clobber edits the user already made (e.g.
  // the openEdit fresh-fetch landing after they started changing tags).
  if (!tagsTouched) {
    selectedTags = Array.isArray(contact.tags) ? contact.tags.slice() : [];
    renderTagChips();
  }

  // Reflect the saved photo in the preview — but never stomp on a photo the
  // user just selected or a pending removal (these win until save).
  if (!selectedPhotoFile && !pendingPhotoRemoval) {
    if (contact.photoUrl) {
      showExistingPhoto(contact.photoUrl);
    } else {
      hidePhotoPreview();
      if (el.btnRemovePhoto) el.btnRemovePhoto.hidden = true;
    }
  }
}

function openContactModal() {
  if (typeof el.contactModal.showModal === 'function') {
    el.contactModal.showModal();
  } else {
    el.contactModal.setAttribute('open', '');
  }
}

function closeContactModal() {
  if (typeof el.contactModal.close === 'function') {
    el.contactModal.close();
  } else {
    el.contactModal.removeAttribute('open');
  }
}

function openCreate() {
  lastTrigger = document.activeElement;
  resetForm();
  el.modalTitle.textContent = 'New Contact';
  el.btnSave.textContent = 'Create';
  openContactModal();
  el.fieldFirstName.focus();
}

async function openEdit(id) {
  lastTrigger = document.activeElement;
  resetForm();
  el.modalTitle.textContent = 'Edit Contact';
  el.btnSave.textContent = 'Save';

  // Prefer fresh data from the row we already have, then refresh from the API.
  const row =
    currentPage &&
    Array.isArray(currentPage.content) &&
    currentPage.content.find((c) => String(c.id) === String(id));
  if (row) fillForm(row);

  openContactModal();

  try {
    const fresh = await getContact(id);
    if (fresh) fillForm(fresh);
    el.fieldFirstName.focus();
  } catch (err) {
    if (err.status === 404) {
      closeContactModal();
      toast(err.message || 'Contact not found.', 'error');
      load();
    } else if (!row) {
      // Couldn't prefill from a row and the fetch failed — bail out.
      closeContactModal();
      toast(err.message || 'Failed to load contact.', 'error');
    } else {
      el.fieldFirstName.focus();
    }
  }
}

/** Read the form into a request body. Empty optional fields become null. */
function readForm() {
  const optional = (v) => {
    const t = v.trim();
    return t === '' ? null : t;
  };
  return {
    firstName: el.fieldFirstName.value.trim(),
    lastName: el.fieldLastName.value.trim(),
    email: el.fieldEmail.value.trim(),
    phone: optional(el.fieldPhone.value),
    company: optional(el.fieldCompany.value),
    tags: selectedTags.slice(),
    favorite: currentFavorite,
    notes: el.fieldNotes ? optional(el.fieldNotes.value) : null,
    // null on create (no version) -> server skips the check; set on edit.
    version: currentVersion,
  };
}

function setSaving(isSaving) {
  el.btnSave.disabled = isSaving;
  for (const name of FORM_FIELDS) {
    const input = fieldInput(name);
    if (input) input.disabled = isSaving;
  }
  if (el.fieldPhoto) el.fieldPhoto.disabled = isSaving;
  if (el.btnRemovePhoto) el.btnRemovePhoto.disabled = isSaving;
  if (el.fieldTagInput) el.fieldTagInput.disabled = isSaving;
  if (el.fieldNotes) el.fieldNotes.disabled = isSaving;
}

/**
 * Apply pending photo changes for an existing contact (edit save). Removal
 * takes precedence over a new selection. Failures are surfaced as a warning
 * toast but never abort the save — the contact's text fields are already saved.
 */
async function applyPhotoChanges(id) {
  if (pendingPhotoRemoval) {
    try {
      await deletePhoto(id);
    } catch (photoErr) {
      toast(
        `Contact updated, but removing the photo failed: ${
          photoErr.message || 'unknown error'
        }`,
        'error'
      );
    }
  } else if (selectedPhotoFile) {
    try {
      await uploadPhoto(id, selectedPhotoFile);
    } catch (photoErr) {
      toast(
        `Contact updated, but photo upload failed: ${
          photoErr.message || 'unknown error'
        }`,
        'error'
      );
    }
  }
}

async function submitForm(event) {
  event.preventDefault();
  clearFieldErrors();

  const id = el.fieldId.value.trim();
  const isEdit = id !== '';
  const body = readForm();

  setSaving(true);
  try {
    if (isEdit) {
      await updateContact(id, body);
      // Photo side-effects run after a successful save. A photo failure must
      // not fail the whole save — warn and carry on (the contact is saved).
      await applyPhotoChanges(id);
    } else {
      const created = await createContact(body);
      if (created && created.id != null && selectedPhotoFile) {
        try {
          await uploadPhoto(created.id, selectedPhotoFile);
        } catch (photoErr) {
          toast(
            `Contact created, but photo upload failed: ${
              photoErr.message || 'unknown error'
            }`,
            'error'
          );
        }
      }
    }
    closeContactModal();
    toast(isEdit ? 'Contact updated.' : 'Contact created.', 'success');
    load();
    populateTagFilter();
  } catch (err) {
    if (err.status === 400 && err.body && err.body.errors) {
      applyValidationErrors(err.body.errors);
    } else if (err.status === 412) {
      // Stale version — someone else changed this contact. Not a field error:
      // close the modal, warn, and reload the authoritative data.
      closeContactModal();
      toast(
        err.message || 'This contact was changed elsewhere. Reloading…',
        'error'
      );
      load();
      populateTagFilter();
    } else if (err.status === 409) {
      toast(err.message || 'A contact with that email already exists.', 'error');
    } else if (err.status === 404) {
      // The record vanished between opening the form and saving.
      closeContactModal();
      toast(err.message || 'Contact no longer exists.', 'error');
      load();
    } else {
      toast(err.message || 'Failed to save contact.', 'error');
    }
  } finally {
    setSaving(false);
  }
}

/* ------------------------------------------------------------------ *
 * 8. Delete confirmation
 * ------------------------------------------------------------------ */

/** Find a contact's display name on the current page, or a generic fallback. */
function nameOnPage(id) {
  const row =
    currentPage &&
    Array.isArray(currentPage.content) &&
    currentPage.content.find((c) => String(c.id) === String(id));
  return row ? fullName(row) : 'this contact';
}

/** Confirm + soft-delete (move to Trash, reversible via the Undo toast). */
async function openDelete(id) {
  const name = nameOnPage(id);
  const ok = await confirmDialog({
    title: 'Delete contact',
    // textContent inside confirmDialog keeps the name XSS-safe.
    message: `Delete ${name}? You can undo this from Trash.`,
    confirmLabel: 'Delete',
    danger: true,
  });
  // performDelete handles its own errors internally; fire-and-forget is intentional.
  if (ok) void performDelete(id, 'soft');
}

/** Confirm + permanent (hard) delete — no Undo. */
async function openPurge(id) {
  const name = nameOnPage(id);
  const ok = await confirmDialog({
    title: 'Permanently delete contact',
    message: `Permanently delete ${name}? This cannot be undone.`,
    confirmLabel: 'Delete',
    danger: true,
  });
  // performDelete handles its own errors internally; fire-and-forget is intentional.
  if (ok) void performDelete(id, 'purge');
}

/** Step back one page if the just-removed row was the last on a non-first page. */
function stepBackIfPageEmptied() {
  const remaining =
    currentPage && typeof currentPage.numberOfElements === 'number'
      ? currentPage.numberOfElements - 1
      : null;
  if (remaining !== null && remaining <= 0 && state.page > 0) {
    state.page -= 1;
  }
}

/** Run the delete (soft or purge) and reconcile the table / toast / tags. */
async function performDelete(id, mode) {
  if (id == null) return;

  try {
    if (mode === 'purge') {
      await purgeContact(id);
      toast('Contact permanently deleted.', 'success');
    } else {
      await deleteContact(id);
      // Soft delete is reversible — offer an Undo that restores then reloads.
      toast('Contact moved to Trash.', 'success', {
        label: 'Undo',
        fn: () => undoDelete([id]),
      });
    }

    stepBackIfPageEmptied();
    load();
    populateTagFilter();
  } catch (err) {
    if (err.status === 404) {
      toast(err.message || 'Contact already deleted.', 'error');
    } else {
      toast(err.message || 'Failed to delete contact.', 'error');
    }
    load();
  }
}

/** Restore one or more soft-deleted ids (Undo handler), then reload + refresh tags. */
async function undoDelete(ids) {
  try {
    await Promise.all(ids.map((id) => restoreContact(id)));
    toast(ids.length === 1 ? 'Contact restored.' : `${ids.length} contacts restored.`, 'success');
  } catch (err) {
    toast(err.message || 'Failed to restore.', 'error');
  } finally {
    load();
    populateTagFilter();
  }
}

/** Restore a single contact from the Trash view, then reload + refresh tags. */
async function restoreFromTrash(id) {
  try {
    await restoreContact(id);
    toast('Contact restored.', 'success');
  } catch (err) {
    if (err.status === 404) {
      toast(err.message || 'Contact no longer exists.', 'error');
    } else {
      toast(err.message || 'Failed to restore contact.', 'error');
    }
  } finally {
    // Restoring the last row on a non-first trash page should step back too.
    stepBackIfPageEmptied();
    load();
    populateTagFilter();
  }
}

/* ------------------------------------------------------------------ *
 * 8b. Favourite toggle
 * ------------------------------------------------------------------ */

/** Toggle a contact's favourite flag via PATCH, then reload the list. */
async function toggleFavorite(id) {
  const contact =
    currentPage &&
    Array.isArray(currentPage.content) &&
    currentPage.content.find((c) => String(c.id) === String(id));
  const next = contact ? !contact.favorite : true;
  try {
    await patchContact(id, { favorite: next });
    await load();
  } catch (err) {
    if (err.status === 404) {
      toast(err.message || 'Contact no longer exists.', 'error');
      load();
    } else {
      toast(err.message || 'Failed to update favourite.', 'error');
    }
  }
}

/* ------------------------------------------------------------------ *
 * 8c. CSV import
 * ------------------------------------------------------------------ */

/** Handle a chosen CSV file: upload, toast the summary, reload + refresh tags. */
async function onImportFile() {
  const file = el.importFile && el.importFile.files && el.importFile.files[0];
  if (!file) return;
  try {
    const summary = await importContacts(file);
    const parts = [`Imported ${summary.imported || 0}`];
    if (summary.skipped) parts.push(`skipped ${summary.skipped}`);
    const errCount = summary.errors ? summary.errors.length : 0;
    if (errCount) parts.push(`${errCount} error${errCount === 1 ? '' : 's'}`);
    toast(parts.join(', ') + '.', errCount ? 'error' : 'success');
    state.page = 0;
    load();
    populateTagFilter();
  } catch (err) {
    toast(err.message || 'Import failed.', 'error');
  } finally {
    if (el.importFile) el.importFile.value = '';
  }
}

/* ------------------------------------------------------------------ *
 * 8c2. Bulk actions
 * ------------------------------------------------------------------ */

/**
 * Run a bulk operation over the current selection, then clear + reload.
 * `op` returns a Promise of the BulkResult; `verb` describes the action for the
 * toast. `refreshTags` re-syncs the tag filter for ops that change tags/active set.
 * Optional `undoIds` enables an Undo toast (used by bulk delete).
 */
async function runBulk(op, verb, refreshTags, undoIds) {
  const ids = [...selectedIds];
  if (ids.length === 0) return;

  try {
    const result = await op(ids);
    const affected = result && typeof result.affected === 'number' ? result.affected : 0;
    const noun = affected === 1 ? 'contact' : 'contacts';
    const message = `${affected} ${noun} ${verb}.`;

    clearSelection();
    if (undoIds) {
      toast(message, 'success', {
        label: 'Undo',
        fn: () => undoDelete(undoIds),
      });
    } else {
      toast(message, 'success');
    }
    load();
    if (refreshTags) populateTagFilter();
  } catch (err) {
    toast(err.message || 'Bulk action failed.', 'error');
  }
}

function onBulkFavorite() {
  runBulk((ids) => bulkFavorite(ids, true), 'favourited', false);
}

function onBulkUnfavorite() {
  runBulk((ids) => bulkFavorite(ids, false), 'unfavourited', false);
}

function onBulkTag() {
  // Minimal UX: prompt for a tag to add to every selected contact.
  const raw = typeof window.prompt === 'function' ? window.prompt('Tag to add to selected contacts:') : null;
  if (raw == null) return; // cancelled
  const tag = String(raw).trim();
  if (!tag) return;
  runBulk((ids) => bulkTags(ids, [tag], []), 'tagged', true);
}

function onBulkDelete() {
  // Capture the ids BEFORE clearSelection() empties the Set, for the Undo toast.
  const ids = [...selectedIds];
  if (ids.length === 0) return;
  runBulk((idList) => bulkDelete(idList), 'moved to Trash', true, ids);
}

/* ------------------------------------------------------------------ *
 * 8c3. Trash view toggle
 * ------------------------------------------------------------------ */

/** Flip between the normal list and the Trash view, resetting page + selection. */
function toggleTrashView() {
  viewingTrash = !viewingTrash;
  clearSelection();
  state.page = 0;
  applyTrashViewState();
  load();
}

/** Sync chrome (New button, bulk bar, trash button label) to viewingTrash. */
function applyTrashViewState() {
  if (el.btnNew) el.btnNew.hidden = viewingTrash;
  if (el.btnTrash) {
    el.btnTrash.textContent = viewingTrash ? 'Back to contacts' : 'Trash';
    el.btnTrash.setAttribute('aria-pressed', viewingTrash ? 'true' : 'false');
  }
  // Activates the .trash-active .btn-trash danger styling defined in styles.css.
  document.body.classList.toggle('trash-active', viewingTrash);
  refreshBulkBar();
}

/* ------------------------------------------------------------------ *
 * 8d. Contact detail modal (read-only profile)
 * ------------------------------------------------------------------ */

/** Id of the contact shown in the detail modal (used by its Edit button). */
let detailId = null;

/** Format an ISO timestamp for display, or em dash when missing/invalid. */
function formatDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return '—';
  return d.toLocaleString(undefined, { dateStyle: 'medium', timeStyle: 'short' });
}

function showDetailModal() {
  if (typeof el.detailModal.showModal === 'function') el.detailModal.showModal();
  else el.detailModal.setAttribute('open', '');
}

function closeDetailModal() {
  if (typeof el.detailModal.close === 'function') el.detailModal.close();
  else el.detailModal.removeAttribute('open');
}

/** Render a contact into the detail modal. All values are set XSS-safely. */
function renderDetail(contact) {
  detailId = contact.id != null ? contact.id : null;

  el.detailAvatar.replaceChildren(avatarElement(contact, 'avatar--lg'));
  el.detailName.textContent = fullName(contact);
  el.detailFav.hidden = !contact.favorite;
  setContactLink(el.detailEmail, contact.email, 'mailto');
  setContactLink(el.detailPhone, contact.phone, 'tel');
  el.detailCompany.textContent = display(contact.company);

  const tags = Array.isArray(contact.tags) ? contact.tags : [];
  if (tags.length === 0) {
    el.detailTags.textContent = '—';
  } else {
    const wrap = document.createElement('div');
    wrap.className = 'tag-chips tag-chips--row';
    for (const tag of tags) {
      const chip = document.createElement('span');
      chip.className = 'tag-chip';
      chip.textContent = tag;
      wrap.appendChild(chip);
    }
    el.detailTags.replaceChildren(wrap);
  }

  // Notes are only shown when present (the field arrives in Feature 8).
  const hasNotes = contact.notes != null && String(contact.notes).trim() !== '';
  el.detailNotesRow.hidden = !hasNotes;
  el.detailNotes.textContent = hasNotes ? contact.notes : '';

  el.detailCreated.textContent = formatDate(contact.createdAt);
  el.detailUpdated.textContent = formatDate(contact.updatedAt);
}

/** Open the detail modal for a contact id (use the loaded row, else fetch). */
async function openDetail(id) {
  lastTrigger = document.activeElement;
  const row =
    currentPage &&
    Array.isArray(currentPage.content) &&
    currentPage.content.find((c) => String(c.id) === String(id));
  if (row) {
    renderDetail(row);
    showDetailModal();
    return;
  }
  try {
    const fresh = await getContact(id);
    if (fresh) {
      renderDetail(fresh);
      showDetailModal();
    }
  } catch (err) {
    toast(err.message || 'Failed to load contact.', 'error');
  }
}

/* ------------------------------------------------------------------ *
 * 8e. Theme (dark / light)
 * ------------------------------------------------------------------ */

/** Current theme from <html data-theme>; defaults to light. */
function currentTheme() {
  return document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light';
}

/** Sync the toggle button's glyph + accessible label to the active theme. */
function updateThemeButton() {
  if (!el.btnTheme) return;
  const dark = currentTheme() === 'dark';
  el.btnTheme.textContent = dark ? '☀️' : '🌙';
  el.btnTheme.setAttribute('aria-label', dark ? 'Switch to light theme' : 'Switch to dark theme');
  el.btnTheme.setAttribute('aria-pressed', dark ? 'true' : 'false');
}

/** Flip the theme, persist it, and update the button. */
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
 * 9. Event wiring
 * ------------------------------------------------------------------ */

function debounce(fn, wait) {
  let timer = null;
  return function debounced(...args) {
    if (timer) clearTimeout(timer);
    timer = setTimeout(() => {
      timer = null;
      fn.apply(this, args);
    }, wait);
  };
}

const onSearch = debounce(() => {
  state.search = el.searchInput.value.trim();
  state.page = 0;
  load();
}, SEARCH_DEBOUNCE_MS);

/** A dialog is a modal "backdrop click" when the click lands on the dialog element itself. */
function isBackdropClick(dialog, event) {
  return event.target === dialog;
}

/** #field-photo change: validate, then either store + preview or reject. */
function onPhotoChange() {
  const file = el.fieldPhoto.files && el.fieldPhoto.files[0];
  if (el.errPhoto) el.errPhoto.textContent = '';

  if (!file) {
    // Selection cleared via the native picker — drop any staged file but keep
    // an existing saved photo visible (handled by not forcing a removal here).
    selectedPhotoFile = null;
    revokePreviewUrl();
    return;
  }

  const error = validatePhotoFile(file);
  if (error) {
    if (el.errPhoto) el.errPhoto.textContent = error;
    selectedPhotoFile = null;
    el.fieldPhoto.value = '';
    revokePreviewUrl();
    return;
  }

  // Valid: stage it, cancel any pending removal, preview via object URL.
  selectedPhotoFile = file;
  pendingPhotoRemoval = false;
  revokePreviewUrl();
  previewObjectUrl = URL.createObjectURL(file);
  if (el.photoPreview) {
    el.photoPreview.src = previewObjectUrl;
    el.photoPreview.hidden = false;
  }
  if (el.btnRemovePhoto) el.btnRemovePhoto.hidden = false;
}

/** #btn-remove-photo click: clear the choice; in edit mode, queue a delete. */
function onRemovePhoto() {
  const isEdit = el.fieldId.value.trim() !== '';
  selectedPhotoFile = null;
  if (el.fieldPhoto) el.fieldPhoto.value = '';
  hidePhotoPreview();
  if (el.errPhoto) el.errPhoto.textContent = '';
  if (el.btnRemovePhoto) el.btnRemovePhoto.hidden = true;
  // In edit mode this signals "delete the saved photo on save".
  pendingPhotoRemoval = isEdit;
}

function wireEvents() {
  // Header — open create modal.
  el.btnNew.addEventListener('click', openCreate);

  // Header — theme toggle.
  if (el.btnTheme) el.btnTheme.addEventListener('click', toggleTheme);

  // Search (debounced) + clear.
  el.searchInput.addEventListener('input', onSearch);
  el.btnClearSearch.addEventListener('click', () => {
    el.searchInput.value = '';
    state.search = '';
    state.page = 0;
    load();
    el.searchInput.focus();
  });

  // Page size.
  el.pageSize.addEventListener('change', () => {
    const size = parseInt(el.pageSize.value, 10);
    state.size = Number.isNaN(size) ? 20 : size;
    state.page = 0;
    load();
  });

  // Sort.
  el.sortSelect.addEventListener('change', () => {
    state.sort = el.sortSelect.value;
    state.page = 0;
    load();
  });

  // Tag filter.
  if (el.tagFilter) {
    el.tagFilter.addEventListener('change', () => {
      state.tag = el.tagFilter.value;
      state.page = 0;
      load();
    });
  }

  // Import CSV: the button opens the hidden file input; choosing a file uploads it.
  // (Export CSV/JSON are plain download anchors — no JS needed.)
  if (el.btnImport && el.importFile) {
    el.btnImport.addEventListener('click', () => el.importFile.click());
    el.importFile.addEventListener('change', onImportFile);
  }

  // Trash view toggle.
  if (el.btnTrash) el.btnTrash.addEventListener('click', toggleTrashView);

  // Select-all (current page only) + bulk action bar buttons.
  if (el.selectAll) {
    el.selectAll.addEventListener('change', () =>
      toggleSelectAll(el.selectAll.checked)
    );
  }
  if (el.btnBulkFavorite)
    el.btnBulkFavorite.addEventListener('click', onBulkFavorite);
  if (el.btnBulkUnfavorite)
    el.btnBulkUnfavorite.addEventListener('click', onBulkUnfavorite);
  if (el.btnBulkTag) el.btnBulkTag.addEventListener('click', onBulkTag);
  if (el.btnBulkDelete) el.btnBulkDelete.addEventListener('click', onBulkDelete);
  if (el.btnBulkClear) el.btnBulkClear.addEventListener('click', clearSelection);

  // Pagination.
  el.btnPrev.addEventListener('click', () => {
    if (state.page > 0) {
      state.page -= 1;
      load();
    }
  });
  el.btnNext.addEventListener('click', () => {
    if (currentPage && currentPage.last) return;
    state.page += 1;
    load();
  });

  // Row selection checkbox: toggle selection and stop the click bubbling so the
  // detail modal never opens. Handled separately from the button delegation.
  el.contactsBody.addEventListener('change', (event) => {
    const cb = event.target.closest('input.row-select');
    if (!cb || !el.contactsBody.contains(cb)) return;
    event.stopPropagation();
    toggleRowSelect(cb.dataset.id, cb.checked);
  });
  // A bare click on the checkbox must not bubble up to the row detail handler.
  el.contactsBody.addEventListener('click', (event) => {
    if (event.target.closest('input.row-select')) {
      event.stopPropagation();
      return;
    }
    const btn = event.target.closest('button[data-action]');
    if (btn && el.contactsBody.contains(btn)) {
      // Copy buttons live in the email cell — they carry data-copy, not data-id,
      // and must not bubble up to open the row detail.
      if (btn.dataset.action === 'copy') {
        event.stopPropagation();
        handleCopy(btn);
        return;
      }
      const id = btn.dataset.id;
      if (!id) return;
      if (btn.dataset.action === 'edit') {
        openEdit(id);
      } else if (btn.dataset.action === 'delete') {
        openDelete(id);
      } else if (btn.dataset.action === 'favorite') {
        toggleFavorite(id);
      } else if (btn.dataset.action === 'restore') {
        restoreFromTrash(id);
      } else if (btn.dataset.action === 'purge') {
        openPurge(id);
      }
      return;
    }
    const row = event.target.closest('tr[data-id]');
    if (row && el.contactsBody.contains(row)) {
      openDetail(row.dataset.id);
    }
  });

  // Contact form submit + cancel.
  el.contactForm.addEventListener('submit', submitForm);
  el.btnCancel.addEventListener('click', (event) => {
    event.preventDefault();
    closeContactModal();
  });

  // Photo: choose a file (validate + preview) / remove a chosen-or-saved photo.
  if (el.fieldPhoto) el.fieldPhoto.addEventListener('change', onPhotoChange);
  if (el.btnRemovePhoto)
    el.btnRemovePhoto.addEventListener('click', onRemovePhoto);

  // Tags: type + Enter/comma to add; preset buttons add a suggested tag;
  // delegated × on a chip removes it. Enter must not submit the form.
  if (el.fieldTagInput) {
    el.fieldTagInput.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ',') {
        event.preventDefault();
        addTag(el.fieldTagInput.value);
        el.fieldTagInput.value = '';
      }
    });
  }
  document.querySelectorAll('.tag-preset').forEach((btn) => {
    btn.addEventListener('click', () => addTag(btn.dataset.tag));
  });
  if (el.tagChips) {
    el.tagChips.addEventListener('click', (event) => {
      const btn = event.target.closest('button[data-tag]');
      if (!btn || !el.tagChips.contains(btn)) return;
      removeTag(btn.dataset.tag);
    });
  }

  // Clear a field's inline error as the user edits it.
  for (const name of ERROR_FIELDS) {
    const input = fieldInput(name);
    if (!input) continue;
    input.addEventListener('input', () => {
      const errNode = fieldError(name);
      if (errNode) errNode.textContent = '';
      input.removeAttribute('aria-invalid');
    });
  }

  // Contact modal: backdrop click closes; Esc handled natively by <dialog>.
  el.contactModal.addEventListener('click', (event) => {
    if (isBackdropClick(el.contactModal, event)) closeContactModal();
  });
  // Native dialog 'cancel' (Esc) — let it close; nothing extra needed,
  // but prevent the form from being submitted on Esc.
  el.contactModal.addEventListener('cancel', () => {
    /* default closes the dialog */
  });
  // Any close path (Esc, backdrop, Cancel, save) returns focus to the opener
  // and tears down photo state (incl. revoking any preview object URL).
  el.contactModal.addEventListener('close', () => {
    resetPhotoState();
    restoreTriggerFocus();
  });

  // Delete confirmation: openDelete / openPurge drive the shared confirmDialog()
  // (confirm-dialog.js), which owns its own confirm / cancel / Esc / backdrop /
  // focus-restore handling — no per-page wiring needed here.

  // Detail modal: Edit hands off to the edit modal; close / backdrop dismiss it.
  el.btnDetailEdit.addEventListener('click', () => {
    const id = detailId;
    closeDetailModal();
    if (id != null) openEdit(id);
  });
  el.btnDetailClose.addEventListener('click', closeDetailModal);
  el.detailModal.addEventListener('click', (event) => {
    if (isBackdropClick(el.detailModal, event)) closeDetailModal();
  });
  el.detailModal.addEventListener('close', restoreTriggerFocus);
}

/* ------------------------------------------------------------------ *
 * Bootstrap
 * ------------------------------------------------------------------ */

/** Show the signed-in username in the header and wire the logout button. */
function setupAuthUi() {
  const user = auth.user();
  if (user && user.username && el.currentUser) {
    el.currentUser.textContent = user.username;
    el.currentUser.hidden = false;
  }
  // The user-management and activity-log links are only shown to admins
  // (and the APIs enforce it).
  if (user && user.role === 'ADMIN') {
    if (el.linkUsers) el.linkUsers.hidden = false;
    if (el.linkActivity) el.linkActivity.hidden = false;
    // Make clear an admin is viewing everyone's contacts, not just their own.
    if (el.contactsScope) {
      el.contactsScope.textContent = 'Admin view — showing all users’ contacts.';
      el.contactsScope.hidden = false;
    }
  }
  if (el.btnLogout) {
    el.btnLogout.addEventListener('click', () => auth.logout());
  }
}

function init() {
  // Sync state with any defaults already present in the markup.
  if (el.pageSize && el.pageSize.value) {
    const size = parseInt(el.pageSize.value, 10);
    if (!Number.isNaN(size)) state.size = size;
  }
  if (el.sortSelect && el.sortSelect.value) {
    state.sort = el.sortSelect.value;
  }

  setupAuthUi();
  wireEvents();
  updateThemeButton();
  renderTagChips();
  applyTrashViewState();
  refreshBulkBar();
  populateTagFilter();
  load();
}

// Script is loaded with `defer`, so the DOM is parsed; init immediately,
// but guard for the (unlikely) case it's pulled in before DOMContentLoaded.
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init, { once: true });
} else {
  init();
}
