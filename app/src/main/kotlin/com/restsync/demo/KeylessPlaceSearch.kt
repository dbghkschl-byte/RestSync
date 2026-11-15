package com.restsync.demo

import android.content.Context
import android.os.SystemClock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class DestinationPlace(val name:String,val address:String,val latitude:Double,val longitude:Double)
internal data class PlaceSearchResult(val places:List<DestinationPlace>,val cached:Boolean)

/** Manual, cached POI searches only. Public Photon is a prototype provider, not a traffic API. */
internal object KeylessPlaceSearch {
    private var lastRequestMs=0L
    private const val MAX_AGE=24*60*60_000L

    @Synchronized fun search(context:Context,query:String):PlaceSearchResult {
        val cache=context.getSharedPreferences("restsync_destination_cache",Context.MODE_PRIVATE)
        val cacheKey=query.lowercase().hashCode().toString()
        val saved=runCatching { JSONObject(cache.getString(cacheKey,"").orEmpty()) }.getOrNull()
        if(saved!=null && saved.optString("query")==query && System.currentTimeMillis()-saved.optLong("at") in 0..MAX_AGE) {
            return PlaceSearchResult(parse(saved.getString("body")),true)
        }
        // No autocomplete/background/bulk requests. Space repeated manual requests apart.
        val wait=1200L-(SystemClock.elapsedRealtime()-lastRequestMs)
        if(wait>0) Thread.sleep(wait)
        lastRequestMs=SystemClock.elapsedRealtime()
        val uri="https://photon.komoot.io/api/?limit=10&bbox=124,33,132,39&q="+URLEncoder.encode(query,"UTF-8")
        val c=(URL(uri).openConnection() as HttpURLConnection).apply {
            connectTimeout=9000;readTimeout=9000
            setRequestProperty("User-Agent","RestSync-Pleos-Companion/0.9.0 (Android development prototype)")
        }
        val body=try {
            when(val code=c.responseCode) {
                in 200..299 -> c.inputStream.bufferedReader().use {it.readText()}
                429 -> error("검색 요청이 잠시 많아요. 조금 뒤 다시 눌러 주세요.")
                else -> error("장소 검색 서비스에 연결하지 못했어요 (HTTP $code). 잠시 후 다시 시도해 주세요.")
            }
        } finally {c.disconnect()}
        val places=parse(body)
        // Bound local cache size; never store an API key in this search component.
        if(cache.all.size>=60) cache.edit().clear().apply()
        cache.edit().putString(cacheKey,JSONObject().put("query",query).put("at",System.currentTimeMillis()).put("body",body).toString()).apply()
        return PlaceSearchResult(places,false)
    }

    private fun parse(body:String):List<DestinationPlace> {
        val features=JSONObject(body).optJSONArray("features") ?: JSONArray()
        return (0 until features.length()).mapNotNull {i->
            val f=features.optJSONObject(i) ?: return@mapNotNull null
            val p=f.optJSONObject("properties") ?: return@mapNotNull null
            val xy=f.optJSONObject("geometry")?.optJSONArray("coordinates") ?: return@mapNotNull null
            val lon=xy.optDouble(0,Double.NaN);val lat=xy.optDouble(1,Double.NaN)
            val name=p.optString("name").trim()
            if(name.isBlank() || !lat.isFinite() || !lon.isFinite() || lat !in 33.0..39.0 || lon !in 124.0..132.0) return@mapNotNull null
            val address=listOf("state","city","district","street","housenumber").map {p.optString(it).trim()}
                .filter {it.isNotBlank()}.distinct().joinToString(" ")
            DestinationPlace(name,address.ifBlank {"좌표 %.5f, %.5f".format(java.util.Locale.US,lat,lon)},lat,lon)
        }.distinctBy {listOf(it.name,it.latitude,it.longitude)}
    }
}
