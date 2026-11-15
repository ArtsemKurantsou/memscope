// The GC-root tree: lazy children, incremental DOM updates, keyboard navigation, reveal.
// The model (Node objects) is separate from the DOM: collapsing removes elements but keeps
// loaded data, expanding inserts only the new rows after the parent row.
import {
  el, formatBytes, formatPercent, formatInt, splitClassName, rowSize, rowPercent, rowVisible, sortRows, debounce,
} from './util.js';

const PAGE = 25;
const MAX_BATCHES = 10; // while hiding system rows: stop looking for visible rows after this many pages
const INDENT = 16;
const AGGREGATE = new Set(['CATEGORY', 'SHARED', 'THREAD']);

class Node {
  constructor(row, parent, fetchPage) {
    this.row = row;
    this.parent = parent;
    this.level = parent ? parent.level + 1 : 0;
    this.fetchPage = fetchPage; // (offset, limit) -> Promise<TreePage>; loads this node's children
    this.kids = null;           // loaded children in server order; null = never loaded
    this.total = 0;
    this.fetched = 0;
    this.expanded = false;
    this.el = null;
    this.moreEl = null;
  }
  get expandable() { return this.row.childCount > 0; }
  get hasMore() { return this.kids !== null && this.fetched < this.total; }
}

export function createTree({ container, getOptions, getSummary, onSelect, onStatus }) {
  const body = el('div', { class: 'tree-body', role: 'tree', 'aria-label': 'GC root tree' });
  const head = el('div', { class: 'tree-head', 'aria-hidden': 'true' },
    el('div', { class: 'c-name' }, 'Name'),
    el('div', { class: 'c-ret' }, 'Retained'),
    el('div', { class: 'c-pct' }, '%'),
    el('div', { class: 'c-sh' }, 'Shallow'),
    el('div', { class: 'c-nat', title: 'Estimated native memory attributed to these objects (e.g. bitmap pixel buffers)' }, 'Native (est.)'));
  container.replaceChildren(head, body);

  let api = null;
  let root = null;
  let current = null; // the focused / selected node
  let epoch = 0;      // bumps on every setSource so late responses are dropped
  const emitSelect = debounce((n) => onSelect(n.row), 140);

  // ------------------------------------------------------------------ model

  function makeKids(parent, rows) {
    return rows.map((r) => new Node(r, parent, (o, l) => api.tree(r.ref, o, l)));
  }

  /** Loads pages into node.kids. Stops once `wantVisible` visible rows arrived, or (untilIndex >= 0) that index is loaded. */
  async function fetchInto(node, { wantVisible = PAGE, untilIndex = -1 } = {}) {
    const my = epoch;
    const hide = getOptions().hideSystem;
    let got = 0;
    let batches = 0;
    while (node.kids === null || node.fetched < node.total) {
      const limit = untilIndex >= 0 ? Math.min(1000, Math.max(PAGE, untilIndex + 1 - node.fetched)) : PAGE;
      const page = await node.fetchPage(node.fetched, limit);
      if (my !== epoch) throw new DOMException('superseded', 'AbortError');
      if (node.kids === null) node.kids = [];
      node.total = page.total;
      if (page.rows.length === 0) { node.total = node.fetched; break; }
      node.kids.push(...makeKids(node, page.rows));
      node.fetched += page.rows.length;
      got += page.rows.filter((r) => rowVisible(r, hide)).length;
      batches++;
      if (untilIndex >= 0) { if (node.fetched > untilIndex) break; }
      else if (got >= wantVisible || batches >= MAX_BATCHES) break;
    }
  }

  function sortedVisibleKids(node) {
    const { hideSystem } = getOptions();
    const kids = node.kids.filter((k) => rowVisible(k.row, hideSystem));
    const order = sortRows(kids.map((k) => k.row), hideSystem);
    const byRow = new Map(kids.map((k) => [k.row, k]));
    return order.map((r) => byRow.get(r));
  }

  // ------------------------------------------------------------------ DOM

  function cell(cls, ...c) { return el('div', { class: cls }, ...c); }

  function nameCell(node) {
    const r = node.row;
    const cls = r.className ? splitClassName(r.className) : null;
    const c = el('div', { class: 'c-name', style: `padding-left:${node.level * INDENT + 6}px` });
    c.append(el('span', { class: 'tw' }, node.expandable ? '▸' : ''));
    if (r.edgeLabel) c.append(el('span', { class: 'edge' }, r.edgeLabel + ' →'));
    if (r.kind === 'GROUP') {
      c.append(el('span', { class: 'badge' }, formatInt(r.count) + ' ×'));
      c.append(el('span', { class: 'cn' }, cls.simple), cls.pkg ? el('span', { class: 'pkg' }, cls.pkg) : null);
    } else if (r.kind === 'OBJECT' || r.kind === 'CLASS_STATICS') {
      const full = r.className || r.label;
      const sp = splitClassName(full);
      c.append(el('span', { class: 'cn' }, sp.simple), sp.pkg ? el('span', { class: 'pkg' }, sp.pkg) : null);
      if (r.kind === 'CLASS_STATICS') c.append(el('span', { class: 'tag' }, 'class'));
    } else if (r.kind === 'THREAD') {
      c.append(el('span', { class: 'cn' }, r.label), el('span', { class: 'pkg' }, 'thread'));
    } else {
      c.append(el('span', { class: 'cn strong' }, r.label));
    }
    return c;
  }

  function fillRow(node) {
    const r = node.row;
    const { hideSystem } = getOptions();
    const size = rowSize(r, hideSystem);
    const pct = rowPercent(r, hideSystem, getSummary());
    const tier = pct >= 10 ? 'hot' : pct >= 1 ? 'warm' : 'cool';
    const e = node.el;
    e.className = `row kind-${r.kind} ${r.isAppCode ? 'app' : 'fw'} ${tier}${node === current ? ' current' : ''}`;
    e.setAttribute('aria-level', String(node.level + 1));
    if (node.expandable) e.setAttribute('aria-expanded', String(node.expanded)); else e.removeAttribute('aria-expanded');
    e.setAttribute('aria-selected', String(node === current));
    e.title = `${r.className || r.label}${r.kind === 'GROUP' ? ` (${formatInt(r.count)} instances)` : ''}\nretained ${formatBytes(r.retainedBytes)} (app heap ${formatBytes(r.appHeapBytes)})`;
    const barW = size > 0 ? Math.max(2, Math.min(100, pct)) : 0;
    const dash = (n) => (n > 0 ? formatBytes(n) : '–');
    e.replaceChildren(
      nameCell(node),
      cell('c-ret', el('span', { class: 'bar' }, el('i', { style: `width:${barW}%` })), el('span', { class: 'num' }, formatBytes(size))),
      cell('c-pct num', formatPercent(pct)),
      cell('c-sh num', AGGREGATE.has(r.kind) ? '' : dash(r.shallowBytes)),
      cell('c-nat num', dash(r.nativeEstBytes)),
    );
  }

  function ensureEl(node) {
    if (!node.el) {
      node.el = el('div', { tabindex: '-1', role: 'treeitem' });
      node.el._node = node;
    }
    fillRow(node);
    return node.el;
  }

  function statusRow(node, text, { error, retry } = {}) {
    const e = el('div', { class: 'row status' + (error ? ' error' : ''), role: 'none' },
      el('div', { class: 'c-name', style: `padding-left:${(node.level + 1) * INDENT + 6}px` },
        error ? el('span', { class: 'status-msg' }, text) : el('span', { class: 'spinner' }), error ? null : el('span', null, text),
        retry ? el('button', { class: 'btn small', onclick: retry }, 'Retry') : null));
    return e;
  }

  function moreRow(node) {
    const remaining = node.total - node.fetched;
    const e = el('div', { class: 'row more', role: 'treeitem', tabindex: '-1', 'aria-level': String(node.level + 2) },
      el('div', { class: 'c-name', style: `padding-left:${(node.level + 1) * INDENT + 6}px` },
        el('span', { class: 'tw' }), el('span', { class: 'more-text' }, `show ${Math.min(PAGE, remaining)} more (${formatInt(remaining)} remaining)`)));
    e._more = node;
    node.moreEl = e;
    return e;
  }

  function emptyRow(node, text) {
    return el('div', { class: 'row status', role: 'none' },
      el('div', { class: 'c-name', style: `padding-left:${(node.level + 1) * INDENT + 6}px` }, el('span', { class: 'muted' }, text)));
  }

  /** Appends the DOM rows of node's loaded children (recursively for expanded ones) to a fragment. */
  function renderKids(node, frag) {
    const kids = sortedVisibleKids(node);
    for (const k of kids) {
      frag.append(ensureEl(k));
      if (k.expanded && k.kids) renderKids(k, frag);
    }
    if (node.hasMore) frag.append(moreRow(node));
    else if (node.kids.length > 0 && kids.length === 0) frag.append(emptyRow(node, 'Everything here is system heap (hidden). Turn off "Hide system heap" to see it.'));
    else if (node.kids.length === 0) frag.append(emptyRow(node, 'Nothing to show.'));
  }

  function removeKidsDom(node) {
    if (!node.kids) return;
    for (const k of node.kids) {
      if (k.el && k.el.isConnected) k.el.remove();
      removeKidsDom(k);
      if (k.moreEl) { k.moreEl.remove(); k.moreEl = null; }
    }
    if (node.moreEl) { node.moreEl.remove(); node.moreEl = null; }
    // status/empty rows have no node link: they sit between rows and are cleared by clearStatus
  }

  function clearInline(node) {
    // Removes status/empty rows directly following node.el that belong to its children block.
    let n = node.el && node.el.nextElementSibling;
    while (n && n.classList.contains('status')) { const next = n.nextElementSibling; n.remove(); n = next; }
  }

  function rerenderKids(node) {
    // Replace whatever children of `node` are in the DOM with a fresh render.
    if (node.el) { removeKidsDom(node); clearAfter(node); }
    const frag = document.createDocumentFragment();
    renderKids(node, frag);
    if (node === root) body.replaceChildren(frag);
    else node.el.after(frag);
  }

  function clearAfter(node) {
    // Remove every row after node.el whose level is deeper than node's (descendants, status rows, more rows).
    if (!node.el) return;
    const lvl = node.level;
    let n = node.el.nextElementSibling;
    while (n) {
      const deeper = n._node ? n._node.level > lvl : n._more ? n._more.level >= lvl : n.classList.contains('status');
      if (!deeper) break;
      const next = n.nextElementSibling;
      n.remove();
      n = next;
    }
  }

  // ------------------------------------------------------------------ actions

  async function expand(node) {
    if (!node.expandable || node.expanded) return;
    node.expanded = true;
    fillRow(node);
    if (node.kids === null) {
      const st = statusRow(node, 'Loading children…');
      node.el.after(st);
      const my = epoch;
      try {
        await fetchInto(node);
      } catch (e) {
        if (e.name === 'AbortError' || my !== epoch) return;
        st.replaceWith(statusRow(node, `Could not load children: ${e.message}${e.hint ? ' — ' + e.hint : ''}`, {
          error: true, retry: () => { node.expanded = false; node.kids = null; expand(node); },
        }));
        node.expanded = false;
        fillRow(node);
        return;
      }
      st.remove();
    }
    if (!node.el.isConnected || !node.expanded) return;
    const frag = document.createDocumentFragment();
    renderKids(node, frag);
    node.el.after(frag);
  }

  function collapse(node) {
    if (!node.expanded) return;
    node.expanded = false;
    if (current && isDescendant(current, node)) setCurrent(node, { focus: true });
    clearAfter(node);
    removeKidsDom(node);
    fillRow(node);
  }

  function isDescendant(n, ancestor) {
    for (let p = n.parent; p; p = p.parent) if (p === ancestor) return true;
    return false;
  }

  async function showMore(node) {
    const at = node.moreEl;
    if (at) at.replaceWith(statusRow(node, 'Loading more…'));
    try {
      await fetchInto(node);
    } catch (e) {
      if (e.name === 'AbortError') return;
      onStatus(`Could not load more rows: ${e.message}`);
    }
    // The server order of loaded rows is retained-desc but the display order may be app-heap desc:
    // re-render this node's children as one block.
    rerenderKids(node);
  }

  function setCurrent(node, { focus = false, select = false, immediate = false } = {}) {
    if (current && current.el) { current.el.tabIndex = -1; current.el.classList.remove('current'); current.el.setAttribute('aria-selected', 'false'); }
    current = node;
    if (node.el) {
      node.el.tabIndex = 0;
      node.el.classList.add('current');
      node.el.setAttribute('aria-selected', 'true');
      if (focus) { node.el.focus({ preventScroll: true }); node.el.scrollIntoView({ block: 'nearest' }); }
    }
    if (select) { if (immediate) { emitSelect.cancel(); onSelect(node.row); } else emitSelect(node); }
  }

  // ------------------------------------------------------------------ events

  body.addEventListener('click', (ev) => {
    const row = ev.target.closest('.row');
    if (!row) return;
    if (row._more) { showMore(row._more); return; }
    const node = row._node;
    if (!node) return;
    setCurrent(node, { focus: true, select: true, immediate: true });
    if (ev.target.closest('.tw') && node.expandable) node.expanded ? collapse(node) : expand(node);
  });
  body.addEventListener('dblclick', (ev) => {
    const node = ev.target.closest('.row')?._node;
    if (node && node.expandable) node.expanded ? collapse(node) : expand(node);
  });

  body.addEventListener('keydown', (ev) => {
    const row = ev.target.closest('.row');
    if (!row || ev.altKey || ev.ctrlKey || ev.metaKey) return;
    const rows = () => [...body.querySelectorAll('.row[role="treeitem"]')];
    const move = (target) => {
      if (!target) return;
      if (target._node) setCurrent(target._node, { focus: true, select: true });
      else target.focus();
    };
    const neighbour = (dir) => {
      let n = dir > 0 ? row.nextElementSibling : row.previousElementSibling;
      while (n && n.getAttribute('role') !== 'treeitem') n = dir > 0 ? n.nextElementSibling : n.previousElementSibling;
      return n;
    };
    const node = row._node;
    switch (ev.key) {
      case 'ArrowDown': move(neighbour(1)); break;
      case 'ArrowUp': move(neighbour(-1)); break;
      case 'Home': move(rows()[0]); break;
      case 'End': move(rows().at(-1)); break;
      case 'ArrowRight':
        if (!node) { showMore(row._more); break; }
        if (node.expandable && !node.expanded) expand(node);
        else if (node.expanded) { const nx = neighbour(1); if (nx && nx._node && nx._node.parent === node) move(nx); }
        break;
      case 'ArrowLeft':
        if (!node) break;
        if (node.expanded) collapse(node);
        else if (node.parent && node.parent.el) move(node.parent.el);
        break;
      case 'Enter':
        if (!node) showMore(row._more); else setCurrent(node, { focus: true, select: true, immediate: true });
        break;
      case ' ':
        if (node && node.expandable) node.expanded ? collapse(node) : expand(node);
        else if (!node) showMore(row._more);
        break;
      default: return;
    }
    ev.preventDefault();
  });

  // ------------------------------------------------------------------ public

  async function setSource(newApi, fetchPage, { onEmpty } = {}) {
    epoch++;
    api = newApi;
    current = null;
    root = new Node(null, null, fetchPage);
    root.level = -1;
    root.expanded = true;
    body.replaceChildren(el('div', { class: 'row status' }, el('div', { class: 'c-name' }, el('span', { class: 'spinner' }), el('span', null, 'Loading the tree…'))));
    const my = epoch;
    try {
      await fetchInto(root);
    } catch (e) {
      if (e.name === 'AbortError' || my !== epoch) return;
      throw e;
    }
    if (root.kids.length === 0) {
      body.replaceChildren(el('div', { class: 'row status' }, el('div', { class: 'c-name muted' }, onEmpty || 'Nothing to show.')));
      return;
    }
    rerenderKids(root);
    const first = body.querySelector('.row[role="treeitem"]');
    if (first && first._node) setCurrent(first._node);
  }

  /** Re-render after a toggle (hide system / my code) from loaded data; no refetching. */
  function refresh() {
    if (!root || !root.kids) return;
    const cur = current;
    rerenderKids(root);
    if (cur && cur.el && cur.el.isConnected) setCurrent(cur);
    else if (cur) { const f = body.querySelector('.row[role="treeitem"]'); if (f && f._node) setCurrent(f._node); }
  }

  /**
   * Opens every ancestor on `chain` (["root", "cat:static", ...]) loading the right pages, then selects
   * and scrolls to the last entry. Returns true when the target had to switch off "hide system heap".
   */
  async function reveal(chain) {
    const my = epoch;
    let node = root;
    let forcedShowSystem = false;
    for (let i = 1; i < chain.length; i++) {
      const ref = chain[i];
      if (node.kids === null) await fetchInto(node);
      let child = node.kids.find((k) => k.row.ref === ref);
      if (!child) {
        const { index } = await api.indexInParent(chain[i - 1], ref);
        if (index < 0) throw new Error(`${ref} is not a child of ${chain[i - 1]}`);
        await fetchInto(node, { untilIndex: index });
        child = node.kids.find((k) => k.row.ref === ref);
        if (!child) throw new Error(`${ref} was not found in the loaded page`);
      }
      if (my !== epoch) throw new DOMException('superseded', 'AbortError');
      if (!rowVisible(child.row, getOptions().hideSystem)) forcedShowSystem = true;
      if (forcedShowSystem) getOptions().forceShowSystem();
      if (i < chain.length - 1) { child.expanded = true; }
      node = child;
    }
    rerenderKids(root);
    setCurrent(node, { focus: true });
    node.el.scrollIntoView({ block: 'center' });
    node.el.classList.remove('flash');
    void node.el.offsetWidth;
    node.el.classList.add('flash');
    onSelect(node.row);
    return forcedShowSystem;
  }

  function focus() { (current && current.el ? current.el : body.querySelector('.row[role="treeitem"]'))?.focus(); }
  function clear(text) { epoch++; root = null; current = null; body.replaceChildren(text instanceof Node ? text : el('div', { class: 'row status' }, el('div', { class: 'c-name muted' }, text || ''))); }

  return { setSource, refresh, reveal, focus, clear, get body() { return body; } };
}
