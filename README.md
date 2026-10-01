# dropit clients

**English** · [简体中文](./README.zh-CN.md)

**Send things to yourself — from any device, to every device.**

See a link, jot down a thought, grab a file: one tap drops it into your queue,
and it's already waiting on your other devices. Items stay for 1 day after they're sent (10 days on paid plans) — dropit delivers, it doesn't store —
and can be pulled again until then.

This repository holds the open-source dropit clients. The Obsidian plugin lives in
its own repository: [smart-kits/dropit-obsidian](https://github.com/smart-kits/dropit-obsidian).

## Let your AI install it

Using an AI coding agent (Claude Code, Codex, Cursor, …)? Paste this:

```text
Install dropit on this machine for me by following
https://raw.githubusercontent.com/smart-kits/dropit-client/main/AGENTS.md
Ask me before creating an account, and never show or commit my token.
```

The agent installs and pairs the CLI, checks that it works, and walks you through the
parts that need your hands (loading the browser extension, building the iOS shortcuts).
Instructions for agents are in [AGENTS.md](./AGENTS.md).

## Clients

| Client | Platform | Sends | Receives | Guide |
|---|---|:-:|:-:|---|
| **iOS Shortcut** | iPhone · iPad | ✅ text, links, photos, videos, files | — | [shortcuts/](./shortcuts/) |
| **Browser extension** | Chrome · Edge | ✅ pages, links, selected text | — | [extension/](./extension/) |
| **CLI** | macOS · Linux (Node.js ≥ 18) | ✅ text, links, files | ✅ into a local folder | [cli/](./cli/) |
| **Obsidian plugin** | Desktop · mobile | ✅ selected text, notes, files | ✅ as notes in your vault | [dropit-obsidian](https://github.com/smart-kits/dropit-obsidian) |
| **Web inbox** | Any browser, at [dropit.smart-kits.xyz](https://dropit.smart-kits.xyz) | ✅ text, links, up to 5 files at once | ✅ in the page | — |

The CLI also powers Raycast, Alfred and the macOS Services menu — see [cli/](./cli/).

## What can I send, and how?

| On | Text | A link | Photos and videos | Files (PDF, Excel, …) |
|---|---|---|---|---|
| **iPhone / iPad** | In any app: select → *Share* → **dropit** | *Share* → **dropit** (Safari, any app) | *Photos* → pick one or several → *Share* → **dropit** | *Files* → press and hold → *Share* → **dropit** |
| **Chrome / Edge** | Select → right-click → *Send selected text* | Right-click the link; or the toolbar icon → *Send this page* | Right-click → sends the image's **address** | — |
| **Terminal** | `dropit send "hello"` · `pbpaste \| dropit send` | `dropit send https://…` | `dropit send -f photo.jpg` | `dropit send -f report.pdf` |
| **Obsidian** | Select → right-click → *Send to dropit* | — | Right-click it in the file list | Right-click a note or files (several at once) |
| **Any browser** | Type in the web inbox → press the stamp | Same | Pick, drag or paste (up to 5) | Same |

- **Nothing to choose.** Send, and it's on your other devices a few seconds later: in Obsidian as notes, in the web inbox, or in the CLI's folder.
- **Size:** a single file up to 5 MB on the free plan, 100 MB on the paid plan. Long videos pass that easily.
- **On the iPhone, a notification says what went** ("3 photo/video sent") or why not. In a web page, a PDF goes as its link; save it to *Files* first to send the PDF itself.
- **Sent twice by accident?** The same thing twice in a row within a minute is kept once.

## Features

- **One step to send.** No forms, no choices on the send path — share, right-click or type one command.
- **Real-time delivery.** Receivers get new items over a live connection and catch up automatically after being offline.
- **Nothing gets lost.** Receivers advance their position only after an item is safely written; a crash means a duplicate at worst, never a gap.
- **Duplicate-safe.** The same thing sent twice in a row within a minute (a double tap, a retry) is stored once. Sent again later, it arrives again.
- **Every device is separate.** Each device is paired on its own and can be revoked on its own. Send-only devices (browser, phone) cannot read your history.
- **Minimal permissions.** The browser extension requests no host permissions at all.
- **No build step, no dependencies.** The CLI is a single Node.js file; the extension loads as-is.

## Getting started

1. **Set up your first device.** This creates your account.
   - CLI: `dropit pair`
   - Obsidian: *Settings → dropit → First time using dropit? → Create a new account*
   - Browser extension: click the icon → the *Create a new account* link under the form
2. **Generate a pairing code** on that device. It is valid for 5 minutes.
   - CLI: `dropit code`
   - Obsidian: *Settings → dropit → Pairing code → Generate*
3. **Join from every other device** with that code.
   - CLI: `dropit pair <code>`
   - Obsidian: *Settings → dropit → Pairing code* → enter the code → *Join*
   - Browser extension: click the icon → enter the code → *Join*
   - iOS: run the *dropit pair* shortcut
4. **Send something** — `dropit send "hello"`, right-click a page, or share from your phone — and watch it arrive.

Pairing codes are 6 characters and forgiving: case, spaces, dashes, and `0`/`O` or `1`/`I`/`l` mix-ups don't matter.

## Security & privacy

- Your token is stored **only on your device** — CLI: `~/.config/dropit/config.json` (mode `0600`); extension: the browser's local extension storage; Obsidian: the plugin's `data.json`.
- Tokens come in two scopes: `full` (send, receive, pair new devices) and `ingest_only` (send only). The browser extension and the iPhone shortcut use `ingest_only` when joining with a code.
- Lost a device? Revoke it from any `full` device: `dropit devices`, then `dropit revoke <device_id>`.

## Problems and ideas

[Open an issue](https://github.com/smart-kits/dropit-client/issues). Each client links there too: the
browser extension at the bottom of its popup, the web inbox at the bottom of the page, the CLI at the end of
`dropit help`, and the iOS shortcut under its entry on the home page. Issues are public: never paste a token (`dk_…`).

## Contributing

- Enable the pre-commit secret scan before your first commit, so a token can never land in the repository:

  ```bash
  brew install gitleaks          # or see https://github.com/gitleaks/gitleaks
  git config core.hooksPath .githooks
  ```

- Run the tests before you push: `node test/i18n.test.mjs` (no dependencies).
- Every interface string lives in an English and a Chinese table; add both, or the test fails.
- Commit messages are in English.
- Docs come in pairs: `README.md` (English) and `README.zh-CN.md` (简体中文). Please update both.

> The interface follows your language: English by default, Simplified Chinese when your system, browser or Obsidian is set to Chinese. The CLI reads `LANG`; set `DROPIT_LANG=zh` or `DROPIT_LANG=en` to override.

## License

[MIT](./LICENSE)
