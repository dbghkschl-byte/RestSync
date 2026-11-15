package com.restsync.demo

internal const val SAFETY_MINUTES = 10L
internal const val NOTICE_MINUTES = 10L

internal data class RestPlan(
    val deadline: Long,
    val now: Long,
    val driveMinutes: Long,
    val approachMinutes: Long = 0
) {
    val departureLimit = deadline - driveMinutes - SAFETY_MINUTES
    val arrivalAtStop = now + approachMinutes
    val restMinutes = (departureLimit - arrivalAtStop).coerceAtLeast(0)
    val alarmTime = (departureLimit - NOTICE_MINUTES).coerceAtLeast(arrivalAtStop)
    val arrivalIfLeavingNow = now + driveMinutes
}
