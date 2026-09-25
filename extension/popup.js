import { api, store, send } from './api.js';

const $ = (id) => document.getElementById(id);
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
  adopt(await api('POST', '/v1/accounts', { device_name: `浏览器 · ${navigator.platform}` }, false));
});

$('claim').onclick = guard(async () => {
  const code = $('code').value.trim().toUpperCase();
  if (!code) return;
  adopt(await api('POST', '/v1/pair/claim',
    { code, device_name: `浏览器 · ${navigator.platform}`, scope: 'ingest_only' }, false));
});

$('page').onclick = guard(async () => {
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  if (!tab?.url) throw new Error('这个页面拿不到地址');
  const res = await send({ kind: 'url', raw: tab.url });
  say(res.deduped ? `已投过了 #${res.seq}` : `✓ 已投递 #${res.seq}`);
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
    $('who').textContent = `${me.plan} · ${me.devices_used}/${me.devices_limit ?? '∞'} 台设备`;
  } catch (err) {
    $('who').textContent = 'dropit';
    say(err.message);
  }
}

render();
