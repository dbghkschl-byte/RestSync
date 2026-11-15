package com.restsync.demo

import android.app.Activity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Responsive companion UI entirely inside RestSync's own application window. */
internal class JourneyDashboard(
    private val a: Activity, private val s: RestSession,
    private val connect: ()->Unit, private val find: ()->Unit, private val navigate: ()->Unit,
    private val parked: ()->Unit, private val deadline: ()->Unit, private val duration: ()->Unit,
    private val voice: ()->Unit, private val demo: ()->Unit, private val destination: ()->Unit,
    private val select: (Int)->Unit
) {
    private val narrow = a.resources.configuration.screenWidthDp < 560
    private fun text(value:String,size:Float=16f,muted:Boolean=false,bold:Boolean=false) =
        RestTheme.label(a,value,size,if(muted) RestTheme.muted else RestTheme.ink,bold)
    private fun block()=RestTheme.container(a,if(narrow)18 else 24)
    private fun add(parent:LinearLayout,v:View,gap:Int=12) { parent.addView(v,RestTheme.margins(a,gap)) }
    private fun button(parent:LinearLayout,title:String,primary:Boolean=true,action:()->Unit) = add(parent,RestTheme.button(a,title,primary,action))
    private fun time(ms:Long)=SimpleDateFormat("HH:mm",Locale.KOREA).format(Date(ms))

    fun view(): View {
        val column=LinearLayout(a).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(RestTheme.dp(a,if(narrow)16 else 28),RestTheme.dp(a,20),RestTheme.dp(a,if(narrow)16 else 28),RestTheme.dp(a,24))
        }
        val connected=s.stage!="PLAN"
        val isDemo=connected && s.source=="DEMO"
        add(column,RestTheme.label(a,when {
            isDemo -> "시연 중 · 실제 내비 경로는 바뀌지 않아요"
            !connected -> "01  경로 연결    ›    02  휴게소 선택    ›    03  휴식"
            s.stage=="WAYPOINT" -> "경유지 연결 완료 · 도착 후 안전하게 쉬세요"
            else -> "Pleos 내비와 함께하는 휴식 계획"
        },13f,RestTheme.deepPurple,true),18)
        add(column,text(when(s.stage) {
            "PLAN" -> "어디로 가든,\n쉬어갈 시간은 필요하니까."
            "WAYPOINT" -> "쉬어갈 곳을 정했어요."
            "SUGGESTIONS" -> "어디에서 쉬어갈까요?"
            else -> "필요할 때, 잠깐 쉬어가세요."
        },if(narrow)27f else 34f,bold=true),12)
        add(column,text(if(connected) "안내는 Pleos에서, 휴식 계획은 여기에서 이어가세요." else "이미 내비에 목적지를 넣었다면 한 번만 연결해 주세요.",16f,true),22)
        val journey=block()
        add(journey,text(if(connected) "지금 가는 곳" else "연결된 경로가 없어요",13f,true),7)
        if(connected) add(journey,text(s.destinationName,25f,bold=true),18)
        else add(journey,text("Pleos에서 안내 중인 목적지를 가져올 수 있어요.",18f,bold=true),18)
        val metrics=LinearLayout(a).apply { orientation=if(narrow) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL }
        val goal=RestTheme.metric(a,"도착 목표",time(s.deadlineMs),"눌러서 시간 변경").apply {setOnClickListener {deadline()}}
        val rest=RestTheme.metric(a,"원하는 휴식","${s.requestedMinutes}분","눌러서 시간 변경").apply {setOnClickListener {duration()}}
        if(narrow) { add(metrics,goal,8);add(metrics,rest,8) }
        else {
            metrics.addView(goal,LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=RestTheme.dp(a,10)})
            metrics.addView(rest,LinearLayout.LayoutParams(0,-2,1f))
        }
        add(journey,metrics,18)
        if(connected && s.source=="PLEOS" && s.lastEtaAtMs>0) {
            val remaining=s.driveMinutes + if(s.stage=="WAYPOINT") s.approachMinutes else 0
            add(journey,text("남은 이동 약 ${remaining}분 · 마지막으로 확인한 예상",14f,true),10)
            if(System.currentTimeMillis()+(remaining+s.requestedMinutes)*60_000L>s.deadlineMs) {
                add(journey,RestTheme.label(a,"현재 목표까지 여유가 부족해요. 도착 목표나 휴식 시간을 조정해 주세요.",15f,RestTheme.red,true),16)
            }
        }
        if(s.stage=="WAYPOINT") {
            add(journey,RestTheme.label(a,s.stopName,23f,RestTheme.deepPurple,true),8)
            add(journey,text("휴게소까지 약 ${s.approachMinutes}분 · ${if(isDemo) "시연 값" else "외부 경로 추정"}",14f,true),18)
            button(journey,"도착했어요 · 정차 확인 후 휴식",action=parked)
            button(journey,"내비 안내로 돌아가기",false,navigate)
        } else if(connected) {
            button(journey,if(s.stage=="SUGGESTIONS") "다른 휴게소 다시 찾아줘" else "휴게소 찾아줘",action=find)
            button(journey,"내비 안내로 돌아가기",false,navigate)
        } else {
            button(journey,"현재 내비 경로 연결",action=connect)
            button(journey,"목적지부터 정하기",false,connect)
        }
        add(column,journey,16)
        if(s.stage=="SUGGESTIONS" && s.source=="PLEOS") addCandidates(column)
        val help=block()
        add(help,text("내비 옆에 두고 쓰세요",19f,bold=true),8)
        add(help,text("Pleos 윈도 바에서 RestSync 창을 나란히 배치해 보세요. 전체 화면 내비에서는 알림을 눌러 다시 열 수 있어요.",14f,true),16)
        if(connected) button(help,"목적지·경로 다시 연결",false,connect)
        if(connected && s.stage!="WAYPOINT") button(help,"이미 정차했어요 · 직접 휴식 시작",false,parked)
        button(help,"말로 요청하기 · 음성 시연",false,voice)
        if(!connected) {
            button(help,"연결 없이 기능 체험하기",false,demo)
            button(help,"앱에서 장소 직접 검색 · 연결 설정",false,destination)
        }
        add(column,help,12)
        return ScrollView(a).apply { isFillViewport=true; addView(column) }
    }

    private fun addCandidates(parent:LinearLayout) {
        val pref=a.getSharedPreferences("restsync_route_candidates",Activity.MODE_PRIVATE)
        val target=pref.getString("target","").orEmpty()
        val correct=target=="${s.destinationLat},${s.destinationLon}"
        val fresh=System.currentTimeMillis()-pref.getLong("savedAtMs",0L) in 0..300_000
        val items=runCatching { JSONArray(if(fresh && correct) pref.getString("items","[]") else "[]") }.getOrDefault(JSONArray())
        if(items.length()>0 && pref.getBoolean("destinationOnly",false)) {
            add(parent,text("목적지 기준 추천 · 내비가 일부 기존 경유지의 좌표를 제공하지 않았어요. 실제 안내와 비교해 주세요.",14f,true),12)
        }
        if(items.length()==0) {
            add(parent,text("추천 결과가 없거나 시간이 지났어요. 다시 찾으면 최신 경로로 확인할게요.",15f,true),16)
            return
        }
        for(i in 0 until minOf(3,items.length())) {
            val item=items.optJSONObject(i) ?: continue
            val card=block()
            add(card,text("${i+1}. ${item.optString("name")}",22f,bold=true),8)
            add(card,text("휴게소까지 약 ${item.optLong("approach")}분 · 이후 주행 약 ${item.optLong("drive")}분",14f,true),16)
            button(card,"이곳에서 쉴래요 · 경유지 확인",false) { select(i) }
            add(parent,card,12)
        }
    }
}
