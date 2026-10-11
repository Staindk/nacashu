package io.github.staindk.nacashu

import android.content.Context
import android.content.SharedPreferences

object Settings {
    const val PIXEL_CAMERA = "com.google.android.GoogleCamera"

    /** The button under the user's tap in "Learn my shutter". Both are null if it had neither. */
    data class LearnedShutter(val viewId: String?, val label: String?)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun cameraPackage(context: Context): String =
        prefs(context).getString("camera_package", PIXEL_CAMERA) ?: PIXEL_CAMERA

    fun autoOpenCamera(context: Context): Boolean =
        prefs(context).getBoolean("auto_open_camera", true)

    fun setAutoOpenCamera(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_open_camera", value).apply()
    }

    /** The learned shutter, or null if "Learn my shutter" hasn't been done for this camera. */
    fun learnedShutter(context: Context): LearnedShutter? {
        val p = prefs(context)
        if (!p.contains("shutter_learned")) return null
        return LearnedShutter(p.getString("shutter_id", null), p.getString("shutter_label", null))
    }

    /** Switches to another camera app and forgets the shutter learned for the old one. */
    fun setCameraPackage(context: Context, cameraPackage: String) {
        prefs(context).edit()
            .putString("camera_package", cameraPackage)
            .remove("shutter_learned").remove("shutter_id").remove("shutter_label")
            .remove("tap_x").remove("tap_y")
            .apply()
    }

    /** Saves what the user tapped in "Learn my shutter" for the current camera app. */
    fun saveLearnedShutter(context: Context, shutter: LearnedShutter, tap: Pair<Float, Float>) {
        prefs(context).edit()
            .putBoolean("shutter_learned", true)
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
