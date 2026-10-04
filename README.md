# dropit

**English** · [简体中文](./README.zh-CN.md)

**Send it on one device. It's waiting on all the others.**

A link from your phone, a paragraph from your browser, a file from your terminal: one move drops it in,
and a few seconds later it's in Obsidian, in a folder on your computer, or in any browser.
No chat to scroll, no "send to" to pick — just your own devices.

Items are kept for 1 day after they're sent (10 days on a paid plan): dropit delivers, it doesn't store.

This repository holds the open-source clients. The Obsidian plugin has its own:
[smart-kits/dropit-obsidian](https://github.com/smart-kits/dropit-obsidian).

## On every device you own

| On | Use | At its best | Guide |
|---|---|---|---|
| **iPhone · iPad** | The **dropit** shortcut | *Share* → **dropit** from any app — photos, videos, files, links, text. Bound to Back Tap, a double-tap on the back of the phone sends the clipboard | [shortcuts/](./shortcuts/) |
| **Chrome · Edge** | The extension | Click the icon and <kbd>Enter</kbd> sends the page with its title; <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>S</kbd> sends the selection; right-click an image to send the file; paste a screenshot | [extension/](./extension/) |
| **Obsidian** (desktop, mobile) | The plugin | Everything you send arrives as a note — titled, images embedded, sources linked; right-click to send notes and files out | [dropit-obsidian](https://github.com/smart-kits/dropit-obsidian) |
| **Terminal** (macOS, Linux) | `dropit` | `pbpaste \| dropit send`, `dropit send -f report.pdf`; `dropit watch` fills a folder; Raycast, Alfred and the Services menu on top | [cli/](./cli/) |
| **Any browser** | The web inbox at [dropit.smart-kits.xyz](https://dropit.smart-kits.xyz) | Nothing to install: type, paste or drag files in; read and download what arrived; add and remove devices | [below](#the-web-inbox) |

Open [dropit.smart-kits.xyz](https://dropit.smart-kits.xyz) on any device: it recommends the client that fits
that device, and every client's install steps are under *Devices*.

## A day with it

- **On the train**, an article on your phone: *Share* → **dropit**. At your desk it's already a note in Obsidian,
  title and summary included.
- **At your desk**, a paragraph in the browser: select, <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>S</kbd>. On your phone,
  the paragraph with a link back to the page.
- **A screenshot you'll need on your phone**: <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>D</kbd>, <kbd>⌘</kbd><kbd>V</kbd>, <kbd>Enter</kbd>.
- **A borrowed computer**: open the web inbox, join with a code, drag the files in, sign out — the device is gone from your account.
- **A long build**: `make && dropit send "build done ✅"`, and your phone tells you.

## The web inbox

[dropit.smart-kits.xyz](https://dropit.smart-kits.xyz), in any browser, phone or computer. Nothing to install.

**Sending**

- Type or paste into the envelope, press <kbd>Enter</kbd> (or the red stamp). <kbd>Shift</kbd>+<kbd>Enter</kbd> for a new line.
- **Drop files anywhere on the page**, paste a screenshot, or *Attach files* — up to 10 at a time, sent with the text as one delivery.
- A failed send is stamped **RETURNED** with the reason and stays on the envelope; nothing you wrote is lost.

**Receiving**

- Every delivery is a letter with a postmark: the date, the time and where it came from. Links show their title and
  summary; selections and images show *From page ↗*. *Copy* and *Download* are on each letter, with how long it's kept.
- The page doesn't listen live: press *Pull new items* to fetch what's new. *Pull again…* fetches everything,
  the last N items or the last N days.

**Devices**

- *Show pairing code* gives a 6-character code and a QR code (also copied for you). Scan it with a phone camera,
  or type it on the other device; the page notices when it has joined.
- Every device on your account, when it was last seen, and *Revoke* to remove one at once.
- Your plan and space used, and the free and paid plans side by side.
- *Create bookmarklet* gives a bookmark that sends the current page, or the selected text, from any browser
  without installing anything. Each bookmarklet is a device of its own, with a send-only key.

**Good to know:** joining in a browser makes it one of your devices — on a borrowed computer, *Sign out on this browser*
removes it from your account right away. The interface is English or Chinese (switch at the top right) and follows
your light or dark setting.

## Start in three steps

1. **Your first device creates the account.** In the web inbox: *Just for now? Use it in this browser* → *Create a new account*.
   Or in the terminal: `dropit pair`; in Obsidian: *Settings → dropit → Create a new account*.
2. **Show a pairing code** on that device: web inbox → *Devices* → *Show pairing code*; terminal: `dropit code`;
   Obsidian: *Settings → dropit → Add a device*.
3. **Join from every other device** with the code — or scan its QR code with the phone's camera.
   On an iPhone, scanning it opens the shortcut's setup with the code filled in — tap *Set up the shortcut*.

Codes last 5 minutes, work once, and are forgiving: case, spaces, dashes and `0`/`O` or `1`/`I`/`l` mix-ups don't matter.

Using an AI coding agent (Claude Code, Codex, Cursor…)? Paste this:

```text
Install dropit on this machine for me by following
https://raw.githubusercontent.com/smart-kits/dropit-client/main/AGENTS.md
Ask me before creating an account, and never show or commit my token.
```

It installs and joins the terminal client, checks that it works, and tells you exactly what to click for the parts
that need your hands (loading the browser extension, adding the iPhone shortcut). Agent instructions: [AGENTS.md](./AGENTS.md).

## What you can count on

- **Nothing to choose.** Sending never asks a question; everything goes to all your devices.
- **You know whether it went.** Every client shows a clear sent or a clear reason why not — offline is never shown as sent.
- **Nothing lost, nothing twice.** A receiver moves on only after an item is safely written: a crash means a repeat at worst,
  never a gap. The same thing sent twice in a row within a minute (a double tap, a retry) is kept once.
- **Every device on its own.** Each is joined separately and can be removed separately. Send-only devices — the iPhone shortcut,
  the browser extension, bookmarklets — can't read anything you've sent.
- **Small and open.** The terminal client is one Node.js file; the extension loads as it is; no build step, no dependencies.

## Security & privacy

- Each device's key stays on that device — terminal: `~/.config/dropit/config.json` (readable only by you);
  extension: the browser's local extension storage; Obsidian: the plugin's `data.json`; web inbox: the browser's local storage.
- Keys come in two kinds: **full** (send, receive, add devices) and **send-only**. The extension and the iPhone shortcut
  join send-only, so a leaked key can't read your items.
- Lost a device? Remove it from any other one: web inbox → *Devices* → *Revoke*, or `dropit devices` and `dropit revoke <id>`.

## Problems and ideas

[Open an issue](https://github.com/smart-kits/dropit-client/issues). Every client links there: the extension at the bottom of
its popup, the web inbox at the bottom of the page, the terminal in `dropit`'s help text, Obsidian under *Settings → dropit → Feedback*.
Issues are public — never paste a key (`dk_…`).

## Contributing

- Turn on the secret scan before your first commit, so a key can never land in the repository:

  ```bash
  brew install gitleaks          # or see https://github.com/gitleaks/gitleaks
  git config core.hooksPath .githooks
  ```

- Run the tests before you push (no dependencies): `node test/i18n.test.mjs`, `node test/extension.test.mjs`,
  `node test/cli-render.test.mjs`, `node test/cli-logic.test.mjs`.
- Every interface string lives in an English and a Chinese table; add both, or the test fails.
- Commit messages are in English. Docs come in pairs: `README.md` (English) and `README.zh-CN.md` (简体中文) — update both.

Interfaces follow your language: English, or Simplified Chinese when the system, browser or Obsidian is set to Chinese.
The terminal reads `LANG`; `DROPIT_LANG=zh` or `DROPIT_LANG=en` overrides.

## License

[MIT](./LICENSE)
