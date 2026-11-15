package com.restsync.demo

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Read-only AAOS properties, as recommended by Pleos' public API policy. */
internal class VehicleParking(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var closed = false
    private var speed: Float? = null
    private var gear: Int? = null
    private var speedAt = 0L
    private var gearAt = 0L
    private val callback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.status != CarPropertyValue.STATUS_AVAILABLE) {
                if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) speedAt = 0 else gearAt = 0
                return
            }
            when (value.propertyId) {
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> { speed = value.value as? Float; speedAt = SystemClock.elapsedRealtime() }
                VehiclePropertyIds.GEAR_SELECTION -> { gear = value.value as? Int; gearAt = SystemClock.elapsedRealtime() }
            }
        }
        override fun onErrorEvent(propId: Int, zone: Int) {
            if (propId == VehiclePropertyIds.PERF_VEHICLE_SPEED) speedAt = 0 else gearAt = 0
        }
    }
    fun start() {
        closed = false
        if (car != null) return
        runCatching {
            car = Car.createCar(context.applicationContext, main, 0L) { connectedCar, connected ->
                if (closed) return@createCar
                if (connected) {
                    val m = connectedCar.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                    manager = m
                    listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.GEAR_SELECTION).forEach { id ->
                        runCatching { m?.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_NORMAL) }
                    }
                } else { manager = null; speedAt = 0; gearAt = 0 }
            }
        }
    }
    /** On a worker thread: obtain fresh values immediately before the start action. */
    fun check(): ParkingPolicy.State {
        val m = manager ?: return ParkingPolicy.State.UNKNOWN
        val s = runCatching { m.getProperty<Float>(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0) }.getOrNull()
        val g = runCatching { m.getProperty<Int>(VehiclePropertyIds.GEAR_SELECTION, 0) }.getOrNull()
        val validS = s?.takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }?.value
        val validG = g?.takeIf { it.status == CarPropertyValue.STATUS_AVAILABLE }?.value
        val speedAgeNs = s?.let { android.os.SystemClock.elapsedRealtimeNanos() - it.timestamp } ?: Long.MAX_VALUE
        // A non-P signal or actual motion always blocks; a stale zero speed never proves parking.
        val state = ParkingPolicy.evaluate(validS, validG, VehicleGear.GEAR_PARK, true)
        return if (state == ParkingPolicy.State.PARKED && speedAgeNs !in 0..5_000_000_000L) ParkingPolicy.State.UNKNOWN else state
    }
    fun close() {
        closed = true
        runCatching { manager?.unregisterCallback(callback) }
        runCatching { car?.disconnect() }
        manager = null; car = null; speedAt = 0; gearAt = 0
    }
}
