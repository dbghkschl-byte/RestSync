package com.restsync.demo

import org.json.JSONObject
import java.io.Reader
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
internal class RouteRequestException(val httpCode: Int, message: String): IllegalStateException(message)

internal object RestAreaCsv {
    fun read(reader: Reader): List<RestArea> {
        // CSV exports can contain quoted commas and embedded newlines in optional fields.
        val records = sequence {
            val input = reader.buffered()
            val fields = mutableListOf<String>()
            val field = StringBuilder()
            var quoted = false
            while (true) {
                val code = input.read()
                if (code < 0) break
                val char = code.toChar()
                when {
                    char == '"' -> {
                        if (quoted) {
                            input.mark(1)
                            if (input.read() == '"'.code) field.append('"')
                            else {
                                input.reset()
                                quoted = false
                            }
                        } else quoted = true
                    }
                    quoted -> field.append(char)
                    char == ',' -> {
                        fields.add(field.toString())
                        field.clear()
                    }
                    char == '\n' -> {
                        yield(fields + field.toString().trimEnd('\r'))
                        fields.clear()
                        field.clear()
                    }
                    else -> field.append(char)
                }
            }
            if (fields.isNotEmpty() || field.isNotEmpty()) yield(fields + field.toString().trimEnd('\r'))
        }.iterator()
        require(records.hasNext()) { "휴게소 CSV가 비어 있어요." }
        val header = records.next().map { it.trim().removePrefix("\uFEFF") }
        val name = header.indexOf("휴게소명")
        val latitude = header.indexOf("위도")
        val longitude = header.indexOf("경도")
        val direction = header.indexOf("도로노선방향")
        require(minOf(name, latitude, longitude) >= 0) { "전국휴게소정보 CSV의 휴게소명·위도·경도 열을 확인해 주세요." }
        val areas = mutableListOf<RestArea>()
        while (records.hasNext()) {
            val row = records.next()
            val label = row.getOrNull(name)?.trim().orEmpty()
            val lat = row.getOrNull(latitude)?.trim()?.toDoubleOrNull() ?: continue
            val lng = row.getOrNull(longitude)?.trim()?.toDoubleOrNull() ?: continue
            val point = Coordinate(lat, lng)
            if (label.isNotEmpty() && point.valid) {
                areas.add(RestArea(label, row.getOrNull(direction)?.trim().orEmpty(), point))
            }
        }
        require(areas.isNotEmpty()) { "CSV에 위치가 등록된 휴게소가 없어요." }
        return areas.distinct()
    }
}

internal class KakaoRoutes(private val apiKey: String) {
    companion object {
        private val cache=RouteSearchCache()
        private val requests=Executors.newFixedThreadPool(2)
    }
    var usedCachedResult=false;private set
    val requestCount=AtomicInteger()
    val responseBytes=AtomicLong()
    fun findStops(
        origin: Coordinate,
        destination: Coordinate,
        waypoints: List<Coordinate>,
        catalog: List<RestArea>,
        useCache:Boolean=true,
        onProgress:(List<LiveStop>,Int,Int)->Unit={_,_,_->}
    ): List<LiveStop> {
        require(origin.valid && destination.valid && waypoints.all {it.valid}) {"내비 위치와 경유지 좌표를 확인해 주세요."}
        usedCachedResult=false
        val fingerprint=java.security.MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray()).joinToString("") {"%02x".format(it)}
        val cacheKey=RouteSearchCache.Key(origin,destination,waypoints,fingerprint,catalog.hashCode())
        if(useCache) cache.get(cacheKey,System.nanoTime()/1_000_000L)?.let {usedCachedResult=true;return it}
        val normal = directions(origin, destination, waypoints)
            ?: error("목적지까지의 자동차 경로를 찾지 못했어요.")
        val firstLeg = normal.getJSONArray("sections").getJSONObject(0)
        val roads=firstLeg.getJSONArray("roads")
        val segments=ArrayList<Double>()
        for(i in 0 until roads.length()) {
            val vertices=roads.getJSONObject(i).getJSONArray("vertexes")
            for(j in 0 until vertices.length()-3 step 2) {
                segments.add(vertices.getDouble(j));segments.add(vertices.getDouble(j+1))
                segments.add(vertices.getDouble(j+2));segments.add(vertices.getDouble(j+3))
            }
        }
        val corridor=RouteCorridor(segments.toDoubleArray())
        val candidates = catalog.mapNotNull { area ->
            corridor.projectWithin(area.coordinate)?.let {projection->area to projection}
        }.sortedBy { it.second.routeOrder }.take(8)
        val baseline = normal.getJSONObject("summary")
        val baselineSeconds=baseline.getLong("duration")
        val baselineMeters=baseline.getLong("distance")
        val completed=ExecutorCompletionService<LiveStop?>(requests)
        val tasks=candidates.map { (area, _) -> completed.submit(Callable<LiveStop?> {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            // Section durations and snapped coordinates remain in summary=true responses.
            val via = try { directions(origin, destination, listOf(area.coordinate) + waypoints,true) }
                catch (e: RouteRequestException) { if (e.httpCode==400) null else throw e }
                ?: return@Callable null
            val summary = via.getJSONObject("summary")
            val snapped = summary.getJSONArray("waypoints").getJSONObject(0)
            val entry = Coordinate(snapped.getDouble("y"), snapped.getDouble("x"))
            val addedSeconds = summary.getLong("duration") - baselineSeconds
            val addedMeters = summary.getLong("distance") - baselineMeters
            if (entry.metersFrom(area.coordinate) > 150 || addedSeconds > 300 || addedMeters > 2500) {
                return@Callable null
            }
            val first = via.getJSONArray("sections").getJSONObject(0)
            val approach = first.getLong("duration")
            LiveStop(area, (approach + 59) / 60, (summary.getLong("duration") - approach + 59) / 60)
        }) }
        val result=try {
            val accepted=mutableListOf<LiveStop>()
            repeat(tasks.size) {index->
                val stop=try {completed.take().get()} catch(e:ExecutionException) {throw (e.cause ?: e)}
                if(stop!=null && accepted.none {RecommendationPolicy.id(it)==RecommendationPolicy.id(stop)})accepted.add(stop)
                onProgress(accepted.toList(),index+1,tasks.size)
            }
            accepted.sortedBy {it.approachMinutes}.take(5)
        } finally {tasks.filterNot {it.isDone}.forEach {it.cancel(true)}}
        cache.put(cacheKey,System.nanoTime()/1_000_000L,result)
        return result
    }

    private fun directions(origin: Coordinate, destination: Coordinate, waypoints: List<Coordinate>, summaryOnly:Boolean=false): JSONObject? {
        if(Thread.currentThread().isInterrupted)throw InterruptedException()
        require(origin.valid && destination.valid && waypoints.all { it.valid }) { "내비 위치나 경유지 좌표를 확인하지 못했어요. Pleos에서 경로를 다시 확인해 주세요." }
        val via = if (waypoints.isEmpty()) "" else "&waypoints=" +
            waypoints.joinToString("%7C") { it.query }
        val url = URL("https://apis-navi.kakaomobility.com/v1/directions?" +
            "origin=${origin.query}&destination=${destination.query}&summary=$summaryOnly$via")
        val connection = url.openConnection() as HttpsURLConnection
        try {
            requestCount.incrementAndGet()
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            connection.setRequestProperty("Authorization", "KakaoAK $apiKey")
            connection.setRequestProperty("Content-Type", "application/json")
            when (connection.responseCode) {
                200 -> Unit
                401, 403 -> throw RouteRequestException(connection.responseCode,"카카오 REST 키 또는 모빌리티 사용 설정을 확인해 주세요.")
                429 -> throw RouteRequestException(429,"카카오 길찾기 호출 한도에 도달했어요. 나중에 다시 시도해 주세요.")
                else -> {
                    val body=connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    val detail=runCatching { JSONObject(body).optString("msg").ifBlank { JSONObject(body).optString("message") } }.getOrDefault("")
                        .replace(apiKey,"[비공개]").take(180)
                    throw RouteRequestException(connection.responseCode,"길찾기 요청을 확인해 주세요 (HTTP ${connection.responseCode}). $detail")
                }
            }
            val body=connection.inputStream.bufferedReader().use {it.readText()}
            responseBytes.addAndGet(body.toByteArray(Charsets.UTF_8).size.toLong())
            if(Thread.currentThread().isInterrupted)throw InterruptedException()
            val result = JSONObject(body)
            val route = result.getJSONArray("routes").optJSONObject(0) ?: return null
            return route.takeIf { it.optInt("result_code", -1) == 0 }
        } finally {
            connection.disconnect()
        }
    }

}
