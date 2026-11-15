package com.restsync.demo

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.NumberPicker
import android.widget.TextView
import android.widget.Toast
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.helper.NaviHelper
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * RestSync: phone-style responsive screens + Pleos landscape vehicle dashboard.
 * Demo voice is an explicit simulation. Real Gleo requires App Schema approval.
 * Vehicle P gear and HVAC controls are NOT simulated as real vehicle operations.
 */
open class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { SessionStore(this) }
    private lateinit var session: RestSession
    private var speech: TextToSpeech? = null
    private var ttsReady = false
    private var lastSpokenAlert = -1L
    private var countLabel: TextView? = null
    private var countRing: RestCountdownRing? = null
    private var stageBeforeNavigation = "PLAN"
    private var suppressDemoAutoNavi = false
    private var mapPreview: RestMapPreview? = null
    private var navigateAfterResume = false
    private var autoContinueScheduled=false
    private var recordingSignature=""
    private var alarmTone: android.media.ToneGenerator?=null
    private val screenLauncher: PleosScreenLauncher by lazy {
        PleosScreenLauncher(this, { showNavigationShortcut() }, { message ->
            session = store.read().copy(statusMessage = message)
            store.write(session)
            render()
            RestAlerts.showCompanionShortcut(this)
            AlertDialog.Builder(this).setTitle("내비 화면 전환 확인 필요")
                .setMessage(message + "\n기본 지도 앱을 직접 열거나 앱 내부 미리보기를 계속 사용할 수 있어요.")
                .setNegativeButton("앱에서 계속", null)
                .setPositiveButton("기본 지도 앱 열기") { _, _ ->
                    showNavigationShortcut()
                    if (!screenLauncher.openSystemMap()) {
                        Toast.makeText(this, "실행 가능한 기본 지도 앱이 없어요. Pleos 내비 아이콘으로 열어 주세요.", Toast.LENGTH_LONG).show()
                    }
                }.show()
        })
    }
    private val demoNames = listOf("안성휴게소", "천안삼거리휴게소", "죽암휴게소")
    private val demoApproach = listOf(15L, 28L, 40L)
    private val demoDrive = listOf(35L, 32L, 28L)

    private val ticker = object : Runnable {
        override fun run() {
            val current = store.read()
            if (current != session) {
                session = current
                if(ensureWindow()) return
                render()
            }
            if (session.stage == "RESTING") {
                val decision = RestLogic.calculate(session, System.currentTimeMillis())
                countLabel?.text = formatCountdown(decision.nextAlertMs - System.currentTimeMillis())
                val total = (decision.nextAlertMs - session.startedAtMs).coerceAtLeast(1L)
                countRing?.progress = ((decision.nextAlertMs - System.currentTimeMillis()).toFloat() / total).coerceIn(0f,1f)
                // Show the completion screen immediately if RestSync is foreground.
                // The foreground monitor handles notification + TTS when the app is backgrounded.
                if (decision.shouldNotifyNow && session.alertedForMs != decision.nextAlertMs) {
                    showDepartureAlarm()
                }
            }
            advanceRecordingTour()
            val recording=RecordingTour.read(this@MainActivity)
            val signature="${recording.active}:${recording.paused}:${recording.step}"
            if(signature!=recordingSignature) {recordingSignature=signature;if(!isFinishing)render()}
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = RestTheme.snow
        window.navigationBarColor = RestTheme.deepPurple
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        navigateAfterResume = savedInstanceState?.getBoolean("navigateAfterResume") ?: false
        session = store.read()
        if (session.deadlineMs <= 0L) {
            session = session.copy(deadlineMs = System.currentTimeMillis() + 2 * 60 * 60_000L)
            store.write(session)
        }
        RestAlerts.ensureChannel(this)
        speech = TextToSpeech(this, this)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            (this is FullScreenActivity || !FlowPolicy.wide(session.stage))) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 411)
        }
        handleGleoIntent(intent)
        if(ensureWindow()) return
        render()
        handler.post { consumeCompanionAction(intent) }
        handler.post {consumeRecordingAction(intent)}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        session = store.read()
        handleGleoIntent(intent)
        if(ensureWindow()) return
        render()
        handler.post { consumeCompanionAction(intent) }
        handler.post {consumeRecordingAction(intent)}
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) speech?.language = Locale.KOREAN
    }

    override fun onResume() {
        super.onResume()
        if(isFinishing) return
        session = store.read()
        if(navigateAfterResume) {
            navigateAfterResume=false
            handler.post { if(!isDestroyed) {openPleosNavigatorNotice();if(this is FullScreenActivity)finish()} }
            return
        }
        if(ensureWindow()) return
        render()
        handler.removeCallbacks(ticker); handler.post(ticker)
    }
    override fun onPostResume() {
        super.onPostResume()
        if (navigateAfterResume) {
            navigateAfterResume = false
            handler.post { if (!isFinishing && !isDestroyed) returnToNavigation() }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("navigateAfterResume", navigateAfterResume)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() { screenLauncher.onPause(); handler.removeCallbacks(ticker); super.onPause() }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); speech?.shutdown(); alarmTone?.release(); screenLauncher.release(); super.onDestroy() }
    @Suppress("DEPRECATION")
    override fun onBackPressed() { navigateBack() }

    private fun say(message: String) {
        if (ttsReady) speech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "restsync_response")
    }

    private fun voiceReply(message: String) {
        say(message)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun update(next: RestSession) {
        session = next
        store.write(next)
        if(!ensureWindow()) render()
    }

    private fun ensureWindow():Boolean {
        val needsWide=FlowPolicy.wide(session.stage)
        if(needsWide==(this is FullScreenActivity)) return false
        val target=if(needsWide) FullScreenActivity::class.java else MainActivity::class.java
        startActivity(Intent(this,target).putExtras(intent).setAction(intent.action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        if(this is FullScreenActivity) finish()
        return true
    }

    override fun onConfigurationChanged(config:android.content.res.Configuration) {
        super.onConfigurationChanged(config)
        if(!isFinishing && ::session.isInitialized) render()
    }

    private fun render() {
        if(isFinishing) return
        countLabel=null;countRing=null
        mapPreview?.dispose();mapPreview=null
        if(session.stage in setOf("RESTING","DEPARTURE")) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            setShowWhenLocked(true);setTurnScreenOn(true)
        } else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(RestTheme.snow)}
        root.addView(header(resources.configuration.screenWidthDp>=730),LinearLayout.LayoutParams(-1,dp(80)))
        if(RecordingTour.read(this).active)root.addView(RecordingTour.bar(this,
            onNext={recordingNext()},onRestart={restartRecordingTour()},onStop={stopRecordingTour()}))
        val screen=FlowScreens(this,session,
            setup={openDestinationSearch()},connect={openCurrentRoute()},resetRoute={resetDestination()},
            find={launchRoutePicker()},
            arrived={chooseArrivalRest()},navigate={openPleosNavigatorNotice()},extend={extendRest()},
            depart={finishRest()},acknowledge={acknowledgeDeparture()},resume={resumeNavigationAfterRest()},
            timer={countLabel=it})
        root.addView(screen.view(),LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        val recording=RecordingTour.read(this)
        if(session.stage=="FINISHED" && !autoContinueScheduled && !(recording.active && (recording.paused || recording.automated))) {
            autoContinueScheduled=true
            handler.postDelayed({
                val latest=RecordingTour.read(this)
                if(latest.active && latest.paused) {autoContinueScheduled=false;return@postDelayed}
                if(!isFinishing && hasWindowFocus() && store.read().stage=="FINISHED") resumeNavigationAfterRest()
            },3000L)
        }
    }

    private fun resetDestination() {
        update(session.copy(stage="PLAN",stopName="",stopLat=0.0,stopLon=0.0,startedAtMs=0,statusMessage="새 목적지를 선택해 주세요."))
    }

    private fun chooseArrivalRest() {
        val picker=NumberPicker(this).apply {minValue=1;maxValue=180;value=if(RecordingTour.read(this@MainActivity).active)30 else session.requestedMinutes.toInt().coerceIn(1,180);wrapSelectorWheel=false}
        RestDialog.enlargePicker(picker)
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER}
        body.addView(RestTheme.label(this,"${session.stopName.ifBlank {"휴식 장소"}} 도착을 수동으로 확인합니다.",24f,RestTheme.muted),RestTheme.margins(this,20))
        body.addView(picker,LinearLayout.LayoutParams(-1,dp(280)))
        body.addView(RestTheme.label(this,"분 · 위아래로 움직여 선택하세요",24f,RestTheme.deepPurple,true).apply {gravity=Gravity.CENTER},RestTheme.margins(this,12,12))
        val dialog=RestDialog.create(this,"몇 분 쉬실까요?",body,"아직 이동 중","선택한 시간으로 계속") {
                picker.clearFocus()
                session=session.copy(requestedMinutes=picker.value.toLong());store.write(session)
                confirmParked()
            }
        RestDialog.show(this,dialog)
    }

    private fun showDepartureAlarm() {
        val latest=store.read()
        val due=FlowPolicy.due(latest,System.currentTimeMillis())
        if(due.stage!="DEPARTURE")return
        RestAlerts.cancel(this);stopService(Intent(this,RestMonitorService::class.java))
        update(due);RestAlerts.showWakeup(this,due)
        if(RecordingTour.read(this).active)RecordingTour.setStep(this,RecordingTour.Step.ALARM)
        voiceReply("이제 일어날 시간이에요. 휴식을 마치고 안전하게 출발을 준비해 주세요.")
        runCatching {
            alarmTone?.release()
            alarmTone=android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM,80)
            alarmTone?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,1800)
        }
    }

    private fun acknowledgeDeparture() {
        val current=store.read()
        if(current.stage!="DEPARTURE")return
        alarmTone?.stopTone();RestAlerts.dismissWakeup(this)
        update(FlowPolicy.acknowledge(current))
        if(RecordingTour.read(this).active)RecordingTour.setStep(this,RecordingTour.Step.COMPLETE)
    }

    private fun openCurrentRoute() {
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this,CurrentRouteActivity::class.java),812)
    }

    private fun renderCompactCompanion(root: LinearLayout) {
        val scroll = ScrollView(this)
        val column = RestTheme.container(this, 16, RestTheme.snow, 0)
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val card = RestTheme.container(this, 18, RestTheme.white, 20)
        card.addView(RestTheme.label(this,"내 여정의 휴식 도우미",22f,RestTheme.ink,true),RestTheme.margins(this,12))
        card.addView(RestTheme.label(this,"목적지 · ${session.destinationName}",16f,RestTheme.ink),RestTheme.margins(this,8))
        card.addView(RestTheme.label(this,"도착 목표 ${time(session.deadlineMs)} · 희망 휴식 ${session.requestedMinutes}분",15f,RestTheme.muted),RestTheme.margins(this,12))
        if (session.stage == "WAYPOINT") {
            card.addView(RestTheme.label(this,session.stopName,21f,RestTheme.deepPurple,true),RestTheme.margins(this,12))
            card.addView(RestTheme.button(this,"도착했어요 · 정차 확인") { confirmParked() },RestTheme.margins(this,12))
        } else {
            card.addView(RestTheme.button(this,"휴게소 찾아줘") {
                if (session.source == "DEMO") demoSearch() else launchRoutePicker()
            },RestTheme.margins(this,12))
        }
        card.addView(RestTheme.button(this,"음성 요청 · 시연",false) { showDemoVoice() },RestTheme.margins(this,8))
        card.addView(RestTheme.button(this,"Pleos 내비 화면으로",false) { openPleosNavigatorNotice() },RestTheme.margins(this,8))
        column.addView(card,RestTheme.margins(this,12))
        if (session.stage == "SUGGESTIONS") suggestionsContent(column)
        column.addView(RestTheme.label(this,"시스템 윈도 바를 조절하면 내비와 나란히 볼 수 있어요. 전체 화면 내비에서는 RestSync 알림으로 돌아오세요.",14f,RestTheme.muted),RestTheme.margins(this,82))
    }

    private fun header(large: Boolean): View {
        val bar = RestTheme.row(this).apply {
            background = RestTheme.rounded(this@MainActivity, RestTheme.white, 0)
            setPadding(dp(18), 0, dp(20), 0)
        }
        val back = RestTheme.label(this, "‹", 36f, RestTheme.deepPurple, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { navigateBack() }
            contentDescription = "뒤로 가기"
        }
        bar.addView(back, LinearLayout.LayoutParams(dp(48), dp(58)))
        val logo = RestTheme.label(this, "❯", 30f, RestTheme.purple, true)
        bar.addView(logo, LinearLayout.LayoutParams(dp(35), -2))
        val title = RestTheme.label(this, "RestSync", if (large) 28f else 25f, RestTheme.ink, true)
        bar.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        val pill = RestTheme.button(this, "설정", false) { showQuickPanel() }
        bar.addView(pill, LinearLayout.LayoutParams(dp(120), dp(60)))
        return bar
    }

    private fun renderPhone(root: LinearLayout) {
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(25))
        }
        scroll.addView(column)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val scene = when (session.stage) { "SUGGESTIONS", "WAYPOINT" -> "route"; else -> "home" }
        val banner = FrameLayout(this).apply {
            background = RestTheme.rounded(this@MainActivity, RestTheme.palePurple, 24)
            clipToOutline = true
        }
        banner.addView(RestVisuals(this, scene), FrameLayout.LayoutParams(-1, -1))
        val brand = RestTheme.label(this, if (session.stage == "RESTING") "쉬는 동안에도 교통을 확인해요" else "쉼이 필요한 순간, RestSync", 18f, RestTheme.deepPurple, true).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = RestTheme.rounded(this@MainActivity, 0xDDFFFFFF.toInt(), 16)
        }
        banner.addView(brand, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.LEFT).apply {
            leftMargin = dp(14); topMargin = dp(12)
        })
        column.addView(banner, RestTheme.margins(this, 16).apply { height = dp(190) })
        contentForStage(column, compact = true)
        val nav = RestTheme.row(this).apply {
            background = RestTheme.rounded(this@MainActivity, RestTheme.white, 0)
            setPadding(dp(16), dp(6), dp(16), dp(6))
        }
        listOf("⌂\n홈", "⌁\n경로", "◷\n휴식", "◎\n음성").forEachIndexed { i, caption ->
            val item = RestTheme.label(this, caption, 12f, if (i == 0) RestTheme.purple else RestTheme.muted, true).apply {
                gravity = Gravity.CENTER
                setOnClickListener {
                    when (i) {
                        0 -> { if (session.stage == "RESTING") render() else update(session.copy(stage = "PLAN")) }
                        1 -> if (session.source == "DEMO") demoSearch() else launchRoutePicker()
                        2 -> if (session.stage == "RESTING") render() else Toast.makeText(this@MainActivity, "휴식 시작 후 확인할 수 있어요.", Toast.LENGTH_SHORT).show()
                        else -> showDemoVoice()
                    }
                }
            }
            nav.addView(item, LinearLayout.LayoutParams(0, dp(56), 1f))
        }
        root.addView(nav)
    }

    /**
     * Car UI is deliberately full-width by state. PLAN = welcome; real navigation opens
     * the system Pleos Navigation app rather than falsely labeling OSM as its map.
     */
    private fun renderCar(root: LinearLayout) {
        when (session.stage) {
            "PLAN" -> renderCarWelcome(root)
            "RESTING" -> renderCarRest(root)
            "FINISHED" -> renderCarFinished(root)
            else -> renderCarCompanion(root)
        }
    }

    private fun renderCarWelcome(root: LinearLayout) {
        val content = RestTheme.row(this).apply {
            setPadding(dp(23), dp(20), dp(23), dp(23))
            gravity = Gravity.CENTER_VERTICAL
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val left = RestTheme.container(this, 27, RestTheme.white, 24)
        left.addView(RestTheme.label(this, "RESTSYNC  /  PLEOS CONNECT", 13f, RestTheme.purple, true), RestTheme.margins(this, 23))
        left.addView(RestTheme.label(this, "안전한 여정을\n함께 계획해요", 37f, RestTheme.ink, true), RestTheme.margins(this, 20))
        left.addView(RestTheme.label(this, "목적지를 선택하고 운전을 시작하세요.\n필요한 순간 휴게소를 찾아 도착 약속을 지킬 수 있도록 도와드려요.", 16f, RestTheme.muted), RestTheme.margins(this, 29))
        val from = RestTheme.container(this, 16, RestTheme.snow, 15)
        from.addView(RestTheme.label(this, "출발지", 13f, RestTheme.muted))
        from.addView(RestTheme.label(this, session.originName, 19f, RestTheme.ink, true), RestTheme.margins(this, 14, 5))
        from.addView(RestTheme.label(this, "목적지", 13f, RestTheme.muted))
        from.addView(RestTheme.label(this, session.destinationName, 19f, RestTheme.ink, true), RestTheme.margins(this, 5, 5))
        from.setOnClickListener { openDestinationSearch() }
        left.addView(from, RestTheme.margins(this, 16))
        val metrics = RestTheme.row(this)
        val arrival = RestTheme.metric(this, "도착 목표", time(session.deadlineMs))
        arrival.setOnClickListener { chooseDeadline() }
        val breakM = RestTheme.metric(this, "희망 휴식", "${session.requestedMinutes}분")
        breakM.setOnClickListener { chooseRestMinutes() }
        metrics.addView(arrival, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(9) })
        metrics.addView(breakM, LinearLayout.LayoutParams(0, -2, 1f))
        left.addView(metrics, RestTheme.margins(this, 18))
        left.addView(RestTheme.button(this, "목적지 검색 · Pleos 경로 시작") { openDestinationSearch() }, RestTheme.margins(this, 13))
        left.addView(RestTheme.button(this, "✦ Gleo 음성 시연", false) { showDemoVoice() })
        left.addView(RestTheme.button(this, "시연 경로 사용", false) { demoSearch() }, RestTheme.margins(this, 0, 8))
        content.addView(ScrollView(this).apply { addView(left) }, LinearLayout.LayoutParams(0, -1, 0.53f).apply { rightMargin = dp(16) })
        val hero = FrameLayout(this).apply { background = RestTheme.rounded(this@MainActivity, RestTheme.palePurple, 26); clipToOutline = true }
        hero.addView(RestVisuals(this, "home"), FrameLayout.LayoutParams(-1, -1))
        val card = RestTheme.container(this, 18, 0xEDFFFFFF.toInt(), 18)
        card.addView(RestTheme.label(this, "나의 이동에 맞춘 휴식", 20f, RestTheme.deepPurple, true))
        card.addView(RestTheme.label(this, "도착 목표 · 경로상 휴게소 · 교통 변화 · 출발 알림", 14f, RestTheme.muted), RestTheme.margins(this, 0, 7))
        hero.addView(card, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { leftMargin=dp(24); rightMargin=dp(24); bottomMargin=dp(22) })
        content.addView(hero, LinearLayout.LayoutParams(0, -1, 0.47f))
    }

    /** In-app fallback while navigation is active. The real Pleos map runs in Pleos Navigation. */
    private fun renderCarCompanion(root: LinearLayout) {
        val row = RestTheme.row(this).apply { gravity=Gravity.TOP; setPadding(dp(15),dp(13),dp(15),dp(14)) }
        root.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))
        val leftScroll = ScrollView(this).apply { isVerticalScrollBarEnabled=false }
        val panel = RestTheme.container(this,20,RestTheme.white,20)
        leftScroll.addView(panel)
        row.addView(leftScroll, LinearLayout.LayoutParams(0,-1,0.38f).apply { rightMargin=dp(12) })
        contentForStage(panel, compact=false)
        if (session.stage=="NAVIGATING") {
            panel.addView(RestTheme.button(this, "Pleos 내비 화면으로 돌아가기") { openPleosNavigatorNotice() }, RestTheme.margins(this, 10))
            panel.addView(RestTheme.button(this, "음성으로 휴게소 추천", false) { showDemoVoice() }, RestTheme.margins(this, 7))
        }
        val mapShell = FrameLayout(this).apply { background=RestTheme.rounded(this@MainActivity,RestTheme.palePurple,20); clipToOutline=true }
        val map = RestMapPreview(this) { lat,lon ->
            AlertDialog.Builder(this).setTitle("지도 미리보기 위치")
                .setMessage("선택 좌표를 목적지 검색 화면에 전달할까요? 실제 Pleos 내비 지도와는 별개입니다.")
                .setNegativeButton("취소",null)
                .setPositiveButton("목적지 확인") {_,_-> openDestinationSearch(lat,lon) }.show()
        }
        mapPreview=map
        mapShell.addView(map,FrameLayout.LayoutParams(-1,-1))
        if(session.destinationLat!=0.0 && session.destinationLon!=0.0)
            map.showDestination(session.destinationLat,session.destinationLon,session.destinationName)
        val info = RestTheme.container(this,12)
        info.addView(RestTheme.label(this,"지도 미리보기 · OpenStreetMap",14f,RestTheme.ink,true))
        info.addView(RestTheme.label(this,"실제 경로 안내는 Pleos 기본 내비에서 표시됩니다.",12f,RestTheme.muted))
        mapShell.addView(info,FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.LEFT).apply { leftMargin=dp(15);topMargin=dp(15) })
        val open = RestTheme.button(this,"Pleos 내비 전체 화면") { openPleosNavigatorNotice() }
        mapShell.addView(open,FrameLayout.LayoutParams(dp(245),dp(54),Gravity.BOTTOM or Gravity.RIGHT).apply { rightMargin=dp(20);bottomMargin=dp(22) })
        row.addView(mapShell, LinearLayout.LayoutParams(0,-1,0.62f))
    }

    private fun carRailAndScene(root: LinearLayout, scene: String): FrameLayout {
        val wrap = RestTheme.row(this).apply { setPadding(dp(18),dp(12),dp(18),dp(13)) }
        root.addView(wrap,LinearLayout.LayoutParams(-1,0,1f))
        val rail = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER
            background=RestTheme.rounded(this@MainActivity,RestTheme.deepPurple,20)
            setPadding(dp(9),dp(12),dp(9),dp(12))
        }
        listOf("⌂","❯","♫","◎").forEachIndexed { i,letter ->
            val item=RestTheme.label(this,letter,25f,if(i==1)RestTheme.white else RestTheme.lavender,true).apply {
                gravity=Gravity.CENTER
                setOnClickListener { if (i==1) return@setOnClickListener; when(i) {
                    0 -> if(session.stage=="RESTING") navigateBack() else reset()
                    2 -> Toast.makeText(this@MainActivity,"미디어 제어는 Pleos에서 이용하세요.",Toast.LENGTH_SHORT).show()
                    else -> showDemoVoice()
                } }
            }
            rail.addView(item,LinearLayout.LayoutParams(dp(56),dp(74)))
        }
        wrap.addView(rail,LinearLayout.LayoutParams(dp(76),-1).apply {rightMargin=dp(15)})
        val backdrop=FrameLayout(this).apply { background=RestTheme.rounded(this@MainActivity,RestTheme.palePurple,26);clipToOutline=true }
        backdrop.addView(RestVisuals(this,scene),FrameLayout.LayoutParams(-1,-1))
        wrap.addView(backdrop,LinearLayout.LayoutParams(0,-1,1f))
        return backdrop
    }

    private fun renderCarRest(root:LinearLayout) {
        val background=carRailAndScene(root,"home")
        val page=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER; setPadding(dp(20),dp(16),dp(20),dp(16))}
        val box=RestTheme.container(this,24,0xED30295D.toInt(),26)
        val title=RestTheme.label(this,"☕  ${session.stopName}에서 휴식 중",25f,RestTheme.white,true).apply{gravity=Gravity.CENTER}
        box.addView(title,RestTheme.margins(this,16))
        val decision=RestLogic.calculate(session,System.currentTimeMillis())
        box.addView(RestTheme.label(this,"남은 휴식 알림 시간",14f,RestTheme.white).apply{gravity=Gravity.CENTER})
        countLabel=RestTheme.label(this,formatCountdown(decision.nextAlertMs-System.currentTimeMillis()),62f,RestTheme.white,true).apply{gravity=Gravity.CENTER}
        box.addView(countLabel,RestTheme.margins(this,14,8))
        box.addView(RestTheme.label(this,"교통 상황   ${if(session.source=="DEMO")"시연 데이터" else "Pleos ETA 조회 중"}",17f,RestTheme.white,true).apply{gravity=Gravity.CENTER},RestTheme.margins(this,12))
        box.addView(RestTheme.label(this,"출발 알림 ${time(decision.nextAlertMs)}  ·  출발 한계 ${time(decision.departureLimitMs)}",15f,RestTheme.white).apply{gravity=Gravity.CENTER},RestTheme.margins(this,20))
        val actions=RestTheme.row(this)
        actions.addView(RestTheme.button(this,"+ 5분 연장",false){extendRest()},LinearLayout.LayoutParams(0,dp(59),1f).apply{rightMargin=dp(12)})
        actions.addView(RestTheme.button(this,"휴식 종료 · 출발") {finishRest()},LinearLayout.LayoutParams(0,dp(59),1f))
        box.addView(actions)
        if(session.source=="DEMO") {
            val demo=RestTheme.row(this).apply {gravity=Gravity.CENTER}
            demo.addView(RestTheme.button(this,"정체 +20분",false){demoTraffic(20)},LinearLayout.LayoutParams(0,dp(53),1f).apply{rightMargin=dp(9)})
            demo.addView(RestTheme.button(this,"정체 완화 −20분",false){demoTraffic(-20)},LinearLayout.LayoutParams(0,dp(53),1f))
            box.addView(demo,RestTheme.margins(this,0,12))
        }
        page.addView(box,LinearLayout.LayoutParams(-1,-2))
        background.addView(page,FrameLayout.LayoutParams(-1,-1))
    }

    private fun renderCarFinished(root:LinearLayout) {
        val background=carRailAndScene(root,"home")
        val page=LinearLayout(this).apply {gravity=Gravity.CENTER;orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(16),dp(20),dp(16))}
        val result=RestLogic.calculate(session,System.currentTimeMillis())
        val card=RestTheme.container(this,28,0xE939315F.toInt(),28)
        card.addView(RestTheme.label(this,"✓",77f,RestTheme.white,true).apply{gravity=Gravity.CENTER},RestTheme.margins(this,6))
        card.addView(RestTheme.label(this,"휴식 완료!",35f,RestTheme.white,true).apply{gravity=Gravity.CENTER},RestTheme.margins(this,12))
        card.addView(RestTheme.label(this,"변경된 경로로 계속 안내합니다.",19f,RestTheme.white).apply{gravity=Gravity.CENTER},RestTheme.margins(this,18))
        card.addView(RestTheme.label(this,"예상 도착 ${time(result.predictedArrivalMs)}   ·   목적지 ${session.destinationName}",17f,RestTheme.white,true).apply{gravity=Gravity.CENTER},RestTheme.margins(this,18))
        card.addView(RestTheme.button(this,"✓  Pleos 내비로 복귀") {resumeNavigationAfterRest()},RestTheme.margins(this,12))
        if(result.cannotMeetDeadline) card.addView(RestTheme.label(this,"지각 예상: 무리한 운전 대신 연락을 권장합니다.",15f,RestTheme.white))
        page.addView(card,LinearLayout.LayoutParams(-1,-2))
        background.addView(page,FrameLayout.LayoutParams(-1,-1))
    }

    private fun contentForStage(box: LinearLayout, compact: Boolean) {
        val caption = when (session.stage) {
            "SUGGESTIONS" -> "가는 길에 잠깐 쉬어갈까요?"
            "WAYPOINT" -> "선택한 휴식 장소로 안내할게요"
            "NAVIGATING" -> "목적지까지 안전하게 안내할게요"
            "RESTING" -> "휴식 중이에요"
            "FINISHED" -> "휴식 완료!"
            else -> "안전한 여정을 함께 계획해요"
        }
        box.addView(RestTheme.label(this, caption, if (compact) 25f else 29f, RestTheme.ink, true), RestTheme.margins(this, 7))
        box.addView(RestTheme.label(this, subtitle(), 14f, RestTheme.muted), RestTheme.margins(this, 19))
        when (session.stage) {
            "SUGGESTIONS" -> suggestionsContent(box)
            "WAYPOINT" -> waypointContent(box)
            "NAVIGATING" -> navigatingContent(box)
            "RESTING" -> restingContent(box)
            "FINISHED" -> finishedContent(box)
            else -> planContent(box)
        }
        RestTheme.addGap(box, this, 6)
        box.addView(RestTheme.label(this,
            if (session.source == "DEMO") "시연 모드 · 휴게소·경로·교통정보는 가상 데이터입니다."
            else "Pleos 모드 · 실제 내비 응답과 차량 상태를 확인하세요.",
            12f, RestTheme.muted), RestTheme.margins(this, 5))
    }

    private fun subtitle(): String = when (session.stage) {
        "SUGGESTIONS" -> "경로 위 휴식 장소를 비교하고 하나를 선택하세요."
        "WAYPOINT" -> "Pleos 경유지 안내 또는 시연 경로를 확인하세요."
        "RESTING" -> "교통이 달라져도 출발 시각을 다시 계산해요."
        "FINISHED" -> "지금 출발할 때의 예상 도착시간을 확인하세요."
        else -> "도착 약속은 지키면서 충분히 쉬는 방법을 찾아드려요."
    }

    private fun planContent(box: LinearLayout) {
        val form = RestTheme.container(this)
        form.addView(RestTheme.label(this, "출발지", 13f, RestTheme.muted))
        form.addView(RestTheme.label(this, session.originName, 19f, RestTheme.ink, true), RestTheme.margins(this, 12, 5))
        form.addView(RestTheme.hairline(this), LinearLayout.LayoutParams(-1, dp(1)))
        form.addView(RestTheme.label(this, "도착지 · 앱에서 검색 / Pleos 경로 설정", 13f, RestTheme.muted), RestTheme.margins(this, 4, 14))
        form.addView(RestTheme.label(this, session.destinationName, 19f, RestTheme.ink, true))
        form.setOnClickListener { openDestinationSearch() }
        box.addView(form, RestTheme.margins(this, 12))
        val metrics = RestTheme.row(this)
        val arrival = RestTheme.metric(this, "도착 목표", time(session.deadlineMs))
        arrival.setOnClickListener { chooseDeadline() }
        val rest = RestTheme.metric(this, "희망 휴식", "${session.requestedMinutes}분")
        rest.setOnClickListener { chooseRestMinutes() }
        metrics.addView(arrival, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = dp(8) })
        metrics.addView(rest, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(metrics, RestTheme.margins(this, 12))
        box.addView(RestTheme.button(this, "목적지 검색 · Pleos 내비 설정") { openDestinationSearch() }, RestTheme.margins(this, 12))
        box.addView(RestTheme.button(this, "✦  Gleo 음성 시연",false) { showDemoVoice() }, RestTheme.margins(this, 12))
        box.addView(RestTheme.button(this, "시연 시작 · 휴게소 추천", false) { demoSearch() }, RestTheme.margins(this, 12))
        box.addView(RestTheme.button(this, "Pleos 내비 경로에서 실제 검색", false) { launchRoutePicker() }, RestTheme.margins(this, 12))
        box.addView(RestTheme.label(this, "장소 검색으로 Pleos 목적지 설정을 요청합니다. 오른쪽은 OpenStreetMap 미리보기이며 실제 안내 화면이 아닙니다.", 13f, RestTheme.muted))
    }

    private fun suggestionsContent(box: LinearLayout) {
        if (session.source == "PLEOS") {
            val prefs=getSharedPreferences("restsync_route_candidates",MODE_PRIVATE)
            val fresh=System.currentTimeMillis()-prefs.getLong("savedAtMs",0L)<5*60_000L
            val candidates=runCatching { JSONArray(if(fresh) prefs.getString("items","[]") ?: "[]" else "[]") }.getOrDefault(JSONArray())
            box.addView(RestTheme.label(this,"Pleos 경로에서 검색한 실제 후보",20f,RestTheme.ink,true),RestTheme.margins(this,14))
            for(i in 0 until candidates.length()) {
                val entry=candidates.optJSONObject(i) ?: continue
                val name=entry.optString("name")
                val ap=entry.optLong("approach")
                val card=RestTheme.container(this,14)
                card.addView(RestTheme.label(this,"${i+1}. $name",17f,RestTheme.ink,true))
                card.addView(RestTheme.label(this,"휴게소까지 약 ${ap}분 · 외부 API 추정",13f,RestTheme.muted),RestTheme.margins(this,10,7))
                card.addView(RestTheme.button(this,"Pleos 경유지로 추가",false) {openCachedCandidate(i)})
                box.addView(card,RestTheme.margins(this,10))
            }
            if(candidates.length()==0) box.addView(RestTheme.label(this,"검색 결과가 만료됐어요. 다시 검색해 주세요.",14f,RestTheme.muted))
            box.addView(RestTheme.button(this,"실제 경로 다시 검색",false) {launchRoutePicker()})
            return
        }
        val summary = RestTheme.container(this, padding = 17, backgroundColor = RestTheme.palePurple)
        summary.addView(RestTheme.label(this, "✦ Gleo 시연 응답", 12f, RestTheme.deepPurple, true), RestTheme.margins(this, 7))
        summary.addView(RestTheme.label(this, "가는 길에 이용할 휴게소 3곳을 찾았어요.", 17f, RestTheme.ink, true))
        summary.addView(RestTheme.label(this, "원하시면 ‘첫 번째로 안내해 줘’라고 말해보세요.", 13f, RestTheme.muted), RestTheme.margins(this, 0, 7))
        box.addView(summary, RestTheme.margins(this, 15))
        demoNames.forEachIndexed { i, name ->
            val item = RestTheme.container(this, 16)
            item.addView(RestTheme.label(this, "${i + 1}  $name  ·  시연", 18f, RestTheme.ink, true), RestTheme.margins(this, 8))
            val plan = RestPlan((session.deadlineMs - System.currentTimeMillis()) / 60_000, 0, demoDrive[i], demoApproach[i])
            item.addView(RestTheme.label(this, "휴게소까지 ${demoApproach[i]}분  ·  최대 휴식 ${plan.restMinutes}분", 13f, RestTheme.muted), RestTheme.margins(this, 10))
            item.addView(RestTheme.button(this, "경유지로 선택  →", i == 0) { selectDemo(i) })
            box.addView(item, RestTheme.margins(this, 11))
        }
        box.addView(RestTheme.button(this, "🎙  음성으로 선택", false) { showDemoVoice() }, RestTheme.margins(this, 8))
    }

    private fun navigatingContent(box: LinearLayout) {
        val card=RestTheme.container(this,17,RestTheme.palePurple)
        card.addView(RestTheme.label(this,"Pleos 내비 안내 중",21f,RestTheme.deepPurple,true),RestTheme.margins(this,8))
        card.addView(RestTheme.label(this,"목적지  ${session.destinationName}",16f,RestTheme.ink),RestTheme.margins(this,12))
        card.addView(RestTheme.label(this,"주행 중 휴식 요청은 음성을 사용하세요. 터치 조작은 안전하게 정차했을 때만 하세요.",13f,RestTheme.muted))
        box.addView(card,RestTheme.margins(this,16))
        box.addView(RestTheme.button(this,"✦ 쉬고 싶어 · Gleo 시연") {showDemoVoice()},RestTheme.margins(this,12))
        box.addView(RestTheme.button(this,"실제 경로에서 휴게소 찾기",false) {launchRoutePicker()},RestTheme.margins(this,12))
    }

    private fun waypointContent(box: LinearLayout) {
        val place = RestTheme.container(this, backgroundColor = RestTheme.palePurple)
        place.addView(RestTheme.label(this, "⌁  ${session.stopName}", 23f, RestTheme.deepPurple, true), RestTheme.margins(this, 8))
        place.addView(RestTheme.label(this, "휴게소까지 약 ${session.approachMinutes}분", 16f, RestTheme.ink), RestTheme.margins(this, 5))
        place.addView(RestTheme.label(this, "이후 목적지까지 약 ${session.driveMinutes}분", 14f, RestTheme.muted))
        box.addView(place, RestTheme.margins(this, 15))
        box.addView(RestTheme.button(this, "도착했어요 · 휴식 시작") { confirmParked() }, RestTheme.margins(this, 12))
        box.addView(RestTheme.button(this, "✦ Gleo 시연으로 휴식 시작", false) { showDemoVoice() }, RestTheme.margins(this, 12))
        box.addView(RestTheme.button(this, "다른 휴게소 선택", false) {
            if (session.source == "DEMO") demoSearch() else launchRoutePicker()
        }, RestTheme.margins(this, 8))
        box.addView(RestTheme.label(this, "정차와 P단 상태는 아직 자동 검증되지 않으므로, 안전 확인 후 휴식을 시작합니다.", 13f, RestTheme.muted))
    }

    private fun restingContent(box: LinearLayout) {
        val now = System.currentTimeMillis()
        val decision = RestLogic.calculate(session, now)
        val countdown = RestTheme.container(this, 19, RestTheme.palePurple)
        countdown.addView(RestTheme.label(this, "✦ 남은 출발 알림까지", 14f, RestTheme.deepPurple, true), RestTheme.margins(this, 12))
        val ringFrame = FrameLayout(this)
        countRing = RestCountdownRing(this).apply {
            val total = (decision.nextAlertMs-session.startedAtMs).coerceAtLeast(1L)
            progress = ((decision.nextAlertMs-now).toFloat()/total).coerceIn(0f,1f)
        }
        ringFrame.addView(countRing, FrameLayout.LayoutParams(dp(220),dp(220), Gravity.CENTER))
        countLabel = RestTheme.label(this, formatCountdown(decision.nextAlertMs - now), 38f, RestTheme.deepPurple, true).apply {
            gravity = Gravity.CENTER
        }
        ringFrame.addView(countLabel, FrameLayout.LayoutParams(-1,-2, Gravity.CENTER))
        countdown.addView(ringFrame, LinearLayout.LayoutParams(-1,dp(220)).apply { bottomMargin=dp(4) })
        countdown.addView(RestTheme.label(this, "지금부터 최대 휴식 가능 ${decision.remainingRestMinutes}분", 15f, RestTheme.ink, true), RestTheme.margins(this, 6))
        countdown.addView(RestTheme.label(this, "예상 도착 ${time(decision.predictedArrivalMs)}", 14f, RestTheme.muted))
        box.addView(countdown, RestTheme.margins(this, 14))
        val m1 = RestTheme.row(this)
        m1.addView(RestTheme.metric(this, "출발 한계", time(decision.departureLimitMs)), LinearLayout.LayoutParams(0,-2,1f).apply{rightMargin=dp(8)})
        m1.addView(RestTheme.metric(this, "출발 알림", time(decision.nextAlertMs)), LinearLayout.LayoutParams(0,-2,1f))
        box.addView(m1, RestTheme.margins(this, 12))
        val traffic = RestTheme.container(this, 15)
        traffic.addView(RestTheme.label(this, if (session.source == "DEMO") "↗ 교통 변화 · 시연" else "↗ Pleos 내비 ETA", 17f, RestTheme.ink, true), RestTheme.margins(this, 6))
        traffic.addView(RestTheme.label(this, "남은 주행 ${session.driveMinutes}분 · 90초 간격 재조회(실제 연동 시)", 13f, RestTheme.muted))
        if (decision.cannotMeetDeadline) traffic.addView(RestTheme.label(this, "지각이 예상됩니다. 무리하지 말고 연락을 권장합니다.", 14f, RestTheme.red, true), RestTheme.margins(this, 0, 8))
        if (session.source == "PLEOS" && now - session.lastEtaAtMs > 180_000L) traffic.addView(RestTheme.label(this, "실제 내비 ETA 갱신을 확인하지 못했습니다.", 13f, RestTheme.red), RestTheme.margins(this, 0, 7))
        box.addView(traffic, RestTheme.margins(this, 12))
        if (session.source == "DEMO") {
            val row = RestTheme.row(this)
            row.addView(RestTheme.button(this, "정체 +20분", false) { demoTraffic(20) }, LinearLayout.LayoutParams(0, dp(55),1f).apply{rightMargin=dp(8)})
            row.addView(RestTheme.button(this, "정체 완화 −20분", false) { demoTraffic(-20) }, LinearLayout.LayoutParams(0,dp(55),1f))
            box.addView(row, RestTheme.margins(this, 12))
        }
        val actions = RestTheme.row(this)
        actions.addView(RestTheme.button(this, "+ 5분 연장", false) { extendRest() }, LinearLayout.LayoutParams(0, dp(57),1f).apply{rightMargin=dp(8)})
        actions.addView(RestTheme.button(this, "휴식 종료") { finishRest() }, LinearLayout.LayoutParams(0,dp(57),1f))
        box.addView(actions, RestTheme.margins(this, 12))
        box.addView(RestTheme.button(this, "✦ 음성으로 휴식 관리", false) { showDemoVoice() }, RestTheme.margins(this, 9))
        box.addView(RestTheme.label(this, "공조·환기 자동 제어는 차량별 권한 확인 전이므로 작동하지 않습니다.", 12f, RestTheme.muted))
    }

    private fun finishedContent(box: LinearLayout) {
        val result = RestLogic.calculate(session, System.currentTimeMillis())
        val card = RestTheme.container(this, 22, RestTheme.palePurple)
        val icon = RestTheme.label(this, "✓", 65f, RestTheme.purple, true).apply { gravity = Gravity.CENTER }
        card.addView(icon)
        card.addView(RestTheme.label(this, "휴식을 마쳤어요", 24f, RestTheme.ink, true).apply { gravity = Gravity.CENTER }, RestTheme.margins(this, 9))
        card.addView(RestTheme.label(this, "지금 출발 시 예상 도착 ${time(result.predictedArrivalMs)}", 16f, RestTheme.muted).apply { gravity = Gravity.CENTER }, RestTheme.margins(this, 10))
        card.addView(RestTheme.label(this, if (result.cannotMeetDeadline) "지각 예상 · 연락을 권장해요" else "도착 마감을 고려해 안전하게 출발하세요", 14f, if (result.cannotMeetDeadline) RestTheme.red else RestTheme.deepPurple, true).apply {gravity=Gravity.CENTER})
        box.addView(card, RestTheme.margins(this, 15))
        box.addView(RestTheme.button(this, "새 휴식 계획") { reset() }, RestTheme.margins(this, 12))
        box.addView(RestTheme.button(this, "Pleos 내비에서 계속 안내", false) { openPleosNavigatorNotice() }, RestTheme.margins(this, 12))
    }

    private fun showDemoVoice() {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20),dp(16),dp(20),dp(10))
        }
        content.addView(RestTheme.label(this, "✦  Gleo 음성 시연", 23f, RestTheme.deepPurple, true), RestTheme.margins(this, 7))
        content.addView(RestTheme.label(this, "실제 Gleo 연결 전, 말하듯 입력하거나 문장을 눌러 전 과정을 시연할 수 있어요.", 13f, RestTheme.muted), RestTheme.margins(this, 14))
        val input = EditText(this).apply {
            hint = "예: 글레오, 나 좀 쉬고 싶어"
            setSingleLine(true)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_TEXT
            background = RestTheme.rounded(this@MainActivity, RestTheme.snow, 14, RestTheme.line)
            setPadding(dp(14),0,dp(14),0)
        }
        content.addView(input, LinearLayout.LayoutParams(-1,dp(56)).apply {bottomMargin=dp(10)})
        val voiceScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        voiceScroll.addView(content)
        val dialog = AlertDialog.Builder(this).setView(voiceScroll).setNegativeButton("닫기", null).create()
        content.addView(RestTheme.button(this, "마이크로 말하기 · 지원되는 기기", false) {
            dialog.dismiss(); startDemoSpeechRecognition()
        }, RestTheme.margins(this, 9))
        content.addView(RestTheme.button(this, "문장 실행") {
            val phrase = input.text.toString()
            dialog.dismiss()
            processDemoVoice(phrase)
        }, RestTheme.margins(this, 13))
        listOf("글레오, 나 졸린데 쉬고 싶어", "첫 번째 휴게소로 안내해 줘", "휴식 시작해 줘", "휴식시간 얼마나 남았어?", "교통 정체가 심해졌어", "5분 더 쉬고 싶어", "휴식 종료해 줘").forEach { phrase ->
            content.addView(RestTheme.button(this, "“$phrase”", false) {
                dialog.dismiss(); processDemoVoice(phrase)
            }, RestTheme.margins(this, 6))
        }
        dialog.show()
    }

    private fun startDemoSpeechRecognition() {
        val recognizer = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "RestSync 시연: 쉬고 싶다고 말해보세요")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(recognizer, 44)
        } catch (_: ActivityNotFoundException) {
            AlertDialog.Builder(this).setTitle("음성 인식 앱 없음")
                .setMessage("이 에뮬레이터에서는 Android 음성 인식 서비스가 제공되지 않습니다. Gleo 시연 문장 버튼으로 동일한 흐름을 테스트할 수 있어요.")
                .setPositiveButton("문장 선택") { _,_ -> showDemoVoice() }.show()
        } catch (e: Exception) {
            Toast.makeText(this, "음성 인식을 실행할 수 없어요: ${e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            showDemoVoice()
        }
    }

    private fun processDemoVoice(phrase: String) {
        val action = DemoVoiceParser.parse(phrase)
        val response = when (action) {
            DemoVoiceIntent.Search -> {
                val minutes = DemoVoiceParser.requestedMinutes(phrase)
                val arrival = DemoVoiceParser.arrivalHourMinute(phrase)
                if (minutes != null || arrival != null) {
                    var revised = session
                    if (minutes != null) revised = revised.copy(requestedMinutes = minutes)
                    if (arrival != null) {
                        val cal = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, arrival.first)
                            set(Calendar.MINUTE, arrival.second)
                            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                            if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
                        }
                        revised = revised.copy(deadlineMs = cal.timeInMillis)
                    }
                    session = revised; store.write(revised)
                }
                if (session.source == "PLEOS" && session.destinationLat != 0.0) {
                    launchRoutePicker()
                    "Pleos에서 설정된 실제 경로의 휴게소를 검색할게요. 결과에서 후보를 선택해 주세요."
                } else {
                    demoSearch()
                    "시연용 휴게소 세 곳을 찾았어요. 첫 번째를 선택해 보세요."
                }
            }
            is DemoVoiceIntent.Select -> {
                if (session.stage != "SUGGESTIONS") "먼저 휴게소를 찾아 달라고 말해 주세요."
                else if (session.source == "PLEOS") {
                    openCachedCandidate(action.index)
                    "선택한 휴게소를 Pleos에 경유지로 추가할지 확인해 주세요."
                } else {selectDemo(action.index); "${demoNames[action.index]}로 안내할게요. 도착 후 휴식을 시작해 주세요."}
            }
            DemoVoiceIntent.Start -> {
                if (session.stage == "WAYPOINT") { confirmParked(); "안전을 확인한 뒤 휴식을 시작할게요." }
                else "휴게소를 먼저 선택해 주세요."
            }
            DemoVoiceIntent.Stop -> {
                if (session.stage == "RESTING") { finishRest(); "휴식을 종료했어요. 안전하게 출발해 주세요." }
                else "현재 진행 중인 휴식이 없어요."
            }
            DemoVoiceIntent.Extend -> {
                if (session.stage == "RESTING") { extendRest(); "출발 한계를 넘지 않는 범위에서 연장을 확인했어요." }
                else "휴식을 시작한 뒤 연장할 수 있어요."
            }
            DemoVoiceIntent.Congestion -> {
                if (session.stage == "RESTING") { demoTraffic(20); session.statusMessage }
                else "휴식 중에 가상 교통 정체를 시연할 수 있어요."
            }
            DemoVoiceIntent.ClearTraffic -> {
                if (session.stage == "RESTING") { demoTraffic(-20); session.statusMessage }
                else "휴식 중에 가상 교통 정체를 해제할 수 있어요."
            }
            DemoVoiceIntent.Status -> {
                if (session.stage == "RESTING") {
                    val d = RestLogic.calculate(session, System.currentTimeMillis())
                    "지금부터 최대 ${d.remainingRestMinutes}분 더 쉴 수 있어요. 출발 안내는 ${spokenTime(d.nextAlertMs)}에 드릴게요."
                } else "휴식 시작 후 출발시간을 알려드릴게요."
            }
            DemoVoiceIntent.Unknown -> "잘 알아듣지 못했어요. '쉬고 싶어', '첫 번째 휴게소', '휴식 시작'처럼 말해 주세요."
        }
        voiceReply(response)
    }

    private fun handleGleoIntent(incoming: Intent?) {
        val action = incoming?.action ?: return
        if (action !in listOf("FIND_REST_STOP", "SELECT_REST_STOP", "START_REST", "CHECK_REST_TIME")) return
        val raw = incoming.getStringExtra("ACTION_PARAM") ?: "{}"
        val params = runCatching {
            val obj = JSONObject(raw)
            obj.optJSONObject("actionParameters") ?: obj
        }.getOrElse { JSONObject() }
        when (action) {
            "FIND_REST_STOP" -> {
                val minutes = params.optLong("restMinutes", 0L)
                if (minutes in 1..180) session = session.copy(requestedMinutes = minutes)
                val h = params.optString("arrivalTime")
                val t = Regex("^(\\d{1,2}):(\\d{2})$").matchEntire(h)
                if (t != null) {
                    val hour = t.groupValues[1].toIntOrNull() ?: -1
                    val min = t.groupValues[2].toIntOrNull() ?: -1
                    if (hour in 0..23 && min in 0..59) {
                        val time = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY,hour); set(Calendar.MINUTE,min)
                            set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
                            if (timeInMillis < System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR,1)
                        }.timeInMillis
                        session = session.copy(deadlineMs = time)
                    }
                }
                store.write(session)
                handler.post { if (!isFinishing) launchRoutePicker() }
                respondGleo(action, "success", "RestSync opened the real navigation rest-stop search. Select a confirmed route stop.")
            }
            "SELECT_REST_STOP" -> {
                // Real route candidates must be verified by NaviHelper, not inferred from demo indices.
                handler.post { if (!isFinishing) launchRoutePicker() }
                respondGleo(action, "asking", "Please confirm a real rest stop from the route search screen before adding it.")
            }
            "START_REST" -> {
                if (session.stage == "WAYPOINT") {
                    handler.post { if (!isFinishing) confirmParked() }
                    respondGleo(action, "asking", "Confirm the vehicle is parked safely before starting the break.")
                } else respondGleo(action, "asking", "Choose a rest stop before starting a break.")
            }
            "CHECK_REST_TIME" -> respondGleo(action, "success", "RestSync shows the current departure limit and remaining rest time.")
        }
    }

    private fun respondGleo(name: String, status: String, message: String) {
        val response = JSONObject().apply {
            put("functionName", name); put("status", status)
            put("message", message); put("data", JSONArray())
        }
        sendBroadcast(Intent("ai.pleos.playground.intent.action.APP_ACTION_RESPONSE").apply {
            putExtra("result", status)
            putExtra("packageName", packageName)
            putExtra("response", response.toString())
        })
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        session=store.read() // Child activities may already have committed SDK waypoint proof.
        session=store.read()
        if(requestCode==812 && resultCode==RESULT_OK) {
            voiceReply("목적지를 연결했어요. 내비 안내를 시작합니다.")
            navigateAfterResume=true
            return
        }
        if (requestCode == 44 && resultCode == RESULT_OK) {
            val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
            if (heard.isNotBlank()) processDemoVoice(heard)
        }
        if(requestCode==811 && resultCode==RESULT_OK && data!=null) {
            update(session.copy(
                destinationName=data.getStringExtra("destinationName") ?: session.destinationName,
                destinationLat=data.getDoubleExtra("destinationLat",0.0),
                destinationLon=data.getDoubleExtra("destinationLon",0.0),
                source="PLEOS",stage="NAVIGATING",
                statusMessage="Pleos 내비 목적지 경로 설정 완료 · 이제 휴게소를 찾아보세요."
            ))
            voiceReply("목적지가 설정됐어요. Pleos 내비를 시작할게요. 쉬고 싶을 때 RestSync를 불러주세요.")
            navigateAfterResume = !data.getBooleanExtra("nativeAlreadyForeground",false)
        }
        if (requestCode == 33 && resultCode == Activity.RESULT_FIRST_USER && data?.getBooleanExtra("recommendationsSaved",false)==true) {
            update(session.copy(stage="SUGGESTIONS",source="PLEOS",statusMessage="알림을 눌러 RestSync에서 추천 장소를 확인하세요."))
            navigateAfterResume = true
        }
        if (requestCode == 33 && resultCode == RESULT_OK && data != null) {
            update(session.copy(
                stage = "WAYPOINT", source = "PLEOS",
                stopName = data.getStringExtra("stopName") ?: "선택한 휴게소",
                stopLat = data.getDoubleExtra("stopLat", 0.0),
                stopLon = data.getDoubleExtra("stopLon", 0.0),
                approachMinutes = data.getLongExtra("approachMinutes", 0L),
                driveMinutes = data.getLongExtra("driveMinutes", 35L),
                lastEtaAtMs = 0L, alertedForMs = -1L,
                statusMessage = "Pleos 경유지 추가가 확인됐습니다. 안전하게 도착한 뒤 휴식을 시작하세요."
            ))
            navigateAfterResume = !data.getBooleanExtra("nativeAlreadyForeground",false)
        }
    }

    private fun demoSearch() {
        update(session.copy(stage = "SUGGESTIONS", source = "DEMO", stopName = "", alertedForMs = -1L, statusMessage = "시연용 휴게소 목록"))
    }

    private fun selectDemo(index: Int) {
        if (index !in demoNames.indices) return
        update(session.copy(stage = "WAYPOINT", source = "DEMO", stopName = demoNames[index],
            approachMinutes = demoApproach[index], driveMinutes = demoDrive[index],
            alertedForMs = -1L, statusMessage = "시연 경로입니다. 실제 Pleos 경유지를 변경하지 않았습니다."))
    }

    private fun launchRoutePicker() {
        val intent = Intent(this, LiveRouteActivity::class.java).putExtra("autoSearch", true)
            .putExtra("requested", session.requestedMinutes)
            .putExtra("deadlineMs", session.deadlineMs)
        @Suppress("DEPRECATION")
        startActivityForResult(intent, 33)
    }

    /** Galaxy-alarm style vertical swipe wheels, not a radial dial. */
    private fun chooseDeadline() {
        val cal=Calendar.getInstance().apply {timeInMillis=session.deadlineMs}
        showWheelPicker("도착 목표 시간",cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true) {hours, minutes->
            val next=Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY,hours);set(Calendar.MINUTE,minutes)
                set(Calendar.SECOND,0);set(Calendar.MILLISECOND,0)
                if(timeInMillis<System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR,1)
            }
            update(FlowPolicy.changeDeadline(store.read(),next.timeInMillis,System.currentTimeMillis()))
            if(session.stage=="RESTING")RestAlerts.schedule(this,session)
        }
    }

    private fun chooseRestMinutes() {
        if(RecordingTour.read(this).active) {
            Toast.makeText(this,"실제 연동 녹화는 30분 시작 후 +5분 연장으로 진행해요.",Toast.LENGTH_LONG).show();return
        }
        val current=session.requestedMinutes.toInt().coerceIn(5,180)
        showWheelPicker("희망 휴식시간",current/60,(current%60)/5,false) {hours, steps->
            val total=(hours*60+steps*5).coerceIn(5,180)
            if(session.stage=="RESTING") {
                val d=RestLogic.calculate(session,System.currentTimeMillis())
                if(session.startedAtMs+total*60_000L > d.departureLimitMs) {
                    Toast.makeText(this,"도착 마감을 넘는 휴식은 설정할 수 없어요.",Toast.LENGTH_LONG).show()
                    return@showWheelPicker
                }
            }
            update(session.copy(requestedMinutes=total.toLong()))
            if(session.stage=="RESTING") RestAlerts.schedule(this,session)
        }
    }

    private fun showWheelPicker(title:String,initialH:Int,initialM:Int,isClock:Boolean,onApply:(Int,Int)->Unit) {
        val h=NumberPicker(this).apply {minValue=0;maxValue=if(isClock)23 else 3;value=initialH.coerceIn(0,if(isClock)23 else 3);wrapSelectorWheel=true;descendantFocusability=NumberPicker.FOCUS_BLOCK_DESCENDANTS}
        val m=NumberPicker(this).apply {minValue=0;maxValue=if(isClock)59 else 11;value=initialM.coerceIn(0,if(isClock)59 else 11);wrapSelectorWheel=true;descendantFocusability=NumberPicker.FOCUS_BLOCK_DESCENDANTS
            displayedValues=if(isClock) Array(60){"%02d".format(Locale.US,it)} else Array(12){"%02d".format(Locale.US,it*5)}
        }
        val row=RestTheme.row(this).apply {gravity=Gravity.CENTER;setPadding(dp(20),dp(12),dp(20),dp(12))}
        RestDialog.enlargePicker(h);RestDialog.enlargePicker(m)
        row.addView(h,LinearLayout.LayoutParams(0,dp(280),1f))
        row.addView(RestTheme.label(this,if(isClock)" : " else "시간",28f,RestTheme.deepPurple,true))
        row.addView(m,LinearLayout.LayoutParams(0,dp(280),1f))
        if(!isClock)row.addView(RestTheme.label(this,"분",28f,RestTheme.deepPurple,true))
        val dialog=RestDialog.create(this,"$title · 위아래로 스와이프",row,"취소","설정") {onApply(h.value,m.value)}
        RestDialog.show(this,dialog)
    }

    private fun openDestinationSearch(lat:Double=Double.NaN,lon:Double=Double.NaN) {
        val choose=Intent(this,DestinationSearchActivity::class.java)
        if(RecordingTour.read(this).active)choose.putExtra("recordingTourQuery","부산역")
        if(lat.isFinite() && lon.isFinite()) {choose.putExtra("mapLat",lat);choose.putExtra("mapLon",lon)}
        @Suppress("DEPRECATION")
        startActivityForResult(choose,811)
    }

    /** Settings stay inside the app window, with a scrollable list and fixed close action. */
    private fun showQuickPanel() {
        val box=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(16),dp(22),dp(20))}
        val dialog=RestDialog.create(this,"RestSync 설정",box)
        fun action(label:String,primary:Boolean=false,doAction:()->Unit) {
            box.addView(RestTheme.button(this,label,primary){dialog.dismiss();doAction()},RestTheme.margins(this,9))
        }
        box.addView(RestTheme.label(this,"앱 연결과 시연 기능을 여기서 관리할 수 있어요.",20f,RestTheme.muted),RestTheme.margins(this,14))
        if(ClickAutomation.enabled(this))action("▶  시연 영상 · 실제 앱 자동 클릭",true) {ClickAutomation.command(this,"start")}
        if(RecordingTour.read(this).active)action("실제 연동 시연 안내 종료") {stopRecordingTour()}
        action("휴게소 검색 설정 · 저장된 키 관리") {startActivity(Intent(this,SearchSettingsActivity::class.java))}
        action("현재 Pleos 경로 연결") {openCurrentRoute()}
        action("앱에서 장소 직접 검색") {openDestinationSearch()}
        action("도착 목표: ${time(session.deadlineMs)}") {chooseDeadline()}
        action("휴식시간: ${session.requestedMinutes}분 · 스와이프") {chooseRestMinutes()}
        if(session.stage=="SUGGESTIONS" && session.source=="DEMO") {
            demoNames.forEachIndexed{i,name->action("${i+1}. $name · 시연 경유지") {selectDemo(i)}}
        } else {
            action("시연 휴게소 3곳 추천") {demoSearch()}
        }
        action("실제 경로상 휴게소 검색") {launchRoutePicker()}
        if(session.stage=="WAYPOINT") action("휴식 시작 · 정차 확인",true){confirmParked()}
        if(session.stage=="RESTING") {
            action("교통 정체 +20분 · 시연") {demoTraffic(20)}
            if(session.simulatedDelayMinutes>0) action("정체 시연 해제 · 현재 +${session.simulatedDelayMinutes}분") {demoTraffic(-session.simulatedDelayMinutes)}
            action("5분 연장") {extendRest()}
            action("휴식 종료 · 알림과 음성",true) {finishRest()}
        }
        action("음성 대화 시연") {showDemoVoice()}
        if (session.stage in setOf("NAVIGATING", "WAYPOINT", "SUGGESTIONS")) {
            action("내비로 복귀 · RestSync 알림 표시") { openPleosNavigatorNotice() }
        }
        val notificationsEnabled = getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()
        action(if (notificationsEnabled) "RestSync 알림 설정" else "알림 꺼짐 · 대체 알림을 사용하려면 켜 주세요") {
            runCatching { startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) }
                .onFailure { Toast.makeText(this,"차량 앱 설정에서 RestSync 알림을 확인해 주세요.",Toast.LENGTH_LONG).show() }
        }
        action("Pleos 내비 화면 열기") {openPleosNavigatorNotice()}
        RestDialog.show(this,dialog)
    }

    /** Opened ONLY by an explicit tap on the small companion card / its notification. */
    private fun openCachedCandidate(position:Int) {
        if (position !in 0..2) return
        val call=Intent(this,LiveRouteActivity::class.java)
            .putExtra("deadlineMs",session.deadlineMs)
            .putExtra("requested",session.requestedMinutes)
            .putExtra("candidateIndex",position)
        @Suppress("DEPRECATION")
        startActivityForResult(call,33)
    }

    private fun startRecordingTour(automated:Boolean=false) {
        session=store.read()
        if(session.stage in setOf("RESTING","DEPARTURE")) {
            Toast.makeText(this,"진행 중인 휴식을 마친 뒤 실제 연동 시연을 시작해 주세요.",Toast.LENGTH_LONG).show();return
        }
        RecordingTour.start(this,automated)
        if(session.source=="PLEOS" && session.stopName.isNotBlank()) {
            RecordingTour.setStep(this,RecordingTour.Step.CLEANUP)
            startActivity(Intent(this,NavigationHandoffActivity::class.java).putExtra("resumeAfterRest",true))
        } else openDestinationSearch()
        RestAlerts.showCompanionShortcut(this)
    }

    private fun stopRecordingTour() {
        if(RecordingTour.read(this).automated)ClickAutomation.command(this,"stop")
        RecordingTour.stop(this);RestAlerts.showCompanionShortcut(this);render()
        Toast.makeText(this,"시연 안내를 종료했어요. 실제 경로와 진행 중인 휴식은 유지됩니다.",Toast.LENGTH_LONG).show()
    }

    private fun restartRecordingTour(fromRunner:Boolean=false) {
        val recording=RecordingTour.read(this)
        if(recording.active && recording.automated && !fromRunner) {ClickAutomation.command(this,"restart");return}
        if(!RecordingTour.read(this).active) {startRecordingTour();return}
        session=store.read()
        if(session.source=="PLEOS" && session.stopName.isNotBlank()) {
            RecordingTour.setStep(this,RecordingTour.Step.RESTART_PENDING)
            if(session.stage=="RESTING") {
                finishRest()
                if(fromRunner || recording.automated)startActivity(Intent(this,NavigationHandoffActivity::class.java).putExtra("resumeAfterRest",true))
            }
            else {
                if(session.stage=="DEPARTURE") {
                    alarmTone?.stopTone();RestAlerts.dismissWakeup(this)
                    update(FlowPolicy.acknowledge(session))
                }
                startActivity(Intent(this,NavigationHandoffActivity::class.java).putExtra("resumeAfterRest",true))
            }
            Toast.makeText(this,"선택한 경유지를 확인·정리한 뒤 다시 시작해요. 내비에서 RestSync로 돌아와 주세요.",Toast.LENGTH_LONG).show()
        } else {RecordingTour.start(this,recording.automated || fromRunner);openDestinationSearch()}
    }

    private fun recordingNext() {
        val tour=RecordingTour.read(this)
        if(!tour.active)return
        if(tour.paused) {Toast.makeText(this,"시연 안내를 계속 재생한 뒤 진행해 주세요.",Toast.LENGTH_SHORT).show();return}
        session=store.read()
        when(tour.step) {
            RecordingTour.Step.DESTINATION,RecordingTour.Step.ROUTE_RESET -> openDestinationSearch()
            RecordingTour.Step.REST_SEARCH -> launchRoutePicker()
            RecordingTour.Step.ARRIVAL -> if(session.stage=="WAYPOINT")chooseArrivalRest()
            RecordingTour.Step.WAIT_ETA -> {
                runCatching {startForegroundService(Intent(this,RestMonitorService::class.java).putExtra("refreshEta",true))}
                Toast.makeText(this,"실제 Pleos 예상 시간을 확인하고 있어요.",Toast.LENGTH_SHORT).show()
            }
            RecordingTour.Step.REST30 -> if(session.stage=="RESTING") {
                if(session.requestedMinutes==30L)extendRest()
                if(store.read().requestedMinutes==35L)RecordingTour.setStep(this,RecordingTour.Step.REST35)
            }
            RecordingTour.Step.REST35 -> if(session.stage=="RESTING") {
                if(session.simulatedDelayMinutes<20L)demoTraffic(20L-session.simulatedDelayMinutes)
                RecordingTour.setStep(this,RecordingTour.Step.TRAFFIC)
            }
            RecordingTour.Step.TRAFFIC -> if(session.stage=="RESTING" && session.source=="PLEOS") {
                update(RealRecordingPolicy.accelerate(session,System.currentTimeMillis()))
                RecordingTour.setStep(this,RecordingTour.Step.ACCELERATE);RestAlerts.schedule(this,session)
            }
            RecordingTour.Step.ACCELERATE -> if(session.stage=="RESTING") {
                update(RealRecordingPolicy.accelerate(session,System.currentTimeMillis(),1));RestAlerts.schedule(this,session)
            }
            RecordingTour.Step.ALARM -> acknowledgeDeparture()
            RecordingTour.Step.COMPLETE -> resumeNavigationAfterRest()
            RecordingTour.Step.CLEANUP,RecordingTour.Step.RESTART_PENDING -> {
                if(session.source=="PLEOS" && session.stopName.isNotBlank())startActivity(Intent(this,NavigationHandoffActivity::class.java).putExtra("resumeAfterRest",true))
                else {RecordingTour.start(this);openDestinationSearch()}
            }
            RecordingTour.Step.DONE -> startRecordingTour()
        }
    }

    private fun advanceRecordingTour() {
        val tour=RecordingTour.read(this)
        if(!tour.active || tour.paused || !hasWindowFocus() || isFinishing)return
        if(session.stage=="DEPARTURE" && tour.step!=RecordingTour.Step.ALARM) {
            RecordingTour.setStep(this,RecordingTour.Step.ALARM);return
        }
        if(session.stage=="RESTING" && session.source=="PLEOS" && tour.step==RecordingTour.Step.WAIT_ETA && session.lastEtaAtMs>0) {
            if(session.requestedMinutes!=30L || session.simulatedDelayMinutes!=0L) {
                RecordingTour.togglePause(this)
                Toast.makeText(this,"휴식 설정이 바뀌어 시연 안내를 멈췄어요. 재시작하면 경유지 정리 후 다시 진행해요.",Toast.LENGTH_LONG).show();return
            }
            update(RealRecordingPolicy.prepareRest(session,System.currentTimeMillis()))
            RecordingTour.markEtaReady(this);RestAlerts.schedule(this,session);return
        }
        if(session.stage=="RESTING" && tour.step==RecordingTour.Step.REST30 && session.requestedMinutes==35L) {
            RecordingTour.setStep(this,RecordingTour.Step.REST35);return
        }
        if(session.stage=="RESTING" && tour.step==RecordingTour.Step.REST35 && session.simulatedDelayMinutes>=20) {
            RecordingTour.setStep(this,RecordingTour.Step.TRAFFIC);return
        }
        if(!tour.automated && tour.step in setOf(RecordingTour.Step.REST30,RecordingTour.Step.REST35,RecordingTour.Step.TRAFFIC,RecordingTour.Step.ALARM) && tour.elapsedMs>=6000L)recordingNext()
    }

    private fun consumeRecordingAction(incoming:Intent?) {
        val action=incoming?.getStringExtra("recordingTourAction") ?: return
        incoming.removeExtra("recordingTourAction")
        when(action) {
            "next"->recordingNext();"restart"->restartRecordingTour();"stop"->stopRecordingTour()
            "runner-start"->if(ClickAutomation.enabled(this)) {
                RecordingTour.setAutomated(this,true)
                val current=store.read()
                if(RecordingTour.read(this).active && current.stage in setOf("RESTING","DEPARTURE","WAYPOINT","FINISHED"))restartRecordingTour(true)
                else startRecordingTour(true)
            }
        }
    }

    private fun consumeCompanionAction(incoming: Intent?) {
        val action = incoming?.getStringExtra("restsyncOverlayAction") ?: return
        incoming.removeExtra("restsyncOverlayAction")
        when (action) {
            "find" -> if (session.source == "DEMO") demoSearch() else launchRoutePicker()
            "arrived" -> if (session.stage == "WAYPOINT") confirmParked()
                else Toast.makeText(this,"먼저 휴게소를 경유지로 선택해 주세요.",Toast.LENGTH_LONG).show()
            "candidate" -> if (session.source == "PLEOS") {
                openCachedCandidate(incoming.getIntExtra("candidateIndex",-1))
            } else demoSearch()
            "voice" -> showDemoVoice()
            "settings" -> showQuickPanel()
            "tour-next" -> recordingNext()
            "continue" -> if (session.stage == "FINISHED") resumeNavigationAfterRest() else openPleosNavigatorNotice()
        }
    }

    private fun resumeNavigationAfterRest() {
        if(store.read().stage!="FINISHED")return
        startActivity(Intent(this,NavigationHandoffActivity::class.java).putExtra("resumeAfterRest",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun returnToNavigation() { openPleosNavigatorNotice() }

    private fun showNavigationShortcut() { RestAlerts.showCompanionShortcut(this) }

    private fun changeDemoPlaces() {
        val dialogView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20),dp(8),dp(20),dp(8))
        }
        val originInput = EditText(this).apply { setText(session.originName); hint = "출발지 · 시연"; setSingleLine() }
        val destinationInput = EditText(this).apply { setText(session.destinationName); hint = "도착지 · 시연"; setSingleLine() }
        dialogView.addView(originInput)
        dialogView.addView(destinationInput)
        AlertDialog.Builder(this).setTitle("시연용 출발지·도착지")
            .setMessage("입력한 장소는 실제 내비 목적지를 변경하지 않습니다.")
            .setView(dialogView).setNegativeButton("취소",null)
            .setPositiveButton("저장") {_,_->
                update(session.copy(
                    originName = originInput.text.toString().trim().ifEmpty { "출발지" },
                    destinationName = destinationInput.text.toString().trim().ifEmpty { "도착지" }
                ))
            }.show()
    }

    private fun confirmParked() {
        AlertDialog.Builder(this).setTitle("안전 정차 확인")
            .setMessage("차량을 안전한 장소에 완전히 정차하고 P단으로 변속했나요? RestSync는 P단을 자동 판별하지 않습니다.\n\n선택한 휴식: ${session.requestedMinutes}분. 도착 목표에 이미 여유가 없다면 선택한 휴식 시간을 기준으로 알려드려요.")
            .setNegativeButton("취소",null)
            .setPositiveButton("정차·P단 확인 · 휴식 시작") {_,_-> startRest() }.show()
    }

    private fun startRest() {
        val recording=RecordingTour.read(this).active
        if(recording && session.source!="PLEOS") {
            Toast.makeText(this,"실제 Pleos 경유지 등록을 확인한 뒤 휴식을 시작해 주세요.",Toast.LENGTH_LONG).show();return
        }
        if(recording && session.requestedMinutes!=30L) {
            Toast.makeText(this,"30 → 35 → 15분 녹화 시연은 30분 휴식을 선택해 주세요.",Toast.LENGTH_LONG).show()
            chooseArrivalRest();return
        }
        val now=System.currentTimeMillis()
        val started=FlowPolicy.beginRest(session,session.requestedMinutes,now)
        update(if(recording && started.source=="PLEOS")RealRecordingPolicy.prepareRest(started,now) else started)
        if(recording)RecordingTour.onRestStarted(this)
        RestAlerts.schedule(this, session)
        runCatching {
            val task = Intent(this, RestMonitorService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(task) else startService(task)
        }.onFailure { Toast.makeText(this,"백그라운드 모니터 시작 실패: 앱 실행 중 시연하세요.", Toast.LENGTH_LONG).show() }
    }

    private fun demoTraffic(delta: Long) {
        if (session.stage != "RESTING") return
        val recording=RecordingTour.read(this)
        if(recording.active && recording.step==RecordingTour.Step.WAIT_ETA) {
            Toast.makeText(this,"첫 실제 Pleos ETA 확인 후 교통 시연을 적용해요.",Toast.LENGTH_LONG).show();return
        }
        // Preserve real Pleos ETA, source and waypoint handoff; only the local
        // hypothetical delay is applied, including across native ETA updates.
        update(FlowPolicy.simulateTraffic(store.read(),delta,System.currentTimeMillis()))
        Toast.makeText(this,session.statusMessage,Toast.LENGTH_LONG).show()
        RestAlerts.schedule(this, session)
        val decision = RestLogic.calculate(session, System.currentTimeMillis())
        if (decision.shouldNotifyNow) {
            voiceReply("교통 상황이 바뀌어 출발 시각이 앞당겨졌어요. 휴식을 종료하고 안전하게 출발을 준비해 주세요.")
            showDepartureAlarm()
        }
    }

    private fun extendRest() {
        if (session.stage != "RESTING") return
        val recording=RecordingTour.read(this)
        if(recording.active && recording.step==RecordingTour.Step.WAIT_ETA) {
            Toast.makeText(this,"첫 실제 Pleos ETA를 확인한 뒤 연장해 주세요.",Toast.LENGTH_LONG).show();return
        }
        val current = RestLogic.calculate(session, System.currentTimeMillis())
        val extended = session.copy(requestedMinutes = session.requestedMinutes + 5L)
        val proposal = RestLogic.calculate(extended, System.currentTimeMillis())
        val extendedEndMs = session.startedAtMs + extended.requestedMinutes * 60_000L
        if ((session.respectDeadline && (current.remainingRestMinutes < 5 || extendedEndMs > current.departureLimitMs)) ||
            System.currentTimeMillis() >= current.nextAlertMs) {
            AlertDialog.Builder(this).setTitle("연장하기 어려워요")
                .setMessage("도착 마감과 안전 여유를 위해 휴식 연장을 권하지 않습니다.")
                .setPositiveButton("확인",null).show()
            return
        }
        update(extended)
        RestAlerts.schedule(this, session)
        Toast.makeText(this,"희망 휴식 5분 연장 · 도착 마감 한계 우선 적용", Toast.LENGTH_LONG).show()
    }

    private fun finishRest() {
        if(session.stage!="RESTING") return
        RestAlerts.cancel(this)
        stopService(Intent(this,RestMonitorService::class.java))
        val completed=session.copy(stage="FINISHED",simulatedDelayMinutes=0, alertedForMs=RestLogic.calculate(session,System.currentTimeMillis()).nextAlertMs)
        update(completed)
        val recording=RecordingTour.read(this)
        if(recording.active && recording.step!=RecordingTour.Step.RESTART_PENDING)RecordingTour.setStep(this,RecordingTour.Step.COMPLETE)
        RestAlerts.showRestComplete(this,completed)
        val d=RestLogic.calculate(completed,System.currentTimeMillis())
        voiceReply(if(d.cannotMeetDeadline) "휴식이 완료됐어요. 현재 예상 도착이 늦을 수 있어요. 안전하게 출발하고 필요한 연락을 해 주세요."
                   else "휴식이 완료됐어요. 준비되면 안전을 확인하고 출발해 주세요. Pleos 내비로 돌아갈 수 있어요.")
    }

    private fun reset() {
        RestAlerts.cancel(this)
        stopService(Intent(this,RestMonitorService::class.java))
        update(RestSession(deadlineMs=System.currentTimeMillis()+2*60*60_000L, originName=session.originName, destinationName=session.destinationName, destinationLat=session.destinationLat,destinationLon=session.destinationLon))
    }

    private fun navigateBack() {
        when(session.stage) {
            "PLAN" -> AlertDialog.Builder(this).setTitle("RestSync 종료")
                .setMessage("앱을 닫을까요?").setNegativeButton("취소",null)
                .setPositiveButton("종료") {_,_->finish()}.show()
            "NAVIGATING" -> AlertDialog.Builder(this).setTitle("내비 화면으로 돌아가기")
                .setMessage("RestSync에서 나가 Pleos 내비를 계속 볼까요?")
                .setNegativeButton("취소",null)
                .setPositiveButton("내비 열기") {_,_-> openPleosNavigatorNotice() }.show()
            "SUGGESTIONS" -> update(session.copy(stage=if(session.destinationLat!=0.0)"NAVIGATING" else "PLAN"))
            "WAYPOINT" -> if (session.source == "DEMO") update(session.copy(stage="SUGGESTIONS")) else openPleosNavigatorNotice()
            "DEPARTURE" -> Toast.makeText(this,"확인 버튼을 눌러 휴식을 마쳐 주세요.",Toast.LENGTH_SHORT).show()
            "RESTING" -> AlertDialog.Builder(this).setTitle("휴식 중")
                .setMessage("휴식을 종료하면 출발 알림이 해제됩니다.")
                .setNegativeButton("계속 쉬기",null)
                .setPositiveButton("휴식 종료") {_,_->finishRest()}.show()
            else -> reset()
        }
    }

    /** Requests the native Pleos Navigation screen. Does NOT embed its View. */
    private fun openPleosNavigatorNotice() {
        startActivity(Intent(this,NavigationHandoffActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun time(ms: Long): String = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(ms))
    private fun spokenTime(ms: Long): String = SimpleDateFormat("a h시 m분", Locale.KOREA).format(Date(ms))
    private fun formatCountdown(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L)
        return "%02d:%02d".format(Locale.US, s / 60L, s % 60L)
    }
    private fun dp(n: Int) = RestTheme.dp(this,n)
}
