/**
 * The extension's decisions that don't need a browser: metadata and its size budget, link or text,
 * file names, context menus, batches, what Enter sends, and error sentences.
 *
 *   node test/extension.test.mjs
 */
import {
  META_BUDGET, FROM_URL_MAX, BATCH_MAX, squash, clip, pageMeta, fromMeta, fitMeta, kindOf,
  fileNameOf, MENUS, FILE_EXT, groupsFor, intentOf, mb, fileSize, errorText,
} from '../extension/payload.js';
import { STRINGS } from '../extension/i18n.js';

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
  ok('E9 streamed media can still send the page', by['media-page'].contexts.includes('video') && by['media-page'].contexts.includes('audio'));
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
  eq(`E11 ${lang} hourly limit`, errorText('RATE_LIMITED', { window: 'hour', retry_after: 1800 }, 429, t), t.rateHour);
  eq(`E11 ${lang} older service`, errorText('RATE_LIMITED', { retry_after: 1800 }, 429, t), t.errors.RATE_LIMITED);
  eq(`E12 ${lang} queue full`, errorText('QUOTA_EXCEEDED', { field: 'items' }, 507, t), t.quotaItems);
  eq(`E12 ${lang} storage full`, errorText('QUOTA_EXCEEDED', { field: 'bytes' }, 507, t), t.quotaBytes);
  ok(`E12 ${lang} unknown code`, errorText('NOPE', {}, 500, t).includes('500'));
}
eq('mb', [mb(5 << 20), mb(100 << 20), mb(1.5 * 1048576)], ['5 MB', '100 MB', '1.5 MB']);
eq('fileSize', [fileSize(11), fileSize(14 * 1024), fileSize(2.34 * 1048576)], ['11 B', '14 KB', '2.3 MB']);

// E13 · batches
{
  eq('E13 one thing has no batch', groupsFor(1), [null]);
  const two = groupsFor(2, 'abcdefgh');
  eq('E13 two', two, [{ id: 'abcdefgh', i: 1, n: 2 }, { id: 'abcdefgh', i: 2, n: 2 }]);
  const max = groupsFor(BATCH_MAX);
  ok('E13 sixteen share one id', max.length === 16 && new Set(max.map((g) => g.id)).size === 1 && max[15].i === 16);
  ok('E13 random id fits the service rule', /^[A-Za-z0-9_-]{6,32}$/.test(max[0].id), max[0].id);
  let threw = null;
  try { groupsFor(BATCH_MAX + 1); } catch (err) { threw = err.code; }
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
}

console.log(`\n${pass} passed, ${fail} failed`);
process.exit(fail ? 1 : 0);
