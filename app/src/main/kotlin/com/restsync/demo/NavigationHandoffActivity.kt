package com.restsync.demo

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.*
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener

/** User-initiated handoff to native navigation; window bounds remain owned by Pleos. */
class NavigationHandoffActivity:Activity() {
    private val ui=Handler(Looper.getMainLooper())
    private val store by lazy {SessionStore(this)}
    private var helper:NaviHelper?=null
    private var listener:NaviHelperEventListener?=null
    private var token=0
    private var phase=""
    private var active=false
    private var opening=false
    private var started=false
    private var restoreTarget=false
    private var remainingWaypoints=0
    private lateinit var status:android.widget.TextView
    private var snapshot=RestSession()

    override fun onCreate(state:Bundle?) {
        super.onCreate(state)
        snapshot=store.read()
        val root=RestTheme.container(this,36,RestTheme.snow,0)
        root.gravity=android.view.Gravity.CENTER
        status=RestTheme.label(this,"Pleos 내비 안내로 이동하고 있어요…",25f,RestTheme.ink,true)
        root.addView(status,RestTheme.margins(this,24))
        root.addView(RestTheme.button(this,"다시 연결",false) { started=false;connect() },RestTheme.margins(this,14))
        root.addView(RestTheme.button(this,"내비에서 직접 확인",false) { openMapFallback() })
        setContentView(root)
    }
    override fun onResume() { super.onResume();active=true }
    override fun onPostResume() {super.onPostResume();if(!started)connect()}
    private fun dispatch(id:Int,block:()->Unit) {ui.post {if(id==token && !isFinishing && !isDestroyed)runCatching(block).onFailure {fail("내비 요청을 처리하지 못했어요. 경로를 내비에서 확인해 주세요.")}}}

    private fun connect() {
        if(!active)return
        cleanup();started=true;opening=false
        val id=token
        phase=if(intent.getBooleanExtra("resumeAfterRest",false) && snapshot.source=="PLEOS") "DESTINATION" else "OPEN"
        ui.postDelayed({if(id==token && !isDestroyed)fail("내비 응답을 확인하지 못했어요. 다시 연결하거나 내비에서 경로를 직접 확인해 주세요.")},15_000L)
        runCatching {
            val h=NaviHelper(this);helper=h
            val l=object:NaviHelperEventListener {
                override fun onDestinationInfo(info:DestinationInfo)=dispatch(id) {
                    android.util.Log.i("RestSyncNavi","destination-check phase=$phase active=$active")
                    if(phase=="RESTORING") {
                        if(FlowPolicy.matchesStop(info.latitude,info.longitude,snapshot.destinationLat,snapshot.destinationLon))openNavigation()
                        else ui.postDelayed({dispatch(id) {if(phase=="RESTORING")h.getDestinationInfo()}},400L)
                        return@dispatch
                    }
                    if(phase=="VERIFY_DESTINATION") {
                        if(FlowPolicy.matchesStop(info.latitude,info.longitude,snapshot.destinationLat,snapshot.destinationLon))openNavigation()
                        else if(remainingWaypoints==0 && !Coordinate(info.latitude,info.longitude).valid)restoreDestination(h,id)
                        else ui.postDelayed({dispatch(id) {if(phase=="VERIFY_DESTINATION")h.getDestinationInfo()}},400L)
                        return@dispatch
                    }
                    if(phase!="DESTINATION")return@dispatch
                    if(!FlowPolicy.matchesStop(info.latitude,info.longitude,snapshot.destinationLat,snapshot.destinationLon)) {
                        if(!Coordinate(info.latitude,info.longitude).valid)restoreTarget=true
                        else {fail("내비 목적지가 변경되어 경유지를 자동 수정하지 않았어요. 내비에서 목적지를 확인해 주세요.");return@dispatch}
                    }
                    phase="WAYPOINTS";h.getWaypointInfo()
                }
                override fun onWayPointInfo(points:List<WaypointInfo>)=dispatch(id) {
                    if(phase !in setOf("WAYPOINTS","REMOVING"))return@dispatch
                    val first=points.firstOrNull()
                    val matches=first!=null && RecommendationPolicy.matchesRegistered(first.name,Coordinate(first.latitude,first.longitude),
                        snapshot.stopName,Coordinate(snapshot.stopLat,snapshot.stopLon),snapshot.waypointProof=="NAME_ONLY")
                    remainingWaypoints=points.count {it.name.isNotBlank() || it.latitude!=0.0 || it.longitude!=0.0}
                    android.util.Log.i("RestSyncNavi","waypoint-check phase=$phase match=$matches count=${points.size} active=$active")
                    if(phase=="WAYPOINTS" && matches) {
                        phase="REMOVING"
                        h.removeWaypoint(WaypointIndex.FIRST)
                        ui.postDelayed({dispatch(id) {if(phase=="REMOVING")h.getWaypointInfo()}},400L)
                    }
                    else if(phase=="REMOVING" && matches) ui.postDelayed({dispatch(id) {if(phase=="REMOVING")h.getWaypointInfo()}},400L)
                    else if(restoreTarget) {
                        if(remainingWaypoints==0)restoreDestination(h,id)
                        else fail("다른 경유지가 남아 있어 목적지를 자동 복원하지 않았어요. 내비에서 확인해 주세요.")
                    }
                    else {
                        phase="REROUTING";h.requestReRoute()
                        // Some emulator versions omit reroute completion callbacks.
                        // The selected stop is already absent; recheck the final target.
                        ui.postDelayed({dispatch(id) {if(phase=="REROUTING") {phase="VERIFY_DESTINATION";h.getDestinationInfo()}}},800L)
                    }
                }
                override fun onWaypointChanged(info:WaypointChangedInfo)=dispatch(id) {if(phase=="REMOVING")h.getWaypointInfo()}
                override fun onReRouteEnded()=dispatch(id) {android.util.Log.i("RestSyncNavi","reroute-ended phase=$phase active=$active");if(phase=="REROUTING")openNavigation()}
                override fun onRoutingCompleted()=dispatch(id) {
                    android.util.Log.i("RestSyncNavi","routing-complete phase=$phase active=$active")
                    when(phase) {"REROUTING"->openNavigation();"REMOVING"->h.getWaypointInfo();"RESTORING"->h.getDestinationInfo()}
                }
                override fun onError(errorCode:NaviErrorCode)=dispatch(id) {fail("내비 요청 오류 · $errorCode\n기존 경로는 내비에서 확인해 주세요.")}
            }
            listener=l
            h.initialize(onConnected={dispatch(id) {
                h.addListener(l)
                if(phase=="DESTINATION")h.getDestinationInfo() else openNavigation()
            }},onFailed={dispatch(id) {fail("Pleos 내비 연결에 실패했어요. 다시 연결해 주세요.")}})
        }.onFailure {fail("내비 화면을 열지 못했어요. 차량 서비스와 권한을 확인해 주세요.")}
    }

    private fun restoreDestination(h:NaviHelper,id:Int) {
        if(!Coordinate(snapshot.destinationLat,snapshot.destinationLon).valid) {fail("저장된 목적지를 확인할 수 없어요.");return}
        phase="RESTORING"
        h.requestRoute(RouteInfo(latitude=snapshot.destinationLat,longitude=snapshot.destinationLon,displayLatitude=null,displayLongitude=null,
            poiName=snapshot.destinationName,poiId="",poiSubId="",address=""))
        ui.postDelayed({dispatch(id) {if(phase=="RESTORING")h.getDestinationInfo()}},400L)
    }

    private fun openNavigation() {
        android.util.Log.i("RestSyncNavi","handoff-ready resume=${intent.getBooleanExtra("resumeAfterRest",false)} active=$active")
        ui.removeCallbacksAndMessages(null)
        phase="OPEN";opening=true
        if(intent.getBooleanExtra("resumeAfterRest",false)) {
            store.write(store.read().copy(stage="NAVIGATING",startedAtMs=0,stopName="",stopLat=0.0,stopLon=0.0,waypointProof="COORDINATES",
                statusMessage="휴식을 마치고 현재 위치에서 목적지까지 안내합니다."))
            if(RecordingTour.read(this).active)RecordingTour.onNavigationResumed(this)
        }
        RestAlerts.showCompanionShortcut(this)
        if(!active) {finish();return}
        runCatching {helper?.requestNavigationScreen(NavigationScreenRequest.Foreground)}
            .onFailure {opening=false;fail("내비 화면을 열 수 없어요.")}
        val id=token
        ui.postDelayed({if(active && opening && id==token)openMapFallback()},1500L)
    }
    private fun openMapFallback() {
        opening=true
        runCatching {startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,Intent.CATEGORY_APP_MAPS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))}
            .onFailure {opening=false;status.text="기본 지도 앱을 찾지 못했어요. Pleos 내비 아이콘으로 열어 주세요."}
    }
    private fun fail(message:String) {android.util.Log.w("RestSyncNavi","handoff-failed phase=$phase: $message");cleanup();opening=false;status.text=message;if(!active)finish()}
    private fun cleanup() {
        token++;ui.removeCallbacksAndMessages(null)
        helper?.let {h->listener?.let {runCatching {h.removeListener(it)}};runCatching {h.release()}}
        helper=null;listener=null
    }
    // Navi route mutations can themselves foreground the native app. Keep receiving
    // their completion events while paused; never reopen another screen in that state.
    override fun onPause() {active=false;super.onPause()}
    override fun onStop() {super.onStop();if(opening)finish()}
    override fun onDestroy() {cleanup();super.onDestroy()}
}
