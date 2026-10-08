/**
 * Adding a device, the decisions that don't need a browser: what this browser's key may do, which device
 * a new one takes the place of when every slot is in use, what the QR code holds, and when to stop watching.
 */

export const POLL_EVERY = 4;           // check the device list every 4th second while a code is showing

/**
 * What this browser's key may do, from asking for the device list once (keys from before this was recorded):
 * it answered → 'full'; refused for scope → 'ingest_only'; anything else (offline…) → null, ask again next time.
 */
export function scopeFromProbe(err) {
  if (!err) return 'full';
  return err.code === 'SCOPE_INSUFFICIENT' ? 'ingest_only' : null;
}

/** Only a key that may add devices shows the way to add one */
export const canAddDevices = (scope) => scope === 'full';

/**
 * Every slot in use: the new device takes the place of another one, picked before the code is made so that
 * joining stays one step on the other device. The pick defaults to the one idle longest (a slot left behind by
 * an old install is idle by definition) and can be changed. This browser is never offered.
 * @returns {{ others: object[], pick: object|null }} pick is null when there is room
 */
export function slotPlan(me, devices, selfId, chosen) {
  const others = (devices ?? []).filter((d) => d.device_id !== selfId)
    .sort((a, b) => (a.last_seen_at ?? 0) - (b.last_seen_at ?? 0));
  const full = me?.devices_limit != null && me.devices_used >= me.devices_limit && others.length > 0;
  return { others, pick: full ? (others.find((d) => d.device_id === chosen) ?? others[0]) : null };
}

/** What the QR code holds: the service's own page with the code filled in, as the web inbox and Obsidian show it */
export const pairLink = (base, code) => `${String(base).replace(/\/+$/, '')}/?pair=${encodeURIComponent(code)}`;

/**
 * A device that wasn't there when the code was made. Watching for a new id, not a higher count: a device that
 * takes another's place leaves the count where it was.
 * @returns {{ joined: object, gone: object|null } | null}
 */
export function newcomer(before, now) {
  const known = new Set(before.map((d) => d.device_id));
  const joined = now.find((d) => !known.has(d.device_id));
  if (!joined) return null;
  return { joined, gone: before.find((d) => !now.some((n) => n.device_id === d.device_id)) ?? null };
}

/**
 * One tick of the countdown (once a second): how long is left, whether the code expired (stop everything),
 * and whether this tick checks the device list.
 */
export function pairTick(now, expiresAt, tick) {
  const left = Math.max(0, expiresAt - now);
  const expired = left === 0;
  return { left, expired, poll: !expired && tick > 0 && tick % POLL_EVERY === 0 };
}

/** 4:05 */
export function clock(ms) {
  const s = Math.ceil(ms / 1000);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

/** When a device was last active, in words: just now · 5 min ago · 3 h ago · a date */
export function ago(ms, now, t, locale) {
  const mins = Math.floor((now - ms) / 60000);
  if (mins < 1) return t.justNow;
  if (mins < 60) return t.minsAgo(mins);
  if (mins < 1440) return t.hoursAgo(Math.floor(mins / 60));
  return new Date(ms).toLocaleDateString(locale);
}
