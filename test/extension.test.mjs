/**
 * The extension's decisions that don't need a browser: metadata and its size budget, link or text,
 * file names, context menus, batches, what Enter sends, error sentences, adding a device, and the QR code.
 *
 *   node test/extension.test.mjs
 */
import {
  META_BUDGET, TITLE_MAX, DESCRIPTION_MAX, FROM_URL_MAX, FILES_MAX, squash, clip, pageMeta, fromMeta, fitMeta, kindOf,
  fileNameOf, MENUS, FILE_EXT, groupsFor, leftOver, resumeGroups, intentOf, mb, fileSize, errorText, safeLink, upgradeLink, deviceFullHelp, endpointList, isPdf, seqRange, openableLink, hostOf, fileSizeWall, goneWall, paymentsOff,
} from '../extension/payload.js';
import { STRINGS } from '../extension/i18n.js';
import * as P from '../extension/pow.js';
import { accountTag, deviceSuffix, reconcile, toValue, canChoose, withDeviceCount, destLine, inListOrder, shortTarget } from '../extension/targets.js';
import * as R from '../extension/receive.js';
import { forgetToken, tooLarge } from '../extension/api.js';
import { pruneRecent, recentList, remember, RECENT_KEY } from '../extension/recent.js';
import { readFileSync } from 'node:fs';

let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  cond ? pass++ : fail++;
  console.log(`${cond ? '  ok  ' : 'FAIL  '}${name}${cond ? '' : ' → ' + detail}`);
};
const eq = (name, got, want) => ok(name, JSON.stringify(got) === JSON.stringify(want),
  `got ${JSON.stringify(got)}, want ${JSON.stringify(want)}`);
const size = (m) => new TextEncoder().encode(JSON.stringify(m)).length;

// E1 · page description: whitespace squashed, at most 300 characters
{
  const m = pageMeta({ title: '  A\ttitle \n', description: 'one\n\ntwo\t three   ' + 'x'.repeat(1000) });
  eq('E1 title squashed', m.title, 'A title');
  ok('E1 description squashed', m.description.startsWith('one two three x'), m.description.slice(0, 30));
  eq('E1 description ≤ 300 characters', Array.from(m.description).length, 300);
  ok('E1 a cut is marked with …', m.description.endsWith('…'));
}

// E2 · the longest CJK title and description fit whole; under a tighter budget the description gives way first
{
  const full = pageMeta({ title: '标'.repeat(250), description: '述'.repeat(400) });
  const m = fitMeta(full);
  ok('E2 within the budget', size(m) <= META_BUDGET, size(m));
  eq('E2 both kept whole at the real budget', m, full);
  const tight = fitMeta(full, 1000);
  ok('E2 tight budget respected', size(tight) <= 1000, size(tight));
  eq('E2 title kept whole (200)', Array.from(tight.title).length, 200);
  ok('E2 description shortened, not dropped', tight.description && Array.from(tight.description).length < 300);
}

// E3 · when the description is gone and it still doesn't fit, the title gives way
{
  const m = fitMeta({ title: '标'.repeat(200), from: { url: 'https://a.com/' + 'p'.repeat(1000), title: '来'.repeat(200) } }, 1200);
  ok('E3 within a tighter budget', size(m) <= 1200, size(m));
  ok('E3 still a valid object', m && typeof m === 'object');
}

// E4 · a source address over the limit is left out; the rest goes
{
  const long = 'https://a.com/?' + 'q'.repeat(FROM_URL_MAX);
  eq('E4 fromMeta refuses a long address', fromMeta(long, 'T'), null);
  eq('E4 fitMeta drops a long source', fitMeta({ title: 'T', from: { url: long, title: 'x' } }), { title: 'T' });
  eq('E4 fromMeta refuses non-http', fromMeta('javascript:alert(1)', 'x'), null);
  eq('E4 fromMeta keeps url and title', fromMeta('https://a.com/p', ' Page\ntitle '), { url: 'https://a.com/p', title: 'Page title' });
  eq('E4 fromMeta without a title', fromMeta('https://a.com/p', ''), { url: 'https://a.com/p' });
}

// E5 · no empty strings are sent; nothing at all is null
{
  eq('E5 empty title and description', pageMeta({ title: ' ', description: '' }), {});
  eq('E5 nothing → null', fitMeta({}), null);
  eq('E5 group survives alone', fitMeta({ title: '', group: { id: 'abcdef', i: 1, n: 2 } }), { group: { id: 'abcdef', i: 1, n: 2 } });
}

// E6 · cuts never leave half a character
{
  const s = clip('😀'.repeat(10), 5);
  ok('E6 no lone surrogate', !/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/.test(s), s);
  const m = fitMeta({ description: '😀'.repeat(300) }, 100);
  ok('E6 shrunk emoji text is well-formed', m && !/�/.test(m.description) && size(m) <= 100, JSON.stringify(m));
  eq('E6 squash', squash(' a \n\n b\t'), 'a b');
  eq('E6 control characters become spaces', squash('a\u0000b\u007fc'), 'a b c');
}

// E7 · a lone http(s) address is a link
eq('E7 kinds', ['https://a.com', '  https://a.com/x \n', '看 https://a.com', 'https://a.com\nhttps://b.com', 'ftp://x', ''].map(kindOf),
  ['url', 'url', 'text', 'text', 'text', 'text']);

// E8 · file names
eq('E8 path with extension, decoded, no query', fileNameOf('https://a.com/a/b/%E5%9B%BE.png?x=1'), '图.png');
eq('E8 directory', fileNameOf('https://a.com/a/b/'), null);
eq('E8 no extension', fileNameOf('https://a.com/download?id=3'), null);
eq('E8 Content-Disposition filename*', fileNameOf('https://a.com/x', "attachment; filename*=UTF-8''%E6%96%87.pdf"), '文.pdf');
eq('E8 Content-Disposition filename', fileNameOf('https://a.com/x', 'attachment; filename="report 1.pdf"'), 'report 1.pdf');
eq('E8 no path separators in a name', fileNameOf('https://a.com/x', 'attachment; filename="../../etc/passwd"'), '....etcpasswd');
eq('E8 data: address', fileNameOf('data:image/png;base64,AAAA'), null);

// E9 · context menus
{
  const by = Object.fromEntries(MENUS.map((m) => [m.id, m]));
  eq('E9 image link only for http(s)', by['image-link'].targetUrlPatterns, ['http://*/*', 'https://*/*']);
  eq('E9 video file only for http(s)', by.video.targetUrlPatterns, ['http://*/*', 'https://*/*']);
  eq('E9 audio file only for http(s)', by.audio.targetUrlPatterns, ['http://*/*', 'https://*/*']);
  ok('E9 image bytes for any source', !by.image.targetUrlPatterns);
  ok('E9 file links with and without a query', by['link-file'].targetUrlPatterns.includes('*://*/*.pdf')
    && by['link-file'].targetUrlPatterns.includes('*://*/*.pdf?*'));
  eq('E9 every extension has both patterns', by['link-file'].targetUrlPatterns.length, FILE_EXT.length * 2);
  // One "dropit" entry everywhere on the page; what fits opens under it, "Send this page" always last
  const page = MENUS.filter((m) => m.parentId === 'dropit');
  ok('E9 every page item sits under the one dropit entry', MENUS.filter((m) => !m.parentId && m.id !== 'dropit').every((m) => m.contexts.join() === 'action'));
  const offered = (ctx) => page.filter((m) => m.contexts.includes(ctx)).map((m) => m.id);
  eq('E9 selected text → text, page', offered('selection'), ['selection', 'page']);
  eq('E9 image → image, image link, page', offered('image'), ['image', 'image-link', 'page']);
  eq('E9 link → link, (linked file), page', offered('link'), ['link', 'link-file', 'page']);
  eq('E9 video → video file (real files only), page', offered('video'), ['video', 'page']);
  eq('E9 blank page → page', offered('page'), ['page']);
  ok('E9 the parent covers every child context', page.every((m) => m.contexts.every((c) => by.dropit.contexts.includes(c))));
  ok('E9 every menu title exists in both languages', MENUS.every((m) =>
    typeof STRINGS.en[m.title] === 'string' && typeof STRINGS.zh[m.title] === 'string'), MENUS.map((m) => m.title).join());
  eq('E9 ids are unique', new Set(MENUS.map((m) => m.id)).size, MENUS.length);
}

// E10–E12 · error sentences, with numbers from the answer
for (const [lang, t] of Object.entries(STRINGS)) {
  ok(`E10 ${lang} too large says the limit`, errorText('PAYLOAD_TOO_LARGE', { field: 'size', limit: 5242880 }, 413, t).includes('5 MB'));
  ok(`E10 ${lang} too large, fractional`, errorText('PAYLOAD_TOO_LARGE', { field: 'size', limit: 1572864 }, 413, t).includes('1.5 MB'));
  eq(`E10 ${lang} meta too large falls back`, errorText('PAYLOAD_TOO_LARGE', { field: 'meta' }, 413, t), t.errors.PAYLOAD_TOO_LARGE);
  const now = new Date(2026, 9, 3, 23, 30).getTime();
  ok(`E11 ${lang} daily limit says when it's back`, errorText('RATE_LIMITED', { window: 'day', retry_after: 1800 }, 429, t, now).includes('00:00'));
  ok(`E11b ${lang} signup limit says how many and when it opens again`, (() => { const x = errorText('SIGNUP_LIMITED', { retry_after: 1800, limit: 5 }, 429, t, now); return x.includes('5') && x.includes('00:00'); })());
  eq(`E11c ${lang} signup limit without a time is the plain sentence`, errorText('SIGNUP_LIMITED', {}, 429, t, now), t.errors.SIGNUP_LIMITED);
  eq(`E11 ${lang} hourly limit`, errorText('RATE_LIMITED', { window: 'hour', retry_after: 1800 }, 429, t), t.rateHour);
  eq(`E11 ${lang} older service`, errorText('RATE_LIMITED', { retry_after: 1800 }, 429, t), t.errors.RATE_LIMITED);
  eq(`E11 ${lang} today's uploads have their own sentence`, errorText('RATE_LIMITED', { window: 'day', field: 'uploads', retry_after: 1800 }, 429, t, now), t.rateDayUploads('00:00'));
  eq(`E11 ${lang} today's downloads have theirs`, errorText('RATE_LIMITED', { window: 'day', field: 'downloads', retry_after: 1800 }, 429, t, now), t.rateDayDownloads('00:00'));
  ok(`E11 ${lang} uploads and downloads are not called sends`, ![t.rateDayUploads('x'), t.rateDayDownloads('x')].includes(t.rateDay('x')));
  ok(`E12 ${lang} space full says when room frees up`, errorText('QUOTA_EXCEEDED', { field: 'bytes', frees_in: 7200 }, 507, t) === t.quotaBytesIn(t.span(7200)) && t.span(7200).includes('2'));
  ok(`E12 ${lang} queue full says when room frees up`, errorText('QUOTA_EXCEEDED', { field: 'items', frees_in: 90000 }, 507, t) === t.quotaItemsIn(t.span(90000)));
  eq(`E12 ${lang} …and without the time the plain sentence`, errorText('QUOTA_EXCEEDED', { field: 'bytes' }, 507, t), t.quotaBytes);
  eq(`E12 ${lang} a span under an hour`, t.span(600), lang === 'zh' ? '不到 1 小时' : 'under an hour');
  for (const why of ['per_day', 'queue', 'space', 'file_size', 'devices', 'realtime', 'uploads', 'downloads', 'kept']) ok(`E13 ${lang} an upgrade line for why=${why}`, typeof t.upgradeFor(why) === 'string' && t.upgradeFor(why) !== t.upgradeFor('nonsense'));
  ok(`E13 ${lang} an unknown reason still says something`, t.upgradeFor('nonsense').length > 0);
  eq(`E12 ${lang} queue full`, errorText('QUOTA_EXCEEDED', { field: 'items' }, 507, t), t.quotaItems);
  if (lang === 'en') {
    const up = 'https://dropit.example/upgrade?u=1&why=per_day&buy=year';
    eq('E15 upgrade link is tagged with the extension, and opens the plans page: a buy= the link carried is taken off', upgradeLink({ upgrade_url: up, why: 'per_day' }), 'https://dropit.example/upgrade?u=1&why=per_day&src=ext');
    eq('E15 buy= first, or before a #fragment, is taken off as well', [upgradeLink({ upgrade_url: 'https://dropit.example/upgrade?buy=year&u=1' }), upgradeLink({ upgrade_url: 'https://dropit.example/upgrade?buy=year#p' }), upgradeLink({ upgrade_url: 'https://dropit.example/upgrade?u=1&buy=year#p' })], ['https://dropit.example/upgrade?u=1&src=ext', 'https://dropit.example/upgrade?src=ext#p', 'https://dropit.example/upgrade?u=1&src=ext#p']);
    eq('E15 a link without a query gets ?src', upgradeLink({ upgrade_url: 'https://dropit.example/upgrade' }), 'https://dropit.example/upgrade?src=ext');
    eq('E15 src goes before a #fragment', [upgradeLink({ upgrade_url: 'https://dropit.example/upgrade#plans' }), upgradeLink({ upgrade_url: 'https://dropit.example/upgrade?a=1#plans' })],
      ['https://dropit.example/upgrade?src=ext#plans', 'https://dropit.example/upgrade?a=1&src=ext#plans']);
    eq('E15 the hourly limit is never sold, even if a link came', upgradeLink({ upgrade_url: up, window: 'hour' }), null);
    eq('E15 no link, no upgrade', upgradeLink({ why: 'per_day' }), null);
    eq('E15 an unsafe link stays dropped', upgradeLink({ upgrade_url: 'javascript:alert(1)' }), null);
    eq('E15 no error body at all', upgradeLink(undefined), null);
  }
  if (lang === 'en') for (const [u, want] of [["https://dropit.example/upgrade?u=1", "https://dropit.example/upgrade?u=1"], ["http://127.0.0.1:8799/upgrade", "http://127.0.0.1:8799/upgrade"], ["http://localhost:8780/x", "http://localhost:8780/x"], ["javascript:alert(1)", null], ["data:text/html,<b>x</b>", null], ["file:///etc/passwd", null], ["obsidian://open?vault=x", null], ["http://evil.example/up", null], ["//evil.example/x", null], ["", null], [null, null]]) eq(`E13 a link from the service: ${String(u).slice(0, 40)}`, safeLink(u), want);
  eq(`E12 ${lang} storage full`, errorText('QUOTA_EXCEEDED', { field: 'bytes' }, 507, t), t.quotaBytes);
  ok(`E14 ${lang} device limit says how a free account gets a slot back`, t.errors.DEVICE_LIMIT_REACHED !== t.errors.DEVICE_REVOKED && (lang === 'en' ? /remove one/i : /移除一台/).test(errorText('DEVICE_LIMIT_REACHED', { limit: 3 }, 403, t)));
  {
    const up = 'https://dropit.example/upgrade?why=devices';
    const withLink = deviceFullHelp({ limit: 2, upgrade_url: up, why: 'devices' }, 10, t);
    eq(`E16 ${lang} device-full help: link is the tagged upgrade link`, withLink.url, `${up}&src=ext`);
    ok(`E16 ${lang} device-full help: says how to free a slot and names the paid count`, withLink.text.includes(t.deviceFullRemove) && withLink.text.includes('10') && !!withLink.link);
    ok(`E16 ${lang} device-full help: no count known, no number written`, !/\d/.test(deviceFullHelp({ upgrade_url: up }, null, t).text));
    const bare = deviceFullHelp({ limit: 10 }, 10, t);
    ok(`E16 ${lang} device-full help: no link and no price for a paid account or payments off`, bare.url === null && bare.link === null && bare.text === t.deviceFullRemove && !/\d/.test(bare.text));
    eq(`E16 ${lang} device-full help: an unsafe link is dropped`, deviceFullHelp({ upgrade_url: 'javascript:alert(1)' }, 10, t).url, null);
  }
  ok(`E14 ${lang} no sentence carries a hard-coded plan number`, ['rateHour', 'quotaItems', 'quotaBytes'].every((k) => !/\d/.test(t[k])) && !/\d/.test(t.rateDay('00:00').replace('00:00', '')));
  ok(`E12 ${lang} unknown code`, errorText('NOPE', {}, 500, t).includes('500'));
}
eq('isPdf', [isPdf('https://a.com/r.PDF'), isPdf('https://a.com/r.pdf?x=1'), isPdf('https://a.com/pdf/123'), isPdf('x', 'application/pdf')], [true, true, false, true]);
eq('seqRange', [seqRange([71]), seqRange([71, 72, 73]), seqRange([])], ['#71', '#71–#73', '']);
eq('mb', [mb(5 << 20), mb(100 << 20), mb(1.5 * 1048576)], ['5 MB', '100 MB', '1.5 MB']);
eq('fileSize', [fileSize(11), fileSize(14 * 1024), fileSize(2.34 * 1048576)], ['11 B', '14 KB', '2.3 MB']);

// E13 · batches
{
  eq('E13 one thing has no batch', groupsFor(1), [null]);
  const two = groupsFor(2, 'abcdefgh');
  eq('E13 two', two, [{ id: 'abcdefgh', i: 1, n: 2 }, { id: 'abcdefgh', i: 2, n: 2 }]);
  eq('E13 at most 10 files at a time, as in every client', FILES_MAX, 10);
  const max = groupsFor(16);
  ok('E13 sixteen share one id', max.length === 16 && new Set(max.map((g) => g.id)).size === 1 && max[15].i === 16);
  ok('E13 random id fits the service rule', /^[A-Za-z0-9_-]{6,32}$/.test(max[0].id), max[0].id);
  let threw = null;
  try { groupsFor(17); } catch (err) { threw = err.code; }
  eq('E13 seventeen is refused before sending', threw, 'BATCH_TOO_LARGE');
}

// E13b · a send that stopped part way is sent again into the same batch
{
  const fileA = { name: 'a.png' }, fileB = { name: 'b.png' }, fileC = { name: 'c.png' };
  const keys = ['words', fileA, fileB, fileC];
  const groups = groupsFor(4, 'abcdefgh');
  eq('E13b nothing left when the first one failed (nothing went): a plain retry', leftOver(groups, keys, 0), null);
  eq('E13b one thing is no batch', leftOver(groupsFor(1), ['x'], 0), null);
  const left = leftOver(groups, keys, 2);                 // the words and a.png went; b.png failed; c.png was never started
  eq('E13b what is left: the batch, its size, the place each had', [left.id, left.n, left.places], ['abcdefgh', 4, [3, 4]]);
  eq('E13b the same things again: same id, same places, same count (not a new batch of two)', resumeGroups(left, [fileB, fileC]),
    [{ id: 'abcdefgh', i: 3, n: 4 }, { id: 'abcdefgh', i: 4, n: 4 }]);
  eq('E13b one thing left is still the 4th of its batch', resumeGroups(leftOver(groups, keys, 3), [fileC]), [{ id: 'abcdefgh', i: 4, n: 4 }]);
  eq('E13b something else added: a new batch', resumeGroups(left, [fileB, fileC, { name: 'd.png' }]), null);
  eq('E13b one taken away: a new batch', resumeGroups(left, [fileC]), null);
  eq('E13b the same count, another file: a new batch', resumeGroups(left, [fileB, { name: 'x.png' }]), null);
  eq('E13b nothing remembered: a new batch', resumeGroups(null, [fileB]), null);
  const t2 = leftOver(groupsFor(2, 'zzzzzzzz'), ['edited text', fileA], 1);
  eq('E13b a text is the same by its words: edited, it is not the same thing', [resumeGroups(t2, [fileA]) !== null, resumeGroups(leftOver(groupsFor(3, 'zzzzzzzz'), ['t', 'u', fileA], 1), ['t', fileA])], [true, null]);
}

// E16 · a saved default meets the devices there are now: only what is gone leaves
{
  const dev = (...ids) => ids.map((id) => ({ device_id: id }));
  eq('E16 a device gone: it leaves, the rest stay, one is counted', (({ to, gone, trimmed, reset, changed }) => ({ to, gone, trimmed, reset, changed }))(reconcile(['a', 'b', 'x'], dev('a', 'b', 'c'))),
    { to: ['a', 'b'], gone: 1, trimmed: true, reset: false, changed: true });
  eq('E16 a chosen set that names every device stays a chosen set (a device added later is not in it), and nothing is said', (({ to, trimmed, reset, changed }) => ({ to, trimmed, reset, changed }))(reconcile(['a', 'b'], dev('a', 'b'))),
    { to: ['a', 'b'], trimmed: false, reset: false, changed: false });
  eq('E16 …even when one of them is removed and the rest are everything that is left: still a chosen set, and it is said', (({ to, gone, trimmed }) => ({ to, gone, trimmed }))(reconcile(['a', 'b', 'x'], dev('a', 'b'))),
    { to: ['a', 'b'], gone: 1, trimmed: true });
  eq('E16 all of them gone: back to every device, said', (({ to, gone, trimmed, reset, changed }) => ({ to, gone, trimmed, reset, changed }))(reconcile(['x', 'y'], dev('a'))),
    { to: null, gone: 2, trimmed: false, reset: true, changed: true });
  eq('E16 nothing saved: nothing to say or write', (({ to, gone, trimmed, reset, changed }) => ({ to, gone, trimmed, reset, changed }))(reconcile(undefined, dev('a'))),
    { to: null, gone: 0, trimmed: false, reset: false, changed: false });
  eq('E16 an empty list that was stored is cleaned away without a word', (({ trimmed, reset, changed }) => ({ trimmed, reset, changed }))(reconcile([], dev('a'))), { trimmed: false, reset: false, changed: true });
  eq('E16 ticking every device in the panel still means every device', toValue(new Set(['a', 'b']), dev('a', 'b')), null);
  eq('E16 the notice says how many, in both languages', [STRINGS.en.targetsTrimmed(1), STRINGS.en.targetsTrimmed(3), STRINGS.zh.targetsTrimmed(3), STRINGS.en.targetsReset(2)],
    ['A device in your default was removed, so it was taken off the list', '3 devices in your default were removed, so they were taken off the list', '默认里有 3 台设备已被移除，已从默认里去掉', 'The devices in your default were removed. The default is back to All']);
}

// E17 · what was sent lately is kept a week, and then it is gone from storage, not only from the list
{
  const realChrome = globalThis.chrome;
  const store = {};
  globalThis.chrome = { storage: { local: {
    get: async (k) => ({ ...(k in store ? { [k]: JSON.parse(JSON.stringify(store[k])) } : {}) }),
    set: async (o) => { for (const [k, v] of Object.entries(o)) store[k] = JSON.parse(JSON.stringify(v)); },
    remove: async (k) => { delete store[k]; },
  } } };
  const DAY = 864e5, now = Date.now();
  try {
    store[RECENT_KEY] = [{ seq: 5, kind: 'text', label: 'new', at: now - DAY }, { seq: 4, kind: 'text', label: 'old', at: now - 8 * DAY }, { seq: 3, kind: 'text', label: 'older', at: now - 30 * DAY }];
    eq('E17 the list leaves out what is over a week old', (await recentList()).map((e) => e.seq), [5]);
    eq('E17 …but storage still holds it until it is pruned', store[RECENT_KEY].length, 3);
    eq('E17 pruning returns what is left', (await pruneRecent()).map((e) => e.seq), [5]);
    eq('E17 …and storage no longer holds the rest', store[RECENT_KEY].map((e) => e.seq), [5]);
    store[RECENT_KEY] = [{ seq: 1, kind: 'text', label: 'x', at: now - 9 * DAY }];
    await pruneRecent();
    ok('E17 when nothing is left the key is removed', !(RECENT_KEY in store), JSON.stringify(store));
    let writes = 0;
    const set = globalThis.chrome.storage.local.set, remove = globalThis.chrome.storage.local.remove;
    globalThis.chrome.storage.local.set = async (o) => { writes++; return set(o); };
    globalThis.chrome.storage.local.remove = async (k) => { writes++; return remove(k); };
    store[RECENT_KEY] = [{ seq: 6, kind: 'text', label: 'fresh', at: now - 1000 }];
    await pruneRecent();
    await pruneRecent();
    eq('E17 nothing over a week old: nothing is written', writes, 0);
    delete store[RECENT_KEY];
    eq('E17 nothing stored: nothing is written and nothing comes back', [await pruneRecent(), writes], [[], 0]);
    store[RECENT_KEY] = 'not a list';
    await pruneRecent();
    ok('E17 something that is not a list is cleaned away', !(RECENT_KEY in store));
    await remember({ seq: 9, kind: 'text', label: 'now', text: 'now' });
    ok('E17 a new entry is kept, and remember also drops what is over a week old', store[RECENT_KEY].length === 1 && store[RECENT_KEY][0].seq === 9);
  } finally { globalThis.chrome = realChrome; }
}

// E14 · what Enter sends
{
  const page = { url: 'https://a.com/p', title: 'Page' };
  const file = { name: 'shot.png' };
  eq('E14 empty → this page', intentOf({ page }), { type: 'page' });
  eq('E14 whitespace only → this page', intentOf({ text: '  \n', page }), { type: 'page' });
  eq('E14 files only', intentOf({ files: [file], page }), { type: 'batch', text: '', files: [file], from: null });
  eq('E14 prefilled selection → text with source', intentOf({ text: 'quote', fromSelection: true, page }),
    { type: 'text', kind: 'text', from: { url: 'https://a.com/p', title: 'Page' } });
  eq('E14 typed or pasted → text without source', intentOf({ text: 'note', page }), { type: 'text', kind: 'text', from: null });
  eq('E14 a pasted address is a link', intentOf({ text: 'https://b.com', page }), { type: 'text', kind: 'url', from: null });
  eq('E14 selection on a page without an address', intentOf({ text: 'q', fromSelection: true, page: { url: 'chrome://x' } }),
    { type: 'text', kind: 'text', from: null });
  eq('E14 files and selected text', intentOf({ text: 'q', files: [file], fromSelection: true, page }).from, { url: 'https://a.com/p', title: 'Page' });
  eq('E14 empty on a PDF → the PDF file', intentOf({ page: { url: 'https://a.com/x/report.pdf' } }), { type: 'pdf' });
  eq('E14 empty in the PDF viewer (by content type) → the PDF file', intentOf({ page: { url: 'https://a.com/get?id=1', contentType: 'application/pdf' } }), { type: 'pdf' });
  eq('E14 text typed on a PDF tab is still text', intentOf({ text: 'note', page: { url: 'https://a.com/r.pdf' } }).type, 'text');
}

// Service addresses: what worked before first, the built-in ones always still tried
{
  const D = ['https://dropit.smart-kits.xyz'];
  eq('endpoints: nothing stored → built-in', endpointList(undefined, D), D);
  eq('endpoints: an old stored address no longer strands the browser', endpointList(['https://old.example'], D), ['https://old.example', ...D]);
  eq('endpoints: no duplicates, order kept', endpointList(['https://dropit.smart-kits.xyz', 'https://b.example'], D), ['https://dropit.smart-kits.xyz', 'https://b.example']);
}

// A dropped connection (a flaky proxy) is tried again — but never for joining or creating an account
{
  globalThis.chrome = { storage: { local: { get: async () => ({ token: 't', endpoints: ['https://a.example'] }), set: async () => {}, remove: async () => {} } } };
  const { api } = await import('../extension/api.js');
  const seen = [];
  let drops = 2;
  globalThis.fetch = async (url, init) => {
    seen.push(`${init.method} ${url}`);
    if (drops-- > 0) throw new TypeError('Failed to fetch');
    return new Response(JSON.stringify({ plan: 'free' }), { status: 200 });
  };
  const me = await api('GET', '/v1/me');
  ok('a read gets through after two dropped connections, on the same address', me.plan === 'free'
    && seen.length === 3 && seen.every((x) => x === 'GET https://a.example/v1/me'), JSON.stringify(seen));
  seen.length = 0;
  globalThis.fetch = async (url, init) => { seen.push(`${init.method} ${url}`); throw new TypeError('Failed to fetch'); };
  let err = null;
  try { await api('POST', '/v1/accounts', {}, { auth: false }); } catch (e) { err = e; }
  ok('creating an account is sent once per address, never repeated', err?.code === 'OFFLINE'
    && seen.length === 2 && seen[0] === 'POST https://a.example/v1/accounts', JSON.stringify(seen));
}

// A request without a file gives up after a while; a file upload is never cut short
{
  const { api } = await import('../extension/api.js');
  const signals = [];
  globalThis.fetch = async (url, init) => { signals.push(init.signal ?? null); return new Response('{}', { status: 200 }); };
  await api('GET', '/v1/me');
  await api('POST', '/v1/ingest/file?x=1', new Blob(['abc'], { type: 'text/plain' }), { type: 'text/plain' });
  ok('TO1 a JSON request carries a timeout signal', signals[0] instanceof AbortSignal);
  ok('TO1 a file upload carries none', signals[1] === null);
  let tries = 0;
  globalThis.fetch = async () => { tries++; throw new DOMException('timed out', 'TimeoutError'); };
  let err = null;
  try { await api('POST', '/v1/accounts', {}, { auth: false }); } catch (e) { err = e; }
  ok('TO2 a timed-out request is a network error: tried on the next address, then OFFLINE', err?.code === 'OFFLINE' && tries === 2, `${err?.code} ${tries}`);
}

// A key that was replaced while a request was in flight is not wiped by that request's late rejection
{
  const { api } = await import('../extension/api.js');
  const mem = { token: 'dk_new', endpoints: ['https://a.example'] };
  const real = globalThis.chrome;
  let reads = 0;
  globalThis.chrome = { storage: { local: {
    get: async () => (reads++ === 0 ? { ...mem, token: 'dk_old' } : { ...mem }),
    set: async (o) => Object.assign(mem, o), remove: async (ks) => { for (const k of [].concat(ks)) delete mem[k]; },
  } } };
  globalThis.fetch = async () => new Response(JSON.stringify({ error: 'INVALID_TOKEN' }), { status: 401 });
  try { await api('GET', '/v1/me'); } catch { /* expected */ }
  ok('FT1 a rejection of an older key leaves the newer one saved', mem.token === 'dk_new' && !('previous_token' in mem), JSON.stringify(mem));
  globalThis.chrome = real;
}

// Opening the popup again soon does not pull again; Refresh pressed twice quickly pulls once
{
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  ok('RP1 the open pull is skipped within 20 seconds of the last refresh', /OPEN_PULL_GAP_MS = 20_000/.test(popup) && /RECV_AT_KEY/.test(popup));
  ok('RP1 the manual refresh has a 3 second cooldown that shows no error', /MANUAL_PULL_GAP_MS = 3_000/.test(popup) && /MANUAL_PULL_GAP_MS\) return;/.test(popup));
}

// E30 · device fingerprint: the same facts give the same key; another kind of client, or other facts, another key
{
  const { deviceKey, browserFacts } = await import('../extension/device.js');
  const facts = ['Google Chrome', 'MacIntel', 10, 8, 'Asia/Shanghai', 'Apple M1 Pro'];
  const a = await deviceKey('extension', facts);
  ok('E30 a 64-character hex hash, never the facts', /^[0-9a-f]{64}$/.test(a) && !a.includes('Mac'), a);
  eq('E30 the same facts give the same key', await deviceKey('extension', [...facts]), a);
  ok('E30 another kind of client gives another key', await deviceKey('web', facts) !== a);
  ok('E30 another computer gives another key', await deviceKey('extension', [...facts.slice(0, 5), 'Apple M2']) !== a);
  const nav = { userAgentData: { brands: [{ brand: 'Not)A;Brand' }, { brand: 'Chromium' }, { brand: 'Google Chrome' }] }, platform: 'MacIntel', hardwareConcurrency: 10 };
  const got = browserFacts(nav);
  ok('E30 the browser brand without version or filler brands', got[0] === 'Google Chrome' && got[1] === 'MacIntel' && got[2] === 10, JSON.stringify(got));
  eq('E30 what the browser doesn\'t offer is left out, not an error', browserFacts({}).slice(0, 4), ['', '', '', '']);
}

// E32 · joining says what it sends before either button, and the buttons say "Agree and …" (store policy: consent by a clear action)
{
  for (const [lang, s] of Object.entries(STRINGS)) {
    ok(`E32 ${lang}: the join note names the fingerprint and how long items stay`, /fingerprint|指纹/.test(s.consent) && /1 day|1 天/.test(s.consent), s.consent);
    ok(`E32 ${lang}: both buttons ask for agreement`, /^(Agree|同意)/.test(s.html.claim) && /^(Agree|同意)/.test(s.html.create), `${s.html.claim} / ${s.html.create}`);
    ok(`E32 ${lang}: a link to the privacy page`, !!s.privacyLink);
  }
  const { PRIVACY_URL } = await import('../extension/api.js').catch(() => ({}));
  ok('E32 the privacy page is on the service\'s own address', !PRIVACY_URL || PRIVACY_URL.endsWith('/privacy'), String(PRIVACY_URL));
}

// E31 · replaced by joining again in this browser: said as such, not as "removed"
{
  const t = { ...STRINGS.en };
  eq('E31 DEVICE_REVOKED + replaced', errorText('DEVICE_REVOKED', { reason: 'replaced' }, 403, t), t.revokedReplaced);
  eq('E31 DEVICE_REVOKED alone stays "removed"', errorText('DEVICE_REVOKED', {}, 403, t), t.errors.DEVICE_REVOKED);
}

// E33 · what this browser's key may do: recorded on joining; an older key is asked about once
{
  const { scopeFromProbe, canAddDevices, canReceive, canTurnOnReceiving } = await import('../extension/pairing.js');
  eq('E33 the device list answered → full', scopeFromProbe(null), 'full');
  eq('E33 refused for scope → send-only', scopeFromProbe({ code: 'SCOPE_INSUFFICIENT', status: 403 }), 'ingest_only');
  eq('E33 offline → unknown, ask again next time', scopeFromProbe({ code: 'OFFLINE' }), null);
  eq('E33 a server error → unknown, not send-only', scopeFromProbe({ code: undefined, status: 500 }), null);
  ok('E33 only a full key offers adding a device', canAddDevices('full') && !canAddDevices('ingest_only') && !canAddDevices(null) && !canAddDevices(undefined));
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  ok('E33 creating the account records full, joining with a code asks for full and records full',
    /'\/v1\/accounts'[\s\S]{0,200}'full'\)/.test(popup) && /'\/v1\/pair\/claim',\s*\{[^}]*scope: 'full'[^}]*\}[^)]*\)[^;]*'full'\);/.test(popup));
  ok('E33 joining with a code never asks for or records a send-only key (the one mention left is the unable-to-read fallback)', (popup.match(/ingest_only/g) ?? []).length === 1);
  ok('E33 a browser that joined with a code is a full one at once: it receives and may add devices, and is not offered "Receive on this browser"',
    canReceive('full') && canAddDevices('full') && !canTurnOnReceiving('full'));
  ok('E33 an older send-only browser keeps the old way in: the entry, and the fallback when the key turns out unable to read',
    canTurnOnReceiving('ingest_only') && /SCOPE_INSUFFICIENT[\s\S]{0,200}store\.set\(\{ scope: 'ingest_only' \}\)/.test(popup));
  const api = readFileSync(new URL('../extension/api.js', import.meta.url), 'utf8');
  ok('E33 forgetting the key forgets what it could do', /remove\(\[[^\]]*'scope'/.test(api));
}

// E34 · all slots in use: the new device takes the place of one, picked before the code is made
{
  const { slotPlan } = await import('../extension/pairing.js');
  const devices = [
    { device_id: 'me', name: 'Browser', last_seen_at: 1 },
    { device_id: 'phone', name: 'iPhone', last_seen_at: 500 },
    { device_id: 'obs', name: 'Obsidian', last_seen_at: 900 },
  ];
  eq('E34 room left → no pick', slotPlan({ devices_used: 2, devices_limit: 3 }, devices.slice(0, 2), 'me').pick, null);
  eq('E34 no limit (paid) → no pick', slotPlan({ devices_used: 9, devices_limit: null }, devices, 'me').pick, null);
  eq('E34 full → the one idle longest, never this browser', slotPlan({ devices_used: 3, devices_limit: 3 }, devices, 'me').pick?.device_id, 'phone');
  eq('E34 full → the one picked', slotPlan({ devices_used: 3, devices_limit: 3 }, devices, 'me', 'obs').pick?.device_id, 'obs');
  eq('E34 a pick that is no longer there → back to the default', slotPlan({ devices_used: 3, devices_limit: 3 }, devices, 'me', 'gone').pick?.device_id, 'phone');
  eq('E34 a device never active counts as idle longest', slotPlan({ devices_used: 3, devices_limit: 3 },
    [...devices, { device_id: 'old', name: 'Old', last_seen_at: null }], 'me').pick?.device_id, 'old');
  eq('E34 over the limit (plan went down) → still a pick', slotPlan({ devices_used: 4, devices_limit: 3 }, devices, 'me').pick?.device_id, 'phone');
  eq('E34 only this browser left → nothing to replace', slotPlan({ devices_used: 1, devices_limit: 1 }, devices.slice(0, 1), 'me').pick, null);
  eq('E34 the list offered leaves this browser out', slotPlan({ devices_used: 3, devices_limit: 3 }, devices, 'me').others.map((d) => d.device_id), ['phone', 'obs']);
}

// E35 · the QR code holds the service's page with the code filled in, as the web inbox and Obsidian show it
{
  const { pairLink } = await import('../extension/pairing.js');
  eq('E35 pairing link', pairLink('https://dropit.smart-kits.xyz', 'K7M2QX'), 'https://dropit.smart-kits.xyz/?pair=K7M2QX');
  eq('E35 a trailing slash on the address is not doubled', pairLink('http://127.0.0.1:8799/', 'ABC234'), 'http://127.0.0.1:8799/?pair=ABC234');
}

// E36 · the countdown: every second; the device list every 4th; everything stops when the code expires
{
  const { pairTick, clock, newcomer, POLL_EVERY } = await import('../extension/pairing.js');
  const exp = 300_000;
  const ticks = Array.from({ length: 301 }, (_, k) => pairTick(k * 1000, exp, k));
  eq('E36 checks every 4th second', ticks.slice(0, 13).map((x) => x.poll), [false, false, false, false, true, false, false, false, true, false, false, false, true]);
  eq('E36 at most 74 checks in 5 minutes', ticks.filter((x) => x.poll).length, 74);
  ok('E36 expired at the deadline: no check, stop', ticks[300].expired && !ticks[300].poll && ticks[300].left === 0);
  ok('E36 a late tick past the deadline still counts as expired', pairTick(exp + 5000, exp, 1000).expired);
  ok('E36 reopened: checks straight away', pairTick(0, exp, POLL_EVERY).poll);
  eq('E36 clock', [clock(300_000), clock(299_001), clock(65_000), clock(9_000), clock(1)], ['5:00', '5:00', '1:05', '0:09', '0:01']);
  const before = [{ device_id: 'me', name: 'Browser' }, { device_id: 'phone', name: 'iPhone' }];
  eq('E36 nothing new → keep watching', newcomer(before, before), null);
  eq('E36 a new device → which one', newcomer(before, [...before, { device_id: 'obs', name: 'Obsidian' }]), { joined: { device_id: 'obs', name: 'Obsidian' }, gone: null });
  eq('E36 one in place of another (count unchanged) → both named',
    newcomer(before, [before[0], { device_id: 'pixel', name: 'Pixel' }]), { joined: { device_id: 'pixel', name: 'Pixel' }, gone: { device_id: 'phone', name: 'iPhone' } });
  eq('E36 a device removed meanwhile is not a join', newcomer(before, [before[0]]), null);
}

// E37 · "active 5 min ago" in the replace picker, in the web inbox's words
{
  const { ago } = await import('../extension/pairing.js');
  const now = 10 * 86_400_000;
  for (const [lang, s] of Object.entries(STRINGS)) {
    eq(`E37 ${lang}`, [ago(now - 10_000, now, s), ago(now - 5 * 60_000, now, s), ago(now - 3 * 3_600_000, now, s)],
      [s.justNow, s.minsAgo(5), s.hoursAgo(3)]);
  }
  ok('E37 older than a day → a date', /\d/.test(ago(0, now, STRINGS.en, 'en')));
}

// E38 · the QR encoder. Fingerprints of whole matrices, each checked bit for bit against the npm `qrcode`
// package (1.5.4, byte mode, level L, same version, every one of the 8 masks) and decoded back with `jsQR`
// (1.4.0) on 2026-10-07. If a change moves one, run that comparison again — don't just update the value.
{
  const { createHash } = await import('node:crypto');
  const { QR } = await import('../extension/qr.js');
  const fp = (m) => createHash('sha256').update(m.map((r) => r.join('')).join('')).digest('hex').slice(0, 16);
  const GOLDEN = [
    ['A', 21, 'c70a942b285013ce'],
    ['中文也要能编码', 25, '5ca9524f316bc3d1'],
    ['x'.repeat(60), 33, '46ce7fb396051eff'],
    ['https://dropit.smart-kits.xyz/?pair=9G08F6', 29, '0be44e09ec941a57'],
    ['https://dropit.smart-kits.xyz/?pair=K7M2QX', 29, '0ff63f3119bac0ad'],
    ['y'.repeat(106), 37, '69cdac05c7ca2618'],
  ];
  for (const [text, size, hash] of GOLDEN) {
    const m = QR.matrix(text);
    ok(`E38 ${JSON.stringify(text).slice(0, 34)} ${size}×${size}`, m.length === size && fp(m) === hash, `${m.length}×${m.length} ${fp(m)}`);
  }
  const link = 'https://dropit.smart-kits.xyz/?pair=9G08F6';
  const eight = createHash('sha256').update([0, 1, 2, 3, 4, 5, 6, 7].map((mask) => QR.matrix(link, { mask }).map((r) => r.join('')).join('')).join('|')).digest('hex').slice(0, 16);
  eq('E38 all 8 masks of the pairing link', eight, '69b597076e7cf997');
  const m = QR.matrix('A');
  const finder = (br, bc) => [0, 6].every((d) => m[br][bc + d] === 1 && m[br + d][bc] === 1) && m[br + 1][bc + 1] === 0 && m[br + 3][bc + 3] === 1;
  ok('E38 three finder patterns', finder(0, 0) && finder(0, 14) && finder(14, 0));
  let threw = false;
  try { QR.matrix('y'.repeat(107)); } catch { threw = true; }
  ok('E38 too long for version 5 → an error, not a wrong code', threw);
}

// V · test/vectors/payload.json: the same cases the phone apps' shared code is tested against.
// The extension is the reference; if a rule changes here, regenerate the expected values there.
{
  const { deviceKey } = await import('../extension/device.js');
  const V = JSON.parse(readFileSync(new URL('./vectors/payload.json', import.meta.url), 'utf8'));
  eq('V constants', V.constants, { META_BUDGET, TITLE_MAX, DESCRIPTION_MAX, FROM_URL_MAX, FILES_MAX, GROUP_MAX: 16 });
  for (const [x, want] of V.kindOf) eq(`V kindOf ${JSON.stringify(x)}`, kindOf(x), want);
  for (const [x, want] of V.squash) eq(`V squash ${JSON.stringify(x)}`, squash(x), want);
  for (const [x, n, want] of V.clip) eq(`V clip ${n}`, clip(x, n), want);
  for (const [x, want] of V.pageMeta) eq('V pageMeta', pageMeta(x), want);
  for (const [u, t, want] of V.fromMeta) eq('V fromMeta', fromMeta(u, t), want);
  V.fitMeta.forEach(([m, limit, want], k) => eq(`V fitMeta ${k}`, fitMeta(m, limit), want));
  for (const [n, id, want] of V.groupsFor) eq(`V groupsFor ${n}`, groupsFor(n, id), want);
  for (const n of V.groupsForRefused) {
    let code = null;
    try { groupsFor(n); } catch (err) { code = err.code; }
    eq(`V groupsFor ${n} refused`, code, 'BATCH_TOO_LARGE');
  }
  for (const [x, want] of V.seqRange) eq('V seqRange', seqRange(x), want);
  for (const [x, want] of V.mb) eq('V mb', mb(x), want);
  for (const [x, want] of V.fileSize) eq('V fileSize', fileSize(x), want);
  for (const [stored, defaults, want] of V.endpointList) eq('V endpointList', endpointList(stored, defaults), want);
  // A recording `t`: each sentence comes back as its key and arguments, so the choice is checked, not the wording
  const rec = { errors: Object.fromEntries(Object.keys(STRINGS.en.errors).map((k) => [k, `errors.${k}`])) };
  for (const [k, v] of Object.entries(STRINGS.en)) if (k !== 'errors') rec[k] = typeof v === 'function' ? (...a) => `${k}(${a.join(',')})` : k;
  for (const c of V.errorText) {
    const [d, tm] = c.now_local.split('T');
    const now = new Date(`${d}T${tm}:00`).getTime();
    eq(`V errorText ${c.code} ${JSON.stringify(c.data)}`, errorText(c.code ?? undefined, c.data, c.status, rec, now), c.expect);
  }
  eq('V every error code has a sentence', V.errorCodes, Object.keys(STRINGS.en.errors));
  for (const [kind, facts, want] of V.deviceKey) eq(`V deviceKey ${kind}`, await deviceKey(kind, facts), want);
}

// ── account-creation puzzle: the shared vectors, then when a client may solve and resend ──
{
  const hex = (b) => Buffer.from(b).toString('hex');
  const POW = JSON.parse(readFileSync(new URL('./vectors/pow.json', import.meta.url), 'utf8'));
  const nodeCrypto = (await import('node:crypto'));
  const ref = (c, dk, nonce) => nodeCrypto.createHash('sha256').update(`${c.id}|${c.salt}|${dk ? nodeCrypto.createHash('sha256').update(dk).digest('hex') : '-'}|${nonce}`).digest();
  for (const c of POW.hash.cases) {
    const d = P.tryNonce(c, c.device_key, c.nonce);
    ok(`P hash ${c.name}`, hex(d) === c.digest_hex, hex(d));
    ok(`P zeros ${c.name}`, P.zeroBits(d) === c.leading_zero_bits, String(P.zeroBits(d)));
    ok(`P verdict ${c.name}`, (P.zeroBits(d) >= c.bits) === c.valid);
    ok(`P key hash ${c.name}`, P.keyHash(c.device_key) === c.dkh);
  }
  for (const c of POW.leadingZeroBits) eq(`P leading zero bits ${c.digest_hex.slice(0, 8)}`, P.zeroBits(Buffer.from(c.digest_hex, 'hex')), c.expect);
  // every hash length around the block edges agrees with node's
  for (let n = 0; n < 200; n += 7) {
    const bytes = Buffer.from('x'.repeat(n));
    ok(`P sha256 of ${n} bytes`, Buffer.from(P.sha256(bytes)).equals(nodeCrypto.createHash('sha256').update(bytes).digest()));
  }
  // solving: what comes out passes the reference check, with and without a key
  for (const c of POW.hash.cases.filter((x) => x.valid)) {
    const ch = { v: 1, id: c.id, salt: c.salt, purpose: 'accounts.create', bits: c.bits };
    const nonce = await P.solve(ch, c.device_key);
    ok(`P solved ${c.name}`, nonce !== null && P.zeroBits(ref(ch, c.device_key, nonce)) >= c.bits, String(nonce));
    eq(`P first answer is the vector's nonce ${c.name}`, nonce, c.nonce);
  }
  {
    const ch = { v: 1, id: 'q1w2e3r4t5y6u7i8o9p0aQ', salt: 'zXcVbNmLkJhGfDsAqWeRtY', purpose: 'accounts.create', bits: 16 };
    const nonce = await P.solve(ch, 'k');
    ok('P 16 bits solved, node agrees', P.zeroBits(ref(ch, 'k', nonce)) >= 16);
    eq('P out of time → null', await P.solve({ ...ch, bits: 26 }, 'k', { limitMs: 30 }), null);
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
    eq('P a puzzle → resent once, with the answer', [r.sent.length, r.solving, r.ok], [2, 1, { token: 't' }]);
    const { pow, ...rest } = r.sent[1];
    eq('P the resend is the same request plus pow', rest, { device_name: 'x', device_key: 'dk' });
    eq('P pow echoes the puzzle and adds nonce', Object.keys(pow), ['v', 'id', 'iat', 'exp', 'purpose', 'bits', 'salt', 'used', 'sig', 'nonce']);
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
  ok('P bits 26 is still tried', P.challengeOf({ challenge: chal({ bits: 26 }) }) !== null);
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


// ── Receiving: what other devices sent, listed when the popup opens or on Refresh (no notifications, no background work) ──

// RV1 · a received link opens only as http(s); an address the service gives is a different, stricter thing (safeLink)
{
  for (const [u, want] of [['https://a.com/x?y=1', 'https://a.com/x?y=1'], ['http://a.com/x', 'http://a.com/x'], [' https://a.com ', 'https://a.com/'],
    ['javascript:alert(1)', null], ['data:text/html,<b>x</b>', null], ['file:///etc/passwd', null], ['chrome://settings', null], ['chrome-extension://abc/x.html', null],
    ['obsidian://open?vault=x', null], ['blob:https://a.com/1', null], ['ftp://a.com/x', null], ['//a.com/x', null], ['a.com/x', null], ['', null], [null, null], [undefined, null]]) {
    eq(`RV1 openableLink: ${String(u).slice(0, 36)}`, openableLink(u), want);
  }
  eq('RV1 a plain http link a device sent opens, where safeLink (for service addresses) would refuse it', [openableLink('http://example.com/a'), safeLink('http://example.com/a')], ['http://example.com/a', null]);
  eq('RV1 hostOf shows the domain without www.', [hostOf('https://www.example.com/a/b?c=1'), hostOf('https://news.example.co.uk/'), hostOf('not a link')], ['example.com', 'news.example.co.uk', '']);
}

// RV2 · one item from the service as a list entry
{
  const at = 1_000;
  const text = R.entryOf({ seq: 7, device_id: 'd_p', kind: 'text', raw: '  hello\n\tworld  ', meta: null }, at);
  eq('RV2 text: squashed label, whole text kept for copying, who sent it, when it came in', [text.kind, text.label, text.text, text.from, text.at], ['text', 'hello world', '  hello\n\tworld  ', 'd_p', at]);
  const cjk = R.entryOf({ seq: 8, device_id: 'd_p', kind: 'text', raw: '字'.repeat(300) }, at);
  eq('RV2 a long text is cut to 120 characters in the list', Array.from(cjk.label).length, 120);
  const huge = R.entryOf({ seq: 9, device_id: 'd_p', kind: 'text', raw: 'x'.repeat(R.RECEIVED_TEXT_MAX + 1) }, at);
  ok('RV2 text over the cap is listed but not kept whole for copying', huge.text === undefined && huge.label.length === 120);
  const link = R.entryOf({ seq: 10, device_id: 'd_p', kind: 'url', raw: 'https://www.example.com/a?b=1', meta: { title: ' A\ntitle ' } }, at);
  eq('RV2 link: title as label, the domain shown, the address kept to open', [link.kind, link.label, link.host, link.text], ['url', 'A title', 'example.com', 'https://www.example.com/a?b=1']);
  eq('RV2 link without a title: the domain is the label', R.entryOf({ seq: 11, device_id: 'd', kind: 'url', raw: 'https://example.com/a' }, at).label, 'example.com');
  for (const bad of ['javascript:alert(1)', 'data:text/html,x', 'file:///etc/passwd', 'chrome://settings', 'not a link']) {
    const e = R.entryOf({ seq: 12, device_id: 'd', kind: 'url', raw: bad }, at);
    ok(`RV2 a "url" that is not http(s) is plain text: ${bad.slice(0, 20)}`, e.kind === 'text' && e.host === undefined && openableLink(e.text) === null);
  }
  const file = R.entryOf({ seq: 13, device_id: 'd_m', kind: 'file', raw: '', url: 'https://x/v1/files/13?sig=1', bytes: 2048, meta: { filename: 'a b.pdf', mime: 'application/pdf' } }, at);
  eq('RV2 file: name, type and size; its short-lived download link is not kept', [file.kind, file.label, file.mime, file.bytes, 'url' in file], ['file', 'a b.pdf', 'application/pdf', 2048, false]);
  const img = R.entryOf({ seq: 14, device_id: 'd_m', kind: 'image', raw: '', meta: { filename: 'p.png', mime: 'image/png' } }, at);
  eq('RV2 an image is a file with an image type', [img.kind, img.mime], ['file', 'image/png']);
  eq('RV2 a file with no name has an empty label (the popup says Image / File)', R.entryOf({ seq: 15, device_id: 'd', kind: 'image', raw: '' }, at).label, '');
  eq('RV2 an unknown kind is shown as text', R.entryOf({ seq: 16, device_id: 'd', kind: 'weird', raw: 'x' }, at).kind, 'text');
}

// RV3 · the list: other devices only, nothing twice, newest first, 50 at most, a week at most
{
  const DAY = 864e5, now = 10 * DAY;
  const item = (seq, device_id = 'd_other', extra = {}) => ({ seq, device_id, kind: 'text', raw: `t${seq}`, ...extra });
  const m = R.mergeReceived([], [item(1, 'me'), item(2), item(3, 'me'), item(4)], 'me', now);
  eq('RV3 what this browser sent itself is never listed', m.list.map((e) => e.seq), [4, 2]);
  eq('RV3 added counts what was taken in', m.added, 2);
  eq('RV3 without knowing which device this is, nothing is taken in', R.mergeReceived([], [item(1)], undefined, now), { list: [], added: 0 });
  const again = R.mergeReceived(m.list, [item(2), item(4), item(5)], 'me', now);
  eq('RV3 nothing is listed twice; newest first', [again.list.map((e) => e.seq), again.added], [[5, 4, 2], 1]);
  ok('RV3 an item with no device id is another device’s, not ours', R.mergeReceived([], [{ seq: 1, kind: 'text', raw: 'x' }], 'me', now).list.length === 1);
  ok('RV3 a malformed item is skipped', R.mergeReceived([], [null, {}, { seq: 'x' }, { seq: 1.5 }, item(3)], 'me', now).list.length === 1);
  const big = R.mergeReceived([], Array.from({ length: 80 }, (_, k) => item(k + 1)), 'me', now);
  eq('RV3 at most 50, the newest ones', [big.list.length, big.list[0].seq, big.list.at(-1).seq, big.added], [R.RECEIVED_MAX, 80, 31, 50]);
  eq('RV3 the cap is 50 and the age 7 days', [R.RECEIVED_MAX, R.RECEIVED_DAYS], [50, 7]);
  const old = [{ seq: 5, kind: 'text', label: 'old', from: 'd', at: now - 8 * DAY }, { seq: 6, kind: 'text', label: 'ok', from: 'd', at: now - 6 * DAY }];
  eq('RV3 over 7 days old drops out of the list when the list is next written', R.mergeReceived(old, [], 'me', now).list.map((e) => e.seq), [6]);
}

// RV4 · fetching: the position moves only after what is before it is in the list; the first time starts from the beginning
{
  const mem = {};
  const local = {
    get: async (k) => Object.fromEntries((Array.isArray(k) ? k : [k]).filter((x) => x in mem).map((x) => [x, JSON.parse(JSON.stringify(mem[x]))])),
    set: async (o) => { for (const [k, v] of Object.entries(o)) mem[k] = JSON.parse(JSON.stringify(v)); },
    remove: async (k) => { delete mem[k]; },
  };
  const reset = () => { for (const k of Object.keys(mem)) delete mem[k]; };
  const it = (seq, device_id = 'd_phone') => ({ seq, device_id, kind: 'text', raw: `t${seq}`, created_at: 1 });
  const pages = (list) => { const asked = []; let n = 0; return { asked, pull: async (path) => { asked.push(path); const r = list[n++]; if (r instanceof Error) throw r; return r; } }; };

  reset();
  let p = pages([{ items: [it(1, 'me'), it(2), it(3)], has_more: false, next_after: 3 }]);
  let r = await R.refreshReceived({ pull: p.pull, selfId: 'me', why: 'open', local });
  eq('RV4 first time: starts at 0, asks with why=open, lists what others sent', [p.asked, r.list.map((e) => e.seq)], [['/v1/pull?after=0&limit=200&why=open'], [3, 2]]);
  eq('RV4 the list and the position are stored together', [mem[R.RECEIVED_KEY].map((e) => e.seq), mem[R.CURSOR_KEY]], [[3, 2], 3]);

  p = pages([{ items: [it(4)], has_more: false, next_after: 4 }]);
  r = await R.refreshReceived({ pull: p.pull, selfId: 'me', why: 'manual', local });
  eq('RV4 next time: continues from the position, why=manual for Refresh', [p.asked, r.list.map((e) => e.seq), mem[R.CURSOR_KEY]], [['/v1/pull?after=3&limit=200&why=manual'], [4, 3, 2], 4]);

  p = pages([{ items: [], has_more: false, next_after: 9 }]);
  await R.refreshReceived({ pull: p.pull, selfId: 'me', why: 'open', local });
  eq('RV4 an empty answer still moves the position to where the service says it has looked up to', mem[R.CURSOR_KEY], 9);
  p = pages([{ items: [it(5)], has_more: false, next_after: 2 }]);
  await R.refreshReceived({ pull: p.pull, selfId: 'me', why: 'open', local });
  eq('RV4 the position never moves back', mem[R.CURSOR_KEY], 9);

  reset();
  p = pages([new Error('offline')]);
  let threw = null;
  try { await R.refreshReceived({ pull: p.pull, selfId: 'me', why: 'open', local }); } catch (e) { threw = e; }
  ok('RV4 a failed request throws and writes nothing: no position, no list', threw?.message === 'offline' && !(R.CURSOR_KEY in mem) && !(R.RECEIVED_KEY in mem));

  reset();
  p = pages([{ items: [it(1), it(2)], has_more: true, next_after: 2 }, new Error('offline')]);
  threw = null;
  try { await R.refreshReceived({ pull: p.pull, selfId: 'me', why: 'open', local }); } catch (e) { threw = e; }
  eq('RV4 a page that fails leaves the earlier page kept and the position after it, not past it', [!!threw, mem[R.RECEIVED_KEY].map((e) => e.seq), mem[R.CURSOR_KEY]], [true, [2, 1], 2]);
  p = pages([{ items: [it(3)], has_more: false, next_after: 3 }]);
  await R.refreshReceived({ pull: p.pull, selfId: 'me', why: 'open', local });
  eq('RV4 …and the next refresh carries on from there', [p.asked, mem[R.RECEIVED_KEY].map((e) => e.seq)], [['/v1/pull?after=2&limit=200&why=open'], [3, 2, 1]]);

  reset();
  p = pages(Array.from({ length: 5 }, (_, k) => ({ items: [it(k + 1)], has_more: true, next_after: k + 1 })));
  r = await R.refreshReceived({ pull: p.pull, selfId: 'me', why: 'open', local });
  eq('RV4 a big backlog: at most 3 requests per refresh, the rest next time', [p.asked.length, r.more, mem[R.CURSOR_KEY]], [R.PAGES_PER_REFRESH, true, 3]);
  eq('RV4 the pages are asked for from where the last one ended', p.asked.map((a) => a.match(/after=(\d+)/)[1]), ['0', '1', '2']);

  reset();
  mem[R.RECEIVED_KEY] = [{ seq: 1, kind: 'text', label: 'x', from: 'd', at: Date.now() - 9 * 864e5 }];
  mem[R.CURSOR_KEY] = 'junk';
  p = pages([{ items: [], has_more: false, next_after: 1 }]);
  await R.refreshReceived({ pull: p.pull, selfId: 'me', why: 'open', local });
  eq('RV4 a position that is not a number starts from 0; what is over a week old is dropped when the list is written', [p.asked[0], mem[R.RECEIVED_KEY]], ['/v1/pull?after=0&limit=200&why=open', []]);
  ok('RV4 only the two why values the service already knows are used', /pullReceived\('open'\)/.test(readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8'))
    && /pullReceived\('manual'\)/.test(readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8'))
    && !/pullReceived\('(?!open|manual)/.test(readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8')));
}

// RV5 · a file or image: a new download link is asked for when it is opened (the one from the list expired), never kept
{
  const asked = [];
  const answer = (items) => async (path) => { asked.push(path); return { items, has_more: false, next_after: 99 }; };
  eq('RV5 asks for exactly that one item: after=seq-1, limit 1, why=open', [await R.fileLinkFrom(42, answer([{ seq: 42, url: 'https://x.example/v1/files/42?sig=a' }])), asked], ['https://x.example/v1/files/42?sig=a', ['/v1/pull?after=41&limit=1&why=open']]);
  eq('RV5 the item is gone (a later one came back): null, said as "no longer available"', await R.fileLinkFrom(42, answer([{ seq: 43, url: 'https://x.example/v1/files/43' }])), null);
  eq('RV5 nothing came back: null', await R.fileLinkFrom(42, answer([])), null);
  eq('RV5 only an http(s) link is opened', [await R.fileLinkFrom(1, answer([{ seq: 1, url: 'javascript:alert(1)' }])), await R.fileLinkFrom(1, answer([{ seq: 1, url: 'data:text/html,x' }])), await R.fileLinkFrom(1, answer([{ seq: 1 }]))], [null, null, null]);
  ok('RV5 an entry stores no download link', !JSON.stringify(R.entryOf({ seq: 1, device_id: 'd', kind: 'file', url: 'https://x/v1/files/1?sig=secret', meta: { filename: 'a' } })).includes('secret'));
}

// RV6 · the list is kept a week and then it is gone from storage; signing out or a key that stopped working empties it
{
  const DAY = 864e5, now = Date.now();
  const mem = {};
  const local = {
    get: async (k) => (k in mem ? { [k]: JSON.parse(JSON.stringify(mem[k])) } : {}),
    set: async (o) => { for (const [k, v] of Object.entries(o)) mem[k] = JSON.parse(JSON.stringify(v)); },
    remove: async (k) => { delete mem[k]; },
  };
  mem[R.RECEIVED_KEY] = [{ seq: 3, kind: 'text', label: 'new', at: now - DAY }, { seq: 2, kind: 'text', label: 'old', at: now - 8 * DAY }];
  eq('RV6 the list leaves out what is over a week old', (await R.receivedList(local)).map((e) => e.seq), [3]);
  eq('RV6 …while storage still holds it until it is pruned', mem[R.RECEIVED_KEY].length, 2);
  eq('RV6 pruning returns what is left and takes the rest out of storage', [(await R.pruneReceived(local)).map((e) => e.seq), mem[R.RECEIVED_KEY].map((e) => e.seq)], [[3], [3]]);
  mem[R.RECEIVED_KEY] = [{ seq: 1, kind: 'text', label: 'x', at: now - 9 * DAY }];
  await R.pruneReceived(local);
  ok('RV6 when nothing is left the key is removed', !(R.RECEIVED_KEY in mem));
  mem[R.RECEIVED_KEY] = 'junk';
  await R.pruneReceived(local);
  ok('RV6 something that is not a list is cleaned away', !(R.RECEIVED_KEY in mem));
  mem[R.RECEIVED_KEY] = Array.from({ length: 70 }, (_, k) => ({ seq: k + 1, kind: 'text', label: 'x', at: now }));
  await R.pruneReceived(local);
  eq('RV6 more than 50 in storage is cut to 50', mem[R.RECEIVED_KEY].length, 50);

  // Signing out (the key is forgotten) takes the list and the position with it; the old key is kept only to join again in its place
  const real = globalThis.chrome;
  const all = { token: 'dk_old', device_id: 'd1', scope: 'full', me: {}, recent: [], [R.RECEIVED_KEY]: [{ seq: 1 }], [R.CURSOR_KEY]: 5, default_to: ['x'], theme: 'dark' };
  globalThis.chrome = { storage: { local: {
    get: async () => ({ ...all }), set: async (o) => Object.assign(all, o), remove: async (ks) => { for (const k of [].concat(ks)) delete all[k]; },
  } } };
  try {
    await forgetToken('dk_old');
    ok('RV6 forgetting the key empties the received list and its position', !(R.RECEIVED_KEY in all) && !(R.CURSOR_KEY in all) && !('token' in all) && !('scope' in all) && all.previous_token === 'dk_old');
    eq('RV6 …and leaves this browser’s theme alone', all.theme, 'dark');
  } finally { globalThis.chrome = real; }
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  ok('RV6 signing out on purpose empties them too', /\$\('so-go'\)\.onclick[\s\S]{0,1200}RECEIVED_KEY, CURSOR_KEY/.test(popup));
}

// RV7 · turning receiving on: the same join control, a full key asked for, this browser's own key sent as the one it replaces
{
  const { receiveJoinBody, canTurnOnReceiving, canReceive } = await import('../extension/pairing.js');
  eq('RV7 the request: the code, a name, scope full, the fingerprint and the key being replaced',
    receiveJoinBody({ code: 'K7Q4M2', name: 'Browser · MacIntel', deviceKey: 'f'.repeat(64), token: 'dk_current' }),
    { code: 'K7Q4M2', device_name: 'Browser · MacIntel', scope: 'full', device_key: 'f'.repeat(64), previous_token: 'dk_current' });
  eq('RV7 no fingerprint available: it is left out, as when joining the first time', Object.keys(receiveJoinBody({ code: 'K', name: 'n', deviceKey: undefined, token: 'dk_c' })).sort(), ['code', 'device_name', 'previous_token', 'scope']);
  ok('RV7 never asks for anything but full', receiveJoinBody({ code: 'K', name: 'n', token: 't' }).scope === 'full');
  ok('RV7 only a send-only browser is offered it; a full or unknown one is not', canTurnOnReceiving('ingest_only') && !canTurnOnReceiving('full') && !canTurnOnReceiving(null) && !canTurnOnReceiving(undefined));
  ok('RV7 only a full key reads', canReceive('full') && !canReceive('ingest_only') && !canReceive(null) && !canReceive(undefined));
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  const html = readFileSync(new URL('../extension/popup.html', import.meta.url), 'utf8');
  ok('RV7 the popup sends that request to the join address, with the key this browser holds now', /'\/v1\/pair\/claim',\s*receiveJoinBody\(\{[^}]*token[^}]*\}\)/.test(popup));
  ok('RV7 success records full (and says so); the old key is replaced by the new one in the same place as joining', /adopt\(res, 'full', \{ receiving: true \}\)/.test(popup) && /async function adopt\(res, scope[^)]*\)\s*\{\s*await store\.set\(\{ token: res\.token, device_id: res\.device_id, scope \}\)/.test(popup));
  ok('RV7 a code that is refused changes nothing: the join is made before anything is stored', /receiveJoinBody[\s\S]{0,200}receiveClose\(false\);[\s\S]{0,300}await adopt/.test(popup));
  ok('RV7 the menu item is hidden until the scope says it applies', /id="receive-open"[^>]*hidden/.test(html) && /\$\('receive-open'\)\.hidden = !canTurnOnReceiving\(scope\)/.test(popup));
  ok('RV7 the code field is the same kind of control as the join screen (an input inside an envelope.join)', /<div class="envelope join">\s*<input type="text" id="rcode"/.test(html));
  const en = STRINGS.en.html, zh = STRINGS.zh.html;
  ok('RV7 the warning is said in both languages', /read what is in your account/.test(en.rcvWarn) && /读到你账号里的内容/.test(zh.rcvWarn));
}

// E35 · the settings panel says the version and what this browser may do
{
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  const html = readFileSync(new URL('../extension/popup.html', import.meta.url), 'utf8');
  const manifest = JSON.parse(readFileSync(new URL('../extension/manifest.json', import.meta.url), 'utf8'));
  ok('E35 the panel has a line for it, not an action', /<p class="menu-note" id="about"><\/p>/.test(html) && !/role="menuitem" id="about"/.test(html));
  ok('E35 the version is the manifest\'s own, read at run time', /\$\('about'\)\.textContent = t\.aboutLine\(chrome\.runtime\.getManifest\(\)\.version, scope\)/.test(popup));
  for (const [lang, t] of Object.entries(STRINGS)) {
    const v = manifest.version;
    ok(`E35 ${lang} full: version and can send and receive`, t.aboutLine(v, 'full').includes(v) && t.aboutLine(v, 'full') !== t.aboutLine(v, 'ingest_only'));
    ok(`E35 ${lang} send-only: version and says send only`, t.aboutLine(v, 'ingest_only').includes(v));
    eq(`E35 ${lang} unknown: the version alone`, t.aboutLine(v, null).replace(/\D*(\d[\d.]*)\D*/, '$1'), v);
  }
  ok('E35 the wording', /send and receive/.test(STRINGS.en.aboutLine('1', 'full')) && /send only/.test(STRINGS.en.aboutLine('1', 'ingest_only'))
    && /能收能发/.test(STRINGS.zh.aboutLine('1', 'full')) && /只能投递/.test(STRINGS.zh.aboutLine('1', 'ingest_only')));
  ok('E35 the join screen says this browser will send and receive, in both languages', /send and receive/.test(STRINGS.en.consent) && /能收能发/.test(STRINGS.zh.consent));
}

// RV9 · things sent together are one card
{
  const t0 = 1_000_000;
  const items = [
    { seq: 9, device_id: 'd_w', kind: 'file', meta: { filename: 'a.txt', group: { id: 'g1', n: 2, i: 2 } }, bytes: 5 },
    { seq: 8, device_id: 'd_w', kind: 'text', raw: 'hello', meta: { group: { id: 'g1', n: 2, i: 1 } } },
    { seq: 7, device_id: 'd_w', kind: 'text', raw: 'alone' },
    { seq: 6, device_id: 'd_w', kind: 'text', raw: 'odd', meta: { group: { id: 5, n: 2, i: 1 } } },
  ];
  const { list } = R.mergeReceived([], items, 'd_me', t0);
  eq('RV9 entries keep their letter', list.map((e) => e.group?.id ?? null), ['g1', 'g1', null, null]);
  const units = R.groupReceived(list);
  eq('RV9 two items of one letter are one card, the rest stay single', units.map((u) => (u.members ? `letter:${u.members.length}` : `one:${u.one.seq}`)), ['letter:2', 'one:7', 'one:6']);
  eq('RV9 the card lists its members in the order they were sent', units[0].members.map((m) => m.seq), [8, 9]);
  eq('RV9 a letter with a malformed group is just an item', list[3].group, undefined);
  eq('RV9 an empty list is nothing', R.groupReceived(null), []);
  eq('RV9 the time shown is when it was sent, not when it was taken in', [R.entryOf({ seq: 1, device_id: 'd', kind: 'text', raw: 'x', created_at: 5000 }, 9000).sent, R.entryOf({ seq: 1, device_id: 'd', kind: 'text', raw: 'x' }, 9000).sent], [5000, undefined]);
}

// RV8 · nothing new is asked of the browser: no notifications, no background work, no new permissions
{
  const manifest = JSON.parse(readFileSync(new URL('../extension/manifest.json', import.meta.url), 'utf8'));
  eq('RV8 permissions are exactly what they were', manifest.permissions, ['contextMenus', 'storage', 'activeTab', 'notifications', 'scripting', 'declarativeNetRequestWithHostAccess']);
  ok('RV8 no clipboardWrite, alarms, offscreen, tabs, downloads', !manifest.permissions.some((p) => ['clipboardWrite', 'alarms', 'offscreen', 'tabs', 'downloads'].includes(p)));
  ok('RV8 no minimum_chrome_version and no host_permissions', !('minimum_chrome_version' in manifest) && !('host_permissions' in manifest));
  eq('RV8 optional host access is what it was', manifest.optional_host_permissions, ['https://*/*', 'http://*/*']);
  const bg = readFileSync(new URL('../extension/background.js', import.meta.url), 'utf8');
  ok('RV8 the background code has no WebSocket, no alarms, and does not fetch what others sent', !/WebSocket|chrome\.alarms|\/v1\/pull|receive\.js/.test(bg));
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  ok('RV8 reading happens only from the popup: on open and on Refresh', (popup.match(/pullReceived\(/g) || []).length === 3);   // the definition, open, manual
}

// F1 · the menu follows the device list: "Send to by default" appears once there are two or more other devices to choose from
{
  const dev = (id, scope = 'full') => ({ device_id: id, name: id, scope, last_seen_at: 1 });
  ok('F1 a full key with two or more other devices can choose', canChoose('full', [dev('a'), dev('b')]));
  ok('F1 one other device: "all" and "that one" are the same send, so no choice is offered (as on the web page)', !canChoose('full', [dev('a')]));
  ok('F1 alone (just created the account): nothing to choose', !canChoose('full', []) && !canChoose('full', undefined));
  ok('F1 a send-only key never chooses', !canChoose('ingest_only', [dev('a'), dev('b')]) && !canChoose(null, [dev('a'), dev('b')]));
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  const body = (name) => popup.match(new RegExp(`(?:async )?function ${name}\\([^)]*\\) \\{([\\s\\S]*?)\\n\\}\\n`))?.[1] ?? '';
  ok('F1 a device list arriving sets the list, redraws the menu and the header', /setList\(candidates\(devices/.test(body('applyDevices')) && /drawTargets\(\)/.test(body('applyDevices')) && /drawPlan\(/.test(body('applyDevices')));
  ok('F1 every place that fetches the device list applies it: opening the panel or menu, showing a code, watching for the new device',
    /applyDevices\(devices\)/.test(body('refreshTargets')) && /applyDevices\(devices\)/.test(body('makePairCode')) && /applyDevices\(list\)/.test(body('pairWatch')));
}

// F2 · the header's device count follows the list that just arrived
{
  const acct = { plan: 'free', devices_used: 1, devices_limit: 2, item_bytes_limit: 10485760 };
  eq('F2 a new device: 1/2 becomes 2/2, the rest of the account line is kept', withDeviceCount(acct, [{ device_id: 'a' }, { device_id: 'b' }]), { ...acct, devices_used: 2 });
  ok('F2 the same count: the same object back (nothing to write)', withDeviceCount(acct, [{ device_id: 'a' }]) === acct);
  eq('F2 a device removed', withDeviceCount({ ...acct, devices_used: 2 }, [{ device_id: 'a' }]).devices_used, 1);
  ok('F2 no account line or no list: left as it is', withDeviceCount(null, []) === null && withDeviceCount(acct, undefined) === acct);
  const t = STRINGS.en;
  eq('F2 what the header says', [t.planLine(t.plans.free, 1, 2), t.planLine(t.plans.free, 2, 2)], ['Free · 1/2 devices', 'Free · 2/2 devices']);
}

// F3 · a file over the free one-file limit, found before sending: say what Paid takes, with the way to upgrade (src=ext)
{
  const MB = 1048576;
  const up = 'https://dropit.example/upgrade?u=U1&e=170&s=SIG&why=account&buy=year';
  const free = { plan: 'free', upgrade_url: up, item_bytes_limit: 10 * MB };
  for (const [lang, t] of Object.entries(STRINGS)) {
    const w = fileSizeWall(free, 50 * MB, 100, 10 * MB, t);
    eq(`F3 ${lang} the sentence names the free limit and what Paid takes`, w.message, t.tooLargeFree('10 MB', '100 MB'));
    ok(`F3 ${lang} …both numbers are in it`, w.message.includes('10 MB') && w.message.includes('100 MB'));
    eq(`F3 ${lang} the link is the account's own, marked as a file-size limit like the service's answer`, w.data, { upgrade_url: 'https://dropit.example/upgrade?u=U1&e=170&s=SIG&why=file_size&buy=year', why: 'file_size' });
    eq(`F3 ${lang} …and opens on the plans page (no buy=), tagged src=ext, the ticket untouched`, upgradeLink(w.data), 'https://dropit.example/upgrade?u=U1&e=170&s=SIG&why=file_size&src=ext');
    ok(`F3 ${lang} the line next to it is the file-size one`, t.upgradeFor(w.data.why) !== t.upgradeFor('nonsense'));
  }
  const t = STRINGS.en;
  const plain = { message: t.tooLarge('10 MB'), data: null };
  eq('F3 over what Paid takes too: no link, only the reason', fileSizeWall(free, 150 * MB, 100, 10 * MB, t), plain);
  eq('F3 exactly the Paid limit still gets the link', !!fileSizeWall(free, 100 * MB, 100, 10 * MB, t).data, true);
  eq('F3 a paid account (the service gave no link): no link', fileSizeWall({ plan: 'paid' }, 150 * MB, 100, 100 * MB, t), { message: t.tooLarge('100 MB'), data: null });
  eq('F3 payments off (no link): no link', fileSizeWall({ plan: 'free' }, 50 * MB, 100, 10 * MB, t), plain);
  eq('F3 the Paid limit is not known: no link, no number written', fileSizeWall(free, 50 * MB, null, 10 * MB, t), plain);
  eq('F3 the size is not known: no link', fileSizeWall(free, undefined, 100, 10 * MB, t), plain);
  eq('F3 an unsafe link is never passed on', fileSizeWall({ upgrade_url: 'javascript:alert(1)' }, 50 * MB, 100, 10 * MB, t), plain);
  eq('F3 a link with no why gets one', fileSizeWall({ upgrade_url: 'https://dropit.example/upgrade?u=1' }, 50 * MB, 100, 10 * MB, t).data.upgrade_url, 'https://dropit.example/upgrade?u=1&why=file_size');
  ok('F3 no sentence hard-codes a size', !/\d/.test(STRINGS.en.tooLargeFree('X', 'Y').replace(/X|Y/g, '')) && !/\d/.test(STRINGS.zh.tooLargeFree('X', 'Y').replace(/X|Y/g, '')));

  // The whole path, with the service answering: the error carries the link, the popup and the page toast show it as for any limit
  const real = { chrome: globalThis.chrome, fetch: globalThis.fetch };
  const mem = { token: 'dk_x', device_id: 'd1', scope: 'full' };
  globalThis.chrome = { storage: { local: {
    get: async () => ({ ...mem }), set: async (o) => Object.assign(mem, o), remove: async (ks) => { for (const k of [].concat(ks)) delete mem[k]; },
  } } };
  const calls = [];
  const serve = (acct) => async (url, init) => {
    const path = new URL(url).pathname;
    calls.push(path);
    const body = path === '/v1/me' ? acct : path === '/v1/plans' ? { limits: { free: { fileMB: 10 }, paid: { fileMB: 100 } } } : {};
    return { ok: true, status: 200, json: async () => body };
  };
  try {
    globalThis.fetch = serve({ plan: 'free', upgrade_url: up, item_bytes_limit: 10 * MB });
    const err = await tooLarge(10 * MB, 50 * MB);
    ok('F3 end to end: the error says what Paid takes and carries the link', err.message.includes('100') && err.data?.why === 'file_size' && upgradeLink(err.data)?.endsWith('&src=ext'), JSON.stringify([err.message, err.data]));
    eq('F3 …asking only for the account and the plans', calls, ['/v1/me', '/v1/plans']);
    calls.length = 0;
    globalThis.fetch = serve({ plan: 'paid', item_bytes_limit: 100 * MB });
    const paid = await tooLarge(100 * MB, 150 * MB);
    ok('F3 end to end, a paid account: the plain sentence, no link, the plans are not even asked for', !paid.data && !calls.includes('/v1/plans'));
    globalThis.fetch = async () => { throw new TypeError('offline'); };
    const off = await tooLarge(10 * MB, 50 * MB);
    ok('F3 offline: still the plain sentence, never a failure of its own', !off.data && off.message.length > 0);
    ok('F3 no limit known: the general sentence', (await tooLarge(undefined, 5)).message === (await import('../extension/i18n.js')).t.errors.PAYLOAD_TOO_LARGE);
  } finally { Object.assign(globalThis, { chrome: real.chrome, fetch: real.fetch }); if (real.chrome === undefined) delete globalThis.chrome; }
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  const bg = readFileSync(new URL('../extension/background.js', import.meta.url), 'utf8');
  ok('F3 the popup uses it for files in a send and for a PDF', (popup.match(/await tooLarge\(/g) || []).length === 3 && !/t\.tooLarge\(/.test(popup));
  ok('F3 the page and right-click sends use it too', (bg.match(/await tooLarge\(/g) || []).length === 3 && !/t\.tooLarge\(/.test(bg));
}

// G1 · a received file that can't be fetched any more (expired or removed): a free account hears what Paid keeps and gets the way to upgrade
{
  const up = 'https://dropit.example/upgrade?u=U1&e=170&s=SIG&why=account&buy=year';
  const free = { plan: 'free', upgrade_url: up };
  for (const [lang, t] of Object.entries(STRINGS)) {
    const w = goneWall(free, 1, 10, t);
    eq(`G1 ${lang} the sentence names how long each plan keeps items`, w.message, t.recvGoneKept(1, 10));
    ok(`G1 ${lang} …both numbers are in it`, w.message.includes('1') && w.message.includes('10'));
    eq(`G1 ${lang} the link is the account's own, marked as a retention limit`, w.data, { upgrade_url: 'https://dropit.example/upgrade?u=U1&e=170&s=SIG&why=kept&buy=year', why: 'kept' });
    eq(`G1 ${lang} …and opens tagged src=ext`, upgradeLink(w.data), 'https://dropit.example/upgrade?u=U1&e=170&s=SIG&why=kept&src=ext');
    ok(`G1 ${lang} the line for it is its own`, t.upgradeFor('kept') !== t.upgradeFor('nonsense'));
    eq(`G1 ${lang} a paid account (no link): the plain sentence`, goneWall({ plan: 'paid' }, 1, 10, t), { message: t.recvGone, data: null });
    eq(`G1 ${lang} a number not known: the plain sentence, no link`, goneWall(free, null, 10, t), { message: t.recvGone, data: null });
    eq(`G1 ${lang} an unsafe link is never passed on`, goneWall({ upgrade_url: 'javascript:alert(1)' }, 1, 10, t).data, null);
  }
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  const html = readFileSync(new URL('../extension/popup.html', import.meta.url), 'utf8');
  ok('G1 the Received note shows the way past a limit: a download limit answer carries its link', /recvNote\(err\.message, err\.data\)/.test(popup) && /id="recv-up"/.test(html));
  ok('G1 an expired file asks for the link and the plans only now', /else await goneNote\(\)/.test(popup) && /async function goneNote/.test(popup));
  eq('G1 a day count of one is singular', STRINGS.en.recvGoneKept(1, 10), 'This file is no longer available — Free keeps items 1 day, Paid keeps them 10 days');
}

// G2 · wording: nothing in what the extension says calls the service storage, and the real-time line uses the one name
{
  const words = /storage|backup|cloud|存储|云盘|备份|不保存|doesn.t keep/i;
  for (const [lang, t] of Object.entries(STRINGS)) {
    const text = [...Object.values(t.errors), t.tooLarge('X'), t.tooLargeFree('X', 'Y'), t.rateDay('X'), t.quotaBytes, t.quotaBytesIn('X'), t.quotaItems, t.quotaItemsIn('X'),
      t.rateDayDownloads('X'), t.recvGone, t.recvGoneKept(1, 10), ...['per_day', 'queue', 'space', 'file_size', 'devices', 'realtime', 'uploads', 'downloads', 'kept'].map(t.upgradeFor)].join('\n');
    ok(`G2 ${lang} no red-line words in the limit sentences`, !words.test(text));
  }
  eq('G2 en the real-time link says real-time delivery', STRINGS.en.upgradeFor('realtime'), 'Keep real-time delivery with Paid');
  ok('G2 zh the real-time link says 实时送达', STRINGS.zh.upgradeFor('realtime').includes('实时送达'));
}

// I-08..I-12 · fixes from the 10-22 round
{
  const now = Date.now();
  const item = (seq, device_id) => ({ seq, kind: 'text', raw: 'x' + seq, device_id, created_at: now });
  // I-08: turning receiving on joins again under a new device id; what it sent under the earlier ones is still its own
  const own = R.ownIds('new', ['old1', 'old2']);
  eq('I-08 ownIds: the current id and the earlier ones', own, ['new', 'old1', 'old2']);
  eq('I-08 ownIds: nothing stored, nothing odd', R.ownIds('new', undefined), ['new']);
  const m = R.mergeReceived([], [item(1, 'old1'), item(2, 'other'), item(3, 'new'), item(4, 'old2')], own, now);
  eq('I-08 what it sent before joining again is not listed as received', m.list.map((e) => e.seq), [2]);
  eq('I-08 a single id still works as before', R.mergeReceived([], [item(1, 'me'), item(2)], 'me', now).list.map((e) => e.seq), [2]);
  eq('I-08 no known ids at all: nothing is taken in', R.mergeReceived([], [item(1, 'x')], [], now), { list: [], added: 0 });
  eq('I-08 rememberOwn adds the old id once', R.rememberOwn(['a'], 'b'), ['a', 'b']);
  eq('I-08 rememberOwn: again is no change', R.rememberOwn(['a', 'b'], 'b'), ['a', 'b']);
  eq('I-08 rememberOwn: no old id (nothing to add)', R.rememberOwn(['a'], undefined), ['a']);
  eq('I-08 rememberOwn: bounded, newest kept', R.rememberOwn(Array.from({ length: R.OWN_IDS_MAX }, (_, i) => 'i' + i), 'z').at(-1), 'z');
  ok('I-08 rememberOwn keeps at most OWN_IDS_MAX', R.rememberOwn(Array.from({ length: R.OWN_IDS_MAX }, (_, i) => 'i' + i), 'z').length === R.OWN_IDS_MAX);
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  ok('I-08 the popup records the old id on the receiving join and reads all ids when pulling', /rememberOwn\(before, oldId\)/.test(popup) && /selfId: ownIds\(device_id, before\)/.test(popup));
  // I-09: the name this browser gives itself is short whatever the platform string is
  for (const [lang, t] of Object.entries(STRINGS)) {
    const long = t.deviceName('P'.repeat(300)), cjk = t.deviceName('平台'.repeat(100));
    ok(`I-09 ${lang} a long platform gives a name of at most 48 characters`, Array.from(long).length <= 48 && Array.from(cjk).length <= 48, `${Array.from(long).length}/${Array.from(cjk).length}`);
    ok(`I-09 ${lang} a cut name ends in …, a normal one is untouched`, long.endsWith('…') && t.deviceName('MacIntel').endsWith('MacIntel'));
  }
  // I-10: signing out asks twice; a removed browser keeps what was typed
  ok('I-10 sign out: the menu item opens a confirmation screen; only its Sign out button signs out', /\$\('unpair'\)\.onclick = signoutOpen;/.test(popup) && /\$\('so-go'\)\.onclick = async \(\) => \{[\s\S]*?api\('DELETE'/.test(popup) && !/unpairArmed/.test(popup));
  ok('I-10 sign out: the confirmation is worded in both languages, with the title and the button the other clients use', STRINGS.en.html.soTitle === 'Sign out of this device?' && STRINGS.zh.html.soTitle === '退出这台设备？' && STRINGS.en.html.unpair === 'Sign out' && STRINGS.zh.html.unpair === '退出');
  ok('N-02 sign out: the text says unsent text is not kept, and the box really is emptied', /Text you haven’t sent is not kept/.test(STRINGS.en.html.soBody) && STRINGS.zh.html.soBody.includes('未发出的文字不会保留') && /state\.text = compose\.value = ''/.test(popup.slice(popup.indexOf("$('so-go')"), popup.indexOf("$('so-go')") + 400)));
  ok('N-02 sign out: what it says goes with it is what the code removes (recent, received, default)', /RECENT_KEY, RECEIVED_KEY, CURSOR_KEY, RECV_AT_KEY, OWN_IDS_KEY, DEFAULT_KEY/.test(popup) && /recent list/.test(STRINGS.en.html.soBody) && STRINGS.zh.html.soBody.includes('最近投出'));
  ok('N-xx the default panel is titled "This browser sends to by default" and the menu item keeps its short name', STRINGS.en.html.dfTitle === 'This browser sends to by default' && STRINGS.zh.html.dfTitle === '这台浏览器默认投给' && STRINGS.en.html.menuDefaults === 'Send to by default' && STRINGS.zh.html.menuDefaults === '默认投给');
  ok('N-xx no old wording for signing out is left in the extension strings', !/在这个浏览器上退出|Sign out on this browser|再点一次确认退出|Tap again to sign out/.test(JSON.stringify(STRINGS)))
  ok('I-10 a removed browser keeps the typed words for this session and puts them back after joining', /storage\.session\?\.set\(\{ draft: compose\.value \}\)/.test(popup) && /get\('draft'\)/.test(popup));
  // I-12: the way to Paid is shown only when the plans do not say paying is off
  ok('I-12 payments open / unknown: the link stays', !paymentsOff({ payments: { open: true } }) && !paymentsOff(null) && !paymentsOff({}) && !paymentsOff(undefined));
  ok('I-12 payments off: no way to Paid', paymentsOff({ payments: { open: false } }));
  const wall = (offered) => {
    const plans = { payments: { open: offered }, limits: { paid: { fileMB: 100 } } };
    return fileSizeWall({ upgrade_url: 'https://x.example/upgrade' }, 20 * 1048576, paymentsOff(plans) ? null : plans.limits.paid.fileMB, 10 * 1048576, STRINGS.en);
  };
  ok('I-12 a too-large file: the plain sentence when payments are off, the offer when on', wall(false).data === null && wall(true).data?.why === 'file_size');
  ok('I-12 the plans are read only where a limit card offers Paid (not at start-up)', !/readPlans\(\)/.test(readFileSync(new URL('../extension/background.js', import.meta.url), 'utf8').replace(/upgradeOffered/g, '')));
}

// Send v3 (A): one block right of the box, Send on top, "→ where" below
{
  const html = readFileSync(new URL('../extension/popup.html', import.meta.url), 'utf8');
  const css = readFileSync(new URL('../extension/popup.css', import.meta.url), 'utf8');
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  ok('A: the block stacks Send over the where-to part, in the box\'s row', /\.split \{[^}]*flex-direction: column/.test(css) && /\.line \{ display: flex; align-items: stretch/.test(css));
  ok('A: Send is the red top (plane + word), the bottom is the tile-coloured where-to', /id="send-lead" data-icon="plane"/.test(html) && /id="dest"/.test(html) && /\.more \{[\s\S]*?border-top: 1\.5px solid rgba\(255, 255, 255, \.75\)[\s\S]*?color-mix\(in srgb, var\(--navy\) 14%/.test(css));
  ok('A: the old number badge and the right-hand segment are gone', !/targets-badge|\.badge/.test(html + css + popup) && !/border-left: 1px solid rgba\(255, 255, 255, \.35\)/.test(css));
  ok('A: with one other device the block is just Send (the bottom is hidden)', /\$\('targets-btn'\)\.hidden = !may/.test(popup) && /\.more\[hidden\] \{ display: none; \}/.test(css) && /\.split \.send \{ border-radius: 14px; \}/.test(css));
  ok('A: the box is at least 92px and scrolls inside after about six lines', /min-height: 92px; max-height: 137px; overflow-y: auto/.test(css));
  ok('A: pressed / sending / sent each have their look', /\.split:has\(\.send:active/.test(css) && /\.sending \.more \{ opacity: \.45/.test(css) && /\.split\.sent \.send \{ background: linear-gradient\(180deg, #26905e/.test(css));
  ok('A: the where-to is written with the short target text (→ All · → names · → N devices)', /destLine\(to, targets\.names, t, destFits\)/.test(popup) && /shortTarget\(to, targets\.names, t\)/.test(popup));
  for (const [lang, t] of Object.entries(STRINGS)) {
    ok(`A ${lang} the where-to has no arrow of its own, and "send to all this time" is worded`, t.destTo === undefined && !!t.sendAllOnce);
  }
  eq('A en: All, 2 devices', [STRINGS.en.targetAll, STRINGS.en.targetN(2)], ['All', '2 devices']);
  eq('A zh: 全部, 2 台', [STRINGS.zh.targetAll, STRINGS.zh.targetN(2)], ['全部', '2 台']);
  ok('A: the bottom is the devices icon + the text + a small chevron', /class="ic dv" data-icon="devices"/.test(html) && /id="dest"/.test(html) && /\.more \.dn svg\.i \{ width: 10px; height: 10px/.test(css));
  ok('A: Send about 60px, where-to 32px in the 92px block, the touch zone does not reach up', /\.send \{\s*flex: 1; min-height: 56px/.test(css) && /\.more \{[^}]*height: 32px/.test(css) && /\.more::before \{ content: ""; position: absolute; inset: 0 0 -4px; \}/.test(css) && /\.split \{[^}]*min-height: 92px/.test(css));
  ok('A: a failed card has Try again, and "Send to all this time" only when the chosen devices are gone', /\$\('send-all'\)\.hidden = err\?\.code !== 'TARGET_UNAVAILABLE'/.test(popup) && /id="send-all"/.test(html) && /state\.pick = null; \$\('send-form'\)\.requestSubmit\(\)/.test(popup));
  ok('A: sending shows the ring and "Sending…", sent shows ✓ and goes back after 1.5 s', /sendFace\(on \? 'sending' : ''\)/.test(popup) && /setTimeout\(\(\) => sendFace\(''\), 1500\)/.test(popup));
}

// V-01..V-05 · look fixes after the first browser pass
{
  const css = readFileSync(new URL('../extension/popup.css', import.meta.url), 'utf8');
  // V-06 · a long file name in a received batch must not widen the card: the file list is one column that may shrink to the card
  ok('V-06 the files of a received batch are one shrinkable column', /\.files \{[^}]*grid-template-columns: minmax\(0, 1fr\)/.test(css) && /\.files \{[^}]*min-width: 0/.test(css));
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  const names = new Map([['mac', 'Mac'], ['pixel', 'Pixel'], ['ipad', 'iPad']]);      // the device list's order
  const fits = (n) => (text) => [...text].length <= n;
  for (const [lang, t] of Object.entries(STRINGS)) {
    eq(`V-02 ${lang} everyone`, destLine(null, names, t, fits(1)), t.targetAll);
    ok(`V-02 ${lang} two names that fit stay names; when they do not it is the count, never a cut name`, destLine(['mac', 'pixel'], names, t, fits(40)) === `Mac${t.targetSep}Pixel` && destLine(['mac', 'pixel'], names, t, fits(lang === 'en' ? 9 : 5)) === t.targetN(2) && !destLine(['mac', 'pixel'], names, t, fits(5)).includes('…'));
    eq(`V-02 ${lang} one name that does not fit: the count`, destLine(['mac'], names, t, fits(2)), [...t.targetN(1)].length <= 2 ? t.targetN(1) : '1');
    eq(`V-02 ${lang} three: the count`, destLine(['mac', 'pixel', 'ipad'], names, t, fits(99)), t.targetN(3));
  }
  eq('V-03 English joins with ", ", Chinese with 「、」', [destLine(['mac', 'ipad'], names, STRINGS.en, fits(40)), destLine(['mac', 'ipad'], names, STRINGS.zh, fits(40))], ['Mac, iPad', 'Mac、iPad']);
  eq('V-04 the order is the device list\'s, however it was picked', [destLine(['ipad', 'mac'], names, STRINGS.en, fits(40)), shortTarget(['ipad', 'mac'], names, STRINGS.en)], ['Mac, iPad', 'Mac, iPad']);
  eq('icon: when even the count does not fit, just the number', destLine(['mac', 'pixel'], names, STRINGS.en, fits(1)), '2');
  eq('V-04 inListOrder: unknown ids last', inListOrder(['x', 'ipad', 'mac'], ['mac', 'pixel', 'ipad']), ['mac', 'ipad', 'x']);
  ok('V-02 the popup asks the real room (canvas) before it writes names', /measure\.measureText\(text\)\.width/.test(popup) && /destLine\(to, targets\.names, t, destFits\)/.test(popup));
  ok('V-01 the ring has a size of its own in a flex holder, 16px', /\.send \.lead \{ display: inline-flex/.test(css) && /\.spin \{ box-sizing: border-box; display: block; width: 16px; height: 16px/.test(css));
  ok('V-05 the focus ring follows the block\'s corners', /\.send:focus-visible, \.more:focus-visible \{ outline: none; \}/.test(css) && /\.split:has\(> \.send:focus-visible, > \.more:focus-visible\) \{ box-shadow: 0 0 0 2px var\(--paper\), 0 0 0 4px var\(--navy\)/.test(css));
}

// ── Which account, which device ──────────────────────────────────────────────────────
{
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  const html = readFileSync(new URL('../extension/popup.html', import.meta.url), 'utf8');
  eq('A1 accountTag: 8 lowercase hex only', [accountTag({ account_tag: 'fc985852' }), accountTag({ account_tag: 'FC985852' }), accountTag({ account_tag: 'fc9858' }), accountTag({}), accountTag(null), accountTag({ account_tag: 'fc98585<' })], ['fc985852', '', '', '', '', '']);
  eq('A2 deviceSuffix: last four characters after d_', [deviceSuffix('d_QMNWEuCelwDcAB7hmUbT5Q'), deviceSuffix('d_ab'), deviceSuffix(undefined)], ['bT5Q', 'ab', '']);
  ok('A3 the menu has the account line under the version, hidden until there is one', /id="about"><\/p>\s*(<!--[^>]*-->\s*)?<p class="menu-note menu-account" id="account" hidden><\/p>/.test(html));
  ok('A4 it is drawn from the account line the popup already has (drawPlan), no request of its own', /function drawPlan\(m\) \{[\s\S]*?accountTag\(m\)[\s\S]*?\$\('account'\)\.hidden = !tag;/.test(popup));
  ok('A5 the device rows write the suffix on the second line, before the last-active text, and not after the name', /className: 'mt', textContent: `\$\{deviceSuffix\(d\.device_id\)\} · \$\{activity\(/.test(popup) && !/nameWithSuffix|className: 'sfx'/.test(popup) && /className: 'tt', title: d\.name, textContent: d\.name \}/.test(popup));
  eq('A6 the wording', [STRINGS.en.accountLine('fc985852'), STRINGS.zh.accountLine('fc985852')], ['Account fc985852', '账号 fc985852']);
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
