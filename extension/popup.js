import { api, store, send } from './api.js';
import { t, lang } from './i18n.js';

const $ = (id) => document.getElementById(id);

// Static text: every element with data-i18n="key" gets t.html[key]
document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
for (const el of document.querySelectorAll('[data-i18n]')) el.textContent = t.html[el.dataset.i18n];
$('code').placeholder = t.html.codePlaceholder;
const say = (text) => { $('msg').textContent = text; };

const guard = (fn) => async () => {
  try { await fn(); } catch (err) { say(err.message); }
};

/** A paired browser gets an ingest_only token: send-only, so a leaked token can't read anything */
async function adopt(res) {
  await store.set({ token: res.token, device_id: res.device_id });
  render();
}

$('create').onclick = guard(async () => {
  adopt(await api('POST', '/v1/accounts', { device_name: t.deviceName(navigator.platform) }, false));
});

$('claim').onclick = guard(async () => {
  const code = $('code').value.trim().toUpperCase();
  if (!code) return;
  adopt(await api('POST', '/v1/pair/claim',
    { code, device_name: t.deviceName(navigator.platform), scope: 'ingest_only' }, false));
});

$('page').onclick = guard(async () => {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.url) throw new Error(t.noPageUrl);
  const res = await send({ kind: 'url', raw: tab.url });
  say(res.deduped ? t.alreadySent(res.seq) : `✓ ${t.sent(res.seq)}`);
});

$('unpair').onclick = guard(async () => {
  await chrome.storage.local.clear();
  render();
});

async function render() {
  const { token } = await store.get();
  $('setup').hidden = !!token;
  $('main').hidden = !token;
  say('');
  if (!token) return;
  try {
    const me = await api('GET', '/v1/me');
    $('who').textContent = t.devices(me);
  } catch (err) {
    $('who').textContent = 'dropit';
    say(err.message);
  }
}

render();
