/**
 * What `dropit watch` writes under the front matter of a text or link item.
 * Links with a title become Markdown links, a source page becomes a closing line,
 * and items with neither must come out exactly as before.
 *
 *   node test/cli-render.test.mjs
 */
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { markdownOf } = require('../cli/dropit');
let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  cond ? pass++ : fail++;
  console.log(`${cond ? '  ok  ' : 'FAIL  '}${name}${cond ? '' : ' → ' + detail}`);
};
const is = (name, got, want) => ok(name, got === want, `\n--- got ---\n${got}\n--- want ---\n${want}`);

// C1 · link + title + description
is('link + title → [title](url)',
  markdownOf({ kind: 'url', raw: 'https://example.com/post', meta: { title: 'A post' } }),
  '[A post](https://example.com/post)');
is('link + title + description → link, blank line, quote',
  markdownOf({ kind: 'url', raw: 'https://example.com/post', meta: { title: 'A post', description: 'What it is about' } }),
  '[A post](https://example.com/post)\n\n> What it is about');
is('multi-line description → every line quoted',
  markdownOf({ kind: 'url', raw: 'https://example.com/', meta: { title: 'T', description: 'one\r\n\ntwo\n' } }),
  '[T](https://example.com/)\n\n> one\n>\n> two');
is('surrounding whitespace in raw is dropped from the link',
  markdownOf({ kind: 'url', raw: '  https://example.com/a \n', meta: { title: 'T' } }),
  '[T](https://example.com/a)');
is('a lone http(s) address with a title is a link even if kind says text',
  markdownOf({ kind: 'text', raw: 'https://example.com/a', meta: { title: 'T' } }),
  '[T](https://example.com/a)');
is('text with a title stays text',
  markdownOf({ kind: 'text', raw: 'see https://example.com/a', meta: { title: 'T' } }),
  'see https://example.com/a');
is('kind url but not http(s) → never a link',
  markdownOf({ kind: 'url', raw: 'javascript:alert(1)', meta: { title: 'T' } }),
  'javascript:alert(1)');
is('description without a title → raw unchanged',
  markdownOf({ kind: 'url', raw: 'https://example.com/a', meta: { description: 'D' } }),
  'https://example.com/a');

// C2 · text + from
is('text + from with title → closing source line',
  markdownOf({ kind: 'text', raw: 'a quote from the page', meta: { from: { url: 'https://example.com/p', title: 'The page' } } }),
  'a quote from the page\n\n— [The page](https://example.com/p)');
is('text + from without title → host name',
  markdownOf({ kind: 'text', raw: 'a quote', meta: { from: { url: 'https://news.example.org/x/y?z=1' } } }),
  'a quote\n\n— [news.example.org](https://news.example.org/x/y?z=1)');
is('text + from with a blank title → host name',
  markdownOf({ kind: 'text', raw: 'a quote', meta: { from: { url: 'http://example.com/', title: ' [ ] ' } } }),
  'a quote\n\n— [example.com](http://example.com/)');
is('trailing newlines in text don\'t pile up before the source line',
  markdownOf({ kind: 'text', raw: 'line one\nline two\n\n', meta: { from: { url: 'https://example.com/' } } }),
  'line one\nline two\n\n— [example.com](https://example.com/)');
is('from that isn\'t http(s) → no source line',
  markdownOf({ kind: 'text', raw: 'x', meta: { from: { url: 'javascript:alert(1)', title: 'T' } } }),
  'x');
is('from that doesn\'t parse → no source line',
  markdownOf({ kind: 'text', raw: 'x', meta: { from: { url: 'https://', title: 'T' } } }),
  'x');
is('from without url → no source line',
  markdownOf({ kind: 'text', raw: 'x', meta: { from: { title: 'T' } } }),
  'x');
is('link + title + description + from → all three, source last',
  markdownOf({ kind: 'url', raw: 'https://a.example/', meta: { title: 'A', description: 'D', from: { url: 'https://b.example/', title: 'B' } } }),
  '[A](https://a.example/)\n\n> D\n\n— [B](https://b.example/)');

// C3 · old items, no meta to render: byte for byte what was written before
const before = (item) => `${item.raw}`;
const old = [
  { kind: 'text', raw: 'hello' },
  { kind: 'text', raw: 'hello', meta: null },
  { kind: 'text', raw: '  padded\n\nlines\n  ', meta: {} },
  { kind: 'url', raw: 'https://example.com/a' },
  { kind: 'url', raw: ' https://example.com/a\n', meta: { title: '' } },
  { kind: 'text', raw: '[not](a link) `code` *stars*' },
  { kind: 'text', raw: '' },
  { kind: 'text', raw: undefined },
];
for (const item of old) is(`unchanged: ${JSON.stringify(item)}`, markdownOf(item), before(item));

// Escaping
is('title loses [ ] and line breaks',
  markdownOf({ kind: 'url', raw: 'https://example.com/', meta: { title: '[Draft]\n  final [v2]' } }),
  '[Draft final v2](https://example.com/)');
is('backslash in a title can\'t escape the closing ]',
  markdownOf({ kind: 'url', raw: 'https://example.com/', meta: { title: 'C:\\dir\\' } }),
  '[C:\\\\dir\\\\](https://example.com/)');
is('url with ( ) → <url>',
  markdownOf({ kind: 'url', raw: 'https://en.wikipedia.org/wiki/Go_(game)', meta: { title: 'Go' } }),
  '[Go](<https://en.wikipedia.org/wiki/Go_(game)>)');
is('source url with a space → <url>',
  markdownOf({ kind: 'text', raw: 'x', meta: { from: { url: 'https://example.com/a b', title: 'T' } } }),
  'x\n\n— [T](<https://example.com/a b>)');
is('source url with < > \\ → escaped inside <url>',
  markdownOf({ kind: 'text', raw: 'x', meta: { from: { url: 'https://example.com/<a>\\', title: 'T' } } }),
  'x\n\n— [T](<https://example.com/\\<a\\>\\\\>)');
is('source url with a line break → encoded, link stays on one line',
  markdownOf({ kind: 'text', raw: 'x', meta: { from: { url: 'https://example.com/a\nb', title: 'T' } } }),
  'x\n\n— [T](https://example.com/a%0Ab)');
is('source title loses [ ] and line breaks',
  markdownOf({ kind: 'text', raw: 'x', meta: { from: { url: 'https://example.com/', title: 'a]\n[b' } } }),
  'x\n\n— [a b](https://example.com/)');
is('IPv6 host as source name keeps no brackets',
  markdownOf({ kind: 'text', raw: 'x', meta: { from: { url: 'http://[::1]:8080/' } } }),
  'x\n\n— [::1](http://[::1]:8080/)');

console.log(`\n${fail === 0 ? '✅' : '🛑'}  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
