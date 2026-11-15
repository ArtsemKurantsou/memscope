// "Record startup" dialog: milestones, custom Class#method hooks, process, launch target, timeout.
// Resolves with a StartupConfig for POST /api/sessions (mode STARTUP), or null when cancelled.
import { el } from './util.js';

/** Last-used custom milestones and timeout (a per-browser convenience; util.prefs stores booleans only). */
const remembered = {
  get(key, dflt, ok) {
    try { const v = JSON.parse(localStorage.getItem('memscope.startup.' + key)); return ok(v) ? v : dflt; } catch { return dflt; }
  },
  set(key, v) { try { localStorage.setItem('memscope.startup.' + key, JSON.stringify(v)); } catch { /* ignore */ } },
};

// Same rules as the server (Milestones.parseCustom, StartupSession.validate); the server re-checks everything.
const IDENT = '[A-Za-z_$][A-Za-z0-9_$]*';
const SPEC = new RegExp(`^${IDENT}(\\.${IDENT})+#${IDENT}$`);
const SHELL_CHARS = /[;|&$<>`'"\\\n\r]/;

export function customMilestone(spec) {
  const [cls, method] = spec.split('#');
  return { id: `custom:${spec}`, label: `${cls.slice(cls.lastIndexOf('.') + 1)}#${method}`, hook: { className: cls, methodName: method }, enabled: true, custom: true };
}

function hookText(h) {
  return `${h.className.slice(h.className.lastIndexOf('.') + 1)}.${h.methodName}${h.afterReturn ? ' (on return)' : ''}`;
}

/**
 * Opens the modal dialog. `processes`: process names known for the package; `defaults`: Milestone[];
 * `launcher`: Promise<string|null> of the detected launcher activity (for the placeholder).
 */
export function askStartupConfig({ pkg, processes, defaults, launcher }) {
  return new Promise((resolve) => {
    const customs = [...new Set(remembered.get('custom', [], Array.isArray).filter((s) => SPEC.test(s)))];
    const checks = defaults.map((m) => el('input', { type: 'checkbox', checked: m.enabled !== false, 'data-id': m.id }));
    const procList = el('datalist', { id: 'startup-procs' }, [...new Set([pkg, ...processes])].map((p) => el('option', { value: p })));
    const procInput = el('input', { type: 'text', list: 'startup-procs', value: pkg, class: 'mono', spellcheck: 'false', 'aria-label': 'Process to profile' });
    const procMsg = el('div', { class: 'inline-msg', hidden: true });
    const customInput = el('input', { type: 'text', class: 'mono', placeholder: `${pkg}.MyActivity#onCreate`, spellcheck: 'false', 'aria-label': 'Custom milestone' });
    const customMsg = el('div', { class: 'inline-msg', hidden: true });
    const customList = el('ul', { class: 'dlg-list' });
    const launchInput = el('input', { type: 'text', class: 'mono', placeholder: 'detecting the launcher activity…', spellcheck: 'false', 'aria-label': 'Launch target' });
    const launchMsg = el('div', { class: 'inline-msg', hidden: true });
    const timeoutInput = el('input', { type: 'number', min: 1, max: 3600, value: remembered.get('timeout', 120, Number.isInteger), class: 'num', 'aria-label': 'Timeout per milestone in seconds' });
    const formMsg = el('div', { class: 'inline-msg', hidden: true });

    launcher.then((c) => { launchInput.placeholder = c ? `${c}  (launcher activity)` : 'no launcher activity: enter pkg/.Activity'; })
      .catch(() => { launchInput.placeholder = 'pkg/.Activity or am start arguments'; });

    const show = (box, text) => { box.textContent = text || ''; box.hidden = !text; return !text; };

    function renderCustoms() {
      customList.replaceChildren(...customs.map((spec, i) => el('li', null,
        el('span', { class: 'mono' }, spec),
        el('button', { class: 'btn small', type: 'button', title: 'Remove', 'aria-label': `Remove ${spec}`, onclick: () => { customs.splice(i, 1); renderCustoms(); } }, '×'))));
    }

    function validCustom(spec) {
      if (!spec) return show(customMsg, '');
      if (!SPEC.test(spec)) return show(customMsg, 'Use fully.qualified.ClassName#methodName, e.g. com.example.MainActivity#onCreate');
      if (customs.includes(spec)) return show(customMsg, 'Already in the list.');
      const label = customMilestone(spec).label;
      if (customs.some((s) => customMilestone(s).label === label)) return show(customMsg, `Another milestone is already labelled ${label}.`);
      return show(customMsg, '');
    }

    function addCustom() {
      const spec = customInput.value.trim();
      if (!spec || !validCustom(spec)) return;
      customs.push(spec);
      customInput.value = '';
      renderCustoms();
    }

    const validProcess = () => {
      const p = procInput.value.trim();
      const ok = p === pkg || (p.startsWith(pkg + ':') && /^[A-Za-z0-9_.]+$/.test(p.slice(pkg.length + 1)));
      return show(procMsg, ok ? '' : `Use ${pkg} or ${pkg}:<name>.`);
    };
    const validLaunch = () => show(launchMsg, SHELL_CHARS.test(launchInput.value) ? 'Shell characters (; | & $ < > ` quotes, backslash) are not allowed.' : '');
    const validTimeout = () => {
      const n = Number(timeoutInput.value);
      return show(formMsg, Number.isInteger(n) && n >= 1 && n <= 3600 ? '' : 'The timeout must be a whole number of seconds between 1 and 3600.');
    };

    procInput.addEventListener('input', validProcess);
    launchInput.addEventListener('input', validLaunch);
    timeoutInput.addEventListener('input', validTimeout);
    customInput.addEventListener('input', () => validCustom(customInput.value.trim()));
    customInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); addCustom(); } });

    const dlg = el('dialog', { class: 'dialog', 'aria-labelledby': 'startup-title' });
    let result = null;

    function start(e) {
      e.preventDefault();
      const typed = customInput.value.trim();
      if (customs.includes(typed)) { customInput.value = ''; show(customMsg, ''); } else if (typed) addCustom();
      const okAll = [validProcess(), validLaunch(), validTimeout(), !customInput.value.trim()].every(Boolean);
      if (!okAll) return;
      const milestones = defaults.map((m, i) => ({ ...m, enabled: checks[i].checked })).concat(customs.map(customMilestone));
      if (!milestones.some((m) => m.enabled)) { show(formMsg, 'Select at least one milestone.'); return; }
      const process = procInput.value.trim();
      remembered.set('custom', customs);
      remembered.set('timeout', Number(timeoutInput.value));
      result = {
        milestones,
        processName: process === pkg ? null : process,
        launch: launchInput.value.trim() || null,
        milestoneTimeoutSec: Number(timeoutInput.value),
      };
      dlg.close();
    }

    dlg.append(el('form', { method: 'dialog', onsubmit: start },
      el('h2', { id: 'startup-title' }, 'Record startup of ', el('span', { class: 'mono' }, pkg)),
      el('p', { class: 'muted' }, 'The app is restarted under a debugger and its heap is dumped at each milestone, starting before Application.onCreate.'),
      el('fieldset', null, el('legend', null, 'Milestones'),
        defaults.map((m, i) => el('label', { class: 'check dlg-row' }, checks[i], m.label, el('span', { class: 'muted mono small' }, hookText(m.hook))))),
      el('fieldset', null, el('legend', null, 'Custom milestones (stop at method entry)'),
        customList,
        el('div', { class: 'dlg-inline' }, customInput, el('button', { class: 'btn small', type: 'button', onclick: addCustom }, 'Add')),
        customMsg),
      el('div', { class: 'dlg-grid' },
        el('label', { for: 'startup-proc' }, 'Process'), el('div', null, Object.assign(procInput, { id: 'startup-proc' }), procList, procMsg),
        el('label', { for: 'startup-launch' }, 'Launch target'), el('div', null, Object.assign(launchInput, { id: 'startup-launch' }),
          el('div', { class: 'muted small' }, 'A component (pkg/.Activity) or am start arguments, e.g. -n pkg/.Main --es key value. Empty: the launcher activity.'), launchMsg),
        el('label', { for: 'startup-timeout' }, 'Timeout per milestone'), el('div', null, Object.assign(timeoutInput, { id: 'startup-timeout' }), ' s')),
      el('p', { class: 'dlg-note' }, 'A debugger is attached during startup, so startup timing is slower than normal; memory contents are unaffected.'),
      formMsg,
      el('div', { class: 'jactions dlg-actions' },
        el('button', { class: 'btn', type: 'button', onclick: () => dlg.close() }, 'Cancel'),
        el('button', { class: 'btn primary', type: 'submit' }, 'Start recording'))));

    dlg.addEventListener('close', () => { dlg.remove(); resolve(result); });
    renderCustoms();
    document.body.append(dlg);
    dlg.showModal();
  });
}
