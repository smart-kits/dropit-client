// ── QR code ─────────────────────────────────────────────────────────────
// Copied unchanged from the dropit Obsidian plugin (https://github.com/smart-kits/dropit-obsidian, MIT),
// which keeps it identical to the web inbox's encoder.
// A tiny encoder for the one case pairing needs: versions 1–5, error correction L, byte mode.
// That range is all single-block (no interleaving) with at most one alignment pattern; 106 bytes is
// plenty for a pairing link. Checked bit for bit against the npm `qrcode` package and decoded with
// `jsQR`; test/extension.test.mjs pins the result.

export const QR = (() => {
  const VERSIONS = { 1: [19, 7], 2: [34, 10], 3: [55, 15], 4: [80, 20], 5: [108, 26] };  // [data, ecc] codewords
  const ALIGN_AT = { 2: 18, 3: 22, 4: 26, 5: 30 };
  const ECC_L = 0b01;
  const PAD = [0xEC, 0x11];

  const EXP = new Uint8Array(512);
  const LOG = new Uint8Array(256);
  for (let i = 0, x = 1; i < 255; i++) {
    EXP[i] = x;
    LOG[x] = i;
    x = (x << 1) ^ (x & 0x80 ? 0x11d : 0);
  }
  for (let i = 255; i < 512; i++) EXP[i] = EXP[i - 255];
  const mul = (a, b) => (a && b ? EXP[LOG[a] + LOG[b]] : 0);

  function generator(n) {
    let poly = [1];
    for (let i = 0; i < n; i++) {
      const next = new Array(poly.length + 1).fill(0);
      // multiply by (x + α^i): coefficients are highest power first, so "times x" stays in place
      poly.forEach((c, j) => {
        next[j] ^= c;
        next[j + 1] ^= mul(c, EXP[i]);
      });
      poly = next;
    }
    return poly;
  }

  function ecc(data, n) {
    const gen = generator(n);
    const out = new Array(n).fill(0);
    for (const byte of data) {
      const factor = byte ^ out[0];
      out.shift();
      out.push(0);
      if (factor) gen.slice(1).forEach((g, i) => { out[i] ^= mul(g, factor); });
    }
    return out;
  }

  function encode(text) {
    const bytes = new TextEncoder().encode(text);
    const version = Object.keys(VERSIONS).map(Number).find((v) => bytes.length + 2 <= VERSIONS[v][0]);
    if (!version) throw new Error(`too long for a QR code here (${bytes.length} bytes, 106 at most)`);
    const [dataLen, eccLen] = VERSIONS[version];
    const bits = [];
    const push = (value, n) => { for (let i = n - 1; i >= 0; i--) bits.push((value >> i) & 1); };
    push(0b0100, 4);                                   // byte mode
    push(bytes.length, 8);                             // 8-bit count for versions 1–9
    bytes.forEach((b) => push(b, 8));
    push(0, Math.min(4, dataLen * 8 - bits.length));   // terminator
    while (bits.length % 8) bits.push(0);
    const codewords = [];
    for (let i = 0; i < bits.length; i += 8) codewords.push(bits.slice(i, i + 8).reduce((n, b) => (n << 1) | b, 0));
    for (let i = 0; codewords.length < dataLen; i++) codewords.push(PAD[i % 2]);   // padding always starts at 0xEC
    return { version, codewords: codewords.concat(ecc(codewords, eccLen)) };
  }

  const FINDER = [
    [1, 1, 1, 1, 1, 1, 1], [1, 0, 0, 0, 0, 0, 1], [1, 0, 1, 1, 1, 0, 1], [1, 0, 1, 1, 1, 0, 1],
    [1, 0, 1, 1, 1, 0, 1], [1, 0, 0, 0, 0, 0, 1], [1, 1, 1, 1, 1, 1, 1],
  ];

  function skeleton(version) {
    const size = 17 + version * 4;
    const m = Array.from({ length: size }, () => new Array(size).fill(null));
    const put = (r, c, v) => { if (r >= 0 && c >= 0 && r < size && c < size) m[r][c] = v; };
    for (const [br, bc] of [[0, 0], [0, size - 7], [size - 7, 0]]) {
      FINDER.forEach((row, r) => row.forEach((v, c) => put(br + r, bc + c, v)));
      for (let i = -1; i <= 7; i++) { put(br + i, bc - 1, 0); put(br + i, bc + 7, 0); }
      for (let i = -1; i <= 7; i++) { put(br - 1, bc + i, 0); put(br + 7, bc + i, 0); }
    }
    for (let i = 8; i < size - 8; i++) {               // timing patterns
      const v = i % 2 === 0 ? 1 : 0;
      m[6][i] = v;
      m[i][6] = v;
    }
    m[size - 8][8] = 1;                                // the fixed dark module
    const a = ALIGN_AT[version];
    if (a) {
      for (let r = -2; r <= 2; r++) {
        for (let c = -2; c <= 2; c++) m[a + r][a + c] = Math.max(Math.abs(r), Math.abs(c)) !== 1 ? 1 : 0;
      }
    }
    return m;
  }

  function formatCells(size) {
    const cells = [];
    for (let i = 0; i <= 5; i++) cells.push([8, i], [i, 8]);
    cells.push([8, 7], [8, 8], [7, 8]);
    for (let i = 0; i < 8; i++) cells.push([8, size - 1 - i]);
    for (let i = 0; i < 7; i++) cells.push([size - 1 - i, 8]);
    return cells;
  }

  function placeData(m, codewords) {
    const size = m.length;
    const bits = [];
    codewords.forEach((b) => { for (let i = 7; i >= 0; i--) bits.push((b >> i) & 1); });
    let idx = 0;
    let upward = true;
    for (let right = size - 1; right > 0; right -= 2) {
      if (right === 6) right = 5;                      // skip the vertical timing column
      for (let step = 0; step < size; step++) {
        const row = upward ? size - 1 - step : step;
        for (const col of [right, right - 1]) {
          if (m[row][col] === null) m[row][col] = bits[idx++] ?? 0;
        }
      }
      upward = !upward;
    }
  }

  const MASKS = [
    (r, c) => (r + c) % 2 === 0,
    (r) => r % 2 === 0,
    (_, c) => c % 3 === 0,
    (r, c) => (r + c) % 3 === 0,
    (r, c) => (Math.floor(r / 2) + Math.floor(c / 3)) % 2 === 0,
    (r, c) => ((r * c) % 2) + ((r * c) % 3) === 0,
    (r, c) => (((r * c) % 2) + ((r * c) % 3)) % 2 === 0,
    (r, c) => (((r + c) % 2) + ((r * c) % 3)) % 2 === 0,
  ];

  function formatBits(mask) {                          // BCH(15,5), then XOR 0x5412
    const data = (ECC_L << 3) | mask;
    let rem = data << 10;
    for (let i = 4; i >= 0; i--) if ((rem >> (i + 10)) & 1) rem ^= 0x537 << i;
    return ((data << 10) | rem) ^ 0x5412;
  }

  function applyFormat(m, mask) {
    const size = m.length;
    const bits = formatBits(mask);
    const at = (i) => (bits >> i) & 1;
    for (let i = 0; i <= 5; i++) m[i][8] = at(i);
    m[7][8] = at(6);
    m[8][8] = at(7);
    m[8][7] = at(8);
    for (let i = 9; i <= 14; i++) m[8][14 - i] = at(i);
    for (let i = 0; i <= 7; i++) m[8][size - 1 - i] = at(i);
    for (let i = 8; i <= 14; i++) m[size - 15 + i][8] = at(i);
  }

  function penalty(m) {
    const size = m.length;
    let score = 0;
    const runs = (get) => {
      for (let a = 0; a < size; a++) {
        let run = 1;
        for (let b = 1; b < size; b++) {
          if (get(a, b) === get(a, b - 1)) run++;
          else { if (run >= 5) score += run - 2; run = 1; }
        }
        if (run >= 5) score += run - 2;
      }
    };
    runs((r, c) => m[r][c]);
    runs((c, r) => m[r][c]);
    for (let r = 0; r < size - 1; r++) {
      for (let c = 0; c < size - 1; c++) {
        const v = m[r][c];
        if (v === m[r][c + 1] && v === m[r + 1][c] && v === m[r + 1][c + 1]) score += 3;
      }
    }
    const BAD = [[1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0], [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1]];
    const hit = (pat, get, a, b) => pat.every((v, i) => get(a, b + i) === v);
    for (const pat of BAD) {
      for (let a = 0; a < size; a++) {
        for (let b = 0; b + 10 < size; b++) {
          if (hit(pat, (x, y) => m[x][y], a, b)) score += 40;
          if (hit(pat, (x, y) => m[y][x], a, b)) score += 40;
        }
      }
    }
    const dark = m.flat().filter((v) => v).length;
    score += Math.floor(Math.abs((dark * 100) / (size * size) - 50) / 5) * 10;
    return score;
  }

  /** @returns {number[][]} 0/1, 1 = dark */
  function matrix(text, opts = {}) {
    const { version, codewords } = encode(text);
    const base = skeleton(version);
    formatCells(base.length).forEach(([r, c]) => { base[r][c] = base[r][c] ?? 0; });
    const reserved = base.map((row) => row.map((v) => v !== null));
    placeData(base, codewords);
    let best = null;
    const masks = opts.mask == null ? [0, 1, 2, 3, 4, 5, 6, 7] : [opts.mask];
    for (const mask of masks) {
      const candidate = base.map((row, r) => row.map((v, c) => (!reserved[r][c] && MASKS[mask](r, c) ? v ^ 1 : v)));
      applyFormat(candidate, mask);
      const score = penalty(candidate);
      if (!best || score < best.score) best = { score, matrix: candidate };
    }
    return best.matrix;
  }

  /** Draw into `parent` as SVG elements (no HTML strings). The 4-module quiet zone is required to scan. */
  function draw(parent, text, { scale = 5, quiet = 4 } = {}) {
    const m = matrix(text);
    const size = m.length + quiet * 2;
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    for (const [k, v] of Object.entries({ width: size * scale, height: size * scale, viewBox: `0 0 ${size} ${size}`, 'shape-rendering': 'crispEdges' })) svg.setAttribute(k, String(v));
    const bg = document.createElementNS(ns, 'rect');
    bg.setAttribute('width', String(size));
    bg.setAttribute('height', String(size));
    bg.setAttribute('fill', '#fff');
    const path = document.createElementNS(ns, 'path');
    path.setAttribute('d', m.flatMap((row, r) => row.map((v, c) => (v ? `M${c + quiet} ${r + quiet}h1v1h-1z` : ''))).join(''));
    path.setAttribute('fill', '#000');
    svg.append(bg, path);
    parent.appendChild(svg);
    return svg;
  }

  return { matrix, draw };
})();
