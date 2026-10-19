# dropit for Android

**English** · [简体中文](./README.zh-CN.md)

> Version 0.2.0 is the first release: download the APK from the Releases page or from the dropit website. Not on Google Play yet.

Send without opening the app: *Share* → **dropit**, select text → **dropit**, *Open with* → **dropit**, or
*Send clipboard* from quick settings or a long press on the icon. Open the app to write, attach files and see
what this phone sent. Join by scanning the QR code another device shows, or by typing its pairing code; add
and remove devices; see what each plan gives. When another device sends something, the phone shows a notification (system push; it needs Google Play services on the phone) with Copy, Open and Share on it. Files that arrive wait for a tap: **Save** puts one in Downloads/dropit, then Open, Share (and Copy image for a picture) work without the network. Each file says until when the service still has it (1 day on the free plan, 10 on the paid one): after that it shows as expired and can't be saved, because dropit delivers, it doesn't store; a file already saved stays on the phone. Each save counts against the account's downloads for the day. Saving needs Android 10 or newer. The build from the website checks for updates by itself.

When many accounts were created from one network in a day, creating one more may take a few seconds of computing on the phone first: it shows "Getting the account ready…", then goes on by itself.

It looks like the rest of dropit: an airmail envelope you send from, a red stamp that sends, a RETURNED mark
on what did not go. English by default, Chinese when the phone is set to Chinese; light and dark follow the system.

## Layout

| Directory | What it is |
|---|---|
| `shared/` | Kotlin Multiplatform: the protocol and the rules every client agrees on (what a payload looks like, batches, error wording). Built for the JVM and for iOS, so an iPhone app can reuse it later |
| `androidApp/` | The Android app: the ways in, the background upload, the screens |
| `androidApp/src/main/res/font/` | The two label faces: Barlow Condensed 600 for Latin, and a subset of Smiley Sans (renamed "dropit Label ZH") holding only the Chinese characters the labels use. Both under the SIL Open Font License: `licenses/` |
| `tools/label_font.py` | Rebuilds the Chinese subset after a Chinese label changes; a test fails when a character is missing |
| `../test/vectors/` | Cases worked out by the browser extension; the shared tests check this code gives the same answers |

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
