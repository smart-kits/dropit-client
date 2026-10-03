/**
 * Pure decisions, no browser APIs — so they can be tested in node (test/extension.test.mjs).
 *
 * What gets sent, with which metadata, under which menu item, and what an error means.
 */

// The service rejects metadata over 2 KB; stay a little under it.
export const META_BUDGET = 2000;
export const TITLE_MAX = 200;           // characters
export const DESCRIPTION_MAX = 300;     // characters
export const FROM_URL_MAX = 1024;       // bytes; a longer source address is left out, not cut
export const BATCH_MAX = 16;            // items sent together as one batch

const bytes = (s) => new TextEncoder().encode(s).length;
const metaBytes = (meta) => bytes(JSON.stringify(meta));
const isHttp = (url) => {
  try { return ['http:', 'https:'].includes(new URL(url).protocol); } catch { return false; }
};

/** Control characters and runs of whitespace (newlines included) become one space — as the service does */
export const squash = (s) => String(s ?? '').replace(/[\u0000-\u001f\u007f\s]+/g, ' ').trim();

/** Cut to `n` characters without splitting a surrogate pair; mark the cut with … */
export function clip(s, n) {
  const chars = Array.from(String(s ?? ''));
  if (chars.length <= n) return chars.join('');
  return n <= 0 ? '' : chars.slice(0, n - 1).join('').trimEnd() + '…';
}

/** "This page": its title and the description its author wrote */
export function pageMeta({ title, description } = {}) {
  const meta = {};
  const t = clip(squash(title), TITLE_MAX);
  const d = clip(squash(description), DESCRIPTION_MAX);
  if (t) meta.title = t;
  if (d) meta.description = d;
  return meta;
}

/** Where a selection or a file came from: the page's address and title, nothing else */
export function fromMeta(url, title) {
  if (!isHttp(url) || bytes(url) > FROM_URL_MAX) return null;
  const t = clip(squash(title), TITLE_MAX);
  return t ? { url, title: t } : { url };
}

/**
 * Make metadata fit the budget. Metadata never makes a send fail: the description gives way first,
 * then the titles, then the source; whatever is left is still valid.
 * @returns {object|null}
 */
export function fitMeta(input, limit = META_BUDGET) {
  if (!input) return null;
  const meta = structuredClone(input);
  for (const k of ['title', 'description']) if (!meta[k]) delete meta[k];
  if (meta.from && (!isHttp(meta.from.url) || bytes(meta.from.url) > FROM_URL_MAX)) delete meta.from;
  if (meta.from && !meta.from.title) delete meta.from.title;

  const fits = () => metaBytes(meta) <= limit;
  // Longest prefix of obj[key] that fits, by binary search over characters; gone if none does
  const shrink = (obj, key) => {
    const full = Array.from(obj[key]);
    let lo = 0, hi = full.length - 1;
    while (lo < hi) {
      const mid = Math.ceil((lo + hi) / 2);
      obj[key] = clip(full.join(''), mid);
      if (fits()) lo = mid; else hi = mid - 1;
    }
    if (lo > 0) obj[key] = clip(full.join(''), lo); else delete obj[key];
  };
  if (!fits() && meta.description) shrink(meta, 'description');
  if (!fits() && meta.title) shrink(meta, 'title');
  if (!fits() && meta.from?.title) shrink(meta.from, 'title');
  if (!fits()) delete meta.from;
  if (!fits()) for (const k of Object.keys(meta)) if (k !== 'group') delete meta[k];
  return Object.keys(meta).length ? meta : null;
}

/** A lone http(s) address is a link; anything else is text */
export function kindOf(text) {
  return /^https?:\/\/\S+$/i.test(String(text ?? '').trim()) ? 'url' : 'text';
}

/**
 * A file name worth keeping, or null to let the service name it by time and type.
 * Content-Disposition wins; then the last path segment, if it has an extension.
 */
export function fileNameOf(url, disposition) {
  const header = String(disposition ?? '');
  const star = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header);
  const plain = /filename\s*=\s*"?([^";]+)"?/.exec(header);
  const decode = (s) => { try { return decodeURIComponent(s); } catch { return s; } };
  const clean = (s) => s.replace(/[\\/\0-\x1f]/g, '').trim();
  if (star) return clean(decode(star[1].trim())) || null;
  if (plain) return clean(plain[1]) || null;
  let path;
  try { path = new URL(url).pathname; } catch { return null; }
  const last = clean(decode(path.split('/').pop() ?? ''));
  return /\.[A-Za-z0-9]{1,8}$/.test(last) && last.length <= 200 ? last : null;
}

const HTTP = ['http://*/*', 'https://*/*'];
// A link worth sending as a file is one whose address ends in a file extension (query string allowed).
// Links to ordinary pages don't get the item, so nobody sends themselves an HTML file by accident.
export const FILE_EXT = ['pdf', 'zip', 'rar', '7z', 'gz', 'tgz', 'tar', 'dmg', 'pkg', 'exe', 'msi', 'apk', 'ipa',
  'doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'key', 'pages', 'numbers', 'csv', 'txt', 'md', 'json', 'epub',
  'png', 'jpg', 'jpeg', 'gif', 'webp', 'avif', 'heic', 'svg',
  'mp4', 'mov', 'webm', 'mkv', 'm4v', 'mp3', 'm4a', 'wav', 'flac', 'ogg'];
const FILE_LINKS = FILE_EXT.flatMap((e) => [`*://*/*.${e}`, `*://*/*.${e}?*`]);

/**
 * Context menu items. Several that apply at once are folded by Chrome into one "dropit" submenu.
 * `title` is a key into the interface strings.
 */
export const MENUS = [
  { id: 'selection', title: 'menuSelection', contexts: ['selection'] },
  { id: 'link', title: 'menuLink', contexts: ['link'] },
  { id: 'link-file', title: 'menuLinkFile', contexts: ['link'], targetUrlPatterns: FILE_LINKS },
  // Image bytes: any source (http, data:, blob:) — the protocol is sorted out after the click
  { id: 'image', title: 'menuImage', contexts: ['image'] },
  { id: 'image-link', title: 'menuImageLink', contexts: ['image'], targetUrlPatterns: HTTP },
  // Streaming players use blob: sources with nothing to download: only a real file gets the file item
  { id: 'video', title: 'menuVideo', contexts: ['video'], targetUrlPatterns: HTTP },
  { id: 'audio', title: 'menuAudio', contexts: ['audio'], targetUrlPatterns: HTTP },
  { id: 'media-page', title: 'menuPage', contexts: ['video', 'audio'] },
  { id: 'page', title: 'menuPage', contexts: ['page'] },
  { id: 'action-page', title: 'menuPage', contexts: ['action'] },
];

/** Batch markers for n things sent together, or null for one */
export function groupsFor(n, id = randomId()) {
  if (n > BATCH_MAX) throw Object.assign(new Error('batch too large'), { code: 'BATCH_TOO_LARGE' });
  if (n < 2) return Array.from({ length: n }, () => null);
  return Array.from({ length: n }, (_, k) => ({ id, i: k + 1, n }));
}

function randomId() {
  const a = new Uint8Array(12);
  crypto.getRandomValues(a);
  return Array.from(a, (b) => 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_'[b & 63]).join('');
}

/**
 * What Enter in the popup sends.
 * @param {{text: string, files: File[], fromSelection: boolean, page: {url, title, description}}} s
 */
export function intentOf({ text = '', files = [], fromSelection = false, page = {} }) {
  const body = text.trim() ? text : '';
  if (files.length) return { type: 'batch', text: body, files, from: body && fromSelection ? fromMeta(page.url, page.title) : null };
  if (body) return { type: 'text', kind: kindOf(body), from: fromSelection ? fromMeta(page.url, page.title) : null };
  return { type: 'page' };
}

/** "5 MB", "1.5 MB" */
export const mb = (n) => {
  const v = n / 1048576;
  return `${Number.isInteger(v) ? v : v.toFixed(1)} MB`;
};

/** A file's size for people: "820 B", "14 KB", "2.3 MB" */
export function fileSize(n) {
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${Math.round(n / 1024)} KB`;
  return `${(n / 1048576).toFixed(1)} MB`;
}

/** A local "HH:MM" `seconds` from now */
const clock = (seconds, now) => {
  const d = new Date(now + seconds * 1000);
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
};

/** The sentence for an error answer from the service. Numbers come from the answer, never from here. */
export function errorText(code, data = {}, status, t, now = Date.now()) {
  if (code === 'PAYLOAD_TOO_LARGE' && data.field === 'size' && data.limit) return t.tooLarge(mb(data.limit));
  if (code === 'RATE_LIMITED' && data.window === 'day') return t.rateDay(clock(data.retry_after ?? 0, now));
  if (code === 'RATE_LIMITED' && data.window === 'hour') return t.rateHour;
  if (code === 'QUOTA_EXCEEDED' && data.field === 'items') return t.quotaItems;
  if (code === 'QUOTA_EXCEEDED' && data.field === 'bytes') return t.quotaBytes;
  return t.errors[code] ?? t.httpFailed(status);
}
