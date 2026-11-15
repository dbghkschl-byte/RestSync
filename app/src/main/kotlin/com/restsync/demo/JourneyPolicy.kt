package com.restsync.demo

internal object JourneyPolicy {
    fun connect(s: RestSession,name: String,lat: Double,lon: Double,seconds: Long,now: Long): RestSession {
        require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0 && (lat!=0.0 || lon!=0.0))
        require(seconds in 1..172800)
        require(name.isNotBlank() && s.stage!="RESTING")
        val same=s.source=="PLEOS" && kotlin.math.abs(s.destinationLat-lat)<0.00001 && kotlin.math.abs(s.destinationLon-lon)<0.00001
        val keepStop=same && s.stage=="WAYPOINT"
        return s.copy(stage=if(keepStop) "WAYPOINT" else "NAVIGATING",
            source="PLEOS",destinationName=name,destinationLat=lat,destinationLon=lon,
            driveMinutes=if(keepStop) s.driveMinutes else (seconds+59)/60,lastEtaAtMs=now,
            stopName=if(keepStop) s.stopName else "",stopLat=if(keepStop) s.stopLat else 0.0,stopLon=if(keepStop) s.stopLon else 0.0,
            approachMinutes=if(keepStop) s.approachMinutes else 0,
            startedAtMs=0,alertedForMs=-1,statusMessage="Pleos 현재 경로 연결 완료")
    }
}
