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

export async function recentList() {
  try {
    const { [KEY]: list = [] } = await chrome.storage.local.get(KEY);
    return Array.isArray(list) ? list.filter((e) => Date.now() - e.at < RECENT_DAYS * 864e5) : [];
  } catch {
    return [];
  }
}

export const RECENT_KEY = KEY;
