package com.restsync.demo

/**
 * Recording-only changes to an already confirmed, real Pleos rest session.
 * The caller must additionally guard these actions with the active recording tour.
 * Real ETA, coordinates, route source, and waypoint proof are never replaced here.
 */
internal object RealRecordingPolicy {
    private const val REST_ALLOWANCE_MINUTES = 35L

    /**
     * Anchors the recording's arrival target to the actual remaining drive estimate.
     * Call at rest start, then once again after the first actual Pleos ETA arrives.
     * Once the tour extends or injects traffic, this initialization must not repeat.
     */
    fun prepareRest(s:RestSession, now:Long):RestSession {
        require(s.stage=="RESTING" && s.source=="PLEOS")
        require(s.startedAtMs>0 && now>=s.startedAtMs)
        require(s.driveMinutes in 1..1440)
        require(s.requestedMinutes==30L && s.simulatedDelayMinutes==0L)
        val budgetMinutes=s.driveMinutes+RestLogic.SAFETY_MIN+RestLogic.PREWARNING_MIN+REST_ALLOWANCE_MINUTES
        val target=Math.addExact(s.startedAtMs,Math.multiplyExact(budgetMinutes,RestLogic.MINUTE_MS))
        return s.copy(deadlineMs=target,respectDeadline=true,alertedForMs=-1,
            statusMessage="실제 내비 ETA 기준 · 녹화용 도착 목표로 35분의 휴식 여유를 설정했어요.")
    }

    /**
     * Advances only the recording scenario by moving its start and target together.
     * Existing wall-clock timer, monitor service, notification and due logic then
     * agree on the shortened countdown without a second virtual-clock mechanism.
     */
    fun accelerate(s:RestSession, now:Long, secondsLeft:Long=8):RestSession {
        require(s.stage=="RESTING" && s.source=="PLEOS")
        require(s.startedAtMs>0 && s.deadlineMs>0 && now>=s.startedAtMs)
        require(secondsLeft in 1..60)
        val alertAt=RestLogic.calculate(s,now).nextAlertMs
        val target=Math.addExact(now,Math.multiplyExact(secondsLeft,1000L))
        val shift=(alertAt-target).coerceAtLeast(0)
        if(shift==0L)return s
        require(s.startedAtMs>shift && s.deadlineMs>shift)
        return s.copy(startedAtMs=s.startedAtMs-shift,deadlineMs=s.deadlineMs-shift,
            alertedForMs=-1,
            statusMessage="녹화용 시간 가속 · 실제 경과 시간이 아니에요. 출발 알림까지 ${secondsLeft}초로 넘겼어요.")
    }
}
