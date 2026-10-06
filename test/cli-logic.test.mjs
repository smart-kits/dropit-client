/**
 * The CLI's decisions that don't need the network: which files `send -f` takes, when it refuses before
 * sending anything, what an error answer says, what `dropit me` prints, and when `dropit pair` may
 * create an account.
 *
 *   node test/cli-logic.test.mjs
 */
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { T, filesOf, filesProblem, groupsFor, errorText, meLine, pairAction, FILES_MAX, deviceKey, machineFacts } = require('../cli/dropit');
let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  cond ? pass++ : fail++;
  console.log(`${cond ? '  ok  ' : 'FAIL  '}${name}${cond ? '' : ' → ' + detail}`);
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
is('429 day → local time it comes back', errorText('RATE_LIMITED', { window: 'day', retry_after: 7200 }, 429, en, now),
  "Today's sends are used up — back at 00:15");
is('429 day, zh', errorText('RATE_LIMITED', { window: 'day', retry_after: 90 }, 429, zh, now),
  '今天的投递次数用完了，22:16 恢复');
is('429 without window → old sentence', errorText('RATE_LIMITED', { retry_after: 30 }, 429, en), en.errors.RATE_LIMITED);
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

console.log(`\n${fail === 0 ? '✅' : '🛑'}  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
