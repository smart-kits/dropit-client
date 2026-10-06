import { api, store, sendText, sendFile, me, authLost, forgetToken, PRIVACY_URL } from './api.js';
import { deviceKey } from './device.js';
import { t, lang } from './i18n.js';
import { fitMeta, pageMeta, intentOf, groupsFor, mb, fileSize, fileNameOf, seqRange, FILES_MAX } from './payload.js';
import { readPage, readSelection } from './page.js';

const $ = (id) => document.getElementById(id);
// Opened as a small window (setup from a right-click, or picking files where the popup can't stay open)
const inWindow = new URLSearchParams(location.search).has('window');
document.body.classList.toggle('window', inWindow);

document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
for (const el of document.querySelectorAll('[data-i18n]')) el.textContent = t.html[el.dataset.i18n];
$('code').placeholder = t.html.codePlaceholder;
// What joining sends, said before either button is pressed; the buttons read "Agree and …"
$('consent').append(`${t.consent} `, Object.assign(document.createElement('a'),
  { href: PRIVACY_URL, target: '_blank', rel: 'noopener', textContent: t.privacyLink }));
$('compose').placeholder = t.html.placeholder;

// A new issue with the version and browser filled in — never anything from this account
const version = chrome.runtime.getManifest().version;
$('issue').href = 'https://github.com/smart-kits/dropit-client/issues/new'
  + `?title=${encodeURIComponent(`[Chrome extension ${version}] `)}`
  + `&body=${encodeURIComponent(t.issueBody(`Extension: ${version} · ${navigator.userAgent}`))}`;

// status: File → 'busy' | 'done' while a batch is going out
const state = { text: '', files: [], fromSelection: false, page: {}, tab: null, busy: false, status: new Map() };

// ── First run ───────────────────────────────────────────────────────────

function setupFailed(message) {
  const box = $('setup-returned');
  box.hidden = !message;
  box.querySelector('span').textContent = message ?? '';
}

/** A joined browser gets a send-only token: a leaked one can't read anything */
async function adopt(res) {
  await store.set({ token: res.token, device_id: res.device_id });
  await chrome.storage.local.remove('previous_token');
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
  try { await fn(); } catch (err) { setupFailed(err.message); }
};

$('create').onclick = guardSetup(async () => {
  adopt(await api('POST', '/v1/accounts', { device_name: t.deviceName(navigator.platform), ...await sameBrowser() }, { auth: false }));
});
$('code').onkeydown = (ev) => { if (ev.key === 'Enter' && !ev.isComposing) $('claim').click(); };
$('code').oninput = () => setupFailed(null);
$('claim').onclick = guardSetup(async () => {
  const code = $('code').value.trim().toUpperCase();
  if (!code) return $('code').focus();
  adopt(await api('POST', '/v1/pair/claim',
    { code, device_name: t.deviceName(navigator.platform), scope: 'ingest_only', ...await sameBrowser() }, { auth: false }));
});

// Signing out removes this browser from the account first, so it stops using a device slot. Offline or
// already removed: forget it here anyway — the kept key lets the next join take its place.
$('unpair').onclick = async () => {
  const { token, device_id } = await store.get();
  try {
    await api('DELETE', `/v1/devices/${encodeURIComponent(device_id)}`);
    await chrome.storage.local.remove(['token', 'device_id', 'me', 'previous_token']);
  } catch {
    await forgetToken(token);
  }
  render();
};

// ── Sending ─────────────────────────────────────────────────────────────

const compose = $('compose');

function returned(message) {
  const box = $('returned');
  box.hidden = !message;
  box.querySelector('span').textContent = message ?? '';
}

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
  say(t.sendingNow);
  try {
    const out = await send(intent);
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
    say(deduped ? `✓ ${t.alreadySent(seqs[0])}` : t.sentLine(seqRange(seqs), clockNow()), true);
    compose.focus();
  } catch (err) {
    busy(false);
    if (authLost(err)) return render(err.message);
    drawFiles();
    explain();
    returned(err.done ? t.partlySent(err.done, err.total, err.message) : err.message);
  }
};

async function send(intent) {
  if (intent.type === 'page') {
    const res = await sendText({ kind: 'url', raw: state.page.url, meta: fitMeta(pageMeta(state.page)) });
    return { seqs: [res.seq], deduped: res.deduped };
  }
  if (intent.type === 'pdf') return sendPdf(state.page.url);
  if (intent.type === 'text') {
    const raw = intent.kind === 'url' ? state.text.trim() : state.text;
    const res = await sendText({ kind: intent.kind, raw, meta: fitMeta(intent.from ? { from: intent.from } : null) });
    return { seqs: [res.seq], deduped: res.deduped };
  }
  // Text and files as one batch, one after the other, with progress. Check everything before sending anything.
  const limit = (await me().catch(() => null))?.item_bytes_limit;
  const big = limit && state.files.find((f) => f.size > limit);
  if (big) throw new Error(`${big.name}: ${t.tooLarge(mb(limit))}`);
  const items = [...(intent.text ? [{ text: intent.text }] : []), ...state.files.map((file) => ({ file }))];
  if (state.files.length > FILES_MAX) throw new Error(t.batchTooLarge(FILES_MAX));
  const groups = groupsFor(items.length);
  const seqs = [];
  for (const [k, it] of items.entries()) {
    say(t.sendingOf(k + 1, items.length));
    if (it.file) { state.status.set(it.file, 'busy'); drawFiles(); }
    try {
      let res;
      if (it.text) {
        const kind = intentOf({ text: it.text }).kind;
        res = await sendText({ kind, raw: kind === 'url' ? it.text.trim() : it.text,
          meta: fitMeta({ ...(intent.from ? { from: intent.from } : {}), ...(groups[k] ? { group: groups[k] } : {}) }) });
      } else {
        res = await sendFile(it.file, { name: nameOf(it.file), mime: it.file.type, group: groups[k] });
        state.status.set(it.file, 'done');
        drawFiles();
      }
      seqs.push(res.seq);
    } catch (err) {
      // What went through stays sent: take it out, so trying again doesn't send it twice
      if (k > 0 && intent.text) { state.text = compose.value = ''; state.fromSelection = false; }
      state.files = state.files.filter((f) => state.status.get(f) !== 'done');
      state.status.clear();
      throw Object.assign(err, { done: k, total: items.length });
    }
  }
  return { seqs };
}

/**
 * The PDF this tab shows, as a file. Opening the popup lent this tab's site to the extension, which is
 * usually enough; a site that still refuses gets the one-site question in the small window.
 */
async function sendPdf(url) {
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
  const out = await sendFile(blob, { name: fileNameOf(url, res.headers.get('content-disposition')), mime: blob.type || 'application/pdf' });
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

async function showPlan() {
  const { me: cached } = await store.get();
  const draw = (m) => {
    $('plan').textContent = m ? t.planLine(t.plans[m.plan] ?? m.plan, m.devices_used, m.devices_limit) : '';
  };
  draw(cached?.data);
  try { draw(await me()); } catch (err) { if (authLost(err)) render(err.message); }
}

async function render(message) {
  const { token } = await store.get();
  $('setup').hidden = !!token;
  $('main').hidden = !token;
  $('unpair').hidden = !token;
  $('plan').textContent = '';
  if (!token) {
    setupFailed(message ?? null);
    $('code').focus();
    return;
  }
  showPlan();
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
