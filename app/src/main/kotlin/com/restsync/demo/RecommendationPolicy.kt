package com.restsync.demo

/** Invalidates delayed search callbacks when the user selects a result or starts over. */
internal class SearchRunGate {
    private var version=0L
    private var active=false
    fun begin():Long {version++;active=true;return version}
    fun isCurrent(token:Long)=active && token==version
    fun finish(token:Long) {if(isCurrent(token))active=false}
    fun cancel() {version++;active=false}
}

internal object RecommendationPolicy {
    fun id(stop:LiveStop)=stop.area.coordinate.query
    fun resolve(stops:List<LiveStop>,id:String)=stops.firstOrNull {RecommendationPolicy.id(it)==id}
    fun isSameStop(name:String,point:Coordinate,selected:LiveStop)=name==selected.area.name && point.valid && point.metersFrom(selected.area.coordinate)<=150.0
    fun matchesRegistered(name:String,point:Coordinate,stopName:String,stop:Coordinate,allowNameOnly:Boolean):Boolean {
        if(!stop.valid || stopName.isBlank() || (name.isNotBlank() && name!=stopName))return false
        if(point.valid)return point.metersFrom(stop)<=150.0
        return allowNameOnly && point.latitude==0.0 && point.longitude==0.0 && name==stopName
    }
    fun allowanceLabel(maxMinutes:Long)=if(maxMinutes<=0) "도착 목표" else "가능 휴식"
    fun allowanceValue(maxMinutes:Long)=if(maxMinutes<=0) "조정 필요" else "${maxMinutes}분"
    fun explanation(maxMinutes:Long,requestedMinutes:Long):String?=when {
        maxMinutes<=0 -> "현재 도착 목표에 여유가 없어요. 쉬는 시간은 도착 후 정할 수 있어요."
        maxMinutes<requestedMinutes -> "희망 휴식보다 시간 여유가 적어요. 도착 목표를 조정할 수 있어요."
        else -> null
    }
}
