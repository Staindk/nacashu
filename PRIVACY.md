# Nacashu privacy policy

Nacashu (short for Native Camera Shutter) collects no personal data, and shares nothing.

## The companion app (Android)

- **No internet access.** The app doesn't ask for Android's INTERNET permission, so it can't send anything off your phone.
- **Accessibility service.** Nacashu uses Android's accessibility service to press the shutter button in your camera app when you press a button on your Pebble watch. It reads the buttons and labels on your chosen camera app's screen, only to find the shutter. During the "Set shutter button" step, it also notes where you tap. The app shows this explanation and asks you to agree before it opens Android's accessibility settings.
- **What the app stores (locally):** your settings, and the shutter button's name and position. If you use **Debug: dump camera screen**, a text copy of your camera app's buttons and labels is saved in the app's private storage. It leaves your phone only if you tap **Debug: share last dump** and choose where to send it.
- Uninstalling the app deletes all of the above.

## The watchapp (Pebble)

The watchapp sends your button presses to the companion app through the Pebble app, and keeps your timer and lefty settings on the watch. It has no internet access of its own.

## Questions

Please [open an issue on GitHub](https://github.com/Staindk/nacashu/issues). The source code for both apps is in this repository.
