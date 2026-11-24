package com.nanci.awalocation.ui.viewmodel

import android.app.Activity
import android.content.res.Configuration
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.nanci.awalocation.android.coro.CoroutineController
import com.nanci.awalocation.android.coro.CoroutineRouteMock
import com.nanci.awalocation.android.widget.RockerView
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.dynamicColorEnabled
import com.nanci.awalocation.ext.reportDuration
import com.nanci.awalocation.ext.routeLoop
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.mock.HistoricalLocation
import com.nanci.awalocation.ui.mock.HistoricalRoute
import com.nanci.awalocation.ui.mock.Rocker
import com.nanci.awalocation.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private var rockerThemeKey: Pair<Int, Boolean>? = null
    private lateinit var rockerJob: Job
    private lateinit var routeMockJob: Job
    var isRockerLocked = false
    @Volatile var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false
    val mockRunning = MutableLiveData(false)
    val rockerShowing = MutableLiveData(false)

    @Volatile
    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null) {
                MockServiceHelper.tryInitService(value)
                refreshMockState()
            }
        }

    var selectedLocation: HistoricalLocation? = null
    @Volatile var selectedRoute: HistoricalRoute? = null

    fun initRocker(activity: Activity): Rocker {
        val appContext = activity.applicationContext
        val currentThemeKey =
            (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) to
                activity.dynamicColorEnabled

        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
            rockerThemeKey = currentThemeKey
            attachRockerListeners()
        } else if (rockerThemeKey != currentThemeKey) {
            val wasShowing = rocker.isStart
            val wasAuto = rocker.autoStatus
            if (wasShowing) {
                runCatching { rocker.hide(immediate = true) }
            }
            rocker = Rocker(activity)
            rockerThemeKey = currentThemeKey
            attachRockerListeners()
            if (wasShowing) {
                rocker.show()
            }
            if (wasAuto) {
                rocker.autoStatus = true
            }
            refreshMockState()
        }

        if (!::rockerJob.isInitialized || !rockerJob.isActive) {
            rockerCoroutineController.pause()
            rockerJob = viewModelScope.launch(Dispatchers.IO) {
                while (isActive) {
                    rockerCoroutineController.controlledCoroutine()
                    val delayTime = appContext.reportDuration.toLong().coerceAtLeast(20L)
                    delay(delayTime)
                    if (rockerCoroutineController.isPaused) continue

                    val lm = locationManager
                    if (lm == null) {
                        rockerCoroutineController.pause()
                        continue
                    }

                    CrashReport.setUserSceneTag(appContext, 261773)
                    val currentSpeed = appContext.speed
                    FakeLoc.speed = currentSpeed
                    val stepDistance = currentSpeed * (delayTime / 1000.0)
                    if (!MockServiceHelper.move(lm, stepDistance, FakeLoc.bearing)) {
                        Log.e("MockServiceViewModel", "Failed to move")
                    }
                }
            }
        }

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        if (!::routeMockJob.isInitialized || !routeMockJob.isActive) {
            routeMockCoroutine.pause()
            routeMockJob = viewModelScope.launch(Dispatchers.IO) {
                routeLoop@ while (isActive) {
                    routeMockCoroutine.routeMockCoroutine()
                    val delayTime = appContext.reportDuration.toLong().coerceAtLeast(20L)
                    delay(delayTime)
                    if (routeMockCoroutine.isPaused) continue@routeLoop

                    val lm = locationManager
                    val route = selectedRoute
                    if (lm == null || route == null || route.route.isEmpty()) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        routeStage = 0
                        continue@routeLoop
                    }

                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        val first = route.route[0]
                        if (!MockServiceHelper.setLocation(lm, first.first, first.second)) {
                            Log.e("MockServiceViewModel", "Failed to set route start location")
                            routeMockCoroutine.pause()
                            rocker.autoStatus = false
                            continue@routeLoop
                        }
                        routeStage++
                    }

                    // 处理所有已到达的阶段
                    var reachedWaypointInThisTick = false
                    while (routeStage < route.route.size) {
                        val target = route.route[routeStage]
                        val location = MockServiceHelper.getLocation(lm)
                        if (location == null) {
                            routeMockCoroutine.pause()
                            rocker.autoStatus = false
                            continue@routeLoop
                        }
                        val currentLat = location.first
                        val currentLon = location.second

                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        val currentSpeed = appContext.speed
                        FakeLoc.speed = currentSpeed
                        val stepDistance = currentSpeed * (delayTime / 1000.0)
                        // 判断距离是否小于1米或本次步长
                        if (inverse.s12 < 1.0 || inverse.s12 < stepDistance) {
                            // 精确设置位置到目标点并进入下一阶段
                            if (!MockServiceHelper.setLocation(lm, target.first, target.second)) {
                                Log.e("MockServiceViewModel", "Failed to set waypoint location")
                                routeMockCoroutine.pause()
                                rocker.autoStatus = false
                                continue@routeLoop
                            }
                            routeStage++
                            reachedWaypointInThisTick = true
                        } else {
                            break
                        }
                    }

                    // 检查是否已完成所有阶段
                    if (routeStage >= route.route.size) {
                        if (appContext.routeLoop) {
                            // 循环模式：回到起点继续跑，直到用户手动停止。
                            routeStage = 0
                            continue@routeLoop
                        }
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        // 重设阶段，保持任务存活，下一次自动播放可以重新开始
                        routeStage = 0
                        continue@routeLoop
                    }

                    if (reachedWaypointInThisTick) {
                        continue@routeLoop
                    }

                    // 处理当前目标点的移动
                    val target = route.route[routeStage]
                    val location = MockServiceHelper.getLocation(lm)
                    if (location == null) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        continue@routeLoop
                    }
                    val currentLat = location.first
                    val currentLon = location.second

                    val inverse = Geodesic.WGS84.Inverse(
                        currentLat,
                        currentLon,
                        target.first,
                        target.second
                    )
                    var azimuth = inverse.azi1
                    if (azimuth < 0) {
                        azimuth += 360
                    }

                    val currentSpeed = appContext.speed
                    FakeLoc.speed = currentSpeed
                    val stepDistance = currentSpeed * (delayTime / 1000.0)
                    Log.d("MockServiceViewModel", "从 $currentLat, $currentLon 移动到 ${target.first}, ${target.second}, 方位角: $azimuth, 步长: $stepDistance")
                    if (!MockServiceHelper.move(lm, stepDistance, azimuth)) {
                        Log.e("MockServiceViewModel", "移动失败")
                    }
                }
            }
        }

        return rocker
    }

    private fun attachRockerListeners() {
        rocker.setRockerListener(object : RockerView.Companion.OnMoveListener {
            override fun onAngle(angle: Double) {
                val lm = locationManager ?: return
                MockServiceHelper.setBearing(lm, angle)
                FakeLoc.bearing = angle
                FakeLoc.hasBearings = true
            }

            override fun onLockChanged(isLocked: Boolean) {
                isRockerLocked = isLocked
            }

            override fun onFinished() {
                if (!isRockerLocked) {
                    rockerCoroutineController.pause()
                }
            }

            override fun onStarted() {
                rockerCoroutineController.resume()
            }
        })

        rocker.setRockerAutoListener(object : Rocker.Companion.OnAutoListener {
            override fun onAutoPlay(isPlay: Boolean) {
                if (isPlay) {
                    routeMockCoroutine.resume()
                } else {
                    routeMockCoroutine.pause()
                }
            }

            override fun onAutoLock(isLock: Boolean) {}
        })
    }

    fun isServiceStart(): Boolean {
        return locationManager != null && MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(
            locationManager!!
        )
    }

    fun refreshMockState() {
        mockRunning.postValue(isServiceStart())
        if (::rocker.isInitialized) {
            rockerShowing.postValue(rocker.isStart)
        }
    }

    override fun onCleared() {
        super.onCleared()
        rockerCoroutineController.pause()
        routeMockCoroutine.pause()
        if (::rockerJob.isInitialized) {
            rockerJob.cancel()
        }
        if (::routeMockJob.isInitialized) {
            routeMockJob.cancel()
        }
        if (::rocker.isInitialized && rocker.isStart) {
            runCatching { rocker.hide(immediate = true) }
        }
    }
}