/**
 * The CLI side of "hand what arrives to an AI agent": the rules file, the one rule, the first-time confirmation,
 * the commands (agents · handlers · run), what `watch` does after it writes an item, and `watch --exec`.
 * The agent is a stand-in (test/fake-agent.mjs); no real agent is ever started, and the home folder is a scratch one.
 *
 *   node test/cli-agents.test.mjs
 */
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawn, spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const HOME = fs.mkdtempSync(path.join(os.tmpdir(), 'dropit-agents-test-'));
process.env.HOME = HOME;
process.env.USERPROFILE = HOME;
process.env.DROPIT_LANG = 'en';
const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const CLI_PATH = path.join(here, '..', 'cli', 'dropit');
const C = require(CLI_PATH);
const FAKE = path.join(here, 'fake-agent.mjs');
const { T } = C;
const en = T.en;

let pass = 0, fail = 0;
const ok = (name, cond, detail = '') => {
  cond ? pass++ : fail++;
  process.stdout.write(`${cond ? '  ok  ' : 'FAIL  '}${name}${cond ? '' : ' → ' + detail}\n`);
};
const is = (name, got, want) => ok(name, JSON.stringify(got) === JSON.stringify(want),
  `\n  got  ${JSON.stringify(got)}\n  want ${JSON.stringify(want)}`);
const rejects = async (p) => { try { await p; return null; } catch (e) { return String(e.message); } };

const FILE = C.AGENTS_FILE;
ok('the rules file is beside the config but is not it', path.dirname(FILE) === path.dirname(C.CONFIG) && path.basename(FILE) === 'agents.json');
ok('…and it is under the scratch home, never the real one', FILE.startsWith(HOME));
const reset = () => { fs.rmSync(FILE, { force: true }); };
const bytes = () => fs.readFileSync(FILE, 'utf8');
const scratch = () => fs.mkdtempSync(path.join(HOME, 'work-'));
/** What the agent said, after the quote of what arrived */
const answerOf = (file) => fs.readFileSync(file, 'utf8').split('\n\n---\n\n')[1];

// What the machine "has": claude with its checked modes, codex found but unchecked, the rest absent
const AGENTS = [
  { id: 'claude', label: 'Claude Code', path: '/fake/bin/claude', verified: 'Claude Code 2.1.301', modes: ['think', 'files', 'web'] },
  { id: 'codex', label: 'Codex CLI', path: '/fake/bin/codex', verified: null, modes: [] },
  { id: 'grok', label: 'Grok CLI', path: null, verified: null, modes: [] },
  { id: 'gemini', label: 'Gemini CLI', path: null, verified: null, modes: [] },
  { id: 'pi', label: 'Pi', path: '/fake/bin/pi', verified: null, modes: [] },
];
const spawned = [];
/** Records what would have been started; a known agent is swapped for the stand-in so a run still gets an answer */
const spyingSpawn = (bin, args, opts) => {
  spawned.push({ bin, args, opts });
  return spawn(process.execPath, [FAKE], opts);
};
function makeIo(over = {}) {
  const log = { out: [], err: [], asked: [] };
  const answers = over.answers ?? [];
  const io = {
    out: (s) => log.out.push(s), err: (s) => log.err.push(s),
    ask: async (q) => { log.asked.push(q); return answers.shift() ?? ''; },
    detect: async () => over.agents ?? AGENTS,
    deps: { fs, os, path, spawn: over.spawn ?? spyingSpawn, env: { USER: 'tester', HOME, PATH: process.env.PATH } },
    env: over.env ?? { EDITOR: '' }, agentsFile: FILE, edit: over.edit, stdin: over.stdin,
    ...(over.io ?? {}),
  };
  return { io, log, text: () => log.out.join('\n') };
}
const custom = (extra = {}) => ({
  id: 'fake', name: 'Fake', enabled: true, auto: true, agent: 'custom', argv: [process.execPath, FAKE],
  kinds: ['text', 'url', 'image', 'file'], mode: 'think', prompt: 'Do it.', ...extra,
});
const put = (handlers, extra = {}) => C.writeAgents({ v: 1, consented: true, paused: false, handlers, ...extra });

// ── The rules file: read, write, damage ───────────────────────────────────
reset();
is('no file yet: an empty, unconfirmed set of rules', C.readAgents().data, { v: 1, consented: false, paused: false, handlers: [] });
put([custom()], { consented: false });
is('written and read back', C.readAgents().data.handlers[0].id, 'fake');
ok('owner-only, as the prompts are the person\'s own', (fs.statSync(FILE).mode & 0o777) === 0o600, (fs.statSync(FILE).mode & 0o777).toString(8));
ok('no temporary file is left beside it', fs.readdirSync(path.dirname(FILE)).every((n) => !n.endsWith('.tmp')), fs.readdirSync(path.dirname(FILE)).join());
reset();
for (const [name, text] of [
  ['not JSON', '{ "v": 1, "handlers": ['], ['empty', ''], ['a list', '[]'], ['no version', '{"handlers":[]}'], ['rules not a list', '{"v":1,"handlers":{}}'],
  ['a rule without an id', '{"v":1,"handlers":[{"name":"x"}]}'], ['two rules with one id', '{"v":1,"handlers":[{"id":"a"},{"id":"a"}]}'],
  ['written by a newer version', '{"v":2,"handlers":[]}'],
]) {
  fs.writeFileSync(FILE, text);
  const got = C.readAgents();
  ok(`damaged (${name}): reported with the file named, not a crash`, !got.data && got.error.includes(FILE) && got.error.includes('Nothing was changed'), JSON.stringify(got));
  const { io } = makeIo();
  const e1 = await rejects(C.handlersCmd([], io));
  const e2 = await rejects(C.handlersCmd(['add', '--yes'], makeIo().io));
  const e3 = await rejects(C.handlersCmd(['pause'], makeIo().io));
  ok(`damaged (${name}): listing and every change refuse`, e1 && e2 && e3 && e1.includes(FILE), `${e1} | ${e2} | ${e3}`);
  is(`damaged (${name}): the file is exactly as it was`, bytes(), text);
}
reset();

// ── The one rule ──────────────────────────────────────────────────────────
const ALL = ['ocr', 'audio', 'link', 'sum'];
const rule = C.newRule('claude', null, ALL, false);
is('the rule: one id, a name, automatic, permissions decided per item, no source kept', [rule.id, rule.name, rule.auto, rule.mode, rule.keepSource], ['auto', 'Auto', true, 'auto', false]);
is('all four parts cover every kind', rule.kinds, ['text', 'url', 'image', 'video', 'file']);
is('the prompt is the frame and the four lines (en)', rule.prompt,
  C.composePrompt(['ocr', 'audio', 'link', 'sum'], 'en'));
const ocrOnly = C.newRule('claude', null, ['ocr'], true);
is('only ocr: image and file kinds, a shorter prompt, the source kept', [ocrOnly.kinds, ocrOnly.prompt.split('\n').length, ocrOnly.keepSource], [['image', 'file'], 3, true]);
is('defaults: 120 s, 20000 characters, 10 MB, and no minimum length', [rule.timeoutSec, rule.maxChars, rule.maxFileMB, 'minChars' in rule], [120, 20000, 10, false]);
is('a custom rule keeps its command', C.newRule('custom', ['x', 'y'], ['sum'], false).argv, ['x', 'y']);
is('Chinese name', T.zh.ag.ruleName, '自动处理');
is('the parts a rule can be told to handle', C.PART_IDS, ALL);
const sumRule = () => ({ ...C.newRule('claude', null, ['sum'], false), id: 'summary', name: 'Summarize', kinds: ['text', 'url'],
  prompt: 'Summarize this in at most 3 short bullet points, in the language of the content.' });

// ── handlers add: the first-time confirmation ─────────────────────────────
{
  reset();
  const a = makeIo({ answers: ['n'] });
  const err = await rejects(C.handlersCmd(['add'], a.io));
  ok('"n" at the confirmation: nothing is created', err === en.ag.consentNeeded && !fs.existsSync(FILE), String(err));
  const shown_ = a.text();
  ok('it shows the whole command that will run', shown_.includes("/fake/bin/claude -p --safe-mode --tools '' --strict-mcp-config --no-session-persistence"), shown_);
  ok('…the permission, decided per item', shown_.includes(en.ag.permAuto));
  ok('…and that everything matching goes to the chosen agent, and dropit does not handle or store it',
    shown_.includes('Everything that arrives here and matches a rule will be handed to the agent you chose (Claude Code). dropit does not handle or store it.'), shown_);
  ok('…and that each arrived text becomes one file', shown_.includes(en.ag.consentFiles), shown_);
  ok('…and that what arrives is untrusted', /untrusted input/.test(shown_));
  const b = makeIo({ answers: ['y'] });
  await C.handlersCmd(['add'], b.io);
  const data = C.readAgents().data;
  ok('"y": the rule is written, confirmed, and uses the first agent with a checked mode', data.consented === true && data.handlers.length === 1
    && data.handlers[0].agent === 'claude' && data.handlers[0].id === 'auto' && data.handlers[0].enabled === true && data.handlers[0].auto === true, bytes());
  is('…and says what becomes of what arrives', b.log.out.some((l) => /becomes one file/.test(l)), true);
  const again = await rejects(C.handlersCmd(['add', '--yes'], makeIo().io));
  is('a second rule is refused, and the existing one is named', again, en.ag.ruleExists('auto'));
  is('…and nothing was changed', C.readAgents().data.handlers.length, 1);

  reset();
  const f = makeIo({ answers: [] });
  await C.handlersCmd(['add', '--yes'], f.io);
  ok('--yes skips the question, still shows the text, and still records the confirmation',
    f.log.asked.length === 0 && /handed to the agent you chose/.test(f.text()) && C.readAgents().data.consented === true);
  reset();
  await C.handlersCmd(['add', '--yes', '--only', 'ocr,link', '--keep-source'], makeIo().io);
  const o = C.readAgents().data.handlers[0];
  is('--only picks the parts, and the kinds and the prompt follow; --keep-source keeps the source', [o.parts, o.kinds, o.prompt.split('\n').length, o.keepSource], [['ocr', 'link'], ['url', 'image', 'file'], 4, true]);
}
{
  reset();
  const none = makeIo({ agents: AGENTS.map((a) => ({ ...a, path: a.id === 'claude' ? null : a.path })) });
  const err = await rejects(C.handlersCmd(['add', '--yes'], none.io));
  ok('no agent with a checked mode: says so, creates nothing', err === en.ag.noneVerified && !fs.existsSync(FILE), String(err));
  ok('…and the sentence points at the other agents and the custom command and invents no link', /--agent <codex/.test(err) && /-- <command>/.test(err) && !/https?:/.test(err));
  const cx = makeIo();
  await C.handlersCmd(['add', '--agent', 'codex', '--yes'], cx.io);
  const cr = C.readAgents().data.handlers[0];
  is('an agent without a checked mode becomes a command of the person\'s own, from the arguments its help suggests',
    [cr.agent, cr.argv], ['custom', ['/fake/bin/codex', 'exec', '--sandbox', 'read-only', '--skip-git-repo-check', '--ephemeral']]);
  ok('…and says its arguments were not checked, and how to change them', cx.text().includes(en.ag.agentNoMode('Codex CLI', '/fake/bin/codex exec --sandbox read-only --skip-git-repo-check --ephemeral')), cx.text());
  reset();
  await C.handlersCmd(['add', '--agent', 'pi', '--yes'], makeIo().io);
  is('Pi starts with no tools', C.readAgents().data.handlers[0].argv, ['/fake/bin/pi', '-p', '--no-tools']);
  reset();
  const gem = await rejects(C.handlersCmd(['add', '--agent', 'gemini', '--yes'], makeIo().io));
  ok('an agent that is not installed: refused', gem === en.ag.agentMissing('Gemini CLI') && !fs.existsSync(FILE), String(gem));
  const bogus = await rejects(C.handlersCmd(['add', '--agent', 'nope', '--yes'], makeIo().io));
  ok('an unknown agent: refused', bogus === en.ag.agentUnknown('nope'), String(bogus));
  for (const bad of [['--only'], ['--only', 'weekly'], ['--bogus'], ['--preset', 'summary'], ['--agent', 'claude', '--', 'x'], ['--agent', 'custom']]) {
    const e = await rejects(C.handlersCmd(['add', ...bad, '--yes'], makeIo().io));
    ok(`add with ${JSON.stringify(bad)}: refused, nothing created`, e && !fs.existsSync(FILE), String(e));
  }
  const cu = makeIo();
  await C.handlersCmd(['add', '--yes', '--', process.execPath, FAKE, '--flag'], cu.io);
  const h = C.readAgents().data.handlers[0];
  ok('a command of the person\'s own is kept as a list, never one string', h.agent === 'custom' && Array.isArray(h.argv) && h.argv.length === 3 && h.argv[2] === '--flag', JSON.stringify(h));
  ok('…and the confirmation names that dropit does not limit it', cu.text().includes(en.ag.permCustom));
  reset();
  await C.handlersCmd(['add', '--yes', '--agent', 'custom', '--', process.execPath, FAKE], makeIo().io);
  is('--agent custom with a command is still accepted', C.readAgents().data.handlers[0].agent, 'custom');
  reset();
  const noCmd = await rejects(C.handlersCmd(['add', '--agent', 'custom', '--yes'], makeIo().io));
  is('--agent custom with no command: refused', noCmd, en.ag.customNeedsCommand);
}

// ── enable · disable · remove · pause ─────────────────────────────────────
{
  reset();
  put([custom(), custom({ id: 'second', name: 'Second', enabled: false })], { consented: false });
  const a = makeIo({ answers: ['no'] });
  ok('switching a rule on when nothing was confirmed yet asks first', (await rejects(C.handlersCmd(['enable', 'second'], a.io))) === en.ag.consentNeeded && C.readAgents().data.handlers[1].enabled === false);
  await C.handlersCmd(['enable', 'second'], makeIo({ answers: ['y'] }).io);
  is('…and after a yes it is on, and confirmed', [C.readAgents().data.handlers[1].enabled, C.readAgents().data.consented], [true, true]);
  await C.handlersCmd(['disable', 'second'], makeIo().io);
  is('disable', C.readAgents().data.handlers[1].enabled, false);
  await C.handlersCmd(['pause'], makeIo().io);
  is('pause', C.readAgents().data.paused, true);
  await C.handlersCmd(['resume'], makeIo().io);
  is('resume', C.readAgents().data.paused, false);
  const results = path.join(scratch(), 'keep.Fake.md');
  fs.writeFileSync(results, 'a result');
  await C.handlersCmd(['remove', 'second'], makeIo().io);
  is('remove takes the rule out and nothing else', [C.readAgents().data.handlers.map((h) => h.id), fs.existsSync(results)], [['fake'], true]);
  ok('an unknown id is named', /nope/.test(await rejects(C.handlersCmd(['disable', 'nope'], makeIo().io))));
  const list = makeIo();
  await C.handlersCmd([], list.io);
  ok('the list shows id, name, on/off, auto/manual, agent, kinds and permission', list.text() === en.ag.ruleLine('fake', 'Fake', 'on', 'auto', 'custom', 'text,url,image,file', en.ag.permCustom), list.text());
  ok('a plain `handlers` makes no change to the file', JSON.stringify(C.readAgents().data.handlers.map((h) => h.id)) === '["fake"]');
}

// ── set: strict values ────────────────────────────────────────────────────
{
  reset();
  put([custom(), sumRule()]);
  const set = (...a) => rejects(C.handlersCmd(['set', ...a], makeIo().io));
  const get = (id = 'fake') => C.readAgents().data.handlers.find((h) => h.id === id);
  is('prompt (words typed without quotes join up)', [await set('fake', 'prompt=Say', 'hi', 'twice'), get().prompt], [null, 'Say hi twice']);
  ok('prompt: empty refused', await set('fake', 'prompt=') === en.ag.setPrompt);
  ok('prompt: far too long refused', await set('fake', 'prompt=' + 'x'.repeat(10001)) === en.ag.setPrompt);
  is('prompt: stays as it was after a refusal', get().prompt, 'Say hi twice');
  is('minChars', [await set('fake', 'minChars=200'), get().minChars], [null, 200]);
  is('minChars: empty clears it', [await set('fake', 'minChars='), 'minChars' in get()], [null, false]);
  for (const bad of ['minChars=-1', 'minChars=1.5', 'minChars=abc', 'minChars=2000000', 'timeoutSec=0', 'timeoutSec=9999', 'timeoutSec=', 'maxChars=0', 'maxFileMB=0']) {
    ok(`${bad}: refused`, await set('fake', bad) !== null, bad);
  }
  is('timeoutSec', [await set('fake', 'timeoutSec=30'), get().timeoutSec], [null, 30]);
  is('kinds: comma separated, repeats dropped', [await set('fake', 'kinds=text, url,text'), get().kinds], [null, ['text', 'url']]);
  ok('kinds: an unknown kind refused', await set('fake', 'kinds=text,audio') !== null);
  ok('kinds: none refused', await set('fake', 'kinds=') !== null);
  is('mode on a custom rule', [await set('fake', 'mode=files'), get().mode], [null, 'files']);
  ok('mode: an unknown one refused', await set('fake', 'mode=root') !== null);
  is('mode on claude: its checked modes', [await set('summary', 'mode=web'), get('summary').mode], [null, 'web']);
  is('mode auto: per item again', [await set('summary', 'mode=auto'), get('summary').mode], [null, 'auto']);
  ok('mode: auto is allowed for a custom rule too', await set('fake', 'mode=auto') === null);
  is('parts: the kinds follow, and so does a prompt nobody edited', [await set('summary', 'prompt=' + C.composePrompt(['sum'], 'en')), await set('summary', 'parts=ocr,audio'), get('summary').kinds, get('summary').prompt],
    [null, null, ['image', 'video', 'file'], C.composePrompt(['ocr', 'audio'], 'en')]);
  // The reminder about transcription is said when audio & video has just been ticked, and not otherwise
  {
    const said = async (...args) => { const m = makeIo(); await C.handlersCmd(['set', ...args], m.io); return m.log.out.join('\n'); };
    await set('summary', 'parts=sum');
    ok('audio ticked now: says what Claude cannot read, and where the guide is', (await said('summary', 'parts=sum,audio')).includes(en.ag.audioNote) && (await said('summary', 'parts=sum')).includes('Updated'));
    await set('summary', 'parts=sum,audio');
    ok('audio already ticked: said nothing more', !(await said('summary', 'parts=sum,audio,link')).includes(en.ag.audioNote));
    ok('audio not ticked: said nothing', !(await said('summary', 'parts=sum,link')).includes(en.ag.audioNote));
  }
  is('parts: a prompt the person edited stays as it is', [await set('summary', 'prompt=My own words'), await set('summary', 'parts=link'), get('summary').kinds, get('summary').prompt], [null, null, ['url'], 'My own words']);
  ok('parts: an unknown part, or none, refused', await set('summary', 'parts=ocr,nope') === en.ag.setParts(C.PART_IDS.join(', ')) && await set('summary', 'parts=') !== null);
  is('keepSource', [await set('summary', 'keepSource=true'), get('summary').keepSource, await set('summary', 'keepSource=false'), get('summary').keepSource], [null, true, null, false]);
  ok('keepSource: only true or false', await set('summary', 'keepSource=yes') === en.ag.setKeep);
  const codexRule = { ...custom({ id: 'cx' }), agent: 'codex', argv: undefined };
  put([codexRule]);
  ok('mode on an agent with no checked mode: refused', /no checked/.test(await set('cx', 'mode=files')));
  put([custom(), custom({ id: 'o', name: 'Other' })]);
  is('auto', [await set('fake', 'auto=false'), get().auto], [null, false]);
  ok('auto: only true or false', await set('fake', 'auto=maybe') === en.ag.setAuto);
  is('name', [await set('fake', 'name=Tidy', 'up'), get().name], [null, 'Tidy up']);
  ok('name: empty, too long, control characters refused', await set('fake', 'name=') !== null && await set('fake', 'name=' + 'x'.repeat(61)) !== null && await set('fake', 'name=a\u0007b') !== null);
  ok('name: one another rule\'s results already use is refused', /\(o\)/.test(await set('fake', 'name=Other')));
  is('webDomain', [await set('fake', 'webDomain=Example.com'), get().webDomain], [null, 'example.com']);
  for (const bad of ['webDomain=http://example.com', 'webDomain=a b', 'webDomain=-x.com', 'webDomain=a/b']) ok(`${bad}: refused`, await set('fake', bad) === en.ag.setDomain, bad);
  is('webDomain: empty clears it', [await set('fake', 'webDomain='), 'webDomain' in get()], [null, false]);
  is('argv on a custom rule', [await set('fake', 'argv=["node","x.js"]'), get().argv], [null, ['node', 'x.js']]);
  ok('argv: not a list of strings refused', await set('fake', 'argv=node x.js') === en.ag.setArgv && await set('fake', 'argv=[1]') === en.ag.setArgv);
  ok('a field that does not exist is refused', /agent/.test(await set('fake', 'agent=claude')));
  ok('no =: usage', await set('fake', 'prompt') === en.ag.setUsage);
  put([custom()]);
  const noEditor = await rejects(C.handlersCmd(['edit', 'fake'], makeIo({ env: {} }).io));
  is('edit without $EDITOR points at set prompt=', noEditor, en.ag.editNoEditor);
  let seen = null;
  await C.handlersCmd(['edit', 'fake'], makeIo({
    env: { EDITOR: 'x' },
    edit: (file) => { seen = fs.readFileSync(file, 'utf8'); fs.writeFileSync(file, 'A new prompt.\n\n'); return true; },
  }).io);
  is('edit: the editor gets the prompt in a temporary file, and what is saved becomes the prompt', [seen, get().prompt], ['Do it.\n', 'A new prompt.']);
  const failed = await rejects(C.handlersCmd(['edit', 'fake'], makeIo({ env: { EDITOR: 'x' }, edit: () => false }).io));
  ok('edit: an editor that fails changes nothing', failed === en.ag.editFailed && get().prompt === 'A new prompt.');
  await C.handlersCmd(['edit', 'fake'], makeIo({ env: { EDITOR: 'x' }, edit: (file) => { fs.writeFileSync(file, '  \n'); return true; } }).io).catch(() => {});
  is('edit: an emptied prompt is refused', get().prompt, 'A new prompt.');
}

// ── test: shows, doesn't run, unless asked ────────────────────────────────
{
  reset();
  put([custom(), sumRule()]);
  spawned.length = 0;
  const a = makeIo();
  await C.handlersCmd(['test', 'summary'], a.io);
  is('test: nothing is started by default', spawned.length, 0);
  const text = a.text();
  ok('test: the command, with every argument', text.includes("/fake/bin/claude -p --safe-mode --tools '' --strict-mcp-config --no-session-persistence"), text);
  ok('test: the working folder, the environment and the permission', /empty temporary folder/.test(text) && /PATH=\/fake\/bin:\/usr\/bin:\/bin/.test(text) && /USER=tester/.test(text) && text.includes(en.ag.permAuto));
  ok('test: the whole input, exactly as the agent gets it, with example content', text.includes('Summarize this in at most 3 short bullet points')
    && text.includes('<content kind="text">\nCall Sam on Friday') && /untrusted data/.test(text), text);
  ok('test: says nothing was run and how to run it once', /Nothing was run/.test(text) && /--run/.test(text));
  const u = makeIo();
  await C.handlersCmd(['test', 'summary', '--sample', 'url'], u.io);
  ok('test: a link sample', u.text().includes('<content kind="url">\nhttps://example.com/post') && u.text().includes('title: An example post'));
  const img = makeIo();
  await C.handlersCmd(['test', 'fake', '--sample', 'image'], img.io);
  ok('test: an image sample for a rule that takes images', img.text().includes('<content kind="image">') && img.text().includes('filename: sample.png'));
  const no = makeIo();
  await C.handlersCmd(['test', 'summary', '--sample', 'video'], no.io);
  ok('test: a sample the rule does not take: says why, shows no command', /This rule would not run on this sample/.test(no.text()) && !/Command:/.test(no.text()));
  ok('test: an unknown sample refused', (await rejects(C.handlersCmd(['test', 'summary', '--sample', 'audio'], makeIo().io))) === en.ag.badSample);
  const declined = makeIo({ answers: ['n'] });
  await rejects(C.handlersCmd(['test', 'fake', '--run'], declined.io));
  is('test --run: asks first, and a "n" starts nothing', [declined.log.asked.length, spawned.length], [1, 0]);
  const ran = makeIo({ answers: ['y'] });
  await C.handlersCmd(['test', 'fake', '--run'], ran.io);
  ok('test --run: after a yes it runs once and shows the answer', spawned.length === 1 && /The agent answered:\nSUMMARY \d+/.test(ran.text()), ran.text());
  spawned.length = 0;
  await C.handlersCmd(['test', 'fake', '--run', '--yes'], makeIo().io);
  is('test --run --yes: runs without asking', spawned.length, 1);
}

// ── run: by hand, end to end ──────────────────────────────────────────────
{
  reset();
  put([custom(), custom({ id: 'off', name: 'Off', enabled: false })]);
  const dir = scratch();
  const note = path.join(dir, 'idea.txt');
  fs.writeFileSync(note, 'Remember the milk.\n');
  const before = fs.readFileSync(note);
  const a = makeIo();
  await C.runCmd(['fake', note], a.io);
  const out = path.join(dir, 'idea.Fake.md');
  ok('run: the result is a separate file next to the original, named <name>.<rule>.md', fs.existsSync(out), fs.readdirSync(dir).join());
  const lines = fs.readFileSync(out, 'utf8').split('\n');
  is('run: what arrived quoted, a rule, then the answer', [lines[0], lines[1], lines[2], lines[3], /^SUMMARY \d+$/.test(lines[4])], ['> Remember the milk.', '', '---', '', true]);
  ok('run: the original is byte for byte the same', fs.readFileSync(note).equals(before));
  is('run: prints where the result went', a.log.out, [`✓ ${out}`]);
  await C.runCmd(['fake', note], makeIo().io);
  ok('run: a second time does not overwrite: it makes another file', fs.existsSync(path.join(dir, 'idea.Fake 2.md')) && fs.readFileSync(out, 'utf8').split('\n')[0] === '> Remember the milk.');
  ok('run: a rule that is off refuses', /disabled/.test(await rejects(C.runCmd(['off', note], makeIo().io))));
  ok('run: an unknown rule refuses', /nope/.test(await rejects(C.runCmd(['nope', note], makeIo().io))));
  ok('run: a missing file refuses', (await rejects(C.runCmd(['fake', path.join(dir, 'gone.txt')], makeIo().io))) === en.fileNotFound(path.join(dir, 'gone.txt')));
  ok('run: a folder refuses', (await rejects(C.runCmd(['fake', dir], makeIo().io))) === en.notAFile(dir));
  ok('run: no rule id gives usage', (await rejects(C.runCmd([], makeIo().io))) === en.ag.runUsage);
  const link = path.join(dir, 'page.md');
  fs.writeFileSync(link, 'https://example.com/a');
  put([custom({ kinds: ['url'], prompt: 'MODE:stdin' })]);
  await C.runCmd(['fake', link], makeIo().io);
  ok('run: a file holding only a link is a link', fs.readFileSync(path.join(dir, 'page.Fake.md'), 'utf8').includes('&lt;content kind="url">'));   // the answer is cleaned, so the stand-in's echo shows tags as text
  put([custom({ kinds: ['image'], mode: 'files', prompt: 'MODE:env' })]);
  fs.writeFileSync(path.join(dir, 'pic.png'), 'x');
  await C.runCmd(['fake', path.join(dir, 'pic.png')], makeIo().io);
  ok('run: an image goes in as an image, with its file in the agent\'s empty working folder', JSON.parse(answerOf(path.join(dir, 'pic.Fake.md'))).cwd.join() === 'pic.png');
  put([custom({ kinds: ['file'], mode: 'files', prompt: 'MODE:env', maxFileMB: 1 })]);
  fs.writeFileSync(path.join(dir, 'blob.bin'), Buffer.from([0, 1, 2, 3, 0]));
  fs.writeFileSync(path.join(dir, 'big.bin'), Buffer.alloc(1024 * 1024 + 1));
  await C.runCmd(['fake', path.join(dir, 'blob.bin')], makeIo().io);
  ok('run: a file that is not text is a file', fs.existsSync(path.join(dir, 'blob.Fake.md')));
  spawned.length = 0;
  const big = await rejects(C.runCmd(['fake', path.join(dir, 'big.bin')], makeIo().io));
  ok('run: a file over the rule\'s limit is not read in or handed over', /limit of 1 MB/.test(big) && spawned.length === 0 && !fs.existsSync(path.join(dir, 'big.Fake.md')), String(big));
  put([custom()]);
  const cwd = process.cwd();
  process.chdir(dir);
  try {
    const s = makeIo({ stdin: async () => 'text from a pipe\n' });
    await C.runCmd(['fake', '-'], s.io);
    ok('run -: text from standard input becomes a result in the current folder', /^✓ .*stdin-\d{14}\.Fake\.md$/.test(s.log.out[0]) && fs.readFileSync(s.log.out[0].slice(2), 'utf8').startsWith('> text from a pipe\n\n---\n\n'), s.log.out.join());
    ok('run -: nothing to read refuses', (await rejects(C.runCmd(['fake', '-'], makeIo({ stdin: async () => '  \n' }).io))) === en.ag.runNothing);
  } finally { process.chdir(cwd); }
  // a failing agent is one error, and writes no file
  put([custom({ prompt: 'MODE:fail' })]);
  const failed = await rejects(C.runCmd(['fake', note], makeIo().io));
  ok('run: an agent that fails: one message with its exit code and what it said, and no result file', /exited with code 3: boom/.test(failed) && !fs.existsSync(path.join(dir, 'idea.Fake 3.md')), String(failed));
  // an unconfirmed set of rules does not run
  put([custom()], { consented: false });
  is('run: nothing is run until the first confirmation was given', await rejects(C.runCmd(['fake', note], makeIo().io)), en.ag.runNoConsent);
  // a known agent: the program is where detection found it, the arguments are the checked ones, and the content is on stdin
  put([sumRule()]);
  spawned.length = 0;
  await C.runCmd(['summary', note], makeIo().io);
  const s0 = spawned[0];
  ok('run: a known agent starts the detected program with the checked arguments', s0.bin === '/fake/bin/claude' && s0.args.join(' ') === ['-p', '--safe-mode', '--tools', '', '--strict-mcp-config', '--no-session-persistence'].join(' ') && s0.opts.shell === false, JSON.stringify(s0.args));
  ok('run: the content is not on the command line', !s0.args.some((x) => x.includes('milk')) && !s0.bin.includes('milk'));
}

// ── watch: what happens after an item is written ──────────────────────────
{
  const dir = scratch();
  const logs = { out: [], err: [] };
  const ctx = () => ({ deps: makeIo().io.deps, detect: async () => AGENTS, log: (s) => logs.out.push(s), err: (s) => logs.err.push(s), agentsFile: FILE });
  const item = (seq, extra = {}) => ({ seq, kind: 'text', source: 'cli', created_at: Date.parse('2026-10-17T08:00:00Z'), raw: `note ${seq}`, meta: {}, ...extra });
  const w1 = await C.writeItem(dir, item(5));
  is('writeItem: a text item is a new file', [path.basename(w1.file), w1.fresh], ['2026-10-17-5.md', true]);
  const w2 = await C.writeItem(dir, item(5));
  is('writeItem: the same again is not new', [w2.file === w1.file, w2.fresh], [true, false]);
  const x3 = await C.writeItem(dir, item(5, { created_at: Date.parse('2026-10-17T09:30:00Z'), raw: 'another account, same seq' }));
  is('writeItem: another item with the same day and seq is not taken as written — it gets its own file', [path.basename(x3.file), x3.fresh], ['2026-10-17-5-2.md', true]);
  is('…and the first stays as it was', fs.readFileSync(w1.file, 'utf8').includes('note 5'), true);
  const x4 = await C.writeItem(dir, item(5, { created_at: Date.parse('2026-10-17T09:30:00Z'), raw: 'another account, same seq' }));
  is('…and that one again is not new either', [x4.file === x3.file, x4.fresh], [true, false]);
  const original = fs.readFileSync(w1.file, 'utf8');
  const kc = (o) => custom({ keepSource: true, ...o });   // the source is kept: the answer is a separate file

  put([kc({ id: 'a', name: 'Auto' }), kc({ id: 'm', name: 'Manual', auto: false }), kc({ id: 'x', name: 'Off', enabled: false }), kc({ id: 'k', name: 'Links', kinds: ['url'] })]);
  let r = await C.processArrived(ctx(), item(5), w1.file);
  is('only rules that are on and automatic run', r, [{ id: 'a', status: 'written' }, { id: 'k', status: 'skipped:kind' }]);
  const res = path.join(dir, '2026-10-17-5.Auto.md');
  ok('with the source kept, the result is <original without extension>.<rule>.md: what arrived quoted, then the answer', fs.readFileSync(res, 'utf8').startsWith('> note 5\n\n---\n\nSUMMARY'), fs.existsSync(res) && fs.readFileSync(res, 'utf8'));
  ok('the original is untouched', fs.readFileSync(w1.file, 'utf8') === original);
  is('it says which file it wrote, and says nothing about a rule that is just not for this kind', [logs.out, logs.err], [['✎ 2026-10-17-5.Auto.md'], []]);
  const first = fs.readFileSync(res, 'utf8');
  r = await C.processArrived(ctx(), item(5), w1.file);
  is('delivered twice: the result exists, so nothing is run again', [r[0], fs.readFileSync(res, 'utf8') === first], [{ id: 'a', status: 'exists' }, true]);
  spawned.length = 0;
  fs.rmSync(res);
  put([kc({ id: 'a', name: 'Auto' })], { paused: true });
  is('paused: nothing runs', [await C.processArrived(ctx(), item(5), w1.file), fs.existsSync(res)], [[], false]);
  put([kc({ id: 'a', name: 'Auto' })], { consented: false });
  is('not confirmed: nothing runs', [await C.processArrived(ctx(), item(5), w1.file), fs.existsSync(res)], [[], false]);
  put([kc({ id: 'a', name: 'Auto', enabled: undefined })]);
  is('a rule that was never switched on does not run', [await C.processArrived(ctx(), item(5), w1.file), fs.existsSync(res)], [[], false]);

  // a failing agent: a line on stderr, no file, and the call still returns normally
  logs.out.length = 0; logs.err.length = 0;
  put([kc({ id: 'a', name: 'Auto', prompt: 'MODE:fail' }), kc({ id: 'b', name: 'Second' })]);
  r = await C.processArrived(ctx(), item(5), w1.file);
  is('a failing rule: one line to stderr, the next rule still runs', [logs.err.length, r.map((x) => x.status)], [1, ['failed:exit:3', 'written']]);
  ok('…the line names the rule and the file', /Auto/.test(logs.err[0]) && /2026-10-17-5\.md/.test(logs.err[0]) && /code 3/.test(logs.err[0]), logs.err[0]);
  ok('…and the failed one left no file', !fs.existsSync(path.join(dir, '2026-10-17-5.Auto.md')));
  // a timeout
  logs.err.length = 0;
  put([kc({ id: 'a', name: 'Slow', prompt: 'MODE:sleep', timeoutSec: 1 })]);
  r = await C.processArrived(ctx(), item(5), w1.file);
  ok('a rule that never answers is stopped, and reported', r[0].status === 'failed:timeout' && /stopped after 1 s/.test(logs.err[0]), JSON.stringify([r, logs.err]));
  // rules run one after the other
  put([kc({ id: 'a', name: 'One' }), kc({ id: 'b', name: 'Two' })]);
  let running = 0, most = 0;
  const slowSpawn = (b, a, o) => { running++; most = Math.max(most, running); const c = spawn(process.execPath, [FAKE], o); c.on('close', () => { running--; }); return c; };
  const sctx = { ...ctx(), deps: { ...makeIo().io.deps, spawn: slowSpawn } };
  await C.processArrived(sctx, item(6), (await C.writeItem(dir, item(6))).file);
  is('rules for one item run one at a time', most, 1);

  // a binary item: its file is the original, results go beside it
  put([custom({ id: 'a', name: 'Auto', kinds: ['file', 'image'], mode: 'files', prompt: 'MODE:env' })]);
  const bin = path.join(dir, '2026-10-17-7-photo.png');
  fs.writeFileSync(bin, 'png bytes');
  const rb = await C.processArrived(ctx(), item(7, { kind: 'image', url: 'https://x.test/f', raw: '', meta: { filename: 'photo.png' } }), bin);
  ok('a file item: the answer sits next to the file, which is not changed', rb[0].status === 'written' && fs.existsSync(path.join(dir, '2026-10-17-7-photo.Auto.md')) && fs.readFileSync(bin, 'utf8') === 'png bytes');
  ok('…and in files mode the agent got a copy of it', JSON.parse(answerOf(path.join(dir, '2026-10-17-7-photo.Auto.md'))).cwd.join() === '2026-10-17-7-photo.png');

  // no source kept: a text item turns into its answer
  put([custom({ id: 'a', name: 'Auto' })]);
  logs.out.length = 0; logs.err.length = 0;
  const w3 = await C.writeItem(dir, item(30));
  const before3 = fs.readFileSync(w3.file, 'utf8');
  const head3 = before3.slice(0, before3.indexOf('\n---\n\n') + 6);
  const r3 = await C.processArrived(ctx(), item(30), w3.file);
  const after3 = fs.readFileSync(w3.file, 'utf8');
  is('a text item turns into its answer: the same file, its own front matter, then what arrived quoted and the result', [r3, after3.startsWith(head3 + '> note 30\n\n---\n\nSUMMARY'), /^---\ndropit_seq: 30\n/.test(after3)], [[{ id: 'a', status: 'written' }], true, true]);
  is('…no second file, and one line saying which file changed', [fs.existsSync(path.join(dir, '2026-10-17-30.Auto.md')), logs.out], [false, ['✎ 2026-10-17-30.md']]);
  is('pulled again: it is not turned into its answer twice', [await C.processArrived(ctx(), item(30), w3.file, { fresh: false }), fs.readFileSync(w3.file, 'utf8') === after3], [[], true]);
  // ── a backlog: only the newest few go to the rule (the rest are written, and said so) ──
  put([kc({ id: 'a', name: 'Auto' })]);
  const batchOf = async (from, n) => { const out = []; for (let i = 0; i < n; i++) { const it = item(from + i); out.push({ item: it, written: await C.writeItem(dir, it) }); } return out; };
  const answered = (from, n) => Array.from({ length: n }, (_, i) => fs.existsSync(path.join(dir, `2026-10-17-${from + i}.Auto.md`)));
  const watching = (over = {}) => ({ exec: null, noHandlers: false, recent: null, queue: C.newQueue(), ctx: ctx(), ...over });
  const said = [];
  const realLog = console.log;
  console.log = (...a) => { const l = a.join(' '); if (/older item/.test(l)) said.push(l); };
  let walk = watching();
  C.handOn(walk, dir, await batchOf(100, 7)); await walk.queue.tail;
  is('seven arrive at once: only the newest three are handed to the rule (the default)', answered(100, 7), [false, false, false, false, true, true, true]);
  ok('…and one line says how many were left out, and how to change it', said.length === 1 && /4 older items/.test(said[0]) && /newest 3/.test(said[0]) && /handlers recent/.test(said[0]), JSON.stringify(said));
  said.length = 0; walk = watching();
  C.handOn(walk, dir, await batchOf(120, 3)); await walk.queue.tail;
  is('three or fewer: all of them, and nothing said', [answered(120, 3), said.length], [[true, true, true], 0]);
  walk = watching({ recent: 0 });
  C.handOn(walk, dir, await batchOf(130, 5)); await walk.queue.tail;
  is('--recent 0: every one of them', answered(130, 5), [true, true, true, true, true]);
  put([kc({ id: 'a', name: 'Auto' })], { recent: 2 });
  walk = watching();
  C.handOn(walk, dir, await batchOf(140, 4)); await walk.queue.tail;
  is('the rules file can say how many: two', answered(140, 4), [false, false, true, true]);
  walk = watching({ recent: 1 });
  C.handOn(walk, dir, await batchOf(150, 3)); await walk.queue.tail;
  is('--recent beats the rules file', answered(150, 3), [false, false, true]);
  console.log = realLog;
  put([kc({ id: 'a', name: 'Auto' })]);
  {
    const a = makeIo();
    await C.handlersCmd(['recent'], a.io);
    ok('handlers recent: says the default', a.text() === en.ag.recentIs(3), a.text());
    const b = makeIo();
    await C.handlersCmd(['recent', '5'], b.io);
    is('handlers recent 5: kept in the rules file, beside the rule', [b.text(), JSON.parse(fs.readFileSync(FILE, 'utf8')).recent], [en.ag.recentSet(5), 5]);
    const c = makeIo();
    await C.handlersCmd(['recent'], c.io);
    is('…and shown again', c.text(), en.ag.recentIs(5));
    const z = makeIo();
    await C.handlersCmd(['recent', '0'], z.io);
    is('handlers recent 0: all of them', [z.text(), JSON.parse(fs.readFileSync(FILE, 'utf8')).recent], [en.ag.recentSet(0), 0]);
    for (const bad of ['x', '-1', '1001', '2.5', '']) ok(`handlers recent ${JSON.stringify(bad)} is refused`, (await rejects(C.handlersCmd(['recent', bad], makeIo().io))) === en.ag.recentBad);
    fs.writeFileSync(FILE, JSON.stringify({ v: 1, handlers: [], recent: 'many' }));
    ok('a rules file with a recent that is not a number is not read', /recent/.test((await rejects(C.handlersCmd([], makeIo().io))) ?? ''));
    put([kc({ id: 'a', name: 'Auto' })]);
  }
  // ── a model, for Claude Code ──
  {
    put([custom({ id: 'c', name: 'Claude', agent: 'claude', argv: undefined, mode: 'auto' })]);
    await C.handlersCmd(['set', 'c', 'model=sonnet'], makeIo().io);
    is('handlers set model= on a Claude Code rule', C.readAgents().data.handlers[0].model, 'sonnet');
    await C.handlersCmd(['set', 'c', 'model='], makeIo().io);
    ok('…empty clears it', C.readAgents().data.handlers[0].model === undefined);
    for (const bad of ['a b', '-x', '--model', 'x;y']) ok(`a model ${JSON.stringify(bad)} is refused`, (await rejects(C.handlersCmd(['set', 'c', `model=${bad}`], makeIo().io))) === en.ag.setModel);
    put([kc({ id: 'k', name: 'Mine' })]);
    ok('a custom command takes no model field: its own arguments are the person\'s', (await rejects(C.handlersCmd(['set', 'k', 'model=sonnet'], makeIo().io))) === en.ag.setModel);
    put([kc({ id: 'a', name: 'Auto' })]);
  }
  // edited while the agent worked: the file stays as the person has it, and the answer goes beside it
  put([custom({ id: 'a', name: 'Auto' })]);
  const w6 = await C.writeItem(dir, item(33));
  const editing = (file) => ({ ...ctx(), deps: { ...makeIo().io.deps, spawn: (b, a, o) => { fs.appendFileSync(file, '\nMY OWN LINE\n'); return spawn(process.execPath, [FAKE], o); } } });
  const r6 = await C.processArrived(editing(w6.file), item(33), w6.file);
  const kept6 = fs.readFileSync(w6.file, 'utf8');
  ok('edited while the agent worked: the person\'s file is left exactly as they have it', kept6.includes('MY OWN LINE') && !kept6.includes('SUMMARY'), kept6);
  const beside6 = path.join(dir, '2026-10-17-33.Auto.md');
  ok('…and the answer is written beside it, so the work is not lost', r6[0].status === 'written' && fs.existsSync(beside6) && fs.readFileSync(beside6, 'utf8').includes('> note 33\n\n---\n\nSUMMARY'), JSON.stringify(r6));
  // the page a text came from stays at the end when the body becomes the answer
  const w7 = await C.writeItem(dir, item(34, { meta: { from: { url: 'https://example.com/p', title: 'The page' } } }));
  const src7 = (/(— \[[^\n]*\))\s*$/.exec(fs.readFileSync(w7.file, 'utf8')) ?? [])[1];
  await C.processArrived(ctx(), item(34, { meta: { from: { url: 'https://example.com/p', title: 'The page' } } }), w7.file);
  const after7 = fs.readFileSync(w7.file, 'utf8');
  ok('the source page line stays at the end of the answer', !!src7 && after7.includes('SUMMARY') && after7.trimEnd().endsWith(src7), JSON.stringify([src7, after7.slice(-120)]));
  put([custom({ id: 'a', name: 'Auto', prompt: 'MODE:fail' })]);
  const w4 = await C.writeItem(dir, item(31));
  const before4 = fs.readFileSync(w4.file, 'utf8');
  const r4 = await C.processArrived(ctx(), item(31), w4.file);
  is('a failed run leaves the item exactly as it arrived', [r4[0].status, fs.readFileSync(w4.file, 'utf8') === before4], ['failed:exit:3', true]);
  put([custom({ id: 'a', name: 'Auto', kinds: ['url'] })]);
  const w5 = await C.writeItem(dir, item(32, { kind: 'url', raw: 'https://example.com/z' }));
  const before5 = fs.readFileSync(w5.file, 'utf8');
  await C.processArrived(ctx(), item(32, { kind: 'url', raw: 'https://example.com/z' }), w5.file);
  ok('a link is quoted too', fs.readFileSync(w5.file, 'utf8').includes('> https://example.com/z\n\n---\n\nSUMMARY') && fs.readFileSync(w5.file, 'utf8') !== before5);

  // a damaged rules file: one line, once, and receiving goes on
  logs.err.length = 0;
  fs.writeFileSync(FILE, '{ broken');
  const c2 = ctx();
  await C.processArrived(c2, item(8), w1.file);
  await C.processArrived(c2, item(9), w1.file);
  ok('a damaged rules file: one line for the whole run, not one per item', logs.err.length === 1 && logs.err[0].includes(FILE), logs.err.join('|'));
  is('…and the file is untouched', fs.readFileSync(FILE, 'utf8'), '{ broken');

  // afterWrite: the queue, --no-handlers
  put([kc({ id: 'a', name: 'Auto' })]);
  const mk = (extra = {}) => ({ exec: null, noHandlers: false, queue: C.newQueue(), ctx: ctx(), ...extra });
  const st = mk();
  const t0 = await C.writeItem(dir, item(20));
  C.afterWrite(st, dir, item(20), t0);
  ok('afterWrite hands the item to the queue and returns before the agent answered', st.queue.pending === 1 && !fs.existsSync(path.join(dir, '2026-10-17-20.Auto.md')));
  await st.queue.tail;
  ok('…and the result appears once the queue has run', fs.existsSync(path.join(dir, '2026-10-17-20.Auto.md')) && st.queue.pending === 0);
  const st2 = mk({ noHandlers: true });
  const t1 = await C.writeItem(dir, item(21));
  C.afterWrite(st2, dir, item(21), t1);
  await st2.queue.tail;
  ok('--no-handlers: nothing runs', st2.queue.pending === 0 && !fs.existsSync(path.join(dir, '2026-10-17-21.Auto.md')));
  const st3 = mk();
  C.afterWrite(st3, dir, item(22), null);
  is('an item that was not written (a download that failed) is not handed on', st3.queue.pending, 0);
}

// ── Items sent together: the rule gets them once, when they are all here ─────
{
  const { mock } = await import('node:test');
  const dir = scratch();
  const logs = { out: [], err: [] };
  const ctx = () => ({ deps: makeIo().io.deps, detect: async () => AGENTS, log: (s) => logs.out.push(s), err: (s) => logs.err.push(s), agentsFile: FILE });
  const stamp = '2026-10-17';
  const created = Date.parse('2026-10-17T08:00:00Z');
  const grp = (i, n, id = 'batch1') => ({ group: { id, i, n } });
  const textOf = (seq, i, n, raw = `part ${seq}`, id) => ({ seq, kind: 'text', source: 'cli', created_at: created, raw, meta: grp(i, n, id) });
  const fileOf = (seq, i, n, name, kind = 'image', id) => ({ seq, kind, source: 'cli', created_at: created, raw: '', url: 'https://x.test/f', meta: { filename: name, mime: kind === 'image' ? 'image/png' : 'video/mp4', ...grp(i, n, id) } });
  /** What `watch` does with an item: written to the folder, then handed on */
  const arrive = async (state, item) => {
    let written;
    if (item.url) {
      const file = path.join(dir, `${stamp}-${item.seq}-${item.meta.filename}`);
      fs.writeFileSync(file, Buffer.from('pixels'));
      written = { file, fresh: true };
    } else written = await C.writeItem(dir, item);
    C.afterWrite(state, dir, item, written);
    return written;
  };
  const watching = (over = {}) => ({ exec: null, noHandlers: false, recent: null, queue: C.newQueue(), ctx: ctx(), batches: new Map(), ...over });
  const resultFile = (lo, hi, name = 'Auto') => path.join(dir, `${stamp}-${lo}–${hi}.${name}.md`);
  const say = () => logs.out.join('\n');
  const asRule = (extra = {}) => custom({ id: 'a', name: 'Auto', mode: 'files', prompt: 'MODE:stdin', kinds: ['text', 'url', 'image', 'file'], ...extra });

  // groupOf: only marks that are in order count
  is('a batch mark: id, place, and how many', C.groupOf({ meta: grp(2, 3) }), { id: 'batch1', i: 2, n: 3 });
  is('no mark, one together, too many, no id, a count that is not a number → not a batch', [C.groupOf({ meta: {} }), C.groupOf({ meta: grp(1, 1) }), C.groupOf({ meta: grp(1, 17) }), C.groupOf({ meta: { group: { i: 1, n: 3 } } }), C.groupOf({ meta: { group: { id: 'x', i: 1, n: '3' } } }), C.groupOf({})], [null, null, null, null, null, null]);

  // all there: one run, one file
  put([asRule()]);
  {
    const st = watching();
    spawned.length = 0;
    await arrive(st, textOf(301, 1, 3, 'what is on these?'));
    ok('the first of three: nothing runs, and it says it is waiting for the rest', st.queue.pending === 0 && spawned.length === 0 && say().includes(en.ag.batchWaiting(1, 3)), say());
    await arrive(st, fileOf(302, 2, 3, 'a.png'));
    is('the second: still nothing, and the notice is not repeated', [spawned.length, say().split(en.ag.batchWaiting(1, 3)).length - 1], [0, 1]);
    await arrive(st, fileOf(303, 3, 3, 'b.png'));
    await st.queue.tail;
    is('the third: one run of the agent, not three', spawned.length, 1);
    const file = resultFile(301, 303);
    ok('…one file named for the first and last, beside the others', fs.existsSync(file) && !fs.existsSync(path.join(dir, `${stamp}-302-a.Auto.md`)) && !fs.existsSync(path.join(dir, `${stamp}-301.Auto.md`)), fs.readdirSync(dir).join());
    const doc = fs.readFileSync(file, 'utf8');
    is('…what arrived as it came (the text as it is, the files as links), then ---', doc.split('\n\n---\n\n')[0], `what is on these?\n\n[${stamp}-302-a.png](<${stamp}-302-a.png>)\n\n[${stamp}-303-b.png](<${stamp}-303-b.png>)`);
    const given = doc.split('\n\n---\n\n')[1];
    ok('…and the answer is to all of it at once: three items, both files, the text, nothing missing', given.includes('items="3"') && given.includes(`file ./${stamp}-302-a.png`) && given.includes(`file ./${stamp}-303-b.png`) && given.includes('what is on these?') && !given.includes('could not be fetched'), given);
    ok('…it is the batch\'s kind, and the rule\'s prompt has no content in it', given.includes('kind="batch"') && given.startsWith('MODE:stdin\n'));
    is('…the text member\'s own file is as it arrived (a batch is not turned into an answer in place)', fs.readFileSync(path.join(dir, `${stamp}-301.md`), 'utf8').endsWith('what is on these?\n'), true);
    ok('…it says where the answer is', say().includes(en.ag.watchResult(path.basename(file))), say());
    ok('…the timer is gone', st.batches.get('batch1').timer === null && st.batches.get('batch1').state === 'done');
  }

  // sent order, not arrival order
  logs.out.length = 0;
  {
    const st = watching();
    await arrive(st, textOf(311, 3, 3, 'third', 'order1'));
    await arrive(st, textOf(312, 1, 3, 'first', 'order1'));
    await arrive(st, textOf(313, 2, 3, 'second', 'order1'));
    await st.queue.tail;
    is('items are put in the order they were sent, by their place in the batch', fs.readFileSync(resultFile(311, 313), 'utf8').split('\n\n---\n\n')[0], 'first\n\nsecond\n\nthird');
  }

  // the same item twice is one
  {
    const st = watching();
    spawned.length = 0;
    const it = textOf(321, 1, 2, 'x', 'dup1');
    const w = await arrive(st, it);
    C.afterWrite(st, dir, it, { ...w, fresh: false });
    is('the same item delivered twice counts once: the batch is not complete', [spawned.length, st.batches.get('dup1').members.size], [0, 1]);
    clearTimeout(st.batches.get('dup1').timer);
  }

  // waiting: quiet window, and a ceiling
  {
    const T0 = Date.now();                           // near the real clock: the record of a batch is dropped a day after its last item
    mock.timers.enable({ apis: ['setTimeout', 'Date'], now: T0 });
    const st = watching();
    spawned.length = 0;
    try {
      await arrive(st, textOf(331, 1, 4, 'one', 'slow1'));
      mock.timers.tick(60_000);
      await arrive(st, textOf(332, 2, 4, 'two', 'slow1'));
      mock.timers.tick(60_000);
      ok('two minutes after the first but one after the last: still waiting (the clock starts again with each item)', st.batches.get('slow1').state === 'wait');
      mock.timers.tick(C.BATCH_QUIET_MS - 60_000 + 1_000);
    } finally { mock.timers.reset(); }
    await st.queue.tail;
    ok('90 s after the last: it goes with what is here, and says so', spawned.length === 1 && say().includes(en.ag.batchTimedOut(2, 4)), say());
    const doc = fs.readFileSync(resultFile(331, 332), 'utf8');
    ok('…one file, both items', doc.startsWith('one\n\ntwo\n\n---\n\n') && doc.includes('items="2"'), doc);
    const late = textOf(333, 3, 4, 'three', 'slow1');
    await arrive(st, late);
    await st.queue.tail;
    ok('one that comes after: not run on its own, and told so; the result stays as it was', spawned.length === 1 && say().includes(en.ag.batchLate(333)) && fs.readFileSync(resultFile(331, 332), 'utf8') === doc);
  }
  {
    // items arriving every 80 s never hit the quiet window: the ceiling (5 minutes after the first) ends the wait
    const T0 = Date.now();
    mock.timers.enable({ apis: ['setTimeout', 'Date'], now: T0 });
    const st = watching();
    spawned.length = 0;
    try {
      for (let k = 0; k < 4; k++) {
        await arrive(st, textOf(341 + k, k + 1, 8, `item ${k}`, 'cap1'));
        if (k < 3) mock.timers.tick(80_000);
      }
      ok('fourth item at 240 s: not over yet', st.batches.get('cap1').state === 'wait');
      mock.timers.tick(C.BATCH_MAX_MS - 240_000 - 1);
      ok('…one millisecond before the ceiling: still waiting', st.batches.get('cap1').state === 'wait');
      mock.timers.tick(1);
      ok('…at 5 minutes after the first: it goes', st.batches.get('cap1').state !== 'wait');
    } finally { mock.timers.reset(); }
    await st.queue.tail;
    is('…with the four that came', spawned.length, 1);
  }

  // a download that failed still counts as arrived
  {
    const st = watching();
    spawned.length = 0;
    const text = textOf(351, 1, 2, 'look', 'miss1');
    const written = await C.writeItem(dir, text);
    C.handOn(st, dir, [{ item: text, written }, { item: fileOf(352, 2, 2, 'gone.png', 'image', 'miss1'), written: null }]);
    await st.queue.tail;
    ok('the batch is complete without waiting for it, and the agent is told one file did not come', spawned.length === 1 && fs.readFileSync(resultFile(351, 352), 'utf8').includes('1 file could not be fetched'), fs.readdirSync(dir).join());
  }

  // only what the rule is ticked for is handed over; what arrived is still all in the file
  {
    put([asRule({ kinds: ['text', 'image'], prompt: 'MODE:env' })]);
    const st = watching();
    await arrive(st, textOf(361, 1, 3, 'hi', 'tick1'));
    await arrive(st, fileOf(362, 2, 3, 'a.png', 'image', 'tick1'));
    await arrive(st, fileOf(363, 3, 3, 'v.mp4', 'video', 'tick1'));
    await st.queue.tail;
    const doc = fs.readFileSync(resultFile(361, 363), 'utf8');
    const seen = JSON.parse(doc.split('\n\n---\n\n')[1]);
    is('a kind the rule is not ticked for is not given to the agent: only the picture is in its folder', seen.cwd, [`${stamp}-362-a.png`]);
    ok('…but the file says everything that arrived', doc.includes('363-v.mp4') && doc.includes('362-a.png'), doc);
    put([asRule()]);
  }

  // a batch with one item that ever came is that item, on its own, as before
  {
    const st = watching();
    spawned.length = 0;
    const T0 = Date.now();
    mock.timers.enable({ apis: ['setTimeout', 'Date'], now: T0 });
    try {
      await arrive(st, textOf(371, 2, 3, 'only one came', 'solo1'));
      mock.timers.tick(C.BATCH_QUIET_MS + 1);
    } finally { mock.timers.reset(); }
    await st.queue.tail;
    ok('only one of three ever came: it is handled as a single item (its file becomes the answer), not as a batch of one', spawned.length === 1 && !fs.existsSync(resultFile(371, 371)) && fs.readFileSync(path.join(dir, `${stamp}-371.md`), 'utf8').includes('> only one came'), fs.readdirSync(dir).join());
  }

  // leaving on its own (caught up, no real-time to wait for): a batch that still waits is handled, not dropped by the exit
  {
    put([asRule()]);
    const st = watching();
    spawned.length = 0;
    await arrive(st, textOf(421, 1, 3, 'words that wait', 'exit1'));
    await arrive(st, fileOf(422, 2, 3, 'a.png', 'image', 'exit1'));
    ok('two of three came: it waits, and that is known', st.batches.get('exit1').state === 'wait' && spawned.length === 0 && C.waitingBatches(st) === true);
    await C.finishBatches(st);
    ok('finishing hands it over with what it has: one run, one file, nothing left in the queue or waiting', spawned.length === 1 && fs.existsSync(resultFile(421, 422)) && st.queue.pending === 0
      && st.batches.get('exit1').state === 'done' && C.waitingBatches(st) === false, fs.readdirSync(dir).join());
    ok('…and says it went early, with how many were there', say().includes(en.ag.batchTimedOut(2, 3)), say());
    const answered = fs.readFileSync(resultFile(421, 422), 'utf8').split('\n\n---\n\n')[1];
    ok('…the agent was given what had come', answered.includes('words that wait') && answered.includes('items="2"'), answered);
    const none = watching();
    await C.finishBatches(none);
    ok('with nothing waiting it is done at once', C.waitingBatches(none) === false && none.queue.pending === 0);
    // the exit that watch takes when the service turns real-time down: it waits for the batch too
    const st2 = watching();
    spawned.length = 0;
    await arrive(st2, textOf(431, 1, 3, 'waiting at the exit', 'exit2'));
    await arrive(st2, fileOf(432, 2, 3, 'b.png', 'image', 'exit2'));
    const realExit = process.exit, realLog = console.log;
    let exited = null;
    process.exit = (code) => { exited = code; };
    console.log = () => {};
    let ret;
    try {
      ret = C.stopRealtime(st2, async () => {}, 'trial_over');
      const early = exited;
      for (let i = 0; i < 250 && exited === null; i++) await new Promise((r) => setTimeout(r, 20));
      ok('real-time turned down with a batch waiting: not an exit at once; it is handled first, and then the exit', ret === 'plan' && early === null && exited === 0 && spawned.length === 1 && fs.existsSync(resultFile(431, 432)), JSON.stringify([ret, early, exited, spawned.length]));
    } finally { process.exit = realExit; console.log = realLog; }
  }

  // the limits on words are for the words: they never get a batch's files skipped; a batch of words alone is told about
  {
    put([asRule({ maxChars: 20 })]);
    const st = watching();
    spawned.length = 0;
    await arrive(st, textOf(441, 1, 2, 'LONGWORDS '.repeat(5), 'lim1'));
    await arrive(st, fileOf(442, 2, 2, 'a.png', 'image', 'lim1'));
    await st.queue.tail;
    const doc = fs.readFileSync(resultFile(441, 442), 'utf8');
    const given = doc.split('\n\n---\n\n').slice(1).join('\n\n---\n\n');
    ok('words over the longest, with a picture: the batch runs, on the picture', spawned.length === 1 && given.includes(`file ./${stamp}-442-a.png`), given);
    ok('…the agent is not given the long words and is told they were left out; the file keeps all that arrived', !given.includes('LONGWORDS') && given.includes('1 text was too long and left out') && doc.split('\n\n---\n\n')[0].includes('LONGWORDS'), doc);
    put([asRule({ maxChars: 5 })]);
    const st2 = watching();
    spawned.length = 0;
    logs.err.length = 0;
    await arrive(st2, textOf(451, 1, 2, 'only words, too long', 'lim2'));
    await arrive(st2, textOf(452, 2, 2, 'more words', 'lim2'));
    await st2.queue.tail;
    ok('a batch of words alone past the longest is not run, no file is made', spawned.length === 0 && !fs.existsSync(resultFile(451, 452)), fs.readdirSync(dir).join());
    ok('…and it is not silent: one line says which rule turned it away and why', logs.err.filter((l) => l.includes(en.ag.whyLong) && l.includes('Auto')).length === 1, JSON.stringify(logs.err));
    put([asRule({ minChars: 500 })]);
    const st3 = watching();
    spawned.length = 0;
    await arrive(st3, textOf(461, 1, 2, 'short', 'lim3'));
    await arrive(st3, fileOf(462, 2, 2, 'a.png', 'image', 'lim3'));
    await st3.queue.tail;
    ok('words under the shortest, with a picture: the batch runs (the shortest is for a text on its own)', spawned.length === 1 && fs.existsSync(resultFile(461, 462)), fs.readdirSync(dir).join());
    put([asRule()]);
  }

  // again after a restart: the file is there, nothing runs
  {
    put([asRule()]);
    spawned.length = 0;
    const before = fs.readFileSync(resultFile(301, 303), 'utf8');
    const st = watching();                           // a new watch: it remembers nothing
    const items = [textOf(301, 1, 3, 'what is on these?'), fileOf(302, 2, 3, 'a.png'), fileOf(303, 3, 3, 'b.png')];
    const entries = items.map((item) => ({ item, written: { file: item.url ? path.join(dir, `${stamp}-${item.seq}-${item.meta.filename}`) : path.join(dir, `${stamp}-${item.seq}.md`), fresh: false } }));
    C.handOn(st, dir, entries);
    await st.queue.tail;
    ok('pulled again by a new watch: the result is already there, so the agent is not started and the file is not touched', spawned.length === 0 && fs.readFileSync(resultFile(301, 303), 'utf8') === before, `${spawned.length}`);
  }

  // not for a batch to wait when nothing would run
  {
    const quiet = [['--no-handlers', () => watching({ noHandlers: true })], ['the rule is paused', () => { put([asRule()], { paused: true }); return watching(); }], ['no rule', () => { put([]); return watching(); }]];
    for (const [why, make] of quiet) {
      logs.out.length = 0;
      const st = make();
      await arrive(st, textOf(381, 1, 3, 'x', 'none1'));
      ok(`${why}: a batch is not waited for, and nothing is said about one`, st.batches.size === 0 && !say().includes('batch'), say());
    }
    put([asRule()]);
  }

  // switched off while it waited
  {
    const st = watching();
    spawned.length = 0;
    const [a, b] = [textOf(391, 1, 2, 'x', 'off1'), textOf(392, 2, 2, 'y', 'off1')];
    const [wa, wb] = [await C.writeItem(dir, a), await C.writeItem(dir, b)];
    C.batchJoin(st, a, wa, C.groupOf(a));
    put([asRule()], { paused: true });
    C.batchJoin(st, b, wb, C.groupOf(b));            // all here: queued, and the rule is paused by the time it would run
    await st.queue.tail;
    ok('paused before the run began: nothing runs, no file, and the batch is closed', spawned.length === 0 && !fs.existsSync(resultFile(391, 392)) && st.batches.get('off1').state === 'done');
    put([asRule()]);
  }

  // a backlog: a batch counts as one
  {
    const kc = (o) => custom({ keepSource: true, ...o });
    put([asRule({ keepSource: true })]);
    const entries = async (list) => { const out = []; for (const item of list) out.push({ item, written: item.url ? { file: path.join(dir, `x-${item.seq}`), fresh: true } : await C.writeItem(dir, item) }); return out; };
    const single = (seq) => ({ seq, kind: 'text', source: 'cli', created_at: created, raw: `single ${seq}`, meta: {} });
    const said = [];
    const realLog = console.log;
    console.log = (...a) => { const l = a.join(' '); if (/older item/.test(l)) said.push(l); };
    let st = watching({ recent: 2 });
    spawned.length = 0;
    C.handOn(st, dir, await entries([textOf(401, 1, 3, 'b one', 'bk1'), textOf(402, 2, 3, 'b two', 'bk1'), textOf(403, 3, 3, 'b three', 'bk1'), single(404), single(405), single(406)]));
    await st.queue.tail;
    is('four things arrived, the newest two are handed over: the batch is left out whole, not in part', [st.batches.size, fs.existsSync(resultFile(401, 403)), fs.existsSync(path.join(dir, `${stamp}-405.Auto.md`)), fs.existsSync(path.join(dir, `${stamp}-406.Auto.md`))], [0, false, true, true]);
    ok('…and it says two were left out', said.length === 1 && /2 older items/.test(said[0]), JSON.stringify(said));
    st = watching({ recent: 2 });
    spawned.length = 0;
    C.handOn(st, dir, await entries([single(410), textOf(411, 1, 3, 'c one', 'bk2'), textOf(412, 2, 3, 'c two', 'bk2'), textOf(413, 3, 3, 'c three', 'bk2'), single(414)]));
    await st.queue.tail;
    console.log = realLog;
    is('the batch among the newest two is run once, with all three', [fs.existsSync(resultFile(411, 413)), fs.readFileSync(resultFile(411, 413), 'utf8').split('\n\n---\n\n')[0], fs.existsSync(path.join(dir, `${stamp}-410.Auto.md`)), fs.existsSync(path.join(dir, `${stamp}-414.Auto.md`))], [true, 'c one\n\nc two\n\nc three', false, true]);
    put([asRule()]);
  }
}

// ── watch --exec ──────────────────────────────────────────────────────────
{
  const pulled = {
    seq: 41, kind: 'url', source: 'chrome-extension', created_at: Date.parse('2026-10-17T08:30:00Z'), raw: 'https://example.com/a; rm -rf ~ $(whoami)',
    meta: { title: 'A page', description: 'About it', filename: undefined, group: { id: 'b1x', i: 2, n: 3 }, from: { url: 'https://example.com/', title: 'Home' }, secret: 'not part of the payload' },
  };
  const payload = C.execPayload(pulled, []);
  is('payload: version 1, in the documented order', Object.keys(payload), ['v', 'seq', 'kind', 'source', 'created', 'batch', 'text', 'meta', 'note', 'files']);
  is('payload: values', [payload.v, payload.seq, payload.kind, payload.source, payload.created, payload.batch, payload.note, payload.files],
    [1, 41, 'url', 'chrome-extension', '2026-10-17T08:30:00.000Z', { id: 'b1x', index: 2, count: 3 }, null, []]);
  is('payload: only the documented meta fields', payload.meta, { title: 'A page', description: 'About it', from: { url: 'https://example.com/', title: 'Home' } });
  ok('payload: the text is the content as it arrived', payload.text === pulled.raw);
  is('payload: no batch is null', C.execPayload({ ...pulled, meta: {} }, []).batch, null);
  const file = C.execPayload({ seq: 3, kind: 'image', source: 'cli', created_at: 0, raw: '', url: 'https://x.test', meta: { filename: 'a.png', mime: 'image/png' } }, ['/abs/dir/a.png']);
  is('payload: a file item has an empty text and the absolute path', [file.text, file.files, file.meta], ['', ['/abs/dir/a.png'], { filename: 'a.png', mime: 'image/png' }]);
  ok('payload: one line of JSON', !JSON.stringify(payload).includes('\n'));

  // with a stand-in spawn: what would be started
  const calls = [];
  const fakeSpawn = (cmd, args, opts) => {
    const chunks = [];
    const child = { on() {}, stdin: { on() {}, end: (d) => chunks.push(d) } };
    calls.push({ cmd, args, opts, chunks });
    return child;
  };
  C.fireExec(['my-script', '--flag', 'two words'], payload, { spawn: fakeSpawn });
  const call = calls[0];
  ok('exec: the words after --exec are the program and its arguments, as a list, with no shell', call.cmd === 'my-script' && JSON.stringify(call.args) === '["--flag","two words"]' && call.opts.shell === false);
  ok('exec: the payload goes in on standard input as one line', call.chunks.length === 1 && call.chunks[0].endsWith('\n') && JSON.parse(call.chunks[0]).seq === 41);
  ok('exec: the content never reaches the command line', ![call.cmd, ...call.args].some((a) => a.includes('rm -rf') || a.includes('example.com')));
  const errs = [];
  C.fireExec(['x'], payload, { spawn: () => { throw new Error('boom'); }, onError: (m) => errs.push(m) });
  ok('exec: a command that cannot start is one message and no exception', errs.length === 1 && /boom/.test(errs[0]), errs.join());

  // for real: a child that writes what it received
  const out = path.join(scratch(), 'got.json');
  const sink = `require('fs').writeFileSync(${JSON.stringify(out)}, JSON.stringify({ args: process.argv.slice(1), stdin: require('fs').readFileSync(0, 'utf8') }))`;
  C.fireExec([process.execPath, '-e', sink, 'fixed-arg'], payload);
  for (let i = 0; i < 100 && !fs.existsSync(out); i++) await new Promise((r) => setTimeout(r, 50));
  const got = JSON.parse(fs.readFileSync(out, 'utf8'));
  ok('exec, for real: the child received the payload on stdin and only its own arguments', JSON.parse(got.stdin).text === pulled.raw && got.args.join() === 'fixed-arg', JSON.stringify(got));

  // through afterWrite: only a new item fires it, once, and a file item hands over its absolute path
  const started = [];
  const cp = require('node:child_process');
  const realSpawn = cp.spawn;
  cp.spawn = (cmd, args, opts) => { started.push({ cmd, args }); return fakeSpawn(cmd, args, opts); };
  try {
    const dir = scratch();
    const st = { exec: ['cmd', '--x'], noHandlers: true, queue: C.newQueue(), ctx: {} };
    C.afterWrite(st, dir, pulled, { file: path.join(dir, 'x.md'), fresh: false });
    is('exec: an item that was already in the folder does not fire it again', started.length, 0);
    C.afterWrite(st, dir, pulled, null);
    is('exec: an item that was not written does not fire it', started.length, 0);
    C.afterWrite(st, dir, pulled, { file: path.join(dir, 'x.md'), fresh: true });
    is('exec: a new item fires it once', started.map((s) => [s.cmd, s.args]), [['cmd', ['--x']]]);
    const sent = JSON.parse(calls.at(-1).chunks[0]);
    is('exec: a text item has no files', sent.files, []);
    C.afterWrite(st, dir, { seq: 9, kind: 'file', source: 'cli', created_at: 0, raw: '', url: 'https://x.test/f', meta: { filename: 'a.pdf' } }, { file: path.join(dir, '2026-10-17-9-a.pdf'), fresh: true });
    is('exec: a file item hands over where the file is, as an absolute path', JSON.parse(calls.at(-1).chunks[0]).files, [path.join(dir, '2026-10-17-9-a.pdf')]);
    const none = { ...st, exec: null };
    C.afterWrite(none, dir, pulled, { file: path.join(dir, 'y.md'), fresh: true });
    is('exec: off unless asked for', started.length, 2);
  } finally { cp.spawn = realSpawn; }
}

// ── The commands as the shell sees them ───────────────────────────────────
{
  const env = { ...process.env, HOME, USERPROFILE: HOME, DROPIT_LANG: 'en' };
  const cli = (args, input = '') => spawnSync(process.execPath, [CLI_PATH, ...args], { env, input, encoding: 'utf8' });
  reset();
  const none = cli(['handlers']);
  ok('dropit handlers: lists nothing and says how to add', none.status === 0 && none.stdout.includes('dropit handlers add'), none.stdout + none.stderr);
  const asked = cli(['handlers', 'add', '--agent', 'custom', '--', process.execPath, FAKE], 'y\n');
  ok('dropit handlers add: the first time shows the confirmation and reads the answer from standard input', asked.status === 0
    && asked.stdout.includes('handed to the agent you chose') && C.readAgents().data.consented === true, asked.stdout + asked.stderr);
  const declined = (reset(), cli(['handlers', 'add', '--agent', 'custom', '--', process.execPath, FAKE], 'n\n'));
  ok('…and "n" exits with an error and creates nothing', declined.status === 1 && !fs.existsSync(FILE));
  reset();
  const gone = cli(['handlers', 'add', '--agent', 'custom', '--', process.execPath, FAKE], '');
  ok('…and no answer at all (standard input closed) counts as no', gone.status === 1 && !fs.existsSync(FILE));
  const help = cli(['help']);
  ok('dropit help: exits 0 and says content is untrusted input, and that --exec gets it on standard input only', help.status === 0 && /untrusted input/.test(help.stdout) && /--exec <command…>/.test(help.stdout) && /standard input only/.test(help.stdout));
  fs.mkdirSync(path.dirname(C.CONFIG), { recursive: true });
  fs.writeFileSync(C.CONFIG, JSON.stringify({ token: 'dk_test', endpoints: ['https://dropit.test'] }));
  const w = cli(['watch', '--exec']);
  ok('watch --exec with no command: says what to give, and does not start', w.status === 1 && w.stderr.includes(en.ag.execNeedsCommand), w.stderr);
  fs.rmSync(C.CONFIG);
  const zhHelp = spawnSync(process.execPath, [CLI_PATH, 'help'], { env: { ...env, DROPIT_LANG: 'zh' }, encoding: 'utf8' });
  ok('dropit help in Chinese names the new commands too', zhHelp.stdout.includes('dropit agents') && zhHelp.stdout.includes('--exec') && zhHelp.stdout.includes('不可信'));
}

// ── Words ─────────────────────────────────────────────────────────────────
{
  const all = JSON.stringify(T.en) + JSON.stringify(T.zh);
  ok('no wording about guarding against abuse or proving a person is real', !/防刷|真人|anti-?abuse|prove you are (a )?human/i.test(all));
  const src = fs.readFileSync(CLI_PATH, 'utf8');
  ok('the CLI file has the runner block between its markers, and does not require the shared file', /\/\/ agent-runner:begin v1[\s\S]*\/\/ agent-runner:end/.test(src) && !/require\(['"][^'"]*agent-runner/.test(src));
}

console.log(`\n${fail === 0 ? '✅' : '🛑'}  ${pass} passed, ${fail} failed\n`);
process.exit(fail ? 1 : 0);
