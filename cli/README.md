# dropit for the terminal

**English** · [简体中文](./README.zh-CN.md)

**Your clipboard, a file, a script's result — on your phone and every other device, in one line.
And a folder that fills itself with whatever you send from elsewhere.**

macOS and Linux. A single Node.js file, no dependencies.

## One line each

| You want… | Run | You see |
|---|---|---|
| What you just copied, on your phone | `pbpaste \| dropit send` | `✓ #41` |
| A file on your phone | `dropit send -f ~/Desktop/report.pdf` | `✓ #42 report.pdf 820 KB` |
| A quick note to yourself | `dropit send "call the bank at 3"` | `✓ #43` |
| To hear when a long job is done | `make release && dropit send "release built ✅"` | the note arrives when it finishes |
| A command's output somewhere you can read it | `git log -5 --oneline \| dropit send` | the five lines, as one item |
| Everything you send from elsewhere, as files here | `dropit watch ~/dropit` | `↓ 2026-10-04-44.md`, `↓ 2026-10-04-45-IMG_2041.jpeg` … |

Several words become one item: `dropit send see you at 6` sends *see you at 6*.
A lone `http(s)://` address is sent as a link; anything else as text.

## Sending

```bash
dropit send "some text"            # text, or a single link
some-command | dropit send         # whatever comes down the pipe, as one item
dropit send < notes.txt            # a text file's contents
dropit send -f photo.jpg           # a file: image, video, PDF, anything
```

- **Several files at once:** `dropit send -f *.png` — up to 10, sent as one batch that arrives together.
  More than 10 and nothing is sent; a missing file is caught before anything goes.
- **Big files stream** from disk; they aren't read into memory first. Your plan sets the size limit per file.
- **Sent twice by accident?** The same thing twice in a row within a minute is kept once — you see `Already sent (#41)`.
- **Scripts can rely on it:** success exits `0`; any failure prints the reason to stderr and exits `1`.

## A folder that fills itself

```bash
dropit watch ~/dropit            # the folder is remembered: next time, just `dropit watch`
```

It catches up on everything that arrived while it wasn't running, then stays connected and writes each
new item the moment it arrives:

| Sent from elsewhere | Lands here as |
|---|---|
| Text, a link | `2026-10-04-44.md` |
| A file | `2026-10-04-45-IMG_2041.jpeg`, saved as it is |

A note looks like this — a web page sent from the browser extension keeps its title and summary,
and a selection keeps where it came from:

```markdown
---
dropit_seq: 44
kind: url
source: chrome-extension
created: 2026-10-04T07:30:00.000Z
---

[How Airmail Worked](https://example.com/airmail)

> A short history of the red-and-blue envelope.
```

Point it at an Obsidian or Logseq folder and every item becomes a note there.

- **Nothing is skipped, nothing doubled.** Its place in the queue moves only after a file is safely written;
  files already in the folder are never overwritten.
- **A file that won't download** leaves a note in its place saying why, and the command that fetches it again:
  `dropit watch --from 45`.
- **Pull again:** `dropit watch --days 3` (the last three days) or `--from 40` (from item #40 on).
  A newly joined device starts with what's sent after it joined; this is how you get what came before,
  for as long as your plan keeps items.
- It receives what this machine sends too, so the folder is a complete record.
- File names use the UTC date.

### Real-time, and when it isn't

`watch` needs Node.js 22 or newer for its live connection (older versions catch up once and stop).

| It says | Means |
|---|---|
| `Real-time push connected` | Live — items appear within seconds |
| `Real-time push connected · trial: 3 days left` | New accounts get real-time for a while |
| `…The 10-day real-time trial has ended…` | It caught up and exits; run it again (or on a schedule) to catch up |
| `…Today's real-time spots are full…` | It waits and reconnects by itself a little after midnight UTC |

### Keep it running

**macOS** — a launch agent that starts `watch` when you log in. Save as
`~/Library/LaunchAgents/xyz.smart-kits.dropit.plist`, replacing the three paths with yours
(`command -v dropit`, `dirname "$(command -v node)"`, your home folder):

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>xyz.smart-kits.dropit</string>
  <key>ProgramArguments</key>
  <array><string>/Users/you/.local/bin/dropit</string><string>watch</string></array>
  <key>EnvironmentVariables</key>
  <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
  <key>RunAtLoad</key><true/>
  <!-- restart after a crash, but not after it exits on purpose (e.g. the real-time trial ended) -->
  <key>KeepAlive</key><dict><key>SuccessfulExit</key><false/></dict>
  <key>StandardOutPath</key><string>/Users/you/Library/Logs/dropit.log</string>
  <key>StandardErrorPath</key><string>/Users/you/Library/Logs/dropit.log</string>
</dict>
</plist>
```

```bash
launchctl load ~/Library/LaunchAgents/xyz.smart-kits.dropit.plist      # start now and at every login
launchctl unload ~/Library/LaunchAgents/xyz.smart-kits.dropit.plist    # stop
```

**Linux** — a user service, `~/.config/systemd/user/dropit.service`:

```ini
[Unit]
Description=dropit watch

[Service]
ExecStart=%h/.local/bin/dropit watch
Restart=on-failure

[Install]
WantedBy=default.target
```

`systemctl --user enable --now dropit`. If `node` isn't found, add `Environment=PATH=…` with the folder `node` is in.

## From anywhere on your Mac

The terminal is the engine; these put it one keystroke away.

| | What you get | Set up |
|---|---|---|
| **Raycast** | *Send to dropit* sends the clipboard's text; *Send File to dropit* sends a file by path. The result (`✓ #46`) shows right in Raycast | Raycast → *Extensions → Script Commands → Add Directory* → choose `cli/raycast/` |
| **Right-click selected text in any app** | *Services → Send to dropit* | Below |
| **Alfred** | A hotkey that sends the clipboard | Below |

**Services menu** (Automator):

1. Automator → *New Document* → **Quick Action**. *Workflow receives current* **text** in **any application**.
2. Add **Run Shell Script**, *Pass input:* **as arguments**, and paste — with the path from `command -v dropit`:

   ```bash
   export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
   "$HOME/.local/bin/dropit" send "$1"
   ```

3. Save it as *Send to dropit*. Select text anywhere → right-click → *Services* → *Send to dropit*.
   Give it a keyboard shortcut in *System Settings → Keyboard → Keyboard Shortcuts → Services*.

On its own this shows no confirmation. To see one — ✓ or the reason it failed — make the second line:

   ```bash
   "$HOME/.local/bin/dropit" send "$1" 2>&1 | xargs -0 osascript -e 'on run a' -e 'display notification (item 1 of a) with title "dropit"' -e 'end run'
   ```

**Alfred:** a Workflow with a **Hotkey** trigger connected to **Run Script** (`/bin/bash`):
`export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"; pbpaste | "$HOME/.local/bin/dropit" send`.

On Linux, read the clipboard with `wl-paste` or `xclip -o -selection clipboard` in place of `pbpaste`.

## Set up in a minute

```bash
curl -fsSL https://dropit.smart-kits.xyz/install.sh | sh    # into ~/.local/bin, no sudo; needs Node.js 18+
```

Run it again to update. Prefer git? `git clone https://github.com/smart-kits/dropit-client.git` and link
`cli/dropit` into a folder on your `PATH`.

Then join your account:

```bash
dropit pair K7M2QX     # with a code from a device you already use (web inbox: Devices; Obsidian: Settings → dropit)
dropit pair            # only on your very first device: creates the account
dropit me              # free · 2/3 devices · 1.2/30 MB · files up to 5 MB · real-time trial: 3 days left
```

> On a machine that's already joined, plain `dropit pair` stops and says so — it won't switch you to a new, empty account.
> To join another account, give its code. To really start a separate account here, `dropit pair --new`.

## Your account from here

| Command | Does |
|---|---|
| `dropit code` | A 6-character code for a new device, valid 5 minutes — type it there, or `dropit pair <code>` on another terminal |
| `dropit me` | Plan, devices used, space used, the largest file you can send, whether real-time is on |
| `dropit devices` | Every device on your account, with its ID |
| `dropit revoke <device_id>` | Removes a device at once and frees its slot — a lost laptop, an old phone |

Pairing codes are forgiving: case, spaces, dashes and `0`/`O` or `1`/`I`/`l` mix-ups don't matter.

## When something goes wrong

| It says | Means | Do this |
|---|---|---|
| `Not paired yet` | This machine hasn't joined | `dropit pair <code>` |
| `Invalid token` · `This device was removed` | This machine was removed from your account | Get a new code, `dropit pair <code>` |
| `Invalid pairing code` · `Pairing code expired` | Wrong, used or older than 5 minutes | Generate a fresh one |
| `Device limit reached` | Your plan's device count is used up | `dropit devices`, then `dropit revoke <id>` one you don't use |
| `Larger than 5 MB, the most one item can be` | The file is over your plan's size limit | Send a smaller file, or a link to it |
| `Network unavailable (tried N addresses)` | No connection, or a proxy is blocking it | Check the network or proxy |
| `Node.js < 22 has no global WebSocket` | Real-time needs Node.js 22+ | Upgrade Node.js, or run `watch` on a schedule |

## Details

- Settings live in `~/.config/dropit/config.json` (readable only by you): this machine's key, its place in the queue,
  the `watch` folder. Never share or commit it.
- The terminal joins as a full device: it can send, receive and add devices.
- Output is in English, or Simplified Chinese when `LANG` is Chinese. `DROPIT_LANG=en` or `DROPIT_LANG=zh` overrides.
- Problems or ideas: [open an issue](https://github.com/smart-kits/dropit-client/issues) (it's public — never paste a token, `dk_…`).

## License

[MIT](../LICENSE)
