package com.restsync.demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/** Offline diagram used ONLY by the explicitly labelled recording simulation. */
internal class DemoRouteMap(context: Context) : View(context) {
    var progress: Float = 0f
        set(value) { field = if (value.isFinite()) value.coerceIn(0f, 1f) else 0f; invalidate() }
    var selectedStop: Boolean = false
        set(value) { field = value; invalidate() }
    var resumed: Boolean = false
        set(value) { field = value; invalidate() }
    var destination: String = "부산역"
        set(value) { field = value.ifBlank { "부산역" }; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val normal = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif-medium", Typeface.BOLD)
    private val route = Path()
    private val firstLeg = Path()
    private val secondLeg = Path()
    private val traveled = Path()
    private val clip = Path()
    private val scenery = ArrayList<Path>()
    private val roads = ArrayList<Path>()
    private val routeMeasure = PathMeasure()
    private val carPosition = FloatArray(2)
    private val carTangent = FloatArray(2)
    private var mapTop = 0f
    private var mapBottom = 0f
    private var startX = 0f
    private var startY = 0f
    private var stopX = 0f
    private var stopY = 0f
    private var endX = 0f
    private var endY = 0f
    private var stopFraction = .5f
    val stopProgress:Float get()=stopFraction
    private var unit = 1f

    init {
        minimumHeight = RestTheme.dp(context, 300)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "시연용 경로 미리보기. 서울에서 안성휴게소를 거쳐 부산역으로 이동하는 가상 경로입니다."
    }

    fun update(progress: Float, selectedStop: Boolean, resumed: Boolean, destination: String = "부산역") {
        this.progress = progress
        this.selectedStop = selectedStop
        this.resumed = resumed
        this.destination = destination
    }

    private fun dp(value: Float) = value * unit

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Keep text usable in both the wide map and the side-by-side demo panel.
        unit = min(resources.displayMetrics.density, w / 420f).coerceAtLeast(.65f)
        mapTop = dp(74f)
        mapBottom = max(mapTop + dp(80f), h - dp(130f))
        val mapHeight = mapBottom - mapTop
        fun x(f: Float) = w * f
        fun y(f: Float) = mapTop + mapHeight * f
        startX = x(.13f); startY = y(.17f)
        stopX = x(.49f); stopY = y(.48f)
        endX = x(.85f); endY = y(.81f)
        firstLeg.reset()
        firstLeg.moveTo(startX, startY)
        firstLeg.cubicTo(x(.23f), y(.20f), x(.24f), y(.35f), x(.34f), y(.32f))
        firstLeg.cubicTo(x(.42f), y(.30f), x(.40f), y(.48f), stopX, stopY)
        secondLeg.reset()
        secondLeg.moveTo(stopX, stopY)
        secondLeg.cubicTo(x(.61f), y(.48f), x(.57f), y(.71f), x(.70f), y(.67f))
        secondLeg.cubicTo(x(.78f), y(.64f), x(.75f), y(.79f), endX, endY)
        route.reset(); route.addPath(firstLeg); route.addPath(secondLeg)
        // A single contour makes the moving vehicle continuous through the stop.
        route.reset(); route.moveTo(startX, startY)
        route.cubicTo(x(.23f), y(.20f), x(.24f), y(.35f), x(.34f), y(.32f))
        route.cubicTo(x(.42f), y(.30f), x(.40f), y(.48f), stopX, stopY)
        route.cubicTo(x(.61f), y(.48f), x(.57f), y(.71f), x(.70f), y(.67f))
        route.cubicTo(x(.78f), y(.64f), x(.75f), y(.79f), endX, endY)
        routeMeasure.setPath(route, false)
        stopFraction = PathMeasure(firstLeg, false).length / routeMeasure.length.coerceAtLeast(1f)
        roads.clear()
        repeat(4) { index ->
            roads += Path().apply {
                moveTo(x(-.1f), y(.12f + index * .22f))
                cubicTo(x(.35f), y(-.03f + index * .21f), x(.60f), y(.42f + index * .16f), x(1.1f), y(.15f + index * .22f))
            }
        }
        repeat(4) { index ->
            roads += Path().apply {
                moveTo(x(.05f + index * .29f), y(-.1f))
                cubicTo(x(.21f + index * .22f), y(.30f), x(-.05f + index * .27f), y(.65f), x(.10f + index * .26f), y(1.1f))
            }
        }
        scenery.clear()
        scenery += Path().apply {
            moveTo(x(.55f), y(.02f)); cubicTo(x(.80f), y(-.07f), x(.98f), y(.06f), x(.91f), y(.30f))
            cubicTo(x(.86f), y(.47f), x(.57f), y(.34f), x(.55f), y(.02f)); close()
        }
        scenery += Path().apply {
            moveTo(x(.03f), y(.62f)); cubicTo(x(.20f), y(.48f), x(.39f), y(.65f), x(.31f), y(.95f))
            lineTo(x(.04f), y(.98f)); close()
        }
        clip.reset(); clip.addRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), dp(24f), dp(24f), Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        canvas.save(); canvas.clipPath(clip)
        canvas.drawColor(RestTheme.snow)
        paint.style = Paint.Style.FILL
        paint.color = 0xFFECEAF8.toInt()
        scenery.forEach { canvas.drawPath(it, paint) }
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(16f); paint.color = Color.WHITE
        roads.forEach { canvas.drawPath(it, paint) }
        paint.strokeWidth = dp(1f); paint.color = 0xFFE7E3F2.toInt()
        roads.forEach { canvas.drawPath(it, paint) }

        paint.strokeWidth = dp(24f); paint.color = Color.WHITE
        canvas.drawPath(route, paint)
        paint.strokeWidth = dp(14f); paint.color = 0xFFD4CDF7.toInt()
        canvas.drawPath(route, paint)
        if (resumed) {
            paint.color = RestTheme.purple
            canvas.drawPath(secondLeg, paint)
        } else {
            paint.color = RestTheme.purple
            canvas.drawPath(route, paint)
        }
        val fraction = if (resumed) stopFraction + (1f - stopFraction) * progress else progress
        traveled.reset()
        routeMeasure.getSegment(0f, routeMeasure.length * fraction, traveled, true)
        paint.strokeWidth = dp(5f); paint.color = 0xFFA89AEF.toInt()
        canvas.drawPath(traveled, paint)

        paint.style = Paint.Style.FILL
        marker(canvas, startX, startY, RestTheme.deepPurple, dp(10f))
        label(canvas, "서울", startX, startY - dp(27f), 22f, RestTheme.ink, Paint.Align.CENTER)
        marker(canvas, endX, endY, RestTheme.purple, dp(13f))
        label(canvas, destination, min(endX + dp(20f), width - dp(22f)), endY + dp(38f), 25f, RestTheme.ink, Paint.Align.RIGHT, true)
        if (selectedStop || resumed) drawRestStop(canvas)
        else label(canvas, "서울 → $destination", width * .55f, mapTop + dp(30f), 23f, RestTheme.muted, Paint.Align.CENTER)

        if (routeMeasure.getPosTan(routeMeasure.length * fraction, carPosition, carTangent)) {
            canvas.save(); canvas.translate(carPosition[0], carPosition[1])
            paint.color = 0x336C5CE7; canvas.drawCircle(0f, 0f, dp(30f), paint)
            paint.color = Color.WHITE; canvas.drawCircle(0f, 0f, dp(24f), paint)
            paint.color = RestTheme.purple; canvas.drawCircle(0f, 0f, dp(20f), paint)
            canvas.rotate(Math.toDegrees(atan2(carTangent[1], carTangent[0]).toDouble()).toFloat() + 90f)
            val arrow = Path().apply { moveTo(0f, -dp(13f)); lineTo(dp(11f), dp(11f)); lineTo(0f, dp(6f)); lineTo(-dp(11f), dp(11f)); close() }
            paint.color = Color.WHITE; canvas.drawPath(arrow, paint)
            canvas.restore()
        }

        // A permanently visible caption prevents this schematic from posing as native navigation.
        paint.color = Color.WHITE; canvas.drawRect(0f, 0f, width.toFloat(), dp(68f), paint)
        label(canvas, "시연용 경로 미리보기", dp(23f), dp(42f), 22f, RestTheme.deepPurple, Paint.Align.LEFT, true)
        paint.color = RestTheme.line; canvas.drawRect(0f, dp(67f), width.toFloat(), dp(68f), paint)
        val bottom = height - dp(116f)
        paint.color = Color.WHITE
        canvas.drawRoundRect(dp(16f), bottom, width - dp(16f), height - dp(16f), dp(20f), dp(20f), paint)
        val title = if (resumed) "휴식 완료 · $destination 안내" else if (selectedStop) "안성휴게소 경유" else "$destination 향해 출발"
        label(canvas, title, dp(36f), bottom + dp(39f), 25f, RestTheme.ink, Paint.Align.LEFT, true)
        val detail = if (resumed) "휴게소 → 목적지 · 가상 경로" else if (selectedStop) "서울 → 안성휴게소 → $destination" else "서울 → $destination · 가상 경로"
        label(canvas, detail, dp(36f), bottom + dp(73f), 19f, RestTheme.muted, Paint.Align.LEFT)
        canvas.restore()
    }

    private fun marker(canvas: Canvas, x: Float, y: Float, color: Int, radius: Float) {
        paint.color = Color.WHITE; canvas.drawCircle(x, y, radius + dp(5f), paint)
        paint.color = color; canvas.drawCircle(x, y, radius, paint)
    }

    private fun drawRestStop(canvas: Canvas) {
        val boxWidth = min(dp(210f), width * .48f)
        val left = (stopX - boxWidth / 2).coerceIn(dp(12f), width - boxWidth - dp(12f))
        val top = stopY - dp(105f)
        paint.color = Color.WHITE
        canvas.drawRoundRect(left, top, left + boxWidth, top + dp(62f), dp(15f), dp(15f), paint)
        label(canvas, "안성휴게소", left + boxWidth / 2, top + dp(39f), 23f, RestTheme.deepPurple, Paint.Align.CENTER, true)
        marker(canvas, stopX, stopY, RestTheme.purple, dp(17f))
        // Coffee cup drawn with primitives for consistent rendering without an emoji font.
        paint.color = Color.WHITE; paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(2f)
        canvas.drawRoundRect(stopX - dp(7f), stopY - dp(5f), stopX + dp(5f), stopY + dp(6f), dp(2f), dp(2f), paint)
        canvas.drawArc(stopX + dp(2f), stopY - dp(4f), stopX + dp(10f), stopY + dp(4f), -90f, 180f, false, paint)
        canvas.drawLine(stopX - dp(9f), stopY + dp(9f), stopX + dp(9f), stopY + dp(9f), paint)
        paint.style = Paint.Style.FILL
    }

    private fun label(canvas: Canvas, value: String, x: Float, baseline: Float, size: Float, color: Int, align: Paint.Align, strong: Boolean = false) {
        paint.style = Paint.Style.FILL; paint.color = color; paint.textSize = dp(size)
        paint.typeface = if (strong) bold else normal; paint.textAlign = align
        canvas.drawText(value, x, baseline, paint)
    }
}
