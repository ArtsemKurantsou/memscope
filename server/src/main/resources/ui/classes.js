// Classes tab: a sortable, filterable class histogram (10-30k rows, virtualised) that gains
// delta columns when a base snapshot is selected. Clicking a class shows its instances in the tree.
import { el, formatBytes, formatInt, formatDeltaBytes, formatDeltaInt, splitClassName, debounce } from './util.js';

const ROW = 26;
const OVERSCAN = 12;

/** The sort keys, with their header text and the value a row sorts by. */
const COLUMNS = [
  { key: 'name', text: 'Class', numeric: false },
  { key: 'count', text: 'Instances', numeric: true },
  { key: 'shallow', text: 'Shallow', numeric: true },
  { key: 'retained', text: 'Retained', numeric: true },
  { key: 'dCount', text: 'Δ Instances', numeric: true, diff: true },
  { key: 'dShallow', text: 'Δ Shallow', numeric: true, diff: true },
  { key: 'dRetained', text: 'Δ Retained', numeric: true, diff: true },
];

/**
 * One table row per class: the numbers of the open snapshot and, with a diff, the changes since the base.
 * `hideSystem` picks the app-heap numbers and drops classes without app instances. A class that only
 * the base had gets zero numbers and negative deltas (`gone`); one the base lacked is `isNew`.
 * Pure: no DOM.
 */
export function buildRows(classes, diff, hideSystem) {
  const rows = [];
  const seen = new Set();
  const pick = (all, app) => (hideSystem ? app : all);
  for (const c of classes) {
    const count = pick(c.instanceCount, c.appInstanceCount);
    const d = diff ? diff.classByName.get(c.className) : null;
    if (diff) seen.add(c.className);
    // Unchanged classes are not in the diff: their "before" equals the current numbers.
    const before = d
      ? { count: pick(d.countBefore, d.appCountBefore), shallow: pick(d.shallowBefore, d.appShallowBefore), retained: pick(d.retainedBefore, d.appRetainedBefore) }
      : { count, shallow: pick(c.shallowBytes, c.appShallowBytes), retained: pick(c.retainedBytes, c.appRetainedBytes) };
    const row = {
      className: c.className, isApp: c.isAppCode, count,
      shallow: pick(c.shallowBytes, c.appShallowBytes), retained: pick(c.retainedBytes, c.appRetainedBytes),
      dCount: 0, dShallow: 0, dRetained: 0, isNew: false, gone: false,
    };
    if (hideSystem && count === 0 && (!diff || before.count === 0)) continue;
    if (diff) {
      row.dCount = row.count - before.count;
      row.dShallow = row.shallow - before.shallow;
      row.dRetained = row.retained - before.retained;
      row.isNew = before.count === 0 && row.count > 0;
      row.gone = row.count === 0 && before.count > 0;
    }
    rows.push(row);
  }
  if (diff) {
    for (const d of diff.classes) {
      if (seen.has(d.className)) continue;
      const before = {
        count: pick(d.countBefore, d.appCountBefore), shallow: pick(d.shallowBefore, d.appShallowBefore),
        retained: pick(d.retainedBefore, d.appRetainedBefore),
      };
      if (before.count === 0) continue; // nothing of it in either snapshot (system only, hidden)
      rows.push({
        className: d.className, isApp: d.isAppCode, count: 0, shallow: 0, retained: 0,
        dCount: -before.count, dShallow: -before.shallow, dRetained: -before.retained, isNew: false, gone: true,
      });
    }
  }
  return rows;
}

const COLLATOR = new Intl.Collator();

/** Substring filter (case-insensitive) and sort; ties keep the class name order so the result is stable. */
export function sortAndFilter(rows, { filter, key, dir }) {
  const needle = filter.trim().toLowerCase();
  const out = needle ? rows.filter((r) => r.className.toLowerCase().includes(needle)) : rows.slice();
  const sign = dir === 'asc' ? 1 : -1;
  if (key === 'name') {
    out.sort((a, b) => sign * COLLATOR.compare(a.className, b.className));
  } else {
    out.sort((a, b) => sign * (a[key] - b[key]) || (a.className < b.className ? -1 : a.className > b.className ? 1 : 0));
  }
  return out;
}

export function createClasses({ container, getOptions, onPickClass }) {
  let rows = [];        // buildRows output
  let shown = [];       // filtered and sorted
  let diff = null;      // indexed DiffResult or null
  let message = null;   // Node shown instead of the table (loading / error)
  let sort = { key: 'retained', dir: 'desc' };
  let userSorted = false;
  let filter = '';
  let maxDelta = 1;
  let source = null;    // { classes } of the open snapshot

  const filterInput = el('input', { type: 'search', placeholder: 'Filter classes (substring)', 'aria-label': 'Filter classes', autocomplete: 'off', class: 'cls-filter' });
  const countEl = el('span', { class: 'muted cls-count' });
  const toolbar = el('div', { class: 'cls-toolbar' }, filterInput, countEl);
  const head = el('div', { class: 'cls-head', role: 'row' });
  const spacer = el('div', { class: 'cls-spacer' });
  const scroller = el('div', { class: 'cls-scroll', tabindex: '-1' }, spacer);
  const msg = el('div', { class: 'cls-message', hidden: true });
  container.replaceChildren(toolbar, head, scroller, msg);

  function headCell(col) {
    const active = sort.key === col.key;
    const b = el('button', {
      type: 'button', class: `cls-h c-${col.key}${col.numeric ? ' r' : ''}${active ? ' active' : ''}`, role: 'columnheader',
      'aria-sort': active ? (sort.dir === 'asc' ? 'ascending' : 'descending') : 'none',
      title: col.diff ? 'Change since the compared snapshot' : `Sort by ${col.text.toLowerCase()}`,
      onclick: () => { userSorted = true; setSort(col.key); },
    }, col.text, active ? el('span', { class: 'arrow', 'aria-hidden': 'true' }, sort.dir === 'asc' ? ' ▲' : ' ▼') : null);
    return b;
  }

  function renderHead() {
    container.classList.toggle('diff', diff != null);
    head.replaceChildren(...COLUMNS.filter((c) => !c.diff || diff).map(headCell));
  }

  function setSort(key) {
    sort = sort.key === key ? { key, dir: sort.dir === 'desc' ? 'asc' : 'desc' } : { key, dir: key === 'name' ? 'asc' : 'desc' };
    recompute(true);
  }

  function recompute(resetScroll = false) {
    shown = sortAndFilter(rows, { filter, ...sort });
    maxDelta = 1;
    if (diff) for (const r of shown) maxDelta = Math.max(maxDelta, Math.abs(r.dRetained));
    countEl.textContent = `${formatInt(shown.length)} of ${formatInt(rows.length)} classes`;
    renderHead();
    spacer.style.height = shown.length * ROW + 'px';
    if (resetScroll) scroller.scrollTop = 0; // a new diff or a hide-system toggle keeps the reader's place
    paint(true);
  }

  function rowEl(r, i) {
    const sp = splitClassName(r.className);
    const e = el('div', {
      class: `cls-row ${r.isApp ? 'mine' : 'fw'}${r.gone ? ' gone' : ''}`, role: 'row', tabindex: '0',
      style: `top:${i * ROW}px`, title: `${r.className}\nClick to list its instances in the tree`,
    });
    e.dataset.cls = r.className;
    const name = el('div', { class: 'c-name' }, el('span', { class: 'cn' }, sp.simple), sp.pkg ? el('span', { class: 'pkg' }, sp.pkg) : null,
      r.isNew ? el('span', { class: 'new-badge', title: 'No instances in the compared snapshot' }, 'new') : null,
      r.gone ? el('span', { class: 'gone-badge', title: 'Present in the compared snapshot, gone in this one' }, 'gone') : null);
    const num = (cls, text, extra) => el('div', { class: `${cls} r num${extra ? ' ' + extra : ''}` }, text);
    e.append(name, num('c-count', formatInt(r.count)), num('c-shallow', formatBytes(r.shallow)), num('c-retained', formatBytes(r.retained)));
    if (diff) {
      const sign = (n) => (n > 0 ? 'up' : n < 0 ? 'down' : 'zero');
      e.append(num('c-dCount', formatDeltaInt(r.dCount), sign(r.dCount)), num('c-dShallow', formatDeltaBytes(r.dShallow), sign(r.dShallow)));
      const w = Math.min(100, (Math.abs(r.dRetained) * 100) / maxDelta);
      e.append(el('div', { class: `c-dRetained r num ${sign(r.dRetained)}` },
        el('span', { class: 'dv', 'aria-hidden': 'true' }, el('i', { style: `width:${w / 2}%` })), el('span', { class: 'dval' }, formatDeltaBytes(r.dRetained))));
    }
    return e;
  }

  let painted = '';

  function paint(force) {
    const h = scroller.clientHeight || 400;
    const first = Math.max(0, Math.floor(scroller.scrollTop / ROW) - OVERSCAN);
    const last = Math.min(shown.length, Math.ceil((scroller.scrollTop + h) / ROW) + OVERSCAN);
    const stamp = `${first}:${last}:${shown.length}`;
    if (!force && stamp === painted) return;
    painted = stamp;
    const focused = document.activeElement?.closest?.('.cls-row')?.dataset.cls;
    const frag = document.createDocumentFragment();
    for (let i = first; i < last; i++) frag.append(rowEl(shown[i], i));
    scroller.replaceChildren(spacer, frag);
    if (focused) scroller.querySelector(`.cls-row[data-cls="${CSS.escape(focused)}"]`)?.focus({ preventScroll: true });
    if (shown.length === 0 && !message) {
      scroller.append(el('div', { class: 'cls-empty muted' }, rows.length === 0 ? 'No classes to show.' : 'No class matches the filter.'));
    }
  }

  scroller.addEventListener('scroll', () => requestAnimationFrame(paint), { passive: true });
  scroller.addEventListener('click', (ev) => { const r = ev.target.closest('.cls-row'); if (r) onPickClass(r.dataset.cls); });
  scroller.addEventListener('keydown', (ev) => {
    const r = ev.target.closest('.cls-row');
    if (!r) return;
    if (ev.key === 'Enter') { onPickClass(r.dataset.cls); ev.preventDefault(); return; }
    const next = ev.key === 'ArrowDown' ? r.nextElementSibling : ev.key === 'ArrowUp' ? r.previousElementSibling : null;
    if (next && next.classList.contains('cls-row')) { next.focus(); ev.preventDefault(); }
  });
  new ResizeObserver(() => paint()).observe(scroller);
  filterInput.addEventListener('input', debounce(() => { filter = filterInput.value; recompute(true); }, 120));

  function rebuild(resetScroll = false) {
    if (!source) { rows = []; shown = []; renderHead(); paint(true); return; }
    rows = buildRows(source.classes, diff, getOptions().hideSystem);
    recompute(resetScroll);
  }

  /** Shows `node` (spinner / error) instead of the table, or the table again for null. */
  function setMessage(node) {
    message = node;
    msg.hidden = !node;
    if (node) msg.replaceChildren(node);
    toolbar.hidden = head.hidden = scroller.hidden = !!node;
  }

  return {
    /** The class table of the open snapshot (ClassStat[]), or null while it loads. */
    setClasses(classes) { source = classes ? { classes } : null; setMessage(null); rebuild(true); },
    /** The indexed diff against the base snapshot, or null for no comparison. */
    setDiff(d) {
      diff = d;
      if (!userSorted) sort = { key: d ? 'dRetained' : 'retained', dir: 'desc' };
      else if (!d && sort.key.startsWith('d')) sort = { key: 'retained', dir: 'desc' };
      rebuild();
    },
    /** Hide-system changed: the numbers, the visible classes and their order change. */
    refresh: rebuild,
    setMessage,
    resetSort() { userSorted = false; },
    focusFilter() { filterInput.focus(); },
  };
}
