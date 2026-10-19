/**
 * Preparing an account on a busy network: the service sometimes hands out a small puzzle (a number to find
 * whose hash starts with enough zero bits) and creates the account once it is solved.
 *
 * Pure code, no browser APIs — so it can be tested in node (test/extension.test.mjs). The hash is plain JS:
 * `crypto.subtle.digest` costs a promise per try, which is far too slow for hundreds of thousands of tries.
 */

export const BITS_MAX = 26;          // a harder puzzle than this is refused, not attempted
export const SOLVE_MS = 60_000;      // give up after this long
const CHUNK_MS = 15;                 // work this long, then let the popup breathe

const K = new Uint32Array([
  0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
  0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
  0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
  0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
  0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
  0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
  0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
  0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
]);
const INIT = [0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19];
const W = new Uint32Array(64);

/** One 64-byte block of `buf` (at `off`) folded into `state` */
function block(state, buf, off) {
  for (let i = 0; i < 16; i++, off += 4) W[i] = (buf[off] << 24) | (buf[off + 1] << 16) | (buf[off + 2] << 8) | buf[off + 3];
  for (let i = 16; i < 64; i++) {
    const a = W[i - 15], b = W[i - 2];
    const s0 = ((a >>> 7) | (a << 25)) ^ ((a >>> 18) | (a << 14)) ^ (a >>> 3);
    const s1 = ((b >>> 17) | (b << 15)) ^ ((b >>> 19) | (b << 13)) ^ (b >>> 10);
    W[i] = (W[i - 16] + s0 + W[i - 7] + s1) | 0;
  }
  let [a, b, c, d, e, f, g, h] = state;
  for (let i = 0; i < 64; i++) {
    const t1 = (h + (((e >>> 6) | (e << 26)) ^ ((e >>> 11) | (e << 21)) ^ ((e >>> 25) | (e << 7))) + ((e & f) ^ (~e & g)) + K[i] + W[i]) | 0;
    const t2 = ((((a >>> 2) | (a << 30)) ^ ((a >>> 13) | (a << 19)) ^ ((a >>> 22) | (a << 10))) + ((a & b) ^ (a & c) ^ (b & c))) | 0;
    h = g; g = f; f = e; e = (d + t1) | 0; d = c; c = b; b = a; a = (t1 + t2) | 0;
  }
  state[0] = (state[0] + a) | 0; state[1] = (state[1] + b) | 0; state[2] = (state[2] + c) | 0; state[3] = (state[3] + d) | 0;
  state[4] = (state[4] + e) | 0; state[5] = (state[5] + f) | 0; state[6] = (state[6] + g) | 0; state[7] = (state[7] + h) | 0;
}

/** SHA-256 of a byte array, as 32 bytes */
export function sha256(bytes) {
  return new Hasher(bytes).digest(new Uint8Array(0));
}

/**
 * Hashes `prefix + tail` for many different tails: the whole 64-byte blocks of the prefix are folded in once.
 */
class Hasher {
  constructor(prefix) {
    this.state = Int32Array.from(INIT);
    const whole = prefix.length - (prefix.length % 64);
    for (let o = 0; o < whole; o += 64) block(this.state, prefix, o);
    this.rest = prefix.subarray(whole);
    this.total = prefix.length;
    this.buf = new Uint8Array(192);       // the rest of the prefix + a tail + padding fits in three blocks
    this.work = new Int32Array(8);
    this.out = new Uint8Array(32);
  }

  /** Writes the digest into a reused buffer — copy it if it must outlive the next call */
  digest(tail) {
    const { buf, rest, work, out } = this;
    buf.set(rest, 0);
    buf.set(tail, rest.length);
    const len = rest.length + tail.length;
    const end = len + 9 <= 64 ? 64 : len + 9 <= 128 ? 128 : 192;
    buf.fill(0, len, end);
    buf[len] = 0x80;
    const bits = (this.total + tail.length) * 8;
    buf[end - 4] = (bits >>> 24) & 255; buf[end - 3] = (bits >>> 16) & 255; buf[end - 2] = (bits >>> 8) & 255; buf[end - 1] = bits & 255;
    work.set(this.state);
    for (let o = 0; o < end; o += 64) block(work, buf, o);
    for (let i = 0; i < 8; i++) { out[i * 4] = work[i] >>> 24; out[i * 4 + 1] = (work[i] >>> 16) & 255; out[i * 4 + 2] = (work[i] >>> 8) & 255; out[i * 4 + 3] = work[i] & 255; }
    return out;
  }
}

const utf8 = (s) => new TextEncoder().encode(s);
const hex = (bytes) => Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');

/** Leading zero bits of a digest */
export function zeroBits(digest) {
  let n = 0;
  for (const b of digest) {
    if (b === 0) { n += 8; continue; }
    return n + Math.clz32(b) - 24;
  }
  return n;
}

/** What the puzzle hashes besides the number: the key this request sends, hashed, or "-" without one */
export const keyHash = (deviceKey) => (deviceKey ? hex(sha256(utf8(deviceKey))) : '-');

/** The hash of one try, as the service computes it */
export function tryNonce(challenge, deviceKey, nonce) {
  return sha256(utf8(`${challenge.id}|${challenge.salt}|${keyHash(deviceKey)}|${nonce}`));
}

/** The puzzle in a refusal's body, or null when there is none this client understands (or one too hard to try) */
export function challengeOf(data) {
  const c = data?.challenge;
  if (!c || typeof c !== 'object' || c.v !== 1 || c.purpose !== 'accounts.create') return null;
  if (!Number.isInteger(c.bits) || c.bits < 0 || c.bits > BITS_MAX) return null;
  if (typeof c.id !== 'string' || typeof c.salt !== 'string') return null;
  return c;
}

const breathe = () => new Promise((r) => setTimeout(r, 0));

/**
 * Finds the number. Works in short chunks and lets the event loop run between them. Resolves to the number as a
 * string, or null when `limitMs` ran out. `clock` and `pause` can be replaced (tests).
 */
export async function solve(challenge, deviceKey, { limitMs = SOLVE_MS, clock = Date.now, pause = breathe } = {}) {
  const prefix = utf8(`${challenge.id}|${challenge.salt}|${keyHash(deviceKey)}|`);
  const hasher = new Hasher(prefix);
  const tail = new Uint8Array(16);
  const need = challenge.bits;
  const start = clock();
  for (let n = 0; ; ) {
    const chunkEnd = clock() + CHUNK_MS;
    do {
      for (let k = 0; k < 256; k++, n++) {
        // the decimal digits of n, written by hand: no string per try
        let len = 0, v = n;
        do { tail[15 - len++] = 48 + (v % 10); v = (v - (v % 10)) / 10; } while (v > 0);
        const d = hasher.digest(tail.subarray(16 - len));
        // fast test on the first bytes, the exact count only when it might pass
        if (need <= 8 ? (d[0] >>> (8 - need)) === 0 : d[0] === 0 && zeroBits(d) >= need) return String(n);
      }
    } while (clock() < chunkEnd);
    if (clock() - start > limitMs) return null;
    await pause();
  }
}

/**
 * Sends the request; on a refusal that carries a puzzle this client can do, solves it and sends the same
 * request once more with the answer. Anything else — no puzzle, an unknown version, too hard, out of time,
 * a second refusal — is the first (or second) error as it was, so the usual message shows.
 * `send(body)` rejects with an error whose `code` and `data` are the service's answer.
 */
export async function withPow(send, body, { onSolving, ...opts } = {}) {
  try {
    return await send(body);
  } catch (err) {
    const challenge = err?.code === 'TURNSTILE_REQUIRED' ? challengeOf(err.data) : null;
    if (!challenge) throw err;
    onSolving?.();
    const nonce = await solve(challenge, body.device_key, opts);
    if (nonce === null) throw err;
    const { v, id, iat, exp, purpose, bits, salt, used, sig } = challenge;
    return await send({ ...body, pow: { v, id, iat, exp, purpose, bits, salt, used, sig, nonce } });
  }
}
