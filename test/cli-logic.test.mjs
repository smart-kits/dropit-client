/**
 * The CLI's decisions that don't need the network: which files `send -f` takes, when it refuses before
 * sending anything, what an error answer says, what `dropit me` prints, and when `dropit pair` may
 * create an account.
 *
 *   node test/cli-logic.test.mjs
 */
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
// The CLI keeps its config under the home folder: point that at a scratch folder before loading it,
// so nothing here can touch a real ~/.config/dropit
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
const HOME = mkdtempSync(join(tmpdir(), 'dropit-cli-test-'));
process.env.HOME = HOME;
process.env.USERPROFILE = HOME;
const { T, filesOf, filesProblem, groupsFor, errorText, withUpgrade, safeLink, meLine, pairAction, FILES_MAX, deviceKey, machineFacts } = require('../cli/dropit');
const P = require('../cli/dropit');
import { readFileSync } from 'node:fs';
let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  cond ? pass++ : fail++;
  process.stdout.write(`${cond ? '  ok  ' : 'FAIL  '}${name}${cond ? '' : ' → ' + detail}\n`);   // not console.log: the network tests capture it
};
const is = (name, got, want) => ok(name, JSON.stringify(got) === JSON.stringify(want),
  `\n  got  ${JSON.stringify(got)}\n  want ${JSON.stringify(want)}`);
const { en, zh } = T;

// ── send -f: which files ─────────────────────────────────────────────────
is('no -f → not a file send', filesOf(['hello', 'world']), null);
is('-f takes every argument after it', filesOf(['-f', 'a.png', 'b.png', 'c d.pdf']), ['a.png', 'b.png', 'c d.pdf']);
is('-f alone → empty list', filesOf(['-f']), []);
is('words before -f are not files', filesOf(['note', '-f', 'a.png']), ['a.png']);

// ── send -f: checked before anything is sent ─────────────────────────────
const all = () => 'file';
const names = (n) => Array.from({ length: n }, (_, k) => `f${k + 1}.png`);
is('no files → usage', filesProblem([], all, en), en.noFiles);
is(`${FILES_MAX} files → fine`, filesProblem(names(FILES_MAX), all, en), null);
is(`${FILES_MAX + 1} files → refused, nothing sent`, filesProblem(names(FILES_MAX + 1), all, en),
  en.tooManyFiles(FILES_MAX + 1, FILES_MAX));
ok('the limit is 10', FILES_MAX === 10);
const states = { 'a.png': 'file', 'gone.png': 'missing', 'dir': 'other' };
is('a missing file anywhere in the list → named', filesProblem(['a.png', 'gone.png'], (f) => states[f], en),
  en.fileNotFound('gone.png'));
is('a folder → not a file', filesProblem(['a.png', 'dir'], (f) => states[f], en), en.notAFile('dir'));
is('too many is reported before a missing file', filesProblem([...names(11), 'gone.png'], (f) => states[f] ?? 'file', en),
  en.tooManyFiles(12, FILES_MAX));

// ── batch marks ──────────────────────────────────────────────────────────
is('one file → no batch', groupsFor(1), [null]);
const g = groupsFor(3);
ok('several → same id on each', g.every((x) => x.id === g[0].id));
is('i counts from 1, n is the total', g.map(({ i, n }) => [i, n]), [[1, 3], [2, 3], [3, 3]]);
ok('id is 6–32 of [A-Za-z0-9_-]', /^[A-Za-z0-9_-]{6,32}$/.test(g[0].id), g[0].id);
ok('a new id each time', groupsFor(2)[0].id !== groupsFor(2)[0].id);

// ── error sentences: numbers from the answer ─────────────────────────────
const now = new Date(2026, 9, 4, 22, 15).getTime();          // local time
is('413 size → the limit from the answer', errorText('PAYLOAD_TOO_LARGE', { field: 'size', limit: 5242880 }, 413, en),
  'Larger than 5 MB, the most one item can be');
is('413 size, other limit → that number', errorText('PAYLOAD_TOO_LARGE', { field: 'size', limit: 1572864 }, 413, zh),
  '超过了单条上限 1.5 MB');
is('413 without field → plain sentence', errorText('PAYLOAD_TOO_LARGE', {}, 413, en), en.errors.PAYLOAD_TOO_LARGE);
is('429 hour', errorText('RATE_LIMITED', { window: 'hour', retry_after: 600 }, 429, en), en.rateHour);
is('429 signup limit: how many, and when it opens again', [/5 accounts/.test(errorText('SIGNUP_LIMITED', { retry_after: 1800, limit: 5 }, 429, en, 0)), /5 个账号/.test(errorText('SIGNUP_LIMITED', { retry_after: 1800, limit: 5 }, 429, zh, 0))], [true, true]);
is('429 signup limit without a time: the plain sentence', errorText('SIGNUP_LIMITED', {}, 429, en), en.errors.SIGNUP_LIMITED);
is('429 day → local time it comes back', errorText('RATE_LIMITED', { window: 'day', retry_after: 7200 }, 429, en, now),
  "Today's sends are used up — back at 00:15");
is('429 day, zh', errorText('RATE_LIMITED', { window: 'day', retry_after: 90 }, 429, zh, now),
  '今天的投递次数用完了，22:16 恢复');
is('429 without window → old sentence', errorText('RATE_LIMITED', { retry_after: 30 }, 429, en), en.errors.RATE_LIMITED);
is('429 day, uploads → its own sentence', errorText('RATE_LIMITED', { window: 'day', field: 'uploads', retry_after: 7200 }, 429, en, now), "Today's file uploads are used up — back at 00:15");
is('429 day, downloads, zh → its own sentence', errorText('RATE_LIMITED', { window: 'day', field: 'downloads', retry_after: 90 }, 429, zh, now), '今天的下载次数用完了，22:16 恢复');
is('space full → when room frees up', errorText('QUOTA_EXCEEDED', { field: 'bytes', frees_in: 7200 }, 507, en), 'Storage is full — the oldest item expires in about 2 hours and frees up room');
is('queue full, zh → when room frees up', errorText('QUOTA_EXCEEDED', { field: 'items', frees_in: 90000 }, 507, zh), '积压的内容太多了，最早的一条约 25 小时后到期，到时自动腾出位置');
is('space full, no time given → the plain sentence', errorText('QUOTA_EXCEEDED', { field: 'bytes' }, 507, en), en.quotaBytes);
is('a span under an hour', en.span(600), 'under an hour');
// the way past a limit the paid plan lifts rides on the sentence (the service gives the link to free accounts only)
const link = 'https://dropit.example/upgrade?u=1&why=per_day';
is('upgrade link follows the sentence', withUpgrade('Too fast', { upgrade_url: link, why: 'per_day' }, en), `Too fast — Keep sending with Paid: ${link}`);
is('upgrade link, zh', withUpgrade('投得太快', { upgrade_url: link, why: 'queue' }, zh), `投得太快 — 升级后可以积压更多: ${link}`);
for (const [u, want] of [["https://dropit.example/upgrade?u=1", "https://dropit.example/upgrade?u=1"], ["http://127.0.0.1:8799/upgrade", "http://127.0.0.1:8799/upgrade"], ["http://localhost:8780/x", "http://localhost:8780/x"], ["javascript:alert(1)", null], ["data:text/html,<b>x</b>", null], ["file:///etc/passwd", null], ["obsidian://open?vault=x", null], ["http://evil.example/up", null], ["//evil.example/x", null], ["", null], [null, null]]) is(`a link from the service: ${String(u).slice(0, 40)}`, safeLink(u), want);
is('a link that is not one to open is left out of the sentence', withUpgrade('Too fast', { upgrade_url: 'javascript:alert(1)', why: 'per_day' }, en), 'Too fast');
is('no link from the service → the sentence alone (paid accounts, limits Paid does not lift)', withUpgrade('Too fast', { why: 'per_day' }, en), 'Too fast');
for (const why of ['per_day', 'queue', 'space', 'file_size', 'devices', 'realtime', 'uploads', 'downloads', 'kept']) {
  is(`an upgrade line for why=${why}, both languages`, [en.upgradeFor(why) !== en.upgradeFor('?'), zh.upgradeFor(why) !== zh.upgradeFor('?')], [true, true]);
}
is('507 items', errorText('QUOTA_EXCEEDED', { field: 'items' }, 507, en), en.quotaItems);
is('507 bytes', errorText('QUOTA_EXCEEDED', { field: 'bytes' }, 507, en), en.quotaBytes);
is('507 without field → old sentence', errorText('QUOTA_EXCEEDED', {}, 507, en), en.errors.QUOTA_EXCEEDED);
is('503 circuit', errorText('GLOBAL_CIRCUIT_OPEN', { retry_after: 60 }, 503, en), en.errors.GLOBAL_CIRCUIT_OPEN);
is('blob unavailable', errorText('BLOB_UNAVAILABLE', {}, 503, zh), zh.errors.BLOB_UNAVAILABLE);
is('unknown code → HTTP status', errorText('SOMETHING_NEW', {}, 500, en), en.httpFailed(500));

// ── dropit me ────────────────────────────────────────────────────────────
const DAY = 86_400_000;
const base = { plan: 'free', devices_used: 1, devices_limit: 3, bytes_used: 0, bytes_limit: 31457280, item_bytes_limit: 5242880 };
is('trial with days left', meLine({ ...base, ws_available: true, realtime_until: now + 2.5 * DAY }, en, now),
  'free · 1/3 devices · 0.0/30 MB · files up to 5 MB · real-time trial: 3 days left');
is('trial under a day', meLine({ ...base, ws_available: true, realtime_until: now + 3_600_000 }, en, now),
  'free · 1/3 devices · 0.0/30 MB · files up to 5 MB · real-time trial: less than a day left');
is('trial over', meLine({ ...base, ws_available: false, realtime_until: now - DAY }, en, now),
  'free · 1/3 devices · 0.0/30 MB · files up to 5 MB · real-time trial ended');
is('null → always on', meLine({ ...base, plan: 'pro', devices_limit: null, ws_available: true, realtime_until: null }, en, now),
  'pro · 1/∞ devices · 0.0/30 MB · files up to 5 MB · real-time available');
is('older service: no realtime_until, no item limit → ws_available', meLine(
  { plan: 'free', devices_used: 2, devices_limit: 3, bytes_used: 1258291, bytes_limit: 31457280, ws_available: false }, en, now),
  'free · 2/3 devices · 1.2/30 MB · real-time unavailable');
is('zh trial', meLine({ ...base, ws_available: true, realtime_until: now + 3 * DAY }, zh, now),
  'free · 1/3 台设备 · 0.0/30 MB · 单个文件最大 5 MB · 实时推送体验期：还剩 3 天');

// ── dropit pair ──────────────────────────────────────────────────────────
is('first machine, no code → create', pairAction([], false), { action: 'create' });
is('joined machine, no code → refuse', pairAction([], true), { action: 'refuse' });
is('joined machine, --new → create', pairAction(['--new'], true), { action: 'create' });
is('with a code → join, joined or not', [pairAction(['k7m2qx'], true), pairAction(['k7m2qx'], false)],
  [{ action: 'join', code: 'k7m2qx' }, { action: 'join', code: 'k7m2qx' }]);
ok('refusal names both ways out', en.alreadyJoined.includes('dropit pair <code>') && en.alreadyJoined.includes('dropit pair --new')
  && zh.alreadyJoined.includes('dropit pair --new'));

// Paths inside the home folder are shown as ~/…
{
  const { tildePath } = require('../cli/dropit');
  ok('tildePath: inside home', tildePath('/Users/a/Notes/Inbox', '/Users/a') === '~/Notes/Inbox');
  ok('tildePath: home itself', tildePath('/Users/a', '/Users/a') === '~');
  ok('tildePath: a look-alike prefix is not home', tildePath('/Users/ab/x', '/Users/a') === '/Users/ab/x');
  ok('tildePath: elsewhere unchanged', tildePath('/tmp/x', '/Users/a') === '/tmp/x');
}

// ── pairing again on the same machine: fingerprint and wording ────────────
{
  const facts = machineFacts();
  const key = deviceKey('cli', facts);
  ok('fingerprint is a 64-character hex hash, never the facts', /^[0-9a-f]{64}$/.test(key) && !key.includes(facts[0]), key);
  is('the same machine gives the same fingerprint', deviceKey('cli', machineFacts()), key);
  ok('another kind of client gives another fingerprint', deviceKey('obsidian', facts) !== key);
  ok('another config file on this machine is another client', deviceKey('cli', [...facts.slice(0, -1), '/elsewhere/config.json']) !== key);
  is('removed because this machine paired again: said as such', errorText('DEVICE_REVOKED', { reason: 'replaced' }, 403, en), en.revokedReplaced);
  is('removed from another device: unchanged', errorText('DEVICE_REVOKED', {}, 403, en), en.errors.DEVICE_REVOKED);
}

// ── Network side: a stand-in service, WebSocket and clock drive the real `watch` ──────
// Hours of retrying run in a second. The numbers asserted are the ones that matter to the shared service:
// how often a client asks while something is wrong, and that it stops when nothing can change.
{
  const { mock } = await import('node:test');
  const fs = await import('node:fs');
  const cli = require('../cli/dropit');
  const { watch, api, endpointsOf, sanitize, shown, yamlValue, CONFIG, HEARTBEAT_MS } = cli;
  const T0 = Date.parse('2026-10-08T00:00:00Z');
  const MIN = 60_000;
  const dir = join(HOME, 'inbox');
  const writeConfig = (cfg) => { fs.mkdirSync(join(HOME, '.config', 'dropit'), { recursive: true }); fs.writeFileSync(CONFIG, JSON.stringify(cfg)); };

  let reqs = [];
  let route = () => null;
  const answer = (status, body) => new Response(typeof body === 'string' ? body : JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
  globalThis.fetch = async (url, init = {}) => {
    const u = new URL(url);
    const r = { path: u.pathname, query: u.searchParams, auth: init.headers?.authorization, at: Date.now() };
    reqs.push(r);
    const got = await route(r);
    if (got) return got;
    if (r.path === '/v1/pull') return answer(200, { items: [], next_after: Number(r.query.get('after')), has_more: false });
    if (r.path === '/v1/ws/ticket') return answer(200, { ticket: 'tk', realtime_until: null });
    return answer(404, { error: 'NOT_FOUND' });
  };
  const count = (path, from = 0, to = Infinity) => reqs.filter((r) => r.path === path && r.at >= from && r.at < to).length;

  class FakeWS {
    static all = [];
    static plan = () => {};
    constructor(url) { this.url = url; this.readyState = 0; this.sent = []; FakeWS.all.push(this); FakeWS.plan(this); }
    open() { this.readyState = 1; this.onopen?.(); }
    serverClose() { this.readyState = 3; this.onclose?.(); }
    say(msg) { this.onmessage?.({ data: typeof msg === 'string' ? msg : JSON.stringify(msg) }); }
    send(d) { this.sent.push(d); }
    close() { if (this.readyState < 2) this.readyState = 2; }   // like a socket stuck in CLOSING: no close event
  }
  globalThis.WebSocket = FakeWS;

  // process.exit ends the scenario: recorded, and thrown so the code after it doesn't run
  let exits = [];
  const realExit = process.exit;
  process.exit = (code) => { exits.push(code); throw Object.assign(new Error('exit'), { exitCode: code }); };
  process.on('unhandledRejection', (e) => { if (!(e && 'exitCode' in e)) throw e; });
  process.setMaxListeners(50);
  let logs = [];
  const realLog = console.log, realErr = console.error;

  const settle = async () => { for (let i = 0; i < 4; i++) await new Promise((r) => setImmediate(r)); };
  const advance = async (ms, step = 1000) => { for (let d = 0; d < ms; d += step) { mock.timers.tick(step); await settle(); } };

  async function scenario(name, { handler = () => null, ws = () => {} } = {}, body) {
    writeConfig({ token: 'dk_A', endpoints: ['https://dropit.test'], cursor: 0 });
    reqs = []; exits = []; logs = []; FakeWS.all = []; FakeWS.plan = ws; route = handler;
    console.log = (...a) => logs.push(a.join(' '));
    console.error = (...a) => logs.push(a.join(' '));
    mock.timers.enable({ apis: ['setTimeout', 'setInterval', 'Date'], now: T0 });
    try {
      watch([dir]).catch((e) => { if (!('exitCode' in e)) throw e; });
      await settle();
      await body();
    } catch (err) {
      ok(`${name}: ran`, false, err.stack);
    } finally {
      mock.timers.reset();
      console.log = realLog; console.error = realErr;
    }
  }
  const opens = (ws) => setTimeout(() => ws.open(), 10);

  await scenario('ticket keeps failing', { handler: (r) => r.path === '/v1/ws/ticket' && answer(503, { error: 'SCHEMA_OUTDATED' }) }, async () => {
    await advance(180 * MIN);
    const perMinute = Array.from({ length: 180 }, (_, m) => count('/v1/ws/ticket', T0 + m * MIN, T0 + (m + 1) * MIN));
    ok('service keeps refusing tickets for 3 hours: at most 1 ticket request a minute, not more every minute (1.0.0: minute T asked T times)',
      Math.max(...perMinute.slice(10)) <= 1, perMinute.join(','));
    ok('…about 3 hours of retrying costs ~150 requests, not ~16,000', count('/v1/ws/ticket') <= 200, String(count('/v1/ws/ticket')));
  });

  await scenario('retry_after', { handler: (r) => r.path === '/v1/ws/ticket' && answer(503, { error: 'GLOBAL_CIRCUIT_OPEN', retry_after: 3600 }) }, async () => {
    await advance(59 * MIN);
    ok('503 with retry_after: 3600 → no new ticket request within the hour', count('/v1/ws/ticket') === 1, String(count('/v1/ws/ticket')));
    await advance(35 * MIN);
    ok('…and it does ask again after the hour', count('/v1/ws/ticket') >= 2, String(count('/v1/ws/ticket')));
  });

  await scenario('removed: pull', { handler: (r) => r.path === '/v1/pull' && answer(403, { error: 'DEVICE_REVOKED' }) }, async () => {
    await advance(10 * MIN);
    ok('device removed (pull answers DEVICE_REVOKED): watch stops with exit code 0 — a launch agent won\'t restart it', exits[0] === 0, JSON.stringify(exits));
    ok('…says why', logs.some((l) => l.includes(T.en.errors.DEVICE_REVOKED)), logs.join(' | '));
    ok('…and asks nothing more (1 request in 10 minutes)', reqs.length === 1, String(reqs.length));
  });

  await scenario('removed: ticket', { handler: (r) => r.path === '/v1/ws/ticket' && answer(401, { error: 'INVALID_TOKEN' }) }, async () => {
    await advance(10 * MIN);
    ok('token invalid when asking for a ticket: exit 0, no more requests', exits[0] === 0 && count('/v1/ws/ticket') === 1, `${exits} ${count('/v1/ws/ticket')}`);
  });

  await scenario('let go right after opening', { ws: (w) => { opens(w); setTimeout(() => w.serverClose(), 40); } }, async () => {
    await advance(10 * MIN, 250);
    ok('closed 30 ms after every open (kicked over and over): backs off to a minute, not back every second (1.0.0: ~570 in 10 minutes)',
      FakeWS.all.length <= 20, String(FakeWS.all.length));
  });

  await scenario('steady connection', { ws: opens }, async () => {
    await advance(2000, 100);
    ok('connected: catches up once right away (what came in while it was down)', count('/v1/pull') === 2 && FakeWS.all.length === 1, `pulls ${count('/v1/pull')}`);
    let release;
    route = (r) => r.path === '/v1/pull' && new Promise((res) => { release = () => res(answer(200, { items: [], next_after: 0, has_more: false })); });
    FakeWS.all[0].say({ type: 'new', seq: 5 });
    await settle();
    FakeWS.all[0].say({ type: 'new', seq: 6 });       // arrives while the first pull is still out
    await settle();
    route = () => null;
    release();
    await advance(1000, 100);
    ok('a push during a pull isn\'t dropped: one more round once it finishes', count('/v1/pull') === 4, `pulls ${count('/v1/pull')}`);
    await advance(HEARTBEAT_MS * 5);
    ok('a connection that answers nothing is let go and replaced, without waiting for a close event that never comes',
      FakeWS.all.length >= 2 && FakeWS.all[0].readyState === 2, `sockets ${FakeWS.all.length}`);
  });

  await scenario('too many', { ws: (w) => { opens(w); setTimeout(() => w.say({ type: 'end', end: 'too_many', limit: 12 }), 50); } }, async () => {
    await advance(14 * MIN);
    ok('told the account\'s connections are full: says so', logs.filter((l) => l.includes('Real-time push is paused')).length === 1, logs.join(' | '));
    ok('…and waits: no ticket request for 14 minutes', count('/v1/ws/ticket') === 1, String(count('/v1/ws/ticket')));
    await advance(10 * MIN);
    ok('…tries again after about 15', count('/v1/ws/ticket') === 2, String(count('/v1/ws/ticket')));
    ok('…without repeating the notice while it stays full', logs.filter((l) => l.includes('Real-time push is paused')).length === 1,
      String(logs.filter((l) => l.includes('Real-time push is paused')).length));
  });

  await scenario('account switched', { ws: opens }, async () => {
    await advance(2000, 100);
    writeConfig({ token: 'dk_B', endpoints: ['https://dropit.test'], cursor: 0 });   // what `dropit pair <code>` writes
    FakeWS.all[0].say({ type: 'new', seq: 900 });
    await settle();
    const last = reqs.filter((r) => r.path === '/v1/pull').at(-1);
    ok('paired with another account while watching: the next pull uses the new token, from its start',
      last.auth === 'Bearer dk_B' && last.query.get('after') === '0', `${last.auth} after=${last.query.get('after')}`);
    ok('…says so', logs.some((l) => l.includes(T.en.accountChanged)));
    ok('…and doesn\'t write the old account\'s cursor over the new one', JSON.parse(fs.readFileSync(CONFIG, 'utf8')).token === 'dk_B');
    await advance(HEARTBEAT_MS + 1000);
    ok('…and reconnects real-time with the new token', reqs.some((r) => r.path === '/v1/ws/ticket' && r.auth === 'Bearer dk_B') && FakeWS.all.length === 2,
      `sockets ${FakeWS.all.length}`);
  });

  // ── api: what counts as an answer, which addresses get the token ──
  {
    writeConfig({ token: 'dk_A', endpoints: ['https://portal.test', 'https://dropit.test'] });
    route = (r) => r.path === '/v1/me' && (reqs.length === 1 ? new Response('<html>sign in</html>', { status: 200 }) : null);
    reqs = [];
    route = (r) => (r.path === '/v1/me' && reqs.length === 1 ? new Response('<html>sign in</html>', { status: 200 }) : answer(200, { plan: 'paid' }));
    const got = await api(JSON.parse(fs.readFileSync(CONFIG, 'utf8')), 'GET', '/v1/me');
    ok('a 2xx that isn\'t JSON (a sign-in page) is not taken as success: the next address answers', got.plan === 'paid' && reqs.length === 2, JSON.stringify(got));
    is('…and that address is tried first next time', JSON.parse(fs.readFileSync(CONFIG, 'utf8')).endpoints, ['https://dropit.test', 'https://portal.test']);
    route = () => new Response('<html>sign in</html>', { status: 200 });
    const err = await api({ token: 'dk_A', endpoints: ['https://portal.test'] }, 'POST', '/v1/ingest', {}).catch((e) => e);
    ok('…and when none is dropit, the send fails out loud instead of printing ✓ #undefined', err instanceof Error && err.message.includes('sign-in page'), String(err?.message));
    route = () => null;
    is('addresses that get the token: https only…', endpointsOf(['http://evil.test', 'https://ok.test']), ['https://ok.test']);
    is('…or this machine, for running the service locally', endpointsOf(['http://127.0.0.1:8799', 'http://localhost:8799/']), ['http://127.0.0.1:8799', 'http://localhost:8799/']);
    is('…and the default when nothing usable is left', endpointsOf(['http://evil.test']), ['https://dropit.smart-kits.xyz']);
  }

  // ── names and text from other devices ──
  is('file name: control characters and reserved ones become _', sanitize('a\x1b]0;x\x07b/c:d.png'), 'a_]0;x_b_c_d.png');
  const zhName = '会议纪要'.repeat(40) + '.pdf';
  const cut = sanitize(zhName);
  ok('a long Chinese name is cut to ≤ 200 bytes (Linux allows 255), keeping .pdf', Buffer.byteLength(cut) <= 200 && cut.endsWith('.pdf'), `${Buffer.byteLength(cut)} ${cut.slice(-8)}`);
  const emoji = sanitize('😀'.repeat(130));
  ok('cut between characters, never inside an emoji', !/[\ud800-\udbff](?![\udc00-\udfff])/.test(emoji) && Array.from(emoji).length <= 120, String(emoji.length));
  is('a short ordinary name is unchanged', sanitize('IMG_1587.jpg'), 'IMG_1587.jpg');
  is('printed text: control characters shown as ?', shown('Mac\x1b[31m'), 'Mac?[31m');
  is('front matter: an ordinary value as is', yamlValue('ios-shortcut'), 'ios-shortcut');
  is('…one YAML would misread, quoted', [yamlValue('@phone'), yamlValue('a: b')], ['"@phone"', '"a: b"']);
  process.exit = realExit;
}


// ── account-creation puzzle: the shared vectors, then when a client may solve and resend ──
{
  const hex = (b) => Buffer.from(b).toString('hex');
  const POW = JSON.parse(readFileSync(new URL('./vectors/pow.json', import.meta.url), 'utf8'));
  const nodeCrypto = (await import('node:crypto'));
  const ref = (c, dk, nonce) => nodeCrypto.createHash('sha256').update(`${c.id}|${c.salt}|${dk ? nodeCrypto.createHash('sha256').update(dk).digest('hex') : '-'}|${nonce}`).digest();
  for (const c of POW.hash.cases) {
    const d = ref(c, c.device_key, c.nonce);
    ok(`P hash ${c.name}`, hex(d) === c.digest_hex, hex(d));
    ok(`P zeros ${c.name}`, P.zeroBits(d) === c.leading_zero_bits, String(P.zeroBits(d)));
    ok(`P verdict ${c.name}`, (P.zeroBits(d) >= c.bits) === c.valid);
  }
  for (const c of POW.leadingZeroBits) is(`P leading zero bits ${c.digest_hex.slice(0, 8)}`, P.zeroBits(Buffer.from(c.digest_hex, 'hex')), c.expect);
  // solving: what comes out passes the reference check, with and without a key
  for (const c of POW.hash.cases.filter((x) => x.valid)) {
    const ch = { v: 1, id: c.id, salt: c.salt, purpose: 'accounts.create', bits: c.bits };
    const nonce = await P.powSolve(ch, c.device_key);
    ok(`P solved ${c.name}`, nonce !== null && P.zeroBits(ref(ch, c.device_key, nonce)) >= c.bits, String(nonce));
    is(`P first answer is the vector's nonce ${c.name}`, nonce, c.nonce);
  }
  {
    const ch = { v: 1, id: 'q1w2e3r4t5y6u7i8o9p0aQ', salt: 'zXcVbNmLkJhGfDsAqWeRtY', purpose: 'accounts.create', bits: 16 };
    const nonce = await P.powSolve(ch, 'k');
    ok('P 16 bits solved, node agrees', P.zeroBits(ref(ch, 'k', nonce)) >= 16);
    is('P out of time → null', await P.powSolve({ ...ch, bits: 26 }, 'k', { limitMs: 30 }), null);
  }

  // when to solve and resend
  const chal = (o = {}) => ({ v: 1, id: 'AAAAAAAAAAAAAAAAAAAAAA', iat: 1, exp: 2, purpose: 'accounts.create', bits: 8, salt: 'BBBBBBBBBBBBBBBBBBBBBB', used: 3, sig: 'sig', ...o });
  const refusal = (data) => Object.assign(new Error('refused'), { code: 'TURNSTILE_REQUIRED', status: 403, data });
  const run = async (answers, body = { device_name: 'x', device_key: 'dk' }) => {
    const sent = [];
    let solving = 0;
    const send = async (b) => { sent.push(b); const a = answers[Math.min(sent.length - 1, answers.length - 1)]; if (a instanceof Error) throw a; return a; };
    let out;
    try { out = { ok: await P.withPow(send, body, { onSolving: () => solving++ }) }; } catch (e) { out = { err: e }; }
    return { sent, solving, ...out };
  };
  {
    const r = await run([refusal({ error: 'TURNSTILE_REQUIRED', challenge: chal() }), { token: 't' }]);
    is('P a puzzle → resent once, with the answer', [r.sent.length, r.solving, r.ok], [2, 1, { token: 't' }]);
    const { pow, ...rest } = r.sent[1];
    is('P the resend is the same request plus pow', rest, { device_name: 'x', device_key: 'dk' });
    is('P pow echoes the puzzle and adds nonce', Object.keys(pow), ['v', 'id', 'iat', 'exp', 'purpose', 'bits', 'salt', 'used', 'sig', 'nonce']);
    ok('P the nonce is a plain decimal string', /^(0|[1-9][0-9]*)$/.test(pow.nonce));
    ok('P the nonce passes the reference check', P.zeroBits(ref(pow, 'dk', pow.nonce)) >= 8);
  }
  {
    const r = await run([refusal({ error: 'TURNSTILE_REQUIRED', challenge: chal() }), { token: 't' }], { device_name: 'x' });
    ok('P no key sent → solved against "-"', P.zeroBits(ref(r.sent[1].pow, null, r.sent[1].pow.nonce)) >= 8);
  }
  for (const [name, c] of [['bits 27', chal({ bits: 27 })], ['unknown v', chal({ v: 2 })], ['wrong purpose', chal({ purpose: 'other' })]]) {
    const first = refusal({ error: 'TURNSTILE_REQUIRED', challenge: c });
    const r = await run([first]);
    ok(`P ${name} → not solved, the first error as it was`, r.sent.length === 1 && r.solving === 0 && r.err === first);
  }
  ok('P bits 26 is still tried', P.powChallengeOf({ challenge: chal({ bits: 26 }) }) !== null);
  {
    const first = refusal({ error: 'TURNSTILE_REQUIRED' });
    const r = await run([first]);
    ok('P no puzzle (older service) → the first error as it was', r.sent.length === 1 && r.solving === 0 && r.err === first);
  }
  {
    const r = await run([refusal({ error: 'TURNSTILE_REQUIRED', challenge: chal() }), refusal({ error: 'TURNSTILE_REQUIRED', challenge: chal() })]);
    ok('P refused again → one resend only, the error shows', r.sent.length === 2 && r.err?.code === 'TURNSTILE_REQUIRED');
  }
  {
    const other = Object.assign(new Error('x'), { code: 'SIGNUP_LIMITED', data: { challenge: chal() } });
    const r = await run([other]);
    ok('P another error code is never retried', r.sent.length === 1 && r.err === other);
  }
}

console.log(`\n${fail === 0 ? '✅' : '🛑'}  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
