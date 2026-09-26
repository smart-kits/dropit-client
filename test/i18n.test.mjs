/**
 * Every interface string must exist in both English and Simplified Chinese.
 * A key present in one language only would show up as `undefined` in the UI.
 *
 *   node test/i18n.test.mjs
 */
import { createRequire } from 'node:module';
import { readFileSync } from 'node:fs';

const require = createRequire(import.meta.url);
let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  cond ? pass++ : fail++;
  console.log(`${cond ? '  ok  ' : 'FAIL  '}${name}${cond ? '' : ' → ' + detail}`);
};

/** Flatten nested keys: { a: { b: 1 } } → ['a.b'] */
const keys = (o, p = '') => Object.entries(o).flatMap(([k, v]) =>
  v && typeof v === 'object' ? keys(v, `${p}${k}.`) : [`${p}${k}`]);
const diff = (a, b) => a.filter((k) => !b.includes(k));

function parity(name, en, zh) {
  const [ke, kz] = [keys(en), keys(zh)];
  ok(`${name}: same keys in en and zh`, !diff(ke, kz).length && !diff(kz, ke).length,
    `only en: ${diff(ke, kz)} · only zh: ${diff(kz, ke)}`);
  const arity = ke.filter((k) => {
    const get = (o) => k.split('.').reduce((x, s) => x?.[s], o);
    return typeof get(en) === 'function' && get(en).length !== get(zh)?.length;
  });
  ok(`${name}: templates take the same arguments`, !arity.length, arity.join(', '));
}

const cli = require('../cli/dropit');
parity('cli', cli.T.en, cli.T.zh);
ok('cli: picks zh for Chinese locale tags', ['zh', 'zh_CN.UTF-8', 'zh-Hans', 'ZH_tw'].every((t) => cli.pickLang(t) === 'zh'));
ok('cli: falls back to en', ['en_US.UTF-8', 'C', '', undefined].every((t) => cli.pickLang(t) === 'en'));

const ext = await import('../extension/i18n.js');
parity('extension', ext.STRINGS.en, ext.STRINGS.zh);

const locale = (l) => JSON.parse(readFileSync(new URL(`../extension/_locales/${l}/messages.json`, import.meta.url)));
parity('extension _locales', locale('en'), locale('zh_CN'));

const html = readFileSync(new URL('../extension/popup.html', import.meta.url), 'utf8');
const used = [...html.matchAll(/data-i18n="([^"]+)"/g)].map((m) => m[1]);
ok('extension: every data-i18n key exists', used.every((k) => k in ext.STRINGS.en.html), diff(used, Object.keys(ext.STRINGS.en.html)));

console.log(`\n${fail === 0 ? '✅' : '🛑'}  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
