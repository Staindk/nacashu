# Nacashu: native camera shutter for Pebble

Nacashu turns a Pebble watch into a remote shutter for your phone's **own** camera app, starting with Pixel Camera. Your camera app still takes the photo, so you keep all of its processing.

```
Watchapp ──AppMessage──▶ Pebble app ──PebbleKit 2──▶ Nacashu (Android)
  SELECT = snap                                      │ accessibility service
  UP     = open camera                               ▼
  DOWN   = 3 s timer   ◀── status (vibrate) ──  clicks the camera's shutter button
```

Status: early proof of concept. It has been tested in the Pebble Time 2 emulator, but not yet on a phone.

## How it works

- **Watchapp** (`watch/`, C): sends a command when you press a button, and shows and vibrates the result.
- **Pebble app** (Core Devices): forwards the watchapp's messages to the companion app through PebbleKit 2.
- **Companion app** (`android/`, Java): an accessibility service finds the camera app's shutter button and clicks it, the same way TalkBack would. If the camera app is locked or not open, it opens the camera with the standard camera intent.

### Why not simpler approaches?

- **Watch as a Bluetooth selfie stick (HID volume key):** watchapps get no Bluetooth access, and PebbleOS has no HID service. This would need custom firmware.
- **PebbleKit JS on the phone:** the Pebble app's JS sandbox can't control other Android apps.
- **Sending a volume-key press:** Android doesn't let normal apps inject key events into other apps.

## Small and auditable on purpose

The companion app asks for accessibility access, so it's kept small enough to read in one sitting:

- It has no third-party dependencies and no internet permission.
- It only reads the camera app's screen, and only when the watch asks it to press the shutter.
- It speaks the PebbleKit 2 protocol directly, in about 150 lines in [`PebbleKit2.java`](android/app/src/main/java/io/github/staindk/nacashu/PebbleKit2.java). The two AIDL interface files are copied from [PebbleKitAndroid2](https://github.com/pebble-dev/PebbleKitAndroid2) (Apache License 2.0).

## Layout

```
watch/            Watchapp (Pebble SDK 3). Targets emery (Time 2), flint (Core 2 Duo), gabbro (Round 2).
watch/tools/      fake_phone.py: plays the phone side against the emulator.
android/          Companion app (Java, minSdk 29).
android/tools/    build-without-gradle.sh: builds the APK with Ubuntu's Android tools, without Gradle.
docs/             Emulator screenshots.
.github/          CI: builds the .pbw and .apk on every push.
```

## Building

- **Watchapp:** `cd watch && pebble build` (needs [pebble-tool](https://developer.repebble.com/sdk/) and an SDK).
- **Companion:** `cd android && ./gradlew assembleDebug`.
- **CI:** every push produces both files as workflow artifacts.

## Testing

See [TESTING.md](TESTING.md).

## Known gaps

- **Shutter detection.** It's tuned for Pixel Camera, using the shutter's view IDs and its "take photo" label. If neither matches, it taps where the shutter was last found. Other camera apps will need a "learn my shutter" setup step, which is planned.
- **Lock-screen camera.** The secure camera is opened when the phone is locked, but this hasn't been tested on a device yet.

## Trademarks

Pebble is a trademark of its owner. Nacashu is an independent project and isn't affiliated with or endorsed by Core Devices.
