# dropit for Chrome

**English** · [简体中文](./README.zh-CN.md)

**What you're looking at in the browser, on your other devices — in one move.**
A page, a paragraph, an image, a PDF, a screenshot: a few seconds later it is waiting in Obsidian,
on your phone, or in any browser. Chrome and Edge.

![Right-click a page, a selection, a link, an image, a PDF, a download link or a video → dropit; or paste and pick files in the popup. Each lands on the phone a moment later.](../media/extension-tour.webp)

<sub>Shown: <i>Big Buck Bunny</i> © Blender Foundation (CC BY 3.0) on YouTube · Wikipedia (CC BY-SA 4.0) · Hacker News · NASA images and video (public domain) · <i>Attention Is All You Need</i>, arXiv:1706.03762 · <i>Pride and Prejudice</i>, Internet Archive (public domain).</sub>

## One move each

| You're looking at… | Do this | Your other devices get |
|---|---|---|
| An article to finish on your phone | Click the dropit icon, press <kbd>Enter</kbd> | The link **with its title and summary**, not a bare address |
| A paragraph worth keeping | Select it, press <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>S</kbd> | The text, line breaks intact, with a link back to the page it came from |
| A picture | Right-click it → **dropit** → *Send this image* | The image file itself — it opens offline and doesn't vanish with the website |
| A screenshot you just took | <kbd>⌃</kbd><kbd>⇧</kbd><kbd>⌘</kbd><kbd>4</kbd>, then <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>D</kbd>, <kbd>⌘</kbd><kbd>V</kbd>, <kbd>Enter</kbd> | The screenshot as an image file |
| A PDF open in Chrome | Click the icon, press <kbd>Enter</kbd> | The PDF file, not its link |
| A video the page plays as a file | Right-click it → **dropit** → *Send this video* | The video file ([streaming sites can't](#what-isnt-possible)) |
| A thought of your own | Click the icon, type, <kbd>Enter</kbd> | Your text |

Nothing to choose on the way — no folder, no tag, no "send to". It goes to every device you've joined.

## The popup

Click the dropit icon, or press <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>D</kbd>. It opens on a box, ready for you to type.

- **The line under the box always says what <kbd>Enter</kbd> will send** — *Enter sends this page: …*,
  *Enter sends this text*, *Enter sends 2 files and the text*. No guessing.
- **Empty box** → this page, with its title and the description its author wrote. On a PDF: the PDF itself.
- **Something selected on the page?** It's already in the box, and it goes with a note of the page it came from.
  Edit it first if you like.
- **Paste anything**: text, a link (sent as a link), a screenshot, files copied in Finder (<kbd>⌘</kbd><kbd>V</kbd>).
  Or *Choose files*.
- **Several at once** — some text and up to 10 files — arrive together as one delivery.
- <kbd>Shift</kbd>+<kbd>Enter</kbd> starts a new line. <kbd>Enter</kbd> that picks a word in an input method never sends.

While it sends, the stamp stays pressed, the envelope's red-and-blue stripes run, and a batch counts up
(*Sending 2 of 3…*). Then the popup **stays open**: *✓ Sent #71 · 14:07* under an empty box, ready for the next thing.

## Right-click

Right-click anywhere on a page and there is one **dropit** entry. It opens onto what fits right there,
the most specific first — *Send this page* is always the last line:

| Right-click on… | Under **dropit** |
|---|---|
| Selected text | *Send selected text* · *Send this page* |
| An image | *Send this image* · *Send image link* · *Send this page* |
| An image that is also a link | *Send this image* · *Send image link* · *Send this link* · *Send this page* |
| A link | *Send this link* · *Send this page* |
| A link to a file (PDF, ZIP, image, video, Office…) | adds *Send the linked file* |
| A video or audio the page plays as a file | *Send this video* / *Send this audio* · *Send this page* |
| Anywhere else | *Send this page* |

Right-clicking the dropit icon in the toolbar offers *Send this page to dropit*.

## Two keys

| Keys | What happens |
|---|---|
| <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>S</kbd> | Sends **right away**, nothing opens: the selection if there is one, otherwise this page (on a PDF, the file) |
| <kbd>Alt</kbd>+<kbd>Shift</kbd>+<kbd>D</kbd> | Opens the popup, your selection already in it |

On a Mac <kbd>Alt</kbd> is <kbd>⌥</kbd>. Change them at `chrome://extensions/shortcuts` (Edge: `edge://extensions/shortcuts`).
After updating from an older version Chrome sometimes leaves them blank — set them there once.

## You always know whether it went

- **After a right-click or a key** — a short note at the top of the page: *dropit · Sent #71*.
  If it didn't go: a red *dropit · Returned: …* with the reason, for five seconds.
- **In the popup** — *✓ Sent #71* under the box, or a red **RETURNED** mark with the reason; what you wrote stays put.
- **On the icon** — a blue ✓ or a red ✗ for a moment, and a red **!** while this browser isn't joined to your account.
- **Offline is never shown as sent.** A connection dropped on the way (a flaky proxy, patchy Wi-Fi) is tried twice more
  before it counts as failed.
- **Clicked twice?** The same thing sent twice in a row within a minute is kept once — and still reported as sent.
- **Too big?** A file over your plan's size limit is turned down *before* it downloads, so you don't wait for nothing.

## How it arrives

| Where you receive | A page | Selected text | An image or a file |
|---|---|---|---|
| [Obsidian](https://github.com/smart-kits/dropit-obsidian) | `[Title](link)`, the summary as a quote | The text, then `— [Page title](link)` | Saved in your attachments folder, embedded in the note |
| Web inbox | A card: title, address, summary | The text, then *From Page title ↗* | A download link |
| [Terminal](../cli/) (`dropit watch`) | A Markdown note with the title and summary | The text and its source line | The file, saved as it is |

## Set up in a minute

1. **Get it.** It isn't on the Chrome Web Store yet: download it from
   [dropit.smart-kits.xyz](https://dropit.smart-kits.xyz) and unzip it, or
   `git clone https://github.com/smart-kits/dropit-client.git` and use the `extension/` folder.
2. **Load it.** Open `chrome://extensions` (Edge: `edge://extensions`), turn on **Developer mode**,
   click **Load unpacked** and choose the folder. Pin the dropit icon to the toolbar if you like seeing the ✓.
3. **Join your account.** Click the icon. On a device you already use, show a pairing code — in the web inbox
   under *Devices*, in Obsidian under *Settings → dropit*, or with `dropit code` in a terminal.
   Type the 6 characters and press *Join*; case, spaces and `0`/`O` mix-ups don't matter.
   Your very first device? Use the small *Create a new account* link under the form instead.

**Updating:** unzip the new version over the same folder, then press *Reload* on `chrome://extensions`.
Same folder, same extension — you stay joined.

## What it can read, and when

| Permission | What for |
|---|---|
| `activeTab` + `scripting` | Only the tab you act on, at the moment you act: its title, description and selection, and the image or file you right-clicked |
| `contextMenus` | The **dropit** right-click entry |
| `storage` | This browser's sign-in, kept on this computer only |
| `notifications` | Saying how a send went where a page can't show the note (`chrome://` pages, the Web Store) |
| `declarativeNetRequestWithHostAccess` | Only for a site you allowed (below): when the extension has to fetch a file itself, it says which page you were on, as the page would — some image hosts insist |
| Optional: one site at a time | Asked in a small window with one button, only when a site won't let the page hand over a file |

**Nothing is granted at install** — the extension can't read any website until you act on it.
Images and files are fetched **by the page itself**, the way it already loads them, so most sites need nothing more.
The rare site that refuses gets a one-site question; you can take it back on the extension's details page.

**Your account stays safe.** Each browser joins as its own device, and joining with a code gives it a
**send-only** key: even if it leaked, nobody could read your items, add devices or change settings with it.
The key never enters a web page — the page only hands over the bytes. To disconnect this browser, use
*Sign out on this browser* at the bottom of the popup, or remove it from another device.

## What isn't possible

- **Streaming video** (YouTube, Bilibili, most video sites) is played as hundreds of small pieces, not a file:
  there's nothing whole to send, so you get *Send this page*. Download it with a video tool, then send the file.
- **A few image hosts only serve their own pages.** When even naming the page doesn't help, the red note says so
  and suggests *Send image link*.
- **Dragging a file from the desktop onto the popup** doesn't work — the popup closes the moment you click elsewhere.
  Copy it in Finder and paste it into the popup, or use *Choose files*.
- **Send-only.** To receive on a computer, use [Obsidian](https://github.com/smart-kits/dropit-obsidian),
  the [terminal](../cli/) or the web inbox.
- Desktop Chrome has no system share sheet: the right-click entry, the icon and the two keys are its share buttons.

## For developers

```
extension/
├── manifest.json     Manifest V3
├── background.js     Right-click menu, keys, sending, the note on the page, badge and notifications
├── popup.html/.js    The popup: joining, the send box, progress
├── popup.css         The airmail look (shared with the one-site window)
├── payload.js        Pure decisions: what Enter sends, metadata and its size, menus, batches, error text
├── page.js           Run inside the page: title and description, selection, fetching a file, the note
├── api.js            Requests, retries, falling back to the built-in address
├── grant.html/.js    The one-site permission window
├── i18n.js           Interface text, English and Simplified Chinese
└── fonts/            Label fonts (SIL Open Font License, licences alongside)
```

No build step — edit, then *Reload* on `chrome://extensions`. Tests, from the repository root:
`node test/extension.test.mjs` and `node test/i18n.test.mjs`.
The interface follows the browser's language: English, or Simplified Chinese when the browser is set to Chinese.

## License

[MIT](../LICENSE) · fonts: [Barlow Condensed](./fonts/OFL-barlow-condensed.txt) and a subset of
[Smiley Sans](./fonts/OFL-smiley-sans.txt), SIL Open Font License
