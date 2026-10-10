package io.github.staindk.nacashu

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.IOException

/**
 * Finds the stock camera's shutter button and clicks it, the same way TalkBack (Android's
 * screen reader) would. The camera app still takes the photo, so all of its processing applies.
 */
class ShutterAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    fun openCamera(): Int {
        val keyguard = getSystemService(KeyguardManager::class.java)
        val action = if (keyguard?.isKeyguardLocked == true) {
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE
        } else {
            MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
        }
        val intent = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pkg = Settings.cameraPackage(this)
        if (Intent(intent).setPackage(pkg).resolveActivity(packageManager) != null) {
            intent.setPackage(pkg)
        }
        return try {
            // Allowed from the background: a running accessibility service is exempt from
            // background activity launch restrictions.
            startActivity(intent)
            ShutterProtocol.STATUS_OK
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not open camera", e)
            ShutterProtocol.STATUS_CAMERA_NOT_OPEN
        }
    }

    fun pressShutter(onStatus: (Int) -> Unit) {
        val root = cameraRoot() ?: return onStatus(ShutterProtocol.STATUS_CAMERA_NOT_OPEN)

        val shutter = findShutter(root)
        if (shutter != null) {
            val target = clickableAncestor(shutter)
            val bounds = Rect().also { target.getBoundsInScreen(it) }
            if (target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Settings.saveTapPoint(this, bounds.exactCenterX(), bounds.exactCenterY())
                onStatus(ShutterProtocol.STATUS_OK)
            } else {
                // Some camera builds ignore ACTION_CLICK on the shutter; tap it instead.
                tap(bounds.exactCenterX(), bounds.exactCenterY(), onStatus)
            }
            return
        }

        // No node found: tap where the shutter was last time, or bottom centre of the camera.
        val window = Rect().also { root.getBoundsInScreen(it) }
        val (x, y) = Settings.tapPoint(this)
            ?: (window.exactCenterX() to window.top + window.height() * 0.86f)
        tap(x, y, onStatus)
    }

    /** Writes the camera's view tree to a file so we can learn its real IDs and labels. */
    fun dumpCameraUi(): String {
        val root = cameraRoot() ?: rootInActiveWindow
        val text = if (root == null) {
            "No window content available\n"
        } else {
            StringBuilder().also { dump(root, 0, it) }.toString()
        }
        try {
            lastDumpFile(this).writeText(text)
        } catch (e: IOException) {
            Log.e(TAG, "Could not write dump", e)
        }
        Log.i(TAG, text)
        return text
    }

    private fun cameraRoot(): AccessibilityNodeInfo? {
        val pkg = Settings.cameraPackage(this)
        windows.mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == pkg }?.let { return it }
        return rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }
    }

    private fun findShutter(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (id in SHUTTER_VIEW_IDS) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }
        return findByDescription(root)
    }

    private fun findByDescription(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase()
        if (desc != null && node.isVisibleToUser && SHUTTER_DESCRIPTIONS.any { it in desc }) {
            return node
        }
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findByDescription(it) }
            if (found != null) return found
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null) {
            if (current.isClickable) return current
            current = current.parent
        }
        return node
    }

    private fun tap(x: Float, y: Float, onStatus: (Int) -> Unit) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 60))
            .build()
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                onStatus(ShutterProtocol.STATUS_GESTURE_FALLBACK)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                onStatus(ShutterProtocol.STATUS_SHUTTER_NOT_FOUND)
            }
        }, null)
        if (!dispatched) onStatus(ShutterProtocol.STATUS_SHUTTER_NOT_FOUND)
    }

    private fun dump(node: AccessibilityNodeInfo, depth: Int, out: StringBuilder) {
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        out.append("  ".repeat(depth))
            .append(node.className?.toString()?.substringAfterLast('.'))
            .append(" id=").append(node.viewIdResourceName)
            .append(" desc=").append(node.contentDescription)
            .append(" text=").append(node.text)
            .append(if (node.isClickable) " CLICKABLE" else "")
            .append(' ').append(bounds.toShortString())
            .append('\n')
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { dump(it, depth + 1, out) }
        }
    }

    companion object {
        private const val TAG = "ShutterA11y"

        // Pixel Camera's shutter; confirm others with "Dump camera screen" on a real device.
        private val SHUTTER_VIEW_IDS = listOf(
            "com.google.android.GoogleCamera:id/shutter_button",
            "com.google.android.GoogleCamera:id/shutter",
        )
        private val SHUTTER_DESCRIPTIONS = listOf("take photo", "shutter", "take picture", "capture")

        @Volatile
        var instance: ShutterAccessibilityService? = null
            private set

        fun lastDumpFile(context: Context) = File(context.filesDir, "camera-ui-dump.txt")
    }
}
