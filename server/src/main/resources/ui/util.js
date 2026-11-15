// Pure helpers (no DOM access except `el`): formatting, ref handling, small utilities.

const UNITS = ['B', 'KB', 'MB', 'GB', 'TB'];

/** 0 -> "0 B", 1536 -> "1.5 KB". One decimal above bytes; 1024-based. */
export function formatBytes(n) {
  if (n == null || Number.isNaN(n)) return '–';
  const neg = n < 0;
  let v = Math.abs(n);
  let u = 0;
  while (v >= 1024 && u < UNITS.length - 1) { v /= 1024; u++; }
  // 1023.96 KB would print as "1024.0 KB": promote.
  if (u > 0 && u < UNITS.length - 1 && Math.round(v * 10) / 10 >= 1024) { v /= 1024; u++; }
  const s = u === 0 ? String(Math.round(v)) : v.toFixed(1);
  return (neg ? '-' : '') + s + ' ' + UNITS[u];
}

/** Percentage with one decimal; tiny non-zero values show "<0.1". */
export function formatPercent(p) {
  if (p == null || Number.isNaN(p)) return '–';
  if (p > 0 && p < 0.05) return '<0.1%';
  return p.toFixed(1) + '%';
}

export function formatInt(n) { return Number(n).toLocaleString('en-US'); }

export function formatTime(ms) {
  const d = new Date(ms);
  return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' });
}

/** "java.util.HashMap$Node" -> { simple: "HashMap$Node", pkg: "java.util" }; "byte[]" -> { simple: "byte[]", pkg: "" }. */
export function splitClassName(name) {
  if (!name) return { simple: '', pkg: '' };
  const dot = name.lastIndexOf('.');
  if (dot < 0) return { simple: name, pkg: '' };
  return { simple: name.slice(dot + 1), pkg: name.slice(0, dot) };
}

/** Ref kinds as the server writes them: root, cat:<k>, thr:<n>, obj:<n>, grp:<parent>|<class>. */
export function refKind(ref) {
  if (ref === 'root') return 'root';
  const i = ref.indexOf(':');
  return i < 0 ? 'unknown' : ref.slice(0, i);
}

/** Refs the `object` endpoint accepts (it answers 400 for categories, groups and root). */
export function hasObjectDetails(ref) {
  const k = refKind(ref);
  return k === 'obj' || k === 'thr';
}

/** Size shown for a row: app-heap bytes when system heap is hidden, else retained bytes. */
export function rowSize(row, hideSystem) { return hideSystem ? row.appHeapBytes : row.retainedBytes; }

export function rowPercent(row, hideSystem, summary) {
  if (!hideSystem) return row.percentOfHeap;
  return summary && summary.appHeapBytes > 0 ? (row.appHeapBytes * 100) / summary.appHeapBytes : 0;
}

export function rowVisible(row, hideSystem) { return !hideSystem || row.appHeapBytes > 0; }

/** Stable sort, largest first, by the size currently displayed. */
export function sortRows(rows, hideSystem) {
  return rows
    .map((r, i) => [r, i])
    .sort((a, b) => (rowSize(b[0], hideSystem) - rowSize(a[0], hideSystem)) || (a[1] - b[1]))
    .map((x) => x[0]);
}

/** Small DOM builder: el('div', {class:'x', onclick}, child, 'text'). */
export function el(tag, attrs, ...children) {
  const e = document.createElement(tag);
  if (attrs) {
    for (const [k, v] of Object.entries(attrs)) {
      if (v == null || v === false) continue;
      if (k === 'class') e.className = v;
      else if (k.startsWith('on') && typeof v === 'function') e.addEventListener(k.slice(2), v);
      else if (k === 'dataset') Object.assign(e.dataset, v);
      else e.setAttribute(k, v === true ? '' : String(v));
    }
  }
  for (const c of children.flat(Infinity)) {
    if (c == null || c === false) continue;
    e.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return e;
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

export function debounce(fn, ms) {
  let t;
  const d = (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
  d.cancel = () => clearTimeout(t);
  return d;
}

/** try/catch wrapped localStorage for non-essential preferences. */
export const prefs = {
  get(key, dflt) {
    try { const v = localStorage.getItem('memscope.' + key); return v == null ? dflt : v === '1'; } catch { return dflt; }
  },
  set(key, on) { try { localStorage.setItem('memscope.' + key, on ? '1' : '0'); } catch { /* ignore */ } },
};

/** Runs `start()` unless `holder.loading` is already in flight; every concurrent caller gets the same promise. */
export function shareInFlight(holder, start) {
  if (!holder.loading) holder.loading = start().finally(() => { holder.loading = null; });
  return holder.loading;
}

// ---------------------------------------------------------------- snapshot diff

/** "+1.5 KB", "−2.0 MB" (real minus sign), "0" for no change. */
export function formatDeltaBytes(n) {
  if (n == null || Number.isNaN(n)) return '–';
  if (n === 0) return '0';
  return (n > 0 ? '+' : '−') + formatBytes(Math.abs(n));
}

/** "+1,234", "−7", "0". */
export function formatDeltaInt(n) {
  if (n == null || Number.isNaN(n)) return '–';
  if (n === 0) return '0';
  return (n > 0 ? '+' : '−') + formatInt(Math.abs(n));
}

/**
 * Index of a DiffResult for lookups: classes by name, roots by label. `hideSystem` picks the numbers a
 * view shows: app-heap bytes (default) or all reachable bytes.
 */
/** The server leaves out numbers that are 0 and `isAppCode: false`: fill them in. */
const CLASS_DEFAULTS = {
  isAppCode: false, countBefore: 0, countAfter: 0, shallowBefore: 0, shallowAfter: 0, retainedBefore: 0, retainedAfter: 0,
  appCountBefore: 0, appCountAfter: 0, appShallowBefore: 0, appShallowAfter: 0, appRetainedBefore: 0, appRetainedAfter: 0,
};
const ROOT_DEFAULTS = { retainedBefore: 0, retainedAfter: 0, appRetainedBefore: 0, appRetainedAfter: 0 };

export function indexDiff(diff) {
  const classes = diff.classes.map((c) => ({ ...CLASS_DEFAULTS, ...c }));
  const roots = diff.roots.map((r) => ({ ...ROOT_DEFAULTS, ...r }));
  return {
    ...diff,
    classes,
    roots,
    classByName: new Map(classes.map((c) => [c.className, c])),
    rootByLabel: new Map(roots.map((r) => [r.label, r])),
  };
}

/** Retained-size change (after − before) of the root row labelled `label`, or null if the diff has no such label. */
export function rootDelta(diff, label, hideSystem) {
  const r = diff.rootByLabel.get(label);
  if (!r) return null;
  return hideSystem ? r.appRetainedAfter - r.appRetainedBefore : r.retainedAfter - r.retainedBefore;
}

/** The root label of a tree row, as `RootDelta.label` writes it; null for rows the diff does not cover. */
export function rootLabelOf(row) {
  if (row.kind === 'CATEGORY' || row.kind === 'SHARED') return row.label;
  if (row.kind === 'CLASS_STATICS') return 'Static fields / ' + (row.className || row.label);
  return null;
}
