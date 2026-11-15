package com.restsync.demo

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Development emulator bridge to a fixed, local ADB UI-test runner. No shell in the app. */
internal object ClickAutomation {
    private val worker=Executors.newSingleThreadExecutor()
    private val main=Handler(Looper.getMainLooper())
    fun enabled(activity:Activity)=activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE!=0

    fun command(activity:Activity,command:String) {
        if(!enabled(activity) || command !in setOf("start","pause","resume","restart","stop"))return
        val current=SessionStore(activity).read()
        if(command=="start" && current.stage in setOf("RESTING","DEPARTURE") && !RecordingTour.read(activity).active) {
            Toast.makeText(activity,"진행 중인 휴식을 마친 뒤 자동 클릭 시연을 시작해 주세요.",Toast.LENGTH_LONG).show();return
        }
        worker.execute {
            val result=runCatching {
                val connection=URL("http://10.0.2.2:8765/$command").openConnection() as HttpURLConnection
                try {
                    connection.requestMethod="POST";connection.connectTimeout=2500;connection.readTimeout=4000
                    connection.setRequestProperty("Content-Type","application/json")
                    connection.setRequestProperty("X-RestSync-Demo","emulator-only-v1")
                    connection.doOutput=true
                    connection.outputStream.use {it.write("{}".toByteArray(Charsets.UTF_8))}
                    val code=connection.responseCode
                    check(code in 200..299) {"HTTP $code"}
                    connection.inputStream.use {it.readBytes()}
                } finally {connection.disconnect()}
            }
            main.post {
                if(activity.isFinishing || activity.isDestroyed)return@post
                if(result.isSuccess)Toast.makeText(activity,when(command) {
                    "start"->"PC 자동 클릭 실행기에 연결했어요. 실제 앱 시연을 시작합니다."
                    "restart"->"현재 자동 클릭을 정리한 뒤 처음부터 다시 진행해요."
                    "stop"->"자동 클릭을 종료했어요. 실제 경로는 내비에서 확인할 수 있어요."
                    "pause"->"자동 클릭 일시정지 · 실제 휴식 타이머는 계속 흐릅니다."
                    else->"자동 클릭을 계속 진행해요."
                },Toast.LENGTH_LONG).show()
                else if(command in setOf("start","restart")) {
                    val body=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
                    body.addView(RestTheme.label(activity,"PC에서 동봉된 launch_runner.cmd를 한 번 실행하고 다시 눌러 주세요.\n\n개발용 에뮬레이터의 실제 화면을 ADB로 클릭합니다. 실제 차량에서는 실행하지 않습니다.",24f))
                    RestDialog.show(activity,RestDialog.create(activity,"자동 클릭 실행기 연결 필요",body))
                }
            }
        }
    }
}
