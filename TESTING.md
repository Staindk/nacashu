# Testing on a phone

You need the `.pbw` and `.apk` from a CI run (or your own build), an Android phone (Android 10 or newer) and a Pebble watch paired in the Core **Pebble** app. Pixel Camera works out of the box; other camera apps need step 2.5.

| File | What it is |
|------|------------|
| `nacashu.pbw` | The watchapp. It's built for Time 2 (emery), Core 2 Duo (flint) and Round 2 (gabbro), for current watch firmware. |
| `nacashu.apk` | The companion app. CI runs on this repo sign it with the project's permanent key, so a new APK installs over the last one. Forks and local builds use the standard debug key. |

## 1. Install

1. Copy both files to the phone. Google Drive, USB, or messaging them to yourself all work.
2. Open the `.apk` and allow installing from that source.
3. Open the `.pbw`. It should offer to open in the Pebble app. If it doesn't, use the Pebble app's sideload option. Then install it on the watch.

## 2. Set up the companion app

1. Go to **Settings › Apps › Nacashu**, open the ⋮ menu, and choose **Allow restricted settings**. Android 13 and newer block accessibility for sideloaded apps until you do this.
2. Open **Nacashu** and tap **1. Turn on accessibility service**. A "Nacashu needs accessibility access" dialog explains what the access is for. Tap **Not now**: nothing else should happen. Tap step 1 again, then **Agree**: Android's accessibility settings open. Turn on "Nacashu". From now on step 1 goes straight to those settings.
3. Back in Nacashu, check the status card. It should show `Accessibility service` ON and `Pebble app` Official (the Core Devices app; any other Pebble app shows its package name). `Next step` changes as you go: turn on accessibility, set the shutter button, then open Nacashu on the watch (it also says to open your camera when "Open camera when the watchapp starts" is off).
4. Tap **2. Choose camera app** and pick the camera you want Nacashu to use. The `Camera app` line shows your choice.
5. Tap **3. Set shutter button**. A dark see-through layer with "tap your camera's shutter button once" appears, and your camera opens under it. Tap where the shutter is. This doesn't take a photo. The layer says which button it set, and after 3 seconds you're back in Nacashu, whose `Shutter` line shows it. With Pixel Camera this step is optional: the line says `built in for Pixel Camera`.
6. Turn on **Help** at the top right. A grey explainer should appear under each step and setting, and "About Nacashu" topics under the header. Tap a topic to open it; its links should open in the browser. Close and reopen Nacashu: Help stays on. Turn it off again.
7. Tap the layer before the camera has opened: it should ask you to wait for the camera instead of saving anything. Leave the layer alone for a minute: it should go away on its own and save nothing.

## 3. Test the phone half without the watch

1. Tap **Test / Debug ▸**, then **Test: open camera, then shutter after 5s**. Your camera should open and take a photo about 5 seconds later.
   - A toast with **OK** means the button was found by its label or ID.
   - **OK (tapped by position)** means it fell back to tapping a screen position. That still works, but please share a dump (section 6).
   - **Camera is not on screen** or **Shutter button not found** means please share a dump.
2. **Test: open camera** should bring the camera to the front without resetting its mode or zoom.

## 4. Test with the watch

1. Open **Nacashu** on the watch. The camera should open on the phone (turn off **Open camera when the watchapp starts** to stop that). The watch shows the time beside the shutter.
2. The first time, the Pebble app may ask you to allow Nacashu (PebbleKit). Allow it.
3. Press **SELECT** to take a photo. The watch shows "Snap...", then "Done", and vibrates once.
4. Press **UP** to change the timer (0, 3, 5, 10, 30 s). The highlight moves along the top row. Pick 3 and press SELECT: a countdown, then a photo. Press SELECT (or UP or DOWN) during the countdown to stop it ("Stopped"); no photo is taken and the timer stays at 3.
5. Press **DOWN** to change the photo count (1, 5, 20, ∞). Pick 5 and press SELECT: the watch shows 1/5 to 5/5 a second apart, vibrates and clicks for each, then shows "Done". Check the camera roll has 5 photos.
6. Pick ∞ and press SELECT: it counts up until you press any button ("Stopped"). With a timer set, the countdown runs once before the first photo.
7. Close the camera on the phone during a run of 20: the watch should show "Camera closed" and stop.
8. Hold **UP**: the watch shows "Reset", and the rows go back to 0 and 1.
9. Hold **DOWN** to bring the camera back to the front.
10. Set the timer to 5 and photos to 20, close Nacashu on the watch and open it again: both are kept. Turn off **Remember watchapp settings** in the phone app, set them again, then close and reopen: they're back at 0 and 1.
11. Lefty mode: turn on **Settings > Display > Orientation > Left-Handed** on the watch, turn on **Lefty** in the phone app, then open Nacashu on the watch. The labels move to the left, next to the buttons. Toggling Lefty while Nacashu is open on the watch should move them straight away.
12. Quick Launch: on the watch, set Nacashu as a Quick Launch app in **Settings > Quick Launch**. Turn on **Take photo when quick launching Nacashu on Pebble** in the phone app, go back to the watchface and hold that button. The camera should open and take a photo, and the watch shows "Done". With it off, the same hold just opens Nacashu.
13. Lock the phone and repeat steps 1 and 3. The camera opens in secure mode, and the shutter should still work.
14. Turn Bluetooth off on the phone and press SELECT. The watch should show "No phone" and vibrate twice.

## 5. Accessibility auto-off (optional)

1. In **Test / Debug**, turn on **Turn accessibility off when I close Nacashu on my watch**.
2. Set up the shortcut once: **Settings › Accessibility › Nacashu › Nacashu shortcut**, holding both volume keys.
3. Open and close Nacashu on the watch. The status card should switch to `Accessibility service` OFF.
4. Hold both volume keys for 3 seconds. It should switch back to `ON`.

## 6. If something fails, share a dump

1. With the accessibility service on, open **Test / Debug**, tap **Debug: dump camera screen after 5s** and switch to your camera app.
2. Come back and tap **Debug: share last dump**. Paste the text into a GitHub issue.

If the watch shows "Phone app?" or nothing happens, run
`adb logcat -s ShutterListener ShutterA11y BasePebbleListenerService` while you press SELECT, and include the output in the issue.

## What the watch shows

| Watch text | Meaning |
|------------|---------|
| Snap... / Opening... | The command was sent to the phone. |
| 2/5, or just 7 for ∞ | The photo number in a run of photos. |
| Done | The shutter was clicked (for a run, the last one). |
| Stopped | A countdown or run of photos was stopped by a button press. |
| Reset | Hold UP: no timer and one photo again. |
| Done (tap) | The shutter was tapped by screen position. |
| Enable service | The accessibility service is off. |
| Camera closed | The camera app isn't on screen. Hold DOWN. |
| No shutter | The shutter wasn't found and the tap failed. |
| No phone | The watch isn't connected to the phone. |
| Phone app? | The message didn't reach the companion app. |
| Busy | The last message is still being sent. Try again. |
| Error | The phone sent a status the watch doesn't know. Update both apps. |
