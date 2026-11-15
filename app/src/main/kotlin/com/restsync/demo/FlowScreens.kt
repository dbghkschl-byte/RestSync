package com.restsync.demo

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal class FlowScreens(
    private val a:Activity,private val s:RestSession,
    private val setup:()->Unit,private val connect:()->Unit,private val resetRoute:()->Unit,
    private val find:()->Unit,private val arrived:()->Unit,private val navigate:()->Unit,
    private val extend:()->Unit,private val depart:()->Unit,private val acknowledge:()->Unit,
    private val resume:()->Unit,private val timer:(TextView)->Unit,
    private val now:()->Long=System::currentTimeMillis
) {
    private fun dp(n:Int)=RestTheme.dp(a,n)
    private fun label(t:String,size:Float=22f,white:Boolean=false,bold:Boolean=false)=RestTheme.label(a,t,size.coerceAtLeast(20f),if(white) Color.WHITE else RestTheme.ink,bold)
    private fun add(p:LinearLayout,v:View,gap:Int=12)=p.addView(v,RestTheme.margins(a,gap))
    private fun button(p:LinearLayout,t:String,primary:Boolean=true,click:()->Unit)=add(p,RestTheme.button(a,t,primary,click),14)
    private fun time(ms:Long)=SimpleDateFormat("HH:mm",Locale.KOREA).format(Date(ms))
    private fun centered(t:String,size:Float,white:Boolean=true)=label(t,size,white,true).apply {gravity=Gravity.CENTER}

    fun view():View = when(s.stage) {
        "RESTING" -> resting()
        "DEPARTURE" -> alarm()
        "FINISHED" -> complete()
        "PLAN" -> welcome()
        else -> menu()
    }

    private fun welcome():View {
        val frame=FrameLayout(a)
        frame.addView(RestScene(a,true),FrameLayout.LayoutParams(-1,-1))
        val page=LinearLayout(a).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(40),dp(28),dp(40),dp(28))}
        add(page,centered("RestSync",24f),20)
        add(page,centered("목적지를 정하고,\n쉬어갈 시간을 함께 계획해요.",36f),32)
        val card=RestTheme.container(a,24,0xEEFFFFFF.toInt(),26)
        button(card,"목적지 설정",true,setup)
        button(card,"이미 안내 중인 경로 연결",false,connect)
        add(card,label("길 안내는 Pleos 내비에서 이어집니다.",15f).apply {gravity=Gravity.CENTER},0)
        page.addView(card,LinearLayout.LayoutParams(-1,-2))
        frame.addView(ScrollView(a).apply {isFillViewport=true;addView(page)},FrameLayout.LayoutParams(-1,-1))
        return frame
    }

    private fun menu():View {
        val page=RestTheme.container(a,24,RestTheme.snow,0)
        add(page,label(if(s.stage=="WAYPOINT") "쉬어갈 곳으로 안내 중이에요" else "여정을 이어갈까요?",34f,bold=true),20)
        val card=RestTheme.container(a,22)
        add(card,label(if(s.stage=="WAYPOINT") s.stopName else s.destinationName,30f,bold=true),14)
        add(card,label("최종 목적지 · ${s.destinationName}",15f),24)
        if(s.stage=="WAYPOINT") {
            if(s.waypointProof=="NAME_ONLY")add(card,label("휴게소 등록은 이름으로 확인했어요. 위치는 내비 화면에서 확인해 주세요.",20f),16)
            button(card,"휴게소 도착",true,arrived)
            button(card,"아직 이동 중 · 내비로",false,navigate)
            add(card,label("도착 버튼은 수동 확인입니다. 안전하게 정차한 뒤 눌러 주세요.",14f),0)
        } else {
            button(card,"휴식 · 휴게소 찾아줘",true,find)
            button(card,"경로 재탐색 · 목적지 다시 설정",false,resetRoute)
        }
        add(page,card,18)
        add(page,label("내비 옆 RestSync에서 휴식만 간단하게 준비하세요.",15f),16)
        if(s.source=="DEMO")add(page,label("시연 모드 · 실제 내비 경유지는 바뀌지 않았어요.",14f),10)
        return ScrollView(a).apply {isFillViewport=true;addView(page)}
    }

    private fun backdrop(road:Boolean=false,body:(LinearLayout)->Unit):View {
        val frame=FrameLayout(a)
        frame.addView(RestScene(a,road),FrameLayout.LayoutParams(-1,-1))
        val page=LinearLayout(a).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(28),dp(25),dp(28),dp(25))}
        body(page)
        if(a.resources.configuration.screenWidthDp<1100) {
            add(page,centered("넓게 보려면 시스템 윈도 바를 화면 끝까지 드래그해 주세요.",14f),0)
        }
        frame.addView(ScrollView(a).apply {isFillViewport=true;addView(page)},FrameLayout.LayoutParams(-1,-1))
        return frame
    }
    private fun glass()=RestTheme.container(a,24,0xD836315E.toInt(),32)

    private fun resting():View=backdrop { page ->
        add(page,centered("☕  ${s.stopName}에서 휴식 중",30f),28)
        val row=LinearLayout(a).apply {orientation=if(a.resources.configuration.screenWidthDp<650) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL}
        val clock=glass()
        add(clock,centered("남은 휴식 시간",19f),14)
        val d=RestLogic.calculate(s,now())
        val remain=((d.nextAlertMs-now()).coerceAtLeast(0)/1000)
        val count=centered("%02d:%02d".format(remain/60,remain%60),76f)
        timer(count);add(clock,count,10)
        val traffic=glass()
        add(traffic,centered("교통 상황",23f),16)
        val delta=s.driveMinutes-s.baselineDriveMinutes+s.simulatedDelayMinutes
        val summary=when {
            s.simulatedDelayMinutes>0 -> "정체 +${s.simulatedDelayMinutes}분 · 시연"
            s.source=="DEMO" && s.lastEtaAtMs==0L -> "시연 교통 정보"
            s.lastEtaAtMs==0L -> "내비 교통 확인 중"
            delta<0 -> "정체 완화"
            delta>0 -> "이동 시간 증가"
            else -> "큰 변화 없어요"
        }
        add(traffic,centered(summary,27f).apply {setTextColor(if(delta<0)0xFFB5E395.toInt() else Color.WHITE)},12)
        add(traffic,centered(if(s.simulatedDelayMinutes>0) "가상 정체를 더해 계산 중이에요" else if(s.source=="DEMO" && s.lastEtaAtMs==0L) "설정에서 교통 변화를 시연해요" else if(s.lastEtaAtMs==0L) "약 90초마다 확인해요" else "예상 이동 ${if(delta>0) "+" else ""}${delta}분",20f),10)
        add(traffic,centered(if(s.lastEtaAtMs>0L) "최근 확인 ${time(s.lastEtaAtMs)}" else "현재 내비 위치 기준",14f),0)
        if(row.orientation==LinearLayout.HORIZONTAL) {
            row.addView(clock,LinearLayout.LayoutParams(0,-2,1f).apply {rightMargin=dp(16)})
            row.addView(traffic,LinearLayout.LayoutParams(0,-2,1f))
        } else {add(row,clock);add(row,traffic)}
        add(page,row,20)
        if(s.simulatedDelayMinutes>0) add(page,centered(FlowPolicy.simulationEffect(s,now()),20f),14)
        if(!s.respectDeadline) add(page,centered("도착 목표에 여유가 없어 선택한 휴식 시간으로 알려드려요.",15f),14)
        val actions=RestTheme.row(a)
        actions.addView(RestTheme.button(a,"+ 5분 연장",false,extend),LinearLayout.LayoutParams(0,dp(80),1f).apply {rightMargin=dp(16)})
        actions.addView(RestTheme.button(a,"출발하기",true,depart),LinearLayout.LayoutParams(0,dp(80),1f))
        add(page,actions,0)
    }

    private fun alarm():View=backdrop { page ->
        val card=glass()
        add(card,centered("◷",64f),12)
        add(card,centered("이제 일어날 시간이에요",38f),18)
        add(card,centered("휴식을 마치고 안전하게 출발을 준비해 주세요.",20f),24)
        button(card,"확인 · 휴식 마치기",true,acknowledge)
        add(page,card,0)
    }

    private fun complete():View=backdrop(true) { page ->
        val card=glass()
        add(card,centered("✓",88f).apply {setTextColor(0xFFD7C6FF.toInt())},6)
        add(card,centered("휴식 완료!",42f),14)
        add(card,centered("목적지까지 계속 안내할게요.",22f),26)
        val d=RestLogic.calculate(s,now())
        val distance=if(s.remainingDistanceMeters>0) " · 내비 남은 거리 ${s.remainingDistanceMeters/1000} km" else ""
        add(card,centered("도착 예상 ${time(d.predictedArrivalMs)}$distance",23f),12)
        add(card,centered(s.destinationName,18f),24)
        if(s.source=="DEMO")add(card,centered("시연 예측값 · 실제 차량 위치는 바뀌지 않았어요.",14f),14)
        button(card,"✓  내비 안내 계속하기",true,resume)
        add(card,centered("잠시 후 내비 화면으로 돌아갑니다.",14f),0)
        add(page,card,0)
    }
}
