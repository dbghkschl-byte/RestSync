package com.restsync.demo

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData

/** Foreground-only arrival hint. It never marks arrival or starts rest automatically. */
internal class ArrivalAssist(context: Context, private val near: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val app = context.applicationContext
    private var sdk: FusedLocation? = null
    private var target: Pair<Double, Double>? = null
    private var closed = false
    private var announced: Pair<Double, Double>? = null
    private val listener = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            main.post {
                val t = target ?: return@post
                if (closed || location == null || announced == t) return@post
                val age = System.currentTimeMillis() - location.timestampMillis
                if (age !in 0..60_000) return@post
                if (ParkingPolicy.nearStop(location.latitude, location.longitude, t.first, t.second)) {
                    announced = t
                    near()
                }
            }
        }
        override fun onError(exception: Exception) = Unit // Manual arrival remains available.
    }
    fun start(lat: Double, lon: Double) {
        if (closed || sdk != null || lat == 0.0 && lon == 0.0) return
        target = lat to lon
        runCatching {
            val current = FusedLocation(app)
            if (!current.isSupported()) return
            sdk = current
            current.initialize()
            current.getLastKnownLocation(listener)
            current.registerFusedLocationCallback(listener)
        }.onFailure { stop() }
    }
    fun stop() {
        target = null
        sdk?.let { current ->
            runCatching { current.unregisterFusedLocationCallback(listener) }
            runCatching { current.release() }
        }
        sdk = null
    }
    fun close() { closed = true; stop(); main.removeCallbacksAndMessages(null) }
}
