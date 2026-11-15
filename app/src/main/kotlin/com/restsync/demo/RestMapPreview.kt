package com.restsync.demo

import android.app.Activity
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.view.ViewGroup
import android.widget.LinearLayout

/** OSM/Leaflet preview ONLY. Actual Pleos navigation is a separate native screen. */
internal class RestMapPreview(
    private val activity:Activity,
    private val onMapTap:(Double,Double)->Unit
) : FrameLayout(activity) {
    private val browser=WebView(activity)
    private var latestDestination: Triple<Double,Double,String>? = null
    init {
        browser.settings.javaScriptEnabled=true
        browser.settings.domStorageEnabled=true
        browser.settings.allowFileAccess=false
        browser.settings.allowContentAccess=false
        browser.webViewClient=object:WebViewClient() {
            override fun onPageFinished(view:WebView?,url:String?) {
                super.onPageFinished(view,url)
                latestDestination?.let { (lat,lon,label) -> displayDestination(lat,lon,label) }
            }
        }
        browser.addJavascriptInterface(MapActions(),"RestSyncMap")
        browser.setBackgroundColor(RestTheme.palePurple)
        addView(browser,LayoutParams(-1,-1))
        // HTTPS CDN/tile requests can fail without network; the HTML has a clear fallback.
        val html=activity.assets.open("restsync_map.html").bufferedReader().use {it.readText()}
        browser.loadDataWithBaseURL("https://restsync.demo.invalid/",html,"text/html","UTF-8",null)
    }
    inner class MapActions {
        @JavascriptInterface fun selectCoordinate(lat:Double,lon:Double) {
            if(lat.isFinite()&&lon.isFinite()) activity.runOnUiThread {onMapTap(lat,lon)}
        }
    }
    fun showDestination(lat:Double,lon:Double,label:String) {
        if(lat in -90.0..90.0 && lon in -180.0..180.0) {
            latestDestination=Triple(lat,lon,label)
            displayDestination(lat,lon,label)
        }
    }
    private fun displayDestination(lat:Double,lon:Double,label:String) {
        val safe=org.json.JSONObject.quote(label)
        browser.evaluateJavascript("window.setRestDestination && window.setRestDestination($lat,$lon,$safe)",null)
    }
    fun dispose() {browser.removeJavascriptInterface("RestSyncMap");browser.destroy()}
}
