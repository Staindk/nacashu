package io.github.staindk.nacashu

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.io.File
import java.io.IOException

/**
 * Finds the stock camera's shutter button and clicks it, the same way TalkBack (Android's
 * screen reader) would. The camera app still takes the photo, so all of its processing applies.
 */
class ShutterAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var learning = false
    private val learningTimedOut = Runnable {
        setLearning(false)
        toast("No tap detected in a camera app, so nothing was learned. Please try again.")
    }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!learning || event?.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED) return
        val pkg = event.packageName?.toString() ?: return
        Log.d(TAG, "Learning: click in $pkg on ${event.source?.viewIdResourceName}")
        // Ignore taps outside camera apps, such as a permission dialog the camera shows first.
        if (!isCameraApp(pkg)) return
        val node = event.source
        val label = (node?.contentDescription ?: node?.text ?: event.contentDescription)?.toString()
        val shutter = Settings.LearnedShutter(node?.viewIdResourceName, label?.takeIf { it.isNotBlank() })
        val tap = node?.let { Rect().also(it::getBoundsInScreen) }?.let { it.exactCenterX() to it.exactCenterY() }
        Settings.saveLearnedShutter(this, pkg, shutter, tap)
        setLearning(false)
        Log.i(TAG, "Learned shutter in $pkg: $shutter")
        toast("Learned your shutter: ${label ?: shutter.viewId ?: "button"}")
    }

    /**
     * "Learn my shutter": opens the camera and records the next button the user taps there.
     * Click events are only switched on while learning, and it stops after one tap or a minute.
     */
    fun startLearning() {
        setLearning(true)
        openCamera(learning = true)
        handler.postDelayed(learningTimedOut, LEARN_TIMEOUT_MS)
    }

    private fun setLearning(on: Boolean) {
        learning = on
        handler.removeCallbacks(learningTimedOut)
        serviceInfo = serviceInfo.apply {
            eventTypes = if (on) {
                eventTypes or AccessibilityEvent.TYPE_VIEW_CLICKED
            } else {
                eventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED.inv()
            }
        }
    }

    private fun isCameraApp(pkg: String): Boolean =
        Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).setPackage(pkg).resolveActivity(packageManager) != null

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_LONG).show()
    }

    override fun onInterrupt() {}

    /**
     * Brings the camera to the front without resetting it: does nothing if it's already on
     * screen, and resumes it as it was (mode, focus, zoom) when unlocked. While learning, opens
     * the phone's default camera instead of the saved one.
     */
    fun openCamera(learning: Boolean = false): Int {
        if (!learning && cameraRoot() != null) return ShutterProtocol.STATUS_OK
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val pkg = Settings.cameraPackage(this)
        val intent = when {
            // The lock screen only allows the secure camera.
            locked -> cameraIntent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE, pkg)
            learning -> cameraIntent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, null)
            // Like tapping the app icon: resumes the camera's existing screen if it's still running.
            else -> packageManager.getLaunchIntentForPackage(pkg)
                ?: cameraIntent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, pkg)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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

    /** A camera intent aimed at [pkg] when that app handles it, else at the default camera. */
    private fun cameraIntent(action: String, pkg: String?): Intent {
        val intent = Intent(action)
        if (pkg != null && Intent(intent).setPackage(pkg).resolveActivity(packageManager) != null) {
            intent.setPackage(pkg)
        }
        return intent
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

    /**
     * Looks for the shutter in this order: the button learned with "Learn my shutter", the
     * built-in view IDs for known camera apps, then any button labelled like a shutter.
     */
    private fun findShutter(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val learned = Settings.learnedShutter(this)
        val ids = listOfNotNull(learned?.viewId) + KNOWN_SHUTTER_IDS[root.packageName?.toString()].orEmpty()
        for (id in ids) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }
                ?.let { return it }
        }
        learned?.label?.let { label ->
            findNode(root) { it.equals(label, ignoreCase = true) }?.let { return it }
        }
        return findNode(root) { text -> SHUTTER_LABELS.any { it in text.lowercase() } }
    }

    /** Depth-first search for a visible node whose label (description or text) matches. */
    private fun findNode(node: AccessibilityNodeInfo, matches: (String) -> Boolean): AccessibilityNodeInfo? {
        val label = (node.contentDescription ?: node.text)?.toString()
        if (label != null && node.isVisibleToUser && matches(label)) return node
        for (i in 0 until node.childCount) {
            val found = node.getChild(i)?.let { findNode(it, matches) }
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

        private const val LEARN_TIMEOUT_MS = 60_000L

        // Built-in shutter view IDs per camera app, so these work without "Learn my shutter".
        // Add more as people report them (the "Dump camera screen" button shows the IDs).
        private val KNOWN_SHUTTER_IDS = mapOf(
            Settings.PIXEL_CAMERA to listOf(
                "com.google.android.GoogleCamera:id/shutter_button",
                "com.google.android.GoogleCamera:id/shutter",
            ),
        )
        private val SHUTTER_LABELS = listOf("take photo", "shutter", "take picture", "capture")

        @Volatile
        var instance: ShutterAccessibilityService? = null
            private set

        fun lastDumpFile(context: Context) = File(context.filesDir, "camera-ui-dump.txt")
    }
}
