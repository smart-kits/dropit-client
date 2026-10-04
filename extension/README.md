# dropit browser extension

**English** · [简体中文](./README.zh-CN.md)

Send what you're looking at to dropit — a page, selected text, a link, an image, a file, a screenshot.
Chrome and Edge (Manifest V3). **Send-only**: it never reads your items back.

## Install

Not yet on the Chrome Web Store. Load it unpacked:

1. `git clone https://github.com/smart-kits/dropit-client.git`
2. Open `chrome://extensions` (Edge: `edge://extensions`) and turn on **Developer mode**
3. Click **Load unpacked** and choose the `extension/` folder

## First run

Until this browser is joined, the icon shows a red **!**. Click it: it opens on *Join with a pairing code*.

- **Already using dropit** → generate a pairing code on another device (e.g. `dropit code`), type it in, then *Join*
- **First device** → the small *Create a new account* link under the form

## Sending

| You do | What gets sent |
|---|---|
| Click the icon (or <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>D</kbd>), press <kbd>Enter</kbd> | **This page**: its address, title and the description its author wrote. If you had text selected, the box opens with it filled in, and <kbd>Enter</kbd> sends that instead. The line under the box always says what <kbd>Enter</kbd> will send |
| Type or paste in the box, <kbd>Enter</kbd> | The text (a lone address is sent as a link). <kbd>Shift</kbd>+<kbd>Enter</kbd> starts a new line |
| Paste a screenshot or a copied file into the box | The file. Several files (plus any text) arrive together as one batch, up to 16 |
| <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>S</kbd> | Right away, nothing opens: the selection if there is one, otherwise this page |
| Right-click anywhere on a page | One **dropit** entry, opening onto what fits there — the specific things first, *Send this page* always last |
| … on selected text | *Send selected text* (with its line breaks) |
| … on a link | *Send this link*; a link to a file (PDF, ZIP, image, video…) also offers *Send the linked file* |
| … on an image | *Send this image* sends the file; *Send image link* sends its address |
| … on a video or audio | *Send this video* when the page plays a real file. Streaming players (YouTube, Bilibili…) split a video into hundreds of pieces with no single file, so there is only *Send this page* |
| A PDF open in Chrome's viewer | Chrome shows no extension items inside its PDF viewer: click the icon and press <kbd>Enter</kbd>, or <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>S</kbd> — both send **the PDF file** |
| Right-click the toolbar icon | *Send this page to dropit* |

Selected text, images and files carry a short note of **where they came from**: the page's address and title, nothing else.

Change the shortcuts at `chrome://extensions/shortcuts`. 
**Feedback you can trust:**

- **In the popup** — while sending, the stamp is pressed and the envelope's stripes run; a batch counts up (*Sending 2 of 3…*). Then the popup **stays open**: *✓ Sent #71 · 14:07* under the box, the box cleared for the next one. A failure is a red RETURNED mark with the reason, and what you wrote stays.
- **From a right-click or a shortcut** — a short note at the top of the page: *dropit · Sent #71*, or in red *dropit · Returned: …* (5 s). On pages that can't show one (chrome://, the Web Store) a failure becomes a system notification.
- The icon also shows a blue ✓ or a red ✗ for 2.5 s. Sending the same thing again within a minute counts as success; it is recognized and not stored twice. Being offline is never shown as success.
- A dropped connection (a flaky proxy) is retried twice before it counts as a failure.
- **Too large** — checked before downloading: a file over your plan's limit is refused without being fetched first.

To disconnect this browser: popup → *Sign out on this browser*. If this browser is removed from your account elsewhere, the extension notices on its next send, says so, and goes back to *Join*.

## Permissions

| Permission | Why |
|---|---|
| `contextMenus` | The right-click items |
| `activeTab` + `scripting` | Only on the tab you act on, at the moment you act: read its title, description and selection, and fetch the image or file you right-clicked |
| `storage` | This browser's token and settings |
| `notifications` | Say how a send went where the page can't show it (chrome://, the Web Store) |
| `declarativeNetRequestWithHostAccess` | Only for a site you allowed: when the extension has to fetch a file itself, it sends the page you were on as the referrer, the way the page would — some image hosts refuse anything else. No warning at install; it can't touch any site you haven't allowed |
| Optional: one site at a time | See below |

**No host permissions at install** — the extension can't read any website until you act on it.

Images and files are fetched **by the page itself**, the way the page already loads them, so most sites need nothing more.
A few sites don't let other pages read their files. Then dropit asks once for access to **that one site** (a small window with one button); you can take it back in the extension's settings.
Sites that refuse anyone but themselves can't be helped — dropit says so and suggests *Send image link* instead.

## Privacy

- Each browser is paired as **its own device**. The token lives in the browser's *local* extension storage and is never synced to your other browsers — so each one can be revoked on its own.
- When joining with a pairing code the extension gets a **send-only** token: even if it leaked, it could not read any of your items, pair new devices or change settings.
- The token never enters a web page: the page only hands over the bytes, and the extension sends them.

## Files

```
extension/
├── manifest.json     Manifest V3
├── api.js            API calls, endpoint fallback, the account line
├── payload.js        Pure decisions: metadata and its size, menus, batches, what Enter sends, error text
├── page.js           Functions run in the page: title and description, selection, fetching a file
├── background.js     Context menus, shortcuts, sending, badge and notifications
├── popup.html/.js    The popup: first-run setup, the send box
├── popup.css         The look (shared with the access window)
├── grant.html/.js    The window that asks for access to one site
├── i18n.js           Interface text (English / Simplified Chinese)
└── fonts/            Label fonts (OFL, licences alongside)
```

No build step — edit a file and reload the extension. Tests: `node test/extension.test.mjs` and `node test/i18n.test.mjs` from the repository root.

## Limitations

- Send-only. To receive, use the [Obsidian plugin](https://github.com/smart-kits/dropit-obsidian) or `dropit watch`.
- Desktop Chrome has no system share sheet: sharing on a computer is right-click, the toolbar or a shortcut.
- Dragging a file from the desktop onto the popup doesn't work (the popup closes when you click elsewhere). Paste it, or use *Choose files*.
- The interface follows your language: English by default, Simplified Chinese when your browser is set to Chinese.

## License

[MIT](../LICENSE) · fonts: [Barlow Condensed](./fonts/OFL-barlow-condensed.txt) and a subset of [Smiley Sans](./fonts/OFL-smiley-sans.txt), SIL Open Font License
