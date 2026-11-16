// Memory timeline: process memory over time (stacked areas), snapshot markers and "held at breakpoint" bands.
// Hand-rolled inline SVG. Data: GET /api/sessions/{id}/timeline?sinceMs= (samples in kB, times in ms since the session started).
import * as api from './api.js';
import { el, prefs } from './util.js';

const SVG_NS = 'http://www.w3.org/2000/svg';
const POLL_MS = 2000;
const MAX_POINTS = 600;      // drawn points; more samples are bucketed (max kept) so an hour of 1 Hz samples stays cheap
const MAX_KEPT = 5000;        // accumulated samples; beyond that the older ones are re-bucketed
const GAP_MS = 20000;        // a longer hole between samples (process gone, polls failing) is not interpolated
const HEIGHT = 210;
const M = { left: 44, right: 14, top: 42, bottom: 24 };

/** Bottom to top. `css` names the custom property holding the colour (light and dark values live in style.css). */
export const SERIES = [
  { key: 'java', name: 'Java', css: '--tl-java', of: (s) => s.javaHeapKb },
  { key: 'native', name: 'Native', css: '--tl-native', of: (s) => s.nativeHeapKb },
  { key: 'graphics', name: 'Graphics', css: '--tl-graphics', of: (s) => s.graphicsKb },
  { key: 'code', name: 'Code', css: '--tl-code', of: (s) => s.codeKb },
  { key: 'other', name: 'Other', css: '--tl-other', of: (s) => s.stackKb + s.otherKb, hint: 'stack + private other + system' },
];

// ---------------------------------------------------------------- pure helpers (also used by selftest.html)

/** At most `max` samples: when there are more, each bucket keeps the sample with the highest total PSS. Order is kept. */
export function downsample(samples, max = MAX_POINTS) {
  if (samples.length <= max) return samples;
  const out = [];
  const size = samples.length / max;
  for (let b = 0; b < max; b++) {
    const from = Math.floor(b * size);
    const to = Math.min(samples.length, Math.floor((b + 1) * size));
    let best = samples[from];
    for (let i = from + 1; i < to; i++) if (samples[i].totalPssKb > best.totalPssKb) best = samples[i];
    out.push(best);
  }
  return out;
}

/** A 1/2/2.5/5 x 10^n step so that `max / step` is about `target` (3 by default). */
export function niceStep(max, target = 3) {
  if (!(max > 0)) return 1;
  const raw = max / target;
  const pow = 10 ** Math.floor(Math.log10(raw));
  const f = raw / pow;
  return (f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10) * pow;
}

/** X-axis step in seconds: the smallest "round" step that gives at most `maxTicks` ticks over `spanSec`. */
export function timeStep(spanSec, maxTicks = 8) {
  const steps = [1, 2, 5, 10, 15, 30, 60, 120, 300, 600, 900, 1800, 3600, 7200, 14400];
  return steps.find((s) => spanSec / s <= maxTicks) || steps[steps.length - 1];
}

/** Keeps at most MAX_KEPT samples: when over, the older part is thinned (peaks kept) and the newest 1000 stay exact. */
export function capSamples(samples, maxKept = MAX_KEPT) {
  if (samples.length <= maxKept) return samples;
  const recent = samples.slice(-1000);
  return downsample(samples.slice(0, samples.length - 1000), Math.floor(maxKept / 2)).concat(recent);
}

/** [from, to] bands (ms) of the held intervals the server recorded; an interval still open ends at `endMs`. */
export function heldBands(held, endMs) {
  return (held || []).map((h) => [h.startMs, h.endMs == null ? Math.max(h.startMs, endMs) : h.endMs]);
}

/** Index of the sample closest in time to `t` (samples sorted by tMs, non-empty). */
export function nearestIndex(samples, t) {
  let lo = 0;
  let hi = samples.length - 1;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (samples[mid].tMs < t) lo = mid + 1; else hi = mid;
  }
  if (lo > 0 && t - samples[lo - 1].tMs <= samples[lo].tMs - t) return lo - 1;
  return lo;
}

const mb = (kb) => kb / 1024;
const fmtMb = (kb) => mb(kb).toFixed(1) + ' MB';
const fmtSec = (ms) => (ms / 1000).toFixed(ms < 10000 ? 1 : 0) + ' s';

function svg(tag, attrs, ...children) {
  const e = document.createElementNS(SVG_NS, tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) { if (v != null && v !== false) e.setAttribute(k, String(v)); }
  for (const c of children.flat()) if (c != null) e.append(c);
  return e;
}

// ---------------------------------------------------------------- component

/**
 * `container`: the section to fill. `onSelectSnapshot(id)`: a marker was clicked. Returns
 * { setSession(sessionMeta|null), setSelected(snapshotId), refresh(), destroy() }.
 */
export function createTimeline({ container, onSelectSnapshot, pollMs = POLL_MS }) {
  let sessionId = null;
  let active = false;          // the session is ACTIVE: keep polling
  let samples = [];
  let markers = [];
  let selected = null;
  let loadedOnce = false;
  let failure = null;
  let hover = null;            // index into `samples`
  let held = [];
  let highlight = null;        // snapshot id whose marker is emphasised (chip hover / focus)
  let pointerIn = false;       // the pointer is over the plot: do not rebuild it under the pointer
  let dirty = false;           // a redraw was deferred because of pointerIn
  let chipSig = '';
  let collapsed = prefs.get('timelineCollapsed', false);
  let timer = null;
  let token = 0;               // drops responses of an earlier session / request
  let busy = false;

  const toggle = el('button', { class: 'btn small tl-toggle', type: 'button', 'aria-controls': 'tl-body', onclick: () => setCollapsed(!collapsed) });
  const title = el('strong', null, 'Memory timeline');
  const legend = el('div', { class: 'tl-legend' });
  const head = el('div', { class: 'tl-head' }, toggle, title, legend);
  const plot = el('div', { class: 'tl-plot' });
  const note = el('div', { class: 'tl-note muted' }, "Memory rises after each dump; this is the dump's own cost. While the app is held at a breakpoint only the total is measured (smaps_rollup), so the stack may not add up to the total line there.");
  const chips = el('div', { class: 'tl-chips', role: 'group', 'aria-label': 'Snapshots on the timeline' });
  const status = el('div', { class: 'tl-status', role: 'status', hidden: true });
  const body = el('div', { class: 'tl-body', id: 'tl-body' }, plot, chips, status, note);
  container.classList.add('timeline');
  container.replaceChildren(head, body);

  function setCollapsed(on) {
    collapsed = on;
    prefs.set('timelineCollapsed', on);
    applyCollapsed();
    if (!on) { render(); schedule(true); }
  }
  function applyCollapsed() {
    body.hidden = collapsed;
    toggle.setAttribute('aria-expanded', String(!collapsed));
    toggle.textContent = collapsed ? 'Show' : 'Hide';
    toggle.title = collapsed ? 'Show the memory timeline' : 'Hide the memory timeline';
  }

  // ---------------------------------------------------------- data

  function reset() {
    token++;
    samples = []; markers = []; held = []; chipSig = ''; pointerIn = false; dirty = false; loadedOnce = false; failure = null; hover = null; busy = false;
    clearTimeout(timer); timer = null;
  }

  function setSession(session) {
    const id = session ? session.id : null;
    const state = session ? session.state === 'ACTIVE' : false;
    if (id !== sessionId) {
      reset();
      sessionId = id;
      active = state;
      render();
      if (id) schedule(true);
      return;
    }
    const wasActive = active;
    active = state;
    if (wasActive && !active) schedule(true); // one last fetch picks up what was recorded until the end
    else if (!wasActive && active) schedule(true);
  }

  function schedule(now) {
    clearTimeout(timer);
    timer = null;
    if (!sessionId || collapsed) return;
    timer = setTimeout(tick, now ? 0 : pollMs);
  }

  async function tick() {
    timer = null;
    if (!sessionId || collapsed || busy) return;
    if (document.hidden) { schedule(false); return; }
    busy = true;
    const my = token;
    const sid = sessionId;
    const since = samples.length ? samples[samples.length - 1].tMs : null;
    try {
      const [t, s] = await Promise.all([api.timeline(sid, since), api.session(sid).catch(() => null)]);
      if (my !== token) return;
      if (s) active = s.state === 'ACTIVE';
      if (since == null) samples = t.samples; else if (t.samples.length) samples = capSamples(samples.concat(t.samples));
      markers = t.markers;
      held = t.held || [];
      loadedOnce = true;
      failure = null;
      if (hover != null && hover >= samples.length) hover = null;
    } catch (e) {
      if (my !== token) return;
      failure = e.isNetwork ? null : (e.message || String(e));
    } finally {
      if (my === token) {
        busy = false;
        render();
        if (active || !loadedOnce) schedule(false);
      }
    }
  }

  // ---------------------------------------------------------- rendering

  function renderLegend() {
    const last = samples.length ? samples[samples.length - 1] : null;
    const items = SERIES.map((s) => el('span', { class: 'tl-key', title: s.hint || s.name },
      el('i', { class: 'tl-sw', style: `background: var(${s.css})` }), s.name, last ? el('b', { class: 'num' }, ' ' + fmtMb(s.of(last))) : null));
    items.push(el('span', { class: 'tl-key', title: 'Total PSS of the process' }, el('i', { class: 'tl-sw tl-sw-line' }), 'Total', last ? el('b', { class: 'num' }, ' ' + fmtMb(last.totalPssKb)) : null));
    items.push(el('span', { class: 'tl-key', title: 'The app was stopped by the debugger at a milestone; only the total is measured then' },
      el('i', { class: 'tl-sw tl-sw-held' }), 'held at breakpoint'));
    legend.replaceChildren(...items);
  }

  function message(text, extra) {
    plot.replaceChildren(el('div', { class: 'tl-empty', role: 'status' }, text, extra));
  }

  function renderStatus() {
    // a failure after the first load is shown, not swallowed; polling goes on and Retry asks again at once
    if (failure && loadedOnce) {
      status.hidden = false;
      status.replaceChildren(`Could not refresh the timeline: ${failure} `, el('button', { class: 'btn small', type: 'button', onclick: () => schedule(true) }, 'Retry'));
    } else {
      status.hidden = true;
      status.replaceChildren();
    }
  }

  /** Numbered snapshot buttons: a stable, easy target row that mirrors the markers (same numbers and shapes). */
  function renderChips() {
    const sig = markers.map((m) => `${m.snapshotId}|${m.kind}|${m.label}`).join('\n') + '\n' + selected;
    if (sig === chipSig) return;
    chipSig = sig;
    chips.hidden = !markers.length;
    // the rebuild would drop keyboard focus from the activated chip
    const focusedAt = [...chips.children].findIndex((c) => c === document.activeElement);
    chips.replaceChildren(...markers.map((m, i) => {
      const isMilestone = m.kind === 'milestone';
      const label = `${i + 1}. ${m.label} (${isMilestone ? 'milestone' : 'dump'}) at ${fmtSec(m.tMs)}`;
      const b = el('button', {
        type: 'button', class: 'tl-chip ' + (isMilestone ? 'milestone' : 'dump') + (m.snapshotId && m.snapshotId === selected ? ' selected' : ''),
        title: label, 'aria-label': 'Select snapshot ' + label, 'aria-pressed': m.snapshotId === selected ? 'true' : 'false', disabled: !m.snapshotId,
      }, String(i + 1));
      b.addEventListener('click', () => onSelectSnapshot && m.snapshotId && onSelectSnapshot(m.snapshotId));
      b.addEventListener('mouseenter', () => setHighlight(m.snapshotId));
      b.addEventListener('focus', () => setHighlight(m.snapshotId));
      b.addEventListener('mouseleave', () => setHighlight(null));
      b.addEventListener('blur', () => setHighlight(null));
      return b;
    }));
    if (focusedAt >= 0) chips.children[focusedAt]?.focus({ preventScroll: true });
  }

  function setHighlight(id) {
    highlight = id;
    for (const g of plot.querySelectorAll('.tl-marker')) g.classList.toggle('hl', !!id && g.dataset.snapshot === id);
  }

  function render() {
    applyCollapsed();
    renderLegend();
    renderStatus();
    renderChips();
    if (collapsed) return;
    if (pointerIn && plot.querySelector('.tl-svg') && samples.length >= 2) { dirty = true; return; } // never rebuild under the pointer
    if (!sessionId) { message('No session yet. Memory samples appear here once you dump an app or record its startup.'); return; }
    if (failure && !samples.length) { message('Could not load the memory timeline: ' + failure); return; }
    if (!loadedOnce) { message(el('span', { class: 'spinner' }), 'Loading the memory timeline…'); return; }
    if (samples.length < 2) {
      if (!active && samples.length === 0) message('No memory samples were recorded for this session.');
      else message(el('span', { class: 'spinner' }), 'Collecting memory samples…');
      return;
    }
    draw();
  }

  function draw() {
    const width = Math.max(320, Math.floor(plot.clientWidth || container.clientWidth || 800));
    const pw = width - M.left - M.right;
    const ph = HEIGHT - M.top - M.bottom;
    let lastT = Math.max(samples[samples.length - 1].tMs, 1000);
    for (const m of markers) if (m.tMs > lastT) lastT = m.tMs;
    const pts = downsample(samples);
    const stackSum = (s) => SERIES.reduce((a, d) => a + d.of(s), 0);
    let maxKb = 1;
    for (const s of pts) maxKb = Math.max(maxKb, s.totalPssKb, stackSum(s));
    const step = niceStep(mb(maxKb), 3);
    const top = Math.max(step, Math.ceil(mb(maxKb) / step) * step);
    const x = (t) => M.left + (t / lastT) * pw;
    const y = (kb) => M.top + ph - (mb(kb) / top) * ph;

    // contiguous runs (a hole in the data is not interpolated)
    const runs = [];
    let cur = [];
    for (const s of pts) {
      if (cur.length && s.tMs - cur[cur.length - 1].tMs > GAP_MS) { runs.push(cur); cur = []; }
      cur.push(s);
    }
    runs.push(cur);

    const children = [];
    // paused bands, under the data
    const bands = heldBands(held, lastT);
    const bandEls = bands.map(([from, to]) => {
      const bx = x(from);
      const bw = Math.max(2, x(Math.min(to, lastT)) - bx);
      const g = svg('g', { class: 'tl-band' },
        svg('title', null, `Held at breakpoint, ${fmtSec(from)} to ${fmtSec(to)}`),
        svg('rect', { x: bx, y: M.top, width: bw, height: ph, fill: 'url(#tl-hatch)' }),
        svg('rect', { x: bx, y: M.top, width: bw, height: ph, class: 'tl-band-edge' }));
      if (bw >= 96) g.append(svg('text', { x: bx + bw / 2, y: M.top + ph - 6, 'text-anchor': 'middle', class: 'tl-band-label' }, 'held at breakpoint'));
      return g;
    });

    // gridlines and y labels
    const grid = [];
    for (let v = 0; v <= top + 1e-9; v += step) {
      const gy = M.top + ph - (v / top) * ph;
      grid.push(svg('line', { x1: M.left, x2: M.left + pw, y1: gy, y2: gy, class: v === 0 ? 'tl-axis' : 'tl-grid' }));
      grid.push(svg('text', { x: M.left - 6, y: gy + 4, 'text-anchor': 'end', class: 'tl-tick' }, String(Math.round(v * 10) / 10)));
    }
    grid.push(svg('text', { x: 4, y: M.top - 8, class: 'tl-tick' }, 'MB'));
    // x ticks in seconds since the session started
    const tstep = timeStep(lastT / 1000);
    for (let sec = 0; sec * 1000 <= lastT; sec += tstep) {
      const tx = x(sec * 1000);
      grid.push(svg('line', { x1: tx, x2: tx, y1: M.top + ph, y2: M.top + ph + 4, class: 'tl-axis' }));
      grid.push(svg('text', { x: tx, y: M.top + ph + 16, 'text-anchor': sec === 0 ? 'start' : 'middle', class: 'tl-tick' }, sec + ' s'));
    }

    // stacked areas
    const cum = (s, upTo) => { let a = 0; for (let i = 0; i < upTo; i++) a += SERIES[i].of(s); return a; };
    const areas = SERIES.map((d, k) => {
      let path = '';
      for (const run of runs) {
        if (run.length < 2) continue;
        const upper = run.map((s) => `${x(s.tMs).toFixed(1)},${y(cum(s, k + 1)).toFixed(1)}`);
        const lower = run.map((s) => `${x(s.tMs).toFixed(1)},${y(cum(s, k)).toFixed(1)}`).reverse();
        path += `M${upper.join('L')}L${lower.join('L')}Z`;
      }
      return svg('path', { d: path, class: 'tl-area', style: `fill: var(${d.css})` }, svg('title', null, d.name));
    });
    // total PSS line
    let totalD = '';
    for (const run of runs) totalD += run.map((s, i) => `${i ? 'L' : 'M'}${x(s.tMs).toFixed(1)},${y(s.totalPssKb).toFixed(1)}`).join('');
    const totalLine = svg('path', { d: totalD, class: 'tl-total' });

    // markers: milestones are solid lines with round badges, plain dumps dashed with square badges
    const markerEls = [];
    let prevX = -Infinity;
    let row = 0;
    markers.forEach((m, i) => {
      const mx = x(m.tMs);
      row = mx - prevX < 22 ? (row + 1) % 2 : 0;
      prevX = mx;
      const by = 12 + row * 20;
      const num = i + 1;
      const isMilestone = m.kind === 'milestone';
      const sel = m.snapshotId && m.snapshotId === selected;
      const label = `${num}. ${m.label} (${isMilestone ? 'milestone' : 'dump'}) at ${fmtSec(m.tMs)}`;
      const badge = isMilestone
        ? svg('circle', { cx: mx, cy: by, r: 9, class: 'tl-badge' })
        : svg('rect', { x: mx - 9, y: by - 9, width: 18, height: 18, rx: 3, class: 'tl-badge' });
      const g = svg('g', {
        class: 'tl-marker' + (isMilestone ? ' milestone' : ' dump') + (sel ? ' selected' : '') + (m.snapshotId && m.snapshotId === highlight ? ' hl' : ''),
        tabindex: m.snapshotId ? 0 : null, role: 'button', 'aria-label': 'Select snapshot ' + label, 'data-snapshot': m.snapshotId || null,
      },
      svg('title', null, label),
      svg('line', { x1: mx, x2: mx, y1: by + 9, y2: M.top + ph, class: 'tl-mline' }),
      badge,
      svg('text', { x: mx, y: by + 4, 'text-anchor': 'middle', class: 'tl-mnum' }, String(num)));
      if (m.snapshotId) {
        const pick = (ev) => { ev.stopPropagation(); onSelectSnapshot && onSelectSnapshot(m.snapshotId); };
        g.addEventListener('pointerdown', pick); // not click: nothing may replace the element between press and release
        g.addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); pick(ev); } });
      }
      markerEls.push(g);
    });

    // hover layer
    const cross = svg('line', { y1: M.top, y2: M.top + ph, class: 'tl-cross', visibility: 'hidden' });
    const dots = svg('g', { class: 'tl-dots' });
    const hit = svg('rect', { x: M.left, y: M.top, width: pw, height: ph, fill: 'transparent', class: 'tl-hit' });

    const defs = svg('defs', null,
      svg('pattern', { id: 'tl-hatch', width: 7, height: 7, patternUnits: 'userSpaceOnUse', patternTransform: 'rotate(45)' },
        svg('rect', { width: 7, height: 7, class: 'tl-hatch-bg' }), svg('line', { x1: 0, y1: 0, x2: 0, y2: 7, class: 'tl-hatch-line' })));

    const root = svg('svg', {
      width, height: HEIGHT, viewBox: `0 0 ${width} ${HEIGHT}`, class: 'tl-svg', role: 'img', tabindex: 0,
      'aria-label': `Memory over time: ${SERIES.map((d) => d.name).join(', ')} and total PSS in MB against seconds since the session started. ` +
        `${markers.length} snapshot marker${markers.length === 1 ? '' : 's'}.`,
    }, defs, ...bandEls, ...grid, ...areas, totalLine, ...markerEls, cross, dots, hit);

    const readout = el('div', { class: 'tl-readout', hidden: true, role: 'status' });
    plot.replaceChildren(root, readout);

    function showAt(i) {
      hover = i;
      const s = samples[i];
      const cx = x(s.tMs);
      cross.setAttribute('x1', cx); cross.setAttribute('x2', cx); cross.setAttribute('visibility', 'visible');
      dots.replaceChildren(...SERIES.map((d, k) => svg('circle', { cx, cy: y(cum(s, k + 1)), r: 3, class: 'tl-dot', style: `fill: var(${d.css})` })));
      readout.replaceChildren(
        el('div', { class: 'tl-r-time' }, `${fmtSec(s.tMs)} after start`, s.paused ? el('span', { class: 'tl-r-held' }, ' held at breakpoint') : null),
        ...SERIES.slice().reverse().map((d) => el('div', { class: 'tl-r-row' }, el('i', { class: 'tl-sw', style: `background: var(${d.css})` }), d.name, el('b', { class: 'num' }, fmtMb(d.of(s))))),
        el('div', { class: 'tl-r-row tl-r-total' }, el('i', { class: 'tl-sw tl-sw-line' }), 'Total', el('b', { class: 'num' }, fmtMb(s.totalPssKb))));
      readout.hidden = false;
      const rw = readout.offsetWidth;
      const left = cx + 14 + rw > width ? cx - 14 - rw : cx + 14;
      readout.style.left = Math.max(0, left) + 'px';
      readout.style.top = M.top + 'px';
    }
    function hideHover() {
      hover = null;
      cross.setAttribute('visibility', 'hidden');
      dots.replaceChildren();
      readout.hidden = true;
    }
    hit.addEventListener('pointermove', (ev) => {
      const r = hit.getBoundingClientRect();
      const t = ((ev.clientX - r.left) / r.width) * lastT;
      showAt(nearestIndex(samples, Math.max(0, t)));
    });
    hit.addEventListener('pointerleave', hideHover);
    root.addEventListener('keydown', (ev) => {
      if (ev.target !== root || (ev.key !== 'ArrowLeft' && ev.key !== 'ArrowRight')) return;
      ev.preventDefault();
      const next = hover == null ? samples.length - 1 : Math.min(samples.length - 1, Math.max(0, hover + (ev.key === 'ArrowLeft' ? -1 : 1)));
      showAt(next);
    });
    root.addEventListener('blur', hideHover);
    if (hover != null && hover < samples.length) showAt(hover);
  }

  const resizeObserver = typeof ResizeObserver === 'function' ? new ResizeObserver(() => { if (samples.length >= 2 && !collapsed) render(); }) : null;
  if (resizeObserver) resizeObserver.observe(container);
  plot.addEventListener('pointerenter', () => { pointerIn = true; });
  plot.addEventListener('pointerleave', () => { pointerIn = false; if (dirty) { dirty = false; render(); } });
  const onVisible = () => { if (!document.hidden && sessionId) schedule(true); };
  document.addEventListener('visibilitychange', onVisible);

  applyCollapsed();
  render();

  return {
    setSession,
    setSelected(id) {
      selected = id;
      render();
      // a deferred redraw (pointer over the plot) must not leave the old selection highlighted
      for (const g of plot.querySelectorAll('.tl-marker')) g.classList.toggle('selected', g.dataset.snapshot === id);
    },
    refresh() { if (sessionId) schedule(true); },
    destroy() { reset(); sessionId = null; document.removeEventListener('visibilitychange', onVisible); if (resizeObserver) resizeObserver.disconnect(); },
  };
}
