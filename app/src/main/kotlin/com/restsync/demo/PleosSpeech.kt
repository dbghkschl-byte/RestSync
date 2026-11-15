package com.restsync.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener

/** Only the documented Pleos Speech Service. Never forwards microphone audio to another provider. */
internal class PleosRecognition(
    context: Context, private val status: (String) -> Unit,
    private val result: (String) -> Unit, private val failed: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val app = context.applicationContext
    private var sdk: SpeechToText? = null
    private var closed = false
    private var delivered = false
    private val timeout = Runnable { fail("음성 인식 시간이 지났어요. 마이크 버튼을 눌러 다시 말해 주세요.") }
    private val listener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() { main.post { if (!closed) status("듣고 있어요…") } }
        override fun onUpdated(stt: String, completed: Boolean) {
            main.post {
                if (closed || delivered) return@post
                status(stt)
                if (completed && stt.isNotBlank()) {
                    delivered = true
                    close()
                    result(stt)
                }
            }
        }
        override fun onEndedRecognition() {
            main.postDelayed({ if (!closed && !delivered) fail("말씀을 인식하지 못했어요. 다시 시도해 주세요.") }, 700)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onError(errorType: ErrorType) { main.post { fail("Pleos 음성 인식을 사용할 수 없어요: ${errorType.name}") } }
    }
    fun start() {
        if (closed || sdk != null) return
        main.postDelayed(timeout, 25_000)
        status("Pleos 마이크를 준비하고 있어요…")
        runCatching {
            val current = SpeechToText(app, Mode.ON_DEVICE)
            sdk = current
            current.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        if (closed) return@post
                        runCatching {
                            current.addListener(listener)
                            current.setLocale(Locale.KOREA)
                            current.request()
                        }.onFailure { fail("Pleos 마이크를 시작할 수 없어요. 권한과 서비스 상태를 확인해 주세요.") }
                    }
                }
                override fun onFailure(errorType: ErrorType) { main.post { fail("Pleos 음성 초기화 실패: ${errorType.name}") } }
            })
        }.onFailure { fail("이 환경에서는 Pleos 음성 서비스를 사용할 수 없어요. 화면 버튼으로 이용해 주세요.") }
    }
    private fun fail(message: String) {
        if (closed) return
        close()
        failed(message)
    }
    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        sdk?.let { current ->
            runCatching { current.stop() }
            runCatching { current.removeListener(listener) }
            runCatching { current.release() }
        }
        sdk = null
    }
}

/** Lazy, bounded speech queue; no server registration or background microphone. */
internal class PleosVoice(context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val app = context.applicationContext
    private var sdk: ai.pleos.playground.tts.TextToSpeech? = null
    private var ready = false
    private var closed = false
    private var pending: String? = null
    private val timeout = Runnable { pending = null; Log.w("RestSyncSpeech", "TTS initialization timed out") }
    fun speak(text: String) {
        if (closed) return
        pending = text
        if (ready) { flush(); return }
        if (sdk != null) return
        main.postDelayed(timeout, 8_000)
        runCatching {
            val current = ai.pleos.playground.tts.TextToSpeech(app, ai.pleos.playground.tts.constant.Mode.ON_DEVICE)
            sdk = current
            current.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        if (closed) return@post
                        runCatching {
                            current.setLocale(Locale.KOREA)
                            ready = true
                            main.removeCallbacks(timeout)
                            flush()
                        }.onFailure { pending = null; Log.w("RestSyncSpeech", "TTS setup unavailable", it) }
                    }
                }
                override fun onFailure(errorType: ai.pleos.playground.tts.constant.ErrorType) {
                    main.post { pending = null; Log.w("RestSyncSpeech", "TTS initialization: $errorType") }
                }
            })
        }.onFailure { pending = null; Log.w("RestSyncSpeech", "TTS unavailable; screen and alarm remain available", it) }
    }
    private fun flush() {
        val message = pending ?: return
        pending = null
        runCatching { sdk?.speak(message, true) }.onFailure { Log.w("RestSyncSpeech", "TTS request failed", it) }
    }
    fun stop() { pending = null; runCatching { sdk?.stop() } }
    fun close() {
        closed = true
        main.removeCallbacksAndMessages(null)
        stop()
        runCatching { sdk?.release() }
        sdk = null
    }
}
