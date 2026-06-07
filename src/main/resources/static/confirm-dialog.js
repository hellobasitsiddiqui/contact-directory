/* ------------------------------------------------------------------ *
 * Reusable confirmation dialog.
 *
 * Drop-in, accessible replacement for window.confirm() for destructive
 * actions. Shared across pages (Contacts, Users) — load it with a plain
 * <script defer> before the page's own script, then call:
 *
 *     const ok = await confirmDialog({
 *       title: 'Delete user',
 *       message: 'Delete "alice"? This cannot be undone.',
 *       confirmLabel: 'Delete',
 *       danger: true,
 *     });
 *     if (!ok) return;
 *
 * Returns a Promise<boolean> that resolves true on confirm, false on
 * cancel / Esc / backdrop click. A single <dialog> is created lazily and
 * reused; styling rides on the existing .modal classes in styles.css.
 * ------------------------------------------------------------------ */
(function () {
  'use strict';

  /** The lazily-created singleton dialog and its parts, or null until first use. */
  let dialogEl = null;
  let titleEl = null;
  let textEl = null;
  let confirmBtn = null;
  let cancelBtn = null;

  /** The currently-open dialog's resolve fn (null when closed). */
  let resolveCurrent = null;
  /** The element focused before the dialog opened, restored on close. */
  let lastTrigger = null;

  function buildDialog() {
    dialogEl = document.createElement('dialog');
    dialogEl.className = 'modal modal--confirm';
    dialogEl.setAttribute('aria-labelledby', 'confirm-dialog-title');
    dialogEl.setAttribute('aria-describedby', 'confirm-dialog-text');

    const form = document.createElement('div');
    form.className = 'modal__form';

    const header = document.createElement('header');
    header.className = 'modal__header';
    titleEl = document.createElement('h2');
    titleEl.id = 'confirm-dialog-title';
    titleEl.className = 'modal__title';
    header.appendChild(titleEl);

    const body = document.createElement('div');
    body.className = 'modal__body';
    textEl = document.createElement('p');
    textEl.id = 'confirm-dialog-text';
    textEl.className = 'confirm-text';
    body.appendChild(textEl);

    const footer = document.createElement('footer');
    footer.className = 'modal__footer';
    cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'btn btn--secondary';
    confirmBtn = document.createElement('button');
    confirmBtn.type = 'button';
    footer.appendChild(cancelBtn);
    footer.appendChild(confirmBtn);

    form.appendChild(header);
    form.appendChild(body);
    form.appendChild(footer);
    dialogEl.appendChild(form);
    document.body.appendChild(dialogEl);

    // Confirm / cancel paths. Esc and backdrop click both resolve to false.
    confirmBtn.addEventListener('click', () => settle(true));
    cancelBtn.addEventListener('click', () => settle(false));
    dialogEl.addEventListener('click', (event) => {
      if (event.target === dialogEl) settle(false);
    });
    // Native <dialog> 'cancel' fires on Esc — resolve false, let it close.
    dialogEl.addEventListener('cancel', () => {
      if (resolveCurrent) {
        const resolve = resolveCurrent;
        resolveCurrent = null;
        resolve(false);
      }
      restoreFocus();
    });
  }

  function restoreFocus() {
    const target =
      lastTrigger && document.body.contains(lastTrigger) ? lastTrigger : null;
    lastTrigger = null;
    if (target && typeof target.focus === 'function') target.focus();
  }

  /** Resolve the pending promise, close the dialog, and restore focus. */
  function settle(result) {
    const resolve = resolveCurrent;
    resolveCurrent = null;
    if (typeof dialogEl.close === 'function') {
      dialogEl.close();
    } else {
      dialogEl.removeAttribute('open');
    }
    restoreFocus();
    if (resolve) resolve(result);
  }

  /**
   * Open the confirmation dialog. Returns Promise<boolean>.
   *
   * @param {Object} opts
   * @param {string} [opts.title]        Heading text (default "Confirm").
   * @param {string} [opts.message]      Body text (set via textContent — XSS-safe).
   * @param {string} [opts.confirmLabel] Confirm button label (default "Confirm").
   * @param {string} [opts.cancelLabel]  Cancel button label (default "Cancel").
   * @param {boolean} [opts.danger]      Style the confirm button as destructive.
   */
  function confirmDialog(opts) {
    const options = opts || {};
    if (!dialogEl) buildDialog();

    // If somehow already open, resolve the previous call as cancelled.
    if (resolveCurrent) settle(false);

    lastTrigger = document.activeElement;

    titleEl.textContent = options.title || 'Confirm';
    textEl.textContent = options.message || '';
    confirmBtn.textContent = options.confirmLabel || 'Confirm';
    cancelBtn.textContent = options.cancelLabel || 'Cancel';
    confirmBtn.className =
      'btn ' + (options.danger ? 'btn--danger' : 'btn--primary');

    return new Promise((resolve) => {
      resolveCurrent = resolve;
      if (typeof dialogEl.showModal === 'function') {
        dialogEl.showModal();
      } else {
        dialogEl.setAttribute('open', '');
      }
      // Focus the confirm button so Enter confirms and Tab stays trapped.
      confirmBtn.focus();
    });
  }

  // Expose as a global for the per-page scripts (no module system here).
  window.confirmDialog = confirmDialog;
})();
