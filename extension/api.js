/**
 * The only layer between the extension and the dropit API.
 *
 * No host_permissions: plain CORS requests are enough. Fewer permissions means
 * users install it more readily, and store review is faster.
 */
import { t } from './i18n.js';
import { errorText, endpointList, squash, clip, fileSizeWall, paymentsOff } from './payload.js';
import { RECEIVED_KEY, CURSOR_KEY, RECV_AT_KEY } from './receive.js';
import { remember } from './recent.js';
import { DEFAULT_KEY, usable, sameSet } from './targets.js';

const DEFAULT_ENDPOINTS = ['https://dropit.smart-kits.xyz'];
// What is kept, for how long and who sees it — linked from the join screen
export const PRIVACY_URL = `${DEFAULT_ENDPOINTS[0]}/privacy`;
/** The page that lists every client, the one for this device recommended: where a new device gets dropit */
export const INSTALL_URL = `${DEFAULT_ENDPOINTS[0]}/#install`;
const SOURCE = 'chrome-extension';
const ME_FRESH_MS = 3_600_000;           // the account line can be an hour old
// The service says this token no longer works: forget it so the extension asks to join again
const AUTH_LOST = ['INVALID_TOKEN', 'DEVICE_REVOKED'];

// Each browser is its own device, so storage.local — never sync
export const store = {
  get: () => chrome.storage.local.get(null),
  set: (patch) => chrome.storage.local.set(patch),
};

export const authLost = (err) => AUTH_LOST.includes(err?.code);

/**
 * Stop using this browser's key. It is kept as `previous_token` and sent with the next join, so the service
 * knows exactly which device this was and lets the new join take its place instead of using another slot.
 */
export async function forgetToken(token) {
  await chrome.storage.local.remove(['token', 'device_id', 'me', 'scope', 'recent', RECEIVED_KEY, CURSOR_KEY, RECV_AT_KEY, DEFAULT_KEY]);
  await chrome.storage.session?.remove('devices_cache').catch(() => {});
  if (token) await chrome.storage.local.set({ previous_token: token });
}

/** The address requests go to now: the one that last worked, else the built-in one */
export async function serviceBase() {
  const { endpoints } = await store.get();
  return endpointList(endpoints, DEFAULT_ENDPOINTS)[0].replace(/\/+$/, '');
}

/**
 * Fall back to the next endpoint and remember the one that works.
 * `body` is sent as JSON, or as-is when it is a Blob (with `type` as its content type).
 */
export async function api(method, path, body, { auth = true, type } = {}) {
  const cfg = await store.get();
  const endpoints = endpointList(cfg.endpoints, DEFAULT_ENDPOINTS);
  const blob = body instanceof Blob;
  let lastErr;
  for (const base of endpoints) {
    try {
      const res = await fetchRetrying(base.replace(/\/$/, '') + path, {
        method,
        headers: {
          ...(auth && cfg.token ? { authorization: `Bearer ${cfg.token}` } : {}),
          ...(body ? { 'content-type': blob ? (type || body.type || 'application/octet-stream') : 'application/json' } : {}),
        },
        body: body ? (blob ? body : JSON.stringify(body)) : undefined,
      }, retriable(method, path), !blob);
      const data = await res.json().catch(() => ({}));
      if (base !== endpoints[0]) await store.set({ endpoints: [base, ...endpoints.filter((e) => e !== base)] });
      if (!res.ok) {
        const err = new Error(errorText(data.error, data, res.status, t));
        Object.assign(err, { code: data.error, data, status: res.status, handled: true });
        // Only when the key that failed is still the one saved: a join that finished meanwhile must not be wiped
        if (auth && authLost(err) && (await store.get()).token === cfg.token) await forgetToken(cfg.token);
        throw err;
      }
      return data;
    } catch (err) {
      if (err.handled) throw err;          // the server answered; not an endpoint problem
      lastErr = err;
    }
  }
  throw Object.assign(new Error(t.offline), { code: 'OFFLINE', cause: lastErr });
}

// A connection dropped on the way (a flaky proxy) is tried again on the same address after these waits
const RETRY_MS = [400, 1200];
/** Safe to send twice: reads, and sends (the service recognizes a repeat within a minute). Never joining or creating an account. */
const retriable = (method, path) => method === 'GET' || path.startsWith('/v1/ingest');

// A request that carries no file gives up after this long and counts as a network error (retried or tried on the next address)
const JSON_TIMEOUT_MS = 30_000;

async function fetchRetrying(url, init, retry, timeout = false) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await fetch(url, timeout ? { ...init, signal: AbortSignal.timeout(JSON_TIMEOUT_MS) } : init);
    } catch (err) {
      if (!retry || attempt >= RETRY_MS.length) throw err;
      await new Promise((r) => setTimeout(r, RETRY_MS[attempt]));
    }
  }
}

/** 200 and 409 (the same thing sent a moment ago) both count as success */
async function settle(promise) {
  try {
    return await promise;
  } catch (err) {
    if (err.code === 'DEDUPED') return { ...err.data, deduped: true };
    throw err;
  }
}

/**
 * Where a send goes. `to`: a list of device ids, null for every device, or left out to use this browser's saved
 * default. A send that finds none of its devices left (they were removed) puts the default back to every device,
 * so the next try goes through; the caller shows the error.
 * @returns the answer, the ids it went to (null = every device) and whether this browser may choose at all
 */
async function toward(to, run) {
  const cfg = await store.get();
  const saved = usable(cfg[DEFAULT_KEY]);
  const ids = to === undefined ? saved : to;
  try {
    const res = await run(ids?.length ? ids : null);
    // What the service says it used (some ids may have been dropped), else what was asked for
    const used = ids?.length ? (Array.isArray(res.to) ? res.to.map((d) => d.device_id) : ids) : null;
    return { res, used, record: cfg.scope === 'full' };
  } catch (err) {
    if (err.code === 'TARGET_UNAVAILABLE' && saved && sameSet(ids, saved)) await chrome.storage.local.remove(DEFAULT_KEY);
    throw err;
  }
}

/** Text or a link. `meta` must already fit (payload.js fitMeta). */
export async function sendText({ kind, raw, meta, to }) {
  const { res, used, record } = await toward(to, (ids) => settle(api('POST', '/v1/ingest',
    { kind, raw, source: SOURCE, client_ts: Date.now(), ...(meta ? { meta } : {}), ...(ids ? { to: ids } : {}) })));
  if (!res.deduped) await remember({ seq: res.seq, kind, text: raw, label: clip(squash(kind === 'url' && meta?.title ? meta.title : raw), 120), ...(record ? { to: used } : {}) });
  return res;
}

/** One file in one request. The service names it by time and type when there is no `name`. */
export async function sendFile(blob, { name, mime, from, group, to } = {}) {
  const { res, used, record } = await toward(to, (ids) => {
    const q = new URLSearchParams({ source: SOURCE });
    if (name) q.set('name', name);
    if (from?.url) {
      q.set('from_url', from.url);
      if (from.title) q.set('from_title', from.title);
    }
    if (group) for (const k of ['id', 'i', 'n']) q.set(k === 'id' ? 'group' : k, String(group[k]));
    if (ids) q.set('to', ids.join(','));
    return settle(api('POST', `/v1/ingest/file?${q}`, blob, { type: mime }));
  });
  if (!res.deduped) {
    const type = mime || blob.type || '';
    await remember({ seq: res.seq, kind: 'file', mime: type, label: name || (type.startsWith('image/') ? t.recentImage : t.recentFile), ...(record ? { to: used } : {}) });
  }
  return res;
}

/**
 * The public plans (limits, and whether paying is on offer). Read only where a limit card is about to offer Paid, never at start-up;
 * kept for a few minutes so one card after another asks once. A failed read is null (the card then shows what it did before).
 */
const PLANS_FRESH_MS = 300_000;
let plansRead = null;
export function readPlans() {
  if (!plansRead || Date.now() - plansRead.at > PLANS_FRESH_MS) {
    const at = Date.now();
    plansRead = { at, p: api('GET', '/v1/plans', undefined, { auth: false }).catch(() => { if (plansRead?.at === at) plansRead = null; return null; }) };
  }
  return plansRead.p;
}
/** Is the way to Paid on offer now? (true when the plans can't be read: the service's own link stays) */
export const upgradeOffered = () => readPlans().then((p) => !paymentsOff(p));

/** The account line: cached, refreshed when older than an hour (or when asked) */
export async function me({ fresh = false } = {}) {
  const { me: cached, token } = await store.get();
  if (!token) return null;
  if (!fresh && cached && Date.now() - cached.at < ME_FRESH_MS) return cached.data;
  const data = await api('GET', '/v1/me');
  await store.set({ me: { data, at: Date.now() } });
  return data;
}

/**
 * The error for a file over this account's one-file limit, found before sending it (`size`: the file's size when known).
 * A free account that the Paid plan's limit would suit gets the way to upgrade as the service's own answer would carry it
 * (`err.data`: the link and `why: 'file_size'`), so every place that shows a limit shows it the same way. Reads the account and
 * the plans only now, on this one path: nothing is asked while sending goes through.
 */
export async function tooLarge(limit, size) {
  if (!limit) return new Error(t.errors.PAYLOAD_TOO_LARGE);
  const account = await me({ fresh: true }).catch(() => null);
  const plans = account?.upgrade_url ? await readPlans() : null;
  const paidMB = paymentsOff(plans) ? null : plans?.limits?.paid?.fileMB;       // payments off: the plain sentence, no way to Paid
  const { message, data } = fileSizeWall(account, size, paidMB, limit, t);
  return Object.assign(new Error(message), data ? { data } : {});
}
