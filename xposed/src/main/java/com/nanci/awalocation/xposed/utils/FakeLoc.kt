package com.nanci.awalocation.xposed.utils

import android.location.Location
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object FakeLoc {
    /**
     * 是否允许打印日志
     */
    var enableLog = true

    /**
     * 是否允许打印调试日志
     */
    var enableDebugLog = true

    /**
     * 模拟定位服务开关
     */
    @Volatile
    var enable = false

    /**
     * 模拟Gnss卫星数据开关
     */
    @Volatile
    var enableMockGnss = false

    /**
     * 模拟WLAN数据
     */
    @Volatile
    var enableMockWifi = false

    /**
     * 模拟步频传感器（计步器/步检器）
     */
    @Volatile
    var enableMockStep = false

    /**
     * 模拟步频（步/分钟），0 表示根据模拟速度自动推算
     */
    @Volatile var stepCadence = 0f

    /**
     * 是否禁用GetCurrentLocation方法（在部分系统不禁用可能导致hook失效）
     */
    var disableGetCurrentLocation = true

    /**
     * 是否禁用RegisterLocationListener方法
     */
    var disableRegisterLocationListener = false

    /**
     * 如果TelephonyHook失效，可能需要打开此开关
     */
    var disableFusedLocation = true
    var disableNetworkLocation = true

    var disableRequestGeofence = false
    var disableGetFromLocation = false

    /**
     * 是否允许AGPS模块（当前没什么鸟用）
     */
    var enableAGPS = false

    /**
     * 是否允许NMEA模块
     */
    var enableNMEA = false

    /**
     * 是否隐藏模拟位置
     */
    var hideMock = true

    /**
     * may cause system to crash
     */
    var hookWifi = true

    /**
     * 将网络定位降级为Cdma
     */
    var needDowngradeToCdma = true
    var isSystemServerProcess = false
    var moduleApkPath: String? = null

    /**
     * 模拟最小卫星数量
     */
    var minSatellites = 12

    /**
     * 反定位复原加强（启用后将导致部分应用在关闭Awalocation后需要重新启动才能重新获取定位）
     */
    var loopBroadcastLocation = false

    /**
     * 上一次的位置
     */
    @Volatile var lastLocation: Location? = null
    @Volatile var latitude = 0.0
    @Volatile var longitude = 0.0
    @Volatile var altitude = 80.0

    @Volatile var speed = 5.0 / 3.6

    var speedAmplitude = 1.0

    @Volatile var hasBearings = false

    var bearing = 0.0
        get() {
            if (hasBearings) {
                return field
            } else {
                if (field >= 360.0) {
                    field -= 360.0
                }
                field += 0.5
                return field
            }
        }

    // 默认模拟开阔室外良好 GPS 精度（~4.5m），避免 25m 导致跑步/地图软件判定为最弱 GPS 信号
    @Volatile var accuracy = 4.5f
        set(value) {
            field = if (value < 0) {
                -value
            } else {
                value
            }
        }

    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val radius = 6371000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).pow(2) + cos(phi1) * cos(phi2) * sin(deltaLambda / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return radius * c
    }

    fun jitterLocation(lat: Double = latitude, lon: Double = longitude, n: Double = -1.0, angle: Double = bearing): Pair<Double, Double> {
        val maxJitter = accuracy.toDouble()
        val distance = when {
            n >= 0.0 -> n
            // 连续轨迹下只施加温和微抖动（<= 0.8m），避免按 accuracy 全半径乱跳把起点移动淹没在噪声圈里
            maxJitter > 0.0 -> Random.nextDouble(0.0, (maxJitter * 0.12).coerceAtMost(0.8))
            else -> 0.0
        }

        // accuracy 为 0 时表示不添加抖动，避免 Random.nextDouble(0.0, 0.0) 抛异常。
        if (distance <= 0.0) {
            return Pair(lat, lon)
        }

        val earthRadius = 6371000.0
        val radiusInDegrees = distance / earthRadius * (180 / PI)

        val jitterAngle = (angle + Random.nextDouble(-180.0, 180.0) + 360.0) % 360.0

        val newLat = lat + radiusInDegrees * cos(Math.toRadians(jitterAngle))
        val newLon = lon + radiusInDegrees * sin(Math.toRadians(jitterAngle)) / cos(Math.toRadians(lat))

        return Pair(newLat, newLon)
    }

    fun moveLocation(lat: Double = latitude, lon: Double = longitude, n: Double, angle: Double = bearing): Pair<Double, Double> {
        val earthRadius = 6371000.0
        val radiusInDegrees = n / earthRadius * (180 / PI)
        val newLat = lat + radiusInDegrees * cos(Math.toRadians(angle))
        val newLon = lon + radiusInDegrees * sin(Math.toRadians(angle)) / cos(Math.toRadians(lat))
        return Pair(newLat, newLon)
    }



    fun calculateBearing(latA: Double, lonA: Double, latB: Double, lonB: Double): Double {
        val lat1 = Math.toRadians(latA)
        val lon1 = Math.toRadians(lonA)
        val lat2 = Math.toRadians(latB)
        val lon2 = Math.toRadians(lonB)

        val deltaLon = lon2 - lon1

        val y = sin(deltaLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(deltaLon)

        var bearing = Math.toDegrees(atan2(y, x))
        bearing = (bearing + 360) % 360  // 标准化到0-360度

        return bearing
    }
}
