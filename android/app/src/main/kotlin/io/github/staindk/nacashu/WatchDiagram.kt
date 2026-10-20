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
 * The app icon's pixel watch, scaled up, with the watchapp's screenshot in its window and the
 * buttons numbered to match the list under it in Help.WATCHAPP. Both pictures come from docs/
 * (icon.png and emery-1-ready.png), which the build copies in (see app/build.gradle.kts), so this
 * always shows the current icon and the screenshot CI last took. The numbers sit on the buttons,
 * not on the screen, so they stay right when the watchapp's layout changes.
 */
class WatchDiagram(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    // Nearest neighbour, so the pixels stay sharp when scaled up.
    private val pixels = Paint().apply { isFilterBitmap = false }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val shot: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.watch_screenshot)
    // The icon without its empty side margins, so the buttons reach the edges.
    private val watch: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.watch_icon).let {
        Bitmap.createBitmap(it, ICON_MARGIN, 0, it.width - 2 * ICON_MARGIN, it.height)
    }

    // The buttons, found on the icon itself: the white runs down its left and right edges.
    private val buttons: List<Rect> = buildList {
        val right = buttonRuns(watch.width - 1).map { Rect(watch.width - BUTTON_W, it.first, watch.width, it.last + 1) }
        val left = buttonRuns(0).map { Rect(0, it.first, BUTTON_W, it.last + 1) }
        addAll(right) // 1 UP, 2 SELECT, 3 DOWN
        addAll(left) // 4 BACK
    }

    // Icon pixels and screenshot pixels per watch pixel, worked out in onMeasure.
    private var cell = 1
    private var shotScale = 1

    init {
        contentDescription = "The Nacashu watchapp on a Pebble Time 2. Numbered buttons: 1 UP, 2 SELECT, " +
            "3 DOWN on the right, 4 BACK on the left"
    }

    private fun buttonRuns(x: Int): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (y in 0..watch.height) {
            val white = y < watch.height && watch.getPixel(x, y) == Color.WHITE
            if (white && start < 0) start = y
            if (!white && start >= 0) {
                runs += start until y
                start = -1
            }
        }
        return runs
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val room = minOf(width - 2 * pad(), (MAX_WATCH_DP * density).toInt())
        // The biggest whole-pixel screenshot whose watch still fits.
        shotScale = 1
        while (cellFor(shotScale + 1) * watch.width <= room) shotScale++
        cell = cellFor(shotScale)
        setMeasuredDimension(width, watch.height * cell + 2 * pad())
    }

    /** The icon pixel size whose window fits the screenshot at [scale]. */
    private fun cellFor(scale: Int) = maxOf(
        (shot.width * scale + WINDOW.width() - 1) / WINDOW.width(),
        (shot.height * scale + WINDOW.height() - 1) / WINDOW.height(),
    )

    override fun onDraw(canvas: Canvas) {
        // A black card, like the app icon's background.
        paint.color = Color.BLACK
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 12 * density, 12 * density, paint)

        val left = (width - watch.width * cell) / 2
        val top = pad()
        fun scaled(r: Rect) = Rect(left + r.left * cell, top + r.top * cell, left + r.right * cell, top + r.bottom * cell)

        canvas.drawBitmap(watch, null, scaled(Rect(0, 0, watch.width, watch.height)), pixels)
        // Empty the icon's window, then put the screenshot in the middle of it.
        val window = scaled(WINDOW)
        paint.color = Color.BLACK
        canvas.drawRect(window, paint)
        val w = shot.width * shotScale
        val h = shot.height * shotScale
        val x = window.centerX() - w / 2
        val y = window.centerY() - h / 2
        canvas.drawBitmap(shot, null, Rect(x, y, x + w, y + h), pixels)

        // Each button's number, on the button.
        paint.color = Color.BLACK
        paint.textSize = cell * 1.3f
        for ((i, button) in buttons.withIndex()) {
            val r = scaled(button)
            canvas.drawText((i + 1).toString(), r.exactCenterX(), r.exactCenterY() - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }

    private fun pad() = (8 * density).toInt()

    private companion object {
        // In icon pixels (docs/icon.png is 32x32): the empty columns either side of the watch, how
        // wide the buttons are, and the black window inside the frame, once the margins are cut off.
        const val ICON_MARGIN = 4
        const val BUTTON_W = 2
        val WINDOW = Rect(5, 7, 19, 25)
        const val MAX_WATCH_DP = 360
    }
}
