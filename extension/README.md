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
  Or the paperclip in the box (*Attach files*).
- **Several at once** — some text and up to 10 files — arrive together as one delivery.
- <kbd>Shift</kbd>+<kbd>Enter</kbd> starts a new line. <kbd>Enter</kbd> that picks a word in an input method never sends.

While it sends, the **Send** button reads *Sending*, the envelope's red-and-blue stripes run, and a batch counts up
(*Sending 2 of 3…*). Then the popup **stays open**: *✓ Sent #71 · 14:07* under an empty box, ready for the next thing.

**Recently sent** lists the last five things this browser sent (from the popup, the right-click menu or the shortcut), newest first:
text and links have a copy button, a link's title opens it. In a browser that created the account or joined with a code, each row also says who it went to
(*→ All*, *→ a device's name*, *→ 3 devices*). The list is kept only in this browser's own storage,
forgotten after a week or when you sign out, and never sent anywhere. If a send comes back, a card with a pale red outline, the reason and a
*Try again* button sits above it until you edit the box.

The **sun icon** at the top right holds the rest: *Add a device*, *Send to by default* (see below), the theme (match system, light or dark), the source code,
reporting a problem, the privacy notice and signing out.

## Choose which devices a send goes to

In a browser that **created the account or joined with a code**, once the account has two or more other devices that can receive, the block on the right of the box gets a second part
under **Send**: a light strip that says where Send goes (*→ All*, *→ Mac, Pixel*, *→ 3 devices*). By default everything goes to all your devices, and nothing changes. To pick:

- **This one send**: press that strip. One list, *Send to which devices*, with your default already ticked.
  Untick what you don't want, then press the big button at the bottom, which is the only thing that sends. Afterwards it goes
  back to the default. Close the list without pressing it and nothing changed. At least one device stays ticked.
- **Every send**: menu at the top right › *Send to by default*. Ticking saves at once. The right-click menu and the keyboard
  shortcut send to this default too.
- **Saved in this browser only.** Ticking every device saves *All*, so devices you add later are included; ticking some
  saves just those. Signing out or reinstalling puts it back to *All*. A device that was removed is taken off the default
  (you are told once); if none is left it is back to *All*.
- A browser that joined earlier with the old send-only key can't read the device list, so it only has **Send**, and sends to all —
  until you [turn on receiving](#receive-here-too) there, which gives it a full key like every browser that joins now.

## Add a device

What you send needs somewhere to arrive. In a browser that **created the account or joined with a code**, the popup adds your
phone, Obsidian or another computer:

- **Right after *Create a new account*** it already shows a 6-character code and its QR code (also copied for you).
  Scan the QR code with your phone's camera, or type the code in Obsidian, in a terminal (`dropit pair <code>`) or in
  another browser. Later, it's *Add a device* in the menu at the top right.
- **It notices when the other device has joined**: the code goes away and the line under the box says
  *✓ The new device joined: iPhone · Shortcuts*.
- **A code lasts 5 minutes and works once**; the countdown is under it. Clicking away closes the popup but not the code —
  open it again and the same code is still there.
- **All device slots in use?** It says which device the new one takes the place of — the one idle longest, unless you
  pick another — so joining is still one step on the other device.
- **Joining and the slots are full?** The new browser says the device limit was reached and what to do: remove one on a
  device you already use, or (free accounts, when Paid is offered) a link to upgrade.

Only a browser that joined earlier with the old send-only key doesn't show this, until you
[turn on receiving](#receive-here-too) there. Make codes on a device with a full key instead — the web inbox, Obsidian,
`dropit code`, or another browser.

## Receive here too

A browser that **created the account or joined with a code** also lists what your other devices sent, from the moment it joins. It is deliberately plain: **no
notifications, no connection kept open in the background.** Open the popup and, under *Received*, it fetches what came since
last time. **Refresh** (the small arrow next to the heading) fetches again.

- **Only what other devices sent**, newest first: up to **50**, kept for **7 days** in this browser's own storage and nowhere else.
  What this browser sent itself stays under *Recently sent*. Signing out, or a key that stops working, empties the list.
- **Text** has a copy button. **A link** opens from its title and shows the site's name; only `http` and `https` addresses ever
  open (anything else a device sent is shown as text, never opened). **A file or image** has a download button: it asks
  for a fresh download link when you press it (a link lasts only minutes, so none is kept) and opens it in a new tab. A file past its
  keeping time says it is no longer available; on a free account (when Paid is offered) that line also says how long each plan
  keeps items and has a link to Paid, as does the line for a day's downloads being used up.
- **The first time** it lists what is already waiting for this browser (the latest 50 from other devices) instead of starting empty:
  sending something from your phone and then opening the popup is the usual way to try it. Nothing pops up either way.
- Each refresh makes a few requests at most; a long backlog is caught up on the next one.

**Joining with a pairing code gives the browser a full key, so it receives right away** — nothing more to turn on. Only a browser
that joined with an earlier version of the extension has a send-only key. To receive there, open the menu
at the top right › **Receive on this browser** (shown only in such a browser). It says one thing first: *Once on, this browser can
read what is in your account.* Then enter a code from a device that has full access — the browser that created the account, Obsidian,
the web inbox, `dropit code` — and press *Agree and turn on*. The browser joins again in its own place: no extra device slot, the
send-only key stops working and the new full one takes over. A code that is refused changes nothing; it keeps sending as before.

The menu at the top right also has a small line with the extension's version and whether this browser can *send and receive* or is *send only*.

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
- **In the popup** — *✓ Sent #71* under the box, or a card with a pale red outline, the reason and a *Try again* button; what you wrote stays put.
- **On the icon** — a blue ✓ or a red ✗ for a moment, and a red **!** while this browser isn't joined to your account.
- **Offline is never shown as sent.** A connection dropped on the way (a flaky proxy, patchy Wi-Fi) is tried twice more
  before it counts as failed.
- **Clicked twice?** The same thing sent twice in a row within a minute is kept once — and still reported as sent.
- **Too big?** A file over your plan's size limit is turned down *before* it downloads, so you don't wait for nothing.
  On a free account, when Paid would take it, the note says what Paid allows and links to it.

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
   Type the 6 characters in the airmail-striped box and press *Agree and join*; case, spaces and `0`/`O` mix-ups don't matter.
   The three steps under the box (*Where to get the code*) say how to get one.
   Your very first device? Use *Agree and create a new account* next to *No account yet?* instead — the popup then shows a code
   for [your next device](#add-a-device) straight away.
   If many accounts were created on your network today, creating one may take a few extra seconds ("Preparing the account…") instead of sending you elsewhere.

**Updating:** unzip the new version over the same folder, then press *Reload* on `chrome://extensions`.
Same folder, same extension — you stay joined.

## What it can read, and when

| Permission | What for |
|---|---|
| `activeTab` + `scripting` | Only the tab you act on, at the moment you act: its title, description and selection, and the image or file you right-clicked |
| `contextMenus` | The **dropit** right-click entry |
| `storage` | This browser's sign-in, and the short lists of what it sent and received, kept on this computer only |
| `notifications` | Saying how a send went where a page can't show the note (`chrome://` pages, the Web Store) — nothing else is ever announced |
| `declarativeNetRequestWithHostAccess` | Only for a site you allowed (below): when the extension has to fetch a file itself, it says which page you were on, as the page would — some image hosts insist |
| Optional: one site at a time | Asked in a small window with one button, only when a site won't let the page hand over a file |

**Nothing is granted at install** — the extension can't read any website until you act on it.
Images and files are fetched **by the page itself**, the way it already loads them, so most sites need nothing more.
The rare site that refuses gets a one-site question; you can take it back on the extension's details page.

**Your account stays safe.** Each browser is its own device. Creating the account and joining with a code both give the browser a
**full** key — it can [add your other devices](#add-a-device) and [list what they sent](#receive-here-too). Like any full key it
could read your items if it leaked, so sign that browser out on a computer that isn't yours. (A browser that joined with an
earlier version has a send-only key until you turn on receiving in it.) The key stays in the extension's own storage on this computer
and never enters a web page — the page only hands over the bytes. To disconnect this browser, use
*Sign out* in the menu at the top right of the popup (a screen says what goes with it before it signs out; text you haven’t sent is not kept), or remove it from another device.

## What isn't possible

- **Streaming video** (YouTube, Bilibili, most video sites) is played as hundreds of small pieces, not a file:
  there's nothing whole to send, so you get *Send this page*. Download it with a video tool, then send the file.
- **A few image hosts only serve their own pages.** When even naming the page doesn't help, the red note says so
  and suggests *Send image link*.
- **Dragging a file from the desktop onto the popup** doesn't work — the popup closes the moment you click elsewhere.
  Copy it in Finder and paste it into the popup, or use the paperclip.
- **No notifications, no live updates.** The extension lists what other devices sent when you open the popup or press
  *Refresh*; it doesn't watch in the background. To be told as it arrives, or to have it saved to a folder or a note, use
  [Obsidian](https://github.com/smart-kits/dropit-obsidian), the [terminal](../cli/), the phone app or the web inbox.
- Desktop Chrome has no system share sheet: the right-click entry, the icon and the two keys are its share buttons.

## For developers

```
extension/
├── manifest.json     Manifest V3
├── background.js     Right-click menu, keys, sending, the note on the page, badge and notifications
├── popup.html/.js    The popup: joining, the send box, progress, recently sent, the menu, adding a device
├── recent.js         The short list of what this browser sent, kept in its own storage
├── receive.js        What other devices sent: fetching from a saved position, the list (50, 7 days), a fresh link for a file
├── targets.js        Pure decisions for choosing devices: the default, checking it against the device list, how a target is written short
├── icons.js          The popup's line icons
├── pairing.js        Pure decisions for adding a device: which key may, which slot to replace, when to stop watching
├── qr.js             A tiny QR encoder (from the Obsidian plugin, MIT), pinned by fingerprints in the tests
├── popup.css         The popup's look: airmail envelope, cards, light and dark (shared with the one-site window)
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


## The other devices

dropit has a client for each of your devices, and they all join the same account with a pairing code. Which ones send, which receive, and how to
combine them: [all the clients](../README.md#on-every-device-you-own). Or open [dropit.smart-kits.xyz](https://dropit.smart-kits.xyz) on any device: it recommends one.

## License

[MIT](../LICENSE) · fonts: [Barlow Condensed](./fonts/OFL-barlow-condensed.txt) and a subset of
[Smiley Sans](./fonts/OFL-smiley-sans.txt), SIL Open Font License
