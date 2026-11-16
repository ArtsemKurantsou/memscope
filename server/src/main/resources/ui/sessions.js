// "Sessions" dialog: the recorded sessions with their size on disk, to open or delete; and a small confirm dialog.
import * as api from './api.js';
import { el, formatBytes } from './util.js';

/** A modal yes/no question; resolves true only for the confirm button. */
export function confirmDialog(title, text, confirmLabel) {
  return new Promise((resolve) => {
    let ok = false;
    const dlg = el('dialog', { class: 'dialog confirm', 'aria-labelledby': 'confirm-title' });
    dlg.append(el('form', { method: 'dialog' },
      el('h2', { id: 'confirm-title' }, title),
      el('p', null, text),
      el('div', { class: 'jactions dlg-actions' },
        el('button', { class: 'btn', type: 'submit', value: 'cancel' }, 'Cancel'),
        el('button', { class: 'btn danger', type: 'submit', value: 'ok', onclick: () => { ok = true; } }, confirmLabel))));
    dlg.addEventListener('close', () => { dlg.remove(); resolve(ok); });
    document.body.append(dlg);
    dlg.showModal();
  });
}

const formatDate = (ms) => new Date(ms).toLocaleString([], { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' });

/**
 * Opens the Sessions dialog. `onOpen(session)` shows a session (the dialog closes); `onDeleted(sessionId)` runs after a
 * session was deleted (the dialog stays open and refreshes).
 */
export function openSessionsDialog({ currentId, onOpen, onDeleted }) {
  const dlg = el('dialog', { class: 'dialog sessions', 'aria-labelledby': 'sessions-title' });
  const body = el('div', { class: 'sessions-body' }, el('span', { class: 'spinner' }), ' Loading sessions…');
  const msg = el('div', { class: 'ferr', role: 'alert' });

  async function deleteSession(s) {
    const ok = await confirmDialog('Delete this session?',
      `${s.packageName}, ${formatDate(s.createdAtMs)}: ${s.snapshots.length} snapshot${s.snapshots.length === 1 ? '' : 's'}. ` +
      `This permanently deletes ${formatBytes(s.diskBytes)} on disk (heap dumps and analysis files).`, `Delete ${formatBytes(s.diskBytes)}`);
    if (!ok) return;
    msg.replaceChildren();
    try {
      await api.deleteSession(s.id);
      onDeleted(s.id);
    } catch (e) {
      msg.replaceChildren(el('strong', null, e.message || String(e)), e.hint ? ' ' + e.hint : '');
    }
    await load();
  }

  async function load() {
    let list;
    try { list = await api.sessions(); } catch (e) {
      body.replaceChildren(el('div', { class: 'error-box' }, el('strong', null, e.message || String(e)), e.hint ? el('p', null, e.hint) : null));
      return;
    }
    if (!list.length) { body.replaceChildren(el('p', { class: 'muted' }, 'No sessions recorded yet.')); return; }
    const total = list.reduce((n, s) => n + (s.diskBytes || 0), 0);
    body.replaceChildren(
      el('p', { class: 'muted' }, `${list.length} session${list.length === 1 ? '' : 's'}, ${formatBytes(total)} on disk.`),
      el('table', { class: 'sessions-table' },
        el('thead', null, el('tr', null, el('th', null, 'App'), el('th', null, 'Date'), el('th', { class: 'num' }, 'Snapshots'),
          el('th', { class: 'num' }, 'On disk'), el('th', null, ''))),
        el('tbody', null, list.map((s) => el('tr', { class: s.id === currentId ? 'current' : null },
          el('td', { class: 'mono' }, s.packageName, s.mode === 'STARTUP' ? el('span', { class: 'muted' }, ' · startup') : null,
            s.state === 'ACTIVE' ? el('span', { class: 'muted' }, ' · active') : null),
          el('td', null, formatDate(s.createdAtMs)),
          el('td', { class: 'num' }, String(s.snapshots.length)),
          el('td', { class: 'num' }, formatBytes(s.diskBytes)),
          el('td', { class: 'row-actions' },
            el('button', { class: 'btn small', type: 'button', disabled: s.id === currentId, onclick: () => { dlg.close(); onOpen(s); } }, s.id === currentId ? 'Open (shown)' : 'Open'),
            el('button', { class: 'btn small danger', type: 'button', onclick: () => deleteSession(s) }, 'Delete')))))));
  }

  dlg.append(
    el('h2', { id: 'sessions-title' }, 'Sessions'),
    el('p', { class: 'muted' }, 'Each snapshot takes its dump size plus about 1.5× that for the analysis cache.'),
    msg, body,
    el('div', { class: 'jactions dlg-actions' }, el('button', { class: 'btn', type: 'button', onclick: () => dlg.close() }, 'Close')));
  dlg.addEventListener('close', () => dlg.remove());
  document.body.append(dlg);
  dlg.showModal();
  load();
}
