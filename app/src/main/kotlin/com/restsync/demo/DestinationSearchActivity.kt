package com.restsync.demo

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import java.util.concurrent.Executors

/**
 * Search a destination inside RestSync, then ask the Pleos Navi Service to route there.
 * Keyless Photon / OpenStreetMap POI coordinates. This is NOT a Pleos map.
 * Real route result is confirmed only after the NaviHelper completion callback.
 * All demo examples are explicitly labeled as such.
 */
internal class DestinationSearchActivity : Activity(), NaviHelperEventListener {
    private data class Place(val title:String, val address:String, val lat:Double, val lon:Double)
    private val executor = Executors.newSingleThreadExecutor()
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var results: LinearLayout
    private lateinit var status: TextView
    private lateinit var searchInput: EditText
    private lateinit var searchButton: TextView
    private var navi: NaviHelper? = null
    private var connected = false
    private var searchSequence = 0
    private var searching = false
    private var pending: Place? = null
    private var routing = false
    private var nativeAlreadyForeground=false
    private val timeout = Runnable {
        if (routing) {
            routing = false
            status.text = "Pleos 응답 시간이 초과됐어요. 기본 내비에서 경로 설정 여부를 확인해 주세요."
            Toast.makeText(this, "경로 요청은 확인되지 않았습니다.", Toast.LENGTH_LONG).show()
        }
    }
    private fun dp(n:Int) = RestTheme.dp(this,n)
    private fun label(t:String, size:Float=16f, bold:Boolean=false):TextView =
        RestTheme.label(this, t, size, if (bold) RestTheme.ink else RestTheme.muted, bold)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val root = LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(24),dp(18),dp(24),dp(18))
            setBackgroundColor(RestTheme.snow)
            isFocusableInTouchMode=true
        }
        val head = RestTheme.row(this)
        head.addView(RestTheme.button(this,"‹  돌아가기",false){finish()}, LinearLayout.LayoutParams(dp(145),dp(56)))
        head.addView(label("목적지 검색",27f,true), LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(16)})
        root.addView(head,RestTheme.margins(this,18))
        root.addView(label("가고 싶은 장소를 검색하면 Pleos 내비로 안내해요.",15f),RestTheme.margins(this,15))
        root.addView(RestTheme.button(this,"이미 안내 중인 Pleos 경로 연결",false) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(this,CurrentRouteActivity::class.java),812)
        },RestTheme.margins(this,12))
        searchInput=EditText(this).apply {
            hint="장소명 검색 · 예: 부산역, 안성휴게소"
            isSingleLine=true
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
            setOnEditorActionListener { _, action, event ->
                val submit=action in setOf(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH,android.view.inputmethod.EditorInfo.IME_ACTION_GO,android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
                if(submit || event?.keyCode==android.view.KeyEvent.KEYCODE_ENTER) {
                    if(event==null || event.action==android.view.KeyEvent.ACTION_UP) search()
                    true
                } else false
            }
            setText(savedInstanceState?.getString("query").orEmpty())
            textSize=19f
            setPadding(dp(18),dp(7),dp(18),dp(7))
            background=RestTheme.rounded(this@DestinationSearchActivity,RestTheme.white,15,RestTheme.line)
        }
        val searchRow=RestTheme.row(this)
        searchRow.addView(searchInput,LinearLayout.LayoutParams(0,dp(60),1f).apply{rightMargin=dp(10)})
        searchButton=RestTheme.button(this,"검색") { search() }
        searchRow.addView(searchButton,LinearLayout.LayoutParams(dp(122),dp(60)))
        root.addView(searchRow,RestTheme.margins(this,15))
        root.addView(label("검색어는 Photon 검색 서비스로 전송돼요. 장소 데이터 © OpenStreetMap contributors",13f),RestTheme.margins(this,10))
        status=label("장소명을 입력한 뒤 검색을 눌러 주세요.",15f)
        root.addView(status,RestTheme.margins(this,12))
        val scroll=ScrollView(this)
        results=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        scroll.addView(results)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        if(RecordingTour.read(this).active)root.addView(recordingSelectionBar("실제 검색 결과에서 목적지를 고르고 ‘설정’을 눌러 주세요."){!routing},0)
        setContentView(root)
        root.requestFocus()
        showDemoExamples()
        val lat=intent.getDoubleExtra("mapLat",Double.NaN)
        val lon=intent.getDoubleExtra("mapLon",Double.NaN)
        if(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) {
            val tapped=Place("지도에서 선택한 위치", "지도 미리보기의 좌표", lat,lon)
            showPlaces(listOf(tapped),"지도에서 선택한 좌표 · 내비 경로 요청 가능")
        }
        runCatching {
            val helper=NaviHelper(applicationContext)
            navi=helper
            helper.initialize({
                uiHandler.post {
                    if(isDestroyed || isFinishing) return@post
                    connected=true; helper.addListener(this)
                    if(searchSequence==0) status.text="장소명을 입력하면 바로 찾아드릴게요."
                }
            },{
                uiHandler.post {status.text="Pleos 연결 실패 · 에뮬레이터와 권한을 확인해 주세요."}
            })
        }.onFailure {status.text="NaviHelper를 시작할 수 없어요: ${it.javaClass.simpleName}"}
        val recordingQuery=intent.getStringExtra("recordingTourQuery")
        if(RecordingTour.read(this).active && !recordingQuery.isNullOrBlank())uiHandler.postDelayed({
            if(!isDestroyed && !isFinishing && searchSequence==0) {searchInput.setText(recordingQuery);search()}
        },400L)
    }

    private fun showDemoExamples() {
        showPlaces(listOf(
            Place("대전역 · 시연 장소", "대전광역시 동구 중앙로 215",36.3321,127.4346),
            Place("부산역 · 시연 장소", "부산광역시 동구 중앙대로 206",35.1151,129.0414),
            Place("서울역 · 시연 장소", "서울특별시 용산구 한강대로 405",37.5547,126.9707)
        ),"시연용 예시 장소 · 검색 결과와 구분하세요.")
    }
    private fun search() {
        val q=searchInput.text.toString().trim().replace(Regex("\\s*(찾아\\s*줘|검색해\\s*줘)[.!?]*$"), "").trim()
        if(q.isBlank()){ status.text="목적지 이름을 입력해 주세요.";return }
        if(searching || routing) return
        (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(searchInput.windowToken,0)
        searchInput.clearFocus()
        searching=true;searchButton.isEnabled=false;searchButton.text="검색 중…"
        val seq=++searchSequence
        results.removeAllViews()
        status.text="‘$q’ 장소를 검색하는 중이에요…"
        executor.execute {
            val response=runCatching {KeylessPlaceSearch.search(applicationContext,q)}
            uiHandler.post {
                if(isDestroyed || isFinishing || seq!=searchSequence) return@post
                searching=false;searchButton.isEnabled=true;searchButton.text="검색"
                response.onSuccess {r->
                    val places=r.places.map {Place(it.name,it.address,it.latitude,it.longitude)}
                    showPlaces(places,if(places.isEmpty())"‘$q’ 검색 결과가 없어요. 지역명이나 주소를 함께 입력해 주세요." else "‘$q’ 검색 결과 ${places.size}곳 · OpenStreetMap${if(r.cached) " · 저장된 결과" else ""}")
                }
                response.onFailure {status.text=it.message?.takeIf {m->m.contains("HTTP") || m.contains("검색 요청")} ?: "인터넷 연결을 확인한 뒤 다시 검색해 주세요. 직접 Pleos에서 목적지를 설정한 후 경로를 연결할 수도 있어요."}
            }
        }
    }
    private fun showPlaces(places:List<Place>,message:String) {
        status.text=message
        results.removeAllViews()
        places.forEach {p->
            val cell=RestTheme.container(this,16)
            cell.addView(label(p.title,20f,true),RestTheme.margins(this,6))
            cell.addView(label(p.address,14f),RestTheme.margins(this,10))
            cell.addView(RestTheme.button(this,"Pleos 내비에 목적지 설정 →") { confirmPlace(p) })
            results.addView(cell,RestTheme.margins(this,11))
        }
    }
    private fun confirmPlace(place:Place) {
        requestPlace(place)
    }

    private fun requestPlace(place:Place) {
        if(!connected) {Toast.makeText(this,"Pleos SDK 연결을 먼저 확인해 주세요.",Toast.LENGTH_LONG).show();return}
        if(routing) return
        AlertDialog.Builder(this).setTitle("목적지 설정")
            .setMessage("${place.title}을(를) Pleos 내비 목적지로 설정할까요?\n\n운전 중에는 직접 조작하지 마세요.")
            .setNegativeButton("취소",null).setPositiveButton("설정") {_,_->
                pending=place;routing=true;status.text="Pleos에서 경로를 계산하고 있어요…"
                uiHandler.removeCallbacks(timeout);uiHandler.postDelayed(timeout,18000L)
                runCatching {
                    navi?.requestRoute(RouteInfo(
                        latitude=place.lat, longitude=place.lon,
                        displayLongitude=null, displayLatitude=null,
                        poiName=place.title,poiId="",poiSubId="",address=place.address
                    )) ?: error("연결되지 않았습니다.")
                    uiHandler.postDelayed({if(routing && !isFinishing)navi?.getDestinationInfo()},700L)
                }.onFailure {routing=false;uiHandler.removeCallbacks(timeout);status.text="경로 요청 실패: ${it.javaClass.simpleName}"}
            }.show()
    }
    override fun onRoutingCompleted() = verifyRequestedDestination()
    override fun onRouteStarted(routeStartInfo: RouteStartInfo) = verifyRequestedDestination()

    private fun verifyRequestedDestination() {
        uiHandler.post {
            if (!routing || isFinishing) return@post
            runCatching { navi?.getDestinationInfo() }.onFailure {
                status.text = "경로 이벤트를 받았지만 목적지를 확인하지 못했어요. 다시 시도해 주세요."
            }
        }
    }

    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
        uiHandler.post {
            if(!routing || isFinishing) return@post
            val chosen=pending ?: return@post
            val actual = Coordinate(destinationInfo.latitude, destinationInfo.longitude)
            if (!actual.valid || actual.metersFrom(Coordinate(chosen.lat, chosen.lon)) > 200.0) {
                uiHandler.postDelayed({if(routing && !isFinishing)navi?.getDestinationInfo()},500L)
                return@post
            }
            routing=false;uiHandler.removeCallbacks(timeout)
            // Persist and start here: Pleos can become foreground before MainActivity
            // receives the Activity result. The service must not see the old PLAN state.
            val store = SessionStore(this)
            store.write(store.read().copy(stage="NAVIGATING",source="PLEOS",
                destinationName=chosen.title,destinationLat=chosen.lat,destinationLon=chosen.lon,
                driveMinutes=if(destinationInfo.remainTime>0) (destinationInfo.remainTime.toLong()+59)/60 else store.read().driveMinutes,
                remainingDistanceMeters=destinationInfo.remainDistance.toLong().coerceAtLeast(0),
                lastEtaAtMs=if(destinationInfo.remainTime>0) System.currentTimeMillis() else 0,
                stopName="",stopLat=0.0,stopLon=0.0,
                statusMessage="Pleos 목적지 확인 완료 · 내비 동행 중"))
            if(RecordingTour.read(this).active)RecordingTour.onDestinationConfirmed(this)
            RestAlerts.showCompanionShortcut(this)
            setResult(RESULT_OK,Intent().apply {
                putExtra("destinationName",chosen.title)
                putExtra("destinationLat",chosen.lat)
                putExtra("destinationLon",chosen.lon)
                putExtra("routeConfirmed",true)
                putExtra("nativeAlreadyForeground",nativeAlreadyForeground)
            })
            Toast.makeText(this,"Pleos 목적지 경로 설정 완료",Toast.LENGTH_LONG).show()
            finish()
        }
    }
    override fun onError(errorCode:NaviErrorCode) {
        uiHandler.post {
            if(routing) {routing=false;uiHandler.removeCallbacks(timeout);status.text="Pleos 경로 오류: $errorCode"}
        }
    }
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==812 && resultCode==RESULT_OK) {
            val s=SessionStore(this).read()
            if(RecordingTour.read(this).active)RecordingTour.onDestinationConfirmed(this)
            setResult(RESULT_OK,Intent().putExtra("destinationName",s.destinationName)
                .putExtra("destinationLat",s.destinationLat).putExtra("destinationLon",s.destinationLon))
            finish()
        }
    }

    override fun onDestroy() {
        uiHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        navi?.let {helper->
            if(connected) runCatching {helper.removeListener(this)}
            runCatching {helper.release()}
        }
        super.onDestroy()
    }
    override fun onPause() {
        if(routing)nativeAlreadyForeground=true
        super.onPause()
    }
    override fun onSaveInstanceState(out:Bundle) {
        out.putString("query",searchInput.text.toString())
        super.onSaveInstanceState(out)
    }
}
