@file:Suppress("KotlinConstantConditions")
@file:OptIn(ExperimentalUuidApi::class)

package com.nanci.awalocation.xposed.hooks

import android.location.GnssStatus
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.DeadObjectException
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.nanci.awalocation.xposed.BaseLocationHook
import com.nanci.awalocation.xposed.RemoteCommandHandler
import com.nanci.awalocation.xposed.hooks.gnss.GnssHook
import com.nanci.awalocation.xposed.hooks.miui.MiuiBlurLocationProviderHook
import com.nanci.awalocation.xposed.hooks.miui.MiuiLocationManagerHook
import com.nanci.awalocation.xposed.hooks.telephony.miui.MiuiTelephonyManagerHook
import com.nanci.awalocation.xposed.hooks.nmea.LocationNMEAHook
import com.nanci.awalocation.xposed.hooks.provider.LocationProviderManagerHook
import com.nanci.awalocation.xposed.utils.FakeLoc
import com.nanci.awalocation.xposed.utils.BinderUtils
import com.nanci.awalocation.xposed.utils.Logger
import com.nanci.awalocation.xposed.utils.afterHook
import com.nanci.awalocation.xposed.utils.beforeHook
import com.nanci.awalocation.xposed.utils.hookAllMethods
import com.nanci.awalocation.xposed.utils.onceHookAllMethod
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random
import kotlin.uuid.ExperimentalUuidApi

private const val MAX_SATELLITES = 36 // 模拟可见卫星数上限

// 载噪比范围（模拟开阔室外良好接收环境，避免 20 dB-Hz 附近被判定为弱信号）
private const val GEO_MIN_CN0 = 38.0f
private const val GEO_MAX_CN0 = 46.0f
private const val IGSO_MIN_CN0 = 35.0f
private const val IGSO_MAX_CN0 = 44.0f
private const val MEO_MIN_CN0 = 32.0f
private const val MEO_MAX_CN0 = 43.0f

// 频率 (Hz)
private const val GPS_L1_FREQ = 1575420000.0f
private const val GLO_G1_FREQ = 1602000000.0f
private const val BDS_B1I_FREQ = 1561098000.0f

object GnssFlags {
    // 基本标志位
    const val SVID_FLAGS_NONE = 0
    const val SVID_FLAGS_HAS_EPHEMERIS_DATA = (1 shl 0)
    const val SVID_FLAGS_HAS_ALMANAC_DATA = (1 shl 1)
    const val SVID_FLAGS_USED_IN_FIX = (1 shl 2)
    const val SVID_FLAGS_HAS_CARRIER_FREQUENCY = (1 shl 3)
    const val SVID_FLAGS_HAS_BASEBAND_CN0 = (1 shl 4)

    // 位移宽度
    const val SVID_SHIFT_WIDTH = 12
    const val CONSTELLATION_TYPE_SHIFT_WIDTH = 8
    const val CONSTELLATION_TYPE_MASK = 0xf

    // 星座类型（与 Android GnssStatus.CONSTELLATION_ 常量对应）
    const val CONSTELLATION_GPS = 1
    const val CONSTELLATION_SBAS = 2
    const val CONSTELLATION_GLONASS = 3
    const val CONSTELLATION_QZSS = 4
    const val CONSTELLATION_BEIDOU = 5
    const val CONSTELLATION_GALILEO = 6
    const val CONSTELLATION_IRNSS = 7
}

sealed class OrbitType(val minCn0: Float, val maxCn0: Float, val elevationRange: ClosedRange<Float>) {
    object GEO : OrbitType(GEO_MIN_CN0, GEO_MAX_CN0, 35f..55f)
    object IGSO : OrbitType(IGSO_MIN_CN0, IGSO_MAX_CN0, 25f..68f)
    object MEO : OrbitType(MEO_MIN_CN0, MEO_MAX_CN0, 18f..82f)
}

data class MockSatellite(
    val prn: Int,
    val constellation: Int,
    val type: OrbitType,
    val defaultFreq: Float
)

private val satelliteList = listOf(
    // BeiDou (GEO, IGSO, MEO)
    MockSatellite(1, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.GEO, BDS_B1I_FREQ),
    MockSatellite(2, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.GEO, BDS_B1I_FREQ),
    MockSatellite(3, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.GEO, BDS_B1I_FREQ),
    MockSatellite(4, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.GEO, BDS_B1I_FREQ),
    MockSatellite(5, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.GEO, BDS_B1I_FREQ),
    MockSatellite(6, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.IGSO, BDS_B1I_FREQ),
    MockSatellite(7, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.IGSO, BDS_B1I_FREQ),
    MockSatellite(8, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.IGSO, BDS_B1I_FREQ),
    MockSatellite(9, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.IGSO, BDS_B1I_FREQ),
    MockSatellite(10, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.IGSO, BDS_B1I_FREQ),
    MockSatellite(11, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(12, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(13, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.IGSO, BDS_B1I_FREQ),
    MockSatellite(14, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(16, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.IGSO, BDS_B1I_FREQ),
    MockSatellite(19, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(20, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(21, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(22, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(23, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(24, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(25, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(26, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(27, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(28, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(29, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(30, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(32, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(33, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(34, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),
    MockSatellite(35, GnssFlags.CONSTELLATION_BEIDOU, OrbitType.MEO, BDS_B1I_FREQ),

    // GPS (MEO)
    MockSatellite(1, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(2, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(3, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(5, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(6, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(7, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(8, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(9, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(10, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(11, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(12, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(13, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(14, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(15, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(17, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(18, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(19, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(20, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(21, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(22, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(24, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(25, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(27, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(28, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(30, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(31, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),
    MockSatellite(32, GnssFlags.CONSTELLATION_GPS, OrbitType.MEO, GPS_L1_FREQ),

    // GLONASS (MEO)
    MockSatellite(1, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(2, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(3, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(4, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(5, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(7, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(8, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(11, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(12, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(14, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(17, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(19, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(20, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(22, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
    MockSatellite(24, GnssFlags.CONSTELLATION_GLONASS, OrbitType.MEO, GLO_G1_FREQ),
)

data class MockGnssData(
    val svCount: Int,
    val svidWithFlags: IntArray,
    val cn0s: FloatArray,
    val elevations: FloatArray,
    val azimuths: FloatArray,
    val carrierFreqs: FloatArray
)

internal object LocationServiceHook: BaseLocationHook() {
    val locationListeners = LinkedBlockingQueue<Pair<String, IInterface>>()
    val gnssStatusListeners = CopyOnWriteArrayList<IInterface>()

    private val firstFixNotifiedBinders = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<IBinder, Boolean>()
    )
    private val isGnssDaemonRunning = AtomicBoolean(false)
    private val isLocationHeartbeatRunning = AtomicBoolean(false)

    @Volatile var lastInUseSatelliteCount: Int = 20
    @Volatile private var lastLocationBroadcastElapsedMs: Long = 0L

    private data class ActiveSatelliteState(
        val sat: MockSatellite,
        var cn0: Float,
        var elevation: Float,
        var azimuth: Float,
        val usedInFix: Boolean
    )

    private val constellationLock = Any()
    private var activeConstellation: MutableList<ActiveSatelliteState> = mutableListOf()
    private var lastConstellationRefreshMs: Long = 0L
    private var lastConstellationMinSats: Int = -1

    fun startGnssMockDaemon() {
        if (!isGnssDaemonRunning.compareAndSet(false, true)) return
        kotlin.concurrent.thread(name = "GnssMockDaemon", isDaemon = true) {
            try {
                while (FakeLoc.enableMockGnss) {
                    try {
                        Thread.sleep(1000L)
                        if (FakeLoc.enableMockGnss) {
                            callOnGnssStatusChanged()
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Throwable) {
                        Logger.error("GnssMockDaemon error", e)
                    }
                }
            } finally {
                isGnssDaemonRunning.set(false)
            }
        }
    }

    /**
     * 1Hz 位置心跳：在 FakeLoc.enable 开启且近期没有主动 move() 时，
     * 持续向已注册监听器推送位置，解决室内无硬件 GPS 修复时应用长时间不动/搜星中的问题。
     */
    fun startLocationHeartbeatDaemon() {
        if (!isLocationHeartbeatRunning.compareAndSet(false, true)) return
        kotlin.concurrent.thread(name = "LocationHeartbeatDaemon", isDaemon = true) {
            try {
                while (FakeLoc.enable) {
                    try {
                        Thread.sleep(1000L)
                        if (!FakeLoc.enable) break
                        if (FakeLoc.latitude == 0.0 && FakeLoc.longitude == 0.0) continue
                        if (locationListeners.isEmpty()) continue
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastLocationBroadcastElapsedMs >= 950L) {
                            callOnLocationChanged()
                        }
                    } catch (e: InterruptedException) {
                        break
                    } catch (e: Throwable) {
                        Logger.error("LocationHeartbeatDaemon error", e)
                    }
                }
            } finally {
                isLocationHeartbeatRunning.set(false)
            }
        }
    }

    private fun addGnssStatusListenerInner(listener: IInterface) {
        val binder = listener.asBinder()
        if (gnssStatusListeners.any { it.asBinder() == binder }) return

        val mDeathRecipient = object: IBinder.DeathRecipient {
            override fun binderDied() {}
            override fun binderDied(who: IBinder) {
                runCatching { who.unlinkToDeath(this, 0) }
                removeGnssStatusListenerByBinder(who)
            }
        }
        runCatching {
            binder.linkToDeath(mDeathRecipient, 0)
        }.onFailure {
            Logger.warn("linkToDeath failed for gnss listener", it)
        }
        gnssStatusListeners.add(listener)
    }

    private fun removeGnssStatusListenerByBinder(binder: IBinder) {
        gnssStatusListeners.removeIf { it.asBinder() == binder }
        firstFixNotifiedBinders.remove(binder)
    }

    private fun ensureAndStepConstellation(): Pair<MockGnssData, List<ActiveSatelliteState>> {
        synchronized(constellationLock) {
            val minSats = FakeLoc.minSatellites.coerceIn(16, MAX_SATELLITES)
            val now = SystemClock.elapsedRealtime()
            val needRebuild = activeConstellation.isEmpty() ||
                lastConstellationMinSats != minSats ||
                (now - lastConstellationRefreshMs) >= 60_000L

            if (needRebuild) {
                val targetCount = Random.nextInt(minSats, (minSats + 8).coerceAtMost(MAX_SATELLITES) + 1)
                // 均衡挑选 GPS + 北斗 + GLONASS，确保高仰角高信噪比
                val gps = satelliteList.filter { it.constellation == GnssFlags.CONSTELLATION_GPS }.shuffled()
                val bds = satelliteList.filter { it.constellation == GnssFlags.CONSTELLATION_BEIDOU }.shuffled()
                val glo = satelliteList.filter { it.constellation == GnssFlags.CONSTELLATION_GLONASS }.shuffled()
                val picked = ArrayList<MockSatellite>(targetCount)
                val gpsCount = (targetCount * 0.42f).toInt().coerceAtLeast(6)
                val bdsCount = (targetCount * 0.42f).toInt().coerceAtLeast(6)
                picked.addAll(gps.take(gpsCount))
                picked.addAll(bds.take(bdsCount))
                picked.addAll(glo.take((targetCount - picked.size).coerceAtLeast(0)))
                if (picked.size < targetCount) {
                    val remaining = satelliteList.filterNot { it in picked }.shuffled()
                    picked.addAll(remaining.take(targetCount - picked.size))
                }

                val inUseTarget = ((picked.size * 0.88f).toInt()).coerceAtLeast(14).coerceAtMost(picked.size)
                activeConstellation = picked.mapIndexed { idx, sat ->
                    val used = idx < inUseTarget
                    val baseCn0 = if (used) {
                        Random.nextFloat(sat.type.minCn0 + 2f, sat.type.maxCn0)
                    } else {
                        Random.nextFloat(sat.type.minCn0 - 4f, sat.type.minCn0 + 2f)
                    }
                    ActiveSatelliteState(
                        sat = sat,
                        cn0 = baseCn0.coerceIn(26f, 47f),
                        elevation = Random.nextFloat(sat.type.elevationRange.start, sat.type.elevationRange.endInclusive),
                        azimuth = Random.nextFloat(0f, 360f),
                        usedInFix = used
                    )
                }.toMutableList()
                lastConstellationRefreshMs = now
                lastConstellationMinSats = minSats
            } else {
                // 每秒平滑微调 CN0 / 仰角 / 方位角，模拟真实卫星轨迹呼吸感
                for (state in activeConstellation) {
                    val low = if (state.usedInFix) state.sat.type.minCn0 + 1f else state.sat.type.minCn0 - 5f
                    val high = state.sat.type.maxCn0
                    state.cn0 = (state.cn0 + Random.nextFloat(-0.7f, 0.7f)).coerceIn(low, high)
                    state.elevation = (state.elevation + Random.nextFloat(-0.25f, 0.25f))
                        .coerceIn(state.sat.type.elevationRange.start, state.sat.type.elevationRange.endInclusive)
                    state.azimuth = (state.azimuth + Random.nextFloat(-0.35f, 0.35f) + 360f) % 360f
                }
            }

            val snapshot = activeConstellation.map { it.copy() }
            val svCount = snapshot.size
            val svidWithFlags = IntArray(svCount)
            val cn0s = FloatArray(svCount)
            val elevations = FloatArray(svCount)
            val azimuths = FloatArray(svCount)
            val carrierFreqs = FloatArray(svCount)
            var inUseCount = 0

            snapshot.forEachIndexed { index, state ->
                var flags = GnssFlags.SVID_FLAGS_HAS_EPHEMERIS_DATA or
                    GnssFlags.SVID_FLAGS_HAS_ALMANAC_DATA or
                    GnssFlags.SVID_FLAGS_HAS_CARRIER_FREQUENCY or
                    GnssFlags.SVID_FLAGS_HAS_BASEBAND_CN0
                if (state.usedInFix) {
                    flags = flags or GnssFlags.SVID_FLAGS_USED_IN_FIX
                    inUseCount++
                }
                svidWithFlags[index] = (state.sat.prn shl GnssFlags.SVID_SHIFT_WIDTH) or
                    ((state.sat.constellation and GnssFlags.CONSTELLATION_TYPE_MASK) shl GnssFlags.CONSTELLATION_TYPE_SHIFT_WIDTH) or
                    flags
                cn0s[index] = state.cn0
                elevations[index] = state.elevation
                azimuths[index] = state.azimuth
                carrierFreqs[index] = state.sat.defaultFreq
            }

            lastInUseSatelliteCount = inUseCount
            return MockGnssData(
                svCount = svCount,
                svidWithFlags = svidWithFlags,
                cn0s = cn0s,
                elevations = elevations,
                azimuths = azimuths,
                carrierFreqs = carrierFreqs
            ) to snapshot
        }
    }

    private fun createMockGnssStatusFromStates(mockData: MockGnssData, states: List<ActiveSatelliteState>): GnssStatus? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val builder = GnssStatus.Builder()
            for (i in 0 until mockData.svCount) {
                val state = states[i]
                val cn0 = mockData.cn0s[i]
                val basebandCn0 = (cn0 - Random.nextFloat(1.5f, 3.0f)).coerceAtLeast(20f)
                builder.addSatellite(
                    state.sat.constellation,
                    state.sat.prn,
                    cn0,
                    mockData.elevations[i],
                    mockData.azimuths[i],
                    true,
                    true,
                    state.usedInFix,
                    true,
                    mockData.carrierFreqs[i],
                    true,
                    basebandCn0
                )
            }
            return builder.build()
        }

        return runCatching {
            val cGnssStatus = GnssStatus::class.java
            val mConstructor = cGnssStatus.declaredConstructors.firstOrNull {
                it.parameterTypes.size == 7
            }?.also { it.isAccessible = true }

            mConstructor?.newInstance(
                mockData.svCount,
                mockData.svidWithFlags,
                mockData.cn0s,
                mockData.elevations,
                mockData.azimuths,
                mockData.carrierFreqs,
                FloatArray(mockData.svCount) { (mockData.cn0s[it] - Random.nextFloat(1.5f, 3.0f)).coerceAtLeast(20f) }
            ) as? GnssStatus
        }.getOrNull()
    }

    fun buildCurrentMockGnssStatus(): GnssStatus? {
        return runCatching {
            val (mockData, states) = ensureAndStepConstellation()
            createMockGnssStatusFromStates(mockData, states)
        }.getOrNull()
    }

    private fun notifyFirstFixIfNeeded(listener: IInterface) {
        val binder = listener.asBinder()
        if (!firstFixNotifiedBinders.add(binder)) return
        runCatching {
            val mOnGnssStarted = XposedHelpers.findMethodExactIfExists(listener.javaClass, "onGnssStarted")
            if (mOnGnssStarted != null) {
                XposedBridge.invokeOriginalMethod(mOnGnssStarted, listener, emptyArray())
            }
        }
        runCatching {
            val mOnFirstFix = XposedHelpers.findMethodExactIfExists(listener.javaClass, "onFirstFix", Int::class.javaPrimitiveType)
            if (mOnFirstFix != null) {
                XposedBridge.invokeOriginalMethod(mOnFirstFix, listener, arrayOf(Random.nextInt(320, 780)))
            }
        }
    }

    fun callOnGnssStatusChanged() {
        if (!FakeLoc.enableMockGnss) return
        if (gnssStatusListeners.isEmpty()) return

        runCatching {
            val (mockGps, states) = ensureAndStepConstellation()
            val gnssStatus = createMockGnssStatusFromStates(mockGps, states)

            val deadListeners = mutableListOf<IInterface>()
            for (listener in gnssStatusListeners) {
                notifyFirstFixIfNeeded(listener)
                val success = dispatchGnssStatus(listener, gnssStatus, mockGps)
                if (!success && !listener.asBinder().isBinderAlive) {
                    deadListeners.add(listener)
                }
            }
            if (deadListeners.isNotEmpty()) {
                deadListeners.forEach { firstFixNotifiedBinders.remove(it.asBinder()) }
                gnssStatusListeners.removeAll(deadListeners.toSet())
            }
        }.onFailure {
            Logger.error("callOnGnssStatusChanged failed", it)
        }
    }

    fun callOnGnssStatusChangedForListener(listener: IInterface) {
        runCatching {
            val (mockGps, states) = ensureAndStepConstellation()
            val gnssStatus = createMockGnssStatusFromStates(mockGps, states)
            notifyFirstFixIfNeeded(listener)
            dispatchGnssStatus(listener, gnssStatus, mockGps)
        }.onFailure {
            Logger.error("callOnGnssStatusChangedForListener failed", it)
        }
    }

    private fun dispatchGnssStatus(listener: IInterface, gnssStatus: GnssStatus?, mockGps: MockGnssData): Boolean {
        var called = false
        if (gnssStatus != null) {
            kotlin.runCatching {
                val mOnSvStatusChanged = XposedHelpers.findMethodBestMatch(listener.javaClass, "onSvStatusChanged", gnssStatus)
                XposedBridge.invokeOriginalMethod(mOnSvStatusChanged, listener, arrayOf(gnssStatus))
                called = true
            }.onFailure {
                if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                    return false
                }
            }
        }
        if (!called) {
            kotlin.runCatching {
                val svCount = mockGps.svCount
                val baseband = FloatArray(svCount) { (mockGps.cn0s[it] - Random.nextFloat(1.5f, 3.0f)).coerceAtLeast(20f) }
                val mOnSvStatusChanged = XposedHelpers.findMethodBestMatch(
                    listener.javaClass,
                    "onSvStatusChanged",
                    svCount,
                    mockGps.svidWithFlags,
                    mockGps.cn0s,
                    mockGps.elevations,
                    mockGps.azimuths,
                    mockGps.carrierFreqs,
                    baseband
                )
                XposedBridge.invokeOriginalMethod(
                    mOnSvStatusChanged,
                    listener,
                    arrayOf(svCount, mockGps.svidWithFlags, mockGps.cn0s, mockGps.elevations, mockGps.azimuths, mockGps.carrierFreqs, baseband)
                )
                called = true
            }.onFailure {
                if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                    return false
                }
            }
        }
        return called
    }

    // A random command is generated to prevent some apps from detecting Awalocation
    operator fun invoke(classLoader: ClassLoader) {
        val cLocationManagerService = XposedHelpers.findClassIfExists("com.android.server.location.LocationManagerService", classLoader)
        if (cLocationManagerService == null) {
            hookLocationManagerServiceV2(classLoader)
        } else {
            onService(cLocationManagerService)
        }
        //startDaemon(classLoader)
    }

    fun onService(cILocationManager: Class<*>) {
        // Got instance of ILocationManager.Stub here, you can hook it
        // Not directly Class.forName because of this thing, it can't be reflected, even if I'm system_server?!?!

        if (FakeLoc.enableDebugLog) {
            Logger.debug("ILocationManager.Stub: class = $cILocationManager")
        }

        cILocationManager.classLoader!!.let {
            BasicLocationHook(it)
            GnssHook(it)
            LocationProviderManagerHook(it)

            MiuiBlurLocationProviderHook(it)
            MiuiLocationManagerHook(it)
            MiuiTelephonyManagerHook(it)
        }

        LocationNMEAHook(cILocationManager)

        if(cILocationManager.hookAllMethods("getLastLocation", afterHook {
                // android 7.0.0 ~ 10.0.0
                // Location getLastLocation(in LocationRequest request, String packageName);
                // android 11.0.0
                // Location getLastLocation(in LocationRequest request, String packageName, String featureId);
                // android 12.0.0 ~ 15.0.0
                // @nullable Location getLastLocation(String provider, in LastLocationRequest request, String packageName, @nullable String attributionTag);
                // Why are there so... I'm really speechless

                // Virtual Coordinate: Instantly update the latest virtual coordinates
                // Roulette Move: Each request moves a certain distance
                // Route Simulation: Move according to a preset route
                //val uid = FqlUtils.getCallerUid()
                // Determine whether it is an app that needs a hook
                if (!FakeLoc.enable) return@afterHook

                // It can't be null, because I'm judging in the previous step
                val location = result as? Location ?: Location("gps")

                result = injectLocation(location)

                if(FakeLoc.enableDebugLog) {
                    Logger.debug("getLastLocation: injected! $result")
                }
        }).isEmpty()) {
            Logger.error("hook getLastLocation failed")
        }

        // android 12 and later remove `requestLocationUpdates`
        cILocationManager.hookAllMethods("requestLocationUpdates", beforeHook {
            // android 7.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener, String packageName);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in Location location);
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //    void onProviderEnabled(String provider);
            //    void onProviderDisabled(String provider);
            //}
            //
            // android 7.1.1 ~ 9.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            //            in PendingIntent intent, String packageName);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in Location location);
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //    void onProviderEnabled(String provider);
            //    void onProviderDisabled(String provider);
            //
            // android 10.0.0
            // oneway interface ILocationListener
            //{
            //    @UnsupportedAppUsage
            //    void onLocationChanged(in Location location);
            //    @UnsupportedAppUsage
            //    void onProviderEnabled(String provider);
            //    @UnsupportedAppUsage
            //    void onProviderDisabled(String provider);
            //    // --- deprecated ---
            //    @UnsupportedAppUsage
            //    void onStatusChanged(String provider, int status, in Bundle extras);
            //}
            //
            // android 11.0.0
            // void requestLocationUpdates(in LocationRequest request, in ILocationListener listener,
            //            in PendingIntent intent, String packageName, String featureId, String listenerId);
            //
            // oneway interface ILocationListener
            //{
            //    @UnsupportedAppUsage
            //    void onLocationChanged(in Location location);
            //    @UnsupportedAppUsage
            //    void onProviderEnabled(String provider);
            //    @UnsupportedAppUsage
            //    void onProviderDisabled(String provider);
            //    // called when the listener is removed from the server side; no further callbacks are expected
            //    void onRemoved();
            //}
            // android 12 and later
            // remove this method
            val provider = kotlin.runCatching {
                XposedHelpers.callMethod(args[0], "getProvider") as? String
            }.getOrNull() ?: "gps"

            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("requestLocationUpdates: listener is null: $method")
                return@beforeHook
            }

            if(FakeLoc.enableDebugLog) {
                Logger.debug("requestLocationUpdates: injected! $listener")
            }

            addLocationListenerInner(provider, listener)

            if (FakeLoc.disableRegisterLocationListener || FakeLoc.enable) {
                result = null
                return@beforeHook
            }

            if (FakeLoc.disableFusedLocation && provider == "fused") {
                result = null
                return@beforeHook
            }
        })
        cILocationManager.hookAllMethods("removeUpdates", afterHook {
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("removeUpdates: listener is null: $method")
                return@afterHook
            }
            if(FakeLoc.enableDebugLog) {
                Logger.debug("removeUpdates: injected! $listener")
            }

            removeLocationListenerInner(listener)
        })
        cILocationManager.hookAllMethods("registerLocationListener", beforeHook {
            // android 12 ~ android 15
            // void registerLocationListener(String provider, in LocationRequest request, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
            //
            // oneway interface ILocationListener
            //{
            //    void onLocationChanged(in List<Location> locations, in @nullable IRemoteCallback onCompleteCallback);
            //    void onProviderEnabledChanged(String provider, boolean enabled);
            //    void onFlushComplete(int requestCode);
            //}
            // Android 12+ 签名为 registerLocationListener(String provider, LocationRequest request, ILocationListener listener, ...)
            val provider = (args.getOrNull(0) as? String) ?: kotlin.runCatching {
                XposedHelpers.callMethod(args.getOrNull(1), "getProvider") as? String
            }.getOrNull() ?: "gps"
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("registerLocationListener: listener is null: $method")
                return@beforeHook
            }

            if(FakeLoc.enableDebugLog) {
                Logger.debug("registerLocationListener: injected! $listener, provider=$provider, from ${BinderUtils.getUidPackageNames()}")
            }

            addLocationListenerInner(provider, listener)

            if (FakeLoc.enable && (FakeLoc.latitude != 0.0 || FakeLoc.longitude != 0.0)) {
                runCatching { dispatchLocationToListener(provider, listener) }
            }

            if (FakeLoc.disableRegisterLocationListener) {
                result = null
                return@beforeHook
            }

            if (FakeLoc.disableFusedLocation && provider == "fused") {
                result = null
                return@beforeHook
            }
        })
        cILocationManager.hookAllMethods("unregisterLocationListener", afterHook {
            val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                Logger.error("unregisterLocationListener: listener is null: $method")
                return@afterHook
            }
            if(FakeLoc.enableDebugLog) {
                Logger.debug("unregisterLocationListener: injected! $listener")
            }

            removeLocationListenerInner(listener)
        })

        run {
            cILocationManager.hookAllMethods("addGnssBatchingCallback", beforeHook {
                if (hasThrowable() || args.isEmpty() || args[0] == null) return@beforeHook
                val callback = args[0] ?: return@beforeHook
                val classCallback = callback.javaClass

                if(FakeLoc.enableDebugLog) {
                    Logger.debug("addGnssBatchingCallback: injected!")
                }

                classCallback.onceHookAllMethod("onLocationBatch", beforeHook onLocationBatch@ {
                    if (args.isEmpty()) return@onLocationBatch

                    if (!FakeLoc.enable) {
                        return@onLocationBatch
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("onLocationBatch: injected!")
                    }

                    val location = (args[0] ?: return@onLocationBatch) as Location
                    args[0] = injectLocation(location)
                })
            })
        }

        cILocationManager.hookAllMethods("requestGeofence", beforeHook {
            if (FakeLoc.disableRequestGeofence && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("requestGeofence: injected!")
                }
                result = null
            }
        })
//        cILocationManager.hookAllMethods("removeGeofence", beforeHook {
//        })

        cILocationManager.hookAllMethods("getFromLocation", beforeHook {
            if (FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("getFromLocation: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("getFromLocationName", beforeHook {
            if (FakeLoc.disableGetFromLocation && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("getFromLocationName: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("addTestProvider", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("addTestProvider: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("removeTestProvider", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("removeTestProvider: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("setTestProviderLocation", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("setTestProviderLocation: injected!")
                }
                result = null
            }
        })

        cILocationManager.hookAllMethods("setTestProviderEnabled", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("setTestProviderEnabled: injected!")
                }
                result = null
            }
        })

        if(XposedBridge.hookAllMethods(cILocationManager, "registerGnssStatusCallback", object: XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam?) {
                    if(param == null || param.args.isEmpty() || param.args[0] == null) return

                    val callback = param.args[0] ?: return
                    if (callback is IInterface) {
                        addGnssStatusListenerInner(callback)
                    }

                    val cIGnssStatusListener = callback.javaClass

                    if(FakeLoc.enableDebugLog) {
                        Logger.debug("registerGnssStatusCallback: injected! ${cIGnssStatusListener.name}")
                    }

                    if (cIGnssStatusListener.onceHookAllMethod("onSvStatusChanged", beforeHook {
                        if (!FakeLoc.enableMockGnss) return@beforeHook

                        val (mockGps, states) = ensureAndStepConstellation()

                        if (args[0] is Int) {
                            args[0] = mockGps.svCount
                            args[1] = mockGps.svidWithFlags
                            args[2] = mockGps.cn0s
                            args[3] = mockGps.elevations
                            args[4] = mockGps.azimuths
                            if (args.size > 5) {
                                args[5] = mockGps.carrierFreqs
                            }

                            if (args.size > 6) {
                                args[6] = FloatArray(mockGps.svCount) {
                                    (mockGps.cn0s[it] - Random.nextFloat(1.5f, 3.0f)).coerceAtLeast(20f)
                                }
                            }
                            return@beforeHook
                        }

                        if (args[0] != null && (args[0] is GnssStatus || args[0].javaClass.name == "android.location.GnssStatus")) {
                            val mockStatus = createMockGnssStatusFromStates(mockGps, states)
                            if (mockStatus != null) {
                                args[0] = mockStatus
                            } else {
                                Logger.error("onSvStatusChanged: createMockGnssStatusFromStates returned null")
                            }
                            return@beforeHook
                        }

                        Logger.error("onSvStatusChanged: unsupported version: $method")
                    }).isEmpty()) {
                        Logger.error("find onSvStatusChanged failed!")
                    }

                    cIGnssStatusListener.onceHookAllMethod("onNmeaReceived", beforeHook {
                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("onNmeaReceived")
                        }
                        if (FakeLoc.enableMockGnss && !FakeLoc.enableAGPS) result = null
                    })

                    if (FakeLoc.enableMockGnss && callback is IInterface) {
                        callOnGnssStatusChangedForListener(callback)
                    }
                }
            }).isEmpty()) {
            XposedBridge.log("[Awalocation] hook registerGnssStatusCallback failed")
        }

        cILocationManager.hookAllMethods("unregisterGnssStatusCallback", beforeHook {
            if (args.isNotEmpty() && args[0] is IInterface) {
                removeGnssStatusListenerByBinder((args[0] as IInterface).asBinder())
            }
        })
        // android 11+
        // @EnforcePermission("LOCATION_HARDWARE")
        // void startGnssBatch(long periodNanos, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
        //
        // void startGnssBatch(long periodNanos, in ILocationListener listener, String packageName, @nullable String attributionTag, String listenerId);
        cILocationManager.hookAllMethods("startGnssBatch", beforeHook {
            if(FakeLoc.enableDebugLog) {
                Logger.debug("startGnssBatch: injected!")
            }

            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                    Logger.error("startGnssBatch: listener is null: $method")
                    return@beforeHook
                }

                addLocationListenerInner("GnssBatch", listener)
                hookILocationListener(listener)
            }
        })
        cILocationManager.hookAllMethods("stopGnssBatch", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("stopGnssBatch: injected!")
                }
            }
            //locationListeners.removeIf { it.first == "GnssBatch" }
        })

        //  void requestListenerFlush(String provider, in ILocationListener listener, int requestCode);
        cILocationManager.hookAllMethods("requestListenerFlush", beforeHook {
            if (FakeLoc.enable && !FakeLoc.enableAGPS && args.size >= 2) {
                if(FakeLoc.enableDebugLog) {
                    Logger.debug("requestListenerFlush: injected!")
                }

                val listener = args.filterIsInstance<IInterface>().firstOrNull() ?: run {
                    Logger.error("requestListenerFlush: listener is null: $method")
                    return@beforeHook
                }

                addLocationListenerInner("gps", listener)

                if (FakeLoc.disableRegisterLocationListener || FakeLoc.enable) {
                    result = null
                }

                hookILocationListener(listener)
            }
        })

//        cILocationManager.hookAllMethods("getBestProvider", beforeHook {
//            if (FakeLoc.enable) {
//                result = "gps"
//            }
//        })
//
//        cILocationManager.hookAllMethods("getAllProviders", afterHook {
//            if(FakeLoc.enable) {
//                result = if (result is List<*>) {
//                    listOf("gps", "passive")
//                } else if (result is Array<*>) {
//                    arrayOf("gps", "passive")
//                } else {
//                    Logger.error("getAllProviders: result is not List or Array")
//                    return@afterHook
//                }
//            }
//        })
//
//        cILocationManager.hookAllMethods("getProviders", afterHook {
//            if(FakeLoc.enable) {
//                result = if (result is List<*>) {
//                    listOf("gps", "passive")
//                } else if (result is Array<*>) {
//                    arrayOf("gps", "passive")
//                } else {
//                    Logger.error("getProviders: result is not List or Array")
//                    return@afterHook
//                }
//            }
//        })
//
//        cILocationManager.hookAllMethods("hasProvider", beforeHook {
//            if (FakeLoc.enableDebugLog) {
//                Logger.debug("hasProvider: ${args[0]}")
//            }
//
//            if(FakeLoc.enable) {
//                if (args[0] == "gps") {
//                    result = true
//                } else if (args[0] == "network") {
//                    result = false
//                } else if (args[0] == "fused" && FakeLoc.disableFusedLocation) {
//                    result = false
//                }
//            }
//        })

        cILocationManager.hookAllMethods("getCurrentLocation", beforeHook {
            if (args.size < 3) return@beforeHook
            val callback = args[2] ?: return@beforeHook

            if (FakeLoc.enableDebugLog) {
                Logger.debug("getCurrentLocation: injected!")
            }

            if (FakeLoc.disableGetCurrentLocation) {
                result = null
                return@beforeHook
            }

            val classCallback = callback.javaClass
            classCallback.onceHookAllMethod("onLocation", beforeHook onLocation@ {
                if (!FakeLoc.enable) return@onLocation
                val location = args.getOrNull(0) as? Location ?: return@onLocation

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("onLocation(getCurrentLocation): injected!")
                }

                args[0] = injectLocation(location)
            })
        })

        cILocationManager.hookAllMethods("sendExtraCommand", beforeHook {
            if (args.size < 3) return@beforeHook

            val provider = args[0] as? String
            val command = args[1] as? String ?: return@beforeHook
            val outResult = args[2] as? Bundle

            if (FakeLoc.disableFusedLocation && provider == "fused") {
                result = false
                return@beforeHook
            }

            // If the GPS provider is enabled, the GPS provider is disabled
            if(provider == "gps" && FakeLoc.enable) {
                result = false
                return@beforeHook
            }

            if(provider == "LOCATION_BIG_DATA") {
                result = false
                return@beforeHook
            }

            // Not the provider of awalocation/awalocation, does not process
            if (provider != "awalocation") {
                if (FakeLoc.enableDebugLog)
                    Logger.debug("sendExtraCommand provider: $provider, command: $command, result: $result")
                return@beforeHook
            }
            if (outResult == null) return@beforeHook

            if (handleInstruction(command, outResult)) {
                result = true
            }
        })

        if(
        // boolean isProviderEnabledForUser(String provider, int userId); from android 9.0.0
            XposedBridge.hookAllMethods(
                cILocationManager,
                "isProviderEnabledForUser",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.size < 2) return
                        val provider = param.args[0] as? String ?: return
                        var userId = param.args[1] as? Int ?: return
                        if (provider == "awalocation") {
                            if (userId == 0) {
                                userId = BinderUtils.getCallerUid()
                            }
                            param.result = BinderUtils.isLocationProviderEnabled(userId)
                        } else if(provider == "network") {
                            param.result = !FakeLoc.enable
                        } else if (FakeLoc.disableFusedLocation && provider == "fused") {
                            param.result = false
                            return
                        } else {
                            if (FakeLoc.enableDebugLog) {
                                 Logger.debug("isProviderEnabledForUser provider: $provider, userId: $userId")
                            }
                        }
                    }
                }).isEmpty()
        ) {
            // boolean isProviderEnabled(String provider);
            XposedBridge.hookAllMethods(
                cILocationManager,
                "isProviderEnabled",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.isEmpty()) return
                        val provider = param.args[0] as? String ?: return
                        val userId = BinderUtils.getCallerUid()
                        if (provider == "awalocation" && BinderUtils.isLocationProviderEnabled(userId)) {
                            param.result = true
                        } else if(provider == "network") {
                            param.result = !FakeLoc.enable
                        } else if (FakeLoc.disableFusedLocation && provider == "fused") {
                            param.result = false
                            return
                        }
                    }
                })
        }


        // F**k You! AMAP Service!
        XposedBridge.hookAllMethods(cILocationManager, "setExtraLocationControllerPackageEnabled", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enable && param.args.isNotEmpty()) {
                    param.args[0] = false
                }
            }
        })

        XposedBridge.hookAllMethods(cILocationManager, "setExtraLocationControllerPackage", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (FakeLoc.enable) {
                    param.result = null
                }
            }
        })

    }

    private fun hookILocationListener(listener: Any) {
        val classListener = listener.javaClass
        if (FakeLoc.enableDebugLog)
            Logger.debug("will hook ILocationListener: ${classListener.name}")

        classListener.onceHookAllMethod("onLocationChanged", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.isEmpty()) return
                    if (!FakeLoc.enable) return

                    when (param.args[0]) {
                        is Location -> {
                            val location = param.args[0] as? Location ?: run {
                                param.result = null
                                return
                            }
                            param.args[0] = injectLocation(location)
                        }

                        is List<*> -> {
                            val locations = param.args[0] as List<*>
                            param.args[0] = locations.map { (it as? Location)?.let(::injectLocation) ?: it }
                        }
                        else -> Logger.error("onLocationChanged args is not `Location`")
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method}: injected! ${param.args[0]}")
                    }
                }
            })
    }

//    private fun startDaemon(classLoader: ClassLoader) {
//        //val cIRemoteCallback = XposedHelpers.findClass("android.os.IRemoteCallback", classLoader)
//        thread(
//            name = "LocationUpdater",
//            isDaemon = true,
//            start = true,
//        ) {
//            while (true) {
//                kotlin.runCatching {
//                    if (!FakeLoc.enable) {
//                        Thread.sleep(3000)
//                        return@runCatching
//                    } else {
//                        Thread.sleep(FakeLoc.updateInterval)
//                    }
//
//                    if (!FakeLoc.enable) return@runCatching // Prevent the last loop from being executed
//
//                    if (FakeLoc.enableDebugLog)
//                        Logger.debug("LocationUpdater: callOnLocationChanged: ${locationListeners.size}")
//
//                    callOnLocationChanged()
//                }.onFailure {
//                    Logger.error("LocationUpdater", it)
//                }
//            }
//        }
//    }

    private fun addLocationListenerInner(provider: String, listener: IInterface) {
        val binder = listener.asBinder()
        if (locationListeners.any { it.second.asBinder() == binder }) {
            hookILocationListener(listener)
            return
        }
        val mDeathRecipient = object: IBinder.DeathRecipient {
            override fun binderDied() {}
            override fun binderDied(who: IBinder) {
                runCatching { who.unlinkToDeath(this, 0) }
                removeLocationListenerByBinder(who)
            }
        }
        runCatching {
            binder.linkToDeath(mDeathRecipient, 0)
        }.onFailure {
            Logger.warn("linkToDeath failed for location listener", it)
        }
        locationListeners.add(provider to listener)
        hookILocationListener(listener)
    }

    private fun removeLocationListenerInner(listener: IInterface) {
        removeLocationListenerByBinder(listener.asBinder())
    }

    private fun removeLocationListenerByBinder(binder: IBinder) {
        locationListeners.removeIf { it.second.asBinder() == binder }
    }

    private fun dispatchLocationToListener(provider: String, listener: IInterface): Boolean {
        val binder = listener.asBinder()
        if (!binder.isBinderAlive) return false

        val normalizedProvider = if (provider == "GnssBatch" || provider == "fused" || provider == "network") {
            "gps"
        } else {
            provider
        }
        var location = FakeLoc.lastLocation?.let { base ->
            Location(base).apply {
                extras?.remove("latlon")
                extras?.remove(EXTRA_INJECTED_LAT)
                extras?.remove(EXTRA_INJECTED_LON)
            }
        } ?: Location(normalizedProvider)
        location.time = System.currentTimeMillis()
        location.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        location = injectLocation(location, realLocation = false)

        var called = false
        var error: Throwable? = null
        kotlin.runCatching {
            val locations = listOf(location)
            val mOnLocationChanged = XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", locations, null)
            XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(locations, null))
            called = true
        }.onFailure {
            if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                return false
            }
            error = it
        }

        if (!called) runCatching {
            val mOnLocationChanged = XposedHelpers.findMethodBestMatch(listener.javaClass, "onLocationChanged", location)
            XposedBridge.invokeOriginalMethod(mOnLocationChanged, listener, arrayOf(location))
            called = true
        }.onFailure {
            if (it is InvocationTargetException && it.targetException is DeadObjectException) {
                return false
            }
            error = it
        }

        if (!called) {
            Logger.error("callOnLocationChanged failed: " + error?.stackTraceToString())
            Logger.error("The listener all methods: " + listener.javaClass.declaredMethods.joinToString { it.name })
        }
        return called
    }

    fun callOnLocationChanged() {
        lastLocationBroadcastElapsedMs = SystemClock.elapsedRealtime()
        if (FakeLoc.enableDebugLog) {
            Logger.debug("==> callOnLocationChanged: ${locationListeners.size}")
        }
        // GNSS 状态已由 GnssMockDaemon 按 1Hz 节奏独立上报，避免高频位置广播重复冲刷 GNSS 监听器
        val deadBinders = mutableListOf<IBinder>()
        locationListeners.forEach { (provider, listener) ->
            val ok = dispatchLocationToListener(provider, listener)
            if (!ok && !listener.asBinder().isBinderAlive) {
                deadBinders.add(listener.asBinder())
            }
        }
        if (deadBinders.isNotEmpty()) {
            deadBinders.forEach { removeLocationListenerByBinder(it) }
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("==> callOnLocationChanged: end")
        }
    }

    private fun hookLocationManagerServiceV2(classLoader: ClassLoader) {
        // As a system_server, the hook can get all the location information here
        kotlin.runCatching {
            XposedHelpers.findClass("android.location.ILocationManager\$Stub", classLoader)
        }.onSuccess {
            fun hookOnTransactForServiceInstance(m: Method) {
                val isHooked = AtomicBoolean(false)
                XposedBridge.hookMethod(m, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param?.thisObject == null || param.args.size < 4) return

                        val thisObject = param.thisObject
                        val code = param.args[0] as? Int ?: return
                        @Suppress("UNUSED_VARIABLE")
                        val data = param.args[1] as? Parcel
                        @Suppress("UNUSED_VARIABLE")
                        val reply = param.args[2] as? Parcel
                        @Suppress("UNUSED_VARIABLE")
                        val flags = param.args[3] as? Int ?: 0

                        if (isHooked.compareAndSet(false, true)) {
                            onService(thisObject.javaClass)
                        }

                        if (!FakeLoc.enable) {
                            return
                        }

                        if (FakeLoc.enable && code == 43) {
                            param.result = true
                        }

                        if (FakeLoc.enableDebugLog) {
                            Logger.debug("ILocationManager.Stub: onTransact(code=$code)")
                        }
                    }
                })
            }

            it.declaredMethods.forEach {
                if (it.name == "onTransact") {
                    hookOnTransactForServiceInstance(it)

                    // Hey, hey, you've found onTransact, what else are you looking for
                    // It's time to end the cycle! BaKa!
                    return@forEach
                }
            }
        }.onFailure {
            Logger.error("ILocationManager.Stub not found", it)
        }

//        // This is the intrusive hook
//        kotlin.runCatching {
//            XposedHelpers.findClass("android.location.ILocationManager\$Stub\$Proxy", cLocationManager.classLoader)
//        }.onSuccess {
//            it.declaredMethods.forEach {
//                XposedBridge.hookMethod(it, object : XC_MethodHook() {
//                    override fun beforeHookedMethod(param: MethodHookParam?) {
//                        if (param == null) return
//
//                        XposedBridge.log("[Awalocation] ILocationManager.Stub.Proxy: c = ${param.thisObject?.javaClass}, m = ${param.method}")
//                    }
//                })
//            }
//        }
    }

    private fun handleInstruction(command: String, rely: Bundle): Boolean {
        return RemoteCommandHandler.handleInstruction(command, rely)
    }
}

private fun Random.nextFloat(min: Float, max: Float): Float {
    return nextFloat() * (max - min) + min
}
