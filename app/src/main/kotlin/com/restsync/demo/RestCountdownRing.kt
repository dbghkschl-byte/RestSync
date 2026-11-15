package com.restsync.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/** Large circular visual countdown for the parked rest state. */
internal class RestCountdownRing(context: Context) : View(context) {
    var progress: Float = 1f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = minOf(View.MeasureSpec.getSize(widthMeasureSpec), View.MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }
    override fun onDraw(canvas: Canvas) {
        val radius = width * 0.38f
        val cx = width / 2f
        val cy = height / 2f
        val bounds = RectF(cx-radius, cy-radius, cx+radius, cy+radius)
        paint.strokeWidth = width * 0.055f
        paint.color = RestTheme.line
        canvas.drawArc(bounds, -90f, 360f, false, paint)
        paint.color = RestTheme.purple
        canvas.drawArc(bounds, -90f, 360f*progress, false, paint)
    }
}
