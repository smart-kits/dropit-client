import { t, lang } from './i18n.js';

const $ = (id) => document.getElementById(id);
document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
for (const el of document.querySelectorAll('[data-i18n]')) el.textContent = t.html[el.dataset.i18n];

const job = JSON.parse(new URLSearchParams(location.search).get('job') ?? 'null');
const url = new URL(job?.url ?? 'about:blank');
document.title = $('title').textContent = t.grantTitle(url.host);

// The click here is what lets the browser show its own permission prompt
$('allow').onclick = async () => {
  if (!/^https?:$/.test(url.protocol)) return window.close();
  const granted = await chrome.permissions.request({ origins: [`${url.protocol}//${url.host}/*`] });
  if (!granted) {
    $('returned').hidden = false;
    $('returned').querySelector('span').textContent = t.accessDenied;
    return;
  }
  await chrome.runtime.sendMessage({ type: 'retry-file', job });
  window.close();
};
$('cancel').onclick = () => window.close();
$('allow').focus();
