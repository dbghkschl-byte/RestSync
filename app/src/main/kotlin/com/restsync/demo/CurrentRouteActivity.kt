package com.restsync.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** Read an existing destination without replacing the driver's route. */
internal class CurrentRouteActivity : Activity(), NaviHelperEventListener {
    private val ui = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var connected = false
    private var waitingForNativeSearch = false
    private var querying = false
    private lateinit var status: TextView
    private lateinit var result: LinearLayout
    private lateinit var keyword: EditText
    private val timeout = Runnable {
        querying = false
        if (!isDestroyed && !isFinishing) status.text = "내비 응답을 기다리고 있어요. Pleos에서 목적지를 설정한 뒤 ‘경로 다시 확인’을 눌러 주세요."
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val column = RestTheme.container(this,22,RestTheme.snow,0)
        column.addView(RestTheme.button(this,"‹ 돌아가기",false) { finish() },RestTheme.margins(this,22))
        column.addView(RestTheme.label(this,"내비의 여정을 이어갈게요",28f,RestTheme.ink,true),RestTheme.margins(this,10))
        column.addView(RestTheme.label(this,"이미 안내 중인 목적지를 가져오면 다시 입력할 필요가 없어요.",16f,RestTheme.muted),RestTheme.margins(this,22))
        status = RestTheme.label(this,"Pleos 내비에 연결하는 중이에요…",16f,RestTheme.deepPurple,true)
        column.addView(status,RestTheme.margins(this,16))
        result = RestTheme.container(this,18)
        column.addView(result,RestTheme.margins(this,16))
        column.addView(RestTheme.button(this,"경로 다시 확인",false) { refreshRoute() },RestTheme.margins(this,24))
        column.addView(RestTheme.label(this,"아직 목적지를 정하지 않았다면",20f,RestTheme.ink,true),RestTheme.margins(this,12))
        keyword = EditText(this).apply {
            hint="어디로 가세요? · 예: 부산역"; isSingleLine=true; textSize=18f
            imeOptions=android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }
        column.addView(keyword,RestTheme.margins(this,12))
        column.addView(RestTheme.button(this,"Pleos에서 목적지 찾기") {
            if (!connected) { status.text="내비 연결 후 다시 눌러 주세요."; return@button }
            runCatching {
                waitingForNativeSearch = true
                helper?.requestNavigationScreen(NavigationScreenRequest.SearchPlace(keyword.text.toString().trim()))
                status.text="Pleos에서 목적지를 선택하고 안내를 시작한 뒤 RestSync로 돌아와 주세요."
            }.onFailure { waitingForNativeSearch=false; status.text="Pleos 검색 화면을 열지 못했어요. 기본 내비에서 직접 설정한 뒤 경로를 다시 확인해 주세요." }
        },RestTheme.margins(this,10))
        column.addView(RestTheme.label(this,"이 화면의 내비 검색에는 카카오 장소 검색 키가 필요하지 않아요. 휴게소 추천의 외부 길찾기 설정은 별도예요.",13f,RestTheme.muted),RestTheme.margins(this,18))
        setContentView(ScrollView(this).apply { addView(column) })
        connect()
    }

    private fun connect() {
        ui.postDelayed(timeout,10_000L)
        runCatching {
            val candidate=NaviHelper(this); helper=candidate
            candidate.initialize(onConnected={ ui.post {
                if (isDestroyed || isFinishing) return@post
                connected=true
                runCatching { candidate.addListener(this); refreshRoute() }.onFailure { status.text="내비 정보 권한을 확인해 주세요." }
            } },onFailed={ ui.post {
                if (!isDestroyed && !isFinishing) { ui.removeCallbacks(timeout); status.text="Pleos 연결에 실패했어요. 기본 내비를 실행한 뒤 이 화면을 다시 열어 주세요." }
            } })
        }.onFailure { ui.removeCallbacks(timeout); status.text="Pleos 서비스에 연결할 수 없어요." }
    }

    private fun refreshRoute() {
        if (!connected || querying) return
        result.removeAllViews()
        querying=true; status.text="현재 목적지를 확인하고 있어요…"
        ui.removeCallbacks(timeout); ui.postDelayed(timeout,10_000L)
        runCatching { helper?.getDestinationInfo() }.onFailure {
            querying=false; ui.removeCallbacks(timeout); status.text="목적지를 읽지 못했어요. 내비 정보 권한을 확인해 주세요."
        }
    }

    override fun onResume() {
        super.onResume()
        if (waitingForNativeSearch) { waitingForNativeSearch=false; ui.postDelayed({ refreshRoute() },600L) }
    }

    override fun onDestinationInfo(destinationInfo: DestinationInfo) { ui.post {
        if (isDestroyed || isFinishing || !querying) return@post
        querying=false; ui.removeCallbacks(timeout)
        val d=destinationInfo
        if (!Coordinate(d.latitude,d.longitude).valid || d.name.isBlank() || d.remainTime<=0) {
            status.text="안내 중인 목적지를 찾지 못했어요. 아래에서 Pleos 목적지를 설정해 주세요."
            return@post
        }
        status.text="현재 내비 목적지를 확인했어요"
        result.removeAllViews()
        result.addView(RestTheme.label(this,d.name,25f,RestTheme.ink,true),RestTheme.margins(this,8))
        result.addView(RestTheme.label(this,d.address,15f,RestTheme.muted),RestTheme.margins(this,18))
        result.addView(RestTheme.button(this,"이 경로로 휴식 계획하기") {
            val store=SessionStore(this)
            if(store.read().stage in setOf("RESTING","DEPARTURE")) { status.text="진행 중인 휴식을 마친 뒤 경로를 다시 연결해 주세요."; return@button }
            store.write(JourneyPolicy.connect(store.read(),d.name,d.latitude,d.longitude,d.remainTime.toLong(),System.currentTimeMillis()).copy(remainingDistanceMeters=d.remainDistance.toLong().coerceAtLeast(0)))
            RestAlerts.showCompanionShortcut(this)
            setResult(RESULT_OK,Intent().putExtra("connectedRoute",true))
            finish()
        })
    } }

    override fun onError(errorCode: NaviErrorCode) { ui.post {
        if (!isDestroyed && !isFinishing) { querying=false; ui.removeCallbacks(timeout); status.text="내비 정보 확인 실패 · $errorCode\n목적지 안내를 시작한 뒤 다시 확인해 주세요." }
    } }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        helper?.let { h -> if (connected) runCatching { h.removeListener(this) }; runCatching { h.release() } }
        super.onDestroy()
    }
}
