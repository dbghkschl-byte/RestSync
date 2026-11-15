package com.restsync.demo

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

internal object RestAlerts {
    const val CHANNEL = "restsync_rest_monitor"
    private const val ACTION_ALERT = "com.restsync.demo.ALERT_DEPARTURE"
    private const val ALARM_CODE = 1001
    const val ALERT_ID = 102
    const val MONITOR_ID = 101
    private const val COMPANION_ID = 104

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL, "RestSync 출발 안내", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "휴식 중 출발 알림과 ETA 상태"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun homeIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun monitorNotification(context: Context, summary: String): Notification {
        ensureChannel(context)
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("RestSync · 휴식 모니터링")
            .setContentText(summary)
            .setContentIntent(homeIntent(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** User-opened entry points while Pleos Navigation is foreground. */
    fun showCompanionShortcut(context: Context) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val channel = "restsync_navigation_shortcut"
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel,"RestSync 내비 동행",NotificationManager.IMPORTANCE_LOW))
        fun actionIntent(action: String, code: Int) = PendingIntent.getActivity(context,code,
            Intent(context,MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("restsyncOverlayAction",action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val recording=RecordingTour.read(context)
        val builder=Notification.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(if(recording.active) "RestSync · 실제 연동 시연" else "RestSync · Pleos 내비 동행")
            .setContentText(if(recording.active) RecordingTour.title(recording.step)+" · 다음 장면을 눌러 이어가세요" else "휴식 요청 / 휴게소 추천 / 설정을 열려면 여기를 누르세요")
            .setContentIntent(if(recording.active)actionIntent("tour-next",1043) else homeIntent(context))
            .addAction(Notification.Action.Builder(null,"휴게소 찾아줘",actionIntent("find",1041)).build())
            .addAction(Notification.Action.Builder(null,"휴식 화면 열기",actionIntent("home",1042)).build())
            .setOnlyAlertOnce(true)
            .setOngoing(false)
            .setAutoCancel(true)
        if(recording.active)builder.addAction(Notification.Action.Builder(null,"다음 장면",actionIntent("tour-next",1043)).build())
        val notification=builder.build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(COMPANION_ID,notification) }
    }

    fun showDeparture(context: Context, session: RestSession, now: Long = System.currentTimeMillis()) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val decision = RestLogic.calculate(session, now)
        val requestedRestEnded = session.startedAtMs > 0L && now >= session.startedAtMs + session.requestedMinutes * 60_000L
        val title = when {
            decision.cannotMeetDeadline -> "도착이 늦어질 수 있어요"
            requestedRestEnded -> "설정한 휴식 시간이 끝났어요"
            else -> "출발할 시간이에요"
        }
        val text = if (decision.cannotMeetDeadline)
            "현재 예상 시각에 늦을 수 있어요. 무리한 운전 대신 연락을 권장해요."
            else "휴식을 마치고 안전을 확인한 뒤 출발하세요."
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(homeIntent(context))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(ALERT_ID, notification)
    }

    fun showRestComplete(context: Context, session: RestSession) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val decision=RestLogic.calculate(session,System.currentTimeMillis())
        val formatter=java.text.SimpleDateFormat("HH:mm",java.util.Locale.KOREA)
        val expected=formatter.format(java.util.Date(decision.predictedArrivalMs))
        val text=if(decision.cannotMeetDeadline) "현재 예상 $expected 도착 · 지각 연락을 권장해요. 무리한 운전은 피하세요."
                 else "현재 예상 $expected 도착 · 준비되면 안전하게 출발하세요."
        val notification=Notification.Builder(context,CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("RestSync · 휴식 완료")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(homeIntent(context))
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(103,notification)
    }

    fun showWakeup(context:Context,session:RestSession) {
        ensureChannel(context)
        if(Build.VERSION.SDK_INT>=33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return
        val text="${session.stopName}에서 휴식을 마치고 안전하게 출발을 준비해 주세요."
        val notice=Notification.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("이제 일어날 시간이에요").setContentText(text)
            .setContentIntent(homeIntent(context)).setCategory(Notification.CATEGORY_ALARM)
            .setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java).notify(ALERT_ID,notice)
    }

    fun dismissWakeup(context:Context) {
        context.getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
    }

    private fun alarmIntent(context: Context) = PendingIntent.getBroadcast(
        context, ALARM_CODE,
        Intent(context, RestAlertReceiver::class.java).setAction(ACTION_ALERT),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(context: Context, session: RestSession) {
        cancel(context)
        if (session.stage != "RESTING") return
        val alarmAt = RestLogic.calculate(session, System.currentTimeMillis()).nextAlertMs
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Fallback reminder when foreground service is stopped by the system.
        // setAndAllowWhileIdle is INEXACT: Android may delay it; the foreground monitor
        // checks the deadline every 15 seconds while it is running.
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alarmAt.coerceAtLeast(System.currentTimeMillis() + 1000L), alarmIntent(context))
    }

    fun cancel(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(alarmIntent(context))
    }
}

/** Inexact fallback alarm; service handles periodic checks while alive. */
class RestAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val session = SessionStore(context).read()
        if (session.stage != "RESTING") return
        val now = System.currentTimeMillis()
        val decision = RestLogic.calculate(session, now)
        if (decision.shouldNotifyNow && session.alertedForMs != decision.nextAlertMs) {
            val completed=FlowPolicy.due(session,now)
            SessionStore(context).write(completed)
            RestAlerts.showWakeup(context, completed)
        }
    }
}
