package com.restsync.demo

import kotlin.math.cos
import kotlin.math.hypot

internal data class Coordinate(val latitude:Double,val longitude:Double) {
    val valid get()=latitude in -90.0..90.0 && longitude in -180.0..180.0 && (latitude!=0.0 || longitude!=0.0)
    val query get()="$longitude,$latitude"
    fun metersFrom(other:Coordinate):Double=hypot((longitude-other.longitude)*cos(Math.toRadians(latitude)),latitude-other.latitude)*111_195
}
internal data class RestArea(val name:String,val direction:String,val coordinate:Coordinate)
internal data class LiveStop(val area:RestArea,val approachMinutes:Long,val driveMinutes:Long)

/** Parsed once per baseline route, with cheap bounding-box rejection before projection. */
internal class RouteCorridor(private val segments:DoubleArray) {
    data class Projection(val distanceMeters:Double,val routeOrder:Double)
    init {require(segments.size%4==0)}
    fun distanceWithin(point:Coordinate,maxMeters:Double=400.0):Double=projectWithin(point,maxMeters)?.distanceMeters ?: Double.POSITIVE_INFINITY
    fun projectWithin(point:Coordinate,maxMeters:Double=400.0):Projection? {
        val scale=cos(Math.toRadians(point.latitude))
        val radius=maxMeters/111_195.0
        var best=radius*radius
        var found=false
        var order=0.0
        for(i in segments.indices step 4) {
            val x0=segments[i];val y0=segments[i+1];val x1=segments[i+2];val y1=segments[i+3]
            val latGap=maxOf(minOf(y0,y1)-point.latitude,point.latitude-maxOf(y0,y1),0.0)
            if(latGap>radius)continue
            val lonGap=maxOf(minOf(x0,x1)-point.longitude,point.longitude-maxOf(x0,x1),0.0)*scale
            if(lonGap*lonGap+latGap*latGap>best)continue
            val x=(x0-point.longitude)*scale;val y=y0-point.latitude
            val dx=(x1-point.longitude)*scale-x;val dy=y1-point.latitude-y
            val length=dx*dx+dy*dy
            val t=if(length==0.0)0.0 else (-(x*dx+y*dy)/length).coerceIn(0.0,1.0)
            val px=x+t*dx;val py=y+t*dy;val squared=px*px+py*py
            if(squared<best || (!found && squared<=best)) {best=squared;found=true;order=i/4+t}
        }
        return if(found)Projection(kotlin.math.sqrt(best)*111_195,order) else null
    }
}

internal class RouteSearchCache(private val ttlMs:Long=45_000L) {
    internal data class Key(val origin:Coordinate,val destination:Coordinate,val via:List<Coordinate>,val credential:String,val catalogHash:Int)
    private data class Entry(val key:Key,val savedAt:Long,val stops:List<LiveStop>)
    private var entry:Entry?=null
    @Synchronized fun get(key:Key,nowMs:Long):List<LiveStop>? {
        if(!key.origin.valid || !key.destination.valid || key.via.any {!it.valid})return null
        val item=entry ?: return null
        if(nowMs-item.savedAt !in 0..ttlMs || key.destination!=item.key.destination || key.via!=item.key.via ||
            key.credential!=item.key.credential || key.catalogHash!=item.key.catalogHash || key.origin.metersFrom(item.key.origin)>75.0)return null
        return item.stops
    }
    @Synchronized fun put(key:Key,nowMs:Long,stops:List<LiveStop>) {
        entry=Entry(key.copy(via=key.via.toList()),nowMs,stops.toList())
    }
}
