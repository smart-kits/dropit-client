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
    const res = await fetch(url);
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
