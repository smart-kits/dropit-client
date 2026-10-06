/**
 * A fingerprint of this browser on this computer. Joining again — after reinstalling the extension, say —
 * then takes the place of the earlier join instead of using up another device slot.
 *
 * Worked out here; only a SHA-256 hash is sent, never the facts themselves. It is not a credential: the key
 * (`dk_…`) stays random. Leaves out the browser version (it updates every few weeks) and the screen
 * (plugging in a display changes it). Anything the browser doesn't offer is simply left out.
 */
export async function deviceKey(kind, facts = browserFacts()) {
  try {
    const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(JSON.stringify([kind, ...facts])));
    return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, '0')).join('');
  } catch {
    return undefined;              // no fingerprint: joining works exactly as before
  }
}

/** Browser brand (no version), platform, cores, memory, time zone and graphics chip */
export function browserFacts(nav = globalThis.navigator) {
  const brand = (nav?.userAgentData?.brands || []).map((b) => b.brand).filter((b) => !/not|chromium/i.test(b)).sort().join('+');
  let gpu = '';
  try {
    const gl = new OffscreenCanvas(1, 1).getContext('webgl');
    const info = gl && gl.getExtension('WEBGL_debug_renderer_info');
    gpu = info ? String(gl.getParameter(info.UNMASKED_RENDERER_WEBGL)) : '';
  } catch { /* no WebGL here */ }
  let zone = '';
  try { zone = Intl.DateTimeFormat().resolvedOptions().timeZone; } catch { /* left out */ }
  return [brand, nav?.platform ?? '', nav?.hardwareConcurrency ?? '', nav?.deviceMemory ?? '', zone, gpu];
}
