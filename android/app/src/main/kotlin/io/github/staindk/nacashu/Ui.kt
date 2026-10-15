package io.github.staindk.nacashu

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable

/** A text colour from the theme. Plain TextViews otherwise default to a dim grey. */
fun Context.themeColor(attr: Int): ColorStateList? {
    val attrs = theme.obtainStyledAttributes(intArrayOf(attr))
    val color = attrs.getColorStateList(0)
    attrs.recycle()
    return color
}

/** A rounded grey card behind a group of views. */
fun Context.card() = GradientDrawable().apply {
    cornerRadius = 12 * resources.displayMetrics.density
    setColor(0x1F808080)
}
