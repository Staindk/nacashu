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
 * A pixel-art Pebble Time 2, in the app icon's style, drawn around the watchapp's screenshot, with
 * its buttons numbered to match the list under it in Help.WATCHAPP. The screenshot is
 * docs/emery-1-ready.png, which the build copies in (see app/build.gradle.kts), so it's always the
 * one CI last took. The numbers sit on the buttons, not on the screen, so they stay right when the
 * watchapp's layout changes.
 */
class WatchDiagram(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    // Nearest neighbour, so the pixels stay sharp when scaled up.
    private val artPaint = Paint().apply { isFilterBitmap = false }
    private val accent = context.themeColor(android.R.attr.colorAccent)?.defaultColor ?: Color.rgb(26, 115, 232)

    // The watch at the screenshot's own pixel size, and where each button's middle is on it.
    private val art: Bitmap
    private val buttonAt = mutableMapOf<Key, Pair<Int, Int>>()

    // Whole multiples of the art's pixels, worked out in onMeasure.
    private var scale = 1

    init {
        val shot = BitmapFactory.decodeResource(resources, R.drawable.watch_screenshot)
        // Everything but the screenshot is drawn on a grid of CELL pixels, like the icon's chunky pixels.
        val inW = (shot.width + CELL - 1) / CELL + 2 // The black around the screen, in cells.
        val inH = (shot.height + CELL - 1) / CELL + 2
        val bodyW = inW + 2 * FRAME
        val bodyH = inH + 2 * FRAME
        art = Bitmap.createBitmap((bodyW + 2 * NUB) * CELL, (bodyH + 2 * STRAP) * CELL, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(art)
        val fill = Paint() // No anti-aliasing: hard pixel edges.
        fun cells(x: Int, y: Int, w: Int, h: Int, color: Int) {
            fill.color = color
            canvas.drawRect(Rect(x * CELL, y * CELL, (x + w) * CELL, (y + h) * CELL), fill)
        }

        // Strap stubs, then the body with stepped corners, then the black around the screen.
        val strapW = bodyW / 2
        cells(NUB + (bodyW - strapW) / 2, 0, strapW, STRAP + 1, STRAP_GREY)
        cells(NUB + (bodyW - strapW) / 2, STRAP + bodyH - 1, strapW, STRAP + 1, STRAP_GREY)
        cells(NUB + 2, STRAP, bodyW - 4, bodyH, Color.WHITE)
        cells(NUB + 1, STRAP + 1, bodyW - 2, bodyH - 2, Color.WHITE)
        cells(NUB, STRAP + 2, bodyW, bodyH - 4, Color.WHITE)
        cells(NUB + FRAME, STRAP + FRAME, inW, inH, Color.BLACK)
        val shotX = (NUB + FRAME) * CELL + (inW * CELL - shot.width) / 2
        val shotY = (STRAP + FRAME) * CELL + (inH * CELL - shot.height) / 2
        canvas.drawBitmap(shot, shotX.toFloat(), shotY.toFloat(), null)

        for (key in Key.entries) {
            val y = Math.round((shotY + key.at * shot.height) / CELL)
            val x = if (key.left) 0 else NUB + bodyW
            cells(x, y - 2, NUB, 4, Color.WHITE)
            buttonAt[key] = (if (key.left) 0 else art.width) to y * CELL
        }
        shot.recycle()

        contentDescription = "The Nacashu watchapp on a Pebble Time 2. Numbered buttons: " +
            Key.entries.joinToString { "${it.ordinal + 1} ${it.name}" }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        // Leave room for the markers either side, and don't let the watch get silly on a tablet.
        val room = minOf(width - 2 * markerRoom(), (MAX_WATCH_DP * density).toInt())
        scale = (room / art.width).coerceAtLeast(1)
        setMeasuredDimension(width, art.height * scale + 2 * pad())
    }

    override fun onDraw(canvas: Canvas) {
        // A black card, like the app icon's background.
        paint.color = Color.BLACK
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), 12 * density, 12 * density, paint)

        val left = (width - art.width * scale) / 2
        val top = pad()
        canvas.drawBitmap(art, null, Rect(left, top, left + art.width * scale, top + art.height * scale), artPaint)

        // A numbered marker beside each button.
        val r = MARKER_DP * density
        val gap = GAP_DP * density + r
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = r * 1.2f
        paint.isFakeBoldText = true
        for ((key, at) in buttonAt) {
            val x = left + at.first * scale + if (key.left) -gap else gap
            val y = top + at.second * scale.toFloat()
            paint.color = accent
            canvas.drawCircle(x, y, r, paint)
            paint.color = Color.WHITE
            canvas.drawText((key.ordinal + 1).toString(), x, y - (paint.ascent() + paint.descent()) / 2, paint)
        }
    }

    private fun pad() = (8 * density).toInt()

    /** Space beside a button for its marker. */
    private fun markerRoom() = ((GAP_DP + 2 * MARKER_DP) * density).toInt()

    /** The buttons in the order Help.WATCHAPP numbers them: which side each is on, and how far down the screen. */
    private enum class Key(val left: Boolean, val at: Float) {
        // Lined up with the labels the watchapp draws next to each button.
        UP(left = false, at = 0.18f),
        SELECT(left = false, at = 0.5f),
        DOWN(left = false, at = 0.9f),
        BACK(left = true, at = 0.18f),
    }

    private companion object {
        // In art pixels: the grid size, then sizes in cells.
        const val CELL = 6
        const val FRAME = 2
        const val NUB = 2
        const val STRAP = 3
        // In dp.
        const val MARKER_DP = 12
        const val GAP_DP = 6
        const val MAX_WATCH_DP = 320
        val STRAP_GREY = Color.rgb(0x55, 0x55, 0x55)
    }
}
