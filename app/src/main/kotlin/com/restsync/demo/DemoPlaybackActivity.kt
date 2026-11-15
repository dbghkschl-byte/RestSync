package com.restsync.demo

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import com.restsync.demo.DemoPlayback.Phase
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recording-only local playback; it never writes SessionStore or calls NaviHelper. */
class DemoPlaybackActivity : Activity() {
    private val handler=Handler(Looper.getMainLooper())
    private var positionMs=0L
    private var anchorMs=0L
    private var baseTimeMs=0L
    private var playing=true
    private var foreground=false
    private var audio=true
    private var tts: PleosVoice? = null
    private var pendingSpeech:String?=null
    private var tone:ToneGenerator?=null
    private lateinit var scene:DemoPlayback.Snapshot
    private lateinit var body:FrameLayout
    private lateinit var playButton:TextView
    private lateinit var audioButton:TextView
    private lateinit var progress:ProgressBar
    private lateinit var progressText:TextView
    private lateinit var title:TextView
    private lateinit var caption:TextView
    private var countLabel:TextView?=null
    private var queryLabel:TextView?=null
    private var map:DemoRouteMap?=null
    private var recommendationScroll:ScrollView?=null
    private var demoDialog:Dialog?=null
    private var rendered:Phase?=null

    private val tick=object:Runnable {
        override fun run() {
            if(!foreground || isFinishing)return
            updateFrame()
            handler.postDelayed(this,100L)
        }
    }
    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        positionMs=state?.getLong("position") ?: 0
        baseTimeMs=state?.getLong("baseTime") ?: System.currentTimeMillis()
        playing=state?.getBoolean("playing") ?: true
        audio=state?.getBoolean("audio") ?: true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor=RestTheme.snow
        scene=DemoPlayback.snapshot(positionMs,baseTimeMs)
        buildLayout()
        tts=PleosVoice(this)
    }
    private fun dp(n:Int)=RestTheme.dp(this,n)
    private fun label(text:String,size:Float=24f,bold:Boolean=false)=RestTheme.label(this,text,size,RestTheme.ink,bold)
    private fun stamp(ms:Long):String="%02d:%02d".format(Locale.US,ms/60000,(ms/1000)%60)
    private fun TextView.putText(value:String) {if(text.toString()!=value)text=value}
    private fun currentPosition():Long = (positionMs+if(playing && foreground) SystemClock.elapsedRealtime()-anchorMs else 0L)
        .coerceIn(0,DemoPlayback.TOTAL_DURATION_MS)

    private fun buildLayout() {
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(RestTheme.snow)}
        val header=RestTheme.row(this).apply {setPadding(dp(20),dp(10),dp(20),dp(8));setBackgroundColor(Color.WHITE)}
        val heading=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        heading.addView(label("RestSync · 자동 시연",28f,true))
        heading.addView(label("녹화용 가상 지도·교통 · 음성 요청 시연",18f))
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
        header.addView(RestTheme.button(this,"나가기",false){finish()},LinearLayout.LayoutParams(dp(120),dp(64)))
        root.addView(header)
        val controls=RestTheme.row(this).apply {setPadding(dp(20),dp(4),dp(20),dp(8))}
        fun control(text:String,click:()->Unit):TextView {
            val button=RestTheme.button(this,text,false,click).apply {textSize=21f;minHeight=dp(58)}
            controls.addView(button,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(8)})
            return button
        }
        playButton=control(if(playing)"일시정지" else "계속 재생") {togglePlayback()}
        control("처음부터") {restart()}
        control("다음 장면") {nextScene()}
        audioButton=control(if(audio)"음성 켜짐" else "음성 꺼짐") {
            audio=!audio;audioButton.text=if(audio)"음성 켜짐" else "음성 꺼짐"
            if(!audio) {pendingSpeech=null;tts?.stop();tone?.stopTone()}
        }
        root.addView(controls)
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {max=DemoPlayback.TOTAL_DURATION_MS.toInt();progressTintList=android.content.res.ColorStateList.valueOf(RestTheme.purple)}
        root.addView(progress,LinearLayout.LayoutParams(-1,dp(5)))
        val guide=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(22),dp(10),dp(22),dp(12));setBackgroundColor(RestTheme.palePurple)}
        val line=RestTheme.row(this)
        title=label("",26f,true);line.addView(title,LinearLayout.LayoutParams(0,-2,1f))
        progressText=label("",18f);line.addView(progressText)
        caption=label("",20f)
        guide.addView(line);guide.addView(caption,RestTheme.margins(this,0,6))
        root.addView(guide)
        body=FrameLayout(this);root.addView(body,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root)
        rendered=null
    }

    private fun updateFrame() {
        scene=DemoPlayback.snapshot(currentPosition(),baseTimeMs)
        if(scene.phase!=rendered) {
            dismissDialog();rendered=scene.phase
            renderScene()
            android.util.Log.i("RestSyncDemo","phase=${scene.phase} elapsedMs=${scene.elapsedMs}")
            speak(if(scene.phase==Phase.VOICE_SEARCH) "휴게소 찾아줘. 쉬어갈 휴게소를 바로 찾아드릴게요." else scene.title)
            if(scene.phase==Phase.DEPARTURE && audio) {
                runCatching {tone?.release();tone=ToneGenerator(AudioManager.STREAM_ALARM,70);tone?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD,1000)}
            }
        }
        title.putText(scene.title)
        caption.putText(scene.caption)
        progress.progress=(scene.elapsedMs/1000*1000).toInt()
        progressText.putText("${scene.phaseIndex+1}/${scene.phaseCount} · ${stamp(scene.elapsedMs)} / 01:30")
        val decision=RestLogic.calculate(scene.session,scene.virtualNowMs)
        countLabel?.putText(stamp((decision.nextAlertMs-scene.virtualNowMs).coerceAtLeast(0)))
        queryLabel?.putText(if(scene.phase==Phase.DESTINATION_ENTRY || scene.phase==Phase.RESET_DESTINATION) {
            "부산역".take((scene.phaseProgress*5).toInt().coerceIn(0,3)).ifBlank {"어디로 갈까요?"}
        } else "부산역")
        map?.let {preview->
            val position=when(scene.phase) {
                Phase.ARRIVAL,Phase.REST_DURATION,Phase.PARK_CONFIRM -> preview.stopProgress
                Phase.WAYPOINT_SELECTED -> .4f+(preview.stopProgress-.4f)*scene.phaseProgress
                Phase.DESTINATION_MAP -> ((scene.mapProgress-.6f)/.4f).coerceIn(0f,1f)
                else -> scene.mapProgress
            }
            preview.update(position,scene.phase.ordinal in Phase.WAYPOINT_SELECTED.ordinal..Phase.PARK_CONFIRM.ordinal,
                scene.phase==Phase.DESTINATION_MAP || scene.phase==Phase.END,scene.session.destinationName)
        }
        recommendationScroll?.let {list->
            val range=((list.getChildAt(0)?.height ?: 0)-list.height).coerceAtLeast(0)
            val fraction=when {
                scene.phaseProgress<.22f -> 0f
                scene.phaseProgress<.62f -> (scene.phaseProgress-.22f)/.4f
                scene.phaseProgress<.8f -> 1f
                else -> (1f-scene.phaseProgress)/.2f
            }
            list.scrollTo(0,(range*fraction.coerceIn(0f,1f)).toInt())
        }
        if(scene.isFinished && playing) {
            positionMs=DemoPlayback.TOTAL_DURATION_MS;playing=false;playButton.text="다시 재생"
        }
    }

    private fun renderScene() {
        body.removeAllViews();map=null;countLabel=null;queryLabel=null;recommendationScroll=null
        when(scene.phase) {
            Phase.DESTINATION_ENTRY,Phase.DESTINATION_SELECTED,Phase.RESET_DESTINATION,Phase.RESELECTED_DESTINATION -> body.addView(searchScreen(),FrameLayout.LayoutParams(-1,-1))
            Phase.NAVIGATION,Phase.NAVIGATION_RESUMED,Phase.WAYPOINT_SELECTED,Phase.DESTINATION_MAP -> body.addView(mapScreen(),FrameLayout.LayoutParams(-1,-1))
            Phase.REOPEN_MENU,Phase.ARRIVAL -> body.addView(companionScreen(),FrameLayout.LayoutParams(-1,-1))
            Phase.VOICE_SEARCH -> body.addView(voiceScreen(),FrameLayout.LayoutParams(-1,-1))
            Phase.RECOMMENDATIONS -> body.addView(recommendations(),FrameLayout.LayoutParams(-1,-1))
            Phase.REST_DURATION,Phase.PARK_CONFIRM -> {
                body.addView(companionScreen(),FrameLayout.LayoutParams(-1,-1))
                showConfirmation(scene.phase==Phase.PARK_CONFIRM)
            }
            Phase.RESTING,Phase.EXTENDED,Phase.TRAFFIC_DELAY,Phase.TIME_SKIP,Phase.DEPARTURE,Phase.COMPLETE -> body.addView(flowScreen(),FrameLayout.LayoutParams(-1,-1))
            Phase.END -> body.addView(endScreen(),FrameLayout.LayoutParams(-1,-1))
        }
    }
    private fun page():LinearLayout=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;setPadding(dp(30),dp(22),dp(30),dp(22))}
    private fun scroll(view:View)=ScrollView(this).apply {isFillViewport=true;addView(view)}
    private fun add(parent:LinearLayout,child:View,gap:Int=16)=parent.addView(child,RestTheme.margins(this,gap))

    private fun searchScreen():View {
        val page=page()
        add(page,label(if(scene.phase==Phase.RESET_DESTINATION)"목적지를 다시 정해볼까요?" else "목적지를 설정해 주세요",34f,true),24)
        add(page,label("시연 검색 · 부산역",22f),24)
        val row=RestTheme.row(this)
        queryLabel=label("부산역",30f,true).apply {setPadding(dp(22),dp(20),dp(22),dp(20));background=RestTheme.rounded(this@DemoPlaybackActivity,Color.WHITE,18,RestTheme.line)}
        row.addView(queryLabel,LinearLayout.LayoutParams(0,-2,1f).apply {marginEnd=dp(16)})
        row.addView(RestTheme.button(this,"검색"){jump(if(scene.phase==Phase.RESET_DESTINATION)Phase.RESELECTED_DESTINATION else Phase.DESTINATION_SELECTED)},LinearLayout.LayoutParams(dp(145),-2))
        add(page,row,24)
        if(scene.phase==Phase.DESTINATION_SELECTED || scene.phase==Phase.RESELECTED_DESTINATION) {
            val card=RestTheme.container(this,24)
            add(card,label("부산역",32f,true));add(card,label("부산광역시 동구 중앙대로 206 · 시연 장소",22f),24)
            add(card,RestTheme.button(this,"이 목적지로 안내 시작 →"){jump(if(scene.phase==Phase.RESELECTED_DESTINATION)Phase.NAVIGATION_RESUMED else Phase.NAVIGATION)},0)
            add(page,card)
        } else add(page,label("잠시 후 목적지를 선택하고 안내를 시작해요.",22f))
        return scroll(page)
    }
    private fun newMap():DemoRouteMap=DemoRouteMap(this).also {map=it}
    private fun mapScreen():View {
        val frame=FrameLayout(this)
        frame.addView(newMap(),FrameLayout.LayoutParams(-1,-1))
        if(scene.phase==Phase.NAVIGATION || scene.phase==Phase.NAVIGATION_RESUMED) {
            frame.addView(RestTheme.button(this,"RestSync 다시 열기"){jump(if(scene.phase==Phase.NAVIGATION)Phase.REOPEN_MENU else Phase.VOICE_SEARCH)},FrameLayout.LayoutParams(dp(320),dp(72),Gravity.TOP or Gravity.END).apply {topMargin=dp(105);marginEnd=dp(22)})
        }
        return frame
    }
    private fun flowScreen():View=FlowScreens(this,scene.session,
        setup={jump(Phase.DESTINATION_ENTRY)},connect={jump(Phase.NAVIGATION)},resetRoute={jump(Phase.RESET_DESTINATION)},
        find={jump(Phase.VOICE_SEARCH)},arrived={jump(Phase.REST_DURATION)},navigate={jump(Phase.WAYPOINT_SELECTED)},
        extend={jump(Phase.EXTENDED)},depart={jump(Phase.DEPARTURE)},acknowledge={jump(Phase.COMPLETE)},
        resume={jump(Phase.DESTINATION_MAP)},timer={countLabel=it},now={scene.virtualNowMs}).view()

    private fun companionScreen():View {
        val row=LinearLayout(this)
        val stacked=resources.configuration.screenWidthDp<900
        row.orientation=if(stacked)LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        row.addView(newMap(),if(stacked)LinearLayout.LayoutParams(-1,dp(280)) else LinearLayout.LayoutParams(0,-1,.44f))
        row.addView(flowScreen(),if(stacked)LinearLayout.LayoutParams(-1,0,1f) else LinearLayout.LayoutParams(0,-1,.56f))
        return row
    }
    private fun voiceScreen():View {
        val frame=FrameLayout(this);frame.addView(newMap(),FrameLayout.LayoutParams(-1,-1))
        val panel=RestTheme.container(this,30,RestTheme.snow)
        add(panel,label("음성 요청 시연",26f,true));add(panel,label("“휴게소 찾아줘”",42f,true),22)
        add(panel,label("쉬어갈 휴게소를 바로 찾아드릴게요.",26f));add(panel,label("가상 요청 재생 · 마이크를 사용하지 않아요.",20f))
        frame.addView(panel,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply {marginStart=dp(42);marginEnd=dp(42)})
        return frame
    }
    private fun recommendations():View {
        val picker=RestSyncRouteUi(this,onBack={jump(Phase.NAVIGATION_RESUMED)},onSearch={},
            onSelect={id->if(id=="demo-0")jump(Phase.WAYPOINT_SELECTED) else Toast.makeText(this,"자동 시연은 안성휴게소를 선택해 진행해요.",Toast.LENGTH_SHORT).show()},
            onDeadlineClick=null,onSettings={Toast.makeText(this,"녹화용 고정 시나리오예요. 실제 설정은 보존됩니다.",Toast.LENGTH_SHORT).show()})
        picker.setDestination("부산역")
        picker.setDeadline(SimpleDateFormat("HH:mm",Locale.KOREA).format(Date(scene.session.deadlineMs)),30)
        picker.setStatus("녹화용 추천 예시 · 잠시 후 안성휴게소를 경유지로 선택해요.")
        picker.showStops(DemoPlayback.candidates.mapIndexed {index,c->RouteUiStop(c.name,c.direction,c.approachMinutes,c.driveMinutes,c.restMinutes,id="demo-$index")})
        picker.setBusy(false,true)
        recommendationScroll=(0 until picker.root.childCount).map {picker.root.getChildAt(it)}.filterIsInstance<ScrollView>().firstOrNull()
        return picker.root
    }
    private fun showConfirmation(parked:Boolean) {
        val panel=page()
        add(panel,label(if(parked)"안전한 장소에 정차하고\nP단으로 변속했나요?" else "안성휴게소 도착 · 선택한 휴식 시간",28f,true),24)
        add(panel,label(if(parked)"정차·P단 확인" else "30분",52f,true).apply {gravity=Gravity.CENTER},24)
        add(panel,label(if(parked)"녹화용 가상 확인입니다. 실제 사용 시 직접 확인해야 해요." else "잠시 후 선택한 시간으로 휴식을 시작해요.",22f))
        val next=if(parked)Phase.RESTING else Phase.PARK_CONFIRM
        demoDialog=RestDialog.create(this,if(parked)"정차·P단 확인 · 시연" else "몇 분 쉬실까요? · 시연",panel,"닫기",if(parked)"확인 · 휴식 시작" else "선택한 시간으로 계속") {jump(next)}
        demoDialog?.setOnCancelListener {if(playing)togglePlayback()}
        RestDialog.show(this,demoDialog!!)
    }
    private fun endScreen():View {
        val page=page()
        add(page,label("✓  자동 시연 완료",40f,true),26)
        add(page,label("목적지 설정 · 경로 재탐색 · 휴게소 추천\n경유지 선택 · 정차 확인 · 휴식 · 교통 변화 · 출발 알림",26f),24)
        add(page,label("실제 경로와 저장된 설정은 그대로 유지됩니다.",22f),28)
        add(page,RestTheme.button(this,"▶  처음부터 다시 재생"){restart()})
        add(page,RestTheme.button(this,"시연 종료 · 내 화면으로",false){finish()})
        return scroll(page)
    }
    private fun dismissDialog() {demoDialog?.setOnCancelListener(null);demoDialog?.dismiss();demoDialog=null}
    private fun jump(phase:Phase) {
        positionMs=DemoPlayback.startOf(phase);anchorMs=SystemClock.elapsedRealtime();rendered=null
        updateFrame()
    }
    private fun nextScene() {
        val next=Phase.entries.getOrNull(scene.phaseIndex+1) ?: return
        jump(next)
    }
    private fun restart() {
        positionMs=0;baseTimeMs=System.currentTimeMillis();anchorMs=SystemClock.elapsedRealtime();playing=true
        playButton.text="일시정지";rendered=null;updateFrame()
    }
    private fun togglePlayback() {
        if(scene.isFinished) {restart();return}
        positionMs=currentPosition();playing=!playing;anchorMs=SystemClock.elapsedRealtime()
        playButton.text=if(playing)"일시정지" else "계속 재생"
        if(!playing) {tts?.stop();tone?.stopTone();pendingSpeech=null}
    }
    private fun speak(text:String) {
        if(!audio || !foreground || !playing)return
        tts?.speak(text)
    }
    override fun onResume() {
        super.onResume();foreground=true;anchorMs=SystemClock.elapsedRealtime()
        handler.removeCallbacks(tick);handler.post(tick)
    }
    override fun onPause() {
        positionMs=currentPosition();foreground=false;handler.removeCallbacks(tick)
        tts?.stop();tone?.stopTone();pendingSpeech=null
        super.onPause()
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putLong("position",currentPosition());out.putLong("baseTime",baseTimeMs)
        out.putBoolean("playing",playing);out.putBoolean("audio",audio);super.onSaveInstanceState(out)
    }
    override fun onConfigurationChanged(config:android.content.res.Configuration) {
        super.onConfigurationChanged(config);dismissDialog();buildLayout();body.post {updateFrame()}
    }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null);dismissDialog();tts?.stop();tts?.close();tone?.release()
        super.onDestroy()
    }
}
