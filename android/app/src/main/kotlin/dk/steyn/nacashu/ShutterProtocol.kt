package dk.steyn.nacashu

import android.content.Context
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import java.util.UUID

/** AppMessage keys and values. Must match watch/src/c/main.c. */
object ShutterProtocol {
    val WATCHAPP_UUID: UUID = UUID.fromString("24258afb-b38c-4325-a2ca-aad66022a81a")

    const val KEY_CMD = 1u
    const val KEY_STATUS = 2u
    /** Phone to watch: 1 puts the button labels on the left (lefty mode), 0 on the right. */
    const val KEY_LEFTY = 3u
    /** Phone to watch: 1 plays sounds on the watch's speaker, 0 keeps it quiet. */
    const val KEY_SOUNDS = 4u

    const val CMD_SHUTTER = 1
    const val CMD_OPEN_CAMERA = 2
    /** The watchapp was opened with Quick Launch. Takes a photo if the user turned that on. */
    const val CMD_QUICK_LAUNCH = 3

    const val STATUS_OK = 0
    const val STATUS_NO_ACCESSIBILITY = 1
    const val STATUS_CAMERA_NOT_OPEN = 2
    const val STATUS_SHUTTER_NOT_FOUND = 3
    const val STATUS_GESTURE_FALLBACK = 4

    fun statusName(status: Int): String = when (status) {
        STATUS_OK -> "OK"
        STATUS_NO_ACCESSIBILITY -> "Accessibility service is off"
        STATUS_CAMERA_NOT_OPEN -> "Camera is not on screen"
        STATUS_SHUTTER_NOT_FOUND -> "Shutter button not found"
        STATUS_GESTURE_FALLBACK -> "OK (tapped by position)"
        else -> "Unknown ($status)"
    }

    /** The settings the phone sends to the watch. */
    fun settingsMessage(context: Context): PebbleDictionary =
        mapOf(
            KEY_LEFTY to PebbleDictionaryItem.UInt8(if (Settings.lefty(context)) 1u else 0u),
            KEY_SOUNDS to PebbleDictionaryItem.UInt8(if (Settings.watchSounds(context)) 1u else 0u),
        )

    /** Runs a watch command and reports its status. Must be called on the main thread. */
    fun run(cmd: Int, onStatus: (Int) -> Unit) {
        val service = ShutterAccessibilityService.instance
        when {
            service == null -> onStatus(STATUS_NO_ACCESSIBILITY)
            cmd == CMD_SHUTTER -> service.pressShutter(onStatus)
            cmd == CMD_OPEN_CAMERA -> onStatus(service.openCamera())
            cmd == CMD_QUICK_LAUNCH -> service.openCameraAndPressShutter(onStatus)
            else -> onStatus(STATUS_SHUTTER_NOT_FOUND)
        }
    }
}
