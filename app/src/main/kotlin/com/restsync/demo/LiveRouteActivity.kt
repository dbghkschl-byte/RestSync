package com.restsync.demo

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.WaypointChangedInfo
import ai.pleos.playground.navi.data.WaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.Future
import org.json.JSONArray
import org.json.JSONObject

/**
 * RestSync — Pleos 스타일 휴식 장소 검색 화면.
 *
 * 기존 Pleos NaviHelper / KakaoRoutes / RestPlan 로직을 유지하면서
 * RestSyncRouteUi.kt에 연결하는 Activity입니다.
 *
 * 주의: 카카오 길찾기 REST API와 카카오 지도 MapView는 별개의 기능입니다.
 * 이 파일은 업로드된 원본에 있던 길찾기를 유지합니다. 별도로 만든
 * MapView가 있다면 routeUi.attachExistingMap(mapView)를 연결하세요.
 */
internal class LiveRouteActivity : Activity(), NaviHelperEventListener {
    companion object {@Volatile private var cachedCatalog:List<RestArea>?=null}

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var systemBackCallback: OnBackInvokedCallback? = null

    private lateinit var navi: NaviHelper
    private lateinit var routeUi: RestSyncRouteUi
    private val localSettings by lazy {getSharedPreferences("restsync_local_settings",MODE_PRIVATE)}

    private var areas = emptyList<RestArea>()
    private var stops = emptyList<LiveStop>()
    private var origin: Coordinate? = null
    private var destination: DestinationInfo? = null
    private var waypoints: List<WaypointInfo>? = null
    private var searchedDestination: Coordinate? = null
    private var pendingStop: LiveStop? = null
    private var awaitingRoute = false
    private var verifyingWaypoint = false
    private var busy = false
    private var ready = false
    private var naviFailed=false
    private var catalogFailed=false
    private var forceFreshSearch=false
    private var searchStartedAt=0L
    private val searchRuns=SearchRunGate()
    private var searchToken=0L
    private var searchJob:Future<*>?=null
    private var searchingRoutes=false
    private var firstResultMeasured=false
    private var checkingExistingStop=false
    private var waypointRequestSent=false
    private var verifyingFinalDestination=false
    private var composedRoute=false
    private var nameOnlyWaypoint=false
    private var nativeAlreadyForeground=false
    private var destinationOnly = false
    private var originalWaypointCount = 0
    private lateinit var autoSearch: AutoSearchGate
    private var deadline = LocalDateTime.now().plusHours(2).withSecond(0).withNano(0)

    private val timeout = Runnable {
        finishRequest("Pleos 내비 응답이 없어요. 경로 안내를 켜고 다시 시도해 주세요.")
    }
    // Route mutations may foreground native navigation before its information
    // service answers. Re-query information only; never repeat a route mutation.
    private val selectionPoll=object:Runnable {
        override fun run() {
            if(pendingStop==null || isDestroyed || isFinishing)return
            sendToNavi {
                when {
                    verifyingFinalDestination -> navi.getDestinationInfo()
                    verifyingWaypoint || waypointRequestSent || checkingExistingStop -> navi.getWaypointInfo()
                    else -> navi.getDestinationInfo()
                }
            }
            if(pendingStop!=null)handler.postDelayed(this,700L)
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        autoSearch = AutoSearchGate(intent.getIntExtra("candidateIndex", -1) < 0)
        // Match the deadline selected in MainActivity (same wall clock).
        val fromMain = intent.getLongExtra("deadlineMs", 0L)
        if (fromMain > 0L) {
            deadline = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(fromMain), java.time.ZoneId.systemDefault()
            )
        }

        routeUi = RestSyncRouteUi(
            activity = this,
            onBack = { navigateBack() },
            onSearch = { if(ready && areas.isNotEmpty()) search(true) else {finish();startActivity(intent)} },
            onSelect = { id ->
                RecommendationPolicy.resolve(stops,id)?.let {stop->confirmAddStop(stops.indexOf(stop))}
            },
            onDeadlineClick = { chooseDeadline() },
            onSettings = {openSearchSettings()}
        )
        if(RecordingTour.read(this).active)routeUi.root.addView(recordingSelectionBar("실제 추천 결과에서 쉬어갈 휴게소를 선택해 주세요."){pendingStop==null},0)
        setContentView(routeUi.root)
        updateDeadline()
        setStatus("Pleos 내비에 연결하는 중이에요.")
        updateButtons()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { navigateBack() }
            systemBackCallback = callback
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback
            )
        }

        if(intent.getIntExtra("candidateIndex",-1)>=0) restoreCachedCandidates()
        loadCatalog()
        runCatching {
            navi = NaviHelper(applicationContext)
            navi.initialize({
                ui {
                    navi.addListener(this)
                    ready = true
                    setStatus("쉬어갈 휴게소를 바로 찾고 있어요…")
                    updateButtons()
                    val candidateIndex=intent.getIntExtra("candidateIndex",-1)
                    if (candidateIndex in stops.indices) {
                        handler.postDelayed({confirmAddStop(candidateIndex)},350L)
                    }
                }
            }, {
                ui { naviFailed=true;setStatus("Pleos 내비에 연결할 수 없어요. 내비를 실행한 뒤 다시 찾아보기를 눌러 주세요.");updateButtons() }
            })
        }.onFailure {
            naviFailed=true;setStatus("Pleos 내비에 연결할 수 없어요.");updateButtons()
        }
    }

    private fun setStatus(message: String) {
        if (::routeUi.isInitialized) routeUi.setStatus(message)
    }

    private fun loadCatalog() {
        worker.execute {
            val result = runCatching {
                cachedCatalog ?: assets.open("rest-areas.csv").bufferedReader(Charsets.UTF_8).use(RestAreaCsv::read).also {cachedCatalog=it}
            }
            ui {
                result.onSuccess { areas = it }
                result.onFailure { catalogFailed=true;setStatus("앱에 포함된 휴게소 목록을 읽지 못했어요.") }
                updateButtons()
            }
        }
    }

    private fun search(forceRefresh:Boolean=false) {
        if (busy) return
        if (!ready) {
            setStatus("Pleos 내비 연결을 먼저 확인해 주세요.")
            return
        }
        if (areas.isEmpty()) {
            setStatus("휴게소 목록을 불러오는 중이거나 목록이 비어 있어요.")
            return
        }
        if (localSettings.getString("kakao_rest_api_key", "").orEmpty().isBlank()) {
            routeUi.setSettingsNeeded(true)
            setStatus("처음 한 번만 검색 키를 저장해 주세요. 저장하고 돌아오면 자동으로 찾아드릴게요.")
            return
        }
        routeUi.setSettingsNeeded(false)
        routeUi.setRetryAvailable(false)
        forceFreshSearch=forceRefresh
        searchStartedAt=android.os.SystemClock.elapsedRealtime()
        searchToken=searchRuns.begin()
        searchJob?.cancel(true)
        firstResultMeasured=false

        stops = emptyList()
        renderStops()
        origin = null
        destination = null
        waypoints = null
        destinationOnly = false
        originalWaypointCount = 0
        awaitingRoute = true
        beginRequest("Pleos 현재 위치와 목적지를 읽는 중이에요.")
        sendToNavi {
            navi.getCurrentLocationInfo()
            navi.getDestinationInfo()
            navi.getWaypointInfo()
        }
    }

    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = ui {
        if (awaitingRoute) {
            origin = Coordinate(currentLocationInfo.latitude, currentLocationInfo.longitude)
            loadRoutes()
        }
    }

    override fun onDestinationInfo(destinationInfo: DestinationInfo) = ui {
        if (awaitingRoute) {
            destination = destinationInfo
            loadRoutes()
        } else if(verifyingFinalDestination && pendingStop!=null) {
            val expected=searchedDestination
            if(expected!=null && FlowPolicy.matchesStop(destinationInfo.latitude,destinationInfo.longitude,expected.latitude,expected.longitude)) {
                completeStopSelection(pendingStop ?: return@ui)
            }
        } else if (pendingStop != null && !checkingExistingStop) {
            val stop = pendingStop ?: return@ui
            if (Coordinate(destinationInfo.latitude, destinationInfo.longitude) != searchedDestination) {
                stops = emptyList()
                finishRequest("내비 목적지가 바뀌었어요. 휴게소를 다시 검색해 주세요.")
                return@ui
            }
            if(!checkingExistingStop) {
                checkingExistingStop=true
                sendToNavi {navi.getWaypointInfo()}
            }
        }
    }

    override fun onWayPointInfo(waypointInfoList: List<WaypointInfo>) = ui {
        if (awaitingRoute) {
            originalWaypointCount=waypointInfoList.count { it.name.isNotBlank() || it.latitude!=0.0 || it.longitude!=0.0 }
            val active=runCatching { WaypointPolicy.active(waypointInfoList.map { WaypointPolicy.Point(it.name,it.latitude,it.longitude) }) }
            destinationOnly=active.isFailure
            routeUi.setDestinationOnly(destinationOnly)
            // Unknown points cannot be sent to the route API. Recommendations remain
            // explicitly destination-based; the existing Pleos route is never cleared.
            waypoints = waypointInfoList.filter { Coordinate(it.latitude,it.longitude).valid }
            loadRoutes()
        } else if (verifyingWaypoint) {
            val stop = pendingStop ?: return@ui
            val added = waypointInfoList.firstOrNull()?.let {
                RecommendationPolicy.matchesRegistered(it.name,Coordinate(it.latitude,it.longitude),stop.area.name,stop.area.coordinate,true)
            } == true
            if (added) {
                val point=waypointInfoList.first()
                nameOnlyWaypoint=!Coordinate(point.latitude,point.longitude).valid
                verifyingWaypoint=false;verifyingFinalDestination=true
                sendToNavi {navi.getDestinationInfo()}
            }
        } else if(checkingExistingStop && !waypointRequestSent) {
            val stop=pendingStop ?: return@ui
            val first=waypointInfoList.firstOrNull()
            if(first!=null && RecommendationPolicy.matchesRegistered(first.name,Coordinate(first.latitude,first.longitude),stop.area.name,stop.area.coordinate,true)) {
                nameOnlyWaypoint=!Coordinate(first.latitude,first.longitude).valid
                verifyingFinalDestination=true
                sendToNavi {navi.getDestinationInfo()}
            } else {
                waypointRequestSent=true
                val selected=RequestWaypointInfo(
                    latitude=stop.area.coordinate.latitude,longitude=stop.area.coordinate.longitude,
                    poiName=stop.area.name,waypointIndex=WaypointIndex.FIRST,poiId="",address="",poiSubId=""
                )
                val active=waypointInfoList.filter {it.name.isNotBlank() || it.latitude!=0.0 || it.longitude!=0.0}
                val target=destination
                composedRoute=active.all {Coordinate(it.latitude,it.longitude).valid} && active.size<2 && target!=null
                if(composedRoute && target!=null) {
                    val retained=active.map {point->RequestWaypointInfo(
                        latitude=point.latitude,longitude=point.longitude,poiName=point.name,
                        waypointIndex=WaypointIndex.SECOND,poiId="",address="",poiSubId=""
                    )}
                    sendToNavi {navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(
                        destination=RouteInfo(latitude=target.latitude,longitude=target.longitude,displayLatitude=null,displayLongitude=null,
                            poiName=target.name,poiId="",poiSubId="",address=target.address),
                        waypoints=listOf(selected)+retained
                    ))}
                } else sendToNavi {navi.addWaypoint(selected)}
                // The emulator can register a waypoint without a completion event.
                handler.postDelayed({
                    if(pendingStop!=null && waypointRequestSent && !verifyingWaypoint && !verifyingFinalDestination) {
                        verifyingWaypoint=true
                        sendToNavi {navi.getWaypointInfo()}
                    }
                },400L)
            }
        }
    }

    private fun completeStopSelection(stop:LiveStop) {
        val sessionStore=SessionStore(this)
        sessionStore.write(sessionStore.read().copy(stage="WAYPOINT",source="PLEOS",stopName=stop.area.name,
            stopLat=stop.area.coordinate.latitude,stopLon=stop.area.coordinate.longitude,
            approachMinutes=stop.approachMinutes,driveMinutes=stop.driveMinutes,
            waypointProof=if(nameOnlyWaypoint) "NAME_ONLY" else "COORDINATES",
            statusMessage="Pleos 경유지 확인 완료 · 도착 후 정차를 확인해 주세요."))
        if(RecordingTour.read(this).active)RecordingTour.onWaypointConfirmed(this)
        RestAlerts.showCompanionShortcut(this)
        setResult(Activity.RESULT_OK,android.content.Intent().apply {
            putExtra("stopName",stop.area.name);putExtra("stopLat",stop.area.coordinate.latitude)
            putExtra("stopLon",stop.area.coordinate.longitude);putExtra("approachMinutes",stop.approachMinutes)
            putExtra("driveMinutes",stop.driveMinutes)
            putExtra("nativeAlreadyForeground",nativeAlreadyForeground)
        })
        finishRequest("Pleos 경유지 확인 완료 · 휴식 준비 화면으로 이동합니다.")
        finish()
    }

    override fun onWaypointChanged(waypointChangedInfo: WaypointChangedInfo) = ui {
        if (pendingStop != null && waypointRequestSent && !verifyingWaypoint && !verifyingFinalDestination) {
            verifyingWaypoint = true
            sendToNavi { navi.getWaypointInfo() }
        }
    }

    override fun onRoutingCompleted() = ui {
        if(pendingStop!=null && waypointRequestSent && !verifyingFinalDestination) {
            verifyingWaypoint=true
            sendToNavi {navi.getWaypointInfo()}
        }
    }

    override fun onError(errorCode: NaviErrorCode) = ui {
        if (awaitingRoute || pendingStop != null) {
            finishRequest("Pleos 내비 요청이 실패했어요 ($errorCode).")
        }
    }

    private fun loadRoutes() {
        val start = origin ?: return
        val target = destination ?: return
        val existing = waypoints ?: return
        val end = Coordinate(target.latitude, target.longitude)
        awaitingRoute = false
        handler.removeCallbacks(timeout)

        if (!start.valid || !end.valid) {
            finishRequest("Pleos 내비에서 현재 위치와 목적지를 확인해 주세요.")
            return
        }
        if (originalWaypointCount >= 2) {
            finishRequest("내비 경유지가 이미 2곳 있어요. 내비에서 한 곳을 정리한 뒤 다시 찾아 주세요.")
            return
        }

        val store=SessionStore(this)
        val current=store.read()
        if(current.stage!="RESTING" && target.remainTime.toLong() in 1..172800) {
            store.write(JourneyPolicy.connect(current,target.name,target.latitude,target.longitude,target.remainTime.toLong(),System.currentTimeMillis()))
        }
        val apiKey = localSettings.getString("kakao_rest_api_key", "").orEmpty().trim()
        val catalog = areas
        val via = existing.map { Coordinate(it.latitude, it.longitude) }
        val useCache=!forceFreshSearch
        routeUi.setDestination(target.name)
        setStatus("${target.name}까지의 외부 경로에서 휴게소를 찾는 중이에요.")
        searchingRoutes=true
        updateButtons()
        val token=searchToken
        searchJob=worker.submit {
            val routes=KakaoRoutes(apiKey)
            val result = runCatching {
                routes.findStops(start, end, via, catalog,useCache) {verified,done,total->
                    ui {
                        if(!searchRuns.isCurrent(token))return@ui
                        stops=verified
                        searchedDestination=end
                        recordFirstResult()
                        setStatus(if(verified.isEmpty()) "휴게소를 비교하고 있어요 · $done/$total" else "확인된 ${verified.size}곳부터 선택할 수 있어요 · 더 비교 중 $done/$total")
                        updateButtons()
                    }
                }
            }
            ui {
                if(!searchRuns.isCurrent(token))return@ui
                searchRuns.finish(token)
                searchingRoutes=false
                android.util.Log.i("RestSyncPerf","search-complete elapsedMs=${android.os.SystemClock.elapsedRealtime()-searchStartedAt} cache=${routes.usedCachedResult} requests=${routes.requestCount.get()} responseBytes=${routes.responseBytes.get()} success=${result.isSuccess}")
                result.onSuccess {
                    stops = it
                    searchedDestination = end
                    saveCachedCandidates(it,end)
                    recordFirstResult()
                }
                result.exceptionOrNull()?.let {error->
                    routeUi.setRetryAvailable(true)
                    if(error is RouteRequestException && error.httpCode in setOf(401,403))routeUi.setSettingsNeeded(true)
                }
                finishRequest(result.fold({
                    if (it.isEmpty()) "외부 경로에서 큰 우회 없이 들를 휴게소를 찾지 못했어요."
                    else if(routes.usedCachedResult) "방금 검색한 결과예요. 교통 상황은 달라질 수 있어요."
                    else if(destinationOnly) "내비가 일부 경유지의 좌표를 제공하지 않아 목적지 기준으로 찾았어요. 기존 경유지는 유지되며 실제 안내와 다를 수 있어요."
                    else "카카오 경로 기준 예상 시간이에요. Pleos 안내 경로와 다를 수 있어요."
                }, {
                    android.util.Log.w("RestSyncRoutes", "Search failed: ${it.javaClass.simpleName}: ${it.message?.replace(apiKey,"[redacted]")}")
                    if(stops.isNotEmpty()) "일부 비교가 중단됐어요. 확인된 ${stops.size}곳은 선택할 수 있고 다시 찾아볼 수도 있어요."
                    else if (it is IllegalStateException || it is IllegalArgumentException) it.message ?: "경로 검색에 실패했어요."
                    else "길찾기 응답을 읽지 못했어요. 인터넷 연결을 확인해 주세요."
                }))
            }
        }
    }

    private fun confirmAddStop(index:Int) {
        if((busy && !searchingRoutes) || !ready || index !in stops.indices)return
        pendingStop=stops[index]
        searchRuns.cancel();searchJob?.cancel(true);searchingRoutes=false
        checkingExistingStop=false;waypointRequestSent=false;verifyingFinalDestination=false;composedRoute=false;nameOnlyWaypoint=false
        beginRequest("${pendingStop?.area?.name}을(를) Pleos 경유지로 추가하고 있어요…")
        handler.removeCallbacks(selectionPoll)
        handler.postDelayed(selectionPoll,700L)
        sendToNavi {navi.getDestinationInfo()}
    }

    private fun recordFirstResult() {
        if(stops.isNotEmpty() && !firstResultMeasured) {
            firstResultMeasured=true
            android.util.Log.i("RestSyncPerf","search-first elapsedMs=${android.os.SystemClock.elapsedRealtime()-searchStartedAt} count=${stops.size}")
        }
    }

    private fun openSearchSettings() {
        if(pendingStop!=null) {android.widget.Toast.makeText(this,"경유지 설정이 끝날 때까지 기다려 주세요.",android.widget.Toast.LENGTH_SHORT).show();return}
        @Suppress("DEPRECATION")
        startActivityForResult(android.content.Intent(this,SearchSettingsActivity::class.java),814)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==814 && resultCode==RESULT_OK) {
            autoSearch=AutoSearchGate(true)
            updateButtons()
        }
    }

    private fun beginRequest(message: String) {
        busy = true
        setStatus(message)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, if(pendingStop!=null)20_000L else 12_000L)
        updateButtons()
    }

    private fun finishRequest(message: String) {
        handler.removeCallbacks(timeout)
        handler.removeCallbacks(selectionPoll)
        pendingStop = null
        awaitingRoute = false
        verifyingWaypoint = false
        checkingExistingStop=false;waypointRequestSent=false;verifyingFinalDestination=false;composedRoute=false
        busy = false
        setStatus(message)
        updateButtons()
    }

    private fun sendToNavi(action: () -> Unit) {
        runCatching(action).onFailure {
            finishRequest("Pleos 내비 요청에 실패했어요. SDK 접근 권한을 확인해 주세요.")
        }
    }

    /** Non-secret route candidate cache, valid only for a short live-search window. */
    private fun saveCachedCandidates(candidates: List<LiveStop>, end: Coordinate) {
        val items=JSONArray()
        candidates.take(3).forEach { stop ->
            items.put(JSONObject().apply {
                put("name",stop.area.name); put("direction",stop.area.direction)
                put("lat",stop.area.coordinate.latitude); put("lon",stop.area.coordinate.longitude)
                put("approach",stop.approachMinutes);put("drive",stop.driveMinutes)
            })
        }
        getSharedPreferences("restsync_route_candidates",MODE_PRIVATE).edit()
            .putString("items",items.toString())
            .putLong("savedAtMs",System.currentTimeMillis())
            .putString("target","${end.latitude},${end.longitude}")
            .putBoolean("destinationOnly",destinationOnly)
            .apply()
    }

    private fun restoreCachedCandidates() {
        val pref=getSharedPreferences("restsync_route_candidates",MODE_PRIVATE)
        destinationOnly=pref.getBoolean("destinationOnly",false)
        routeUi.setDestinationOnly(destinationOnly)
        if (System.currentTimeMillis()-pref.getLong("savedAtMs",0L)>5*60_000L) return
        val raw=pref.getString("items", "[]") ?: return
        val list=runCatching {
            val arr=JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val item=arr.optJSONObject(i) ?: return@mapNotNull null
                val p=Coordinate(item.optDouble("lat"),item.optDouble("lon"))
                if(!p.valid) return@mapNotNull null
                LiveStop(RestArea(item.optString("name"),item.optString("direction"),p),
                    item.optLong("approach"),item.optLong("drive"))
            }
        }.getOrDefault(emptyList())
        if (list.isEmpty()) return
        val coords=pref.getString("target", "")?.split(",")
        val target=if(coords?.size==2) Coordinate(coords[0].toDoubleOrNull() ?: 0.0,coords[1].toDoubleOrNull() ?: 0.0) else null
        if(target?.valid!=true) return
        stops=list
        searchedDestination=target
        setStatus("최근 5분 이내 검색한 후보 · 실제 경유지 추가 시 Pleos 목적지를 다시 확인합니다.")
        renderStops()
    }

    private fun returnRecommendationsToMap() {
        if(stops.isEmpty()) {
            setStatus("추천 결과가 없습니다. 현재 Pleos 경로에서 검색해 주세요.")
            return
        }
        setResult(Activity.RESULT_FIRST_USER,android.content.Intent().putExtra("recommendationsSaved",true))
        finish()
    }

    private fun chooseDeadline() {
        TimePickerDialog(this, { _, hour, minute ->
            val now = LocalDateTime.now()
            val chosen = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            deadline = if (chosen.isBefore(now)) chosen.plusDays(1) else chosen
            val store=SessionStore(this)
            store.write(store.read().copy(deadlineMs=deadline.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()))
            updateDeadline()
            renderStops()
        }, deadline.hour, deadline.minute, true).show()
    }

    private fun updateDeadline() {
        if (::routeUi.isInitialized) {
            routeUi.setDeadline(
                deadline.format(DateTimeFormatter.ofPattern("MM/dd HH:mm")),
                intent.getLongExtra("requested", 40L)
            )
        }
    }

    private fun updateButtons() {
        if (::routeUi.isInitialized) {
            routeUi.setBusy(busy || (!ready && !naviFailed) || (areas.isEmpty() && !catalogFailed),searchingRoutes && stops.isNotEmpty())
            renderStops()
            if (!busy && autoSearch.consumeIfReady(ready, areas.isNotEmpty())) search()
        }
    }

    private fun renderStops() {
        if (!::routeUi.isInitialized) return
        val budget = Duration.between(LocalDateTime.now(), deadline).toMinutes()
        routeUi.showStops(stops.map { stop ->
            val plan = RestPlan(budget, 0, stop.driveMinutes, stop.approachMinutes)
            RouteUiStop(
                name = stop.area.name,
                direction = stop.area.direction,
                minutesToStop = stop.approachMinutes,
                minutesAfterStop = stop.driveMinutes,
                maxRestMinutes = plan.restMinutes,
                category = "휴게소",id=RecommendationPolicy.id(stop)
            )
        })
    }

    private fun ui(action: () -> Unit) {
        handler.post { if (!isDestroyed && !isFinishing) action() }
    }

    private fun navigateBack() {
        if (!busy) {
            finish()
        } else {
            AlertDialog.Builder(this)
                .setTitle("검색을 중단할까요?")
                .setMessage("진행 중인 내비 조회 또는 휴게소 검색을 중단하고 휴식 계획으로 돌아갑니다.")
                .setNegativeButton("계속 검색", null)
                .setPositiveButton("돌아가기") { _, _ -> finish() }
                .show()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        navigateBack()
    }

    override fun onPause() {
        if(pendingStop!=null && waypointRequestSent)nativeAlreadyForeground=true
        super.onPause()
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            systemBackCallback?.let {
                onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it)
            }
            systemBackCallback = null
        }
        handler.removeCallbacksAndMessages(null)
        searchRuns.cancel();searchJob?.cancel(true)
        worker.shutdownNow()
        if (::navi.isInitialized) {
            if (ready) navi.removeListener(this)
            navi.release()
        }
        super.onDestroy()
    }
}
