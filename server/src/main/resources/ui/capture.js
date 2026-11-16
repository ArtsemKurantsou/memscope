      // unreadable or failed: say why instead of trying to open it
      const unreadable = meta.status === 'UNREADABLE';
      showFailure({
        message: `Snapshot ${meta.label || meta.id} cannot be opened (${meta.status.toLowerCase()})`,
        hint: (meta.error ? meta.error + ' ' : '') + (unreadable
          ? 'Retry checks the file again and re-analyses it if it is intact; otherwise take a new dump to replace it.'
          : 'Take a new dump to replace it.'),
      }, unreadable ? () => retryOpen(id) : undefined); // the server re-validates an unreadable file on open// Capture bar: device / app pickers, "Dump now", job stepper, snapshot selector.
import * as api from './api.js';
import { el, formatBytes, formatTime } from './util.js';
import { askStartupConfig } from './startup.js';

const $ = (id) => document.getElementById(id);

export function createCapture({ onSnapshot, onNoSnapshot, onEmptyState, onSession = () => {} }) {
  const deviceSel = $('device-select');
  const appSel = $('app-select');
  const dumpBtn = $('dump-btn');
  const startupBtn = $('startup-btn');
  const procField = $('dump-process-field');
  const procSel = $('dump-process');
  const jobPanel = $('job-panel');
  const snapSel = $('snapshot-select');

  let devices = [];
  let apps = [];
  let session = null;       // SessionMeta of the active session for the selection, or null
  let busy = false;         // a dump/open pipeline is running
  let stepClock = new Map(); // "jobId/stepName" -> { start, end }
  let hideTimer = null;
  let appsToken = 0;        // guards loadApps against out-of-order responses

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
    const my = ++appsToken;
    setSelect(appSel, [], 'Loading apps…');
    let list;
    try { list = await api.apps(serial, refresh); } catch (e) {
      if (my !== appsToken) return;
      setSelect(appSel, [], 'Failed to load apps'); updateButtons(); throw e;
    }
    if (my !== appsToken || deviceSel.value !== serial) return; // a newer scan or another device took over
    apps = list;
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
    renderProcesses();
    updateButtons();
  }

  function updateButtons() {
    dumpBtn.disabled = busy || !deviceSel.value || !appSel.value || deviceSel.disabled || appSel.disabled;
    dumpBtn.textContent = busy ? 'Working…' : 'Dump now';
    startupBtn.disabled = dumpBtn.disabled;
    procSel.disabled = busy;
  }

  /** Process names of the selected app (running ones from the app list), the package itself first. */
  function processNames() {
    const pkg = appSel.value;
    const app = apps.find((a) => a.packageName === pkg);
    return [...new Set([pkg, ...(app ? app.processes.map((p) => p.name) : [])])].filter(Boolean);
  }

  /** The process picker next to "Dump now" is shown only when the app has more than one process. */
  function renderProcesses() {
    const names = processNames();
    const keep = procSel.value;
    procField.hidden = names.length < 2;
    procSel.replaceChildren(...names.map((n) => el('option', { value: n }, n)));
    if (names.includes(keep)) procSel.value = keep;
  }

  // ------------------------------------------------------------ snapshots

  function renderSnapshots(selectId) {
    onSession(session);
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
  function renderPanel(groups, { cancel, failure, success, note } = {}) {
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
    if (note) parts.push(el('div', { class: 'status-line' }, el('span', { class: 'spinner' }), note));
    if (cancel) parts.push(el('div', { class: 'jactions' }, el('button', { class: 'btn small', onclick: cancel }, 'Cancel')));
    if (success) parts.push(el('div', { class: 'jsuccess' }, success));
    if (failure) {
      parts.push(el('div', { class: 'error-box', role: 'alert' },
        el('strong', null, failure.message),
        failure.hint ? el('p', null, failure.hint) : null,
        el('div', { class: 'jactions' },
          failure.retry ? el('button', { class: 'btn primary small', onclick: failure.retry }, 'Retry') : null,
          el('button', { class: 'btn small', onclick: hidePanel }, 'Dismiss'))));
    }
    jobPanel.replaceChildren(...parts);
  }

  function hidePanel() { clearHideTimer(); jobPanel.hidden = true; jobPanel.replaceChildren(); }

  class PipelineError extends Error {
    constructor(message, hint) { super(message); this.hint = hint; }
  }

  /** Polls a job while rendering `groups` (earlier groups first); returns the final JobView. `onJob` sees every update. */
  async function runJob(jobId, title, doneGroups, onJob) {
    const cancel = async () => { try { await api.cancelJob(jobId); } catch { /* the next poll shows the outcome */ } };
    let latest = null;
    const j = await api.pollJob(jobId, (job) => {
      latest = job;
      renderPanel([...doneGroups, { title, job }], { cancel: job.state === 'RUNNING' ? cancel : null });
      if (onJob) onJob(job);
    }, () => renderPanel([...doneGroups, { title, job: latest, pending: 'Waiting for the server\u2026' }],
      { cancel, note: 'Connection to MemScope lost, retrying\u2026 (the job keeps running on the server)' }));
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

  /** Runs one pipeline pass; on failure shows the error with a Retry that resumes from the stage reached. */
  async function guarded(run, initialRetry) {
    if (busy) return;
    busy = true;
    updateButtons();
    const ctl = { retry: initialRetry };
    try { await run(ctl); } catch (e) { showFailure(e, ctl.retry); } finally { busy = false; updateButtons(); }
  }

  const dumpNow = () => guarded(async (ctl) => {
    const serial = deviceSel.value;
    const pkg = appSel.value;
    const process = procField.hidden ? pkg : (procSel.value || pkg);
    session = await ensureSession(serial, pkg);
    const { jobId } = await api.dump(session.id, process === pkg ? {} : { processName: process });
    await dumpFrom(jobId, process, ctl);
  }, dumpNow);

  // ------------------------------------------------------------ startup recording

  const recordStartup = () => guarded(async (ctl) => {
    const serial = deviceSel.value;
    const pkg = appSel.value;
    renderPanel([{ title: 'Record startup', job: null, pending: 'Loading the milestones…' }]);
    const defaults = await api.milestoneDefaults();
    hidePanel();
    const config = await askStartupConfig({
      pkg, processes: processNames(), defaults, launcher: api.launcher(serial, pkg).then((r) => r.component),
    });
    if (!config) return;
    const title = `Record startup of ${config.processName || pkg}`;
    renderPanel([{ title, job: null, pending: 'Starting the app under the debugger…' }]);
    const started = await api.createStartupSession(serial, pkg, config);
    session = started.session;
    renderSnapshots();
    await startupFrom(started.jobId, title, ctl);
  }, recordStartup);

  /** Every pulled milestone dump shows up in the snapshot selector at once; the newest one is opened at the end. */
  async function startupFrom(jobId, title, ctl) {
    ctl.retry = recordStartup;
    const sid = session.id;
    let pulled = 0;
    const refresh = async () => {
      try {
        const s = await api.session(sid);
        if (!session || session.id !== sid) return;
        const keep = snapSel.value;
        session = s;
        renderSnapshots(s.snapshots.some((x) => x.id === keep) ? keep : lastOk(s)?.id);
      } catch { /* the final refresh below reports problems */ }
    };
    const job = await runJob(jobId, title, [], (j) => {
      const n = j.steps.filter((st) => st.name.startsWith('Dump: ') && st.state === 'DONE').length;
      if (n !== pulled) { pulled = n; refresh(); }
    });
    session = await api.session(sid);
    const last = lastOk(session);
    renderSnapshots(last?.id);
    const failure = job.state === 'DONE' ? null : failJob(job, 'Startup recording');
    if (last) {
      ctl.retry = failure ? recordStartup : () => retryOpen(last.id);
      await analyse(sid, last.id, [{ title, job }]);
    } else if (!failure) {
      throw new PipelineError('The recording finished without a snapshot', 'Every milestone failed; see the steps above for the reasons.');
    }
    if (failure) { ctl.retry = recordStartup; throw failure; }
  }

  const resumeDump = (jobId, pkg) => guarded((ctl) => dumpFrom(jobId, pkg, ctl), () => resumeDump(jobId, pkg));

  /** Poll the dump job, then analyse its snapshot. Retry never starts a second dump once a job exists. */
  async function dumpFrom(jobId, pkg, ctl) {
    ctl.retry = () => resumeDump(jobId, pkg);
    const dumpTitle = `Dump heap of ${pkg}`;
    renderPanel([{ title: dumpTitle, job: null, pending: 'Starting the dump\u2026' }]);
    const dj = await runJob(jobId, dumpTitle, []);
    if (dj.state !== 'DONE') { ctl.retry = dumpNow; throw failJob(dj, 'Heap dump'); }
    const snapId = dj.resultRef;
    ctl.retry = () => retryOpen(snapId);
    try { session = await api.session(session.id); renderSnapshots(snapId); } catch { /* retryOpen refreshes the list */ }
    await analyse(session.id, snapId, [{ title: dumpTitle, job: dj }]);
  }

  async function analyse(sid, snapId, doneGroups) {
    const done = await runOpen(sid, snapId, doneGroups, 'Analyse snapshot');
    renderPanel([...doneGroups, { title: 'Analyse snapshot', job: done }],
      { success: `Snapshot ready (analysed in ${(done.elapsedMs / 1000).toFixed(1)} s).` });
    hideTimer = setTimeout(hidePanel, 4000);
    await onSnapshot(sid, selectedSnapshotMeta() || { id: snapId });
  }

  const retryOpen = (snapId) => guarded(async (ctl) => {
    ctl.retry = () => retryOpen(snapId);
    try { session = await api.session(session.id); renderSnapshots(snapId); } catch { /* the snapshot id is already known */ }
    await analyse(session.id, snapId, []);
  }, () => retryOpen(snapId));

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
    renderProcesses();
    updateButtons();
    // Switching app: show the active session's snapshots if it is for this app.
    let all;
    try { all = await api.sessions(); } catch (e) { reportLoad(e); return; }
    session = all.find((s) => s.state === 'ACTIVE' && s.serial === deviceSel.value && s.packageName === appSel.value) || null;
    renderSnapshots(session && lastOk(session)?.id);
  });
  $('device-refresh').addEventListener('click', () => loadDevices().catch(reportLoad));
  $('app-refresh').addEventListener('click', () => loadApps(true).catch(reportLoad));
  dumpBtn.addEventListener('click', dumpNow);
  startupBtn.addEventListener('click', recordStartup);
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
  /** Marker click on the timeline: select that snapshot in the selector and open it. */
  async function selectSnapshot(id) {
    if (!session || busy || !session.snapshots.some((s) => s.id === id)) return;
    snapSel.value = id;
    const meta = selectedSnapshotMeta();
    if (meta && meta.status !== 'OK') {
      // unreadable or failed: say why instead of trying to open it
      const unreadable = meta.status === 'UNREADABLE';
      showFailure({
        message: `Snapshot ${meta.label || meta.id} cannot be opened (${meta.status.toLowerCase()})`,
        hint: (meta.error ? meta.error + ' ' : '') + (unreadable
          ? 'Retry checks the file again and re-analyses it if it is intact; otherwise take a new dump to replace it.'
          : 'Take a new dump to replace it.'),
      }, unreadable ? () => retryOpen(id) : undefined); // the server re-validates an unreadable file on open
      return;
    }
    if (meta) await onSnapshot(session.id, meta);
  }

  return { init, refreshDevices: () => loadDevices(), selectSnapshot };
}
