/**
 * The only layer between the extension and the dropit API.
 *
 * No host_permissions: plain CORS requests are enough. Fewer permissions means
 * users install it more readily, and store review is faster.
 */
import { t } from './i18n.js';
import { errorText, endpointList } from './payload.js';

const DEFAULT_ENDPOINTS = ['https://dropit.smart-kits.xyz'];
// What is kept, for how long and who sees it — linked from the join screen
export const PRIVACY_URL = `${DEFAULT_ENDPOINTS[0]}/privacy`;
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
  await chrome.storage.local.remove(['token', 'device_id', 'me', 'scope']);
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
      }, retriable(method, path));
      const data = await res.json().catch(() => ({}));
      if (base !== endpoints[0]) await store.set({ endpoints: [base, ...endpoints.filter((e) => e !== base)] });
      if (!res.ok) {
        const err = new Error(errorText(data.error, data, res.status, t));
        Object.assign(err, { code: data.error, data, status: res.status, handled: true });
        if (auth && authLost(err)) await forgetToken(cfg.token);
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

async function fetchRetrying(url, init, retry) {
  for (let attempt = 0; ; attempt++) {
    try {
      return await fetch(url, init);
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

/** Text or a link. `meta` must already fit (payload.js fitMeta). */
export function sendText({ kind, raw, meta }) {
  return settle(api('POST', '/v1/ingest', { kind, raw, source: SOURCE, client_ts: Date.now(), ...(meta ? { meta } : {}) }));
}

/** One file in one request. The service names it by time and type when there is no `name`. */
export function sendFile(blob, { name, mime, from, group } = {}) {
  const q = new URLSearchParams({ source: SOURCE });
  if (name) q.set('name', name);
  if (from?.url) {
    q.set('from_url', from.url);
    if (from.title) q.set('from_title', from.title);
  }
  if (group) for (const k of ['id', 'i', 'n']) q.set(k === 'id' ? 'group' : k, String(group[k]));
  return settle(api('POST', `/v1/ingest/file?${q}`, blob, { type: mime }));
}

/** The account line: cached, refreshed when older than an hour (or when asked) */
export async function me({ fresh = false } = {}) {
  const { me: cached, token } = await store.get();
  if (!token) return null;
  if (!fresh && cached && Date.now() - cached.at < ME_FRESH_MS) return cached.data;
  const data = await api('GET', '/v1/me');
  await store.set({ me: { data, at: Date.now() } });
  return data;
}
