/**
 * The only layer between the extension and the dropit API.
 *
 * No host_permissions: plain CORS requests are enough. Fewer permissions means
 * users install it more readily, and store review is faster.
 */
import { t } from './i18n.js';

const DEFAULT_ENDPOINTS = ['https://dropit.realeye.top'];

// Each browser is its own device, so storage.local — never sync
export const store = {
  get: () => chrome.storage.local.get(null),
  set: (patch) => chrome.storage.local.set(patch),
};

/** Fall back to the next endpoint and remember the one that works */
export async function api(method, path, body, auth = true) {
  const cfg = await store.get();
  const endpoints = cfg.endpoints?.length ? cfg.endpoints : DEFAULT_ENDPOINTS;
  let lastErr;
  for (const base of endpoints) {
    try {
      const res = await fetch(base.replace(/\/$/, '') + path, {
        method,
        headers: {
          ...(auth && cfg.token ? { authorization: `Bearer ${cfg.token}` } : {}),
          ...(body ? { 'content-type': 'application/json' } : {}),
        },
        body: body ? JSON.stringify(body) : undefined,
      });
      const data = await res.json().catch(() => ({}));
      if (base !== endpoints[0]) await store.set({ endpoints: [base, ...endpoints.filter((e) => e !== base)] });
      if (!res.ok) {
        const err = new Error(t.errors[data.error] ?? t.httpFailed(res.status));
        Object.assign(err, { code: data.error, data, handled: true });
        throw err;
      }
      return data;
    } catch (err) {
      if (err.handled) throw err;          // the server answered; not an endpoint problem
      lastErr = err;
    }
  }
  throw new Error(t.offline);
}

/** 200 and 409 (already sent today) both count as success */
export async function send({ kind, raw, source = 'chrome-extension' }) {
  try {
    return await api('POST', '/v1/ingest', { kind, raw, source, client_ts: Date.now() });
  } catch (err) {
    if (err.code === 'DEDUPED') return { ...err.data, deduped: true };
    throw err;
  }
}
