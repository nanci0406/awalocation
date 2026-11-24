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
import kotlin.math.abs
import kotlin.random.Random

abstract class BaseLocationHook: BaseDivineService() {
    companion object {
        private val injectedLocations: MutableSet<Location> =
            Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    }

    fun injectLocation(originLocation: Location, realLocation: Boolean = true): Location {
        if (realLocation) {
            if (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    originLocation.provider == LocationManager.GPS_PROVIDER && originLocation.isComplete
                } else {
                    originLocation.provider == LocationManager.GPS_PROVIDER
                }
            ) {
                FakeLoc.lastLocation = originLocation
            }
        } else {
            originLocation.altitude = FakeLoc.altitude
        }

        if (!FakeLoc.enable)
            return originLocation

        val alreadyInjected = injectedLocations.contains(originLocation) ||
            (originLocation.extras?.containsKey("latlon") == true &&
                originLocation.extras?.getDouble("latlon", Double.NaN) == originLocation.latitude + originLocation.longitude)
        if (alreadyInjected) {
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
        val jitterLat = FakeLoc.jitterLocation()
        location.latitude = jitterLat.first
        location.longitude = jitterLat.second
        location.altitude = FakeLoc.altitude
        if (FakeLoc.speed > 0.0) {
            val maxAmp = (FakeLoc.speed * 0.05).coerceAtMost(FakeLoc.speedAmplitude.coerceAtLeast(0.0))
            val speedAmp = if (maxAmp > 0.0) Random.nextDouble(-maxAmp, maxAmp) else 0.0
            location.speed = (FakeLoc.speed + speedAmp).toFloat().coerceAtLeast(0.01f)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.speedAccuracyMetersPerSecond = 0.25f
            }
        } else {
            location.speed = 0.0f
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                location.speedAccuracyMetersPerSecond = 0.0f
            }
        }

        if (location.altitude == 0.0) {
            location.altitude = 80.0
        }

        location.time = if (originLocation.time > 0L) originLocation.time else System.currentTimeMillis()

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

        location.elapsedRealtimeNanos = if (originLocation.elapsedRealtimeNanos > 0L) {
            originLocation.elapsedRealtimeNanos
        } else {
            SystemClock.elapsedRealtimeNanos()
        }
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

                    return nmea.toNmeaString()
                }
                is NmeaValue.VTG -> {
                    return null
                }
            }
        }.onFailure {
            Logger.error("NMEA parse failed: ${it.message}, source = $nmeaStr")
            return null
        }
        return null
    }
}