package io.github.staindk.nacashu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View

/**
 * A Pebble Time 2 drawn around the watchapp's screenshot, with its buttons numbered to match the
 * list under it in Help.WATCHAPP. The screenshot is docs/emery-1-ready.png, which the build copies
 * in (see app/build.gradle.kts), so it's always the one CI last took. The numbers sit on the
 * buttons, not on the screen, so they stay right when the watchapp's layout changes.
 */
class WatchDiagram(context: Context) : View(context) {
    private val shot: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.watch_screenshot)
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Nearest neighbour, so the watch's pixels stay sharp when scaled up.
    private val shotPaint = Paint().apply { isFilterBitmap = false }
    private val accent = context.themeColor(android.R.attr.colorAccent)?.defaultColor ?: Color.rgb(255, 128, 0)

    // Whole multiples of the watch's pixels, worked out in onMeasure.
    private var scale = 1

    init {
        contentDescription = "The Nacashu watchapp on a Pebble Time 2. Numbered buttons: " +
            BUTTONS.joinToString { "${it.number} ${it.name}" }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Leave room for the markers either side, and don't let the watch get silly on a tablet.
        val room = minOf(width - 2 * markerRoom(), (MAX_WATCH_DP * density).toInt())
        scale = (room / (shot.width + 2 * (BEZEL + NUB_W))).coerceAtLeast(1)
        setMeasuredDimension(width, (shot.height + 2 * BEZEL) * scale + 2 * pad())
    }

    override fun onDraw(canvas: Canvas) {
        val s = scale.toFloat()
        val screen = RectF(0f, 0f, shot.width * s, shot.height * s)
        screen.offset((width - screen.width()) / 2, pad() + BEZEL * s)
        val body = RectF(screen).apply { inset(-BEZEL * s, -BEZEL * s) }

        // Buttons first, so the body covers their inner half.
        paint.color = BODY
        for (button in BUTTONS) {
            val y = screen.top + button.at * screen.height()
            val x = if (button.left) body.left - NUB_W * s else body.right - NUB_W * s
            canvas.drawRoundRect(RectF(x, y - NUB_H * s / 2, x + 2 * NUB_W * s, y + NUB_H * s / 2), 2 * s, 2 * s, paint)
        }
        canvas.drawRoundRect(body, 8 * s, 8 * s, paint)
        canvas.drawBitmap(shot, null, Rect().also { screen.round(it) }, shotPaint)

        // A numbered marker beside each button.
        val r = MARKER_DP * density
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = r * 1.2f
        paint.isFakeBoldText = true
        for (button in BUTTONS) {
            val y = screen.top + button.at * screen.height()
            val x = if (button.left) body.left - NUB_W * s - GAP_DP * density - r else body.right + NUB_W * s + GAP_DP * density + r
            paint.color = accent
            canvas.drawCircle(x, y, r, paint)
            paint.color = Color.WHITE
            canvas.drawText(button.number.toString(), x, y - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }

    private fun pad() = (8 * density).toInt()

    /** Space beside a button for its marker. */
    private fun markerRoom() = ((GAP_DP + 2 * MARKER_DP) * density).toInt()

    /** A button: its number in the list, which side it's on, and how far down the screen it sits. */
    private class Key(val number: Int, val name: String, val left: Boolean, val at: Float)

    private companion object {
        // In the watch's own pixels.
        const val BEZEL = 14
        const val NUB_W = 5
        const val NUB_H = 30
        // In dp.
        const val MARKER_DP = 12
        const val GAP_DP = 6
        const val MAX_WATCH_DP = 320
        val BODY = Color.rgb(0x30, 0x30, 0x30)

        // Lined up with the labels the watchapp draws next to each button.
        val BUTTONS = listOf(
            Key(1, "UP", left = false, at = 0.18f),
            Key(2, "SELECT", left = false, at = 0.5f),
            Key(3, "DOWN", left = false, at = 0.9f),
            Key(4, "BACK", left = true, at = 0.18f),
        )
    }
}
