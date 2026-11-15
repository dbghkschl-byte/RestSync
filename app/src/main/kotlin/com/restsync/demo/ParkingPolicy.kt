package com.restsync.demo

/** Missing/stale values are unknown, never evidence of a safe stop. */
internal object ParkingPolicy {
    enum class State { PARKED, UNSAFE, UNKNOWN }
    fun evaluate(speedMps: Float?, gear: Int?, parkGear: Int, fresh: Boolean): State {
        if (!fresh) return State.UNKNOWN
        if (speedMps != null && speedMps.isFinite() && kotlin.math.abs(speedMps) > 0.05f) return State.UNSAFE
        if (gear != null && gear != parkGear) return State.UNSAFE
        return if (speedMps != null && speedMps.isFinite() && gear == parkGear) State.PARKED else State.UNKNOWN
    }
    fun nearStop(lat: Double, lon: Double, stopLat: Double, stopLon: Double): Boolean {
        if (!listOf(lat, lon, stopLat, stopLon).all { it.isFinite() } ||
            lat !in -90.0..90.0 || stopLat !in -90.0..90.0 || lon !in -180.0..180.0 || stopLon !in -180.0..180.0 ||
            (stopLat == 0.0 && stopLon == 0.0)) return false
        val r = Math.PI / 180.0
        val a = kotlin.math.sin((lat - stopLat) * r / 2).let { it * it } +
            kotlin.math.cos(lat * r) * kotlin.math.cos(stopLat * r) *
            kotlin.math.sin((lon - stopLon) * r / 2).let { it * it }
        return 6_371_000 * 2 * kotlin.math.asin(kotlin.math.sqrt(a.coerceIn(0.0, 1.0))) <= 350
    }
}
