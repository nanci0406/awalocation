package com.nanci.awalocation.xposed

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import de.robv.android.xposed.XposedHelpers
import com.nanci.awalocation.xposed.utils.FakeLoc
import com.nanci.awalocation.xposed.utils.Logger
import com.nanci.awalocation.nmea.NMEA
import com.nanci.awalocation.nmea.NmeaValue
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.random.Random

abstract class BaseLocationHook: BaseDivineService() {
    companion object {
        private val injectedLocations: MutableSet<Location> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
        private val lastInjectedWallTimeMs = AtomicLong(0L)
        internal const val EXTRA_INJECTED_LAT = "awalocation.base_lat"
        internal const val EXTRA_INJECTED_LON = "awalocation.base_lon"

        private fun monotonicWallTimeMs(): Long {
            val now = System.currentTimeMillis()
            while (true) {
                val prev = lastInjectedWallTimeMs.get()
                val next = if (now >= prev || prev - now > 5_000L) now else prev
                if (lastInjectedWallTimeMs.compareAndSet(prev, next)) {
                    return next
                }
            }
        }
    }

    fun injectLocation(originLocation: Location, realLocation: Boolean = true): Location {
        val extras = originLocation.extras
        val hasInjectedTag = injectedLocations.contains(originLocation) ||
            (extras?.containsKey("latlon") == true &&
                extras.getDouble("latlon", Double.NaN) == originLocation.latitude + originLocation.longitude)

        if (realLocation && !hasInjectedTag) {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    originLocation.provider == LocationManager.GPS_PROVIDER && originLocation.isComplete
                } else {
                    originLocation.provider == LocationManager.GPS_PROVIDER
                }
            ) {
                FakeLoc.lastLocation = originLocation
            }
        } else if (!realLocation) {
            originLocation.altitude = FakeLoc.altitude
        }

        if (!FakeLoc.enable)
            return originLocation

        // 仅当该 Location 是在同一事件帧内（<250ms）刚注入过、非主动推送(realLocation=true)、且基准坐标未变时才跳过重复注入；
        // 若超过 250ms（如 getLastLocation 读取缓存）或路线坐标已推进，必须重新刷新时间戳、速度与微漂移。
        val isFreshSameFrame = originLocation.elapsedRealtimeNanos > 0L &&
            abs(SystemClock.elapsedRealtimeNanos() - originLocation.elapsedRealtimeNanos) < 250_000_000L
        val matchesCurrentTarget = isFreshSameFrame &&
            extras != null &&
            extras.containsKey(EXTRA_INJECTED_LAT) &&
            extras.containsKey(EXTRA_INJECTED_LON) &&
            abs(extras.getDouble(EXTRA_INJECTED_LAT, Double.NaN) - FakeLoc.latitude) < 1e-7 &&
            abs(extras.getDouble(EXTRA_INJECTED_LON, Double.NaN) - FakeLoc.longitude) < 1e-7
        if (realLocation && hasInjectedTag && matchesCurrentTarget) {
            return originLocation
        }

        val provider = if (FakeLoc.disableNetworkLocation && originLocation.provider == LocationManager.NETWORK_PROVIDER) {
            LocationManager.GPS_PROVIDER
        } else {
            originLocation.provider ?: LocationManager.GPS_PROVIDER
        }

        val location = Location(provider)
        val baseAccuracy = if (FakeLoc.accuracy > 0.0f) {
            FakeLoc.accuracy
        } else if (originLocation.hasAccuracy() && originLocation.accuracy > 0.0f) {
            originLocation.accuracy
        } else {
            4.5f
        }
        location.accuracy = (baseAccuracy + Random.nextDouble(-0.4, 0.6).toFloat()).coerceAtLeast(1.0f)
        val targetLat = FakeLoc.latitude
        val targetLon = FakeLoc.longitude
        val (jitteredLat, jitteredLon) = FakeLoc.jitterLocation(lat = targetLat, lon = targetLon)
        location.latitude = jitteredLat
        location.longitude = jitteredLon
        location.altitude = FakeLoc.altitude

        val currentReportedSpeed = FakeLoc.reportedSpeed()
        if (currentReportedSpeed > 0.0f) {
            location.speed = currentReportedSpeed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.speedAccuracyMetersPerSecond = 0.18f
            }
        } else {
            location.speed = 0.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.speedAccuracyMetersPerSecond = 0.05f
            }
        }

        if (location.altitude == 0.0) {
            location.altitude = 80.0
        }

        // 统一使用当前单调系统时钟与启动时钟，防止 getLastLocation 或延迟硬件回调将新坐标打上旧时间戳造成瞬时配速畸变
        location.time = monotonicWallTimeMs()

        // final addition of zero is to remove -0 results. while these are technically within the
        // range [0, 360) according to IEEE semantics, this eliminates possible user confusion.
        var modBearing = FakeLoc.bearing % 360.0 + 0.0
        if (modBearing < 0) {
            modBearing += 360.0
        }
        location.bearing = modBearing.toFloat()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // bearingAccuracyDegrees is an error bound (degrees), not the bearing itself.
            location.bearingAccuracyDegrees = 5.0f
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock = false
        }

        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            location.elapsedRealtimeUncertaintyNanos = originLocation.elapsedRealtimeUncertaintyNanos
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val originVAcc = if (originLocation.hasVerticalAccuracy()) originLocation.verticalAccuracyMeters else 0f
            location.verticalAccuracyMeters = if (originVAcc in 0.5f..15.0f) {
                originVAcc
            } else {
                Random.nextDouble(2.5, 5.5).toFloat()
            }
        }
        location.extras = originLocation.extras?.let { Bundle(it) } ?: Bundle()
        location.extras?.putDouble("latlon", location.latitude + location.longitude)
        location.extras?.putDouble(EXTRA_INJECTED_LAT, targetLat)
        location.extras?.putDouble(EXTRA_INJECTED_LON, targetLon)
        val inUseSats = com.nanci.awalocation.xposed.hooks.LocationServiceHook.lastInUseSatelliteCount.coerceIn(14, 28)
        location.extras?.putInt("satellites", inUseSats)
        location.extras?.putInt("maxCn0", Random.nextInt(40, 48))
        location.extras?.putInt("meanCn0", Random.nextInt(34, 41))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (originLocation.hasMslAltitude()) {
                location.mslAltitudeMeters = FakeLoc.altitude
            }
            if (originLocation.hasMslAltitudeAccuracy()) {
                val originMslAcc = originLocation.mslAltitudeAccuracyMeters
                location.mslAltitudeAccuracyMeters = if (originMslAcc in 0.5f..15.0f) {
                    originMslAcc
                } else {
                    Random.nextDouble(2.5, 5.5).toFloat()
                }
            }
        }
        if (FakeLoc.hideMock) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = false
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                location.isMock = true
            }
            location.extras?.putBoolean("awalocation.enable", true)
            location.extras?.putBoolean("is_mock", true)
        }

        kotlin.runCatching {
            XposedHelpers.callMethod(location, "makeComplete")
        }.onFailure {
            Logger.error("makeComplete failed", it)
        }

        injectedLocations.add(location)

        if (FakeLoc.enableDebugLog) {
            Logger.debug("injectLocation success! $location")
        }

        return location
    }

    /**
     * NMEA ddmm.mmmm uses an absolute value plus an N/S or E/W hemisphere field.
     * Keep that contract for negative coordinates as well.
     */
    private fun nmeaCoordinate(value: Double): Double {
        val absolute = abs(value)
        val degree = absolute.toInt()
        val minute = (absolute - degree) * 60
        return degree * 100 + minute
    }

    fun injectNMEA(nmeaStr: String): String? {
        if (!FakeLoc.enable) {
            return null
        }

        kotlin.runCatching {
            val nmea = NMEA.valueOf(nmeaStr)
            when(val value = nmea.value) {
                is NmeaValue.DTM -> {
                    return null
                }
                is NmeaValue.GGA -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.fixQuality == 0) {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    value.latitude = nmeaCoordinate(FakeLoc.latitude)
                    value.longitude = nmeaCoordinate(FakeLoc.longitude)

                    return nmea.toNmeaString()
                }
                is NmeaValue.GNS -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.mode == "N") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    value.latitude = nmeaCoordinate(FakeLoc.latitude)
                    value.longitude = nmeaCoordinate(FakeLoc.longitude)

                    return nmea.toNmeaString()
                }
                is NmeaValue.GSA -> {
                    return null
                }
                is NmeaValue.GSV -> {
                    return null
                }
                is NmeaValue.RMC -> {
                    if (value.latitude == null || value.longitude == null) {
                        return null
                    }

                    if (value.status == "V") {
                        return null
                    }

                    val latitudeHemisphere = if (FakeLoc.latitude >= 0) "N" else "S"
                    val longitudeHemisphere = if (FakeLoc.longitude >= 0) "E" else "W"

                    value.latitudeHemisphere = latitudeHemisphere
                    value.longitudeHemisphere = longitudeHemisphere

                    value.latitude = nmeaCoordinate(FakeLoc.latitude)
                    value.longitude = nmeaCoordinate(FakeLoc.longitude)

                    val speedMps = FakeLoc.reportedSpeed().toDouble()
                    value.speedKnots = speedMps * 1.943844
                    var course = FakeLoc.bearing % 360.0
                    if (course < 0.0) course += 360.0
                    value.trackAngle = course

                    return nmea.toNmeaString()
                }
                is NmeaValue.VTG -> {
                    if (value.mode == "N") {
                        return null
                    }
                    val speedMps = FakeLoc.reportedSpeed().toDouble()
                    var course = FakeLoc.bearing % 360.0
                    if (course < 0.0) course += 360.0
                    value.trueTrack = course
                    value.groundSpeedKnots = speedMps * 1.943844
                    value.groundSpeedKph = speedMps * 3.6
                    return nmea.toNmeaString()
                }
            }
        }.onFailure {
            Logger.error("NMEA parse failed: ${it.message}, source = $nmeaStr")
            return null
        }
        return null
    }
}