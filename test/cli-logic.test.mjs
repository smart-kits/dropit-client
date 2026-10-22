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
is('upgrade link follows the sentence, tagged with this client', withUpgrade('Too fast', { upgrade_url: link, why: 'per_day' }, en), `Too fast — Keep sending with Paid: ${link}&src=cli`);
is('upgrade link, zh', withUpgrade('投得太快', { upgrade_url: link, why: 'queue' }, zh), `投得太快 — 升级后可以积压更多: ${link}&src=cli`);
is('a link with no query gets ?src', withUpgrade('x', { upgrade_url: 'https://dropit.example/upgrade', why: 'per_day' }, en), 'x — Keep sending with Paid: https://dropit.example/upgrade?src=cli');
is('the hourly limit is never sold, even if a link came', withUpgrade('Slow down', { upgrade_url: link, why: 'per_day', window: 'hour' }, en), 'Slow down');
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
const base = { plan: 'free', devices_used: 1, devices_limit: 2, bytes_used: 0, bytes_limit: 31457280, item_bytes_limit: 5242880 };
is('trial with days left', meLine({ ...base, ws_available: true, realtime_until: now + 2.5 * DAY }, en, now),
  'free · 1/2 devices · 0.0/30 MB · files up to 5 MB · real-time trial: 3 days left');
is('trial under a day', meLine({ ...base, ws_available: true, realtime_until: now + 3_600_000 }, en, now),
  'free · 1/2 devices · 0.0/30 MB · files up to 5 MB · real-time trial: less than a day left');
is('trial over', meLine({ ...base, ws_available: false, realtime_until: now - DAY }, en, now),
  'free · 1/2 devices · 0.0/30 MB · files up to 5 MB · real-time trial ended');
is('null → always on', meLine({ ...base, plan: 'pro', devices_limit: null, ws_available: true, realtime_until: null }, en, now),
  'pro · 1/∞ devices · 0.0/30 MB · files up to 5 MB · real-time available');
is('older service: no realtime_until, no item limit → ws_available', meLine(
  { plan: 'free', devices_used: 2, devices_limit: 2, bytes_used: 1258291, bytes_limit: 31457280, ws_available: false }, en, now),
  'free · 2/2 devices · 1.2/30 MB · real-time unavailable');
is('zh trial', meLine({ ...base, ws_available: true, realtime_until: now + 3 * DAY }, zh, now),
  'free · 1/2 台设备 · 0.0/30 MB · 单个文件最大 5 MB · 实时推送体验期：还剩 3 天');

// ── Upgrade moments: numbers from the service, lines once, nothing made up ──
{
  const { upgradeLink, accountNotes, trialDaysOf, trialFacts } = P;
  is('trial length: from the account', trialDaysOf({ trial_days: 14 }, { limits: { free: { realtimeTrialDays: 9 } } }), 14);
  is('…else from the plans', trialDaysOf({}, { limits: { free: { realtimeTrialDays: 9 } } }), 9);
  is('…else from the account\'s two dates', trialDaysOf({ created_at: 0, realtime_until: 7 * DAY }, null), 7);
  is('…else nothing: no number is written', [trialDaysOf({}, null), trialDaysOf(null, null)], [null, null]);
  is('upgradeLink: nothing without a link', [upgradeLink({}), upgradeLink(undefined), upgradeLink({ upgrade_url: 'javascript:alert(1)' })], [null, null, null]);
  is('what the account has had: only what it said', trialFacts({}, 14, now), { days: 0, items: 0, devices: 0 });
  is('…the first day counts as 1, and it stops at the trial\'s length', [trialFacts({ created_at: now - 100 }, 14, now).days, trialFacts({ created_at: now - 30 * DAY }, 14, now).days], [1, 14]);

  for (const [lang, S] of [['en', en], ['zh', zh]]) {
    const f = { days: 12, items: 41, devices: 2 };
    ok(`${lang}: "what you had" says days, items and devices`, /12/.test(S.enjoyed(f)) && /41/.test(S.enjoyed(f)) && /2/.test(S.enjoyed(f)), S.enjoyed(f));
    ok(`${lang}: no items sent: that half is left out`, !/41|\b0\b/.test(S.enjoyed({ ...f, items: 0 })) && /12/.test(S.enjoyed({ ...f, items: 0 })), S.enjoyed({ ...f, items: 0 }));
    ok(`${lang}: one device: that half is left out`, S.enjoyed({ ...f, devices: 1 }).replace(/12|41/g, '').search(/\d/) === -1, S.enjoyed({ ...f, devices: 1 }));
    ok(`${lang}: the trial lines carry the length they are given, and none when they have none`,
      [S.wsTrialEnding, S.wsNotIncluded, S.wsTrialOver].every((fn) => fn(14).includes('14') && !/\d{2}/.test(fn(null)))
      && S.wsTrialSoon(S.daysLeft(3), 14).includes('14') && !/\d{2}/.test(S.wsTrialSoon(S.daysLeft(0), null)), S.wsTrialOver(14));
    is(`${lang}: me usage: both halves only when said`, [S.meUsage(0, 0), S.meUsage(3, 0).includes('3'), /41/.test(S.meUsage(0, 41))], ['', true, true]);
  }
  is('me usage, en', en.meUsage(12, 41), '12 days in use / 41 items sent');
  is('me usage, zh', zh.meUsage(12, 41), '已用 12 天 / 已投 41 条');

  const free = { plan: 'free', created_at: now - 12 * DAY - 1000, items_total: 41, upgrade_url: link };
  is('me: how long and how much (the first day counts as 1)', accountNotes(free, {}, now, en).usage, '13 days in use / 41 items sent');
  is('me: nothing said when the account gave neither', accountNotes({ plan: 'free' }, {}, now, en).usage, null);
  is('me: no items sent yet: only the days', accountNotes({ ...free, items_total: 0 }, {}, now, en).usage, '13 days in use');
  const paid = { plan: 'paid', paid_until: now + 5 * DAY, renews: false, items_total: 9 };
  ok('P1: a paid year with under a week left says when it ends', /^Paid runs until \d{4}-\d{2}-\d{2}\./.test(accountNotes(paid, {}, now, en).p1?.text ?? ''), JSON.stringify(accountNotes(paid, {}, now, en).p1));
  is('P1: a plan that renews itself: nothing', accountNotes({ ...paid, renews: true }, {}, now, en).p1, null);
  is('P1: more than a week away: nothing', accountNotes({ ...paid, paid_until: now + 30 * DAY }, {}, now, en).p1, null);
  is('P1: a lifetime plan (no end): nothing', accountNotes({ plan: 'paid', paid_until: null, renews: false }, {}, now, en).p1, null);
  const gone = { plan: 'free', paid_until: now - 1000, paid_grace_until: now + 10 * DAY, items_total: 500, upgrade_url: link };
  const ended = accountNotes(gone, { lastPlan: 'paid', lastPaidUntil: now - 1000 }, now, en).p2;
  ok('P2: Paid just ended: the account\'s total, the grace date, and a link tagged with this client', /Paid has ended/.test(ended?.text) && /500 items in total/.test(ended.text) && ended.link === `${link}&src=cli`, JSON.stringify(ended));
  is('P2: not known to have been paid here: nothing', accountNotes(gone, {}, now, en).p2, null);
  is('P2: no grace period (refunded, or never paid): nothing', accountNotes({ ...gone, paid_grace_until: null }, { lastPlan: 'paid' }, now, en).p2, null);
  is('P2: still paid: nothing', accountNotes(paid, { lastPlan: 'paid' }, now, en).p2, null);
}

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

  // Reading the account at the start never holds up receiving for long: a slow answer is waited for 3 seconds at most
  await scenario('account read hangs', { handler: (r) => (r.path === '/v1/me' ? new Promise(() => {}) : null), ws: (w) => opens(w) }, async () => {
    await advance(2000);
    ok('the account has not answered after 2 s: real-time is not asked for yet (it waits, but only for 3 s)', count('/v1/ws/ticket') === 0, String(count('/v1/ws/ticket')));
    await advance(3000);
    ok('after 3 s it connects anyway, without it', count('/v1/ws/ticket') === 1 && FakeWS.all.length === 1 && logs.some((l) => l === T.en.wsConnected), logs.join(' | '));
  });
  await scenario('account read answers at once', { handler: (r) => r.path === '/v1/me' && answer(200, { plan: 'paid', paid_until: T0 + 100 * 86_400_000, renews: true, realtime_until: null }), ws: (w) => opens(w) }, async () => {
    await advance(1000);
    ok('a quick answer is not waited out: connected within a second', count('/v1/ws/ticket') === 1 && FakeWS.all.length === 1, String(count('/v1/ws/ticket')));
  });

  // The trial's end, said by the account: T1 / T2 when watch starts (once), T3 when the service turns real-time down
  {
    const ME = (extra = {}) => ({ plan: 'free', trial_days: 14, created_at: T0 - 12 * 86_400_000 - 1000, items_total: 41, devices_used: 2, upgrade_url: 'https://dropit.test/upgrade?u=1&why=account&buy=year', ...extra });
    const readMoments = () => JSON.parse(fs.readFileSync(CONFIG, 'utf8')).moments ?? {};
    await scenario('T1', { handler: (r) => r.path === '/v1/me' && answer(200, ME({ realtime_until: T0 + 2.5 * 86_400_000 })), ws: (w) => opens(w) }, async () => {
      await advance(2000);
      const line = logs.find((l) => /3 days left/.test(l));
      ok('T1: three days out, watch says so once at start, with the length and what the account had', !!line && /14 days/.test(line) && /41 items/.test(line) && /2 devices/.test(line), logs.join(' | '));
      ok('…with the way to Paid, tagged with this client', !!line && /&src=cli$/.test(line), line);
      ok('…and remembers it in the config file', Object.keys(readMoments()).some((k) => k.startsWith('T1.')), JSON.stringify(readMoments()));
    });
    writeConfig({ token: 'dk_A', endpoints: ['https://dropit.test'], cursor: 0 });
    ok('a line is shown the first time for its period and not again', cli.momentTaken('T1', 5) === false && cli.momentTaken('T1', 5) === true && cli.momentTaken('T1', 6) === false, JSON.stringify(readMoments()));
    ok('…and the token and the rest of the config are left as they were', JSON.parse(fs.readFileSync(CONFIG, 'utf8')).token === 'dk_A');
    fs.writeFileSync(CONFIG, '{ not json');
    ok('a config that can\'t be read: the answer is "already shown" (silent beats repeating)', cli.momentTaken('T9', 1) === true);
    await scenario('T2', { handler: (r) => r.path === '/v1/me' && answer(200, ME({ realtime_until: T0 + 3 * 3_600_000, items_total: 0, devices_used: 1 })), ws: (w) => opens(w) }, async () => {
      await advance(2000);
      const line = logs.find((l) => /less than a day/.test(l));
      ok('T2: under a day: the old wording, the service\'s length, and no halves with nothing to say', !!line && /14 days/.test(line) && !/items sent|devices receiving/.test(line) && /12 days|13 days/.test(line), logs.join(' | '));
      await advance(3 * 60 * MIN);
      ok('…said once in this run (the heartbeat does not say it again)', logs.filter((l) => /less than a day/.test(l)).length === 1, logs.join(' | '));
    });
    await scenario('T3', {
      handler: (r) => (r.path === '/v1/me' ? answer(200, ME({ realtime_until: T0 - 1000 }))
        : r.path === '/v1/ws/ticket' ? answer(403, { error: 'WS_REQUIRES_PAID', reason: 'trial_over', upgrade_url: 'https://dropit.test/upgrade?u=1&why=realtime&buy=year', why: 'realtime' }) : null),
    }, async () => {
      await advance(2000);
      const line = logs.find((l) => /real-time trial has ended/.test(l));
      ok('T3: exits after catching up (code 0)', exits[0] === 0, JSON.stringify(exits));
      ok('T3: says the trial\'s length and what the account had', !!line && /14-day/.test(line) && /41 items/.test(line) && /2 devices/.test(line), logs.join(' | '));
      ok('T3: the link is tagged with this client', !!line && /&src=cli$/.test(line), line);
    });
    await scenario('T3, account unreadable', { handler: (r) => (r.path === '/v1/ws/ticket' ? answer(403, { error: 'WS_REQUIRES_PAID', reason: 'trial_over' }) : null) }, async () => {
      await advance(2000);
      const line = logs.find((l) => /real-time trial has ended/.test(l));
      ok('T3 with no answer from the account: still said, with no number and no link', !!line && !/\d/.test(line) && !/https?:/.test(line), logs.join(' | '));
    });
    await scenario('paid', { handler: (r) => r.path === '/v1/me' && answer(200, { plan: 'paid', paid_until: T0 + 100 * 86_400_000, renews: true, realtime_until: null }), ws: (w) => opens(w) }, async () => {
      await advance(2000);
      ok('a paid account hears nothing about the trial or the free plan', !logs.some((l) => /trial|free/i.test(l)), logs.join(' | '));
    });
  }

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

// ── Who a send goes to: --to, dropit target, and what a send reads back ───────────────
// Pure parts first; then the real `send`, `sendFiles` and `target` against a stand-in service and a scratch home folder.
{
  process.env.NO_COLOR = '1';                       // no escape codes in what is compared
  const fsx = await import('node:fs');
  const cli = require('../cli/dropit');
  const { toFlag, wordsOf, resolveWords, targetLabel, savedTargets, settleDefault, receiptLine, deviceLines, cols, shellQuote } = cli;
  const NOW = Date.parse('2026-10-22T12:00:00Z');
  const DEVICES = [
    { device_id: 'd_me', name: 'My Mac', scope: 'full', last_seen_at: NOW },
    { device_id: 'd_pix', name: 'My Pixel', scope: 'full', last_seen_at: NOW - 180_000 },
    { device_id: 'd_lin', name: 'Home Linux', scope: 'full', last_seen_at: NOW - 86_400_000 },
    { device_id: 'd_ios', name: 'iPhone Shortcut', scope: 'ingest_only', last_seen_at: NOW - 86_400_000 },
    { device_id: 'd_o1', name: 'Office web', scope: 'full', last_seen_at: NOW - 3_600_000 },
    { device_id: 'd_o2', name: 'Office web', scope: 'full', last_seen_at: NOW - 7_200_000 },
  ];
  const resolved = (words, tt = en, devices = DEVICES) => resolveWords(words, devices, 'd_me', tt, NOW);

  // --to out of the arguments
  {
    const a = ['hello', '--to', 'My Pixel'];
    is('--to <value>: the value, and the words left are the text', [toFlag(a), a], ['My Pixel', ['hello']]);
    const b = ['-f', 'a.jpg', 'b.jpg', '--to', 'My Mac'];
    is('--to after the files: they stay the files', [toFlag(b), b], ['My Mac', ['-f', 'a.jpg', 'b.jpg']]);
    const c = ['--to=Pixel,Linux', 'hi'];
    is('--to=<value>', [toFlag(c), c], ['Pixel,Linux', ['hi']]);
    const d = ['--to', 'Pixel', 'x', '--to', 'Linux'];
    is('given twice: both count', [toFlag(d), d], ['Pixel,Linux', ['x']]);
    const e = ['hi'];
    is('absent: null, nothing taken out', [toFlag(e), e], [null, ['hi']]);
    for (const bad of [['--to'], ['--to', '--other'], ['--to=']]) {
      let msg = null;
      try { toFlag([...bad]); } catch (err) { msg = err.message; }
      is(`${JSON.stringify(bad)} → says what is needed`, msg, en.needTo);
    }
    is('names split at commas, blanks dropped', wordsOf(' My Pixel , ,Home Linux,'), ['My Pixel', 'Home Linux']);
  }

  // a name or an id → a device
  is('a name', resolved(['My Pixel']), { ids: ['d_pix'] });
  is('a name ignoring case and spaces: typing it without the space works', [resolved(['mypixel']), resolved(['MY  PIXEL'])], [{ ids: ['d_pix'] }, { ids: ['d_pix'] }]);
  is('an id', resolved(['d_lin']), { ids: ['d_lin'] });
  is('several, in the order given, each once', resolved(['Home Linux', 'My Pixel', 'd_lin']), { ids: ['d_lin', 'd_pix'] });
  is('"all" alone', [resolved(['all']), resolved(['ALL'])], [{ all: true }, { all: true }]);
  ok('"all" with others is refused', resolved(['all', 'My Pixel']).error?.includes(en.toAllAlone));
  {
    const r = resolved(['Office web']);
    ok('two devices with the name: no guess, both ids listed', r.error?.includes(en.ambiguous('Office web', 2)) && r.error.includes('d_o1') && r.error.includes('d_o2') && !r.ids, r.error);
    ok('…each with when it was last seen, to tell them apart', /d_o1 {2}Office web {2}1 h ago/.test(r.error) && /d_o2 {2}Office web {2}2 h ago/.test(r.error), r.error);
    is('…and the id then works', resolved(['d_o2']), { ids: ['d_o2'] });
    ok('…in Chinese too', resolved(['Office web'], zh).error?.includes(zh.ambiguous('Office web', 2)));
  }
  ok('this machine is not a target: said, by name and by id', resolved(['My Mac']).error?.includes(en.thisMachine('My Mac')) && resolved(['d_me']).error?.includes(en.thisMachine('My Mac')));
  ok('a device that can only send is not a target', resolved(['iPhone Shortcut']).error?.includes(en.sendOnlyDevice('iPhone Shortcut')) && resolved(['d_ios']).error?.includes(en.sendOnlyDevice('iPhone Shortcut')));
  ok('no such device: named, and where to look', resolved(['Nope']).error?.includes(en.noDevice('Nope')) && resolved(['d_zzz']).error?.includes(en.noDevice('d_zzz')));
  is('a name shared with this machine means the other one', resolveWords(['Office web'], [{ device_id: 'd_me', name: 'Office web', scope: 'full' }, { device_id: 'd_x', name: 'Office web', scope: 'full' }], 'd_me', en, NOW), { ids: ['d_x'] });
  ok('an error starts with the cross', resolved(['Nope']).error.startsWith('✗ '));
  ok('no device that cannot be typed: no hint about numbers', !resolved(['Nope']).error.includes(en.nameByNumber));
  {
    const odd = [{ device_id: 'd_me', name: 'Me', scope: 'full' }, { device_id: 'd_w', name: 'Work, Mac', scope: 'full' }, { device_id: 'd_a', name: 'All', scope: 'full' }];
    const typed = resolveWords(wordsOf('Work, Mac'), odd, 'd_me', en, NOW);
    ok('a name with a comma splits into two words that match nothing: the error says to use the id', typed.error?.includes(en.noDevice('Work')) && typed.error.includes(en.nameByNumber), typed.error);
    ok('…in Chinese too', resolveWords(wordsOf('Work, Mac'), odd, 'd_me', zh, NOW).error?.includes(zh.nameByNumber));
    is('…and the id of that device works', resolveWords(['d_w'], odd, 'd_me', en, NOW), { ids: ['d_w'] });
    ok('a device called all cannot be named beside others: the error says to use the id', resolveWords(['All', 'Work'], odd, 'd_me', en, NOW).error?.includes(en.nameByNumber));
    is('…and its id works', resolveWords(['d_a'], odd, 'd_me', en, NOW), { ids: ['d_a'] });
  }
  // --batch --of --at: files into a batch that was begun
  {
    const { batchFlags } = cli;
    const args = ['-f', 'b.jpg', 'd.jpg', '--batch', 'bGRPabc123', '--of', '5', '--at', '2,4'];
    is('batch flags are taken out of the arguments and read', [batchFlags(args), args], [{ id: 'bGRPabc123', n: 5, at: [2, 4] }, ['-f', 'b.jpg', 'd.jpg']]);
    is('none given: none', batchFlags(['-f', 'a.jpg']), null);
    for (const bad of [['--batch', 'bGRPabc123'], ['--batch', 'bGRPabc123', '--of', '5'], ['--of', '5', '--at', '1'], ['--batch', 'x', '--of', '5', '--at', '1'],
      ['--batch', 'bGRPabc123', '--of', '1', '--at', '1'], ['--batch', 'bGRPabc123', '--of', '17', '--at', '1'], ['--batch', 'bGRPabc123', '--of', '5', '--at', '6'],
      ['--batch', 'bGRPabc123', '--of', '5', '--at', '2,2'], ['--batch', 'bGRPabc123', '--of', '5', '--at', '0'], ['--batch', 'bGRPabc123', '--of', '5', '--at', 'a'], ['--batch']]) {
      let msg = null;
      try { batchFlags([...bad]); } catch (e) { msg = e.message; }
      ok(`batch flags that do not make a batch are refused: ${bad.join(' ')}`, msg === en.batchFlagsBad, String(msg));
    }
  }
  // the link of a limit: src goes before a fragment
  {
    const up = (u) => withUpgrade('x', { upgrade_url: u, why: 'per_day' }, en).split(': ').pop();
    is('src= goes before a #fragment, with ? or & as the link needs', [up('https://dropit.example/upgrade#plans'), up('https://dropit.example/upgrade?a=1#plans'), up('https://dropit.example/upgrade?a=1')],
      ['https://dropit.example/upgrade?src=cli#plans', 'https://dropit.example/upgrade?a=1&src=cli#plans', 'https://dropit.example/upgrade?a=1&src=cli']);
  }

  // who it went to, in a few words
  const label = (names, tt = en) => targetLabel(names, tt);
  is('all', [label(null), label([]), label(null, zh)], ['All', 'All', '全部']);
  is('one: the name', label(['My Pixel']), 'My Pixel');
  is('one, 8 Chinese characters: whole; 9: cut with …', [label(['一二三四五六七八'], zh), label(['一二三四五六七八九'], zh)], ['一二三四五六七八', '一二三四五六七…']);
  is('one, 12 other characters: whole; more: cut with …', [label(['abcdefghijkl']), label(['abcdefghijklm'])], ['abcdefghijkl', 'abcdefghijk…']);
  is('two short names: both', [label(['Mac', 'Pixel']), label(['我的Mac', '旧笔记本'], zh)], ['Mac, Pixel', '我的Mac、旧笔记本']);
  is('two names over 14 characters together: just how many', [label(['My Pixel', 'Home Linux']), label(['我的Mac', '家里的 Linux'], zh)], ['2 devices', '2 台']);
  is('three or more: how many, never the names', [label(['a', 'b', 'c']), label(['a', 'b', 'c', 'd'], zh)], ['3 devices', '4 台']);
  ok('a name from another device cannot carry a control character into the line', !/[\u0000-\u001f]/.test(label(['Mac\x1b[31m'])));

  // the receipt
  const at = new Date(2026, 9, 22, 14, 19);
  is('receipt: to everyone, with the time', receiptLine(131, 'All', false, en, at), '✓ Sent #131  → All · 14:19');
  is('receipt: chosen with --to, says it was this once', receiptLine(132, 'My Pixel', true, en, at), '✓ Sent #132  → My Pixel · this once; default unchanged');
  is('receipt in Chinese', [receiptLine(131, '全部', false, zh, at), receiptLine(132, '我的 Pixel', true, zh, at)], ['✓ 已投递 #131  → 全部 · 14:19', '✓ 已投递 #132  → 我的 Pixel · 只这一次，默认不变']);

  // the default as kept
  is('saved default: ids alone, or with names; what is not an id is left out', savedTargets({ targets: ['d_a', { id: 'd_b', name: 'B' }, { id: 'not an id' }, null, 5, { id: 'd_c' }] }),
    [{ id: 'd_a', name: 'd_a' }, { id: 'd_b', name: 'B' }, { id: 'd_c', name: 'd_c' }]);
  is('saved default: absent or not a list → none', [savedTargets({}), savedTargets({ targets: 'd_a' })], [[], []]);
  is('the default against today\'s devices: the gone, the sender-only and this machine leave; a rename is followed',
    settleDefault([{ id: 'd_pix', name: 'Old name' }, { id: 'd_gone', name: 'Old Mac' }, { id: 'd_ios', name: 'iPhone' }, { id: 'd_me', name: 'Me' }], DEVICES, 'd_me'),
    { keep: [{ id: 'd_pix', name: 'My Pixel' }], removed: [{ id: 'd_gone', name: 'Old Mac' }, { id: 'd_ios', name: 'iPhone' }, { id: 'd_me', name: 'Me' }] });

  // the devices list: columns line up, also with Chinese names
  {
    const list = [{ device_id: 'd_me', name: '我的 Mac', scope: 'full', last_seen_at: NOW }, { device_id: 'd_pix', name: 'Pixel', scope: 'full', last_seen_at: NOW - 180_000 }, { device_id: 'd_ios', name: 'iPhone 快捷指令', scope: 'ingest_only', last_seen_at: NOW - 90_000_000 }];
    const lines = deviceLines(list, 'd_me', zh, NOW);
    const col = (l, id) => cols(l.slice(0, l.indexOf(id)));
    ok('devices: the id column starts at the same place in every row', new Set(lines.map((l, k) => col(l, list[k].device_id))).size === 1, lines.join('\n'));
    ok('devices: this machine and a send-only one are tagged, the others are not', lines[0].includes(zh.devThis) && lines[2].includes(zh.devSendOnly) && !lines[1].includes(zh.devThis) && !lines[1].includes(zh.devSendOnly), lines.join('\n'));
    ok('devices: when it was last seen, in words', lines[0].includes('刚刚') && lines[1].includes('3 分钟前') && lines[2].includes('昨天'), lines.join('\n'));
    ok('devices: English reads "now", "3 min ago", "1 d ago"', ['now', '3 min ago', '1 d ago'].every((w) => deviceLines(list, 'd_me', en, NOW).join('\n').includes(w)));
  }

  // ── the real thing, against a stand-in service and a scratch home ──
  const writeConfig = (cfg) => { fsx.mkdirSync(join(HOME, '.config', 'dropit'), { recursive: true }); fsx.writeFileSync(cli.CONFIG, JSON.stringify(cfg)); };
  const readConfig = () => JSON.parse(fsx.readFileSync(cli.CONFIG, 'utf8'));
  const base = { token: 'dk_A', device_id: 'd_me', endpoints: ['https://dropit.test'] };
  const answer = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'content-type': 'application/json' } });
  const view = (ids) => (ids ? ids.map((id) => ({ device_id: id, name: DEVICES.find((d) => d.device_id === id)?.name })) : null);
  let reqs = [];
  let route = () => null;
  const realFetch = globalThis.fetch;
  globalThis.fetch = async (url, init = {}) => {
    const u = new URL(url);
    const r = { method: init.method ?? 'GET', path: u.pathname, query: u.searchParams, body: typeof init.body === 'string' ? JSON.parse(init.body) : null };
    reqs.push(r);
    if (init.body && typeof init.body.cancel === 'function') await init.body.cancel().catch(() => {});
    const got = await route(r);
    if (got) return got;
    if (r.path === '/v1/devices') return answer(200, { devices: DEVICES });
    if (r.path === '/v1/me') return answer(200, { devices_used: 6, devices_limit: 10, item_bytes_limit: 10_485_760 });
    if (r.path === '/v1/ingest') return answer(200, { seq: 132, deduped: false, to: view(r.body.to ?? null) });
    if (r.path === '/v1/ingest/file') return answer(200, { seq: 140, filename: r.query.get('name'), to: view(r.query.get('to') ? r.query.get('to').split(',') : null) });
    return answer(404, { error: 'NOT_FOUND' });
  };
  // Runs `fn` with the console and process.exit stood in for: what it printed, how it ended, and what it asked the service
  const run = async (fn) => {
    const out = [], err = [];
    const realLog = console.log, realErr = console.error, realExit = process.exit;
    let exit = null, thrown = null;
    reqs = [];
    cli.forgetDevices();                            // each run is its own process: the list of devices is asked for again
    console.log = (...a) => out.push(a.join(' '));
    console.error = (...a) => err.push(a.join(' '));
    process.exit = (code) => { exit = code; throw Object.assign(new Error('exit'), { exitCode: code }); };
    try { await fn(); } catch (e) { if (!('exitCode' in e)) thrown = e.message; } finally { console.log = realLog; console.error = realErr; process.exit = realExit; }
    return { out, err, exit, thrown, reqs: [...reqs] };
  };
  const paths = (r) => r.reqs.map((x) => `${x.method} ${x.path}`);
  const clockLine = (s) => /^✓ Sent #\d+  → .* · \d\d:\d\d$/.test(s);

  // text
  writeConfig(base);
  {
    const r = await run(() => cli.send(['hello']));
    is('a send with no choice and the default "all": one request, no list asked for, no `to`', [paths(r), r.reqs[0].body.to], [['POST /v1/ingest'], undefined]);
    ok('…and the receipt says "→ All" with the time', clockLine(r.out[0]) && r.out[0].includes('→ All'), r.out.join('|'));
  }
  {
    const r = await run(() => cli.send(['hello', '--to', 'My Pixel']));
    is('--to <name>: the list is read, the id goes in `to`', [paths(r), r.reqs[1].body.to], [['GET /v1/devices', 'POST /v1/ingest'], ['d_pix']]);
    is('…the receipt names it and says it was this once', r.out, ['✓ Sent #132  → My Pixel · this once; default unchanged']);
    is('…and the default is not touched', readConfig().targets, undefined);
  }
  {
    const r = await run(() => cli.send(['hello', '--to', 'all']));
    is('--to all: nothing to look up, no `to`', [paths(r), r.reqs[0].body.to, r.out], [['POST /v1/ingest'], undefined, ['✓ Sent #132  → All · this once; default unchanged']]);
    const two = await run(() => cli.send(['--to', 'My Pixel,Home Linux', 'x']));
    is('--to two names: both ids; the receipt says how many', [two.reqs[1].body.to, two.out[0]], [['d_pix', 'd_lin'], '✓ Sent #132  → 2 devices · this once; default unchanged']);
    const text = await run(() => cli.send(['hi', 'there', '--to', 'Home Linux']));
    is('--to after the words: the words are the text', text.reqs[1].body.raw, 'hi there');
  }
  {
    const nope = await run(() => cli.send(['hello', '--to', 'Nope']));
    ok('an unknown name: refused, with where to look, and nothing is sent', nope.thrown?.includes(en.noDevice('Nope')) && !paths(nope).includes('POST /v1/ingest'), nope.thrown);
    const amb = await run(() => cli.send(['hello', '--to', 'Office web']));
    ok('a name two devices have: refused with both ids, nothing sent', amb.thrown?.includes('d_o1') && amb.thrown.includes('d_o2') && !paths(amb).includes('POST /v1/ingest'), amb.thrown);
    const self = await run(() => cli.send(['hello', '--to', 'My Mac']));
    ok('this machine: refused, nothing sent', self.thrown?.includes(en.thisMachine('My Mac')) && !paths(self).includes('POST /v1/ingest'), self.thrown);
    const flag = await run(() => cli.send(['hello', '--to']));
    is('--to with no value: says what is needed, no request at all', [flag.thrown, flag.reqs.length], [en.needTo, 0]);
  }
  // a default of chosen devices
  writeConfig({ ...base, targets: [{ id: 'd_pix', name: 'My Pixel' }, { id: 'd_lin', name: 'Home Linux' }] });
  {
    const r = await run(() => cli.send(['hello']));
    is('a chosen default: the list is read, the ids go in `to`', [paths(r), r.reqs[1].body.to], [['GET /v1/devices', 'POST /v1/ingest'], ['d_pix', 'd_lin']]);
    ok('…the receipt carries the time and not "this once"', clockLine(r.out[0]) && r.out[0].includes('→ 2 devices'), r.out.join('|'));
    const over = await run(() => cli.send(['hello', '--to', 'Home Linux']));
    is('--to beats the default for this send, and the default stays', [over.reqs[1].body.to, readConfig().targets.length], [['d_lin'], 2]);
  }
  writeConfig({ ...base, targets: [{ id: 'd_pix', name: 'My Pixel' }, { id: 'd_gone', name: 'Old Mac' }] });
  {
    const r = await run(() => cli.send(['hello']));
    is('a device in the default is gone: it leaves, and the send goes to the rest', [r.reqs[1].body.to, readConfig().targets], [['d_pix'], [{ id: 'd_pix', name: 'My Pixel' }]]);
    is('…said once', r.err, [en.defaultPruned('“Old Mac”')]);
    const again = await run(() => cli.send(['hello']));
    is('…and not said again', again.err, []);
  }
  writeConfig({ ...base, targets: [{ id: 'd_gone', name: 'Old Mac' }, { id: 'd_gone2', name: 'Old Pi' }] });
  {
    const r = await run(() => cli.send(['hello']));
    is('every device in the default is gone: back to all, said, and the send carries no `to`', [r.reqs[1].body.to, readConfig().targets, r.err], [undefined, undefined, [en.defaultPrunedAll('“Old Mac”, “Old Pi”')]]);
  }
  writeConfig({ ...base, targets: [{ id: 'd_pix', name: 'My Pixel' }] });
  {
    route = (r) => (r.path === '/v1/devices' ? answer(500, { error: 'X' }) : null);
    const r = await run(() => cli.send(['hello']));
    route = () => null;
    is('the list cannot be read: the send goes with the default as saved', [r.reqs.at(-1).body.to, r.thrown], [['d_pix'], null]);
  }
  {
    // Sending again and again in one process to a chosen default: the list of devices is asked for once, not before every send
    const twice = await run(async () => { await cli.send(['one']); await cli.send(['two']); });
    is('two sends in a row to a chosen default ask for the list of devices once', [twice.reqs.filter((x) => x.path === '/v1/devices').length, twice.reqs.filter((x) => x.path === '/v1/ingest').map((x) => x.body.to)], [1, [['d_pix'], ['d_pix']]]);
    const later = await run(async () => {
      const real = Date.now;
      await cli.send(['one']);
      Date.now = () => real() + 31_000;
      try { await cli.send(['two']); } finally { Date.now = real; }
    });
    is('…and after half a minute it is asked again', later.reqs.filter((x) => x.path === '/v1/devices').length, 2);
  }
  {
    route = (r) => (r.path === '/v1/ingest' ? answer(400, { error: 'TARGET_UNAVAILABLE', reason: 'gone', gone: ['d_pix'] }) : null);
    const fromDefault = await run(() => cli.send(['hello']));
    ok('the service says the default devices cannot receive: the reason, the default is back to all, and it says so', fromDefault.thrown === `${en.targetRefused.gone}\n${en.defaultReset}` && readConfig().targets === undefined, fromDefault.thrown);
    writeConfig({ ...base, targets: [{ id: 'd_pix', name: 'My Pixel' }] });
    const typed = await run(() => cli.send(['hello', '--to', 'My Pixel']));
    ok('the same refusal for a --to: the reason alone, and the default stays', typed.thrown === en.targetRefused.gone && readConfig().targets.length === 1, typed.thrown);
    route = () => null;
    writeConfig({ ...base, targets: [{ id: 'd_pix', name: 'My Pixel' }, { id: 'd_lin', name: 'Home Linux' }] });
    route = (r) => (r.path === '/v1/ingest' ? answer(200, { seq: 132, deduped: false, to: view(['d_pix']), dropped: ['d_lin'] }) : null);
    const dropped = await run(() => cli.send(['hello']));
    route = () => null;
    ok('a device the service left out is said, by name', dropped.err.includes(en.droppedNote('“Home Linux”')) && dropped.out[0].includes('→ My Pixel'), dropped.err.join('|'));
  }
  {
    route = (r) => (r.path === '/v1/ingest' ? answer(409, { error: 'DEDUPED', seq: 7 }) : null);
    const r = await run(() => cli.send(['hello']));
    route = () => null;
    is('sent a moment ago already: said as before', r.out, [en.alreadySent(7)]);
  }

  // files
  const dir = join(HOME, 'files');
  fsx.mkdirSync(dir, { recursive: true });
  const names = ['a.jpg', 'b.jpg', 'c.jpg', 'd.jpg', 'e.jpg', 'my pic.jpg'];
  for (const n of names) fsx.writeFileSync(join(dir, n), Buffer.alloc(2048, 1));
  const f = (...ns) => ns.map((n) => join(dir, n));
  const cwd = process.cwd();
  process.chdir(dir);                               // so the files are named as typed, and a resend line reads as one would type it
  writeConfig(base);
  {
    const one = await run(() => cli.send(['-f', 'a.jpg']));
    is('one file: the plain receipt, and no batch marks', [one.out.length, clockLine(one.out[0]), one.reqs.at(-1).query.get('group'), one.reqs.at(-1).query.get('to')], [1, true, null, null]);
    const to = await run(() => cli.send(['-f', 'a.jpg', '--to', 'My Pixel']));
    is('one file with --to: `to` is in the address; the receipt says this once', [to.reqs.at(-1).query.get('to'), to.out], ['d_pix', ['✓ Sent #140  → My Pixel · this once; default unchanged']]);
    const all = await run(() => cli.send(['-f', 'a.jpg', 'b.jpg', 'c.jpg', '--to', 'My Pixel']));
    const posts = all.reqs.filter((x) => x.path === '/v1/ingest/file');
    is('three files: one line each, then one receipt that says where', all.out, ['  ✓ a.jpg  2 KB', '  ✓ b.jpg  2 KB', '  ✓ c.jpg  2 KB', '✓ Sent 3 files  → My Pixel · this once; default unchanged']);
    ok('…all to the same devices, as one batch', posts.every((x) => x.query.get('to') === 'd_pix' && x.query.get('n') === '3') && new Set(posts.map((x) => x.query.get('group'))).size === 1 && posts.map((x) => x.query.get('i')).sort().join() === '1,2,3');
    const plain = await run(() => cli.send(['-f', 'a.jpg', 'b.jpg']));
    ok('several files, no choice: the time, and no `to`', /^✓ Sent 2 files {2}→ All · \d\d:\d\d$/.test(plain.out.at(-1)) && plain.reqs.filter((x) => x.path === '/v1/ingest/file').every((x) => x.query.get('to') === null), plain.out.join('|'));
  }
  {
    // b fails at once; a and c are slow and finish; d and e were never started
    route = (r) => {
      if (r.path !== '/v1/ingest/file') return null;
      const n = r.query.get('name');
      if (n === 'b.jpg') return answer(503, { error: 'BLOB_UNAVAILABLE' });
      if (n === 'a.jpg' || n === 'c.jpg') return new Promise((res) => setTimeout(() => res(answer(200, { seq: 150, to: null })), 40));
      return null;
    };
    const r = await run(() => cli.send(['-f', 'a.jpg', 'b.jpg', 'c.jpg', 'd.jpg', 'e.jpg', '--to', 'My Pixel']));
    route = () => null;
    is('a failure stops the batch: lines in the given order, what ran stays sent, what did not start is listed', [r.out, r.err.slice(0, 3)],
      [['  ✓ a.jpg  2 KB', '  ✓ c.jpg  2 KB'], [`  ✗ b.jpg  ${en.errors.BLOB_UNAVAILABLE}`, '  – d.jpg  not sent', '  – e.jpg  not sent']]);
    const gid = r.reqs.find((x) => x.path === '/v1/ingest/file').query.get('group');
    is('…then how many did not go, to whom, and the command that sends just those to the same devices, in the same batch', r.err[3],
      `${en.notSent(3)}  → My Pixel\n${en.resendUnsent(['b.jpg', 'd.jpg', 'e.jpg'], "'My Pixel'", { id: gid, n: 5, at: [2, 4, 5] })}`);
    ok('…the exit code is 1, and d and e were never asked', r.exit === 1 && !r.reqs.some((x) => ['d.jpg', 'e.jpg'].includes(x.query.get('name'))), JSON.stringify(r.exit));
    ok('…and the line can be pasted as it is: the files keep the batch they were in, and the place each had (the count stays 5)', r.err[3].endsWith(`dropit send -f b.jpg d.jpg e.jpg --to 'My Pixel' --batch ${gid} --of 5 --at 2,4,5`), r.err[3]);
    // the line, run: the three go into that batch at those places, as members of it (not as a new batch of three)
    const again = await run(() => cli.send(['-f', 'b.jpg', 'd.jpg', 'e.jpg', '--to', 'My Pixel', '--batch', gid, '--of', '5', '--at', '2,4,5']));
    const sent = again.reqs.filter((x) => x.path === '/v1/ingest/file');
    ok('sent again with it: the same batch id, the places 2, 4 and 5 of 5, to the same device', sent.length === 3 && sent.every((x) => x.query.get('group') === gid && x.query.get('n') === '5' && x.query.get('to') === 'd_pix')
      && sent.map((x) => x.query.get('i')).sort().join() === '2,4,5', JSON.stringify(sent.map((x) => [...x.query])));
    const lone = await run(() => cli.send(['-f', 'e.jpg', '--batch', gid, '--of', '5', '--at', '5']));
    const loneReq = lone.reqs.find((x) => x.path === '/v1/ingest/file');
    ok('…even one file alone goes in as the 5th of its batch', loneReq.query.get('group') === gid && loneReq.query.get('i') === '5' && loneReq.query.get('n') === '5' && clockLine(lone.out[0]), JSON.stringify([...loneReq.query, lone.out]));
  }
  {
    route = (r) => (r.path === '/v1/ingest/file' && r.query.get('name') === 'my pic.jpg' ? answer(503, { error: 'BLOB_UNAVAILABLE' }) : null);
    const r = await run(() => cli.send(['-f', 'a.jpg', 'my pic.jpg']));
    route = () => null;
    const gid2 = r.reqs.find((x) => x.path === '/v1/ingest/file').query.get('group');
    ok('a name with a space is quoted for the shell in the resend line; no --to when none was given; the batch is kept', r.err.at(-1).endsWith(`dropit send -f 'my pic.jpg' --batch ${gid2} --of 2 --at 2`) && r.err.at(-1).includes(en.notSent(1)), r.err.at(-1));
    writeConfig({ ...base, targets: [{ id: 'd_pix', name: 'My Pixel' }, { id: 'd_lin', name: 'Home Linux' }] });
    route = (r) => (r.path === '/v1/ingest/file' && r.query.get('name') === 'b.jpg' ? answer(503, { error: 'BLOB_UNAVAILABLE' }) : null);
    const d = await run(() => cli.send(['-f', 'a.jpg', 'b.jpg']));
    route = () => null;
    const gid3 = d.reqs.find((x) => x.path === '/v1/ingest/file').query.get('group');
    ok('with a chosen default, the resend line names the same devices by id (the default may change before it is used)', d.err.at(-1).endsWith(`dropit send -f b.jpg --to d_pix,d_lin --batch ${gid3} --of 2 --at 2`) && d.err.at(-1).includes('→ 2 devices'), d.err.at(-1));
    // a file name with control characters is not printed as they are, in the resend line either
    const odd = 'x\u001b[2Jy.jpg';
    fsx.writeFileSync(join(dir, odd), Buffer.alloc(2048, 1));
    route = (r) => (r.path === '/v1/ingest/file' && r.query.get('name') === odd ? answer(503, { error: 'BLOB_UNAVAILABLE' }) : null);
    const ctl = await run(() => cli.send(['-f', 'a.jpg', odd]));
    route = () => null;
    fsx.rmSync(join(dir, odd));
    ok('a file name with an escape character is shown with it replaced, in every line (the resend line too)', !/\u001b/.test(ctl.err.join('\n')) && ctl.err.at(-1).includes('?[2Jy.jpg'), JSON.stringify(ctl.err));
    const mism = await run(() => cli.send(['-f', 'a.jpg', 'b.jpg', '--batch', 'bGRPabc123', '--of', '5', '--at', '2']));
    ok('places that do not match the files: refused before anything is sent', mism.exit === 1 && mism.err.some((l) => l.includes(en.batchPlaces(2, 1))) && !mism.reqs.some((x) => x.path === '/v1/ingest/file'), JSON.stringify([mism.exit, mism.err]));
    const alone = await run(() => cli.send(['hello', '--batch', 'bGRPabc123', '--of', '5', '--at', '2']));
    ok('--batch with no files: said, nothing sent', alone.exit === 1 && alone.err.some((l) => l.includes(en.batchNeedsFiles)) && !alone.reqs.some((x) => x.path.startsWith('/v1/ingest')), JSON.stringify([alone.exit, alone.err]));
    const nofile = await run(() => cli.send(['-f', 'a.jpg', 'no\u001b[0mfile.jpg']));
    ok('…and so is one that is not there', nofile.exit === 1 && !/\u001b/.test(nofile.err.join('\n')) && nofile.err.join('\n').includes('no?[0mfile.jpg'), JSON.stringify(nofile.err));
  }
  {
    writeConfig({ ...base, targets: [{ id: 'd_pix', name: 'My Pixel' }] });
    route = (r) => (r.path === '/v1/ingest/file' ? answer(400, { error: 'TARGET_UNAVAILABLE', reason: 'gone', gone: ['d_pix'] }) : null);
    const r = await run(() => cli.send(['-f', 'a.jpg', 'b.jpg']));
    route = () => null;
    ok('the default devices cannot receive: the batch says so once, the default is back to all', r.err.at(-1).includes(en.defaultReset) && readConfig().targets === undefined && r.exit === 1, r.err.join('|'));
    writeConfig(base);
    route = (r) => (r.path === '/v1/ingest/file' ? answer(503, { error: 'BLOB_UNAVAILABLE' }) : null);
    const one = await run(() => cli.send(['-f', 'a.jpg']));
    route = () => null;
    is('one file that fails: just the reason, as before', [one.err, one.exit], [[en.errors.BLOB_UNAVAILABLE], 1]);
    route = (r) => (r.path === '/v1/ingest/file' ? answer(409, { error: 'DEDUPED', seq: 9 }) : null);
    const dup = await run(() => cli.send(['-f', 'a.jpg', 'b.jpg']));
    route = () => null;
    ok('files sent a moment ago already count as sent', dup.out[0].includes(en.alreadySent(9)) && dup.out.at(-1).startsWith('✓ Sent 2 files') && dup.exit === null, dup.out.join('|'));
    const missing = await run(() => cli.send(['-f', 'a.jpg', 'nope.jpg', '--to', 'My Pixel']));
    ok('a file that is not there is refused before anything is asked of the service', missing.exit === 1 && missing.reqs.length === 0 && missing.err[0] === en.fileNotFound('nope.jpg'), JSON.stringify(missing));
  }
  process.chdir(cwd);

  // dropit target
  writeConfig(base);
  {
    const r = await run(() => cli.targetCmd([]));
    is('target: the default is all, with how many devices there are and that it stays on this computer', r.out, ['Default  All (4 devices; new ones join automatically)   · this computer only']);
    const set = await run(() => cli.targetCmd(['My Pixel', 'Home Linux']));
    is('target <names>: set, said in the receipt\'s words, and that new devices are not added', set.out, ['✓ Default: 2 devices — My Pixel, Home Linux', '  new devices are not added automatically']);
    is('…kept in the config file with their names, and nothing was sent', [readConfig().targets, set.reqs.filter((x) => x.method !== 'GET').length], [[{ id: 'd_pix', name: 'My Pixel' }, { id: 'd_lin', name: 'Home Linux' }], 0]);
    ok('…the token and the rest of the file are still there', readConfig().token === 'dk_A' && readConfig().device_id === 'd_me');
    const show = await run(() => cli.targetCmd([]));
    is('target: shows the chosen ones, and how many of the devices that is', show.out, ['Default  My Pixel, Home Linux (2 of 4 devices; new ones are not added automatically)   · this computer only']);
    const one = await run(() => cli.targetCmd(['set', 'My Pixel']));
    is('target set <name>: one device', [one.out[0], readConfig().targets], ['✓ Default: My Pixel', [{ id: 'd_pix', name: 'My Pixel' }]]);
    const all = await run(() => cli.targetCmd(['all']));
    is('target all: back to every device, with no request at all (works offline)', [all.out, all.reqs.length, readConfig().targets], [['✓ Default: All'], 0, undefined]);
    await run(() => cli.targetCmd(['My Pixel']));
    const clear = await run(() => cli.targetCmd(['clear']));
    is('target clear: the same', [clear.out, clear.reqs.length, readConfig().targets], [['✓ Default: All'], 0, undefined]);
    const every = await run(() => cli.targetCmd(['d_pix', 'd_lin', 'd_o1', 'd_o2']));
    is('choosing every device there is = all, so devices that join later are included', [every.out, readConfig().targets], [['✓ Default: All'], undefined]);
    const amb = await run(() => cli.targetCmd(['Office web']));
    ok('a name two devices have: refused with both ids, the default unchanged', amb.thrown?.includes('d_o1') && amb.thrown.includes('d_o2') && readConfig().targets === undefined, amb.thrown);
    const none = await run(() => cli.targetCmd(['set']));
    is('target set with nothing: usage', none.thrown, en.targetUsage);
    const extra = await run(() => cli.targetCmd(['clear', 'x']));
    is('target clear with more: usage', extra.thrown, en.targetUsage);
    const zhSet = await run(() => cli.targetCmd(['My Pixel', 'Home Linux'], zh));
    is('target in Chinese', zhSet.out, ['✓ 默认投给 2 台：My Pixel、Home Linux', `  ${zh.notAutoAdded}`]);
    const zhShow = await run(() => cli.targetCmd([], zh));
    is('…and its display', zhShow.out, ['默认投给  My Pixel、Home Linux（4 台里的 2 台；新加入的设备不会自动并入）   · 只存在这台电脑']);
    writeConfig({ ...base, targets: [{ id: 'd_gone', name: 'Old Mac' }, { id: 'd_pix', name: 'My Pixel' }] });
    const pruned = await run(() => cli.targetCmd([]));
    is('target: a device that is gone is taken out of the default, said once', [pruned.err, readConfig().targets.map((e) => e.id)], [[en.defaultPruned('“Old Mac”')], ['d_pix']]);
  }

  // pairing again starts from "all"
  {
    writeConfig({ ...base, targets: [{ id: 'd_pix', name: 'My Pixel' }] });
    route = (r) => (r.path === '/v1/pair/claim' ? answer(200, { token: 'dk_B', device_id: 'd_new', user_id: 'u1' }) : null);
    await run(() => cli.pair(['K7M2QX']));
    route = () => null;
    const cfg = readConfig();
    ok('joining an account: the new token is kept, and the default devices of the earlier account are not', cfg.token === 'dk_B' && cfg.device_id === 'd_new' && cfg.targets === undefined, JSON.stringify(cfg));
  }

  ok('the help names --to and dropit target, in both languages', [en.usage, zh.usage].every((u) => u.includes('--to <') && u.includes('dropit target')));
  is('shellQuote: plain words stay bare, others in single quotes', [shellQuote('a.jpg'), shellQuote('my pic.jpg'), shellQuote("it's.jpg")], ['a.jpg', "'my pic.jpg'", `'it'\\''s.jpg'`]);
  globalThis.fetch = realFetch;
}

console.log(`\n${fail === 0 ? '✅' : '🛑'}  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
