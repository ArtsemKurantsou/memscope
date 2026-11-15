// Class search box: suggestions from /classes (prefix matches first), Enter or click runs /instances.
import { el, formatInt, formatBytes, splitClassName } from './util.js';

export function createSearch({ input, list, getClasses, onPick }) {
  let matches = [];
  let active = -1;

  function close() { list.hidden = true; active = -1; input.setAttribute('aria-expanded', 'false'); }

  function render() {
    list.replaceChildren(...matches.map((c, i) => {
      const sp = splitClassName(c.className);
      return el('li', { role: 'option', class: i === active ? 'active' : '', 'aria-selected': String(i === active),
        onmousedown: (e) => { e.preventDefault(); pick(c.className); } },
      el('span', { class: 'cn' }, sp.simple), sp.pkg ? el('span', { class: 'pkg' }, sp.pkg) : null,
      el('span', { class: 'meta num' }, `${formatInt(c.instanceCount)} × · ${formatBytes(c.retainedBytes)}`));
    }));
    list.hidden = matches.length === 0;
  }

  function pick(name) { input.value = name; close(); onPick(name); }

  async function update() {
    const text = input.value.trim().toLowerCase();
    if (!text) { matches = []; close(); return; }
    let all;
    try { all = await getClasses(); } catch { matches = []; close(); return; }
    const simple = (n) => n.slice(n.lastIndexOf('.') + 1).toLowerCase();
    const scored = [];
    for (const c of all) {
      const full = c.className.toLowerCase();
      const rank = simple(c.className).startsWith(text) ? 0 : full.startsWith(text) ? 1 : full.includes(text) ? 2 : -1;
      if (rank >= 0) scored.push([rank, c]);
    }
    scored.sort((a, b) => a[0] - b[0] || b[1].retainedBytes - a[1].retainedBytes);
    matches = scored.slice(0, 12).map((s) => s[1]);
    active = -1;
    render();
  }

  input.addEventListener('input', update);
  input.addEventListener('blur', close);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'ArrowDown' && matches.length) { active = (active + 1) % matches.length; render(); e.preventDefault(); }
    else if (e.key === 'ArrowUp' && matches.length) { active = (active - 1 + matches.length) % matches.length; render(); e.preventDefault(); }
    else if (e.key === 'Enter') {
      const exact = matches.find((m) => m.className === input.value.trim());
      const chosen = active >= 0 ? matches[active] : exact || matches[0];
      if (chosen) pick(chosen.className);
      else if (input.value.trim()) pick(input.value.trim());
      e.preventDefault();
    } else if (e.key === 'Escape') { close(); }
  });
  return { close };
}
