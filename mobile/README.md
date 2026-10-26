# dropit for Android

**English** · [简体中文](./README.zh-CN.md)

> Download the APK from the Releases page or from the dropit website. Not on Google Play yet.

Send without opening the app: *Share* → **dropit**, select text → **dropit**, *Open with* → **dropit**, or
*Send clipboard* from quick settings or a long press on the icon. Open the app to write, attach files and see
what this phone sent. Join by scanning the QR code another device shows, or by typing its pairing code; add
and remove devices. When another device sends something, the phone shows a notification (system push; it needs Google Play services on the phone) with Copy, Open and Share on it. Files that arrive wait for a tap: **Save** puts one in Downloads/dropit, then Open, Share (and Copy image for a picture) work without the network. Each file says until when it is still available: after that it shows as expired and can't be saved; a file already saved stays on the phone. Saving needs Android 10 or newer. The build from the website checks for updates by itself.

![Share photos, a screenshot, a video link, a clip and a voice note to dropit from the gallery or any app; send selected text or the clipboard; write a line with files for one device only; then see a shared PDF in the browser extension, pick up an item in the web inbox, and send a note, a picture or a passage from Obsidian to the phone. Phone screens are drawn; what they send and what arrives is real.](../media/android-tour.webp)

<sub>The phone screens are drawn; what they send and what arrives is real. Shown: a NASA photograph (NASA/Anil Menon). Other pages and files are made for the demo.</sub>

When many accounts were created from one network in a day, creating one more may take a few seconds of computing on the phone first: it shows "Getting the account ready…", then goes on by itself.

It looks like the rest of dropit: an airmail envelope you send from, a red button that sends (with a small devices icon beside it, to
choose who gets it), a RETURNED mark on what did not go. English by default, Chinese when the phone is set to Chinese; light and dark
follow the system.

## Layout

| Directory | What it is |
|---|---|
| `shared/` | Kotlin Multiplatform: the protocol and the rules every client agrees on (what a payload looks like, batches, error wording). Built for the JVM and for iOS, so an iPhone app can reuse it later |
| `androidApp/` | The Android app: the ways in, the background upload, the screens |
| `androidApp/src/main/res/font/` | The two label faces: Barlow Condensed 600 for Latin, and a subset of Smiley Sans (renamed "dropit Label ZH") holding only the Chinese characters the labels use. Both under the SIL Open Font License: `licenses/` |
| `tools/label_font.py` | Rebuilds the Chinese subset after a Chinese label changes; a test fails when a character is missing |
| `../test/vectors/` | Cases worked out by the browser extension; the shared tests check this code gives the same answers |

## Speed, and receiving with the app closed

- **A connection ahead of time.** When the share sheet, the clipboard entry or the app's screen comes up, the app asks once for a tiny static file on the service's own address (no key, nothing sent, at most once every 5 minutes), so the connection is already open when you send. If that fails nothing is shown and nothing is retried.
- **Three at a time.** A drop of a text and several files goes out three requests at a time, in order. After the first failure nothing new starts; the ones on their way finish. Only what did not go is sent again by Retry, under the same batch.
- **One request per file.** Every file, whatever its size, is one request; there is no separate preparation step.
- **Smaller photos (a setting, on by default).** A JPEG over 2 MB is re-encoded at quality 85, with its own details (date, orientation, location) copied across unchanged, and the smaller copy is used only when it is at least 10% smaller. Screenshots, PNG, HEIC, video and PDF are never touched; a photo it cannot read or that takes too long is sent as it is. So is any photo that is more than one picture (a motion photo's video, a second picture, an HDR gain map, anything after the end of the picture), or whose details cannot be found to copy across: re-encoding would lose them. Photos are made smaller one at a time. `Store.imageMode` / `shrinkImages` is the switch.
- **Receiving with the app closed.** System push where it gets through; where it does not (no Google services, or they cannot be reached), the app keeps one connection open from a foreground service with the quietest notice the system allows, and pulls what it missed after each reconnect. One switch, "Real-time delivery". It waits for a network instead of asking without one, uses at most 60 connection attempts a day on a phone, and backs off from 1 second to 5 minutes. On many phones the battery setting must be "unrestricted" or the screen lock cuts the connection. When it is not available for the moment, the settings row says so, and it comes back by itself later.
- **Tiles.** "Send clipboard" and "Get latest" (puts the newest text or link from another device on the clipboard; files open the app). Whether a tile is really in the panel is what the system answered when asked to add it; the app's own record is only a hint. Anything that sends the clipboard in one step (the tile, the floating button, the shortcut) leaves it alone when the app that put it there marked it as sensitive (Android 13+): it is not sent, not shown in the offer at the top, and you are told so; paste it into the text box to send it yourself.
- **What other apps can hand over.** Only files served by another app's content provider are copied in; `file://` addresses and the app's own provider are refused, and a copy that stops moving for 20 seconds is given up on. A file over the single-item size limit, and files past the tenth, are listed in the result but never copied. A link is opened only if it is an http(s) address, whatever the sender called it; otherwise it is copied.
- **Download from a notification.** A notice about one file has a Download button that saves it without opening the app.
- **A floating button (experiment, off by default, `site` build only).** One tap sends the clipboard. The `play` build has neither it nor its permission. It is not finished until tried on real phones.

## Who gets it, the two lists, and settings

- **Choosing who gets it.** The red **Send** button has two parts: the left sends to this phone's *default* in one press; the right (a devices icon and an arrow), or a long press on the left, opens a panel: the default devices are ticked, a tick can be changed for this one send, at least one stays ticked, and nothing goes until the big button at the bottom is pressed. Everyone ticked means "everyone" and the request carries no `to`, so a device that joins later is included. With no other device that can receive there is only the left part.
- **The default.** The ticks on the *Devices* screen (one tick saves; shown when two or more other devices can receive; the bottom line of the send panel opens it). It is kept on this phone only. The same screen has *Remove* on every other device and *Sign out* on this phone. Each time the list of devices is read, devices that were removed drop out of the default (you are told once); if none of it is left it is everyone again. If the service says the chosen devices are gone (`TARGET_UNAVAILABLE`), the default goes back to everyone, you are told, and what was being sent stays held with a one-tap "Send to all this time" (on the notification, the card in the Sent list, the pill and the box on the home screen).
- **Sent and Received.** The *Sent* list says where each thing went (`→ All`, `→ Mac`, `→ 3 devices`; never a list of names), when, and how many devices have it (`2/3 ●●○`, from each device's own place in the line, read with the devices list). A batch that stopped part-way is a card with a pale-red edge saying which photo or file did not go and why; its button retries only what is left. Tapping where it went (or the row's button, for files) opens *Send again*: nothing is ticked, each device says whether it has it, is waiting for it, or was not sent it, and the button waits for a choice. Sending again is a new send: it uses one of the day's, and it is marked *Again* at the top of the list. It is not possible once the service no longer keeps the item.
- **Pull again.** *Settings → Receive → Pull again*: everything the service still keeps, the newest 50, or the last 3 days (cut on the phone). It moves the phone's place in the line back and reads again; what comes back goes into the list without a notification. At most once a minute.
- **Settings** is one page in five groups: the account first, Receive, Send, Devices and About. The floating button row exists only in the `site` build.
- **While sending from another app** a small pill floats 34 dp above the bottom of the screen (48 dp, one line, no dim over the screen): a turning ring, what is going and how far, and Cancel; then a check and "Sent" for two seconds; or a pale-red edge, the reason, and the whole pill is Retry. About 78% see-through, with a blur of what is behind it on Android 12+ when the system blur is on, otherwise about 94% solid. Not tried on a phone yet: whether the app behind it can be touched around the pill depends on how the window is made.

## Build and test

Needs JDK 17 and the Android SDK (platform 36).

```bash
./gradlew :androidApp:assembleSiteDebug          # a debug build
./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test :androidApp:testSiteDebugUnitTest
```

The iOS tests need Xcode. With `DROPIT_DEV_URL` set to a local dropit service, the JVM tests also run one
end-to-end check against it. A debug build talks to `127.0.0.1:8799` on the phone, so forward it with
`adb reverse tcp:8799 tcp:8799`.

Three build types: `debug`, `release`, and `qa` — shrunk exactly like a release but talking to the local
service like a debug build, for checking that nothing breaks once the code is shrunk
(`./gradlew :androidApp:assembleSiteQa`).

The camera is used only to scan a pairing code, when the scan button is tapped: frames are read on the phone
by an open-source reader (ZXing) and never leave it. Only a 6-character code or dropit's own pairing link does
anything; any other QR code is ignored.

Two flavors: `site` (from the website, updates itself) and `play` (Google Play). Release signing reads a
`keystore.properties` kept outside this repository: the path in `DROPIT_SIGNING`, otherwise
`../../signing/keystore.properties`. Its `storeFile` is relative to that file. Without it, release builds are unsigned.

## The other devices

dropit has a client for each of your devices, and they all join the same account with a pairing code. Which ones send, which receive, and how to
combine them: [all the clients](../README.md#on-every-device-you-own). Or open [dropit.smart-kits.xyz](https://dropit.smart-kits.xyz) on any device: it recommends one.
