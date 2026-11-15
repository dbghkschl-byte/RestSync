package com.restsync.demo

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.NumberPicker
import android.widget.ScrollView

/** Large controls bounded by RestSync's own window, including Pleos split view. */
internal object RestDialog {
    fun create(
        activity: Activity,
        title: String,
        body: View,
        negative: String = "닫기",
        positive: String? = null,
        onPositive: () -> Unit = {}
    ): Dialog {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val panel = RestTheme.container(activity, 24, RestTheme.snow, 24)
        panel.addView(RestTheme.label(activity, title, 32f, RestTheme.ink, true), RestTheme.margins(activity, 18))
        panel.addView(ScrollView(activity).apply {
            isFillViewport = true
            clipToPadding = false
            addView(body)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        val narrow = activity.resources.configuration.screenWidthDp < 620
        val actions = LinearLayout(activity).apply {
            orientation = if (narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        fun action(label: String, primary: Boolean, click: () -> Unit) {
            val params = if (narrow || positive == null) LinearLayout.LayoutParams(-1, -2)
                else LinearLayout.LayoutParams(0, -2, 1f)
            if (actions.childCount > 0) {
                if (narrow) params.topMargin = RestTheme.dp(activity, 12)
                else params.marginStart = RestTheme.dp(activity, 16)
            }
            actions.addView(RestTheme.button(activity, label, primary, click), params)
        }
        action(negative, false) { dialog.dismiss() }
        if (positive != null) action(positive, true) { dialog.dismiss(); onPositive() }
        panel.addView(actions, RestTheme.margins(activity, 0, 18))
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(.35f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        return dialog
    }

    fun show(activity: Activity, dialog: Dialog) {
        dialog.show()
        val decor = activity.window.decorView
        val width = decor.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
        val height = decor.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
        dialog.window?.setLayout((width * .94f).toInt(), (height * .88f).toInt())
    }

    fun enlargePicker(picker: NumberPicker) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            picker.textSize = 40f * picker.resources.displayMetrics.scaledDensity
        }
        // The editable value also stays readable on Android 9 (minSdk 28).
        for (index in 0 until picker.childCount) {
            (picker.getChildAt(index) as? android.widget.EditText)?.apply {
                textSize = 40f
                imeOptions = imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            }
        }
    }
}
