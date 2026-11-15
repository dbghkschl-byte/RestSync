package com.restsync.demo

internal object WaypointPolicy {
    data class Point(val name: String,val lat: Double,val lon: Double)
    fun active(points: List<Point>): List<Point> = points.filterNot {
        it.name.isBlank() && it.lat==0.0 && it.lon==0.0
    }.onEach {
        require(it.lat.isFinite() && it.lon.isFinite() && it.lat in -90.0..90.0 && it.lon in -180.0..180.0 && (it.lat!=0.0 || it.lon!=0.0)) {
            "내비 경유지의 위치를 읽지 못했어요. Pleos에서 경유지를 확인한 뒤 다시 찾아 주세요."
        }
    }
}
