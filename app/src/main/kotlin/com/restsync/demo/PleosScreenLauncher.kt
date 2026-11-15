package com.restsync.demo

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** A fresh connection per handoff. A void SDK call is not a success acknowledgement. */
internal class PleosScreenLauncher(
    private val activity: Activity,
    private val beforeRequest: () -> Unit,
    private val onFailure: (String) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var generation = 0
    private var pending = false
    private var sent = false
    private var listener: NaviHelperEventListener? = null

    fun open() {
        if (pending || activity.isFinishing || activity.isDestroyed) return
        release()
        pending = true
        val request = generation
        val requestListener = object : NaviHelperEventListener {
            override fun onError(errorCode: NaviErrorCode) {
                handler.post {
                    if (pending && generation == request) fail("Pleos 화면 요청 오류: $errorCode")
                }
            }
        }
        listener = requestListener
        handler.postDelayed({
            if (pending && generation == request) {
                fail(if (sent) "화면 전환을 확인하지 못했습니다. 내비 앱 실행 또는 NAVI_CUSTOM_MAP 권한을 확인해 주세요."
                     else "Pleos 서비스 연결 시간이 초과됐습니다.")
            }
        }, 10_000L)
        runCatching {
            // Preserve the foreground Activity attribution for the user-initiated request.
            val candidate = NaviHelper(activity)
            helper = candidate
            candidate.initialize(onConnected = {
                handler.post {
                    if (generation != request || !pending || activity.isDestroyed) return@post
                    runCatching {
                        candidate.addListener(requestListener)
                        beforeRequest()
                        sent = true
                        candidate.requestNavigationScreen(NavigationScreenRequest.Foreground)
                        // Some emulator/navigation builds accept the SDK command without
                        // bringing an Activity forward. Launch the system map entry point
                        // only if RestSync has not left the foreground in the meantime.
                        handler.postDelayed({
                            if (pending && generation == request) openSystemMap()
                        }, 1_500L)
                    }.onFailure { fail("Pleos 화면 요청 실패: ${it.javaClass.simpleName}") }
                }
            }, onFailed = { error ->
                handler.post {
                    if (generation == request && pending) fail("Pleos 연결 실패: ${error.javaClass.simpleName}")
                }
            })
        }.onFailure { fail("Pleos 초기화 실패: ${it.javaClass.simpleName}") }
    }

    fun onPause() {
        // The screen API has no success callback. Leaving this activity only cancels
        // the watchdog; it does not certify which app became foreground.
        if (sent) {
            pending = false
            handler.removeCallbacksAndMessages(null)
        } else release() // Never navigate from a late connection callback in background.
    }

    private fun fail(message: String) {
        release()
        if (!activity.isFinishing && !activity.isDestroyed) onFailure(message)
    }

    fun release() {
        generation++
        pending = false
        sent = false
        handler.removeCallbacksAndMessages(null)
        helper?.let {
            listener?.let { registered -> runCatching { it.removeListener(registered) } }
            runCatching { it.release() }
        }
        helper = null
        listener = null
    }

    fun openSystemMap(): Boolean = runCatching {
        // A launch-only fallback; no geo URI and no replacement of the active route.
        activity.startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MAPS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
