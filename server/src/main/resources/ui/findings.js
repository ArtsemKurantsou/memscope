// Findings tab: rule-based "what looks wrong and what to do" cards for the open snapshot, plus a
// section for rules that compare the session's snapshots ("Across snapshots"). All text is set as
// text nodes (class names, field names and string contents come from the heap dump).
import * as api from './api.js';
import { el, formatBytes } from './util.js';

const SEVERITIES = [['HIGH', 'High'], ['MEDIUM', 'Medium'], ['LOW', 'Low']];

/** What each rule step shows while the findings are computed. */
export const RULE_LABELS = {
  'leaked-component': 'Looking for leaked Activities, Fragments and Views (Shark leak analysis, can take a while)',
  'oversized-bitmap': 'Checking bitmap sizes against the screen',
  'duplicate-bitmaps': 'Looking for possible duplicate bitmaps',
  'duplicate-strings': 'Scanning the heap dump for duplicate strings',
  'duplicate-arrays': 'Scanning the heap dump for duplicate arrays',
  'sparse-collection': 'Looking for collections with a mostly empty backing array',
  'large-static': 'Measuring what static fields keep alive',
  'dominant-class': 'Looking for a class that dominates the heap',
};

/** Groups findings by severity in display order; empty groups are omitted. Pure. */
export function groupBySeverity(findings) {
  return SEVERITIES.map(([key, label]) => ({ key, label, items: findings.filter((f) => f.severity === key) })).filter((g) => g.items.length);
}

/** { total, high } for the tab badge. Pure. */
export function badgeCounts(findings) {
  return { total: findings.length, high: findings.filter((f) => f.severity === 'HIGH').length };
}

/** The text of the running step of a findings job, e.g. "Scanning the heap dump for duplicate strings". Pure. */
export function runningStepText(job) {
  const step = (job.steps || []).find((s) => s.state === 'RUNNING' || s.detail === 'running');
  return step ? (RULE_LABELS[step.name] || step.name) : 'Starting';
}

const EVIDENCE_COLLAPSE_LINES = 6;
const EVIDENCE_COLLAPSE_CHARS = 600;

export function createFindings({ container, getSession, onShow, onBadge, onAnalyseAll }) {
  let snap = null;                 // snapshotApi of the open snapshot
  const results = new Map();       // snapshot id -> findings (computed once; the dump never changes)
  let token = 0;
  let session = null;              // { findings, pending } of the last session request

  function labelOf(snapshotId) {
    const s = getSession();
    const m = s && s.snapshots.find((x) => x.id === snapshotId);
    return m ? (m.label || m.id) : snapshotId;
  }

  function chip(sev) {
    return el('span', { class: 'chip sev-' + sev.toLowerCase() }, sev);
  }

  function evidenceBlock(text) {
    const lines = text.split('\n');
    const pre = el('pre', { class: 'evidence mono' }, text);
    if (lines.length <= EVIDENCE_COLLAPSE_LINES && text.length <= EVIDENCE_COLLAPSE_CHARS) return el('div', null, el('div', { class: 'ev-label' }, 'Evidence'), pre);
    return el('details', { class: 'ev-details' }, el('summary', null, `Evidence (${lines.length} line${lines.length === 1 ? '' : 's'})`), pre);
  }

  function card(f) {
    const buttons = f.refs.slice(0, 3).map((ref, i) => el('button', {
      class: 'btn small', type: 'button', title: `Open the tree at this object${f.snapshotId ? ' in ' + labelOf(f.snapshotId) : ''}`,
      onclick: (e) => show(e.currentTarget, ref, f.snapshotId),
    }, f.refs.length > 1 ? `Show in tree (${i + 1})` : 'Show in tree'));
    if (f.ruleId === 'leaked-component' && f.severity === 'LOW') {
      buttons.push(el('button', { class: 'btn small primary', type: 'button', title: 'Run only the slow leak analysis again', onclick: () => load(true) }, 'Run leak analysis again'));
    }
    return el('article', { class: 'fcard sev-' + f.severity.toLowerCase() },
      el('header', { class: 'fhead' }, chip(f.severity), el('h3', { class: 'ftitle' }, f.title),
        f.bytes > 0 ? el('span', { class: 'fsize num', title: 'Bytes this finding is about' }, formatBytes(f.bytes)) : null),
      f.snapshotId ? el('div', { class: 'fsnap muted' }, 'Snapshot: ', labelOf(f.snapshotId)) : null,
      evidenceBlock(f.evidence),
      el('p', { class: 'fsuggest' }, el('strong', null, 'What to do: '), f.suggestion),
      buttons.length ? el('div', { class: 'factions' }, buttons) : null);
  }

  async function show(button, ref, snapshotId) {
    button.disabled = true;
    try { await onShow(ref, snapshotId); } catch (e) {
      button.after(el('span', { class: 'ferr', role: 'alert' }, ` ${e.message || e}${e.hint ? ' ' + e.hint : ''}`));
    } finally { button.disabled = false; }
  }

  function section(title, findings, intro) {
    const groups = groupBySeverity(findings);
    return el('section', { class: 'fsection' }, el('h2', null, title), intro || null,
      groups.map((g) => el('div', { class: 'fgroup' }, el('h3', { class: 'fgroup-title' }, `${g.label} (${g.items.length})`), g.items.map(card))));
  }

  function render(snapFindings) {
    const parts = [];
    if (snapFindings) {
      parts.push(snapFindings.length
        ? section('This snapshot', snapFindings)
        : el('div', { class: 'empty' }, el('h2', null, 'This snapshot'),
          el('p', null, 'No problems found by the built-in rules. That does not prove there are none — check the tree for unexpectedly large owners.')));
    }
    if (session) {
      const intro = session.pending.length
        ? el('div', { class: 'fpending' }, `${session.pending.length} snapshot${session.pending.length === 1 ? ' is' : 's are'} not analysed yet, so the comparison below leaves ${session.pending.length === 1 ? 'it' : 'them'} out: `,
          session.pending.map(labelOf).join(', '), ' ', el('button', { class: 'btn small', type: 'button', onclick: analyseAll }, 'Analyse all snapshots'))
        : null;
      if (session.findings.length || intro) parts.push(section('Across snapshots', session.findings, intro));
      else parts.push(el('div', { class: 'fsection muted' }, 'Across snapshots: nothing found. Growth needs at least 3 analysed snapshots of one session; startup intervals need a startup recording.'));
    }
    container.replaceChildren(...parts);
  }

  async function analyseAll(e) {
    const btn = e.currentTarget;
    const my = token; // a snapshot switch while analysing must not draw into the new snapshot view
    btn.disabled = true;
    try { await onAnalyseAll(session.pending, (i, n) => { btn.textContent = `Analysing ${i} of ${n}…`; }); } catch (err) {
      btn.after(el('span', { class: 'ferr', role: 'alert' }, ` ${err.message || err}${err.hint ? ' ' + err.hint : ''}`));
    }
    if (my !== token) return;
    await refreshSession(my);
    if (my === token) render(snap ? results.get(snap.snap) || null : null);
  }

  function message(...nodes) { container.replaceChildren(el('div', { class: 'empty' }, ...nodes)); }

  function errorPanel(e) {
    container.replaceChildren(el('div', { class: 'error-box', role: 'alert' }, el('strong', null, e.message || String(e)),
      e.hint ? el('p', null, e.hint) : null, el('button', { class: 'btn primary small', type: 'button', onclick: () => load() }, 'Retry')));
  }

  async function refreshSession(my = token) {
    const s = getSession();
    if (my === token) session = null;
    if (!s) return;
    let fresh = null;
    try { fresh = await api.sessionFindings(s.id); } catch { fresh = null; }
    if (my === token) session = fresh;
  }

  /** Computes (or reuses) the findings of the open snapshot and draws them. */
  async function load(rerun = false) {
    if (!snap) { message('No snapshot open.'); return; }
    const my = ++token;
    const mine = snap;
    if (rerun) results.delete(mine.snap);
    const cached = results.get(mine.snap);
    if (cached) { await refreshSession(my); if (my === token) render(cached); return; }
    message(el('span', { class: 'spinner' }), ' Computing findings… ', el('span', { class: 'muted', id: 'findings-step' }, 'Starting'));
    try {
      let r = await mine.findings(rerun);
      if (r.jobId) {
        const j = await api.pollJob(r.jobId, (job) => {
          const t = document.getElementById('findings-step');
          if (t && my === token) t.textContent = runningStepText(job);
        });
        if (j.state !== 'DONE') throw new api.ApiError(500, { error: j.errorMessage || 'Computing findings failed', hint: j.errorHint || '', kind: 'JOB_FAILED' });
        r = await mine.findings();
      }
      results.set(mine.snap, r.findings);
      if (snap === mine) onBadge(badgeCounts(r.findings));
      await refreshSession(my);
      if (my === token) render(r.findings);
    } catch (e) {
      if (my === token) errorPanel(e);
    }
  }

  return {
    /** The Findings tab was opened. */
    show: load,
    /** Another snapshot is open (null: none). Cached results stay per snapshot. */
    setSnapshot(api_) {
      snap = api_;
      token++;
      session = null;
      const c = snap && results.get(snap.snap);
      onBadge(c ? badgeCounts(c) : null);
    },
  };
}
