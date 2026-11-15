package com.restsync.demo

import android.content.Context

/** Local demo state only. No location, key or account data is stored here. */
internal class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("restsync_session_v1", Context.MODE_PRIVATE)

    fun read(): RestSession = RestSession(
        stage = prefs.getString("stage", "PLAN") ?: "PLAN",
        deadlineMs = prefs.getLong("deadline", 0),
        requestedMinutes = prefs.getLong("requested", 40),
        stopName = prefs.getString("stop", "") ?: "",
        stopLat = java.lang.Double.longBitsToDouble(prefs.getLong("stopLat", 0L)),
        stopLon = java.lang.Double.longBitsToDouble(prefs.getLong("stopLon", 0L)),
        originName = prefs.getString("originName", "한양대학교 ERICA") ?: "한양대학교 ERICA",
        destinationName = prefs.getString("destinationName", "시연 목적지") ?: "시연 목적지",
        destinationLat = java.lang.Double.longBitsToDouble(prefs.getLong("destLat", 0L)),
        destinationLon = java.lang.Double.longBitsToDouble(prefs.getLong("destLon", 0L)),
        approachMinutes = prefs.getLong("approach", 0),
        driveMinutes = prefs.getLong("drive", 35),
        source = prefs.getString("source", "DEMO") ?: "DEMO",
        waypointProof=prefs.getString("waypointProof","COORDINATES") ?: "COORDINATES",
        startedAtMs = prefs.getLong("started", 0),
        lastEtaAtMs = prefs.getLong("etaAt", 0),
        baselineDriveMinutes = prefs.getLong("baselineDrive",0),
        remainingDistanceMeters = prefs.getLong("distanceMeters",0),
        respectDeadline = prefs.getBoolean("respectDeadline",true),
        simulatedDelayMinutes = prefs.getLong("simulatedDelayMinutes",0),
        alertedForMs = prefs.getLong("alerted", -1),
        statusMessage = prefs.getString("status", "") ?: ""
    )

    fun write(s: RestSession) {
        prefs.edit()
            .putString("stage", s.stage)
            .putLong("deadline", s.deadlineMs)
            .putLong("requested", s.requestedMinutes)
            .putString("stop", s.stopName)
            .putLong("stopLat", java.lang.Double.doubleToRawLongBits(s.stopLat))
            .putLong("stopLon", java.lang.Double.doubleToRawLongBits(s.stopLon))
            .putString("originName", s.originName)
            .putString("destinationName", s.destinationName)
            .putLong("destLat", java.lang.Double.doubleToRawLongBits(s.destinationLat))
            .putLong("destLon", java.lang.Double.doubleToRawLongBits(s.destinationLon))
            .putLong("approach", s.approachMinutes)
            .putLong("drive", s.driveMinutes)
            .putString("source", s.source)
            .putString("waypointProof",s.waypointProof)
            .putLong("started", s.startedAtMs)
            .putLong("etaAt", s.lastEtaAtMs)
            .putLong("baselineDrive",s.baselineDriveMinutes)
            .putLong("distanceMeters",s.remainingDistanceMeters)
            .putBoolean("respectDeadline",s.respectDeadline)
            .putLong("simulatedDelayMinutes",s.simulatedDelayMinutes)
            .putLong("alerted", s.alertedForMs)
            .putString("status", s.statusMessage)
            .apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
