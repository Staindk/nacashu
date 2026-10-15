package io.github.staindk.nacashu

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.Switch
import kotlin.math.roundToInt

/**
 * An on/off switch, label on the left and switch on the right like Android's settings, or
 * [stacked] on a small card with the label above. Drawn as the shutter from the app icon, kept
 * black and white like the rest of the app: the full shutter when on, an empty grey ring when off.
 * Built on a plain CheckBox because Android's Switch stretches custom knobs to its own size.
 */
class ShutterToggle(
    context: Context,
    label: String,
    checked: Boolean,
    stacked: Boolean = false,
    onChange: (Boolean) -> Unit,
) : CheckBox(context) {
    init {
        text = label
        textSize = if (stacked) 14f else 16f
        val fg = context.themeColor(android.R.attr.textColorPrimary)
        setTextColor(fg)
        val pixel = (2.4f * resources.displayMetrics.density).roundToInt() // Whole pixels stay crisp.
        val on = fg?.defaultColor ?: Color.WHITE
        val shutter = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), ShutterDrawable(pixel, on, on))
            addState(intArrayOf(), ShutterDrawable(pixel, 0xFF9E9E9E.toInt(), null))
        }
        buttonDrawable = null
        // The tap ripple spreads from the finger across the whole toggle, not from its middle.
        val ripple = ColorStateList.valueOf(0x33808080)
        if (stacked) {
            // Label and shutter both centred in the one view, so they line up exactly.
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, null, shutter)
            gravity = Gravity.CENTER
            compoundDrawablePadding = pixel * 2
            setPadding(pixel * 5, pixel * 3, pixel * 5, pixel * 3)
            background = RippleDrawable(ripple, context.card(), null)
        } else {
            setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, shutter, null)
            compoundDrawablePadding = pixel * 6
            background = RippleDrawable(ripple, null, ColorDrawable(Color.WHITE))
            // A labelled switch spans the row, so the switches line up at the right edge.
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }

    // TalkBack calls it a switch, not a checkbox.
    override fun getAccessibilityClassName(): CharSequence = Switch::class.java.name
}

/** The shutter from the app icon, 10x10 pixels: a ring, and a disc inside it when [disc] is set. */
private class ShutterDrawable(private val pixel: Int, private val ring: Int, private val disc: Int?) : Drawable() {
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        for ((y, row) in PIXELS.withIndex()) {
            for ((x, c) in row.withIndex()) {
                paint.color = when (c) {
                    'r' -> ring
                    'd' -> disc ?: continue
                    else -> continue
                }
                val left = (bounds.left + x * pixel).toFloat()
                val top = (bounds.top + y * pixel).toFloat()
                canvas.drawRect(left, top, left + pixel, top + pixel, paint)
            }
        }
    }

    override fun getIntrinsicWidth() = pixel * 10
    override fun getIntrinsicHeight() = pixel * 10
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    private companion object {
        val PIXELS = listOf(
            "..rrrrrr..",
            ".rr....rr.",
            "rr.dddd.rr",
            "r.dddddd.r",
            "r.dddddd.r",
            "r.dddddd.r",
            "r.dddddd.r",
            "rr.dddd.rr",
            ".rr....rr.",
            "..rrrrrr..",
        )
    }
}
