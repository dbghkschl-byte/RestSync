package com.restsync.demo

internal object FlowPolicy {
    fun wide(stage:String)=stage in setOf("PLAN","RESTING","DEPARTURE","FINISHED")
    fun beginRest(s:RestSession,minutes:Long,now:Long): RestSession {
        require(s.stage in setOf("WAYPOINT","NAVIGATING"))
        require(minutes in 1..180)
        val early=s.deadlineMs-(s.driveMinutes+RestLogic.SAFETY_MIN+RestLogic.PREWARNING_MIN)*60_000L
        return s.copy(stage="RESTING",requestedMinutes=minutes,startedAtMs=now,
            stopName=s.stopName.ifBlank {"직접 선택한 휴식 장소"},baselineDriveMinutes=s.driveMinutes,
            lastEtaAtMs=0,alertedForMs=-1,respectDeadline=early>now,simulatedDelayMinutes=0,
            statusMessage=if(early>now) "휴식이 시작됐어요." else "도착 목표에 여유가 없어 선택한 휴식 시간을 기준으로 알려드려요.")
    }
    fun due(s:RestSession,now:Long):RestSession {
        if(s.stage!="RESTING") return s
        val d=RestLogic.calculate(s,now)
        return if(d.shouldNotifyNow) s.copy(stage="DEPARTURE",alertedForMs=d.nextAlertMs) else s
    }
    fun updateDriveEstimate(s:RestSession,minutes:Long,now:Long):RestSession {
        require(s.stage=="RESTING" && minutes in 1..1440)
        val first=s.lastEtaAtMs==0L
        val updated=s.copy(driveMinutes=minutes,lastEtaAtMs=now,baselineDriveMinutes=if(first)minutes else s.baselineDriveMinutes)
        val initialGoalImpossible=first && updated.respectDeadline && RestLogic.calculate(updated.copy(simulatedDelayMinutes=0),now).earlyWarningMs<=now
        return updated.copy(respectDeadline=updated.respectDeadline && !initialGoalImpossible,
            statusMessage=when {
                initialGoalImpossible -> "현재 내비 예상으로는 도착 목표가 어려워 선택한 휴식 시간으로 알려드려요."
                first -> "Pleos 예상 이동 시간을 확인했어요."
                minutes!=s.driveMinutes -> "Pleos ETA가 갱신됐어요."
                else -> "Pleos ETA 확인 완료"
            })
    }
    fun simulateTraffic(s:RestSession,delta:Long,now:Long):RestSession {
        require(s.stage=="RESTING")
        val revised=s.copy(simulatedDelayMinutes=(s.simulatedDelayMinutes+delta).coerceIn(0,1440),alertedForMs=-1)
        return revised.copy(statusMessage=simulationEffect(revised,now))
    }
    fun simulationEffect(s:RestSession,now:Long):String {
        if(s.simulatedDelayMinutes<=0)return "교통 정체 시연을 해제했어요."
        if(!s.respectDeadline)return "현재 도착 목표가 어려워 선택한 휴식 시간을 유지해요."
        val original=RestLogic.calculate(s.copy(simulatedDelayMinutes=0),now).nextAlertMs
        val revised=RestLogic.calculate(s,now).nextAlertMs
        val earlier=(original-revised).coerceAtLeast(0)/60_000
        return if(earlier>0) "정체 시연으로 출발 알림을 ${earlier}분 앞당겼어요."
            else "정체를 반영했지만 도착 목표에 여유가 있어 휴식 시간을 유지해요."
    }
    fun changeDeadline(s:RestSession,deadline:Long,now:Long):RestSession {
        val revised=s.copy(deadlineMs=deadline)
        if(s.stage!="RESTING")return revised
        val feasible=deadline-(s.driveMinutes+RestLogic.SAFETY_MIN+RestLogic.PREWARNING_MIN)*60_000>now
        return revised.copy(respectDeadline=feasible,alertedForMs=-1,
            statusMessage=if(feasible) "새 도착 목표로 출발 알림을 다시 계산했어요." else "도착 목표에 여유가 없어 선택한 휴식 시간을 유지해요.")
    }
    fun acknowledge(s:RestSession):RestSession {
        require(s.stage=="DEPARTURE")
        return s.copy(stage="FINISHED",simulatedDelayMinutes=0)
    }
    fun matchesStop(lat:Double,lon:Double,stopLat:Double,stopLon:Double):Boolean {
        if(!lat.isFinite() || !lon.isFinite() || !stopLat.isFinite() || !stopLon.isFinite()) return false
        if(lat !in -90.0..90.0 || stopLat !in -90.0..90.0 || lon !in -180.0..180.0 || stopLon !in -180.0..180.0) return false
        if((lat==0.0 && lon==0.0) || (stopLat==0.0 && stopLon==0.0)) return false
        val x=(lon-stopLon)*kotlin.math.cos(Math.toRadians(lat))
        return kotlin.math.hypot(x,lat-stopLat)*111_195<=150
    }
}
