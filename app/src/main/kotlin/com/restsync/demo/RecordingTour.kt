package com.restsync.demo

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/** Guide state only. Real navigation and rest-session changes belong to their existing screens. */
internal object RecordingTour {
    enum class Step {
        DESTINATION, ROUTE_RESET, REST_SEARCH, ARRIVAL, WAIT_ETA,
        REST30, REST35, TRAFFIC, ACCELERATE, ALARM, COMPLETE,
        CLEANUP, RESTART_PENDING, DONE
    }

    data class State(
        val active:Boolean,
        val paused:Boolean,
        val step:Step,
        val elapsedMs:Long,
        val destinationVisits:Int=0,
        val automated:Boolean=false
    )

    private const val PREFS="restsync_recording_tour_v1"
    private val lock=Any()
    private fun prefs(context:Context)=context.applicationContext.getSharedPreferences(PREFS,Context.MODE_PRIVATE)

    fun read(context:Context):State=synchronized(lock) { read(prefs(context),System.currentTimeMillis()) }

    private fun read(p:SharedPreferences,now:Long):State {
        val active=p.getBoolean("active",false)
        val paused=p.getBoolean("paused",false)
        val step=runCatching {Step.valueOf(p.getString("step",Step.DESTINATION.name).orEmpty())}.getOrDefault(Step.DESTINATION)
        val saved=p.getLong("elapsed",0).coerceAtLeast(0)
        val since=p.getLong("since",0)
        val running=if(active && !paused && since>0) (now-since).coerceAtLeast(0) else 0
        val elapsed=saved+running.coerceAtMost(Long.MAX_VALUE-saved)
        return State(active,paused,step,elapsed,p.getInt("destinationVisits",0).coerceAtLeast(0),p.getBoolean("automated",false))
    }

    fun start(context:Context,automated:Boolean=false)=synchronized(lock) {
        prefs(context).edit().putBoolean("active",true).putBoolean("paused",false)
            .putBoolean("automated",automated)
            .putString("step",Step.DESTINATION.name).putLong("elapsed",0)
            .putLong("since",System.currentTimeMillis()).putInt("destinationVisits",0).apply()
    }
    fun setAutomated(context:Context,value:Boolean) {prefs(context).edit().putBoolean("automated",value).apply()}

    /** Ends this guide only; it does not claim to undo any native route operation. */
    fun stop(context:Context)=synchronized(lock) {
        val p=prefs(context);val current=read(p,System.currentTimeMillis())
        p.edit().putBoolean("active",false).putBoolean("paused",false)
            .putLong("elapsed",current.elapsedMs).putLong("since",0).apply()
    }

    fun setStep(context:Context,step:Step)=synchronized(lock) {
        val p=prefs(context);val current=read(p,System.currentTimeMillis())
        if(!current.active || current.step==step)return@synchronized
        writeStep(p,step,current.paused,System.currentTimeMillis())
    }

    private fun writeStep(p:SharedPreferences,step:Step,paused:Boolean,now:Long) {
        p.edit().putString("step",step.name).putLong("elapsed",0)
            .putLong("since",if(paused)0 else now).apply()
    }

    fun togglePause(context:Context)=synchronized(lock) {
        val p=prefs(context);val now=System.currentTimeMillis();val current=read(p,now)
        if(!current.active)return@synchronized
        p.edit().putBoolean("paused",!current.paused).putLong("elapsed",current.elapsedMs)
            .putLong("since",if(current.paused)now else 0).apply()
    }

    fun onDestinationConfirmed(context:Context)=synchronized(lock) {
        val p=prefs(context);val current=read(p,System.currentTimeMillis())
        if(!current.active)return@synchronized
        val next=when(current.step) {
            Step.DESTINATION -> Step.ROUTE_RESET
            Step.ROUTE_RESET -> Step.REST_SEARCH
            else -> return@synchronized
        }
        p.edit().putInt("destinationVisits",(current.destinationVisits+1).coerceAtMost(2)).apply()
        writeStep(p,next,current.paused,System.currentTimeMillis())
    }

    fun onWaypointConfirmed(context:Context)=advanceWhen(context,Step.REST_SEARCH,Step.ARRIVAL)
    fun onRestStarted(context:Context)=advanceWhen(context,Step.ARRIVAL,Step.WAIT_ETA)
    fun markEtaReady(context:Context)=advanceWhen(context,Step.WAIT_ETA,Step.REST30)

    private fun advanceWhen(context:Context,expected:Step,next:Step)=synchronized(lock) {
        val p=prefs(context);val current=read(p,System.currentTimeMillis())
        if(current.active && current.step==expected)writeStep(p,next,current.paused,System.currentTimeMillis())
    }

    fun onNavigationResumed(context:Context)=synchronized(lock) {
        val p=prefs(context);val current=read(p,System.currentTimeMillis())
        if(!current.active)return@synchronized
        if(current.step==Step.CLEANUP || current.step==Step.RESTART_PENDING) {
            p.edit().putBoolean("active",true).putBoolean("paused",false)
                .putString("step",Step.DESTINATION.name).putLong("elapsed",0)
                .putLong("since",System.currentTimeMillis()).putInt("destinationVisits",0).apply()
        } else {
            p.edit().putBoolean("active",false).putBoolean("paused",false)
                .putString("step",Step.DONE.name).putLong("elapsed",0).putLong("since",0).apply()
        }
    }

    fun title(step:Step):String=when(step) {
        Step.DESTINATION -> "목적지 설정"
        Step.ROUTE_RESET -> "경로 재탐색"
        Step.REST_SEARCH -> "실제 휴게소 추천"
        Step.ARRIVAL -> "도착·정차 확인"
        Step.WAIT_ETA -> "내비 예상 시간 확인"
        Step.REST30 -> "30분 휴식"
        Step.REST35 -> "5분 연장 · 35분"
        Step.TRAFFIC -> "정체 +20분 · 약 15분"
        Step.ACCELERATE -> "출발 알림까지 시간 가속"
        Step.ALARM -> "출발 알림"
        Step.COMPLETE -> "휴식 완료"
        Step.CLEANUP -> "경유지 정리"
        Step.RESTART_PENDING -> "재시작 준비"
        Step.DONE -> "시연 완료"
    }

    fun bar(activity:Activity,onNext:()->Unit,onRestart:()->Unit,onStop:()->Unit):View {
        val root=RestTheme.container(activity,12,RestTheme.palePurple,16)
        val title=RestTheme.label(activity,"",20f,RestTheme.deepPurple,true)
        val hint=RestTheme.label(activity,"",18f,RestTheme.ink)
        root.addView(title,RestTheme.margins(activity,6))
        root.addView(hint,RestTheme.margins(activity,10))
        val row=RestTheme.row(activity)
        lateinit var pause:TextView
        fun bind() {
            val state=read(activity)
            root.visibility=if(state.active)View.VISIBLE else View.GONE
            title.text=(if(state.automated)"실제 앱 자동 클릭 · " else "실제 앱 연동 시연 · ")+RecordingTour.title(state.step)
            val backToApp=if(state.step in setOf(Step.ROUTE_RESET,Step.REST_SEARCH,Step.ARRIVAL))
                "내비에서는 RestSync 아이콘/알림으로 돌아오세요.\n" else ""
            val recordingTime=when(state.step) {
                Step.REST30,Step.REST35,Step.TRAFFIC -> "녹화용 도착 목표: 실제 ETA + 휴식 여유 35분\n"
                Step.ACCELERATE -> "녹화용 시간 가속 · 실제 경과시간과 달라요.\n"
                else -> ""
            }
            hint.text=backToApp+recordingTime+if(state.paused) "도우미 일시정지 중 · 실제 휴식 시간과 내비 요청은 계속됩니다."
                else "일시정지는 도우미만 멈춰요. 실제 휴식과 내비 요청은 계속됩니다."
            pause.text=if(state.paused)"도우미 재개" else "일시정지"
        }
        fun button(text:String,click:()->Unit):TextView {
            val v=RestTheme.button(activity,text,false,click).apply {
                textSize=20f;minHeight=RestTheme.dp(activity,56)
                setPadding(RestTheme.dp(activity,8),RestTheme.dp(activity,10),RestTheme.dp(activity,8),RestTheme.dp(activity,10))
            }
            row.addView(v,LinearLayout.LayoutParams(0,-2,1f).apply {
                if(row.childCount>0)marginStart=RestTheme.dp(activity,8)
            })
            return v
        }
        pause=button("일시정지") {
            togglePause(activity)
            val state=read(activity)
            if(state.automated)ClickAutomation.command(activity,if(state.paused)"pause" else "resume")
            bind()
        }
        button("재시작",onRestart)
        button("다음 장면",onNext)
        button("시연 종료",onStop)
        root.addView(row)
        bind()
        return root
    }
}
