/**
 * The extension's decisions that don't need a browser: metadata and its size budget, link or text,
 * file names, context menus, batches, what Enter sends, error sentences, adding a device, and the QR code.
 *
 *   node test/extension.test.mjs
 */
import {
  META_BUDGET, TITLE_MAX, DESCRIPTION_MAX, FROM_URL_MAX, FILES_MAX, squash, clip, pageMeta, fromMeta, fitMeta, kindOf,
  fileNameOf, MENUS, FILE_EXT, groupsFor, intentOf, mb, fileSize, errorText, safeLink, upgradeLink, endpointList, isPdf, seqRange,
} from '../extension/payload.js';
import { STRINGS } from '../extension/i18n.js';
import * as P from '../extension/pow.js';
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
    eq('E15 upgrade link is tagged with the extension', upgradeLink({ upgrade_url: up, why: 'per_day' }), `${up}&src=ext`);
    eq('E15 a link without a query gets ?src', upgradeLink({ upgrade_url: 'https://dropit.example/upgrade' }), 'https://dropit.example/upgrade?src=ext');
    eq('E15 the hourly limit is never sold, even if a link came', upgradeLink({ upgrade_url: up, window: 'hour' }), null);
    eq('E15 no link, no upgrade', upgradeLink({ why: 'per_day' }), null);
    eq('E15 an unsafe link stays dropped', upgradeLink({ upgrade_url: 'javascript:alert(1)' }), null);
    eq('E15 no error body at all', upgradeLink(undefined), null);
  }
  if (lang === 'en') for (const [u, want] of [["https://dropit.example/upgrade?u=1", "https://dropit.example/upgrade?u=1"], ["http://127.0.0.1:8799/upgrade", "http://127.0.0.1:8799/upgrade"], ["http://localhost:8780/x", "http://localhost:8780/x"], ["javascript:alert(1)", null], ["data:text/html,<b>x</b>", null], ["file:///etc/passwd", null], ["obsidian://open?vault=x", null], ["http://evil.example/up", null], ["//evil.example/x", null], ["", null], [null, null]]) eq(`E13 a link from the service: ${String(u).slice(0, 40)}`, safeLink(u), want);
  eq(`E12 ${lang} storage full`, errorText('QUOTA_EXCEEDED', { field: 'bytes' }, 507, t), t.quotaBytes);
  ok(`E14 ${lang} device limit says how a free account gets a slot back`, t.errors.DEVICE_LIMIT_REACHED !== t.errors.DEVICE_REVOKED && (lang === 'en' ? /remove one/i : /移除一台/).test(errorText('DEVICE_LIMIT_REACHED', { limit: 3 }, 403, t)));
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
  const { scopeFromProbe, canAddDevices } = await import('../extension/pairing.js');
  eq('E33 the device list answered → full', scopeFromProbe(null), 'full');
  eq('E33 refused for scope → send-only', scopeFromProbe({ code: 'SCOPE_INSUFFICIENT', status: 403 }), 'ingest_only');
  eq('E33 offline → unknown, ask again next time', scopeFromProbe({ code: 'OFFLINE' }), null);
  eq('E33 a server error → unknown, not send-only', scopeFromProbe({ code: undefined, status: 500 }), null);
  ok('E33 only a full key offers adding a device', canAddDevices('full') && !canAddDevices('ingest_only') && !canAddDevices(null) && !canAddDevices(undefined));
  const popup = readFileSync(new URL('../extension/popup.js', import.meta.url), 'utf8');
  ok('E33 creating the account records full, joining with a code records ingest_only',
    /'\/v1\/accounts'[\s\S]{0,200}'full'\)/.test(popup) && /'\/v1\/pair\/claim'[\s\S]{0,300}'ingest_only'\)/.test(popup));
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

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
