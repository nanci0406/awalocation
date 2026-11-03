package com.nanci.awalocation.ext

import android.content.Context
import androidx.core.content.edit
import com.alibaba.fastjson2.JSON
import com.amap.api.maps.AMap
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.mock.HistoricalLocation
import com.nanci.awalocation.ui.mock.HistoricalRoute
import com.nanci.awalocation.xposed.utils.FakeLoc

val Context.sharedPrefs
    get() = getSharedPreferences(MockServiceHelper.PROVIDER_NAME, Context.MODE_PRIVATE)!!

var Context.selectLocation: HistoricalLocation?
    get() {
        return sharedPrefs.getString("selectedLocation", null)?.let {
            HistoricalLocation.fromString(it)
        }
    }
    set(value) = sharedPrefs.edit(commit = true) {
        putString("selectedLocation", value?.toString())
    }

var Context.selectRoute: HistoricalRoute?
    get() {
        return sharedPrefs.getString("selectedRoute", null)?.let {
            try {
                JSON.parseObject(it, HistoricalRoute::class.java)
            } catch (e: Exception) {
                sharedPrefs.edit {
                    putString("selectedRoute", "")
                }
                null
            }
        }
    }
    set(value) = sharedPrefs.edit(commit = true) {
        putString("selectedRoute", JSON.toJSONString(value))
    }

val Context.historicalLocations: List<HistoricalLocation>
    get() {
        return sharedPrefs.getStringSet("locations", emptySet())?.map {
            HistoricalLocation.fromString(it)
        } ?: emptyList()
    }

var Context.rawHistoricalLocations: Set<String>
    get() {
        return sharedPrefs.getStringSet("locations", emptySet()) ?: emptySet()
    }
    set(value) {
        sharedPrefs.edit(commit = true) {
            putStringSet("locations", value)
        }
    }

var Context.jsonHistoricalRoutes: String
    get() {
        return sharedPrefs.getString("routes", null) ?: ""
    }
    set(value) {
        sharedPrefs.edit(commit = true) {
            putString("routes", value)
        }
    }

fun Context.getHistoricalRoutesList(): MutableList<HistoricalRoute> {
    val json = jsonHistoricalRoutes
    if (json.isBlank()) {
        val defaultRoute = HistoricalRoute(
            "默认路线",
            listOf(Pair(39.908822, 116.397465), Pair(39.907951, 116.397500))
        )
        val list = mutableListOf(defaultRoute)
        saveHistoricalRoutesList(list)
        return list
    }
    return try {
        val array = JSON.parseArray(json)
        val list = mutableListOf<HistoricalRoute>()
        for (i in 0 until array.size) {
            val item = array.getJSONObject(i) ?: continue
            val name = item.getString("name") ?: continue
            val routeArray = item.getJSONArray("route") ?: continue
            val points = mutableListOf<Pair<Double, Double>>()
            for (j in 0 until routeArray.size) {
                val ptObj = routeArray.getJSONObject(j) ?: continue
                val lat = ptObj.getDouble("first") ?: ptObj.getDouble("lat") ?: 0.0
                val lon = ptObj.getDouble("second") ?: ptObj.getDouble("lon") ?: 0.0
                points.add(Pair(lat, lon))
            }
            if (points.isNotEmpty()) {
                list.add(HistoricalRoute(name, points))
            }
        }
        if (list.isEmpty()) {
            val defaultRoute = HistoricalRoute(
                "默认路线",
                listOf(Pair(39.908822, 116.397465), Pair(39.907951, 116.397500))
            )
            list.add(defaultRoute)
            saveHistoricalRoutesList(list)
        }
        list
    } catch (e: Exception) {
        mutableListOf()
    }
}

fun Context.saveHistoricalRoutesList(routes: List<HistoricalRoute>) {
    val json = JSON.toJSONString(routes)
    sharedPrefs.edit(commit = true) {
        putString("routes", json)
    }
}

var Context.reportDuration: Int
    get() = sharedPrefs.getInt("reportDuration", 100).coerceAtLeast(20)
    set(value) = sharedPrefs.edit {
        putInt("reportDuration", value.coerceAtLeast(20))
    }

var Context.minSatelliteCount: Int
    get() = sharedPrefs.getInt("minSatelliteCount", 12)
    set(value) = sharedPrefs.edit {
        putInt("minSatelliteCount", value)
    }

var Context.mapType: Int
    get() = sharedPrefs.getInt("mapType", AMap.MAP_TYPE_NORMAL)
    set(value) = sharedPrefs.edit {
        putInt("mapType", value)
    }

var Context.rockerCoords: Pair<Int, Int>
    get() {
        val x = sharedPrefs.getInt("rocker_x", 0)
        val y = sharedPrefs.getInt("rocker_y", 0)
        return Pair(x, y)
    }
    set(value) = sharedPrefs.edit {
        putInt("rocker_x", value.first)
        putInt("rocker_y", value.second)
    }

var Context.speed: Double
    get() = sharedPrefs.getFloat("speed", FakeLoc.speed.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("speed", value.toFloat())
    }

var Context.altitude: Double
    get() = sharedPrefs.getFloat("altitude", FakeLoc.altitude.toFloat()).toDouble()
    set(value) = sharedPrefs.edit {
        putFloat("altitude", value.toFloat())
    }

var Context.accuracy: Float
    get() = sharedPrefs.getFloat("accuracy", FakeLoc.accuracy)
    set(value) = sharedPrefs.edit {
        putFloat("accuracy", value)
    }

var Context.needOpenSELinux: Boolean
    get() = sharedPrefs.getBoolean("needOpenSELinux", false)
    set(value) = sharedPrefs.edit {
        putBoolean("needOpenSELinux", value)
    }

var Context.needDowngradeToCdma: Boolean
    get() = sharedPrefs.getBoolean("needDowngradeToCdma", FakeLoc.needDowngradeToCdma)
    set(value) = sharedPrefs.edit {
        putBoolean("needDowngradeToCdma", value)
    }

/**
 * 是否模拟步频传感器（计步器/步检器）
 */
var Context.enableStepMock: Boolean
    get() = sharedPrefs.getBoolean("enableStepMock", false)
    set(value) = sharedPrefs.edit {
        putBoolean("enableStepMock", value)
    }

/**
 * 模拟步频（步/分钟），0 表示根据模拟速度自动推算
 */
var Context.stepCadence: Float
    get() = sharedPrefs.getFloat("stepCadence", 0f)
    set(value) = sharedPrefs.edit {
        putFloat("stepCadence", value)
    }

//var Context.updateInterval: Long
//    get() = sharedPrefs.getLong("updateInterval", FakeLoc.updateInterval)
//
//    set(value) = sharedPrefs.edit {
//        putLong("updateInterval", value)
//    }
//
//var Context.hideMock: Boolean
//    get() = sharedPrefs.getBoolean("hideMock", FakeLoc.hideMock)
//
//    set(value) = sharedPrefs.edit {
//        putBoolean("hideMock", value)
//    }

var Context.debug: Boolean
    get() = sharedPrefs.getBoolean("debug", FakeLoc.enableDebugLog)
    set(value) = sharedPrefs.edit {
        putBoolean("debug", value)
    }

var Context.disableGetCurrentLocation: Boolean
    get() = sharedPrefs.getBoolean("disableGetCurrentLocation", FakeLoc.disableGetCurrentLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("disableGetCurrentLocation", value)
    }

var Context.disableRegisterLocationListener: Boolean
    get() = sharedPrefs.getBoolean(
        "disableRegitserLocationListener",
        FakeLoc.disableRegisterLocationListener
    )
    set(value) = sharedPrefs.edit {
        putBoolean("disableRegitserLocationListener", value)
    }

var Context.disableFusedProvider: Boolean
    get() = sharedPrefs.getBoolean("disableFusedProvider", FakeLoc.disableFusedLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("disableFusedProvider", value)
        FakeLoc.disableFusedLocation = value
    }

/**
 * 是否允许地理围栏请求
 */
var Context.enableRequestGeofence: Boolean
    get() = sharedPrefs.getBoolean("enableRequestGeofence", !FakeLoc.disableRequestGeofence)
    set(value) = sharedPrefs.edit {
        putBoolean("enableRequestGeofence", value)
        FakeLoc.disableRequestGeofence = !value
    }

/**
 * 是否允许位置获取
 */
var Context.enableGetFromLocation: Boolean
    get() = sharedPrefs.getBoolean("enableGetFromLocation", !FakeLoc.disableGetFromLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("enableGetFromLocation", value)
        FakeLoc.disableGetFromLocation = !value
    }

/**
 * 是否允许AGPS模块
 */
var Context.enableAGPS: Boolean
    get() = sharedPrefs.getBoolean("enableAGPS", FakeLoc.enableAGPS)
    set(value) = sharedPrefs.edit {
        putBoolean("enableAGPS", value)
        FakeLoc.enableAGPS = value
    }

/**
 * 是否允许NMEA模块
 */
var Context.enableNMEA: Boolean
    get() = sharedPrefs.getBoolean("enableNMEA", FakeLoc.enableNMEA)
    set(value) = sharedPrefs.edit {
        putBoolean("enableNMEA", value)
        FakeLoc.enableNMEA = value
    }

var Context.disableWifiScan: Boolean
    get() = sharedPrefs.getBoolean("disableWifiScan", false)
    set(value) = sharedPrefs.edit {
        putBoolean("disableWifiScan", value)
        FakeLoc.enableMockWifi = value
    }

var Context.loopBroadcastlocation: Boolean
    get() = sharedPrefs.getBoolean("loopBroadcastLocation", FakeLoc.loopBroadcastLocation)
    set(value) = sharedPrefs.edit {
        putBoolean("loopBroadcastLocation", value)
        FakeLoc.loopBroadcastLocation = value
    }

var Context.routeLoop: Boolean
    get() = sharedPrefs.getBoolean("routeLoop", false)
    set(value) = sharedPrefs.edit {
        putBoolean("routeLoop", value)
    }


