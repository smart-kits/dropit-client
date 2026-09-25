# dropit for iOS (Shortcuts)

**English** · [简体中文](./README.zh-CN.md)

The main way to send from iPhone and iPad: the **share sheet** and **Back Tap**.
Share a link or text, feel a short vibration — it's in your queue.

It is two shortcuts, built with Apple's Shortcuts app:

| Shortcut | Purpose | Run from |
|---|---|---|
| **dropit 配对** (pair) | Run once to pair this device and save its token | Manually |
| **dropit** | Send. Nothing to choose, nothing to confirm | Share sheet · Back Tap · Home Screen |

Pairing is a separate shortcut on purpose: the send path must never ask a question.

> A one-tap install link is not available yet — for now, build the two shortcuts by hand
> with the steps below. You can build them on a Mac: the Shortcuts app syncs them to your
> iPhone through iCloud, and a keyboard makes this much faster.

In the steps, **`{API}`** is the dropit service address — the same one your other clients use.
You'll find it in the CLI config (`~/.config/dropit/config.json`, first entry of `endpoints`)
or in Obsidian under *Settings → dropit →* 服务地址.

---

## 1 · dropit 配对 (run once)

| # | Action | Settings |
|---|---|---|
| 1 | Choose from Menu | Prompt: `这是你的第一台设备吗？` · Two items: `是，创建新账号` / `否，我有配对码` |
| 2 | └ **Yes** branch | |
| 3 | 　Get Contents of URL | `{API}/v1/accounts` · POST · JSON `{"device_name":"iPhone"}` |
| 4 | └ **No** branch | |
| 5 | 　Ask for Input | Text, prompt `配对码` |
| 6 | 　Get Contents of URL | `{API}/v1/pair/claim` · POST · JSON<br>`{"code": Provided Input, "device_name":"iPhone", "scope":"ingest_only"}` |
| 7 | Get Dictionary Value | Key `token`, from the previous result |
| 8 | Save File | To `iCloud Drive/Shortcuts/dropit.token`, **overwrite if the file exists** |
| 9 | Show Notification | `dropit：配对完成` |

- Get the pairing code from another device — `dropit code` in the CLI, or *Settings → dropit →* 配对码 in Obsidian. It's 6 characters, valid for 5 minutes, and forgiving about case, spaces, dashes and `0`/`O`, `1`/`I`/`l`.
- Instead of *Ask for Input* you can use **Scan QR Code** on the pairing QR code; pass the scanned text to `code` as-is.
- Joining with a code gives the phone a **send-only** (`ingest_only`) token on purpose: if it ever leaked, it could not read your items, pair new devices or change settings.
- The *Yes* branch creates an account with a `full` token, because your first device must be able to issue pairing codes for the others.

---

## 2 · dropit (send)

Create a new shortcut. In *Details*, turn on **Show in Share Sheet** and accept only **URLs** and **Text**.

| # | Action | Settings |
|---|---|---|
| 1 | Get File | `iCloud Drive/Shortcuts/dropit.token` — **turn off** *Error If Not Found* |
| 2 | If | *File* **does not have any value** → Show Notification `请先运行「dropit 配对」` → Stop |
| 3 | Get Current Date | — |
| 4 | Dictionary | Four keys, see below |
| 5 | Get Contents of URL | `{API}/v1/ingest` · POST · JSON (the dictionary from step 4)<br>Header `Authorization` = `Bearer ` + the file from step 1 |
| 6 | If | *Contents of URL* **has any value** → Vibrate; **Otherwise** → Show Notification `dropit：投递失败` |

The dictionary in step 4:

| Key | Value |
|---|---|
| `kind` | `url` or `text` |
| `raw` | Shortcut Input |
| `client_ts` | Current Date → formatted as a **Unix timestamp**, × 1000 (milliseconds) |
| `source` | `ios-shortcut` |

No hashing and no date formatting are needed — duplicate detection is handled for you.

### Back Tap

*Settings → Accessibility → Touch → Back Tap → Double Tap →* `dropit`.

For apps that don't offer a share sheet, make a copy that reads the **Clipboard** instead
(replace `raw` in step 4 with *Clipboard*): copy, then double-tap the back of your phone.

---

## What you'll see

| What happens | Meaning | What to do |
|---|---|---|
| One short vibration | Sent. Sending the same thing again on the same day also counts as success | — |
| Notification `投递失败` | No network, or the token is no longer valid | Check your connection; if it persists, run *dropit 配对* again |
| Notification `请先运行「dropit 配对」` | Not paired yet, or iCloud removed the token file | Run *dropit 配对* |

Shortcuts can't read HTTP status codes, so "offline" and "invalid token" look the same on the phone.
That's a deliberate trade-off: nothing extra on the send path.

## Limitations

- Text and links only; files and photos aren't supported by the shortcut yet — use the [CLI](../cli/) for files.
- These steps have not yet been verified end-to-end on a device. If a step doesn't match what you see, please open an issue.

## License

[MIT](../LICENSE)
