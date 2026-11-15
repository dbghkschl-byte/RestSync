package com.restsync.demo

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import java.lang.ref.WeakReference

/** Recording-only graphics in our own windows. Never dispatches input or changes app state. */
class RestSyncApplication : Application(), Application.ActivityLifecycleCallbacks {
    private var resumed = WeakReference<Activity>(null)
    private var pointer: DemoPointer? = null
    private var previousX = -1f
    private var previousY = -1f

    override fun onCreate() {
        super.onCreate()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        registerActivityLifecycleCallbacks(this)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != "com.restsync.demo.DEMO_POINTER") return
                val activity = resumed.get() ?: return
                val root = (if (Build.VERSION.SDK_INT >= 29) {
                    WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }
                        ?: activity.window.decorView
                } else activity.window.decorView) as? ViewGroup ?: return
                val position = IntArray(2).also { root.getLocationOnScreen(it) }
                val screenX = intent.getIntExtra("x", -1).toFloat()
                val screenY = intent.getIntExtra("y", -1).toFloat()
                val x = screenX - position[0]
                val y = screenY - position[1]
                if (x !in 0f..root.width.toFloat() || y !in 0f..root.height.toFloat()) return
                pointer?.dispose()
                val fromX = if (previousX >= 0) previousX - position[0] else root.width * .75f
                val fromY = if (previousY >= 0) previousY - position[1] else root.height * .8f
                pointer = DemoPointer(root, fromX, fromY, x, y).also { it.start() }
                previousX = screenX
                previousY = screenY
            }
        }
        val filter = IntentFilter("com.restsync.demo.DEMO_POINTER")
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(receiver, filter)
    }

    override fun onActivityResumed(activity: Activity) { resumed = WeakReference(activity) }
    override fun onActivityPaused(activity: Activity) {
        if (resumed.get() === activity) { pointer?.dispose(); pointer = null; resumed.clear() }
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    private class DemoPointer(
        private val root: ViewGroup, private val fromX: Float, private val fromY: Float,
        private val toX: Float, private val toY: Float
    ) : View(root.context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var progress = 0f
        private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
        }
        fun start() {
            layout(0, 0, root.width, root.height)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            root.overlay.add(this)
            animator.start()
            postDelayed({ dispose() }, 1500)
        }
        fun dispose() { animator.cancel(); root.overlay.remove(this) }
        override fun onDraw(canvas: Canvas) {
            val move = (progress / .36f).coerceIn(0f, 1f)
            val eased = move * move * (3 - 2 * move)
            val x = fromX + (toX - fromX) * eased
            val y = fromY + (toY - fromY) * eased
            val scale = resources.displayMetrics.density.coerceAtLeast(1f)
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            if (move == 1f) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f
                paint.color = Color.rgb(153, 109, 255)
                paint.alpha = (255 * (1f - progress)).toInt()
                canvas.drawCircle(0f, 0f, 14f + 30f * progress, paint)
            }
            val arrow = Path().apply {
                moveTo(0f, 0f); lineTo(2f, 38f); lineTo(12f, 28f)
                lineTo(20f, 45f); lineTo(28f, 41f); lineTo(20f, 24f)
                lineTo(34f, 23f); close()
            }
            paint.alpha = 255; paint.style = Paint.Style.FILL; paint.color = Color.rgb(114, 77, 240)
            canvas.drawPath(arrow, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = Color.WHITE
            canvas.drawPath(arrow, paint)
            canvas.restore()
        }
    }
}
