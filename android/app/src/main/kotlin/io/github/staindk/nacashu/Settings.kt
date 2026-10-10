package io.github.staindk.nacashu

import android.content.Context
import android.content.SharedPreferences

object Settings {
    const val PIXEL_CAMERA = "com.google.android.GoogleCamera"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun cameraPackage(context: Context): String =
        prefs(context).getString("camera_package", PIXEL_CAMERA) ?: PIXEL_CAMERA

    fun autoOpenCamera(context: Context): Boolean =
        prefs(context).getBoolean("auto_open_camera", true)

    fun setAutoOpenCamera(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean("auto_open_camera", value).apply()
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
