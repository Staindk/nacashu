package io.github.staindk.nacashu

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.io.File
import java.io.IOException

/**
 * Finds the stock camera's shutter button and clicks it, the same way TalkBack (Android's
 * screen reader) would. The camera app still takes the photo, so all of its processing applies.
 */
class ShutterAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: TextView? = null
    // When openCamera() last launched the camera, so Quick Launch knows if it has just started.
    private var cameraStartedAt = 0L
    private val learningTimedOut = Runnable { finishLearning("No tap seen, so the shutter wasn't set. Please try again.") }

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        removeOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    /**
     * "Set shutter button": covers the screen with a see-through layer, then opens the chosen
     * camera under it. The layer comes first, so no tap reaches the camera. Taps before the camera
     * is on screen are ignored. The first tap after that is saved (where it landed and which
     * camera button is under it), and 3 seconds later the layer goes away and Nacashu comes back
     * to the front. With no tap, the same happens after a minute, and nothing is saved.
     */
    fun startLearning() {
        // Drop anything left from an earlier round, such as its pending trip back to Nacashu.
        handler.removeCallbacksAndMessages(null)
        removeOverlay()
        showLearnOverlay()
        openCamera()
        handler.postDelayed(learningTimedOut, LEARN_TIMEOUT_MS)
    }

    private fun showLearnOverlay() {
        val dp = resources.displayMetrics.density
        val view = TextView(this).apply {
            text = "Nacashu: tap your camera's shutter button once"
            textSize = 24f
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (120 * dp).toInt(), (24 * dp).toInt(), 0)
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP) learnAt(event.rawX, event.rawY)
                true
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        getSystemService(WindowManager::class.java).addView(view, params)
        overlay = view
    }

    private fun learnAt(x: Float, y: Float) {
        val root = cameraRoot()
        if (root == null) {
            // Tapped before the camera came up, so there's nothing under the tap to learn yet.
            overlay?.text = "Wait for the camera to open, then tap its shutter button"
            return
        }
        val node = nodeAt(root, x.toInt(), y.toInt())
        val label = (node?.contentDescription ?: node?.text)?.toString()?.takeIf { it.isNotBlank() }
        val shutter = Settings.LearnedShutter(node?.viewIdResourceName, label)
        Settings.saveLearnedShutter(this, shutter, x to y)
        Log.i(TAG, "Learned shutter at ($x, $y): $shutter")
        finishLearning("Shutter set: ${label ?: shutter.viewId ?: "that spot on the screen"}")
    }

    /** Shows [message] on the layer for a few seconds, then removes it and goes back to Nacashu. */
    private fun finishLearning(message: String) {
        handler.removeCallbacks(learningTimedOut)
        val view = overlay ?: return
        view.text = "$message\n\nGoing back to Nacashu…"
        view.setOnTouchListener(null)
        handler.postDelayed({
            removeOverlay()
            // Allowed from the background for the same reason as openCamera().
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
        }, 3000)
    }

    private fun removeOverlay() {
        overlay?.let { getSystemService(WindowManager::class.java).removeView(it) }
        overlay = null
    }

    /**
     * The smallest visible node under the point, preferring clickable ones. Ignores anything
     * bigger than a quarter of the camera screen (such as the preview), because that isn't a
     * button; the tapped position is used then.
     */
    private fun nodeAt(root: AccessibilityNodeInfo, x: Int, y: Int): AccessibilityNodeInfo? {
        val screen = Rect().also(root::getBoundsInScreen)
        val maxArea = screen.width().toLong() * screen.height() / 4
        var best: AccessibilityNodeInfo? = null
        var bestScore = Long.MAX_VALUE
        fun visit(node: AccessibilityNodeInfo) {
            val bounds = Rect().also(node::getBoundsInScreen)
            if (!node.isVisibleToUser || !bounds.contains(x, y)) return
            val area = bounds.width().toLong() * bounds.height()
            // Any clickable node beats any non-clickable one.
            val score = if (node.isClickable) area else area + Int.MAX_VALUE
            if (area <= maxArea && score < bestScore) {
                best = node
                bestScore = score
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(::visit)
        }
        visit(root)
        return best
    }

    override fun onInterrupt() {}

    /**
     * Brings the chosen camera to the front without resetting it: does nothing if it's already on
     * screen, and resumes it as it was (mode, focus, zoom) when unlocked.
     */
    fun openCamera(): Int {
        if (cameraRoot() != null) return ShutterProtocol.STATUS_OK
        val locked = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val pkg = Settings.cameraPackage(this)
        val intent = when {
            // The lock screen only allows the secure camera.
            locked -> cameraIntent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE, pkg)
            // Like tapping the app icon: resumes the camera's existing screen if it's still running.
            else -> packageManager.getLaunchIntentForPackage(pkg)
                ?: cameraIntent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, pkg)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            // Allowed from the background: a running accessibility service is exempt from
            // background activity launch restrictions.
            startActivity(intent)
            cameraStartedAt = SystemClock.uptimeMillis()
            ShutterProtocol.STATUS_OK
        } catch (e: RuntimeException) {
            Log.e(TAG, "Could not open camera", e)
            ShutterProtocol.STATUS_CAMERA_NOT_OPEN
        }
    }

    /** A camera intent aimed at [pkg] when that app handles it, else at the default camera. */
    private fun cameraIntent(action: String, pkg: String): Intent {
        val intent = Intent(action)
        if (Intent(intent).setPackage(pkg).resolveActivity(packageManager) != null) {
            intent.setPackage(pkg)
        }
        return intent
    }

    /**
     * Quick Launch: brings the camera up if it isn't already, then presses the shutter.
     *
     * A camera that was already open is pressed straight away. If Nacashu opened it less than
     * [CAMERA_SETTLE_MS] ago (just now, or when the watchapp started), this first waits out the
     * rest of that time so the camera is ready to take the photo.
     */
    fun openCameraAndPressShutter(onStatus: (Int) -> Unit) {
        val opened = openCamera()
        if (opened != ShutterProtocol.STATUS_OK) return onStatus(opened)

        val msSinceStarted = SystemClock.uptimeMillis() - cameraStartedAt
        val wait = (CAMERA_SETTLE_MS - msSinceStarted).coerceAtLeast(0)
        handler.postDelayed({ pressShutterWhenOnScreen(CAMERA_CHECKS, onStatus) }, wait)
    }

    /**
     * Presses the shutter once the camera is on screen, checking every [CAMERA_CHECK_MS].
     * After [checksLeft] checks it presses anyway, and pressShutter reports the camera isn't open.
     */
    private fun pressShutterWhenOnScreen(checksLeft: Int, onStatus: (Int) -> Unit) {
        if (cameraRoot() != null || checksLeft == 0) {
            pressShutter(onStatus)
            return
        }
        handler.postDelayed({ pressShutterWhenOnScreen(checksLeft - 1, onStatus) }, CAMERA_CHECK_MS)
    }

    fun pressShutter(onStatus: (Int) -> Unit) {
        val root = cameraRoot() ?: return onStatus(ShutterProtocol.STATUS_CAMERA_NOT_OPEN)

        val shutter = findShutter(root)
        if (shutter != null) {
            // A learned button is exactly what the user tapped; don't widen it to a parent.
            val target = clickTarget(shutter, widen = Settings.learnedShutter(this) == null)
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
     * Once a shutter has been learned, only that is used: its view ID, then its label. When
     * neither is found this returns null and the caller taps the learned position. Built-in IDs
     * and shutter-like labels are only for cameras that haven't been learned.
     */
    private fun findShutter(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val learned = Settings.learnedShutter(this)
        if (learned != null) {
            learned.viewId?.let { id ->
                root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }?.let { return it }
            }
            return learned.label?.let { label -> findNode(root) { it.equals(label, ignoreCase = true) } }
        }
        for (id in KNOWN_SHUTTER_IDS[root.packageName?.toString()].orEmpty()) {
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull { it.isVisibleToUser }?.let { return it }
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

    /**
     * The node to click for [node]: itself if clickable, else a clickable node inside it (Pixel
     * Camera's shutter_button wraps a clickable Compose button), else, when [widen], a clickable
     * parent.
     */
    private fun clickTarget(node: AccessibilityNodeInfo, widen: Boolean): AccessibilityNodeInfo =
        if (node.isClickable) node
        else clickableDescendant(node) ?: if (widen) clickableAncestor(node) else node

    private fun clickableDescendant(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isClickable && child.isVisibleToUser) return child
            clickableDescendant(child)?.let { return it }
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

        // Quick Launch: the camera gets this long after Nacashu opens it before its shutter is
        // pressed, so it's ready to take the photo. A first guess; tune it on a real phone.
        private const val CAMERA_SETTLE_MS = 1_000L
        // Then up to 4 more seconds (40 checks, 100 ms apart) for it to show up on screen.
        private const val CAMERA_CHECK_MS = 100L
        private const val CAMERA_CHECKS = 40

        // Built-in shutter view IDs per camera app, so these work without "Set shutter button".
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
