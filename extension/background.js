import { store, sendText, sendFile, me, authLost } from './api.js';
import { t } from './i18n.js';
import { MENUS, fitMeta, pageMeta, fromMeta, fileNameOf, mb } from './payload.js';
import { readPage, readSelection, fetchStart, fetchChunk, fetchDrop } from './page.js';

const NAVY = '#1f4e9e';   // arrived
const RED = '#c8102e';    // failed, or not set up
const CHUNK = 4 << 20;    // bytes per message when bringing a file out of the page

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.removeAll(() => {
    for (const { title, ...m } of MENUS) chrome.contextMenus.create({ ...m, title: t[title] });
  });
  badge();
});
chrome.runtime.onStartup.addListener(badge);
chrome.storage.onChanged.addListener((changes) => { if ('token' in changes) badge(); });

chrome.contextMenus.onClicked.addListener((info, tab) => run(() => fromMenu(info, tab)));
chrome.commands.onCommand.addListener((command, tab) => {
  if (command === 'drop-now') run(() => dropNow(tab));
});

chrome.runtime.onMessage.addListener((msg) => {
  // The popup sends on its own, then closes: the badge outlives it here
  if (msg?.type === 'flash') flash(msg.ok, msg.title);
  // The access window got permission for a site: fetch the file again, now as the extension
  if (msg?.type === 'retry-file') run(() => fileFromHere(msg.job));
});

/** Success is quiet, failure is loud — never pretend a send worked while offline */
async function run(fn) {
  try {
    const res = await fn();
    if (res?.pending) return;          // waiting for the user in the access window
    if (res) flash(true, res.deduped ? t.alreadySent(res.seq) : t.sent(res.seq));
  } catch (err) {
    console.warn('dropit:', err);
    if (authLost(err)) return notify(t.revokedTitle, t.revoked);
    flash(false, err.message);
    notify(t.failedTitle, err.message);
  }
}

async function fromMenu(info, tab) {
  if (!(await store.get()).token) return openSetup();
  switch (info.menuItemId) {
    case 'selection': {
      const text = (await inPage(tab, readSelection, [], info.frameId)) || info.selectionText;
      return sendSelection(text, tab);
    }
    case 'link': return sendText({ kind: 'url', raw: info.linkUrl });
    case 'image-link': return sendText({ kind: 'url', raw: info.srcUrl });
    case 'link-file': return fileFromPage(info.linkUrl, tab, info.frameId);
    case 'image':
    case 'video':
    case 'audio':
      if (/^blob:/i.test(info.srcUrl) && info.mediaType !== 'image') throw new Error(t.noFile);
      return fileFromPage(info.srcUrl, tab, info.frameId);
    default: return sendPage(tab);   // page, media-page, action-page
  }
}

/** The shortcut that sends without opening anything: the selection if there is one, else the page */
async function dropNow(tab) {
  if (!(await store.get()).token) return openSetup();
  const text = await selectionAnywhere(tab);
  return text.trim() ? sendSelection(text, tab) : sendPage(tab);
}

function sendSelection(text, tab) {
  return sendText({ kind: 'text', raw: text, meta: fitMeta({ from: fromMeta(tab?.url, tab?.title) }) });
}

/** The page: address, title, and the description its author wrote */
async function sendPage(tab) {
  if (!tab?.url) throw new Error(t.noPageUrl);
  const page = (await inPage(tab, readPage)) ?? { title: tab.title };
  return sendText({ kind: 'url', raw: tab.url, meta: fitMeta(pageMeta(page)) });
}

/**
 * A file the page shows or links to. First as the page (no permission needed); if the site won't
 * let the page read it, ask for access to that one site and fetch it as the extension.
 */
async function fileFromPage(url, tab, frameId) {
  const job = { url, from: fromMeta(tab?.url, tab?.title) };
  if (/^data:/i.test(url)) return fileFromHere(job);
  const limit = (await me().catch(() => null))?.item_bytes_limit;
  const got = await inPage(tab, fetchStart, [url, limit], frameId);
  if (got?.id) {
    try {
      return await upload(await bringOut(tab, frameId, got), got, job);
    } finally {
      inPage(tab, fetchDrop, [got.id], frameId);
    }
  }
  if (got?.error === 'large') throw tooLarge(limit);
  if (got?.error === 'http') throw new Error(t.hotlinked);
  // Blocked for other sites (or the page can't run scripts): ask for this one site
  if (!/^https?:/i.test(url)) throw new Error(t.fetchFailed);
  return withAccess(job);
}

/** Fetch as the extension: data: addresses, and sites the user gave access to */
async function fileFromHere(job) {
  const limit = (await me().catch(() => null))?.item_bytes_limit;
  let res;
  try {
    res = await fetch(job.url, { credentials: 'include' });
  } catch {
    throw new Error(t.fetchFailed);
  }
  if (!res.ok) throw new Error(t.hotlinked);
  if (limit && Number(res.headers.get('content-length') || 0) > limit) {
    res.body?.cancel();
    throw tooLarge(limit);
  }
  const blob = await res.blob();
  if (limit && blob.size > limit) throw tooLarge(limit);
  const type = (res.headers.get('content-type') || '').split(';')[0].trim();
  return upload(blob, { type, disposition: res.headers.get('content-disposition') }, job);
}

function upload(blob, { type, disposition }, { url, from }) {
  return sendFile(blob, { name: fileNameOf(url, disposition), mime: type || blob.type || 'application/octet-stream', from });
}

/** Copy a file the page downloaded into this worker, a few MB per message */
async function bringOut(tab, frameId, { id, size, type }) {
  const parts = [];
  for (let start = 0; start < size; start += CHUNK) {
    const b64 = await inPage(tab, fetchChunk, [id, start, start + CHUNK], frameId);
    if (b64 == null) throw new Error(t.fetchFailed);
    parts.push(Uint8Array.from(atob(b64), (c) => c.charCodeAt(0)));
  }
  return new Blob(parts, { type });
}

async function withAccess(job) {
  const { protocol, host } = new URL(job.url);
  const origins = [`${protocol}//${host}/*`];
  if (await chrome.permissions.contains({ origins })) return fileFromHere(job);
  try {
    if (await chrome.permissions.request({ origins })) return fileFromHere(job);
    throw new Error(t.accessDenied);
  } catch (err) {
    if (err.message === t.accessDenied) throw err;
    // Not allowed outside a click on an extension page: open a small window with one button
    await chrome.windows.create({
      url: `grant.html?job=${encodeURIComponent(JSON.stringify(job))}`,
      type: 'popup', width: 420, height: 260, focused: true,
    });
    return { pending: true };
  }
}

/** Run one of page.js's functions in a frame; null when the page doesn't allow scripts (chrome://, the store…) */
async function inPage(tab, func, args = [], frameId = 0) {
  if (!tab?.id) return null;
  try {
    const [r] = await chrome.scripting.executeScript({ target: { tabId: tab.id, frameIds: [frameId ?? 0] }, func, args });
    return r?.result ?? null;
  } catch {
    return null;
  }
}

/** The first non-empty selection in any frame */
async function selectionAnywhere(tab) {
  if (!tab?.id) return '';
  try {
    const all = await chrome.scripting.executeScript({ target: { tabId: tab.id, allFrames: true }, func: readSelection });
    return all.map((r) => r.result).find((s) => s?.trim()) ?? '';
  } catch {
    return '';
  }
}

const tooLarge = (limit) => new Error(limit ? t.tooLarge(mb(limit)) : t.errors.PAYLOAD_TOO_LARGE);

/** Not set up yet: open the popup on the join form (or the same page in a small window) */
async function openSetup() {
  try {
    await chrome.action.openPopup();
  } catch {
    await chrome.windows.create({ url: 'popup.html?window=1', type: 'popup', width: 400, height: 560, focused: true });
  }
  return null;
}

function notify(title, message) {
  chrome.notifications.create({ type: 'basic', iconUrl: 'icon.png', title, message });
}

/** ✓ in navy or ✗ in red for 2.5 s, then back to the resting state */
function flash(ok, title) {
  chrome.action.setBadgeText({ text: ok ? '✓' : '✗' });
  chrome.action.setBadgeBackgroundColor({ color: ok ? NAVY : RED });
  chrome.action.setBadgeTextColor?.({ color: '#ffffff' });
  chrome.action.setTitle({ title: `dropit: ${title}` });
  setTimeout(badge, 2500);
}

/** Resting state: nothing when set up, a red ! when this browser isn't joined to an account */
async function badge() {
  const { token } = await store.get();
  chrome.action.setBadgeText({ text: token ? '' : '!' });
  chrome.action.setBadgeBackgroundColor({ color: RED });
  chrome.action.setBadgeTextColor?.({ color: '#ffffff' });
  chrome.action.setTitle({ title: token ? t.actionTitle : t.unpairedTitle });
}
