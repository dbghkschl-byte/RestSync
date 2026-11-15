package com.restsync.demo

import android.app.Activity
import android.view.Gravity
import android.view.View
import android.widget.*

internal data class RouteUiStop(
    val name:String,val direction:String,val minutesToStop:Long,val minutesAfterStop:Long,
    val maxRestMinutes:Long,val category:String="휴게소",val id:String=""
)

/** Opening this result screen starts searching; credentials live in Settings. */
internal class RestSyncRouteUi(
    private val activity:Activity,private val onBack:()->Unit,private val onSearch:()->Unit,
    private val onSelect:(String)->Unit,private val onDeadlineClick:(()->Unit)?=null,
    private val onSettings:()->Unit
) {
    private fun dp(n:Int)=RestTheme.dp(activity,n)
    private fun text(t:String,size:Float=22f,bold:Boolean=false)=RestTheme.label(activity,t,size,RestTheme.ink,bold)
    private fun gap(n:Int)=LinearLayout.LayoutParams(-1,-2).apply {bottomMargin=dp(n)}
    private var requestedRest=40L
    private var allStops=emptyList<RouteUiStop>()
    private var busy=true
    private var settingsNeeded=false
    private var canSelect=false
    private var retryAvailable=false
    val root=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(RestTheme.snow)}
    private val headline=text("쉬어갈 휴게소를 찾고 있어요",32f,true)
    private val destination=text("현재 내비 경로를 확인하고 있어요",22f)
    private val summary=text("",22f)
    private val status=text("잠시만 기다려 주세요…",22f)
    private val progress=ProgressBar(activity).apply {isIndeterminate=true}
    private val stopList=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL}
    private val routeContext=text("경유지 반영",19f)
    private val settingsAction=RestTheme.button(activity,"검색 설정 열기",false,onSettings).apply {visibility=View.GONE}
    private val retryAction=RestTheme.button(activity,"다시 찾아보기",false,onSearch).apply {visibility=View.GONE}
    private val goalAction=RestTheme.button(activity,"도착 목표 조정",false) {onDeadlineClick?.invoke()}.apply {visibility=View.GONE}

    init {
        val header=RestTheme.row(activity).apply {setPadding(dp(18),dp(10),dp(24),dp(10));setBackgroundColor(RestTheme.white)}
        header.addView(RestTheme.button(activity,"‹",false,onBack),LinearLayout.LayoutParams(dp(72),dp(64)))
        header.addView(text("쉬어갈 휴게소",28f,true),LinearLayout.LayoutParams(0,-2,1f).apply {leftMargin=dp(18)})
        header.addView(RestTheme.button(activity,"설정",false,onSettings),LinearLayout.LayoutParams(dp(120),dp(64)))
        root.addView(header,LinearLayout.LayoutParams(-1,-2))
        val page=LinearLayout(activity).apply {orientation=LinearLayout.VERTICAL;setPadding(dp(26),dp(26),dp(26),dp(26))}
        page.addView(headline,gap(16));page.addView(destination,gap(16))
        summary.apply {
            setPadding(dp(20),dp(18),dp(20),dp(18))
            background=RestTheme.rounded(activity,RestTheme.palePurple,18,RestTheme.line)
            onDeadlineClick?.let {action->isClickable=true;isFocusable=true;setOnClickListener {action()}}
        }
        page.addView(summary,gap(14));page.addView(goalAction,gap(20))
        val loading=RestTheme.row(activity)
        loading.addView(progress,LinearLayout.LayoutParams(dp(34),dp(34)).apply {rightMargin=dp(14)})
        loading.addView(status,LinearLayout.LayoutParams(0,-2,1f))
        page.addView(loading,gap(20));page.addView(settingsAction,gap(16));page.addView(retryAction,gap(16))
        page.addView(routeContext,gap(14));page.addView(stopList,gap(0))
        page.addView(RestTheme.label(activity,"마음에 드는 곳을 누르면 내비 경유지로 추가해요.",20f,RestTheme.muted),gap(14))
        root.addView(ScrollView(activity).apply {isFillViewport=true;addView(page)},LinearLayout.LayoutParams(-1,0,1f))
    }

    fun setDestination(name:String) {destination.text="${name.ifBlank {"목적지"}}까지 가는 길"}
    fun setDestinationOnly(value:Boolean) {routeContext.text=if(value) "목적지 기준 추천 · 내비 경로와 다를 수 있어요" else "경유지를 반영한 추천"}
    fun setDeadline(deadline:String,requestedMinutes:Long) {
        requestedRest=requestedMinutes.coerceAtLeast(0)
        summary.text="도착 목표 $deadline  ·  희망 휴식 ${requestedRest}분"
        renderStops()
    }
    fun setSettingsNeeded(value:Boolean) {settingsNeeded=value;updateState()}
    fun setStatus(message:String) {status.text=message}
    fun setRetryAvailable(value:Boolean) {retryAvailable=value;updateState()}
    fun setBusy(value:Boolean,selectionReady:Boolean=false) {if(busy==value && canSelect==selectionReady)return;busy=value;canSelect=selectionReady;updateState();renderStops()}
    fun showStops(stops:List<RouteUiStop>) {if(allStops==stops)return;allStops=stops;updateState();renderStops()}
    private fun updateState() {
        progress.visibility=if(busy) View.VISIBLE else View.GONE
        settingsAction.visibility=if(settingsNeeded && !busy) View.VISIBLE else View.GONE
        retryAction.visibility=if(!busy && !settingsNeeded && (allStops.isEmpty() || retryAvailable)) View.VISIBLE else View.GONE
        goalAction.visibility=if(onDeadlineClick!=null && allStops.isNotEmpty() && allStops.all {it.maxRestMinutes<requestedRest}) View.VISIBLE else View.GONE
        headline.text=when {busy && allStops.isNotEmpty()->"찾은 곳부터 먼저 골라도 돼요";busy->"쉬어갈 휴게소를 찾고 있어요";settingsNeeded->"검색 설정을 한 번만 해 주세요";allStops.isEmpty()->"휴게소를 찾지 못했어요";else->"마음에 드는 휴게소를 골라 주세요"}
        routeContext.visibility=if(allStops.isEmpty()) View.GONE else View.VISIBLE
    }
    private fun renderStops() {
        stopList.removeAllViews()
        allStops.forEachIndexed {index,stop->
            val card=RestTheme.container(activity,24)
            card.addView(text(stop.name,30f,true),gap(10))
            card.addView(RestTheme.label(activity,listOf(stop.category,stop.direction).filter {it.isNotBlank()}.joinToString(" · "),21f,RestTheme.muted),gap(18))
            val metrics=RestTheme.row(activity)
            fun metric(label:String,value:String)=LinearLayout(activity).apply {
                orientation=LinearLayout.VERTICAL
                addView(RestTheme.label(activity,label,20f,RestTheme.muted),gap(8))
                addView(text(value,28f,true))
            }
            metrics.addView(metric("휴게소까지","${stop.minutesToStop}분"),LinearLayout.LayoutParams(0,-2,1f))
            metrics.addView(metric("이후 주행","${stop.minutesAfterStop}분"),LinearLayout.LayoutParams(0,-2,1f))
            metrics.addView(metric(RecommendationPolicy.allowanceLabel(stop.maxRestMinutes),RecommendationPolicy.allowanceValue(stop.maxRestMinutes)),LinearLayout.LayoutParams(0,-2,1f))
            card.addView(metrics,gap(18))
            RecommendationPolicy.explanation(stop.maxRestMinutes,requestedRest)?.let {message->card.addView(RestTheme.label(activity,message,20f,RestTheme.muted),gap(16))}
            val action=RestTheme.button(activity,"이 휴게소로 안내  →") {if(!busy || canSelect)onSelect(stop.id)}
            action.isEnabled=!busy || canSelect;action.alpha=if(action.isEnabled)1f else .55f
            card.addView(action,LinearLayout.LayoutParams(-1,dp(76)))
            stopList.addView(card,gap(18))
        }
    }
}
