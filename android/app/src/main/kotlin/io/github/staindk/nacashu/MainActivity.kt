package io.github.staindk.nacashu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TableLayout
import android.widget.TableRow
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
    private lateinit var status: TableLayout
    // Everything the Help switch shows and hides.
    private val helpViews = mutableListOf<View>()

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
        // One row: the app icon, the name, and the Help switch at the far right. The name takes the
        // space left over and shrinks on narrow screens, kept to the left like the rest of the screen.
        val headerRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        headerRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.logo)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = pad / 2 },
        )
        headerRow.addView(
            ImageView(this).apply {
                setImageResource(R.drawable.header)
                contentDescription = "Nacashu, Native Camera Shutter, for Pebble"
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_START
                maxHeight = drawable.intrinsicHeight // Shrink when short of room, never grow.
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = pad / 2 },
        )
        // "Help" sits above its toggle on a small card, which takes less width than side by side.
        headerRow.addView(ShutterToggle(this, "Help", Settings.helpOn(this), stacked = true) { on ->
            Settings.setHelpOn(this, on)
            showHelp(on)
        })
        // The header stays put above the scrolling part of the screen.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
        }
        header.addView(headerRow)
        header.addView(
            View(this).apply { setBackgroundColor(0x40808080) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, resources.displayMetrics.density.toInt().coerceAtLeast(1))
                .apply { topMargin = pad },
        )

        addAboutTopics(pad)

        // The status sits on a card, which also marks where the About topics end.
        // Labels on the left, values on the right; long values wrap.
        status = TableLayout(this).apply {
            setPadding(pad, pad, pad, pad)
            background = card()
            setColumnStretchable(1, true)
            setColumnShrinkable(1, true)
        }
        column.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = pad },
        )

        addButton("1. Turn on accessibility service") {
            if (Settings.disclosureAccepted(this)) {
                startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS))
            } else {
                showDisclosure()
            }
        }
        explain(Help.STEP_ACCESSIBILITY)
        addButton("2. Choose camera app") { chooseCamera() }
        explain(Help.STEP_CAMERA)
        addButton("3. Set shutter button") {
            val service = ShutterAccessibilityService.instance
            if (service == null) {
                toast("Do step 1 first")
            } else {
                service.startShutterSetup()
            }
        }
        explain(Help.STEP_SHUTTER)

        column.addView(ShutterToggle(this, "Open camera when the watchapp starts", Settings.autoOpenCamera(this)) { checked ->
            Settings.setAutoOpenCamera(this, checked)
            updateStatus() // The next step depends on it.
        })
        explain(Help.AUTO_OPEN)

        column.addView(ShutterToggle(this, "Take photo when quick launching Nacashu on Pebble", Settings.snapOnQuickLaunch(this)) { checked ->
            Settings.setSnapOnQuickLaunch(this, checked)
        })
        explain(Help.QUICK_LAUNCH)

        column.addView(ShutterToggle(this, "Lefty: button labels on the left of the watch screen", Settings.lefty(this)) { checked ->
            Settings.setLefty(this, checked)
            sendSettingsToWatch()
        })
        explain(Help.LEFTY)

        column.addView(ShutterToggle(this, "Sounds on the watch", Settings.watchSounds(this)) { checked ->
            Settings.setWatchSounds(this, checked)
            sendSettingsToWatch()
        })
        explain(Help.SOUNDS)

        column.addView(ShutterToggle(this, "Remember watchapp settings", Settings.rememberChoices(this)) { checked ->
            Settings.setRememberChoices(this, checked)
            sendSettingsToWatch()
        })
        explain(Help.REMEMBER)

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
        explain(Help.TEST_DEBUG)
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
                    toast(if (service.dumpCameraUi()) "Camera screen dumped" else "Camera wasn't on screen")
                }
            }, 5000)
        }
        addButton("Debug: share last dump", testSection) { shareDump() }
        testSection.addView(ShutterToggle(this, "Turn accessibility off when I close Nacashu on my watch", Settings.accessibilityAutoOff(this)) { checked ->
            Settings.setAccessibilityAutoOff(this, checked)
        })
        testSection.addView(TextView(this).apply {
            text = "For banking apps that refuse to run while accessibility is on. To turn it back " +
                "on, hold both volume keys for 3 seconds. Set that up once in Settings > " +
                "Accessibility > Nacashu > Nacashu shortcut."
            textSize = 13f
            setPadding(0, 0, 0, pad)
        })

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(
                ScrollView(this@MainActivity).apply { addView(column) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
            )
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
        showHelp(Settings.helpOn(this))
    }

    private fun showHelp(on: Boolean) {
        for (view in helpViews) view.visibility = if (on) View.VISIBLE else View.GONE
    }

    /** A grey explainer line under the setting just added, shown when Help is on. */
    private fun explain(text: String) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(themeColor(android.R.attr.textColorSecondary))
            setPadding(pad / 4, 0, 0, pad / 2)
        }
        column.addView(view)
        helpViews += view
    }

    /** "About Nacashu": one collapsed line per topic, tap to open. Shown when Help is on. */
    private fun addAboutTopics(pad: Int) {
        val about = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, pad)
        }
        about.addView(TextView(this).apply {
            text = "About Nacashu"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(themeColor(android.R.attr.textColorPrimary))
        })
        for ((title, html) in Help.TOPICS) {
            val body = TextView(this).apply {
                text = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
                movementMethod = LinkMovementMethod.getInstance()
                textSize = 14f
                setTextColor(themeColor(android.R.attr.textColorSecondary))
                setPadding(pad / 2, 0, 0, pad / 2)
                visibility = View.GONE
            }
            about.addView(TextView(this).apply {
                text = "$title ▸"
                textSize = 16f
                setTextColor(themeColor(android.R.attr.textColorPrimary))
                setPadding(0, pad / 2, 0, pad / 2)
                setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    text = if (open) "$title ▾" else "$title ▸"
                }
            })
            about.addView(body)
        }
        column.addView(about)
        helpViews += about
    }

    /**
     * Google Play's prominent disclosure: says what accessibility access is for before Android's
     * settings open. "Not now" leaves everything as it was, so step 1 shows this again next time.
     */
    private fun showDisclosure() {
        AlertDialog.Builder(this)
            .setTitle(Help.DISCLOSURE_TITLE)
            .setMessage(Html.fromHtml(Help.DISCLOSURE, Html.FROM_HTML_MODE_LEGACY))
            .setNegativeButton("Not now", null)
            .setPositiveButton("Agree") { _, _ ->
                Settings.setDisclosureAccepted(this)
                startActivity(Intent(ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        // The service can also turn on or off while this screen is open, e.g. its auto-off.
        ShutterAccessibilityService.onStateChanged = ::updateStatus
        updateStatus()
    }

    override fun onPause() {
        ShutterAccessibilityService.onStateChanged = null
        super.onPause()
    }

    private fun updateStatus() {
        // Core Devices' app is the official one; anything else (a fork, say) shows its package name.
        val pebbleApp = when (val pkg = DefaultPebbleAndroidAppPicker.getInstance(this).getAllEligibleApps().firstOrNull()?.toString()) {
            null -> "not found"
            OFFICIAL_PEBBLE_APP -> "Official"
            else -> pkg
        }
        val accessibilityOn = ShutterAccessibilityService.instance != null
        val saved = Settings.savedShutter(this)
        val shutter = when {
            saved != null && (saved.label != null || saved.viewId != null) ->
                "set to \"${saved.label ?: "no label"}\" (ID: ${saved.viewId ?: "none"})"
            saved != null -> "set to a position on screen"
            Settings.cameraPackage(this) == Settings.PIXEL_CAMERA &&
                packageManager.getLaunchIntentForPackage(Settings.PIXEL_CAMERA) != null -> "built in for Pixel Camera"
            else -> null
        }
        val nextStep = when {
            !accessibilityOn -> "Turn on accessibility below"
            pebbleApp == "not found" -> "Install the Pebble app"
            shutter == null -> "Set shutter button below"
            Settings.autoOpenCamera(this) -> "Open Nacashu on the watch, then press SELECT to take a photo"
            else -> "Open Nacashu on the watch, ensure your camera app is open, then press SELECT to take a photo"
        }
        status.removeAllViews()
        statusRow("Accessibility service", if (accessibilityOn) "ON" else "OFF")
        statusRow("Pebble app", pebbleApp)
        statusRow("Camera app", appLabel(Settings.cameraPackage(this)))
        statusRow("Shutter", shutter ?: "not set")
        statusRow("Next step", nextStep, gap = true)
    }

    /** One line of the status card: label on the left, value on the right. */
    private fun statusRow(label: String, value: String, gap: Boolean = false) {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val color = themeColor(android.R.attr.textColorPrimary)
        status.addView(TableRow(this).apply {
            if (gap) setPadding(0, pad, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 16f
                setTextColor(color)
                setPadding(0, 0, pad, 0)
            })
            addView(TextView(this@MainActivity).apply {
                text = value
                textSize = 16f
                setTextColor(color)
                gravity = Gravity.END
            })
        })
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
                updateStatus()
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

    private companion object {
        const val OFFICIAL_PEBBLE_APP = "coredevices.coreapp"
    }
}
