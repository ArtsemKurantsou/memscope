// Wires the modules together. Later tasks add timeline.js, classes.js and findings.js as further
// modules (the placeholders in index.html) and register them next to the tree here.
import * as api from './api.js';
import { createCapture } from './capture.js';
import { createTree } from './tree.js';
import { createDetails } from './details.js';
import { createSearch } from './search.js';
import { createTimeline } from './timeline.js';
import { createClasses } from './classes.js';
import { createCompare } from './compare.js';
import { createFindings } from './findings.js';
import { el, formatBytes, formatInt, formatDeltaBytes, indexDiff, prefs } from './util.js';

const $ = (id) => document.getElementById(id);

const state = {
  hideSystem: prefs.get('hideSystem', true),
  myCode: prefs.get('myCode', false),
  snap: null,      // snapshotApi of the open snapshot
  summary: null,
  classes: null,   // Promise<ClassStat[]>, fetched once per snapshot
  classesLoaded: false, // the Classes tab shows the classes of the open snapshot
  query: null,     // class name of the active search, or null
  loadToken: 0,
  tab: 'tree',
  session: null,   // SessionMeta of the session on screen
  base: null,      // SnapshotMeta compared against, or null
  diff: null,      // indexed DiffResult of (open snapshot, base), or null
  diffKey: null,   // "snap|base" of the diff loaded or loading
  diffLoading: false,
  diffError: null,
  diffToken: 0,
};

const hideBox = $('hide-system');
const myCodeBox = $('my-code');
hideBox.checked = state.hideSystem;
myCodeBox.checked = state.myCode;
$('tree').classList.toggle('my-code', state.myCode);
$('classes').classList.toggle('my-code', state.myCode);

// ---------------------------------------------------------------- banner (server unreachable)

let startFn = null;
api.hooks.onReachability = (ok) => {
  const b = $('banner');
  if (ok) { if (b.dataset.kind === 'network') b.hidden = true; return; }
  b.dataset.kind = 'network';
  b.hidden = false;
  b.replaceChildren(el('strong', null, 'Cannot reach the MemScope server. '),
    'Is the memscope process still running? ', el('button', { class: 'btn small', onclick: () => startFn && startFn() }, 'Retry'));
};

function showBanner(e) {
  const b = $('banner');
  if (e.isNetwork) return;
  b.dataset.kind = 'error';
  b.hidden = false;
  b.replaceChildren(el('strong', null, e.message || String(e)), e.hint ? ' ' + e.hint : '', ' ',
    el('button', { class: 'btn small', onclick: () => { b.hidden = true; startFn && startFn(); } }, 'Retry'));
}

// ---------------------------------------------------------------- summary line

function renderSummary() {
  const box = $('summary');
  const s = state.summary;
  if (!s) { box.replaceChildren(); return; }
  const bits = [
    el('span', { class: 'sum-main', title: 'Bytes retained by objects that are not part of the Android boot image' }, 'App heap ', el('b', null, formatBytes(s.appHeapBytes))),
    el('span', { title: 'Everything reachable from a GC root, including the shared system heap' }, 'Reachable ', el('b', null, formatBytes(s.reachableBytes))),
    el('span', { title: 'Native memory estimated from Java wrappers such as bitmaps' }, 'Native (est.) ', el('b', null, formatBytes(s.nativeEstBytes))),
    el('span', null, el('b', null, formatInt(s.objectCount)), ' objects'),
  ];
  if (state.hideSystem) bits.push(el('span', { class: 'muted' }, `Hiding ${formatBytes(Math.max(0, s.reachableBytes - s.appHeapBytes))} of system heap`));
  box.replaceChildren(...bits);
}

// ---------------------------------------------------------------- compare line

/** "App heap: 12.3 MB → 27.9 MB (+15.6 MB) since <base>", or the loading / failure state of the diff. */
function renderCompareLine() {
  const box = $('compare-line');
  const base = state.base;
  box.hidden = !base;
  box.className = 'compare-line';
  if (!base) return;
  const name = base.label || base.id;
  if (state.diffLoading) {
    box.replaceChildren(el('span', { class: 'spinner' }), `Comparing with ${name}…`);
  } else if (state.diffError) {
    const e = state.diffError;
    box.classList.add('error');
    box.replaceChildren(el('strong', null, `Could not compare with ${name}: ${e.message}`), e.hint ? el('span', null, e.hint) : null,
      el('button', { class: 'btn small', onclick: () => fetchDiff(true) }, 'Retry'));
  } else if (state.diff) {
    const d = state.diff;
    const [label, before, after] = state.hideSystem ? ['App heap', d.appHeapBefore, d.appHeapAfter] : ['Heap incl. system', d.reachableBefore, d.reachableAfter];
    const change = after - before;
    box.replaceChildren(
      el('span', null, `${label}: `, el('b', null, formatBytes(before)), ' → ', el('b', null, formatBytes(after)), ' (',
        el('b', { class: change > 0 ? 'up' : change < 0 ? 'down' : '' }, formatDeltaBytes(change)), ')'),
      el('span', { class: 'muted' }, `since ${name}`));
  }
}

// ---------------------------------------------------------------- modules

const details = createDetails({
  container: $('details'),
  getApi: () => state.snap,
  getOptions: () => ({ hideSystem: state.hideSystem }),
  onReveal: revealRef,
});

const tree = createTree({
  container: $('tree'),
  getOptions: () => ({
    hideSystem: state.hideSystem,
    myCode: state.myCode,
    get diff() { return state.diff; },
    forceShowSystem: () => { setHideSystem(false); },
  }),
  getSummary: () => state.summary,
  onSelect: (row) => details.showRow(row),
  onStatus: (text) => {
    const b = $('banner');
    if (!b.hidden && b.dataset.kind === 'network') return; // the unreachable banner already says it
    b.dataset.kind = 'error'; b.hidden = false; b.textContent = text;
  },
});

/** The class table of the open snapshot, fetched once; a rejected promise is never kept (the next call asks again). */
function classList() {
  if (!state.classes) {
    const p = state.snap.classes();
    state.classes = p;
    p.catch(() => { if (state.classes === p) state.classes = null; });
  }
  return state.classes;
}

const search = createSearch({
  input: $('search'),
  list: $('search-list'),
  getClasses: classList,
  onPick: (name) => runSearch(name),
});

const classesView = createClasses({
  container: $('classes'),
  getOptions: () => ({ hideSystem: state.hideSystem }),
  onPickClass: (name) => { setTab('tree'); $('search').value = name; runSearch(name); },
});

const compare = createCompare({ box: $('compare-on'), select: $('compare-select'), onChange: (meta) => applyBase(meta) });

/** "Show in tree" of a finding: switch to its snapshot if needed, then reveal the object. */
async function showInTree(ref, snapshotId) {
  // Leave the Findings tab first: opening the target snapshot while it is shown would start a findings computation nobody asked for.
  setTab('tree');
  if (snapshotId && (!state.snap || snapshotId !== state.snap.snap)) {
    await capture.selectSnapshot(snapshotId);
    if (!state.snap || state.snap.snap !== snapshotId) { // selectSnapshot returns silently when busy, not OK or unknown
      setTab('findings');
      showBanner({ message: 'Could not switch to that snapshot.', hint: 'It may be busy (a dump or analysis is running) or unreadable; wait for the current operation to finish and try again.' });
      return;
    }
  }
  await revealRef(ref);
}

/** Opens (analyses) each snapshot in turn so its summary exists for the cross-snapshot rules. */
async function analyseAll(ids, onProgress) {
  for (let i = 0; i < ids.length; i++) {
    onProgress(i + 1, ids.length);
    const { jobId } = await api.openSnapshot(state.session.id, ids[i]);
    const j = await api.pollJob(jobId, () => {});
    if (j.state !== 'DONE') throw new api.ApiError(500, { error: j.errorMessage || `Analysing ${ids[i]} failed`, hint: j.errorHint || '', kind: 'JOB_FAILED' });
  }
}

function setFindingsBadge(counts) {
  const b = $('findings-badge');
  b.hidden = !counts;
  if (!counts) return;
  b.replaceChildren(String(counts.total), counts.high ? el('b', { class: 'badge-high' }, ` \u00b7 ${counts.high} high`) : null);
  b.title = counts.total ? `${counts.total} finding${counts.total === 1 ? '' : 's'}, ${counts.high} high severity` : 'No findings';
}

const findingsView = createFindings({
  container: $('findings'),
  getSession: () => state.session,
  onShow: showInTree,
  onBadge: setFindingsBadge,
  onAnalyseAll: analyseAll,
});

function setHideSystem(on) {
  state.hideSystem = on;
  hideBox.checked = on;
  prefs.set('hideSystem', on);
  renderSummary();
  renderCompareLine();
  tree.refresh();
  if (state.classesLoaded) classesView.refresh();
}
hideBox.addEventListener('change', () => setHideSystem(hideBox.checked));
myCodeBox.addEventListener('change', () => {
  state.myCode = myCodeBox.checked;
  prefs.set('myCode', state.myCode);
  $('tree').classList.toggle('my-code', state.myCode);
  $('classes').classList.toggle('my-code', state.myCode);
});

// ---------------------------------------------------------------- tabs

function setTab(name) {
  state.tab = name;
  for (const t of ['tree', 'classes', 'findings']) {
    $('tab-' + t).classList.toggle('active', t === name);
    $('tab-' + t).setAttribute('aria-selected', String(t === name));
  }
  const wide = name !== 'tree';
  $('tree').hidden = wide;
  $('classes').hidden = name !== 'classes';
  $('findings').hidden = name !== 'findings';
  $('search-banner').hidden = wide || !state.query;
  document.querySelector('.search').hidden = wide;
  document.querySelector('.main').classList.toggle('classes-mode', wide);
  if (name === 'classes') loadClassesView();
  if (name === 'findings') findingsView.show();
}
$('tab-tree').addEventListener('click', () => setTab('tree'));
$('tab-classes').addEventListener('click', () => setTab('classes'));
$('tab-findings').addEventListener('click', () => setTab('findings'));

/** Fills the Classes tab with the open snapshot's classes (once per snapshot). */
async function loadClassesView() {
  if (!state.snap) { classesView.setMessage(el('div', { class: 'empty' }, 'No snapshot open.')); return; }
  if (state.classesLoaded) return;
  const snap = state.snap;
  classesView.setMessage(el('div', { class: 'empty' }, el('span', { class: 'spinner' }), ' Loading classes…'));
  try {
    const list = await classList();
    if (snap !== state.snap) return;
    state.classesLoaded = true;
    classesView.setClasses(list);
    classesView.setDiff(state.diff);
  } catch (e) {
    if (snap !== state.snap) return;
    classesView.setMessage(errorPanel(e, loadClassesView));
  }
}

// ---------------------------------------------------------------- compare

/** The compare selector chose another base snapshot (or none). */
function applyBase(meta) {
  state.base = meta;
  state.diff = null;
  state.diffError = null;
  state.diffKey = null;
  state.diffToken++;
  state.diffLoading = false;
  renderCompareLine();
  applyDiff();
  if (meta) fetchDiff();
}

/** Pushes the diff (or its absence) into the tree's delta column and the Classes table. */
function applyDiff() {
  tree.refresh();
  if (state.classesLoaded) classesView.setDiff(state.diff);
}

/** Loads the diff of the open snapshot against the base; one request per (snapshot, base) pair unless `force`. */
async function fetchDiff(force = false) {
  if (!state.base || !state.snap) return;
  const key = `${state.snap.snap}|${state.base.id}`;
  if (!force && state.diffKey === key && (state.diffLoading || state.diff)) return;
  state.diffKey = key;
  const my = ++state.diffToken;
  state.diffLoading = true;
  state.diffError = null;
  state.diff = null;
  renderCompareLine();
  let result = null;
  let error = null;
  try { result = indexDiff(await state.snap.diff(state.base.id, () => my === state.diffToken)); } catch (e) { error = e; }
  if (my !== state.diffToken) return;
  state.diffLoading = false;
  state.diff = result;
  state.diffError = error;
  if (error) state.diffKey = null;
  renderCompareLine();
  applyDiff();
}

// ---------------------------------------------------------------- snapshot loading

function showTreeMessage(...nodes) {
  tree.clear(el('div', { class: 'empty' }, ...nodes));
}

function errorPanel(e, retry) {
  return el('div', { class: 'error-box', role: 'alert' }, el('strong', null, e.message), e.hint ? el('p', null, e.hint) : null,
    el('button', { class: 'btn primary small', onclick: retry }, 'Retry'));
}

async function loadRoot() {
  const my = ++state.loadToken;
  state.query = null;
  $('search-banner').hidden = true;
  try {
    await tree.setSource(state.snap, (o, l) => state.snap.tree('root', o, l));
  } catch (e) {
    if (my !== state.loadToken) return;
    tree.clear(errorPanel(e, loadRoot));
  }
}

async function openSnapshot(sid, meta) {
  timeline.setSelected(meta.id);
  const my = ++state.loadToken;
  state.snap = api.snapshotApi(sid, meta.id);
  state.summary = null;
  state.classes = null;
  state.classesLoaded = false;
  state.diff = null;
  state.diffKey = null;
  state.diffLoading = false;
  state.diffError = null;
  state.diffToken++;
  findingsView.setSnapshot(state.snap);
  if (state.tab === 'findings') findingsView.show();
  state.query = null;
  $('search').value = '';
  $('search').disabled = false;
  $('search-banner').hidden = true;
  details.clear();
  renderSummary();
  if (state.tab === 'classes') loadClassesView();
  compare.setCurrent(meta.id); // an automatic base moves to the previous milestone and starts its diff
  fetchDiff(); // a kept base needs the diff of the new snapshot (no-op if setCurrent just started it)
  renderCompareLine();
  tree.clear(el('div', { class: 'empty' }, el('span', { class: 'spinner' }), ` Loading snapshot ${meta.label || meta.id}…`));
  try {
    state.summary = await state.snap.summary();
    if (my !== state.loadToken) return;
    renderSummary();
    await loadRoot();
  } catch (e) {
    if (my !== state.loadToken) return;
    tree.clear(errorPanel(e, () => openSnapshot(sid, meta)));
  }
}

// ---------------------------------------------------------------- search

async function runSearch(name) {
  const my = ++state.loadToken;
  state.query = name;
  const banner = $('search-banner');
  banner.hidden = state.tab === 'classes';
  banner.replaceChildren(el('span', null, 'Instances of ', el('b', { class: 'mono' }, name), ' '), el('span', { class: 'muted', id: 'search-count' }, 'searching…'),
    el('button', { class: 'btn small', onclick: clearSearch }, 'Clear search'));
  try {
    await tree.setSource(state.snap, (o, l) => state.snap.instances(name, o, l).then((p) => {
      const c = document.getElementById('search-count');
      if (c && my === state.loadToken) c.textContent = `${formatInt(p.total)} instance${p.total === 1 ? '' : 's'}, largest first`;
      return p;
    }), { onEmpty: `No instances of ${name} in this snapshot.` });
  } catch (e) {
    if (my !== state.loadToken) return;
    tree.clear(errorPanel(e, () => runSearch(name)));
  }
}

async function clearSearch() {
  $('search').value = '';
  search.close();
  await loadRoot();
}

async function revealRef(ref) {
  if (state.query) await clearSearch();
  const chain = await state.snap.reveal(ref);
  const forced = await tree.reveal(chain);
  if (forced) {
    const b = $('banner');
    b.dataset.kind = 'info'; b.hidden = false;
    b.textContent = 'That object is part of the system heap, so "Hide system heap" was switched off.';
    setTimeout(() => { if (b.dataset.kind === 'info') b.hidden = true; }, 5000);
  }
}

// ---------------------------------------------------------------- capture / empty states

function onEmptyState(kind, devices) {
  if (state.snap) return;
  if (kind === 'no-device') {
    showTreeMessage(el('h2', null, 'No device connected'), el('p', null, 'Plug in a phone or start an emulator, then Refresh.'),
      el('button', { class: 'btn primary', onclick: () => capture.refreshDevices().catch(showBanner) }, 'Refresh devices'));
  } else if (kind === 'device-unusable') {
    showTreeMessage(el('h2', null, 'No usable device'), el('p', null, 'Found: ' + devices.map((d) => `${d.serial} (${d.state})`).join(', ') +
      '. Unlock the device and accept the USB debugging prompt, then Refresh.'),
    el('button', { class: 'btn primary', onclick: () => capture.refreshDevices().catch(showBanner) }, 'Refresh devices'));
  } else if (kind === 'no-apps') {
    showTreeMessage(el('h2', null, 'No debuggable apps on this device'),
      el('p', null, 'MemScope can only dump apps built as debuggable (a debug build, or android:debuggable="true"). Install one, then use the refresh button next to the app list.'));
  } else if (kind === 'ready') {
    showTreeMessage(el('h2', null, 'No snapshot yet'), el('p', null, 'Pick a device and app, then press Dump now. The heap is captured, analysed and shown here as a tree from the GC roots.'));
  }
}

const timeline = createTimeline({ container: $('timeline'), onSelectSnapshot: (id) => capture.selectSnapshot(id) });

const capture = createCapture({
  onSnapshot: openSnapshot,
  onSession: (s) => { state.session = s; timeline.setSession(s); compare.setSession(s); },
  onNoSnapshot: () => {},
  onEmptyState,
});

startFn = async () => {
  $('banner').hidden = true;
  try { await capture.init(showBanner); } catch (e) { showBanner(e); }
};
startFn();
