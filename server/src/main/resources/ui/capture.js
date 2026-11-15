// Capture bar: device / app pickers, "Dump now", job stepper, snapshot selector.
import * as api from './api.js';
import { el, formatBytes, formatTime, sleep } from './util.js';

const $ = (id) => document.getElementById(id);

export function createCapture({ onSnapshot, onNoSnapshot, onEmptyState }) {
  const deviceSel = $('device-select');
  const appSel = $('app-select');
  const dumpBtn = $('dump-btn');
  const jobPanel = $('job-panel');
  const snapSel = $('snapshot-select');

  let devices = [];
  let apps = [];
  let session = null;       // SessionMeta of the active session for the selection, or null
  let busy = false;         // a dump/open pipeline is running
  let stepClock = new Map(); // "jobId/stepName" -> { start, end }
  let hideTimer = null;

  // ------------------------------------------------------------ pickers

  function setSelect(sel, options, placeholder) {
    sel.replaceChildren();
    if (!options.length) { sel.append(el('option', { value: '' }, placeholder)); sel.disabled = true; return; }
    sel.disabled = false;
    for (const o of options) sel.append(el('option', { value: o.value, disabled: o.disabled }, o.label));
  }

  async function loadDevices() {
    setSelect(deviceSel, [], 'Looking for devices…');
    let list;
    try { list = await api.devices(); } catch (e) { setSelect(deviceSel, [], 'Unavailable'); setSelect(appSel, [], '–'); updateButtons(); throw e; }
    devices = list;
    const usable = devices.filter((d) => d.state === 'device');
    if (!devices.length) {
      setSelect(deviceSel, [], 'No device');
      setSelect(appSel, [], '–');
      onEmptyState('no-device');
      updateButtons();
      return;
    }
    setSelect(deviceSel, devices.map((d) => ({
      value: d.serial, disabled: d.state !== 'device',
      label: `${d.serial}${d.model ? ' · ' + d.model : ''}${d.apiLevel ? ' · API ' + d.apiLevel : ''}${d.state !== 'device' ? ' (' + d.state + ')' : ''}`,
    })), 'No device');
    const keep = session && devices.some((d) => d.serial === session.serial && d.state === 'device') ? session.serial : null;
    if (keep) deviceSel.value = keep;
    else if (usable.length >= 1) deviceSel.value = usable[0].serial;
    if (!usable.length) {
      setSelect(appSel, [], '–');
      onEmptyState('device-unusable', devices);
      updateButtons();
      return;
    }
    await loadApps(false);
  }

  async function loadApps(refresh) {
    const serial = deviceSel.value;
    if (!serial) return;
    const prev = appSel.value || (session && session.packageName) || '';
    setSelect(appSel, [], 'Loading apps…');
    try { apps = await api.apps(serial, refresh); } catch (e) { setSelect(appSel, [], 'Failed to load apps'); updateButtons(); throw e; }
    apps = [...apps].sort((a, b) => (b.running - a.running) || a.packageName.localeCompare(b.packageName));
    if (!apps.length) {
      setSelect(appSel, [], 'No debuggable apps');
      onEmptyState('no-apps');
      updateButtons();
      return;
    }
    setSelect(appSel, apps.map((a) => ({ value: a.packageName, label: `${a.packageName}${a.running ? '  ● running' : '  (not running)'}` })), '');
    if (apps.some((a) => a.packageName === prev)) appSel.value = prev;
    onEmptyState('ready');
    updateButtons();
  }

  function updateButtons() {
    dumpBtn.disabled = busy || !deviceSel.value || !appSel.value || deviceSel.disabled || appSel.disabled;
    dumpBtn.textContent = busy ? 'Working…' : 'Dump now';
  }

  // ------------------------------------------------------------ snapshots

  function renderSnapshots(selectId) {
    const snaps = session ? session.snapshots : [];
    if (!snaps.length) { setSelect(snapSel, [], 'No snapshots yet'); return; }
    setSelect(snapSel, snaps.map((s, i) => ({
      value: s.id,
      label: `${i + 1}. ${s.label} · ${formatTime(s.takenAtMs)} · ${formatBytes(s.sizeBytes)}${s.status !== 'OK' ? ' (' + s.status.toLowerCase() + ')' : ''}`,
    })), '');
    if (selectId) snapSel.value = selectId;
  }

  function selectedSnapshotMeta() {
    return session && session.snapshots.find((s) => s.id === snapSel.value);
  }

  // ------------------------------------------------------------ job stepper

  function clearHideTimer() { clearTimeout(hideTimer); hideTimer = null; }

  function stepState(jobId, step) {
    const key = jobId + '/' + step.name;
    let t = stepClock.get(key);
    const now = Date.now();
    if (!t && step.state === 'RUNNING') { t = { start: now }; stepClock.set(key, t); }
    if (t && !t.end && (step.state === 'DONE' || step.state === 'FAILED')) t.end = now;
    return t;
  }

  function stepRow(jobId, step) {
    const t = stepState(jobId, step);
    const secs = t ? ((t.end || Date.now()) - t.start) / 1000 : null;
    const icon = { PENDING: '○', RUNNING: null, DONE: '✓', FAILED: '✗' }[step.state] ?? null;
    return el('li', { class: 'jstep ' + step.state.toLowerCase() },
      el('span', { class: 'jicon', 'aria-hidden': 'true' }, icon === null ? el('span', { class: 'spinner' }) : icon),
      el('span', { class: 'jname' }, step.name),
      el('span', { class: 'jstate' }, step.state.toLowerCase()),
      el('span', { class: 'jdetail' }, step.detail || ''),
      el('span', { class: 'jtime num' }, secs == null ? '' : secs.toFixed(1) + ' s'));
  }

  /** groups: [{ title, job|null, pending: text }]; job is a JobView. */
  let lastGroups = [];
  function renderPanel(groups, { cancel, failure, success } = {}) {
    lastGroups = groups;
    clearHideTimer();
    jobPanel.hidden = false;
    const parts = [];
    for (const g of groups) {
      parts.push(el('div', { class: 'jgroup' },
        el('div', { class: 'jtitle' }, el('strong', null, g.title), g.job ? el('span', { class: 'muted num' }, ` · ${(g.job.elapsedMs / 1000).toFixed(1)} s`) : null),
        g.job
          ? el('ol', { class: 'jsteps' }, g.job.steps.map((s) => stepRow(g.job.id, s)))
          : el('div', { class: 'status-line' }, el('span', { class: 'spinner' }), g.pending || 'Starting…')));
    }
    if (cancel) parts.push(el('div', { class: 'jactions' }, el('button', { class: 'btn small', onclick: cancel }, 'Cancel')));
    if (success) parts.push(el('div', { class: 'jsuccess' }, success));
    if (failure) {
      parts.push(el('div', { class: 'error-box', role: 'alert' },
        el('strong', null, failure.message),
        failure.hint ? el('p', null, failure.hint) : null,
        el('div', { class: 'jactions' },
          el('button', { class: 'btn primary small', onclick: failure.retry }, 'Retry'),
          el('button', { class: 'btn small', onclick: hidePanel }, 'Dismiss'))));
    }
    jobPanel.replaceChildren(...parts);
  }

  function hidePanel() { clearHideTimer(); jobPanel.hidden = true; jobPanel.replaceChildren(); }

  class PipelineError extends Error {
    constructor(message, hint) { super(message); this.hint = hint; }
  }

  /** Polls a job while rendering `groups` (earlier groups first); returns the final JobView. */
  async function runJob(jobId, title, doneGroups) {
    const cancel = async () => { try { await api.cancelJob(jobId); } catch { /* the next poll shows the outcome */ } };
    const j = await api.pollJob(jobId, (job) => renderPanel([...doneGroups, { title, job }], { cancel: job.state === 'RUNNING' ? cancel : null }));
    return j;
  }

  function failJob(j, what) {
    const cancelled = j.state === 'CANCELLED';
    return new PipelineError(cancelled ? `${what} cancelled` : j.errorMessage || `${what} failed`, cancelled ? '' : j.errorHint || '');
  }

  async function runOpen(sid, snapId, doneGroups, title) {
    renderPanel([...doneGroups, { title, job: null, pending: 'Asking the server to analyse the snapshot…' }]);
    const { jobId } = await api.openSnapshot(sid, snapId);
    const j = await runJob(jobId, title, doneGroups);
    if (j.state !== 'DONE') throw failJob(j, 'Analysis');
    return j;
  }

  // ------------------------------------------------------------ pipeline

  async function ensureSession(serial, pkg) {
    renderPanel([{ title: 'Preparing', job: null, pending: 'Checking for an active session…' }]);
    const all = await api.sessions();
    const active = all.find((s) => s.state === 'ACTIVE' && s.serial === serial && s.packageName === pkg);
    if (active) return active;
    renderPanel([{ title: 'Preparing', job: null, pending: `Starting a session for ${pkg}…` }]);
    return api.createSession(serial, pkg);
  }

  async function dumpNow() {
    if (busy) return;
    const serial = deviceSel.value;
    const pkg = appSel.value;
    busy = true;
    updateButtons();
    let failedStage = 'dump';
    let snapId = null;
    try {
      session = await ensureSession(serial, pkg);
      const { jobId } = await api.dump(session.id);
      const dumpTitle = `Dump heap of ${pkg}`;
      renderPanel([{ title: dumpTitle, job: null, pending: 'Starting the dump…' }]);
      const dj = await runJob(jobId, dumpTitle, []);
      if (dj.state !== 'DONE') throw failJob(dj, 'Heap dump');
      snapId = dj.resultRef;
      session = await api.session(session.id);
      renderSnapshots(snapId);
      failedStage = 'open';
      await analyse(session.id, snapId, [{ title: dumpTitle, job: dj }]);
    } catch (e) {
      showFailure(e, failedStage === 'open' && snapId ? () => retryOpen(snapId) : dumpNow);
    } finally {
      busy = false;
      updateButtons();
    }
  }

  async function analyse(sid, snapId, doneGroups) {
    const done = await runOpen(sid, snapId, doneGroups, 'Analyse snapshot');
    renderPanel([...doneGroups, { title: 'Analyse snapshot', job: done }],
      { success: `Snapshot ready (analysed in ${(done.elapsedMs / 1000).toFixed(1)} s).` });
    hideTimer = setTimeout(hidePanel, 4000);
    await onSnapshot(sid, selectedSnapshotMeta() || { id: snapId });
  }

  async function retryOpen(snapId) {
    if (busy) return;
    busy = true;
    updateButtons();
    try { await analyse(session.id, snapId, []); } catch (e) { showFailure(e, () => retryOpen(snapId)); } finally { busy = false; updateButtons(); }
  }

  function showFailure(e, retry) {
    const message = e.message || String(e);
    const hint = e.hint || '';
    renderPanel(lastGroups.filter((g) => g.job), { failure: { message, hint, retry } });
  }

  // Used by api.js when a snapshot call answers 409 "not analysed" (e.g. evicted from memory).
  api.hooks.reopen = async (sid, snapId) => {
    const was = busy;
    busy = true; updateButtons();
    try {
      await runOpen(sid, snapId, [], 'Re-analysing snapshot (it was no longer in memory)');
      hidePanel();
    } catch (e) {
      showFailure(e, () => onSnapshot(sid, { id: snapId }));
      throw e;
    } finally { busy = was; updateButtons(); }
  };

  // ------------------------------------------------------------ wiring

  deviceSel.addEventListener('change', () => { session = null; renderSnapshots(); loadApps(false).catch(reportLoad); });
  appSel.addEventListener('change', async () => {
    updateButtons();
    // Switching app: show the active session's snapshots if it is for this app.
    const all = await api.sessions().catch(() => []);
    session = all.find((s) => s.state === 'ACTIVE' && s.serial === deviceSel.value && s.packageName === appSel.value) || null;
    renderSnapshots(session && lastOk(session)?.id);
  });
  $('device-refresh').addEventListener('click', () => loadDevices().catch(reportLoad));
  $('app-refresh').addEventListener('click', () => loadApps(true).catch(reportLoad));
  dumpBtn.addEventListener('click', dumpNow);
  snapSel.addEventListener('change', async () => {
    const meta = selectedSnapshotMeta();
    if (meta) await onSnapshot(session.id, meta);
  });

  let reportLoad = () => {};
  const lastOk = (s) => [...s.snapshots].reverse().find((x) => x.status === 'OK');

  /** Page load: devices, then restore an ACTIVE session and its newest snapshot. */
  async function init(onError) {
    reportLoad = onError;
    const all = await api.sessions();
    const active = all.find((s) => s.state === 'ACTIVE');
    if (active) { session = active; renderSnapshots(lastOk(active)?.id); } else renderSnapshots();
    try { await loadDevices(); } catch (e) { onError(e); }
    if (active) {
      if (apps.some((a) => a.packageName === active.packageName)) appSel.value = active.packageName;
      const snap = lastOk(active);
      if (snap) { snapSel.value = snap.id; await onSnapshot(active.id, snap); return; }
    }
    onNoSnapshot();
  }

  updateButtons();
  void sleep;
  return { init, refreshDevices: () => loadDevices() };
}
