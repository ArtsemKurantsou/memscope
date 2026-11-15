// Details panel: path from GC root, fields, referrers; explanatory text for category / group rows.
import { el, formatBytes, formatInt, splitClassName, hasObjectDetails } from './util.js';

const CATEGORY_TEXT = {
  'cat:static': 'Objects kept alive by static fields of loaded classes. A class stays loaded for as long as its class loader lives, which for app classes is normally the whole process, so anything reachable from a static field is held for the process lifetime unless the field is cleared. Expand a class to see which static field holds what.',
  'cat:threads': 'Objects referenced from thread stacks: local variables and parameters of running or blocked threads, grouped by thread. These are released when the thread returns from the method, or ends.',
  'cat:jni': 'Objects pinned by native code through JNI global references. They stay alive until native code deletes the reference, so a leak here usually points to a missing DeleteGlobalRef or a never-released native resource.',
  'cat:other': 'Objects kept alive by other GC roots: monitors held by threads, native stack roots, the finalizer queue, and runtime internals.',
  'cat:shared': 'Objects reachable from several GC roots. No single root "owns" them (freeing one root would not free the object), so they sit outside the per-root categories. Expand a row and select it to see which roots hold it.',
};

export function createDetails({ container, getApi, onReveal, getOptions }) {
  let token = 0;
  const history = [];
  let currentRef = null;

  function setContent(...nodes) { container.replaceChildren(...nodes); container.scrollTop = 0; }

  function className(name, cls = 'cn') {
    const sp = splitClassName(name);
    return [el('span', { class: cls }, sp.simple), sp.pkg ? el('span', { class: 'pkg' }, sp.pkg) : null];
  }

  function sizes(retained, shallow, native, appHeap) {
    const items = [['Retained', retained], ['Shallow', shallow], ['Native (est.)', native]];
    if (appHeap != null && getOptions().hideSystem) items.splice(1, 0, ['App heap', appHeap]);
    return el('dl', { class: 'sizes' }, items.map(([k, v]) => [el('dt', null, k), el('dd', { class: 'num' }, formatBytes(v))]));
  }

  function showRow(row) {
    // Called by the tree on selection.
    history.length = 0;
    if (hasObjectDetails(row.ref)) return load(row.ref, row);
    currentRef = row.ref;
    token++;
    const text = row.kind === 'GROUP'
      ? `${formatInt(row.count)} instances of ${row.className} held by the same parent. They are grouped because there are many of the same class. Expand the group to see the individual instances, largest first.`
      : CATEGORY_TEXT[row.ref] || 'A group of GC roots.';
    setContent(
      el('div', { class: 'd-head' }, el('h2', null, row.kind === 'GROUP' ? className(row.className) : row.label)),
      sizes(row.retainedBytes, row.shallowBytes, row.nativeEstBytes, row.appHeapBytes),
      el('p', { class: 'explain' }, text));
  }

  async function load(ref, rowHint) {
    currentRef = ref;
    const my = ++token;
    setContent(el('div', { class: 'd-head' }, el('h2', null, rowHint ? className(rowHint.className || rowHint.label) : ref)),
      el('div', { class: 'status-line' }, el('span', { class: 'spinner' }), 'Loading details…'));
    let d;
    try {
      d = await getApi().object(ref);
    } catch (e) {
      if (my !== token) return;
      setContent(el('div', { class: 'error-box' }, el('strong', null, e.message), e.hint ? el('p', null, e.hint) : null,
        el('button', { class: 'btn small', onclick: () => load(ref, rowHint) }, 'Retry')));
      return;
    }
    if (my !== token) return;
    render(d);
  }

  function navigate(ref) {
    if (currentRef) history.push(currentRef);
    load(ref);
  }

  function refButton(ref, content, title) {
    return el('button', { class: 'link', title: title || ref, onclick: () => navigate(ref) }, content);
  }

  function renderStep(step, last) {
    if (step.ref == null) {
      return el('li', { class: 'step elided' }, `… ${formatInt(step.elided)} more steps …`);
    }
    return el('li', { class: 'step' + (last ? ' target' : '') },
      last ? el('span', { class: 'cls' }, className(step.className)) : refButton(step.ref, className(step.className), step.className),
      step.edgeLabel ? el('span', { class: 'edge-out' }, step.edgeLabel + ' →') : null);
  }

  function render(d) {
    const revealMsg = el('div', { class: 'inline-msg', role: 'status' });
    const revealBtn = el('button', {
      class: 'btn small',
      onclick: async () => {
        revealMsg.textContent = '';
        revealBtn.disabled = true;
        try { await onReveal(d.ref); } catch (e) {
          if (e.name !== 'AbortError') revealMsg.textContent = e.message + (e.hint ? ' — ' + e.hint : '');
        } finally { revealBtn.disabled = false; }
      },
    }, 'Reveal in tree');
    const nodes = [];
    nodes.push(el('div', { class: 'd-head' },
      history.length ? el('button', { class: 'btn small', title: 'Back to the previous object', onclick: () => load(history.pop()) }, '‹ Back') : null,
      el('h2', { title: d.className }, className(d.className)),
      el('div', { class: 'refline' }, d.ref, ' ', revealBtn),
      revealMsg));
    nodes.push(sizes(d.retainedBytes, d.shallowBytes, d.nativeEstBytes));

    // Path from GC root
    const steps = d.pathFromRoot;
    nodes.push(el('section', null, el('h3', null, 'Path from GC root'),
      steps.length === 0
        ? el('p', { class: 'muted' }, 'No path: this object is not reachable from a GC root.')
        : el('ol', { class: 'path' }, steps.map((s, i) => renderStep(s, i === steps.length - 1)))));

    if (d.rootsReaching.length) {
      nodes.push(el('section', null, el('h3', null, `Held by these roots (${d.rootsReaching.length})`),
        el('ul', { class: 'plain' }, d.rootsReaching.map((s) => el('li', null,
          s.ref ? refButton(s.ref, className(s.className), s.className) : el('span', { class: 'muted' }, s.className),
          s.edgeLabel ? el('span', { class: 'edge-out' }, s.edgeLabel + ' →') : null)))));
    }

    // Fields
    nodes.push(el('section', null, el('h3', null, `Fields (${d.fields.length})`),
      d.fields.length === 0 ? el('p', { class: 'muted' }, 'No fields.') :
        el('table', { class: 'fields' }, el('thead', null, el('tr', null, el('th', null, 'Name'), el('th', null, 'Type'), el('th', null, 'Value'))),
          el('tbody', null, d.fields.map((f) => el('tr', null,
            el('td', { class: 'mono' }, f.name),
            el('td', { class: 'mono muted', title: f.type }, splitClassName(f.type).simple),
            el('td', { class: 'mono' }, f.ref ? refButton(f.ref, f.value, f.ref) : f.value)))))));

    // Referrers
    nodes.push(el('section', null, el('h3', null, `Referrers (${formatInt(d.referrerCount)})`),
      d.referrers.length === 0 ? el('p', { class: 'muted' }, 'Nothing references this object directly (GC root).') :
        [d.referrerCount > d.referrers.length ? el('p', { class: 'muted' }, `Showing the first ${d.referrers.length}.`) : null,
          el('ul', { class: 'plain' }, d.referrers.map((s) => el('li', null,
            s.ref ? refButton(s.ref, className(s.className), s.className) : el('span', { class: 'muted' }, s.className),
            s.edgeLabel ? el('span', { class: 'edge-out' }, s.edgeLabel + ' \u2192') : null)))]));
    setContent(...nodes);
  }

  function clear(text) {
    token++;
    currentRef = null;
    history.length = 0;
    setContent(el('p', { class: 'muted pad' }, text || 'Select a row to see its details: path from the GC root, fields and referrers.'));
  }

  clear();
  return { showRow, clear };
}
