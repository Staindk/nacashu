package io.github.staindk.nacashu

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker

/** Setup and test screen. Plain views, so there's no UI library to audit. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var column: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 3, pad, pad)
        }

        status = TextView(this).apply {
            textSize = 16f
            setPadding(0, 0, 0, pad)
        }
        column.addView(status)

        addButton("1. Turn on accessibility service") {
            startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("2. Learn my shutter") {
            val service = ShutterAccessibilityService.instance
            if (service == null) {
                toast("Do step 1 first")
            } else {
                toast("Tap your camera's shutter button once")
                service.startLearning()
            }
        }

        column.addView(CheckBox(this).apply {
            text = "Open camera when the watchapp starts"
            isChecked = Settings.autoOpenCamera(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setAutoOpenCamera(this@MainActivity, checked)
            }
        })

        addButton("Test: open camera") {
            runCommand(ShutterProtocol.CMD_OPEN_CAMERA, 0)
        }
        addButton("Test: open camera, then shutter after 4s") {
            runCommand(ShutterProtocol.CMD_OPEN_CAMERA, 0)
            runCommand(ShutterProtocol.CMD_SHUTTER, 4000)
        }
        addButton("Debug: dump camera screen after 5s") {
            toast("Switch to the camera now")
            handler.postDelayed({
                val service = ShutterAccessibilityService.instance
                if (service == null) {
                    toast("Accessibility service is off")
                } else {
                    service.dumpCameraUi()
                    toast("Camera screen dumped")
                }
            }, 5000)
        }
        addButton("Debug: share last dump") { shareDump() }

        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        val pebbleApp = DefaultPebbleAndroidAppPicker.getInstance(this).getAllEligibleApps().firstOrNull()
        val accessibility = if (ShutterAccessibilityService.instance != null) "ON" else "OFF (step 1)"
        val learned = Settings.learnedShutter(this)
        val shutter = when {
            learned != null -> "learned (${learned.label ?: learned.viewId})"
            Settings.cameraPackage(this) == Settings.PIXEL_CAMERA &&
                packageManager.getLaunchIntentForPackage(Settings.PIXEL_CAMERA) != null -> "built in for Pixel Camera"
            else -> "not set up (step 2)"
        }
        status.text = "Accessibility service: $accessibility" +
            "\nPebble app: ${pebbleApp ?: "not found"}" +
            "\nCamera app: ${Settings.cameraPackage(this)}" +
            "\nShutter: $shutter" +
            "\n\nOpen Nacashu on the watch, then press SELECT to take a photo."
    }

    private fun addButton(label: String, onClick: () -> Unit) {
        val button = Button(this).apply {
            text = label
            setOnClickListener { onClick() }
        }
        column.addView(
            button,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
    }

    private fun runCommand(cmd: Int, delayMs: Long) {
        handler.postDelayed({
            ShutterProtocol.run(cmd) { result -> toast(ShutterProtocol.statusName(result)) }
        }, delayMs)
    }

    private fun toast(text: String) {
        Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()
    }

    private fun shareDump() {
        val file = ShutterAccessibilityService.lastDumpFile(this)
        if (!file.exists()) return toast("No dump yet")
        val text = file.readText().take(90_000)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Share camera dump"))
    }
}
