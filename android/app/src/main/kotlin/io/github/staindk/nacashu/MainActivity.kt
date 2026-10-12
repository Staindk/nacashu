package io.github.staindk.nacashu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.rebble.pebblekit2.client.DefaultPebbleAndroidAppPicker
import io.rebble.pebblekit2.client.DefaultPebbleSender
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Setup and test screen. Plain views, so there's no UI library to audit. */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    // Cheap to create: it only binds to the Pebble app when a message is sent.
    private val sender by lazy { DefaultPebbleSender(applicationContext) }
    private lateinit var column: LinearLayout
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Newer Android draws apps edge to edge, under the title bar. Drop the title bar and keep
        // the content clear of the status and navigation bars instead.
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        val pad = (16 * resources.displayMetrics.density).toInt()
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        // "Nacashu / Native Camera Shutter / for Pebble" in the watchapp's pixel font, drawn by
        // watch/tools/make_name_images.py. Pebble's trademark rules allow the word "Pebble" in
        // "for Pebble", but not its logo.
        column.addView(ImageView(this).apply {
            setImageResource(R.drawable.header)
            contentDescription = "Nacashu, Native Camera Shutter, for Pebble"
        })
        column.addView(
            View(this).apply { setBackgroundColor(0x40808080) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.density.toInt().coerceAtLeast(1))
                .apply { setMargins(0, pad, 0, pad) },
        )

        status = TextView(this).apply {
            textSize = 16f
            // A plain TextView defaults to a dim grey; use the theme's main text colour.
            val attrs = theme.obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
            setTextColor(attrs.getColorStateList(0))
            attrs.recycle()
            setPadding(0, 0, 0, pad)
        }
        column.addView(status)

        addButton("1. Turn on accessibility service") {
            startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS))
        }
        addButton("2. Choose camera app") { chooseCamera() }
        addButton("3. Set shutter button") {
            val service = ShutterAccessibilityService.instance
            if (service == null) {
                toast("Do step 1 first")
            } else {
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

        column.addView(CheckBox(this).apply {
            text = "Lefty: button labels on the left of the watch screen"
            isChecked = Settings.lefty(this@MainActivity)
            setOnCheckedChangeListener { _, checked ->
                Settings.setLefty(this@MainActivity, checked)
                sendSettingsToWatch()
            }
        })

        // Test and debug buttons, hidden until the user opens this section.
        val testSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        addButton("Test / Debug ▸") {
            val open = testSection.visibility != View.VISIBLE
            testSection.visibility = if (open) View.VISIBLE else View.GONE
            (it as Button).text = if (open) "Test / Debug ▾" else "Test / Debug ▸"
        }
        column.addView(testSection)
        addButton("Test: open camera", testSection) {
            runCommand(ShutterProtocol.CMD_OPEN_CAMERA, 0)
        }
        addButton("Test: open camera, then shutter after 5s", testSection) {
            runCommand(ShutterProtocol.CMD_OPEN_CAMERA, 0)
            runCommand(ShutterProtocol.CMD_SHUTTER, 5000)
        }
        addButton("Debug: dump camera screen after 5s", testSection) {
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
        addButton("Debug: share last dump", testSection) { shareDump() }

        setContentView(ScrollView(this).apply {
            addView(column)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION") // The replacement needs API 30; this app supports 29.
                view.setPadding(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom,
                )
                insets
            }
        })
    }

    override fun onResume() {
        super.onResume()
        val pebbleApp = DefaultPebbleAndroidAppPicker.getInstance(this).getAllEligibleApps().firstOrNull()
        val accessibility = if (ShutterAccessibilityService.instance != null) "ON" else "OFF (step 1)"
        val learned = Settings.learnedShutter(this)
        val shutter = when {
            learned != null && (learned.label != null || learned.viewId != null) ->
                "set to \"${learned.label ?: "no label"}\" (ID: ${learned.viewId ?: "none"})"
            learned != null -> "set to a position on screen"
            Settings.cameraPackage(this) == Settings.PIXEL_CAMERA &&
                packageManager.getLaunchIntentForPackage(Settings.PIXEL_CAMERA) != null -> "built in for Pixel Camera"
            else -> "not set up (step 3)"
        }
        status.text = "Accessibility service: $accessibility" +
            "\nPebble app: ${pebbleApp ?: "not found"}" +
            "\nCamera app: ${appLabel(Settings.cameraPackage(this))}" +
            "\nShutter: $shutter" +
            "\n\nOpen Nacashu on the watch, then press SELECT to take a photo."
    }

    /** Lets the user pick which installed camera app Nacashu opens and presses. */
    private fun chooseCamera() {
        val cameras = packageManager.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), 0)
            .map { it.activityInfo.packageName }
            .distinct()
        if (cameras.isEmpty()) return toast("No camera apps found")
        val iconSize = (32 * resources.displayMetrics.density).toInt()
        // The dialog's own list row, with each app's icon in front of its name.
        val rows = object : ArrayAdapter<String>(this, android.R.layout.select_dialog_item, cameras.map(::appLabel)) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                (super.getView(position, convertView, parent) as TextView).apply {
                    val icon = appIcon(cameras[position])?.apply { setBounds(0, 0, iconSize, iconSize) }
                    setCompoundDrawablesRelative(icon, null, null, null)
                    compoundDrawablePadding = iconSize / 2
                }
        }
        AlertDialog.Builder(this)
            .setTitle("Camera app")
            .setAdapter(rows) { _, which ->
                Settings.setCameraPackage(this, cameras[which])
                onResume()
            }
            .show()
    }

    private fun appIcon(pkg: String) = try {
        packageManager.getApplicationIcon(pkg)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun appLabel(pkg: String): String = try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        "$pkg (not installed)"
    }

    override fun onDestroy() {
        scope.cancel()
        sender.close()
        super.onDestroy()
    }

    /** Updates the watchapp right away if it's open. If not, it gets the settings when it opens. */
    private fun sendSettingsToWatch() {
        scope.launch {
            sender.sendDataToPebble(ShutterProtocol.WATCHAPP_UUID, ShutterProtocol.settingsMessage(this@MainActivity))
        }
    }

    private fun addButton(label: String, parent: LinearLayout = column, onClick: (View) -> Unit) {
        val button = Button(this).apply {
            text = label
            setOnClickListener { onClick(it) }
        }
        parent.addView(
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
