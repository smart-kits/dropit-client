# dropit for Android

**English** · [简体中文](./README.zh-CN.md)

> In development, not released yet. Nothing here is installable from a store or the website.

Send without opening the app: *Share* → **dropit**, select text → **dropit**, *Open with* → **dropit**, or
*Send clipboard* from quick settings or a long press on the icon. Open the app to write, attach files and see
what arrived.

## Layout

| Directory | What it is |
|---|---|
| `shared/` | Kotlin Multiplatform: the protocol and the rules every client agrees on (what a payload looks like, batches, error wording). Built for the JVM and for iOS, so an iPhone app can reuse it later |
| `androidApp/` | The Android app: the ways in, the background upload, the screens |
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

Two flavors: `site` (from the website, updates itself) and `play` (Google Play). Release signing reads
`keystore.properties`, which never enters this repository.
