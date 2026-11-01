package io.github.staindk.nacashu

/**
 * The app's help copy, kept in one place so it's easy to read and review. The disclosure is
 * Google Play's "prominent disclosure" for accessibility: it must be shown, and agreed to, before
 * Nacashu sends anyone to Android's accessibility settings.
 */
object Help {
    private const val REPO = "https://github.com/Staindk/nacashu"

    const val DISCLOSURE_TITLE = "Nacashu needs accessibility access"

    private const val WHY =
        "Android doesn't let one app press buttons in another app, except through accessibility. " +
            "Without accessibility, Nacashu can't reach your camera's shutter, and there's no other way to " +
            "use your Pebble as a shutter for your phone's own camera app."

    /** HTML for the disclosure dialog. */
    const val DISCLOSURE =
        "Nacashu (short for Native Camera Shutter) uses Android's accessibility service to press the " +
            "shutter button in your camera app when you press a button on your Pebble watch, so your " +
            "Pebble watch acts as a remote shutter. Your camera app still takes the photo." +
            "<br><br><b>Why Nacashu needs accessibility access:</b> $WHY" +
            "<br><br><b>What Nacashu reads:</b> the buttons and labels on your camera app's screen, only " +
            "to find the shutter. During the \"Set shutter button\" step, Nacashu also notes where you tap." +
            "<br><br><b>What Nacashu does:</b> opens your camera app, then clicks or taps its shutter button." +
            "<br><br><b>What Nacashu stores:</b> the shutter button's name and position, on this phone " +
            "only. Nacashu has no internet access and collects or shares nothing." +
            "<br><br>You can turn off Nacashu's accessibility access any time in Android's accessibility settings."

    /** The "About Nacashu" topics shown when Help is on: title to HTML body. */
    val TOPICS = listOf(
        "What Nacashu does" to
            "Nacashu turns your Pebble watch into a remote shutter for your phone's camera app. Press " +
            "SELECT on the watch and your camera takes a photo, with all of the photo processing you are " +
            "used to. See \"Using the watchapp\" for what each button does.",
        "Why there are two apps" to
            "Watch apps can't control your phone directly. The Nacashu watchapp sends your button press " +
            "through the Pebble app to this companion app, and this app presses the shutter in your camera app." +
            "<br><br>To save power, the watch normally checks for messages from your phone only about every " +
            "1.4 seconds, which would make the first photo late. So while you use the watchapp, it sends your " +
            "phone a tiny message every 7 seconds to keep Bluetooth quick. This uses a little battery, so it " +
            "stops 5 minutes after your last button press (but never during a timer or a run of photos).",
        "Why Nacashu needs accessibility" to
            "$WHY Read more:" +
            "<br>• <a href=\"https://support.google.com/accessibility/android/answer/6006564\">Android " +
            "accessibility overview</a> (Google)" +
            "<br>• <a href=\"https://developer.android.com/guide/topics/ui/accessibility/service\">How " +
            "accessibility services work</a> (Android Developers)" +
            "<br>• <a href=\"$REPO\">Nacashu's source code</a>, small enough to read in one sitting" +
            "<br>• <a href=\"$REPO/blob/main/PRIVACY.md\">Nacashu's privacy policy</a>",
        "Turning on accessibility" to
            "1. Tap \"1. Turn on accessibility service\".<br>2. Find Nacashu in the list. Some phones put " +
            "it under \"Installed apps\" or \"Downloaded apps\".<br>3. Turn it on and confirm." +
            "<br><br>If Android says the setting is restricted, go to Settings &gt; Apps &gt; Nacashu, open " +
            "the ⋮ menu and tap \"Allow restricted settings\" (<a href=\"https://support.google.com/android/" +
            "answer/12623953\">Google's help page</a>). Menus differ between phone makers; if yours looks " +
            "different, search your phone's settings for \"Accessibility\" or look up your phone model.",
        "Questions" to
            "If you have any other questions, please <a href=\"$REPO/issues\">open an issue on GitHub</a> " +
            "or email the developer from Nacashu's Play Store page.",
    )

    // A button's hold action, on its own indented line under the press.
    private const val HOLD = "<br>&nbsp;&nbsp;&nbsp;&nbsp;<b>↳ Hold</b>"

    /** The "Using the watchapp" topic, shown under WatchDiagram. Its numbers match the diagram's. */
    const val WATCHAPP_TITLE = "Using the watchapp"
    const val WATCHAPP =
        "The numbers match the buttons in the picture." +
            "<br><br><b>1. UP</b> changes the timer: 0, 3, 5, 10 or 30 seconds." +
            "$HOLD resets to 0 timer and 1 photo." +
            "<br><b>2. SELECT</b> takes the photos, after the timer if there is one." +
            "<br><b>3. DOWN</b> changes how many photos SELECT takes, about a second apart: 1, 3, 5, 10 or ∞ " +
            "(until you stop it)." +
            "$HOLD brings the camera to the front on your phone." +
            "<br><b>4. BACK</b> closes Nacashu." +
            "<br><br>UP, SELECT or DOWN stops a timer or a run of photos. The rows at the top and bottom show " +
            "the options, with currently set options highlighted. The middle of the screen shows the " +
            "most recent status/event, otherwise (after 5 seconds of nothing) it shows the current time. " +
            "E.g. a timer counts down, during a multi-photo run it counts the photos, and after a button press " +
            "it shows what happened for a few seconds, such as \"Done\" or what went wrong."

    // Shown under each step and setting when Help is on.
    const val STEP_ACCESSIBILITY = "Lets Nacashu press your camera's shutter. Opens Android's accessibility " +
        "settings, where you turn on Nacashu. Stuck? See \"Turning on accessibility\" above."
    const val STEP_CAMERA = "The camera app Nacashu opens and presses."
    const val STEP_SHUTTER = "Opens your camera under a see-through layer. Tap its shutter once; this stores " +
        "the button identifier and location on the screen instead of taking a photo. If your camera app " +
        "changes during a big update, you may have to set your shutter button again in Nacashu."
    const val AUTO_OPEN = "Opening Nacashu on your watch also opens the camera on your phone."
    const val QUICK_LAUNCH = "Set Nacashu as a Quick Launch app on your watch (Settings > Quick Launch). " +
        "Holding that button then opens the camera and takes a photo straight away."
    const val LEFTY = "For PebbleOS's Left-Handed mode (Settings > Display > Orientation). Moves the button " +
        "labels to the left, next to the buttons. At time of writing, there is no way to automatically " +
        "determine whether your watch OS is in lefty mode."
    const val SOUNDS = "Plays a tick for each of the timer's last 10 seconds, a shutter click when the photo is taken, " +
        "and a buzz when something goes wrong. Uses the watch's own speaker volume, and stays quiet when " +
        "the watch is muted or in Quiet Time. The Pebble Round 2 has no speaker."
    const val REMEMBER = "On the watch, UP changes the timer and DOWN changes how many photos SELECT takes, " +
        "about a second apart. With this on, the watch keeps your choices for next time. With it off, " +
        "Nacashu starts with no timer and one photo each time you open it."
    const val TEST_DEBUG = "Check the phone side without your watch, and share what Nacashu sees if it " +
        "can't find the shutter."
}
