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

/* ------------------------------------------------------------------ *
 * 2. Element references
 * ------------------------------------------------------------------ */

const $ = (id) => document.getElementById(id);

const el = {
  // Header
  btnNew: $('btn-new'),
  // Toolbar
  searchInput: $('search-input'),
  btnClearSearch: $('btn-clear-search'),
  pageSize: $('page-size'),
  sortSelect: $('sort-select'),
  tagFilter: $('tag-filter'),
  btnImport: $('btn-import'),
  importFile: $('import-file'),
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
  // Delete modal
  confirmModal: $('confirm-modal'),
  confirmText: $('confirm-text'),
  btnConfirmDelete: $('btn-confirm-delete'),
  btnCancelDelete: $('btn-cancel-delete'),
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

  let response;
  try {
    response = await fetch(url, opts);
  } catch (networkErr) {
    const err = new Error('Network error — could not reach the server.');
    err.status = 0;
    err.body = null;
    err.cause = networkErr;
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

/** Initials from first/last name (e.g. "Ada Lovelace" -> "AL"), uppercased. */
function initials(contact) {
  const first = (contact.firstName || '').trim();
  const last = (contact.lastName || '').trim();
  const text = (first.charAt(0) + last.charAt(0)).toUpperCase();
  return text || '?';
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
function makeAvatarCell(contact) {
  const td = document.createElement('td');
  td.className = 'cell-avatar';
  td.setAttribute('data-label', 'Photo');

  if (contact.photoUrl) {
    const img = document.createElement('img');
    img.className = 'avatar';
    img.src = contact.photoUrl;
    img.alt = '';
    td.appendChild(img);
  } else {
    const span = document.createElement('span');
    span.className = 'avatar avatar--placeholder';
    span.setAttribute('aria-hidden', 'true');
    span.textContent = initials(contact);
    td.appendChild(span);
  }
  return td;
}

/** Build the Edit/Delete action cell for a row. */
function makeActionsCell(contact) {
  const td = document.createElement('td');
  td.className = 'cell-actions';
  td.setAttribute('data-label', 'Actions');

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
    makeStarCell(contact),
    makeAvatarCell(contact),
    makeCell('Name', fullName(contact)),
    makeCell('Email', display(contact.email)),
    makeCell('Phone', display(contact.phone)),
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
    const page = await listContacts(state);
    currentPage = page;

    const content = Array.isArray(page.content) ? page.content : [];
    renderRows(content);

    const isEmpty = page.empty !== undefined ? page.empty : content.length === 0;
    el.emptyState.hidden = !isEmpty;

    updatePagination(page);
  } catch (err) {
    currentPage = null;
    renderRows([]);
    el.emptyState.hidden = true;
    el.pageInfo.textContent = '';
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
 */
function toast(message, variant = 'success') {
  const node = el.toast;
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
  el.photoPreview.src = url;
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

/** Render the staged tags as removable chips inside #tag-chips (XSS-safe). */
function renderTagChips() {
  const wrap = el.tagChips;
  if (!wrap) return;
  wrap.replaceChildren();
  for (const tag of selectedTags) {
    const chip = document.createElement('span');
    chip.className = 'tag-chip tag-chip--removable';

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

  // Carry the favourite flag through so a PUT (full replace) preserves it.
  currentFavorite = !!contact.favorite;

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

/** Id of the contact currently queued for deletion. */
let pendingDeleteId = null;

function openConfirmModal() {
  if (typeof el.confirmModal.showModal === 'function') {
    el.confirmModal.showModal();
  } else {
    el.confirmModal.setAttribute('open', '');
  }
}

function closeConfirmModal() {
  if (typeof el.confirmModal.close === 'function') {
    el.confirmModal.close();
  } else {
    el.confirmModal.removeAttribute('open');
  }
}

function openDelete(id) {
  lastTrigger = document.activeElement;
  pendingDeleteId = id;
  const row =
    currentPage &&
    Array.isArray(currentPage.content) &&
    currentPage.content.find((c) => String(c.id) === String(id));
  const name = row ? fullName(row) : 'this contact';

  // textContent keeps the name XSS-safe.
  el.confirmText.textContent = `Delete ${name}? This cannot be undone.`;
  openConfirmModal();
  el.btnConfirmDelete.focus();
}

async function confirmDelete() {
  if (pendingDeleteId == null) return;
  const id = pendingDeleteId;

  el.btnConfirmDelete.disabled = true;
  el.btnCancelDelete.disabled = true;

  try {
    await deleteContact(id);
    closeConfirmModal();
    toast('Contact deleted.', 'success');

    // If we just removed the last row on a non-first page, step back one page.
    const remaining =
      currentPage && typeof currentPage.numberOfElements === 'number'
        ? currentPage.numberOfElements - 1
        : null;
    if (remaining !== null && remaining <= 0 && state.page > 0) {
      state.page -= 1;
    }
    load();
    populateTagFilter();
  } catch (err) {
    closeConfirmModal();
    if (err.status === 404) {
      toast(err.message || 'Contact already deleted.', 'error');
    } else {
      toast(err.message || 'Failed to delete contact.', 'error');
    }
    load();
  } finally {
    pendingDeleteId = null;
    el.btnConfirmDelete.disabled = false;
    el.btnCancelDelete.disabled = false;
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

  // Row actions via event delegation (no inline onclick).
  el.contactsBody.addEventListener('click', (event) => {
    const btn = event.target.closest('button[data-action]');
    if (!btn || !el.contactsBody.contains(btn)) return;
    const id = btn.dataset.id;
    if (!id) return;
    if (btn.dataset.action === 'edit') {
      openEdit(id);
    } else if (btn.dataset.action === 'delete') {
      openDelete(id);
    } else if (btn.dataset.action === 'favorite') {
      toggleFavorite(id);
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

  // Delete modal: confirm / cancel / backdrop.
  el.btnConfirmDelete.addEventListener('click', confirmDelete);
  el.btnCancelDelete.addEventListener('click', (event) => {
    event.preventDefault();
    pendingDeleteId = null;
    closeConfirmModal();
  });
  el.confirmModal.addEventListener('click', (event) => {
    if (isBackdropClick(el.confirmModal, event)) {
      pendingDeleteId = null;
      closeConfirmModal();
    }
  });
  el.confirmModal.addEventListener('cancel', () => {
    pendingDeleteId = null;
  });
  // Any close path returns focus to the opener (or #btn-new if the row is gone).
  el.confirmModal.addEventListener('close', restoreTriggerFocus);
}

/* ------------------------------------------------------------------ *
 * Bootstrap
 * ------------------------------------------------------------------ */

function init() {
  // Sync state with any defaults already present in the markup.
  if (el.pageSize && el.pageSize.value) {
    const size = parseInt(el.pageSize.value, 10);
    if (!Number.isNaN(size)) state.size = size;
  }
  if (el.sortSelect && el.sortSelect.value) {
    state.sort = el.sortSelect.value;
  }

  wireEvents();
  renderTagChips();
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
