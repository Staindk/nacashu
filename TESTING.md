# Testing on a phone

You need the `.pbw` and `.apk` from a CI run (or your own build), an Android phone with Pixel Camera, and a Pebble watch paired in the Core **Pebble** app.

| File | What it is |
|------|------------|
| `nacashu.pbw` | The watchapp. It's built for Time 2 (emery), Core 2 Duo (flint) and Round 2 (gabbro), for current watch firmware. |
| `nacashu.apk` | The companion app, debug-signed. |

## 1. Install

1. Copy both files to the phone. Google Drive, USB, or messaging them to yourself all work.
2. Open the `.apk` and allow installing from that source.
3. Open the `.pbw`. It should offer to open in the Pebble app. If it doesn't, use the Pebble app's sideload option. Then install it on the watch.

## 2. Turn on the accessibility service

1. Go to **Settings › Apps › Nacashu**, open the ⋮ menu, and choose **Allow restricted settings**. Android 13 and newer block accessibility for sideloaded apps until you do this.
2. Open **Nacashu**, tap **1. Turn on accessibility service**, and turn on "Nacashu".
3. Back in Nacashu, check the top lines. They should say `Accessibility service: ON` and `Pebble app: coredevices.coreapp` (or similar).
4. Tap **2. Choose camera app** and pick the camera you want Nacashu to use.
5. Tap **3. Set shutter button**. A dark see-through layer with "tap your camera's shutter button once" appears, and your camera opens under it. Tap where the shutter is. This doesn't take a photo. The layer says which button it set, and after 3 seconds you're back in Nacashu, whose `Shutter:` line shows it. With Pixel Camera this step is optional, because it works out of the box.

## 3. Test the phone half without the watch

1. Tap **Test: open camera, then shutter after 4s**. Your camera should open and take a photo about 4 seconds later.
   - A toast with **OK** means the button was found by its label or ID.
   - **OK (tapped by position)** means it fell back to tapping a screen position. That still works, but please share a dump (step 5).
   - **Camera is not on screen** or **Shutter button not found** means please share a dump.

## 4. Test with the watch

1. Open **Nacashu** on the watch. The camera should open on the phone.
2. The first time, the Pebble app may ask you to allow Nacashu (PebbleKit). Allow it.
3. Press **SELECT** to take a photo. The watch shows "Done" and vibrates once.
4. Press **UP** to start a 3-second countdown, then a photo. Press UP again to cancel.
5. Hold **UP** to change the delay (1, 3, 5, 10, 30 s). The highlight moves along the row and the choice is kept next time. After updating from an older version the delay starts at 3 s again.
6. Press **DOWN** to bring the camera back to the front.
7. Lefty mode: turn on **Settings > Display > Orientation > Left-Handed** on the watch, tick **Lefty** in the phone app, then open Nacashu on the watch. The labels move to the left, next to the buttons. Toggling Lefty while Nacashu is open on the watch should move them straight away.

Try the same with the phone locked. The camera opens in secure mode, and the shutter should still work.

## 5. If something fails, share a dump

1. With the accessibility service on, tap **Debug: dump camera screen after 5s** and switch to your camera app.
2. Come back and tap **Debug: share last dump**. Paste the text into a GitHub issue.

If the watch shows "Phone app?" or nothing happens, run
`adb logcat -s ShutterListener ShutterA11y BasePebbleListenerService` while you press SELECT, and include the output in the issue.

## What the watch shows

| Watch text | Meaning |
|------------|---------|
| Done | The shutter was clicked. |
| Done (tap) | The shutter was tapped by screen position. |
| Enable service | The accessibility service is off. |
| Camera closed | The camera app isn't on screen. Press UP. |
| No shutter | The shutter wasn't found and the tap failed. |
| Phone app? / No phone | The message didn't reach the phone. |
