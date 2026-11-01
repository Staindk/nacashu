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
 * A chunky pixel watch shaped like the app icon's (docs/icon.png), with the watchapp's screenshot
 * in its window and the buttons numbered to match the list under it in Help.WATCHAPP. The
 * screenshot is docs/emery-1-ready.png, which the build copies in (see app/build.gradle.kts), so
 * it's always the one CI last took. The numbers sit on the buttons, not on the screen, so they stay
 * right when the watchapp's layout changes.
 */
class WatchDiagram(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    // Nearest neighbour, so the pixels stay sharp when scaled up.
    private val pixels = Paint().apply { isFilterBitmap = false }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // The watch, drawn once at the screenshot's own pixel size, and its buttons in Help.WATCHAPP's order.
    private val art: Bitmap
    private val buttons = mutableListOf<Rect>()

    // Screen pixels per screenshot pixel, worked out in onMeasure.
    private var scale = 1

    init {
        val shot = BitmapFactory.decodeResource(resources, R.drawable.watch_screenshot)
        // Everything but the screenshot is drawn in cells of CELL pixels, like the icon's chunky pixels.
        // The black window leaves a gap round the screenshot, so its white edges don't run into the frame.
        val inW = (shot.width + 2 * GAP + CELL - 1) / CELL
        val inH = (shot.height + 2 * GAP + CELL - 1) / CELL
        val bodyW = inW + 2 * FRAME
        val bodyH = inH + 2 * FRAME
        art = Bitmap.createBitmap((bodyW + 2 * BUTTON_W) * CELL, (bodyH + 2 * STRAP_H) * CELL, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(art)
        val fill = Paint() // No anti-aliasing: hard pixel edges.
        fun cells(x: Int, y: Int, w: Int, h: Int, color: Int): Rect {
            val r = Rect(x * CELL, y * CELL, (x + w) * CELL, (y + h) * CELL)
            fill.color = color
            canvas.drawRect(r, fill)
            return r
        }

        // As in the icon: half-width grey straps, a white body with two-step corners, a black window.
        val strapW = bodyW / 2
        cells(BUTTON_W + (bodyW - strapW) / 2, 0, strapW, STRAP_H, STRAP_GREY)
        cells(BUTTON_W + (bodyW - strapW) / 2, STRAP_H + bodyH, strapW, STRAP_H, STRAP_GREY)
        cells(BUTTON_W + 2, STRAP_H, bodyW - 4, bodyH, Color.WHITE)
        cells(BUTTON_W + 1, STRAP_H + 1, bodyW - 2, bodyH - 2, Color.WHITE)
        cells(BUTTON_W, STRAP_H + 2, bodyW, bodyH - 4, Color.WHITE)
        cells(BUTTON_W + FRAME, STRAP_H + FRAME, inW, inH, Color.BLACK)
        val shotX = (BUTTON_W + FRAME) * CELL + (inW * CELL - shot.width) / 2
        val shotY = (STRAP_H + FRAME) * CELL + (inH * CELL - shot.height) / 2
        canvas.drawBitmap(shot, shotX.toFloat(), shotY.toFloat(), null)

        // 1 UP, 2 SELECT, 3 DOWN on the right, 4 BACK on the left across from UP. SELECT is in the
        // middle of the body, and UP and DOWN a quarter of the body's height above and below it.
        val middle = STRAP_H * 2 + bodyH // Twice the middle row, so odd heights stay exact.
        val step = bodyH / 4
        for ((left, offset) in listOf(false to -1, false to 0, false to 1, true to -1)) {
            val y = (middle - BUTTON_H) / 2 + offset * step
            buttons += cells(if (left) 0 else BUTTON_W + bodyW, y, BUTTON_W, BUTTON_H, Color.WHITE)
        }
        shot.recycle()

        contentDescription = "The Nacashu watchapp on a Pebble Time 2. Numbered buttons: 1 UP, 2 SELECT, " +
            "3 DOWN on the right, 4 BACK on the left"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Don't let the watch get silly on a tablet.
        val room = minOf(width - 2 * pad(), (MAX_WATCH_DP * density).toInt())
        scale = (room / art.width).coerceAtLeast(1)
        setMeasuredDimension(width, art.height * scale + 2 * pad())
    }

    override fun onDraw(canvas: Canvas) {
        // A black card, like the app icon's background.
        paint.color = Color.BLACK
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 12 * density, 12 * density, paint)

        val left = (width - art.width * scale) / 2
        val top = pad()
        canvas.drawBitmap(art, null, Rect(left, top, left + art.width * scale, top + art.height * scale), pixels)

        // Each button's number, on the button.
        paint.color = Color.BLACK
        paint.textSize = CELL * scale * 1.3f
        for ((i, button) in buttons.withIndex()) {
            val x = left + button.exactCenterX() * scale
            val y = top + button.exactCenterY() * scale
            canvas.drawText((i + 1).toString(), x, y - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }

    private fun pad() = (8 * density).toInt()

    private companion object {
        // In screenshot pixels: the size of one chunky pixel, and the black gap round the screenshot.
        const val CELL = 10
        const val GAP = 4
        // In chunky pixels, matching the icon's proportions.
        const val FRAME = 3
        const val BUTTON_W = 2
        const val BUTTON_H = 4
        const val STRAP_H = 4
        const val MAX_WATCH_DP = 360
        val STRAP_GREY = Color.rgb(0x55, 0x55, 0x55)
    }
}
