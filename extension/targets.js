/**
 * Choosing which devices a send goes to, the parts that don't need a browser.
 *
 * Only a browser whose key can read the device list (the one that created the account) can choose. Its default
 * lives in this browser's own storage under `default_to`: nothing stored = every device (a send then carries no
 * target, and devices added later are included); a list of device ids = just those. Each send may override it once.
 */

export const DEFAULT_KEY = 'default_to';
const IDS_MAX = 16;                      // what the service accepts in one send
const NAME_ZH = 8, NAME_EN = 12;         // longest device name written in a short target before it is cut
const PAIR_MAX = 14;                     // two names are written out only when they fit in this many characters
const IDLE_MS = 7 * 864e5;               // not seen for this long: "offline for a long time"

/** A stored default as a usable list of ids, or null (= every device) */
export function usable(raw) {
  if (!Array.isArray(raw)) return null;
  const ids = [...new Set(raw.filter((x) => typeof x === 'string' && x))].slice(0, IDS_MAX);
  return ids.length ? ids : null;
}

/** The devices a send can go to: full-access ones other than this browser, most recently active first */
export function candidates(devices, selfId) {
  return (devices ?? []).filter((d) => d.scope === 'full' && d.device_id !== selfId)
    .sort((a, b) => (b.last_seen_at ?? 0) - (a.last_seen_at ?? 0));
}

/**
 * Check a saved default against the devices that exist now.
 * `to`: what to use from now on (null = every device); `trimmed`: some saved ids were gone (they are dropped);
 * `reset`: none was left, so it is back to every device; `changed`: the stored value must be rewritten.
 * A default that names every device is the same as "every device", so it is stored as nothing.
 */
export function reconcile(saved, list) {
  const ids = usable(saved) ?? [];
  const have = new Set(list.map((d) => d.device_id));
  const valid = ids.filter((id) => have.has(id));
  const reset = ids.length > 0 && valid.length === 0;
  const trimmed = valid.length > 0 && valid.length < ids.length;
  const to = !valid.length || valid.length === have.size ? null : valid;
  return { to, trimmed, reset, changed: saved !== undefined && JSON.stringify(saved) !== JSON.stringify(to) };
}

/** A set of ticked ids as what is stored and sent: null when it covers every device */
export function toValue(selected, list) {
  const ids = list.map((d) => d.device_id).filter((id) => selected.has(id));
  return !ids.length || ids.length === list.length ? null : ids;
}

/** Same ids, any order */
export function sameSet(a, b) {
  if (!a || !b) return a === b;
  return a.length === b.length && a.every((x) => b.includes(x));
}

const chars = (s) => Array.from(s);
const clipName = (name) => {
  const max = /[㐀-鿿]/.test(name) ? NAME_ZH : NAME_EN, c = chars(name);
  return c.length > max ? `${c.slice(0, max).join('')}…` : name;
};

/**
 * Who it went to, short (the arrow is added by the caller): "All" · one name · two names when they fit · "3 devices".
 * Never a long list of names. `names`: Map of id → name; an id it doesn't know counts as a device without a name.
 */
export function shortTarget(to, names, t) {
  if (!to) return t.targetAll;
  const known = to.map((id) => names.get(id)).filter(Boolean);
  if (to.length === 1 && known.length === 1) return clipName(known[0]);
  if (to.length === 2 && known.length === 2) {
    const both = known.join(t.targetSep);
    if (chars(both).length <= PAIR_MAX) return both;
  }
  return t.targetN(to.length);
}

/** Which small drawing a device gets, from its name (the list says nothing else about what it is) */
export function deviceIcon(name) {
  if (/^cli\b|终端|terminal/i.test(name)) return 'terminal';
  if (/android|iphone|ipad|phone|pixel|手机|快捷指令|安卓/i.test(name)) return 'phone';
  return 'laptop';
}

/** When a device was last active, or that it has been away a long time */
export function activity(device, now, t, locale, ago) {
  if (!device.last_seen_at) return t.neverSeen;
  if (now - device.last_seen_at > IDLE_MS) return t.longIdle;
  const days = Math.floor((now - device.last_seen_at) / 864e5);
  return t.lastSeen(days >= 1 ? t.daysAgo(days) : ago(device.last_seen_at, now, t, locale));
}
