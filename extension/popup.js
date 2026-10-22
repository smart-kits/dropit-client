import { api, store, sendText, sendFile, me, authLost, forgetToken, serviceBase, PRIVACY_URL, INSTALL_URL } from './api.js';
import { deviceKey } from './device.js';
import { withPow } from './pow.js';
import { t, lang } from './i18n.js';
import { deviceFullHelp, fitMeta, pageMeta, intentOf, groupsFor, leftOver, resumeGroups, mb, fileSize, fileNameOf, seqRange, FILES_MAX, safeLink, upgradeLink, squash, clip } from './payload.js';
import { icon } from './icons.js';
import { recentList, pruneRecent, RECENT_KEY } from './recent.js';
import { readPage, readSelection } from './page.js';
import { scopeFromProbe, canAddDevices, slotPlan, pairLink, newcomer, pairTick, clock, ago, POLL_EVERY } from './pairing.js';
import { QR } from './qr.js';
import { DEFAULT_KEY, usable, candidates, reconcile, toValue, shortTarget, deviceIcon, activity } from './targets.js';

const $ = (id) => document.getElementById(id);
// Opened as a small window (setup from a right-click, or picking files where the popup can't stay open)
const inWindow = new URLSearchParams(location.search).has('window');
document.body.classList.toggle('window', inWindow);

document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
for (const el of document.querySelectorAll('[data-i18n]')) el.textContent = t.html[el.dataset.i18n];
for (const el of document.querySelectorAll('[data-i18n-label]')) el.setAttribute('aria-label', el.title = t.html[el.dataset.i18nLabel]);
$('code').placeholder = t.html.codeExample;      // an example of what a code looks like; the label is for screen readers
$('code').setAttribute('aria-label', t.html.codePlaceholder);
for (const el of document.querySelectorAll('[data-icon]')) el.prepend(icon(el.dataset.icon));
// What joining sends, said before either button is pressed; the buttons read "Agree and …"
$('consent').append(`${t.consent} `, Object.assign(document.createElement('a'),
  { href: PRIVACY_URL, target: '_blank', rel: 'noopener', textContent: t.privacyLink }));
$('compose').placeholder = t.html.placeholder;
$('pair-close').setAttribute('aria-label', t.html.closePair);
Object.assign($('privacy'), { href: PRIVACY_URL, textContent: t.privacyLink });

// A new issue with the version and browser filled in — never anything from this account
const version = chrome.runtime.getManifest().version;
$('issue').href = 'https://github.com/smart-kits/dropit-client/issues/new'
  + `?title=${encodeURIComponent(`[Chrome extension ${version}] `)}`
  + `&body=${encodeURIComponent(t.issueBody(`Extension: ${version} · ${navigator.userAgent}`))}`;

// status: File → 'busy' | 'done' while a batch is going out
// pick: where the next send goes when chosen in the panel — undefined = the default, null = every device, else ids
const state = { text: '', files: [], fromSelection: false, page: {}, tab: null, busy: false, status: new Map(), pick: undefined, resume: null };

// ── First run ───────────────────────────────────────────────────────────

function setupFailed(message, err) {
  const box = $('setup-returned');
  box.hidden = !message;
  box.querySelector('span').textContent = message ?? '';
  helpDeviceFull(message && err?.code === 'DEVICE_LIMIT_REACHED' ? err : null);
}

/** Joining refused because every slot is taken: say how to free one, and (free accounts, payments open) offer Paid with a link */
function helpDeviceFull(err) {
  const box = $('setup-help'), up = $('setup-up');
  box.hidden = !err;
  up.hidden = true;
  if (!err) return;
  const show = (paidDevices) => {
    if ($('setup-help').hidden) return;               // cleared meanwhile
    const h = deviceFullHelp(err.data, paidDevices, t);
    box.querySelector('span').textContent = h.text;
    up.hidden = !h.url;
    if (h.url) {
      up.textContent = h.link;
      up.onclick = (ev) => { ev.preventDefault(); chrome.tabs.create({ url: h.url }); };
    }
  };
  show(null);
  // The paid plan's device count is public; a failed read leaves the sentence without a number
  if (upgradeLink(err.data)) api('GET', '/v1/plans', undefined, { auth: false }).then((p) => show(p?.limits?.paid?.devices)).catch(() => {});
}

/**
 * A joined browser gets a send-only key: a leaked one can't read anything. Creating the account gives a full one
 * (it can add devices). What the key may do is kept, so the popup knows without asking.
 */
async function adopt(res, scope) {
  await store.set({ token: res.token, device_id: res.device_id, scope });
  await chrome.storage.local.remove('previous_token');
  await pairForget();
  await render();                // it ends by saying what Enter will send; the note goes after that
  // Joined in place of this browser's earlier join (reinstalled, signed out): say so, it was one step
  if (res.replaced && res.replaced.how !== 'issuer') say(t.replacedOld(res.replaced.name), true);
}

/** What lets the service tell this browser joining again from a new one (device.js) */
async function sameBrowser() {
  const { previous_token } = await store.get();
  return { device_key: await deviceKey('extension'), ...(previous_token ? { previous_token } : {}) };
}

const guardSetup = (fn) => async () => {
  setupFailed(null);
  try { await fn(); } catch (err) { setupFailed(err.message, err); }
};

// A new account has only this browser in it, and items sent here need somewhere to arrive:
// adding another device is the one next step, so its code is already showing
$('create').onclick = guardSetup(async () => {
  const button = $('create'), label = button.textContent;
  // On a busy network the service asks for a little work first: show that something is happening
  const preparing = () => { button.disabled = true; button.textContent = t.preparing; };
  try {
    const body = { device_name: t.deviceName(navigator.platform), ...await sameBrowser() };
    await adopt(await withPow((b) => api('POST', '/v1/accounts', b, { auth: false }), body, { onSolving: preparing }), 'full');
  } finally {
    button.disabled = false;
    button.textContent = label;
  }
  await showPairCode(null, true).catch((err) => pairFailed(err.message));
});
$('code').onkeydown = (ev) => { if (ev.key === 'Enter' && !ev.isComposing) $('claim').click(); };
$('code').oninput = () => setupFailed(null);
$('claim').onclick = guardSetup(async () => {
  const code = $('code').value.trim().toUpperCase();
  if (!code) return $('code').focus();
  await adopt(await api('POST', '/v1/pair/claim',
    { code, device_name: t.deviceName(navigator.platform), scope: 'ingest_only', ...await sameBrowser() }, { auth: false }), 'ingest_only');
});

// Signing out removes this browser from the account first, so it stops using a device slot. Offline or
// already removed: forget it here anyway — the kept key lets the next join take its place.
$('unpair').onclick = async () => {
  const { token, device_id } = await store.get();
  try {
    await api('DELETE', `/v1/devices/${encodeURIComponent(device_id)}`);
    await chrome.storage.local.remove(['token', 'device_id', 'me', 'scope', 'previous_token', RECENT_KEY, DEFAULT_KEY]);
    await chrome.storage.session?.remove('devices_cache').catch(() => {});
  } catch {
    await forgetToken(token);
  }
  await pairForget();
  render();
};

// ── Sending ─────────────────────────────────────────────────────────────

const compose = $('compose');

function returned(message, err = null) {
  const box = $('failed');
  box.hidden = !message;
  if (!message) return;
  // The card says what is coming back: the text, else the file names, else the page
  const text = squash(state.text), files = state.files;
  const title = text ? clip(text, 120)
    : files.length === 1 ? files[0].name
    : files.length ? t.failedFiles(files.length)
    : state.page.title || state.page.url || '';
  const kind = text ? intentOf(state).kind === 'url' ? 'link' : 'text'
    : files.length ? (files.every((f) => f.type.startsWith('image/')) ? 'image' : 'file') : 'link';
  $('failed-tile').replaceChildren(icon(kind));
  $('failed-title').textContent = title;
  $('failed-meta').textContent = clockNow();
  $('failed-why').textContent = message;
  $('retry').replaceChildren(icon('retry'));
  $('retry').title = t.retry;
  $('retry').setAttribute('aria-label', t.retryLabel(title));
  // A limit the paid plan lifts: the service gives a link (to free accounts only) and says which limit it was
  const up = $('returned-up');
  const url = upgradeLink(err?.data);
  up.hidden = !url;
  if (url) {
    up.textContent = t.upgradeFor(err.data.why);
    up.onclick = (ev) => { ev.preventDefault(); chrome.tabs.create({ url }); };
  }
}
$('retry').onclick = () => $('send-form').requestSubmit();

/** The line under the box: what Enter will send, how sending is going, or what was just sent */
function say(text, done = false) {
  $('will').textContent = text;
  $('will').classList.toggle('done', done);
}

/** Say what Enter will send; there is never a choice to make */
function explain() {
  const intent = intentOf(state);
  const host = (() => { try { return new URL(state.page.url).hostname; } catch { return ''; } })();
  say(intent.type === 'batch' ? t.willBatch(state.files.length, !!intent.text)
    : intent.type === 'text' ? (intent.from ? t.willTextFrom(host) : t.willText)
    : intent.type === 'pdf' ? t.willPdf(fileNameOf(state.page.url) ?? host)
    : state.page.url ? t.willPage(state.page.title || host || state.page.url) : '');
}

function grow() {
  compose.style.height = 'auto';
  compose.style.height = `${compose.scrollHeight + 3}px`;
}

compose.oninput = () => {
  state.text = compose.value;
  if (!compose.value.trim()) state.fromSelection = false;   // cleared: whatever comes next isn't the selection
  returned(null);
  grow();
  explain();
};
compose.onkeydown = (ev) => {
  // Enter sends, Shift+Enter breaks the line, an Enter that commits an IME composition does neither
  if (ev.key === 'Enter' && !ev.shiftKey && !ev.isComposing && ev.keyCode !== 229) {
    ev.preventDefault();
    $('send-form').requestSubmit();
  }
};

// A pasted screenshot or file joins the send; pasting needs no clipboard permission
compose.addEventListener('paste', (ev) => {
  const files = [...(ev.clipboardData?.files ?? [])];
  if (!files.length) return;
  ev.preventDefault();          // copying a file also puts its name on the clipboard as text
  attach(files);
});
for (const target of [document.body]) {
  target.addEventListener('dragover', (ev) => { if (ev.dataTransfer?.types.includes('Files')) ev.preventDefault(); });
  target.addEventListener('drop', (ev) => {
    if (!ev.dataTransfer?.files.length) return;
    ev.preventDefault();
    attach([...ev.dataTransfer.files]);
  });
}
$('choose').onclick = () => $('files').click();
$('files').onchange = () => {
  attach([...$('files').files]);
  $('files').value = '';
};

function attach(files) {
  state.files.push(...files);
  returned(null);
  drawFiles();
  explain();
  compose.focus();
}

function drawFiles() {
  const list = $('attached');
  list.hidden = !state.files.length;
  list.replaceChildren(...state.files.map((f, k) => {
    const li = document.createElement('li');
    const status = state.status.get(f) ?? '';
    li.dataset.state = status;
    const mark = Object.assign(document.createElement('span'), { className: 'st', textContent: status === 'done' ? '✓' : status === 'busy' ? '↑' : '' });
    const name = Object.assign(document.createElement('span'), { className: 'fn', textContent: f.name, title: f.name });
    const size = Object.assign(document.createElement('span'), { className: 'sz', textContent: fileSize(f.size) });
    const x = Object.assign(document.createElement('button'), { type: 'button', className: 'x', textContent: '×', hidden: state.busy });
    x.setAttribute('aria-label', t.removeFile(f.name));
    x.onclick = () => { state.files.splice(k, 1); drawFiles(); explain(); compose.focus(); };
    li.append(mark, name, size, x);
    return li;
  }));
}

// Pasted screenshots are all called image.png: let the service name them by time instead
const nameOf = (f) => (/^image\.(png|jpe?g|gif|webp)$/i.test(f.name) ? undefined : f.name);
const clockNow = () => new Date().toLocaleTimeString(lang === 'zh' ? 'zh-CN' : 'en-GB', { hour: '2-digit', minute: '2-digit', hour12: false });

// The popup stays open after sending: it says how it went in place and clears what went out,
// so the next thing can be sent straight away (the toolbar badge shows ✓ too)
$('send-form').onsubmit = async (ev) => {
  ev.preventDefault();
  if (state.busy) return;
  const intent = intentOf(state);
  if (intent.type === 'page' && !state.page.url) return compose.focus();
  busy(true);
  returned(null);
  noteTarget('');
  say(t.sendingNow);
  const to = currentTo();
  try {
    const out = await send(intent, to);
    if (out.pending) {                     // asking for a site in the small window, which sends from there
      busy(false);
      return say(t.grantOpened(out.host));
    }
    const { seqs, deduped } = out;
    chrome.runtime.sendMessage({ type: 'flash', ok: true, title: deduped ? t.alreadySent(seqs[0]) : t.sent(seqs.at(-1)) }).catch(() => {});
    Object.assign(state, { text: '', files: [], fromSelection: false });
    state.status.clear();
    compose.value = '';
    busy(false);
    drawFiles();
    grow();
    state.pick = undefined;               // a choice made in the panel is for one send
    drawTargets();
    drawRecent();
    say(deduped ? `✓ ${t.alreadySent(seqs[0])}`
      : to !== undefined && targets.scope === 'full' ? t.sentTo(seqRange(seqs), shortTarget(to, targets.names, t))
      : t.sentLine(seqRange(seqs), clockNow()), true);
    compose.focus();
  } catch (err) {
    busy(false);
    if (authLost(err)) return render(err.message);
    if (err.code === 'TARGET_UNAVAILABLE') {         // those devices are gone: the choice and the default are void
      state.pick = undefined;
      targets.def = usable((await store.get())[DEFAULT_KEY]);       // already put back to every device if it was the default
      refreshTargets();
    }
    drawFiles();
    explain();
    returned(err.done ? t.partlySent(err.done, err.total, err.message) : err.message, err);
  }
};

async function send(intent, to) {
  if (intent.type === 'page') {
    const res = await sendText({ kind: 'url', raw: state.page.url, meta: fitMeta(pageMeta(state.page)), to });
    return { seqs: [res.seq], deduped: res.deduped };
  }
  if (intent.type === 'pdf') return sendPdf(state.page.url, to);
  if (intent.type === 'text') {
    const raw = intent.kind === 'url' ? state.text.trim() : state.text;
    const res = await sendText({ kind: intent.kind, raw, meta: fitMeta(intent.from ? { from: intent.from } : null), to });
    return { seqs: [res.seq], deduped: res.deduped };
  }
  // Text and files as one batch, one after the other, with progress. Check everything before sending anything.
  const limit = (await me().catch(() => null))?.item_bytes_limit;
  const big = limit && state.files.find((f) => f.size > limit);
  if (big) throw new Error(`${big.name}: ${t.tooLarge(mb(limit))}`);
  const items = [...(intent.text ? [{ text: intent.text }] : []), ...state.files.map((file) => ({ file }))];
  if (state.files.length > FILES_MAX) throw new Error(t.batchTooLarge(FILES_MAX));
  // Sent again after a failure part way, what is left stays in the batch it was in (the same id, the place each had)
  const keys = items.map((it) => it.file ?? it.text);
  const groups = resumeGroups(state.resume, keys) ?? groupsFor(items.length);
  state.resume = null;
  const seqs = [];
  for (const [k, it] of items.entries()) {
    say(t.sendingOf(k + 1, items.length));
    if (it.file) { state.status.set(it.file, 'busy'); drawFiles(); }
    try {
      let res;
      if (it.text) {
        const kind = intentOf({ text: it.text }).kind;
        res = await sendText({ kind, raw: kind === 'url' ? it.text.trim() : it.text,
          meta: fitMeta({ ...(intent.from ? { from: intent.from } : {}), ...(groups[k] ? { group: groups[k] } : {}) }), to });
      } else {
        res = await sendFile(it.file, { name: nameOf(it.file), mime: it.file.type, group: groups[k], to });
        state.status.set(it.file, 'done');
        drawFiles();
      }
      seqs.push(res.seq);
    } catch (err) {
      // What went through stays sent: take it out, so trying again doesn't send it twice
      if (k > 0 && intent.text) { state.text = compose.value = ''; state.fromSelection = false; }
      state.files = state.files.filter((f) => state.status.get(f) !== 'done');
      state.status.clear();
      state.resume = leftOver(groups, keys, k);
      throw Object.assign(err, { done: k, total: items.length });
    }
  }
  return { seqs };
}

/**
 * The PDF this tab shows, as a file. Opening the popup lent this tab's site to the extension, which is
 * usually enough; a site that still refuses gets the one-site question in the small window.
 */
async function sendPdf(url, to) {
  const limit = (await me().catch(() => null))?.item_bytes_limit;
  let res;
  try {
    res = await fetch(url, { credentials: 'include' });
  } catch {
    const job = { url, from: null, tabId: state.tab?.id };
    await chrome.windows.create({ url: `grant.html?job=${encodeURIComponent(JSON.stringify(job))}`, type: 'popup', width: 420, height: 260, focused: true });
    return { pending: true, host: new URL(url).host };
  }
  if (!res.ok) throw new Error(t.httpFailed(res.status));
  if (limit && Number(res.headers.get('content-length') || 0) > limit) { res.body?.cancel(); throw new Error(t.tooLarge(mb(limit))); }
  const blob = await res.blob();
  if (limit && blob.size > limit) throw new Error(t.tooLarge(mb(limit)));
  const out = await sendFile(blob, { name: fileNameOf(url, res.headers.get('content-disposition')), mime: blob.type || 'application/pdf', to });
  return { seqs: [out.seq], deduped: out.deduped };
}

/** Sending: the stamp is pressed, the envelope's stripes run, nothing can be changed until it's done */
function busy(on) {
  state.busy = on;
  $('send').setAttribute('aria-busy', String(on));
  $('send').disabled = on;
  $('stamp-word').textContent = on ? t.html.sending : t.html.stamp;
  $('send-form').classList.toggle('sending', on);
  compose.readOnly = on;
  $('choose').disabled = on;
  $('targets-btn').disabled = on;
  if (on) panelClose();
}

// ── What was sent lately ─────────────────────────────────────────────────

/** "14:15" today, "10-19 14:15" before */
function when(at) {
  const d = new Date(at), loc = lang === 'zh' ? 'zh-CN' : 'en-GB';
  const time = d.toLocaleTimeString(loc, { hour: '2-digit', minute: '2-digit', hour12: false });
  return d.toDateString() === new Date().toDateString() ? time : `${d.toLocaleDateString(loc, { month: '2-digit', day: '2-digit' })} ${time}`;
}

async function copyOut(btn, text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch { return; }
  btn.replaceChildren(icon('check'));
  btn.title = t.copied;
  setTimeout(() => { btn.replaceChildren(icon('copy')); btn.title = t.copy; }, 1400);
}

/** One row per thing that went through: what it was, when, and the one next step the data allows (copy) */
async function drawRecent() {
  const list = await recentList();
  $('recent').hidden = !list.length;
  $('empty').hidden = !!list.length;
  $('recent-list').replaceChildren(...list.map((e) => {
    const li = document.createElement('li');
    const tile = Object.assign(document.createElement('span'), { className: 'tile' });
    tile.append(icon(e.kind === 'url' ? 'link' : e.kind === 'file' ? (e.mime?.startsWith('image/') ? 'image' : 'file') : 'text'));
    const body = Object.assign(document.createElement('div'), { className: 'body' });
    const href = e.kind === 'url' ? safeLink(e.text) : null;
    const title = href
      ? Object.assign(document.createElement('a'), { className: 'tt', href, target: '_blank', rel: 'noopener' })
      : Object.assign(document.createElement('p'), { className: 'tt' });
    title.textContent = e.label;
    title.title = e.label;
    // Who it went to, short; an entry made before this was kept (or by a key that can't choose) shows its number instead
    const where = e.to === undefined ? '' : `→ ${shortTarget(e.to, targets.names, t)}`;
    body.append(title, Object.assign(document.createElement('p'), { className: 'mt', textContent: where ? `${where} · ${when(e.at)}` : `${when(e.at)} · #${e.seq}`, title: `#${e.seq}` }));
    li.append(tile, body);
    if (e.text) {
      const copy = Object.assign(document.createElement('button'), { type: 'button', className: 'act', title: t.copy });
      copy.setAttribute('aria-label', t.copyLabel(e.label));
      copy.append(icon('copy'));
      copy.onclick = () => copyOut(copy, e.text);
      li.append(copy);
    }
    return li;
  }));
}
// A send made elsewhere (right-click, shortcut) while the popup is open
chrome.storage.onChanged.addListener((changes, area) => { if (area === 'local' && RECENT_KEY in changes) drawRecent(); });

// ── Which devices a send goes to ─────────────────────────────────────────
// Only a key that can read the device list can choose (the browser that created the account). The list is kept in
// memory (and, for a moment, in session storage so reopening is instant) and fetched again whenever the panel opens.

const targets = {
  scope: null, self: null, ready: false, loading: null,
  list: [],              // devices a send can go to (full access, not this browser), most recently active first
  names: new Map(),      // id → name, for writing "→ Mac" in the receipt and the recent list
  def: null,             // this browser's saved default: ids, or null = every device
};
const CACHE_KEY = 'devices_cache', CACHE_MS = 30_000;
const mayChoose = () => targets.scope === 'full' && targets.list.length > 0;

/** Where the next send goes: the panel's pick, else the default. undefined until the saved default has been read. */
function currentTo() {
  if (targets.scope !== 'full' || !targets.ready) return state.pick;      // the sender then uses the saved default itself
  return state.pick !== undefined ? state.pick : targets.def;
}

function setList(list) {
  targets.list = list;
  targets.names = new Map(list.map((d) => [d.device_id, d.name]));
}

async function initTargets() {
  if (targets.scope !== 'full') { targets.ready = false; return drawTargets(); }
  const cfg = await store.get();
  targets.self = cfg.device_id;
  targets.def = usable(cfg[DEFAULT_KEY]);
  targets.ready = true;
  const { [CACHE_KEY]: cache } = await chrome.storage.session?.get(CACHE_KEY).catch(() => ({})) ?? {};
  const cached = cache?.self === targets.self;
  if (cached) setList(cache.list);
  drawTargets();
  drawRecent();
  if (!cached || Date.now() - cache.at > CACHE_MS) refreshTargets().then((message) => { if (message && $('tpanel').hidden) noteTarget(message); });
}

/** Fetch the device list again and check the saved default against it. Resolves to a message about the default, if any. */
function refreshTargets() {
  if (targets.scope !== 'full') return Promise.resolve('');
  targets.loading ??= (async () => {
    let message = '';
    try {
      const { devices } = await api('GET', '/v1/devices');
      setList(candidates(devices, targets.self));
      chrome.storage.session?.set({ [CACHE_KEY]: { self: targets.self, at: Date.now(),
        list: targets.list.map((d) => ({ device_id: d.device_id, name: d.name, scope: d.scope, last_seen_at: d.last_seen_at ?? null })) } }).catch(() => {});
      const r = reconcile(targets.def, targets.list);
      if (r.changed) {
        targets.def = r.to;
        await (r.to ? store.set({ [DEFAULT_KEY]: r.to }) : chrome.storage.local.remove(DEFAULT_KEY));
        if (r.reset || r.trimmed) message = r.reset ? t.targetsReset(r.gone) : t.targetsTrimmed(r.gone);   // said only when a device in it was really removed
      }
    } catch (err) {
      if (authLost(err)) render(err.message);
    } finally {
      targets.loading = null;
      drawTargets();
      drawRecent();
    }
    return message;
  })();
  return targets.loading;
}

/** What the right part and the menu show */
function drawTargets() {
  const may = mayChoose();
  $('split').classList.toggle('two', may);
  $('targets-btn').hidden = !may;
  if (!may && !$('tpanel').hidden) panelClose();
  $('defaults-open').hidden = !may;
  $('defaults-val').textContent = t.defaultValue(shortTarget(targets.def, targets.names, t));
  // The badge says how many devices this send reaches, only when that is not every device
  const to = panel.sel ? toValue(panel.sel, targets.list) : currentTo();
  const n = may && to ? to.length : 0;
  $('targets-badge').hidden = !n;
  $('targets-badge').textContent = n || '';
  $('tp-default').textContent = t.tpDefault(shortTarget(targets.def, targets.names, t));
}

/** A line under the box about the default (a device in it was removed); empty clears it */
function noteTarget(text) {
  $('tnote').textContent = text;
}

/** One device: its small drawing, name, when it was last active, and a tick box. `onToggle(id, box)` */
function deviceRow(d, checked, onToggle) {
  const li = document.createElement('li');
  const label = Object.assign(document.createElement('label'), { className: 'dev' });
  const tile = Object.assign(document.createElement('span'), { className: 'tile' });
  tile.append(icon(deviceIcon(d.name)));
  const body = Object.assign(document.createElement('div'), { className: 'body' });
  body.append(
    Object.assign(document.createElement('p'), { className: 'tt', textContent: d.name, title: d.name }),
    Object.assign(document.createElement('p'), { className: 'mt', textContent: activity(d, Date.now(), t, lang === 'zh' ? 'zh-CN' : 'en', ago) }));
  const box = Object.assign(document.createElement('input'), { type: 'checkbox', className: 'tick', checked });
  box.setAttribute('aria-label', d.name);
  box.onchange = () => onToggle(d.device_id, box);
  label.append(tile, body, box);
  li.append(label);
  return li;
}

// The panel: "Send to which devices". The ticks start from the default and change only this one send.
const panel = { sel: null, opener: null };

function panelDraw() {
  if (!panel.sel) return;
  $('tp-list').replaceChildren(...targets.list.map((d) => deviceRow(d, panel.sel.has(d.device_id), (id, box) => {
    if (!box.checked && panel.sel.size === 1) {          // never none: the last one stays ticked
      box.checked = true;
      $('tp-keep-text').textContent = t.keepOne;
      $('tp-keep').hidden = false;
      return;
    }
    $('tp-keep').hidden = true;
    box.checked ? panel.sel.add(id) : panel.sel.delete(id);
    panelCount();
  })));
  panelCount();
}

function panelCount() {
  $('tp-send').textContent = t.tpSend(panel.sel.size);
  drawTargets();
}

function panelPlace() {
  // The popup is as tall as its page: make room for the panel hanging below the box
  document.body.style.minHeight = '';
  const need = Math.ceil($('tpanel').getBoundingClientRect().bottom + window.scrollY + 12);
  if (need > document.body.offsetHeight) document.body.style.minHeight = `${need}px`;
}

async function panelOpen() {
  if (!mayChoose() || state.busy) return;
  closeMenuIfOpen();
  const start = state.pick !== undefined ? state.pick : targets.def;
  panel.sel = new Set(start ?? targets.list.map((d) => d.device_id));
  $('tp-sub').textContent = t.html.tpSub;
  $('tp-keep').hidden = true;
  $('tpanel').hidden = false;
  $('targets-btn').setAttribute('aria-expanded', 'true');
  panelDraw();
  panelPlace();
  $('tp-list').querySelector('input')?.focus();
  const message = await refreshTargets();
  if (!panel.sel) return;                                // closed meanwhile
  // Devices that are gone can't stay ticked; none left means every device
  const have = new Set(targets.list.map((d) => d.device_id));
  const kept = [...panel.sel].filter((id) => have.has(id));
  panel.sel = new Set(kept.length ? kept : targets.list.map((d) => d.device_id));
  if (!mayChoose()) return;
  if (message) $('tp-sub').textContent = message;
  panelDraw();
  panelPlace();
}

function panelClose(focusButton = false) {
  panel.sel = null;
  $('tpanel').hidden = true;
  document.body.style.minHeight = '';
  $('targets-btn').setAttribute('aria-expanded', 'false');
  drawTargets();
  if (focusButton) $('targets-btn').focus();
}

$('targets-btn').onclick = () => ($('tpanel').hidden ? panelOpen() : panelClose());
$('tp-all').onclick = $('tp-keep-all').onclick = () => {
  panel.sel = new Set(targets.list.map((d) => d.device_id));
  $('tp-keep').hidden = true;
  panelDraw();
};
// The bottom button is what sends: this once, to exactly the ticked devices
$('tp-send').onclick = () => {
  const to = toValue(panel.sel, targets.list);
  panelClose();
  state.pick = to;
  drawTargets();
  $('send-form').requestSubmit();
};
$('tp-default').onclick = () => { panelClose(); defaultsOpen(); };
document.addEventListener('click', (ev) => {
  if (!$('tpanel').hidden && !ev.target.closest('#tpanel, #targets-btn')) panelClose();
});
document.addEventListener('keydown', (ev) => {
  if (ev.key === 'Escape' && !$('tpanel').hidden) { ev.preventDefault(); panelClose(true); }
  else if (ev.key === 'Escape' && !$('defaults').hidden) { ev.preventDefault(); defaultsClose(); }
});

// The default devices, from the menu: ticking saves at once, and at least one stays ticked
const dflt = { sel: null };

async function defaultsOpen() {
  closeMenuIfOpen();
  dflt.sel = new Set(targets.def ?? targets.list.map((d) => d.device_id));
  $('top').hidden = $('main').hidden = true;
  $('defaults').hidden = false;
  $('df-saved').textContent = '';
  defaultsDraw();
  $('df-back').focus();
  const message = await refreshTargets();
  if ($('defaults').hidden) return;
  dflt.sel = new Set(targets.def ?? targets.list.map((d) => d.device_id));
  if (message) $('df-saved').textContent = message;
  defaultsDraw();
}

function defaultsDraw() {
  const all = !targets.def;
  $('df-list').replaceChildren(...targets.list.map((d) => deviceRow(d, dflt.sel.has(d.device_id), (id, box) => {
    if (!box.checked && dflt.sel.size === 1) {
      box.checked = true;
      $('df-saved').textContent = `${t.keepOne}${t.html.tpAll}`;
      return;
    }
    box.checked ? dflt.sel.add(id) : dflt.sel.delete(id);
    defaultsSave();
  })));
  $('df-note').textContent = all ? t.dfNoteAll : t.dfNotePart;
}

async function defaultsSave() {
  const to = toValue(dflt.sel, targets.list);
  targets.def = to;
  await (to ? store.set({ [DEFAULT_KEY]: to }) : chrome.storage.local.remove(DEFAULT_KEY));
  $('df-saved').textContent = `✓ ${t.html.dfSaved}`;
  $('df-note').textContent = to ? t.dfNotePart : t.dfNoteAll;
  drawTargets();
}

$('df-all').onclick = () => { dflt.sel = new Set(targets.list.map((d) => d.device_id)); defaultsDraw(); defaultsSave(); };
$('df-back').onclick = () => defaultsClose();
$('defaults-open').onclick = () => defaultsOpen();
function defaultsClose() {
  $('defaults').hidden = true;
  $('top').hidden = $('main').hidden = false;
  $('menu-btn').focus();
}

// ── The menu at the top right ────────────────────────────────────────────

const menu = $('menu');
const items = () => [...menu.querySelectorAll('[role=menuitem]')].filter((el) => !el.hidden);
function menuClose(focusButton = false) {
  menu.hidden = true;
  $('menu-btn').setAttribute('aria-expanded', 'false');
  if (focusButton) $('menu-btn').focus();
}
const closeMenuIfOpen = () => { if (!menu.hidden) menuClose(); };
$('menu-btn').onclick = () => {
  if (!menu.hidden) return menuClose();
  menu.hidden = false;
  $('menu-btn').setAttribute('aria-expanded', 'true');
  items()[0]?.focus();
};
document.addEventListener('click', (ev) => { if (!menu.hidden && !ev.target.closest('.menu-wrap')) menuClose(); });
document.addEventListener('keydown', (ev) => {
  if (menu.hidden) return;
  if (ev.key === 'Escape') { ev.preventDefault(); return menuClose(true); }
  if (ev.key === 'Tab') return menuClose();
  const move = { ArrowDown: 1, ArrowUp: -1 }[ev.key];
  if (!move) return;
  ev.preventDefault();
  const list = items(), at = list.indexOf(document.activeElement);
  list[(at + move + list.length) % list.length]?.focus();
});
menu.addEventListener('click', (ev) => { if (ev.target.closest('#theme') == null) menuClose(); });

// Theme: follows the system until picked here; the pick is this browser's own
const THEMES = ['auto', 'light', 'dark'];
let theme = 'auto';
function applyTheme(mode) {
  theme = THEMES.includes(mode) ? mode : 'auto';
  if (theme === 'auto') document.documentElement.removeAttribute('data-theme');
  else document.documentElement.dataset.theme = theme;
  $('theme').textContent = t.themeLine(t.themes[theme]);
}
applyTheme('auto');
chrome.storage.local.get('theme').then(({ theme: saved }) => applyTheme(saved)).catch(() => {});
$('theme').onclick = () => {
  applyTheme(THEMES[(THEMES.indexOf(theme) + 1) % THEMES.length]);
  chrome.storage.local.set({ theme }).catch(() => {});
};

// ── Adding a device ─────────────────────────────────────────────────────
// Only a key that may add devices offers it: the one that created the account. A browser joined with a code
// has a send-only key and never sees the entry, rather than finding out from an error.

/**
 * What this browser's key may do. Recorded when it joined; a key from before that was recorded is asked about
 * once (the device list answers only a full key) and the answer is kept. Offline: unknown, asked next time.
 */
async function myScope() {
  const { token, scope } = await store.get();
  if (!token) return null;
  if (scope) return scope;
  let err = null;
  try { await api('GET', '/v1/devices'); } catch (e) { err = e; }
  if (authLost(err)) throw err;
  const found = scopeFromProbe(err);
  if (found) await store.set({ scope: found });
  return found;
}

/** Show the entry to a key that may use it, and bring back a code that was showing when the popup closed */
async function offerAdding() {
  let scope = null;
  try { scope = await myScope(); } catch (err) { return render(err.message); }
  const may = canAddDevices(scope);
  $('add-device').hidden = !may || !$('pair').hidden;
  targets.scope = scope;
  initTargets();
  if (may) await pairResume();
}

$('add-device').onclick = () => showPairCode(null, false).catch((err) => pairFailed(err.message));
$('pair-again').onclick = () => showPairCode(null, pairing.shown?.first ?? false).catch((err) => pairFailed(err.message));
$('pair-close').onclick = async () => {
  pairStop();
  await pairForget();
  $('pair').hidden = true;
  $('add-device').hidden = false;
};

// The code being shown, and the countdown that also checks for the new device. The popup closes whenever the
// browser loses focus (typing the code into Obsidian on this computer does that), so the code is also kept in
// session storage — memory only, gone with the browser — and shown again on the next open while it is valid.
const pairing = { timer: null, run: 0, checking: false, shown: null };
const sessionStore = chrome.storage.session;

async function pairForget() {
  await sessionStore?.remove('pairing').catch(() => {});
}

function pairStop() {
  clearInterval(pairing.timer);
  pairing.timer = null;
  pairing.run++;
}

function pairFailed(message) {
  pairStop();
  $('pair').hidden = false;
  $('add-device').hidden = false;               // trying again is one click
  $('pair-title').textContent = t.pairTitle;
  $('pair-body').hidden = $('pair-replace').hidden = $('pair-hint').hidden = $('pair-install').hidden = true;
  const box = $('pair-returned');
  box.hidden = false;
  box.querySelector('span').textContent = message;
}

/**
 * A fresh code. All slots in use: the new device takes the place of the one picked here (by default the one idle
 * longest); picking another makes a new code for it. `first`: right after creating the account.
 */
async function showPairCode(chosen, first) {
  pairStop();
  const [account, { devices }] = await Promise.all([me({ fresh: true }), api('GET', '/v1/devices')]);
  const { device_id } = await store.get();
  const { others, pick } = slotPlan(account, devices, device_id, chosen);
  const res = await api('POST', '/v1/pair', pick ? { replace: pick.device_id } : undefined);
  const keep = (d) => ({ device_id: d.device_id, name: d.name, last_seen_at: d.last_seen_at ?? null });
  const p = {
    code: res.code, expires_at: res.expires_at, link: pairLink(await serviceBase(), res.code), first: !!first, device_id,
    before: devices.map(keep), others: pick ? others.map(keep) : [], pick: pick?.device_id ?? null, limit: account?.devices_limit ?? null,
  };
  await sessionStore?.set({ pairing: p }).catch(() => {});
  navigator.clipboard?.writeText(res.code).catch(() => {});    // so it can be pasted on this computer too
  if (!state.busy) explain();                                  // an earlier "joined" line is old news now
  drawPair(p);
  pairWatch(p, false);
}

/** A code still valid from the last time the popup was open, for this same key */
async function pairResume() {
  if (pairing.timer || !sessionStore) return;
  const { pairing: p } = await sessionStore.get('pairing').catch(() => ({}));
  const { device_id } = await store.get();
  if (!p || p.device_id !== device_id || p.expires_at <= Date.now()) return pairForget();
  drawPair(p);
  pairWatch(p, true);
}

function drawPair(p) {
  pairing.shown = p;
  $('pair').hidden = false;
  $('add-device').hidden = true;
  $('pair-returned').hidden = true;
  $('pair-body').hidden = false;
  $('pair').classList.remove('expired');
  $('pair-title').textContent = p.first ? t.pairTitleFirst : t.pairTitle;
  $('pair-qr').replaceChildren();
  QR.draw($('pair-qr'), p.link, { scale: 4 });
  $('pair-code').textContent = p.code;          // exactly what to type: no dash to skip over
  $('pair-hint').textContent = t.pairHint;
  // Where the new device gets dropit, as a link that opens here (the QR code takes a phone there by itself)
  const [before, after] = t.pairInstall;
  const link = Object.assign(document.createElement('a'), { href: INSTALL_URL, textContent: 'dropit.smart-kits.xyz', target: '_blank', rel: 'noopener' });
  $('pair-install').replaceChildren(before, link, after);
  $('pair-install').hidden = false;
  $('pair-hint').hidden = $('pair-left').hidden = false;
  $('pair-expired').hidden = true;
  $('pair-replace').hidden = !p.pick;
  if (p.pick) {
    $('pair-replace').querySelector('label').textContent = t.pairFull(p.limit);
    const select = $('pair-pick');
    const locale = lang === 'zh' ? 'zh-CN' : 'en';
    select.replaceChildren(...p.others.map((d) => new Option(
      `${d.name} · ${d.last_seen_at ? t.lastSeen(ago(d.last_seen_at, Date.now(), t, locale)) : t.neverSeen}`, d.device_id, false, d.device_id === p.pick)));
    select.onchange = () => showPairCode(select.value, p.first).catch((err) => pairFailed(err.message));
  }
}

/**
 * Once a second: the countdown; every 4th second: the device list. A device it hasn't seen means the code was
 * used: the code goes away and the line under the box says which device joined. Stops when the code expires
 * (at most 75 checks) or the popup closes. `now`: check straight away (reopened: it may have joined meanwhile).
 */
function pairWatch(p, now) {
  pairStop();
  const run = pairing.run;
  let tick = now ? POLL_EVERY : 0;
  const step = async () => {
    if (run !== pairing.run) return;
    const { left, expired, poll } = pairTick(Date.now(), p.expires_at, tick++);
    if (expired) return pairExpired();
    $('pair-left').textContent = t.pairLeft(clock(left));
    if (!poll || pairing.checking) return;
    pairing.checking = true;
    let list = null;
    try {
      list = (await api('GET', '/v1/devices')).devices;
    } catch (err) {
      if (authLost(err) && run === pairing.run) { pairStop(); render(err.message); }
    } finally {
      pairing.checking = false;
    }
    const hit = list && run === pairing.run && newcomer(p.before, list);
    if (hit) pairJoined(hit);
  };
  pairing.timer = setInterval(step, 1000);
  step();
}

async function pairExpired() {
  pairStop();
  await pairForget();
  $('pair').classList.add('expired');
  $('pair-hint').hidden = $('pair-left').hidden = $('pair-replace').hidden = $('pair-install').hidden = true;
  $('pair-expired').hidden = false;
  $('pair-expired').querySelector('span').textContent = t.pairExpired;
}

async function pairJoined({ joined, gone }) {
  pairStop();
  await pairForget();
  $('pair').hidden = true;
  $('add-device').hidden = false;
  say(`✓ ${gone ? t.deviceJoinedReplacing(joined.name, gone.name) : t.deviceJoined(joined.name)}`, true);
  showPlan(true);
}

// ── Opening ─────────────────────────────────────────────────────────────

/** The tab the user was looking at (also from the small window, which is a window of its own) */
async function currentTab() {
  try {
    const win = await chrome.windows.getLastFocused({ windowTypes: ['normal'] });
    const [tab] = await chrome.tabs.query({ active: true, windowId: win.id });
    return tab ?? null;
  } catch {
    return null;
  }
}

/** Title, description and selection from the page; nothing when it can't run scripts (chrome://, the store…) */
async function readTab(tab) {
  if (!tab?.id) return { page: {}, selection: '' };
  const page = { url: tab.url, title: tab.title };
  let selection = '';
  try {
    const [r] = await chrome.scripting.executeScript({ target: { tabId: tab.id }, func: readPage });
    Object.assign(page, r?.result ?? {}, { url: tab.url ?? r?.result?.url });
    const all = await chrome.scripting.executeScript({ target: { tabId: tab.id, allFrames: true }, func: readSelection });
    selection = all.map((x) => x.result).find((s) => s?.trim()) ?? '';
  } catch { /* the address and title are enough */ }
  return { page, selection };
}

async function showPlan(fresh = false) {
  const { me: cached } = await store.get();
  const draw = (m) => {
    $('plan').textContent = m ? t.planLine(t.plans[m.plan] ?? m.plan, m.devices_used, m.devices_limit) : '';
  };
  draw(cached?.data);
  try { draw(await me({ fresh })); } catch (err) { if (authLost(err)) render(err.message); }
}

async function render(message) {
  const { token } = await store.get();
  $('setup').hidden = !!token;
  $('top').hidden = !token;
  $('main').hidden = !token;
  $('unpair').hidden = !token;
  $('plan').textContent = '';
  if (!token) {
    pairStop();
    $('pair').hidden = $('add-device').hidden = true;
    setupFailed(message ?? null);
    $('code').focus();
    return;
  }
  showPlan();
  offerAdding();
  pruneRecent().then(drawRecent);                // what is over a week old is removed from storage as the list is opened
  compose.focus();
  const tab = await currentTab();
  const { page, selection } = await readTab(tab);
  Object.assign(state, { tab, page });
  if (selection.trim() && !compose.value) {
    compose.value = state.text = selection;
    state.fromSelection = true;
    compose.select();
  }
  grow();
  explain();
}

render();
