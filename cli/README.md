# dropit CLI

**English** · [简体中文](./README.zh-CN.md)

dropit on the command line — it both **sends** and **receives**.
It is also the engine behind the Raycast, Alfred and macOS Services integrations:
many entry points, one piece of logic.

- Single file, zero dependencies
- Node.js ≥ 18 (real-time `watch` needs Node.js ≥ 22)
- macOS and Linux

## Install

```bash
git clone https://github.com/smart-kits/dropit-client.git
cd dropit-client/cli
ln -s "$(pwd)/dropit" /usr/local/bin/dropit     # prefix with sudo if needed, or link into ~/.local/bin
```

## First run

```bash
dropit pair            # first device: creates your account
dropit code            # prints a pairing code for your next device (valid 5 minutes)
dropit pair K7M2QX     # on another device: join with that code
```

Your settings, including the token, are saved to `~/.config/dropit/config.json` with mode `0600`.

## Commands

| Command | What it does |
|---|---|
| `dropit pair [code]` | Without a code: create an account. With a code: join an existing account |
| `dropit code` | Generate a pairing code for a new device (valid 5 minutes) |
| `dropit send <text or link>` | Send text; links are detected automatically. Also reads a pipe: `pbpaste \| dropit send` |
| `dropit send -f <file>` | Send a file — images, PDFs, videos and more. Streamed, so large files don't fill memory |
| `dropit watch [folder]` | Receive into a local folder, in real time (default `~/dropit`) |
| `dropit me` | Show your plan, devices and storage used |
| `dropit devices` | List paired devices |
| `dropit revoke <device_id>` | Revoke a device and free its slot |

## Receiving with `watch`

`dropit watch` first catches up on everything you haven't received yet, then stays
connected and writes new items the moment they arrive.

| Item | Saved as |
|---|---|
| Text or link | `YYYY-MM-DD-<seq>.md`, with front matter (`dropit_seq`, `kind`, `source`, `created`) |
| File | `YYYY-MM-DD-<seq>-<original name>` |

- **Nothing is skipped.** Progress advances only after a file is written. If `watch` is interrupted, the next run picks up exactly where it stopped; existing files are never overwritten.
- **Stays alive across sleep.** A heartbeat every 60 s detects dead connections (after sleep/wake, a socket often looks open but receives nothing) and reconnects with backoff from 1 s up to 60 s.
- **Without real-time push** (Node.js < 22, or not included in your plan), `watch` catches up once and tells you why it stopped.

### Keep `watch` running (macOS launchd)

```bash
cat > ~/Library/LaunchAgents/io.github.smart-kits.dropit.plist <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>Label</key><string>io.github.smart-kits.dropit</string>
  <key>ProgramArguments</key><array>
    <string>/usr/local/bin/dropit</string><string>watch</string>
  </array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict></plist>
PLIST
launchctl load ~/Library/LaunchAgents/io.github.smart-kits.dropit.plist
```

## Integrations

### Raycast

Raycast → Extensions → Script Commands → *Add Directory* → choose [`raycast/`](./raycast/).
You get two commands: send the clipboard, and send a file.

### macOS Services menu (right-click selected text in any app)

Automator → New *Quick Action* → workflow receives **text** → add *Run Shell Script*,
with *Pass input* set to **as arguments**:

```bash
export PATH=/usr/local/bin:$PATH
dropit send "$1"
```

Save it as `Send to dropit`. Now select text in any app → right-click → Services → Send to dropit.
You can bind a global shortcut under *System Settings → Keyboard → Keyboard Shortcuts → Services*.

### Alfred

New Workflow → *Hotkey* trigger → connect a *Run Script* (`/bin/bash`):

```bash
export PATH=/usr/local/bin:$PATH
pbpaste | dropit send
```

## When something goes wrong

| Message | Meaning | What to do |
|---|---|---|
| `token 无效` / `设备已被移除` | The token is invalid or this device was revoked | `dropit pair <code>` again |
| `设备数已达上限` | Device limit reached | `dropit devices`, then `dropit revoke <id>` one you no longer use |
| `投递过于频繁` | Too many sends in a short time | Wait a moment and retry |
| `队列已满` | Storage is full | Wait for old items to expire (30 days) |
| `网络不可用（试过 N 个域名）` | No service address was reachable | Check your connection |
| `上传失败 …` | A file upload failed — reported, never silently dropped | Retry `dropit send -f` |

The CLI keeps a list of service addresses in its config. If the first one can't be reached,
it tries the next and remembers whichever worked.

## Limitations

- Windows is untested.
- `watch` has no special handling for a full disk or a read-only folder; such failures are logged.

## License

[MIT](../LICENSE)
