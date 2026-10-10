package io.github.staindk.nacashu;

import android.content.Context;
import android.content.SharedPreferences;

final class Settings {
    static final String PIXEL_CAMERA = "com.google.android.GoogleCamera";

    private Settings() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static String cameraPackage(Context context) {
        return prefs(context).getString("camera_package", PIXEL_CAMERA);
    }

    static boolean autoOpenCamera(Context context) {
        return prefs(context).getBoolean("auto_open_camera", true);
    }

    static void setAutoOpenCamera(Context context, boolean value) {
        prefs(context).edit().putBoolean("auto_open_camera", value).apply();
    }

    /** Centre of the shutter the last time it was found, used when it can't be found again. */
    static float[] tapPoint(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains("tap_x")) return null;
        return new float[]{p.getFloat("tap_x", 0f), p.getFloat("tap_y", 0f)};
    }

    static void saveTapPoint(Context context, float x, float y) {
        prefs(context).edit().putFloat("tap_x", x).putFloat("tap_y", y).apply();
    }
}
