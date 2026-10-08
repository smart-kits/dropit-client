# AGENTS.md

Instructions for AI agents (Claude Code, Codex, Cursor, …) that **install dropit for a user**
or **work on this repository**. Humans: see [README.md](./README.md).

---

## Part 1 · Installing dropit for a user

### Ground rules

1. **Never print, log, paste or commit a token.** Tokens start with `dk_`. They live in
   `~/.config/dropit/config.json` (CLI) and must stay there. To confirm pairing worked,
   run `dropit me` — never `cat` the config file.
2. **Ask before creating an account.** Ask the user: *"Do you already use dropit on another device?"*
   - **No** → this machine is the first device; `dropit pair` creates the account.
   - **Yes** → ask them to generate a pairing code on that device (`dropit code`,
     *Settings → dropit → Pairing code* in Obsidian, or *+ Add a device* in the browser extension
     they created the account in) and give it to you. Then `dropit pair <code>`.
     A code is 6 characters and expires after 5 minutes.
   Creating a second account by mistake splits the user's items across two accounts.
3. **Don't use `sudo` without asking.** Prefer a user-writable directory on `PATH`.
4. Steps marked **👤 user** need the user's hands (a browser or phone UI). Tell them exactly
   what to click; don't pretend you did it.

### Which clients to install

Ask which devices the user wants, or install the CLI first — it is the base for the others
and the easiest to verify.

| Client | Where | Can an agent do it end-to-end? |
|---|---|---|
| CLI | macOS / Linux terminal | ✅ yes |
| Browser extension | Chrome / Edge | ⚠️ partly — loading it is 👤 user |
| iOS Shortcuts | iPhone / iPad | ❌ no — 👤 the user installs it in one tap; you can check the device slot |
| Obsidian plugin | Obsidian vault | ✅ mostly — see [dropit-obsidian/AGENTS.md](https://github.com/smart-kits/dropit-obsidian/blob/main/AGENTS.md) |

### A · CLI

**Check prerequisites**

```bash
node --version    # needs >= 18; real-time `watch` needs >= 22
git --version
```

If Node.js is missing or too old, ask before installing it (e.g. `brew install node` on macOS).

**Install**

```bash
git clone https://github.com/smart-kits/dropit-client.git ~/.dropit-client
mkdir -p ~/.local/bin
ln -sf ~/.dropit-client/cli/dropit ~/.local/bin/dropit
command -v dropit || echo 'Add ~/.local/bin to PATH'
```

If `~/.local/bin` is not on `PATH`, add `export PATH="$HOME/.local/bin:$PATH"` to the user's
shell profile (`~/.zshrc` on macOS) — tell them you did.

**Pair** (follow ground rule 2 first)

```bash
dropit pair            # first device: creates the account
dropit pair K7M2QX     # joining: use the code the user gave you
```

**Verify**

```bash
dropit me                          # prints plan · devices · storage — proves the token works
dropit send "dropit is set up ✅"   # prints ✓ #<seq>
```

If the user wants to receive on this machine too:

```bash
dropit watch ~/dropit              # Ctrl-C to stop; the item above appears as a .md file
```

To keep `watch` running at login, use the launch agent (macOS) or user service (Linux) in
[cli/README.md](./cli/README.md#keep-it-running) — replace its paths with the output of
`command -v dropit` and `dirname "$(command -v node)"`.

**Optional integrations**: Raycast, the macOS Services menu and Alfred are described in
[cli/README.md](./cli/README.md#from-anywhere-on-your-mac). Raycast only needs 👤 the user to add the
`cli/raycast/` directory.

### B · Browser extension

```bash
git clone https://github.com/smart-kits/dropit-client.git ~/.dropit-client   # skip if already cloned
```

👤 **User**, in Chrome or Edge:

1. Open `chrome://extensions` (Edge: `edge://extensions`) and turn on **Developer mode**
2. **Load unpacked** → choose `~/.dropit-client/extension`
3. Click the dropit icon → either create an account (first device) or enter a pairing code

To produce a pairing code for the extension from a paired CLI, run `dropit code` and give the
user the result. Verify by asking the user to click the dropit icon and press Enter (sends the current page), then check it
arrived (e.g. with `dropit watch`). Shortcuts: <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>D</kbd> opens the popup,
<kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>S</kbd> sends the selection (or the page) right away.

### C · iOS Shortcuts

👤 **User**, on the iPhone or iPad: open https://dropit.smart-kits.xyz in Safari →
**Install the shortcut** → **Add Shortcut**. Details in [shortcuts/README.md](./shortcuts/README.md).

To pair it, the user needs a QR code from a device that already uses dropit: the web inbox's
*Add a device → Show pairing code*. They scan it with the iPhone camera and tap
**Set up the shortcut** — no code to type. The shortcut becomes its own send-only device, so check
`dropit me` first: on a plan with a device limit, a full account must free a slot (`dropit revoke <id>`).

### Updating and uninstalling

```bash
git -C ~/.dropit-client pull                  # update
dropit devices                                # find this machine's device_id
dropit revoke <device_id>                     # revoke it — needs a device with a full token
rm ~/.local/bin/dropit && rm -rf ~/.dropit-client ~/.config/dropit   # remove
```

### Troubleshooting

| Output | Meaning | Fix |
|---|---|---|
| `Not paired yet` | No token yet | `dropit pair` / `dropit pair <code>` |
| `Invalid token` · `This device was removed` | Token invalid or device revoked | Get a new code, `dropit pair <code>` |
| `Invalid pairing code` · `Pairing code expired` | Wrong or expired code | Ask for a fresh code (valid 5 min) |
| `Device limit reached` | Device limit reached | `dropit devices`, then `dropit revoke <id>` an unused one |
| `Network unavailable (tried N addresses)` | No service address reachable | Check the network / proxy |
| `Node < 22 …` | No global WebSocket | Upgrade Node.js for real-time `watch` |

Messages are shown in English unless `LANG` (or `DROPIT_LANG`) is Chinese. For predictable output while installing, run the CLI with `DROPIT_LANG=en`.

---

## Part 2 · Working on this repository

- **Enable the secret scan first:** `git config core.hooksPath .githooks` (needs `gitleaks`).
- **Commit messages in English.**
- **Docs come in pairs:** every `README.md` (English) has a `README.zh-CN.md` twin. Change both.
- **No backend details.** Don't add infrastructure names, internal design references or server
  internals to docs or comments. Clients talk to the API; that's all they need to know.
- **No build step, no dependencies.** Keep the CLI a single file and the extension loadable as-is.
- **Interface text is bilingual.** Strings live in `T` (CLI) and `extension/i18n.js` (+ `_locales/` for the manifest), each with `en` and `zh`. Add every new string to both.
- **Test before pushing:** `node test/i18n.test.mjs`, `node test/extension.test.mjs`, `node test/cli-render.test.mjs`, `node test/cli-logic.test.mjs` and `node --check cli/dropit`.
