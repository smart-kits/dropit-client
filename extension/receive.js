/**
 * What other devices sent, as this browser lists it: fetched when the popup opens or when asked (never in the
 * background), kept in this browser only (storage.local), and never sent anywhere. The decisions that don't need a
 * browser live here so they can be tested in node (test/extension.test.mjs); the popup only draws.
 *
 * The list holds what *other* devices sent — never what this browser sent itself — at most RECEIVED_MAX, for
 * RECEIVED_DAYS days, and is emptied when this browser signs out or its key stops working.
 */
import { squash, clip, openableLink, hostOf } from './payload.js';

export const RECEIVED_KEY = 'received';
export const OWN_IDS_KEY = 'own_ids';         // this browser's earlier device ids (turning receiving on joins again under a new one): what they sent is its own
export const OWN_IDS_MAX = 8;
export const RECV_AT_KEY = 'recv_at';        // when the list was last refreshed, so opening the popup again soon does not pull again
export const CURSOR_KEY = 'recv_cursor';      // the last position handled; advances only after what came before it is in the list
export const RECEIVED_MAX = 50;
export const RECEIVED_DAYS = 7;
export const RECEIVED_TEXT_MAX = 20_000;      // text longer than this is listed, but not kept whole for copying
const LINK_MAX = 2048;                        // a longer address is listed, but not kept to open
export const PAGE_SIZE = 200;                 // items asked for per request
export const PAGES_PER_REFRESH = 3;           // requests per refresh at most; a bigger backlog is caught up on the next one

const DAY = 864e5;
const fresh = (e, now) => !!e && Number.isInteger(e.seq) && typeof e.at === 'number' && now - e.at < RECEIVED_DAYS * DAY;

/** The address that asks for what came after `after` (`why` says what the person did: open · manual) */
export const pullPath = (after, why, limit = PAGE_SIZE) => `/v1/pull?after=${Math.max(0, Math.trunc(after) || 0)}&limit=${limit}&why=${why}`;

/**
 * One item from the service as a list entry: { seq, kind: 'text' | 'url' | 'file', label, host?, text?, mime?, bytes?, from, at, sent? } (`at` is when it was taken in, `sent` when the other device sent it: that is the time shown).
 * A `url` that is not an http(s) address is just text (it can be copied, never opened). A file keeps no address: its
 * download link lasts minutes, so a fresh one is asked for when it is opened (`fileLinkFrom`). `label` may be empty for
 * a file with no name; the popup then says "Image" or "File".
 */
export function entryOf(item, now = Date.now()) {
  const raw = typeof item.raw === 'string' ? item.raw : '';
  const g = item.meta?.group;
  const group = g && typeof g.id === 'string' && g.id && g.id.length <= 64 && Number.isInteger(g.i) && Number.isInteger(g.n) && g.n >= 2 && g.n <= 50
    ? { id: g.id, i: g.i, n: g.n } : null;
  const base = { seq: item.seq, from: typeof item.device_id === 'string' ? item.device_id : null, at: now, ...(Number.isFinite(item.created_at) ? { sent: item.created_at } : {}), ...(group ? { group } : {}) };
  if (['image', 'video', 'file'].includes(item.kind)) {
    const name = squash(item.meta?.filename);
    const mime = typeof item.meta?.mime === 'string' ? item.meta.mime.slice(0, 128) : '';
    return { ...base, kind: 'file', label: clip(name, 120), mime: mime || (item.kind === 'image' ? 'image/*' : ''), ...(Number.isFinite(item.bytes) ? { bytes: item.bytes } : {}) };
  }
  const link = item.kind === 'url' ? openableLink(raw) : null;
  if (link) {
    const host = hostOf(link);
    const title = squash(item.meta?.title);
    return { ...base, kind: 'url', label: clip(title || host || squash(raw), 120), host, ...(link.length <= LINK_MAX ? { text: link } : {}) };
  }
  return { ...base, kind: 'text', label: clip(squash(raw), 120), ...(raw.length <= RECEIVED_TEXT_MAX ? { text: raw } : {}) };
}

/**
 * The list after taking in a page of items: only other devices' (`selfId` is this browser's own id, or a list of the ids it
 * has had; without one nothing is taken in, since there is no telling), nothing already listed, newest first, at most RECEIVED_MAX,
 * nothing past RECEIVED_DAYS. `at` is when it was taken in, so the week is counted from receiving.
 * @returns {{ list: object[], added: number }}
 */
export function mergeReceived(list, items, selfId, now = Date.now()) {
  const kept = (Array.isArray(list) ? list : []).filter((e) => fresh(e, now));
  const own = new Set([selfId].flat().filter((x) => typeof x === 'string' && x));
  if (!own.size) return { list: kept.slice(0, RECEIVED_MAX), added: 0 };
  const have = new Set(kept.map((e) => e.seq));
  const incoming = [];
  for (const item of Array.isArray(items) ? items : []) {
    if (!Number.isInteger(item?.seq) || own.has(item.device_id) || have.has(item.seq)) continue;
    have.add(item.seq);
    incoming.push(entryOf(item, now));
  }
  const merged = [...incoming, ...kept].sort((a, b) => b.seq - a.seq).slice(0, RECEIVED_MAX);
  return { list: merged, added: incoming.filter((e) => merged.includes(e)).length };
}

/**
 * Fetch what is new and put it in the list. Each page is written together with the position after it, in one write: the
 * position moves only once what is before it is in the list, and a page that fails leaves the earlier pages kept.
 * The first time (no position yet) it starts from the beginning and the list keeps the newest RECEIVED_MAX of what others sent —
 * nothing pops up anywhere, so showing the recent past is the useful thing, and the service only hands over what arrived
 * after this browser joined.
 * `pull(path)` returns the answer ({ items, has_more, next_after }); `local` is chrome.storage.local.
 * @returns {{ list: object[], added: number, more: boolean }}
 */
export async function refreshReceived({ pull, selfId, why, local, now = Date.now() }) {
  const stored = await local.get([RECEIVED_KEY, CURSOR_KEY]);
  let after = Number.isInteger(stored[CURSOR_KEY]) && stored[CURSOR_KEY] >= 0 ? stored[CURSOR_KEY] : 0;
  let list = (Array.isArray(stored[RECEIVED_KEY]) ? stored[RECEIVED_KEY] : []).filter((e) => fresh(e, now));
  let added = 0, more = false;
  for (let page = 0; page < PAGES_PER_REFRESH; page++) {
    const res = await pull(pullPath(after, why));
    const merged = mergeReceived(list, res?.items, selfId, now);
    list = merged.list;
    added += merged.added;
    if (Number.isInteger(res?.next_after) && res.next_after > after) after = res.next_after;
    await local.set({ [RECEIVED_KEY]: list, [CURSOR_KEY]: after });
    more = !!res?.has_more;
    if (!more) break;
  }
  return { list, added, more };
}

/** A fresh download link for one received file, or null when it is no longer there (expired, removed). `pull(path)` as above. */
export async function fileLinkFrom(seq, pull) {
  const res = await pull(pullPath(seq - 1, 'open', 1));
  const item = res?.items?.find((i) => i.seq === seq);
  return item ? openableLink(item.url) : null;
}

/**
 * Things sent together arrive as several items but were one letter on the other side, so they are one here too.
 * `list` is newest first; a letter stands where its newest member is, its members in the order they were sent.
 * @returns {({ one: object } | { id: string, members: object[], n: number })[]}
 */
export function groupReceived(list) {
  const out = [];
  const at = new Map();
  for (const e of Array.isArray(list) ? list : []) {
    if (!e.group) { out.push({ one: e }); continue; }
    const have = at.get(e.group.id);
    if (have) { have.members.push(e); continue; }
    const unit = { id: e.group.id, members: [e], n: e.group.n };
    at.set(e.group.id, unit);
    out.push(unit);
  }
  for (const u of out) if (u.members) u.members.sort((a, b) => (a.group.i - b.group.i) || (a.seq - b.seq));
  return out;
}

/** The ids to count as this browser's own: the current one and the ones it had before (see OWN_IDS_KEY) */
export const ownIds = (selfId, stored) => [selfId, ...(Array.isArray(stored) ? stored : [])].filter((x) => typeof x === 'string' && x);

/** The list of earlier ids with `previous` added (newest kept, at most OWN_IDS_MAX); unchanged when there is nothing to add */
export function rememberOwn(stored, previous) {
  const have = Array.isArray(stored) ? stored.filter((x) => typeof x === 'string' && x) : [];
  if (typeof previous !== 'string' || !previous || have.includes(previous)) return have;
  return [...have, previous].slice(-OWN_IDS_MAX);
}

/** What is listed now: nothing over RECEIVED_DAYS old */
export async function receivedList(local) {
  try {
    const { [RECEIVED_KEY]: list = [] } = await local.get(RECEIVED_KEY);
    return Array.isArray(list) ? list.filter((e) => fresh(e, Date.now())) : [];
  } catch {
    return [];
  }
}

/**
 * What is over RECEIVED_DAYS old (and anything that is not an entry) is taken out of storage, not only left out of the
 * list, as for what this browser sent. Written back only when something was cut. Never throws. → the entries that are left
 */
export async function pruneReceived(local) {
  try {
    const { [RECEIVED_KEY]: list } = await local.get(RECEIVED_KEY);
    if (list === undefined) return [];
    const now = Date.now();
    const keep = Array.isArray(list) ? list.filter((e) => fresh(e, now)).slice(0, RECEIVED_MAX) : [];
    if (!Array.isArray(list) || keep.length !== list.length) {
      await (keep.length ? local.set({ [RECEIVED_KEY]: keep }) : local.remove(RECEIVED_KEY));
    }
    return keep;
  } catch {
    return [];
  }
}
