package com.restsync.demo

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** RestSync design system: purple / lilac / snowy white, optimized for large touch targets. */
internal object RestTheme {
    val purple = Color.rgb(108, 92, 231)
    val deepPurple = Color.rgb(73, 58, 159)
    val lavender = Color.rgb(142, 127, 255)
    val palePurple = Color.rgb(239, 235, 255)
    val snow = Color.rgb(250, 249, 255)
    val white = Color.WHITE
    val ink = Color.rgb(30, 27, 67)
    val muted = Color.rgb(115, 112, 139)
    val line = Color.rgb(231, 227, 247)
    val green = Color.rgb(44, 152, 105) // Only for semantic improved traffic state
    val red = Color.rgb(202, 82, 96) // Only for warnings

    fun dp(context: Context, n: Int): Int = (n * context.resources.displayMetrics.density + .5f).toInt()
    fun rounded(context: Context, color: Int, radius: Int = 20, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(context, radius).toFloat()
            if (border != null) setStroke(dp(context, 1), border)
        }

    fun gradient(context: Context, start: Int = lavender, end: Int = purple, radius: Int = 22): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(start, end)).apply {
            cornerRadius = dp(context, radius).toFloat()
        }

    fun label(context: Context, value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false): TextView =
        TextView(context).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.BOLD)
                else Typeface.create("sans-serif", Typeface.NORMAL)
            includeFontPadding = false
        }

    fun button(context: Context, value: String, primary: Boolean = true, click: () -> Unit): TextView =
        label(context, value, 24f, if (primary) white else deepPurple, true).apply {
            gravity = Gravity.CENTER
            background = if (primary) gradient(context) else rounded(context, palePurple, 20, line)
            setPadding(dp(context, 20), dp(context, 13), dp(context, 20), dp(context, 13))
            isClickable = true
            isFocusable = true
            contentDescription = value
            setOnClickListener { click() }
            minHeight = dp(context, 72)
            elevation = if (primary) dp(context, 2).toFloat() else 0f
        }

    fun container(context: Context, padding: Int = 18, backgroundColor: Int = white, radius: Int = 22): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, padding), dp(context, padding), dp(context, padding), dp(context, padding))
            background = rounded(context, backgroundColor, radius, line)
        }

    fun hairline(context: Context): View = View(context).apply { setBackgroundColor(line) }
    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    fun spacer(context: Context, h: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(context, h))
    }
    fun addGap(parent: LinearLayout, context: Context, h: Int) {
        parent.addView(spacer(context, h))
    }
    fun margins(context: Context, bottom: Int = 12, top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, bottom)
            topMargin = dp(context, top)
        }
    fun metric(context: Context, label: String, value: String, subtitle: String? = null): LinearLayout =
        container(context, padding = 16, backgroundColor = palePurple).apply {
            addView(RestTheme.label(context, label, 13f, muted))
            addGap(this, context, 7)
            addView(RestTheme.label(context, value, 27f, ink, true))
            if (!subtitle.isNullOrBlank()) {
                addGap(this, context, 5)
                addView(RestTheme.label(context, subtitle, 12f, muted))
            }
        }
}
