/**
 * The few things this browser sent most recently, kept in this browser only (storage.local) so the popup can
 * list them. Only what went through is kept; nothing here is ever sent anywhere.
 */
const KEY = 'recent';
export const RECENT_MAX = 5;
const RECENT_DAYS = 7;
export const COPY_MAX = 2000;     // text longer than this is listed, but not kept whole for copying

/**
 * Add one entry: { seq, kind: 'text' | 'url' | 'file', label, text?, mime?, to? }.
 * `to` is who it went to: null for every device, else the device ids (never names; names are looked up when shown).
 * Never throws: a list that can't be written must not make a send look failed.
 */
export async function remember(entry) {
  try {
    const now = Date.now();
    const { [KEY]: old = [] } = await chrome.storage.local.get(KEY);
    const keep = old.filter((e) => now - e.at < RECENT_DAYS * 864e5 && e.seq !== entry.seq);
    const text = entry.kind === 'file' || (entry.text?.length ?? 0) > COPY_MAX ? undefined : entry.text;
    const to = Array.isArray(entry.to) ? entry.to.filter((id) => typeof id === 'string').slice(0, 16) : entry.to === null ? null : undefined;
    await chrome.storage.local.set({ [KEY]: [{ ...entry, text, to, at: now }, ...keep].slice(0, RECENT_MAX) });
  } catch { /* the list is a convenience */ }
}

const fresh = (e, now) => !!e && now - e.at < RECENT_DAYS * 864e5;

export async function recentList() {
  try {
    const { [KEY]: list = [] } = await chrome.storage.local.get(KEY);
    return Array.isArray(list) ? list.filter((e) => fresh(e, Date.now())) : [];
  } catch {
    return [];
  }
}

/**
 * What is older than a week (and anything that is not an entry) is taken out of storage, not only left out of the list: the
 * privacy note says a week and then it is gone. Written back only when something was cut, so opening the popup writes nothing
 * in the usual case. Never throws. → the entries that are left
 */
export async function pruneRecent() {
  try {
    const { [KEY]: list } = await chrome.storage.local.get(KEY);
    if (list === undefined) return [];
    const now = Date.now();
    const keep = Array.isArray(list) ? list.filter((e) => fresh(e, now)) : [];
    if (!Array.isArray(list) || keep.length !== list.length) {
      await (keep.length ? chrome.storage.local.set({ [KEY]: keep }) : chrome.storage.local.remove(KEY));
    }
    return keep;
  } catch {
    return [];
  }
}

export const RECENT_KEY = KEY;
