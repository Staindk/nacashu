package io.github.staindk.nacashu

import android.content.Context
import android.content.SharedPreferences

object Settings {
    const val PIXEL_CAMERA = "com.google.android.GoogleCamera"

    /** The button under the user's tap in "Set shutter button". Both are null if it had neither. */
    data class SavedShutter(val viewId: String?, val label: String?)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun cameraPackage(context: Context): String =
        prefs(context).getString("camera_package", PIXEL_CAMERA) ?: PIXEL_CAMERA

    fun autoOpenCamera(context: Context): Boolean =
        prefs(context).getBoolean("auto_open_camera", true)

    fun setAutoOpenCamera(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_open_camera", value).apply()
    }

    /** Take a photo straight away when the watchapp is opened with Quick Launch. */
    fun snapOnQuickLaunch(context: Context): Boolean =
        prefs(context).getBoolean("snap_on_quick_launch", false)

    fun setSnapOnQuickLaunch(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("snap_on_quick_launch", value).apply()
    }

    /** Button labels on the left of the watch screen, for PebbleOS's Left-Handed mode. */
    fun lefty(context: Context): Boolean =
        prefs(context).getBoolean("lefty", false)

    fun setLefty(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("lefty", value).apply()
    }

    /** Countdown, shutter and error sounds on the watch's speaker. */
    fun watchSounds(context: Context): Boolean =
        prefs(context).getBoolean("watch_sounds", true)

    fun setWatchSounds(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("watch_sounds", value).apply()
    }

    /** Turn the accessibility service off when the watchapp closes, for apps that refuse to run while it's on. */
    fun accessibilityAutoOff(context: Context): Boolean =
        prefs(context).getBoolean("accessibility_auto_off", false)

    fun setAccessibilityAutoOff(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("accessibility_auto_off", value).apply()
    }

    /** Show the help explainers and the About topics. */
    fun helpOn(context: Context): Boolean =
        prefs(context).getBoolean("help_on", false)

    fun setHelpOn(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("help_on", value).apply()
    }

    /** The user tapped "Agree" on the accessibility disclosure, so step 1 goes straight to Android's settings. */
    fun disclosureAccepted(context: Context): Boolean =
        prefs(context).getBoolean("disclosure_accepted", false)

    fun setDisclosureAccepted(context: Context) {
        prefs(context).edit().putBoolean("disclosure_accepted", true).apply()
    }

    /** The saved shutter, or null if "Set shutter button" hasn't been done for this camera. */
    fun savedShutter(context: Context): SavedShutter? {
        val p = prefs(context)
        if (!p.contains("shutter_saved")) return null
        return SavedShutter(p.getString("shutter_id", null), p.getString("shutter_label", null))
    }

    /** Switches to another camera app and forgets the shutter saved for the old one. */
    fun setCameraPackage(context: Context, cameraPackage: String) {
        prefs(context).edit()
            .putString("camera_package", cameraPackage)
            .remove("shutter_saved").remove("shutter_id").remove("shutter_label")
            .remove("tap_x").remove("tap_y")
            .apply()
    }

    /** Saves what the user tapped in "Set shutter button" for the current camera app. */
    fun saveShutter(context: Context, shutter: SavedShutter, tap: Pair<Float, Float>) {
        prefs(context).edit()
            .putBoolean("shutter_saved", true)
            .putString("shutter_id", shutter.viewId)
            .putString("shutter_label", shutter.label)
            .putFloat("tap_x", tap.first)
            .putFloat("tap_y", tap.second)
            .apply()
    }

    /** Centre of the shutter the last time it was found, used when it can't be found again. */
    fun tapPoint(context: Context): Pair<Float, Float>? {
        val p = prefs(context)
        if (!p.contains("tap_x")) return null
        return p.getFloat("tap_x", 0f) to p.getFloat("tap_y", 0f)
    }

    fun saveTapPoint(context: Context, x: Float, y: Float) {
        prefs(context).edit().putFloat("tap_x", x).putFloat("tap_y", y).apply()
    }
}
