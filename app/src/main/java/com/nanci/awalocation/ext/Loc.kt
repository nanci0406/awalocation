package com.nanci.awalocation.ext

import android.location.Location
import com.amap.api.maps.model.LatLng
import com.amap.api.services.core.LatLonPoint
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

val LatLng.wgs84: Pair<Double, Double>
    get() = Loc4j.gcj2wgs(latitude, longitude)

val LatLonPoint.wgs84: Pair<Double, Double>
    get() = Loc4j.gcj2wgs(latitude, longitude)

/**
 * Location callbacks from the AMap map location layer use GCJ-02 as well.
 */
val Location.wgs84: Pair<Double, Double>
    get() = Loc4j.gcj2wgs(latitude, longitude)

val Pair<Double, Double>.gcj02: LatLng
    get() = Loc4j.wgs2gcj(first, second).let { LatLng(it.first, it.second) }

object Loc4j {
    private const val PI = 3.1415926535897932384626
    private const val EARTH_AXIS = 6378245.0
    private const val EE = 0.00669342162296594323

    fun gcj2wgs(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) {
            return lat to lon
        }

        // GCJ-02 has no closed-form inverse; iterate until the forward
        // transform reproduces the input. Five rounds are well within
        // sub-meter precision for the UI use cases here.
        var wgsLat = lat
        var wgsLon = lon
        repeat(5) {
            val (gcjLat, gcjLon) = wgs2gcj(wgsLat, wgsLon)
            wgsLat += lat - gcjLat
            wgsLon += lon - gcjLon
        }
        return wgsLat to wgsLon
    }

    fun wgs2gcj(lat: Double, lon: Double): Pair<Double, Double> {
        if (outOfChina(lat, lon)) {
            return lat to lon
        }

        var dLat = transformLat(lon - 105.0, lat - 35.0)
        var dLon = transformLon(lon - 105.0, lat - 35.0)

        val radLat = lat / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = sqrt(magic)
        dLat = (dLat * 180.0) / ((EARTH_AXIS * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLon = (dLon * 180.0) / (EARTH_AXIS / sqrtMagic * cos(radLat) * PI)

        return lat + dLat to lon + dLon
    }

    private fun outOfChina(lat: Double, lon: Double): Boolean {
        return lon < 72.004 || lon > 137.8347 || lat < 0.8293 || lat > 55.8271
    }

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320.0 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLon(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }
}
