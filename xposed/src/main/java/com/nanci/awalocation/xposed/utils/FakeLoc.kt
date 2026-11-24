package com.nanci.awalocation.xposed.utils

import android.location.Location
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

object FakeLoc {
    private const val WGS84_A = 6378137.0
    private const val WGS84_E2 = 0.00669437999014
    private const val ACTIVE_MOTION_TIMEOUT_NS = 2_500_000_000L

    private val jitterLock = Any()
    private var driftNorthMeters = 0.0
    private var driftEastMeters = 0.0
    private var lastJitterUpdateNs = 0L
    private var speedDriftRatio = 0.0
    private var lastSpeedUpdateNs = 0L
    @Volatile private var lastMoveNanoTime = 0L

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

    @Volatile var speedAmplitude = 1.0

    @Volatile var hasBearings = false

    @Volatile var bearing = 0.0
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

    fun markMoved(nowNs: Long = System.nanoTime()) {
        lastMoveNanoTime = if (nowNs == 0L) 1L else nowNs
    }

    fun resetMotionAndJitter() {
        lastMoveNanoTime = 0L
        synchronized(jitterLock) {
            driftNorthMeters = 0.0
            driftEastMeters = 0.0
            lastJitterUpdateNs = 0L
            speedDriftRatio = 0.0
            lastSpeedUpdateNs = 0L
        }
    }

    fun isActivelyMoving(nowNs: Long = System.nanoTime()): Boolean {
        val last = lastMoveNanoTime
        if (last == 0L) return false
        val elapsed = nowNs - last
        return elapsed in 0L..ACTIVE_MOTION_TIMEOUT_NS
    }

    /**
     * 返回注入到 Location / NMEA 的瞬时速度（m/s）：
     * - 静止选点或路线暂停/结束时返回 0，避免坐标不动却上报正速度触发异常检测；
     * - 路线或摇杆移动中使用时间相关低频微幅呼吸（均值严格等于 [speed]），避免白噪声速度乱跳。
     */
    fun reportedSpeed(nowNs: Long = System.nanoTime()): Float {
        val base = speed
        if (base <= 0.0 || !isActivelyMoving(nowNs)) return 0.0f
        if (speedAmplitude <= 0.0) return base.toFloat()

        synchronized(jitterLock) {
            val maxRatio = (0.025 * speedAmplitude.coerceIn(0.0, 2.0)).coerceAtMost(0.04)
            if (lastSpeedUpdateNs == 0L) {
                lastSpeedUpdateNs = nowNs
            } else {
                val rawDt = (nowNs - lastSpeedUpdateNs) / 1_000_000_000.0
                if (rawDt >= 0.05 || rawDt < 0.0) {
                    val dt = rawDt.coerceIn(0.0, 1.0)
                    lastSpeedUpdateNs = nowNs
                    val decay = (dt / 8.0).coerceAtMost(0.5)
                    val step = Random.nextDouble(-1.0, 1.0) * maxRatio * 0.25 * dt.coerceAtLeast(0.05)
                    speedDriftRatio = (speedDriftRatio * (1.0 - decay) + step).coerceIn(-maxRatio, maxRatio)
                }
            }
            return (base * (1.0 + speedDriftRatio)).toFloat().coerceAtLeast(0.01f)
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

    private fun offsetByMeters(lat: Double, lon: Double, northMeters: Double, eastMeters: Double): Pair<Double, Double> {
        if (northMeters == 0.0 && eastMeters == 0.0) return Pair(lat, lon)
        val clampedLat = lat.coerceIn(-89.999999, 89.999999)
        val latRad = Math.toRadians(clampedLat)
        val sinLat = sin(latRad)
        val cosLat = cos(latRad).coerceAtLeast(1e-7)
        val w = sqrt(1.0 - WGS84_E2 * sinLat * sinLat)
        val meridionalRadius = WGS84_A * (1.0 - WGS84_E2) / (w * w * w)
        val primeVerticalRadius = WGS84_A / w

        val newLat = (lat + Math.toDegrees(northMeters / meridionalRadius)).coerceIn(-90.0, 90.0)
        var newLon = lon + Math.toDegrees(eastMeters / (primeVerticalRadius * cosLat))
        if (newLon > 180.0) newLon -= 360.0 else if (newLon < -180.0) newLon += 360.0
        return Pair(newLat, newLon)
    }

    /**
     * 时间相关平滑微漂移（Ornstein-Uhlenbeck 低通随机游走）：
     * - 替代原先每帧 360° 独立白噪声跳变，彻底消除 10Hz 高频上报下的「之」字锯齿折线里程膨胀（配速越跑越快）；
     * - 同一时刻（<40ms）多路监听器/Hook 共享同一偏移，保证 GPS / Fused / getLastLocation 坐标一致。
     */
    fun jitterLocation(
        lat: Double = latitude,
        lon: Double = longitude,
        n: Double = -1.0,
        angle: Double = bearing,
        nowNs: Long = System.nanoTime(),
    ): Pair<Double, Double> {
        if (n >= 0.0) {
            if (n <= 0.0) return Pair(lat, lon)
            val jitterAngle = Math.toRadians((angle + Random.nextDouble(-180.0, 180.0) + 360.0) % 360.0)
            return offsetByMeters(lat, lon, n * cos(jitterAngle), n * sin(jitterAngle))
        }

        val maxAccuracy = accuracy.toDouble()
        if (maxAccuracy <= 0.0) {
            return Pair(lat, lon)
        }

        val maxRadius = (maxAccuracy * 0.10).coerceAtMost(0.45)
        val moving = isActivelyMoving(nowNs)
        val currentSpeed = speed

        val (northOffset, eastOffset) = synchronized(jitterLock) {
            if (lastJitterUpdateNs == 0L) {
                lastJitterUpdateNs = nowNs
                0.0 to 0.0
            } else {
                val rawDt = (nowNs - lastJitterUpdateNs) / 1_000_000_000.0
                if (rawDt in 0.0..0.04) {
                    driftNorthMeters to driftEastMeters
                } else {
                    val dt = rawDt.coerceIn(0.0, 1.0)
                    lastJitterUpdateNs = nowNs
                    // 将漂移速率严格限制在前进速度的 1.2% 以内（最高 3.5cm/s），确保折线累计误差 < 0.05%
                    val maxDriftSpeed = if (moving && currentSpeed > 0.0) {
                        (currentSpeed * 0.012).coerceIn(0.004, 0.035)
                    } else {
                        0.012
                    }
                    val stepBudget = maxDriftSpeed * dt
                    val decay = (dt / 15.0).coerceAtMost(0.4)
                    val theta = Random.nextDouble(0.0, 2.0 * PI)
                    val randomStep = Random.nextDouble(0.3, 1.0) * stepBudget

                    var candN = driftNorthMeters * (1.0 - decay) + randomStep * cos(theta)
                    var candE = driftEastMeters * (1.0 - decay) + randomStep * sin(theta)

                    val deltaN = candN - driftNorthMeters
                    val deltaE = candE - driftEastMeters
                    val deltaNorm = hypot(deltaN, deltaE)
                    val maxAllowedDelta = stepBudget * 1.2
                    if (deltaNorm > maxAllowedDelta && deltaNorm > 0.0) {
                        val scale = maxAllowedDelta / deltaNorm
                        candN = driftNorthMeters + deltaN * scale
                        candE = driftEastMeters + deltaE * scale
                    }

                    val norm = hypot(candN, candE)
                    if (norm > maxRadius && norm > 0.0) {
                        val scale = maxRadius / norm
                        candN *= scale
                        candE *= scale
                    }

                    driftNorthMeters = candN
                    driftEastMeters = candE
                    driftNorthMeters to driftEastMeters
                }
            }
        }

        return offsetByMeters(lat, lon, northOffset, eastOffset)
    }

    fun moveLocation(lat: Double = latitude, lon: Double = longitude, n: Double, angle: Double = bearing): Pair<Double, Double> {
        val angleRad = Math.toRadians(angle)
        return offsetByMeters(lat, lon, n * cos(angleRad), n * sin(angleRad))
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
