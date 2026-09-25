/**
 * The only layer between the extension and the dropit API.
 *
 * No host_permissions: plain CORS requests are enough. Fewer permissions means
 * users install it more readily, and store review is faster.
 */
const DEFAULT_ENDPOINTS = ['https://dropit.realeye.top'];

// Error messages shown to the user, keyed by the API error code
const MESSAGES = {
  INVALID_TOKEN: 'token 无效，请重新配对',
  DEVICE_REVOKED: '设备已被移除，请重新配对',
  SCOPE_INSUFFICIENT: '这个 token 只能投递',
  DEVICE_LIMIT_REACHED: '设备数已达上限，先移除一台',
  PAIRING_CODE_INVALID: '配对码无效',
  PAIRING_CODE_EXPIRED: '配对码已过期，请重新生成',
  RATE_LIMITED: '投递过于频繁',
  QUOTA_EXCEEDED: '队列已满，等旧内容过期',
};

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
        const err = new Error(MESSAGES[data.error] ?? `请求失败 HTTP ${res.status}`);
        Object.assign(err, { code: data.error, data, handled: true });
        throw err;
      }
      return data;
    } catch (err) {
      if (err.handled) throw err;          // the server answered; not an endpoint problem
      lastErr = err;
    }
  }
  throw new Error('网络不可用');
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
