package com.restsync.demo

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import java.util.Locale
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/**
 * Foreground monitor used only during a manually confirmed rest session.
 * Re-queries Pleos every 90s when available. If unavailable, DEMO retains the
 * last manually entered ETA and labels it as simulated. No real vehicle control.
 */
class RestMonitorService : Service(), NaviHelperEventListener, TextToSpeech.OnInitListener {
    private val handler = Handler(Looper.getMainLooper())
    private val store by lazy { SessionStore(this) }
    private var helper: NaviHelper? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var tone:android.media.ToneGenerator?=null
    private var connected = false
    private var destroyed = false
    private var polling = false
    private var lastQueryAtMs = 0L
    private var lastConnectAttemptMs=0L

    private val periodic = object : Runnable {
        override fun run() {
            if (destroyed) return
            val s = store.read()
            if (s.stage != "RESTING") {
                stopSelf()
                return
            }
            val now = System.currentTimeMillis()
            if(!connected && s.source=="PLEOS" && now-lastConnectAttemptMs>=90_000L) connectNavi()
            val d = RestLogic.calculate(s, now)
            if (d.shouldNotifyNow && s.alertedForMs != d.nextAlertMs) {
                completeAndAnnounce(s, d.nextAlertMs)
                handler.postDelayed({ stopSelf() }, 12_000L)
                return
            }
            // 90-second polling target (not 15 seconds). If a NaviHelper callback
            // never arrives, clear the in-flight flag after 15 seconds.
            if (connected && s.source == "PLEOS" && now - lastQueryAtMs >= 90_000L) {
                polling = true
                lastQueryAtMs = now
                runCatching { helper?.getDestinationInfo() }.onFailure { polling = false }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        RestAlerts.ensureChannel(this)
        tts = TextToSpeech(this, this)
        startForeground(RestAlerts.MONITOR_ID, RestAlerts.monitorNotification(this, "휴식 중 · 예상 도착시간을 확인하고 있어요"))
        connectNavi()
        handler.post(periodic)
    }

    private fun connectNavi() {
        if(destroyed || store.read().source!="PLEOS")return
        lastConnectAttemptMs=System.currentTimeMillis()
        helper?.let {old->runCatching {old.removeListener(this)};runCatching {old.release()}}
        connected=false
        runCatching {
            val candidate = NaviHelper(applicationContext)
            helper = candidate
            candidate.initialize(
                onConnected = {
                    handler.post {
                        if (destroyed || helper!==candidate) return@post
                        connected = true
                        candidate.addListener(this)
                        lastQueryAtMs = System.currentTimeMillis()
                        candidate.getDestinationInfo()
                    }
                },
                onFailed = { connected = false }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if(intent?.getBooleanExtra("refreshEta",false)==true) {
            if(connected)runCatching {helper?.getDestinationInfo()} else connectNavi()
        }
        return START_STICKY
    }

    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
        handler.post {
            polling = false
            if (destroyed) return@post
            val current = store.read()
            if (current.stage != "RESTING" || current.source != "PLEOS") return@post
            if(!FlowPolicy.matchesStop(destinationInfo.latitude,destinationInfo.longitude,current.destinationLat,current.destinationLon)) {
                store.write(current.copy(statusMessage="내비 목적지가 바뀌어 교통 업데이트를 잠시 멈췄어요."))
                return@post
            }
            // NaviHelper v3 destination remaining travel time is treated as seconds.
            // Verify this unit with the installed SDK's model/docs before real driving.
            val seconds = destinationInfo.remainTime.toLong()
            if (seconds <= 0 || seconds > 24 * 3600) return@post
            val newMinutes = (seconds + 59L) / 60L
            val now = System.currentTimeMillis()
            var revised = FlowPolicy.updateDriveEstimate(current,newMinutes,now).copy(
                remainingDistanceMeters=destinationInfo.remainDistance.toLong().coerceAtLeast(0))
            val recording=RecordingTour.read(this)
            if(recording.active && recording.step==RecordingTour.Step.WAIT_ETA && revised.requestedMinutes==30L && revised.simulatedDelayMinutes==0L) {
                revised=RealRecordingPolicy.prepareRest(revised,now)
                RecordingTour.markEtaReady(this)
            }
            store.write(revised)
            RestAlerts.schedule(this, revised)
            val d = RestLogic.calculate(revised, now)
            if (d.shouldNotifyNow && revised.alertedForMs != d.nextAlertMs) {
                completeAndAnnounce(revised, d.nextAlertMs)
                handler.postDelayed({ stopSelf() }, 12_000L)
            }
        }
    }

    /** No background activity launch: TTS + notification, user taps to view the completion screen. */
    private fun completeAndAnnounce(s: RestSession, alertMs: Long) {
        val updated=s.copy(stage="DEPARTURE", alertedForMs=alertMs)
        store.write(updated)
        RestAlerts.cancel(this)
        RestAlerts.showWakeup(this,updated)
        runCatching {
            tone?.release()
            tone=android.media.ToneGenerator(android.media.AudioManager.STREAM_ALARM,80)
            tone?.startTone(android.media.ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,1800)
        }
        val d=RestLogic.calculate(updated, System.currentTimeMillis())
        speak(if (d.cannotMeetDeadline)
            "휴식 알림 시간입니다. 현재 예상 도착이 늦어질 수 있어요. 무리한 운전 대신 연락을 권장합니다."
            else "이제 일어날 시간이에요. 안전을 확인하고 출발을 준비해 주세요. RestSync에서 확인 버튼을 눌러 주세요.")
    }

    private fun speak(message: String) {
        if (!ttsReady) {pendingSpeech=message;return}
        runCatching {tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "restsync_auto_depart")}
    }

    override fun onInit(status: Int) {
        ttsReady=status==TextToSpeech.SUCCESS
        if (ttsReady) {
            tts?.language=Locale.KOREAN
            pendingSpeech?.let {text->pendingSpeech=null;speak(text)}
        }
    }

    override fun onError(errorCode: NaviErrorCode) {
        polling = false
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        helper?.let {
            if (connected) runCatching { it.removeListener(this) }
            runCatching { it.release() }
        }
        helper = null
        tts?.stop()
        tts?.shutdown()
        tone?.release();tone=null
        tts=null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
