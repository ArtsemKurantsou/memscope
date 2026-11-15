// Wires the modules together. Later tasks add timeline.js, classes.js and findings.js as further
// modules (the placeholders in index.html) and register them next to the tree here.
import * as api from './api.js';
import { createCapture } from './capture.js';
import { createTree } from './tree.js';
import { createDetails } from './details.js';
import { createSearch } from './search.js';
import { el, formatBytes, formatInt, prefs } from './util.js';

const $ = (id) => document.getElementById(id);

const state = {
  hideSystem: prefs.get('hideSystem', true),
  myCode: prefs.get('myCode', false),
  snap: null,      // snapshotApi of the open snapshot
  summary: null,
  classes: null,   // Promise<ClassStat[]>, fetched once per snapshot
  query: null,     // class name of the active search, or null
  loadToken: 0,
};

const hideBox = $('hide-system');
const myCodeBox = $('my-code');
hideBox.checked = state.hideSystem;
myCodeBox.checked = state.myCode;
$('tree').classList.toggle('my-code', state.myCode);

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

const search = createSearch({
  input: $('search'),
  list: $('search-list'),
  getClasses: () => {
    // Never keep a rejected promise: the next keystroke or Retry asks again.
    if (!state.classes) {
      const p = state.snap.classes();
      state.classes = p;
      p.catch(() => { if (state.classes === p) state.classes = null; });
    }
    return state.classes;
  },
  onPick: (name) => runSearch(name),
});

function setHideSystem(on) {
  state.hideSystem = on;
  hideBox.checked = on;
  prefs.set('hideSystem', on);
  renderSummary();
  tree.refresh();
}
hideBox.addEventListener('change', () => setHideSystem(hideBox.checked));
myCodeBox.addEventListener('change', () => {
  state.myCode = myCodeBox.checked;
  prefs.set('myCode', state.myCode);
  $('tree').classList.toggle('my-code', state.myCode);
});

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
  const my = ++state.loadToken;
  state.snap = api.snapshotApi(sid, meta.id);
  state.summary = null;
  state.classes = null;
  state.query = null;
  $('search').value = '';
  $('search').disabled = false;
  $('search-banner').hidden = true;
  details.clear();
  renderSummary();
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
  banner.hidden = false;
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

const capture = createCapture({
  onSnapshot: openSnapshot,
  onNoSnapshot: () => {},
  onEmptyState,
});

startFn = async () => {
  $('banner').hidden = true;
  try { await capture.init(showBanner); } catch (e) { showBanner(e); }
};
startFn();
