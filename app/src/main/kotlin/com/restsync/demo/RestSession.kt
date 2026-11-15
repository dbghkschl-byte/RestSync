package com.restsync.demo

import kotlin.math.min

/** All times are wall-clock epoch milliseconds. No Android dependencies. */
internal data class RestSession(
    val stage: String = "PLAN", // PLAN, NAVIGATING, SUGGESTIONS, WAYPOINT, RESTING, FINISHED
    val deadlineMs: Long = 0L,
    val requestedMinutes: Long = 40L,
    val stopName: String = "",
    val stopLat: Double = 0.0,
    val stopLon: Double = 0.0,
    val originName: String = "한양대학교 ERICA",
    val destinationName: String = "시연 목적지",
    val destinationLat: Double = 0.0,
    val destinationLon: Double = 0.0,
    val approachMinutes: Long = 0L,
    val driveMinutes: Long = 35L,
    val source: String = "DEMO", // DEMO or PLEOS
    val waypointProof:String="COORDINATES",
    val startedAtMs: Long = 0L,
    val lastEtaAtMs: Long = 0L,
    val baselineDriveMinutes: Long = 0L,
    val remainingDistanceMeters: Long = 0L,
    val respectDeadline: Boolean = true,
    val simulatedDelayMinutes: Long = 0L,
    val alertedForMs: Long = -1L,
    val statusMessage: String = ""
)

internal data class RestDecision(
    val departureLimitMs: Long,
    val earlyWarningMs: Long,
    val requestedEndMs: Long,
    val nextAlertMs: Long,
    val predictedArrivalMs: Long,
    val remainingRestMinutes: Long,
    val cannotMeetDeadline: Boolean,
    val shouldNotifyNow: Boolean
)

internal object RestLogic {
    const val MINUTE_MS = 60_000L
    const val SAFETY_MIN = 10L
    const val PREWARNING_MIN = 10L

    fun calculate(session: RestSession, nowMs: Long): RestDecision {
        val driveMs = (session.driveMinutes.coerceAtLeast(0) + session.simulatedDelayMinutes.coerceIn(0,1440)) * MINUTE_MS
        val departureLimit = session.deadlineMs - driveMs - SAFETY_MIN * MINUTE_MS
        val early = departureLimit - PREWARNING_MIN * MINUTE_MS
        val requestedEnd = if (session.startedAtMs > 0) {
            session.startedAtMs + session.requestedMinutes.coerceAtLeast(0) * MINUTE_MS
        } else Long.MAX_VALUE
        // Notify at the requested end, or before the deadline limit, whichever comes first.
        val alertMs = if(session.respectDeadline) min(early, requestedEnd) else requestedEnd
        val arrival = nowMs + driveMs
        return RestDecision(
            departureLimitMs = departureLimit,
            earlyWarningMs = early,
            requestedEndMs = requestedEnd,
            nextAlertMs = alertMs,
            predictedArrivalMs = arrival,
            remainingRestMinutes = ((departureLimit - nowMs).coerceAtLeast(0) + MINUTE_MS - 1) / MINUTE_MS,
            cannotMeetDeadline = arrival > session.deadlineMs,
            shouldNotifyNow = nowMs >= alertMs
        )
    }
}
