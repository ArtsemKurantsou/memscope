// Thin client for the MemScope HTTP API. Every failure becomes an ApiError with the server's
// error / hint / kind so the UI can always say what went wrong and what to do.
import { sleep } from './util.js';

export class ApiError extends Error {
  constructor(status, body) {
    super(body.error || 'Request failed');
    this.status = status;
    this.kind = body.kind || 'UNKNOWN';
    this.hint = body.hint || '';
  }
  get isNetwork() { return this.kind === 'NETWORK'; }
  get notAnalysed() { return this.status === 409 && this.kind === 'NOT_ANALYSED'; }
}

export const hooks = {
  /** Called with true when the server cannot be reached, false after a request got through. */
  onReachability: (_ok) => {},
  /** Re-opens an evicted snapshot; replaced by capture.js so the stepper shows progress. */
  reopen: null,
};

async function request(method, path, body) {
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: 'no-store',
    });
  } catch (e) {
    hooks.onReachability(false);
    throw new ApiError(0, {
      error: 'Cannot reach the MemScope server',
      hint: 'Is the memscope process still running? Start it again and press Retry.',
      kind: 'NETWORK',
    });
  }
  hooks.onReachability(true);
  const text = await res.text();
  let json = null;
  if (text) { try { json = JSON.parse(text); } catch { /* not JSON */ } }
  if (!res.ok) {
    throw new ApiError(res.status, json && json.error ? json : { error: `HTTP ${res.status} ${res.statusText}`, hint: text.slice(0, 200), kind: 'HTTP' });
  }
  return json;
}

const get = (p) => request('GET', p);
const post = (p, b) => request('POST', p, b === undefined ? {} : b);
const del = (p) => request('DELETE', p);
const q = (params) => '?' + Object.entries(params).filter(([, v]) => v != null).map(([k, v]) => `${k}=${encodeURIComponent(v)}`).join('&');

export const devices = () => get('/api/devices');
export const apps = (serial, refresh) => get(`/api/devices/${encodeURIComponent(serial)}/apps${refresh ? '?refresh=1' : ''}`);
export const sessions = () => get('/api/sessions');
export const session = (id) => get(`/api/sessions/${id}`);
/** Deletes a session and all its files; answers { deleted, freedBytes }. 409 BUSY while a job of it runs. */
export const deleteSession = (id) => del(`/api/sessions/${id}`);
/** Deletes one snapshot (hprof and analysis files); answers { deleted, freedBytes }. */
export const deleteSnapshot = (sid, snap) => del(`/api/snapshots/${sid}/${snap}`);
export const createSession = (serial, packageName) => post('/api/sessions', { serial, packageName, mode: 'ATTACH' });
export const dump = (sessionId, body) => post(`/api/sessions/${sessionId}/dump`, body || {});
/** Starts a startup recording; answers { session, jobId }. */
export const createStartupSession = (serial, packageName, startup) => post('/api/sessions', { serial, packageName, mode: 'STARTUP', startup });
/** Memory samples newer than sinceMs (all when null) plus every snapshot marker of a session. */
export const timeline = (id, sinceMs) => get(`/api/sessions/${id}/timeline` + (sinceMs == null ? '' : `?sinceMs=${sinceMs}`));
export const milestoneDefaults = () => get('/api/milestones/defaults');
export const launcher = (serial, pkg) => get(`/api/devices/${encodeURIComponent(serial)}/apps/${encodeURIComponent(pkg)}/launcher`);
export const job = (id) => get(`/api/jobs/${id}`);
export const cancelJob = (id) => post(`/api/jobs/${id}/cancel`);
export const openSnapshot = (sid, snap) => post(`/api/snapshots/${sid}/${snap}/open`);
/** Findings of the session rules over the per-snapshot summaries: { findings, pending }. */
export const sessionFindings = (id) => get(`/api/sessions/${id}/findings`);

/** Polls a job every 500 ms, calling onUpdate with each JobView, until it leaves RUNNING. onLost(n) is called while the server is unreachable. */
export async function pollJob(jobId, onUpdate, onLost) {
  let lost = 0;
  for (;;) {
    let j;
    try {
      j = await job(jobId);
      lost = 0;
    } catch (e) {
      // The job keeps running on the server: back off and keep polling (bounded, about two minutes).
      if (!e.isNetwork || ++lost > MAX_LOST_POLLS) throw e;
      if (onLost) onLost(lost);
      await sleep(Math.min(500 * 2 ** lost, 5000));
      continue;
    }
    onUpdate(j);
    if (j.state !== 'RUNNING') return j;
    await sleep(500);
  }
}
const MAX_LOST_POLLS = 30;

const reopening = new Map();
async function reopen(sid, snap) {
  const key = sid + '/' + snap;
  if (!reopening.has(key)) {
    const run = hooks.reopen ? hooks.reopen(sid, snap) : defaultReopen(sid, snap);
    reopening.set(key, run.finally(() => reopening.delete(key)));
  }
  return reopening.get(key);
}
async function defaultReopen(sid, snap) {
  const { jobId } = await openSnapshot(sid, snap);
  const j = await pollJob(jobId, () => {});
  if (j.state !== 'DONE') throw new ApiError(500, { error: j.errorMessage || 'Analysis failed', hint: j.errorHint || '', kind: 'JOB_FAILED' });
}

/**
 * What grew between snapshot `baseId` (before) and `snap` (after). Both must be open on the server: a 409
 * "not analysed: <id>" re-opens the named one and retries (the other is touched first so it stays the more
 * recently used of the two). `isCurrent` says whether the caller still wants the result; once it is false no snapshot is re-opened.
 */
async function diff(sid, snap, baseId, isCurrent = () => true) {
  const url = `/api/snapshots/${sid}/${snap}/diff` + q({ base: baseId });
  for (let attempt = 0; ; attempt++) {
    try { return await get(url); } catch (e) {
      if (!e.notAnalysed || attempt >= 2) throw e;
      if (!isCurrent()) throw new ApiError(0, { error: "Superseded", hint: "", kind: "SUPERSEDED" }); // the user moved on: do not evict what they now browse
      const missing = /^not analysed: (\S+)/.exec(e.message)?.[1] || snap;
      const open = missing === snap ? baseId : snap;
      await get(`/api/snapshots/${sid}/${open}/summary`).catch(() => {});
      await reopen(sid, missing);
    }
  }
}

/** Snapshot-scoped calls; a 409 "not analysed" triggers one transparent re-open and a retry. */
export function snapshotApi(sid, snap) {
  const base = `/api/snapshots/${sid}/${snap}`;
  const call = async (path, send = get) => {
    try { return await send(base + path); } catch (e) {
      if (!e.notAnalysed) throw e;
      await reopen(sid, snap);
      return send(base + path);
    }
  };
  return {
    sid, snap,
    summary: () => call('/summary'),
    tree: (ref, offset = 0, limit = 25) => call('/tree' + q({ ref, offset, limit })),
    object: (ref) => call('/object' + q({ ref })),
    reveal: (ref) => call('/reveal' + q({ ref })),
    indexInParent: (parent, child) => call('/indexInParent' + q({ parent, child })),
    classes: () => call('/classes'),
    /** { findings } once computed, else { jobId } of the job computing them (poll it, then ask again); rerun recomputes all of them. */
    findings: (rerun = false) => (rerun ? call('/findings/rerun', post) : call('/findings')),
    instances: (cls, offset = 0, limit = 25) => call('/instances' + q({ class: cls, offset, limit })),
    diff: (baseId, isCurrent) => diff(sid, snap, baseId, isCurrent),
  };
}
