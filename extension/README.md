# dropit browser extension

**English** · [简体中文](./README.zh-CN.md)

Send the current page, a link, or selected text to dropit — right-click, done.
Chrome and Edge (Manifest V3). **Send-only**: it never reads anything back.

## Install

Not yet on the Chrome Web Store. Load it unpacked:

1. `git clone https://github.com/smart-kits/dropit-client.git`
2. Open `chrome://extensions` (Edge: `edge://extensions`) and turn on **Developer mode**
3. Click **Load unpacked** and choose the `extension/` folder

## First run

Click the dropit icon. It opens on *Join with a pairing code*:

- **Already using dropit** → generate a pairing code on another device (e.g. `dropit code`), type it in, then *Join*
- **First device** → the small *Create a new account* link under the form

## Usage

| Where | What gets sent |
|---|---|
| Right-click selected text | The text |
| Right-click a link | The link address |
| Right-click an image | The image **address** (not the file) |
| Right-click the page | The page address |
| Icon → *Send this page* | The current page address |

**Feedback you can trust:**

- **Success** — a green ✓ badge for 2.5 s. Sending the same thing again within a minute also counts as success; it is recognized and not stored twice.
- **Failure** — a red ✗ badge **and** a system notification with the reason. Being offline is never shown as success.

To disconnect this browser: icon → *Sign out on this browser*.

## Permissions

Only four: `contextMenus` · `storage` · `activeTab` · `notifications`.

**No host permissions** — the extension can't read or change any website.
The trade-off is that it can't read an image's bytes, so right-clicking an image sends
its address. To send actual files, use the [CLI](../cli/) (`dropit send -f`).

## Privacy

- Each browser is paired as **its own device**. The token lives in the browser's *local* extension storage and is never synced to your other browsers — so each one can be revoked on its own.
- When joining with a pairing code the extension gets a **send-only** (`ingest_only`) token: even if it leaked, it could not read any of your items, pair new devices or change settings.

## Files

```
extension/
├── manifest.json     Manifest V3
├── api.js            API calls, endpoint fallback, error messages
├── background.js     Context menus, sending, badge and notification feedback
├── popup.html/.js    Popup: first-run setup, send this page
└── icon.png
```

No build step — edit a file and reload the extension.

## Limitations

- Send-only. To receive, use the [Obsidian plugin](https://github.com/smart-kits/dropit-obsidian) or `dropit watch`.
- Images are sent as addresses, not files.
- The interface follows your language: English by default, Simplified Chinese when your browser is set to Chinese.

## License

[MIT](../LICENSE)
