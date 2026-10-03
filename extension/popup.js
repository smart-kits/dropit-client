import { api, store, sendText, sendFile, me, authLost } from './api.js';
import { t, lang } from './i18n.js';
import { fitMeta, pageMeta, intentOf, groupsFor, mb, fileSize, BATCH_MAX } from './payload.js';
import { readPage, readSelection } from './page.js';

const $ = (id) => document.getElementById(id);
// Opened as a small window (setup from a right-click, or picking files where the popup can't stay open)
const inWindow = new URLSearchParams(location.search).has('window');
document.body.classList.toggle('window', inWindow);

document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
for (const el of document.querySelectorAll('[data-i18n]')) el.textContent = t.html[el.dataset.i18n];
$('code').placeholder = t.html.codePlaceholder;
$('compose').placeholder = t.html.placeholder;

// A new issue with the version and browser filled in — never anything from this account
const version = chrome.runtime.getManifest().version;
$('issue').href = 'https://github.com/smart-kits/dropit-client/issues/new'
  + `?title=${encodeURIComponent(`[Chrome extension ${version}] `)}`
  + `&body=${encodeURIComponent(t.issueBody(`Extension: ${version} · ${navigator.userAgent}`))}`;

const state = { text: '', files: [], fromSelection: false, page: {}, tab: null, busy: false };

// ── First run ───────────────────────────────────────────────────────────

function setupFailed(message) {
  const box = $('setup-returned');
  box.hidden = !message;
  box.querySelector('span').textContent = message ?? '';
}

/** A joined browser gets a send-only token: a leaked one can't read anything */
async function adopt(res) {
  await store.set({ token: res.token, device_id: res.device_id });
  render();
}

const guardSetup = (fn) => async () => {
  setupFailed(null);
  try { await fn(); } catch (err) { setupFailed(err.message); }
};

$('create').onclick = guardSetup(async () => {
  adopt(await api('POST', '/v1/accounts', { device_name: t.deviceName(navigator.platform) }, { auth: false }));
});
$('code').onkeydown = (ev) => { if (ev.key === 'Enter' && !ev.isComposing) $('claim').click(); };
$('code').oninput = () => setupFailed(null);
$('claim').onclick = guardSetup(async () => {
  const code = $('code').value.trim().toUpperCase();
  if (!code) return $('code').focus();
  adopt(await api('POST', '/v1/pair/claim',
    { code, device_name: t.deviceName(navigator.platform), scope: 'ingest_only' }, { auth: false }));
});

$('unpair').onclick = async () => {
  await chrome.storage.local.remove(['token', 'device_id', 'me']);
  render();
};

// ── Sending ─────────────────────────────────────────────────────────────

const compose = $('compose');

function returned(message) {
  const box = $('returned');
  box.hidden = !message;
  box.querySelector('span').textContent = message ?? '';
}

/** Say what Enter will send; there is never a choice to make */
function explain() {
  const intent = intentOf(state);
  const host = (() => { try { return new URL(state.page.url).hostname; } catch { return ''; } })();
  $('will').textContent =
    intent.type === 'batch' ? t.willBatch(state.files.length, !!intent.text)
    : intent.type === 'text' ? (intent.from ? t.willTextFrom(host) : t.willText)
    : state.page.url ? t.willPage(state.page.title || host || state.page.url) : '';
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
    const name = Object.assign(document.createElement('span'), { className: 'fn', textContent: f.name, title: f.name });
    const size = Object.assign(document.createElement('span'), { className: 'sz', textContent: fileSize(f.size) });
    const x = Object.assign(document.createElement('button'), { type: 'button', className: 'x', textContent: '×' });
    x.setAttribute('aria-label', t.removeFile(f.name));
    x.onclick = () => { state.files.splice(k, 1); drawFiles(); explain(); compose.focus(); };
    li.append(name, size, x);
    return li;
  }));
}

// Pasted screenshots are all called image.png: let the service name them by time instead
const nameOf = (f) => (/^image\.(png|jpe?g|gif|webp)$/i.test(f.name) ? undefined : f.name);

$('send-form').onsubmit = async (ev) => {
  ev.preventDefault();
  if (state.busy) return;
  const intent = intentOf(state);
  if (intent.type === 'page' && !state.page.url) return compose.focus();
  busy(true);
  returned(null);
  try {
    const res = await send(intent);
    // Hand the ✓ to the background before closing: a message still in flight dies with the popup
    await chrome.runtime.sendMessage({ type: 'flash', ok: true, title: res.deduped ? t.alreadySent(res.seq) : t.sent(res.seq) }).catch(() => {});
    window.close();
  } catch (err) {
    if (authLost(err)) return render(err.message);
    returned(err.message);
    busy(false);
  }
};

async function send(intent) {
  if (intent.type === 'page') {
    return sendText({ kind: 'url', raw: state.page.url, meta: fitMeta(pageMeta(state.page)) });
  }
  if (intent.type === 'text') {
    const raw = intent.kind === 'url' ? state.text.trim() : state.text;
    return sendText({ kind: intent.kind, raw, meta: fitMeta(intent.from ? { from: intent.from } : null) });
  }
  // Text and files as one batch. Check everything before sending anything.
  const limit = (await me().catch(() => null))?.item_bytes_limit;
  const big = limit && state.files.find((f) => f.size > limit);
  if (big) throw new Error(`${big.name}: ${t.tooLarge(mb(limit))}`);
  const count = (intent.text ? 1 : 0) + state.files.length;
  if (count > BATCH_MAX) throw new Error(t.batchTooLarge(BATCH_MAX));
  const groups = groupsFor(count);
  let res;
  if (intent.text) {
    const group = groups.shift();
    const kind = intentOf({ text: intent.text }).kind;
    res = await sendText({ kind, raw: kind === 'url' ? intent.text.trim() : intent.text,
      meta: fitMeta({ ...(intent.from ? { from: intent.from } : {}), ...(group ? { group } : {}) }) });
    // Sent: a retry after a later failure must not send it again
    state.text = compose.value = '';
    state.fromSelection = false;
  }
  while (state.files.length) {
    const file = state.files[0];
    res = await sendFile(file, { name: nameOf(file), mime: file.type, group: groups.shift() });
    state.files.shift();
    drawFiles();
  }
  return res;
}

function busy(on) {
  state.busy = on;
  $('send').setAttribute('aria-busy', String(on));
  $('send').disabled = on;
  $('stamp-word').textContent = on ? t.html.sending : t.html.stamp;
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
