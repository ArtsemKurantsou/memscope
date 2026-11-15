// The "compare with" selector: which snapshot of the session the current one is diffed against.
import { el, formatTime } from './util.js';

export function createCompare({ box, select, onChange }) {
  let session = null;
  let current = null;  // id of the open snapshot
  let base = null;     // id of the snapshot compared against, or null
  let auto = false;    // the base follows "the previous milestone" of the open snapshot
  let emitted = null;

  const oks = () => (session ? session.snapshots.filter((s) => s.status === 'OK') : []);
  const others = () => oks().filter((s) => s.id !== current);
  const previous = (id) => {
    const list = oks();
    const i = list.findIndex((s) => s.id === id);
    return i > 0 ? list[i - 1] : null;
  };

  function emit() {
    if (base === emitted) return;
    emitted = base;
    onChange(base ? session.snapshots.find((s) => s.id === base) : null);
  }

  function render() {
    const list = others();
    if (base && !list.some((s) => s.id === base)) { base = null; auto = false; }
    select.replaceChildren(el('option', { value: '' }, 'none'),
      ...list.map((s) => el('option', { value: s.id }, `${session.snapshots.indexOf(s) + 1}. ${s.label} · ${formatTime(s.takenAtMs)}`)));
    select.value = base || '';
    select.disabled = list.length === 0;
    box.checked = base != null;
    box.disabled = list.length === 0;
    const why = list.length === 0 ? 'Needs a second snapshot in this session' : '';
    box.title = select.title = why;
  }

  /** Called whenever the session's snapshot list changes (also while a recording adds snapshots). */
  function setSession(s) {
    const changed = !session || !s || session.id !== s.id;
    session = s;
    if (changed) { base = null; auto = false; }
    render();
    emit();
  }

  /** The open snapshot changed: an automatic base moves to its predecessor. */
  function setCurrent(id) {
    current = id;
    if (auto) {
      const p = previous(id);
      base = p ? p.id : null;
      if (!p) auto = false;
    }
    render();
    emit();
  }

  box.addEventListener('change', () => {
    if (!box.checked) { base = null; auto = false; } else {
      const startup = session && session.mode === 'STARTUP';
      const p = previous(current);
      const pick = p || others()[0];
      base = pick ? pick.id : null;
      auto = startup && p != null;
    }
    render();
    emit();
  });
  select.addEventListener('change', () => {
    base = select.value || null;
    auto = false;
    render();
    emit();
  });

  return { setSession, setCurrent, get base() { return base; } };
}
