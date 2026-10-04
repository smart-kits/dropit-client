/**
 * Functions run inside the page with chrome.scripting.executeScript. Each one is serialized on its own,
 * so it must not use anything outside its own body.
 *
 * Reading bytes from inside the page is the first choice for images and files: the request goes out
 * as the page (its origin, its referrer, its cache), which most image hosts accept. The extension asks
 * for access to a site only when that fails.
 */

/** This page's title and the description its author wrote */
export function readPage() {
  const meta = (sel) => document.querySelector(sel)?.getAttribute('content')?.trim() || '';
  return {
    url: location.href,
    contentType: document.contentType,     // application/pdf in Chrome's PDF viewer
    title: document.title.trim() || meta('meta[property="og:title"]'),
    description: meta('meta[name="description"]') || meta('meta[property="og:description"]')
      || meta('meta[name="twitter:description"]'),
  };
}

/** The selected text with its line breaks (a text field keeps its own selection) */
export function readSelection() {
  const el = document.activeElement;
  const field = el && (el.tagName === 'TEXTAREA'
    || (el.tagName === 'INPUT' && /^(text|search|url|email|tel)$/i.test(el.type)));
  if (field && el.selectionEnd > el.selectionStart) return el.value.slice(el.selectionStart, el.selectionEnd);
  return String(globalThis.getSelection?.() ?? '');
}

/**
 * Download `url` as the page and keep it here until fetchChunk has read it.
 * Stops as soon as it is larger than `limit` bytes, so an oversized video is never fully downloaded.
 * @returns {{id, size, type, disposition} | {error: 'http'|'large'|'network', status?, size?, message?}}
 */
export async function fetchStart(url, limit) {
  try {
    // A connection dropped on the way (a flaky proxy) is tried twice more, the last time past the cache,
    // before giving up — giving up here means asking the user for access to the site
    let res;
    for (const [wait, cache] of [[0, 'default'], [400, 'default'], [1200, 'reload']]) {
      if (wait) await new Promise((r) => setTimeout(r, wait));
      try { res = await fetch(url, { cache }); break; } catch (err) { if (cache === 'reload') throw err; }
    }
    if (!res.ok) return { error: 'http', status: res.status };
    const declared = Number(res.headers.get('content-length') || 0);
    if (limit && declared > limit) {
      res.body?.cancel();
      return { error: 'large', size: declared };
    }
    const reader = res.body.getReader();
    const parts = [];
    let size = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.length;
      if (limit && size > limit) {
        reader.cancel();
        return { error: 'large', size };
      }
      parts.push(value);
    }
    const type = (res.headers.get('content-type') || '').split(';')[0].trim();
    const id = crypto.randomUUID();
    (globalThis.__dropitFiles ??= new Map()).set(id, new Blob(parts, { type }));
    return { id, size, type, disposition: res.headers.get('content-disposition') };
  } catch (err) {
    return { error: 'network', message: String(err?.message ?? err) };
  }
}

/** Bytes [start, end) of a downloaded file, as base64 (messages to the extension carry text only) */
export async function fetchChunk(id, start, end) {
  const blob = globalThis.__dropitFiles?.get(id);
  if (!blob) return null;
  const bytes = new Uint8Array(await blob.slice(start, end).arrayBuffer());
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

export function fetchDrop(id) {
  globalThis.__dropitFiles?.delete(id);
}

/**
 * Say how a right-click or shortcut send went, on the page itself: the toolbar badge is easy to miss,
 * and invisible when the icon isn't pinned. A shadow root keeps the page's styles out of it.
 * @param {string} text
 * @param {boolean} failed red, stays longer
 */
export function showToast(text, failed) {
  const ID = 'dropit-toast-host';
  document.getElementById(ID)?.remove();
  const host = document.createElement('div');
  host.id = ID;
  const root = host.attachShadow({ mode: 'closed' });
  const dark = matchMedia('(prefers-color-scheme: dark)').matches;
  const ink = dark ? '#e9eaec' : '#15171c';
  const paper = dark ? '#1b1e24' : '#ffffff';
  const red = dark ? '#ff6b78' : '#c8102e';
  // At the top, where the eye already is after right-clicking; a full-width row centres it (a box at left: 50%
  // would only get half the width and wrap early)
  const row = document.createElement('div');
  Object.assign(row.style, {
    position: 'fixed', left: '0', right: '0', top: '16px', zIndex: '2147483647',
    display: 'flex', justifyContent: 'center', padding: '0 16px', pointerEvents: 'none',
  });
  const box = document.createElement('div');
  box.setAttribute('role', failed ? 'alert' : 'status');
  box.textContent = text;
  Object.assign(box.style, {
    maxWidth: '480px', transform: 'translateY(-8px)', opacity: '0', transition: 'opacity .2s, transform .3s',
    padding: '10px 16px', borderRadius: '6px', font: `${failed ? 600 : 400} 14px/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif`,
    background: failed ? paper : ink, color: failed ? red : paper, border: failed ? `2px solid ${red}` : '0',
    boxShadow: '0 1px 2px rgba(21, 23, 28, .06), 0 4px 14px rgba(21, 23, 28, .12)', overflowWrap: 'anywhere',
  });
  row.append(box);
  root.append(row);
  (document.body ?? document.documentElement).append(host);
  const still = matchMedia('(prefers-reduced-motion: reduce)').matches;
  if (still) box.style.transition = 'none';
  requestAnimationFrame(() => { box.style.opacity = '1'; box.style.transform = 'none'; });
  setTimeout(() => {
    box.style.opacity = '0';
    setTimeout(() => host.remove(), still ? 0 : 300);
  }, failed ? 5000 : 1800);
  return true;
}
