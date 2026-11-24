package com.nanci.awalocation.ui.viewmodel

import android.app.Activity
import android.content.res.Configuration
import android.location.LocationManager
import android.os.SystemClock
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
import com.nanci.awalocation.Awalocation
import com.nanci.awalocation.android.coro.CoroutineController
import com.nanci.awalocation.android.coro.CoroutineRouteMock
import com.nanci.awalocation.android.widget.RockerView
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.dynamicColorEnabled
import com.nanci.awalocation.ext.reportDuration
import com.nanci.awalocation.ext.routeLoop
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.service.MockKeepAliveService
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.mock.HistoricalLocation
import com.nanci.awalocation.ui.mock.HistoricalRoute
import com.nanci.awalocation.ui.mock.Rocker
import com.nanci.awalocation.ui.mock.RouteStepper
import com.nanci.awalocation.xposed.utils.FakeLoc

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private var rockerThemeKey: Pair<Int, Boolean>? = null
    private lateinit var rockerJob: Job
    private lateinit var routeMockJob: Job
    @Volatile var isRockerLocked = false
    @Volatile var routeStage = 0
    @Volatile private var routeInitialized = false
    @Volatile private var routeNeedsCoordSync = false
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

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
                var lastRockerTickNs = 0L
                while (isActive) {
                    if (rockerCoroutineController.isPaused) {
                        lastRockerTickNs = 0L
                        rockerCoroutineController.controlledCoroutine()
                    }
                    if (rockerCoroutineController.isPaused) continue

                    if (lastRockerTickNs == 0L) {
                        lastRockerTickNs = SystemClock.elapsedRealtimeNanos()
                    }
                    val delayTime = appContext.reportDuration.toLong().coerceAtLeast(20L)
                    delay(delayTime)
                    if (rockerCoroutineController.isPaused) {
                        lastRockerTickNs = 0L
                        continue
                    }

                    val lm = locationManager
                    if (lm == null) {
                        rockerCoroutineController.pause()
                        lastRockerTickNs = 0L
                        continue
                    }

                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    val dtSec = ((nowNs - lastRockerTickNs) / 1_000_000_000.0).coerceIn(0.0, 2.0)
                    lastRockerTickNs = nowNs

                    CrashReport.setUserSceneTag(appContext, 261773)
                    val currentSpeed = appContext.speed
                    FakeLoc.speed = currentSpeed
                    val stepDistance = currentSpeed * dtSec
                    if (stepDistance > 0.0 && !MockServiceHelper.move(lm, stepDistance, FakeLoc.bearing)) {
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
                var currentLat = 0.0
                var currentLon = 0.0
                var currentBearing = 0.0
                var lastTickElapsedNs = 0L

                routeLoop@ while (isActive) {
                    if (routeMockCoroutine.isPaused) {
                        lastTickElapsedNs = 0L
                        routeMockCoroutine.routeMockCoroutine()
                    }
                    if (routeMockCoroutine.isPaused) continue@routeLoop

                    val delayTime = appContext.reportDuration.toLong().coerceAtLeast(20L)
                    val lm = locationManager
                    val route = selectedRoute
                    if (lm == null || route == null || route.route.isEmpty()) {
                        stopRoutePlayback(resetStage = true)
                        lastTickElapsedNs = 0L
                        continue@routeLoop
                    }

                    // 用户正在手动按住摇杆时让出控制权，松手后再从新坐标无缝恢复，避免手动+自动叠加造成双倍配速
                    if (!rockerCoroutineController.isPaused) {
                        lastTickElapsedNs = 0L
                        routeNeedsCoordSync = true
                        delay(delayTime)
                        continue@routeLoop
                    }

                    if (!routeInitialized) {
                        val (startLat, startLon) = route.route[0]
                        currentBearing = RouteStepper.initialBearing(route.route, FakeLoc.bearing)
                        if (!MockServiceHelper.setLocation(lm, startLat, startLon, currentBearing)) {
                            Log.e("MockServiceViewModel", "Failed to set route start location")
                            stopRoutePlayback(resetStage = true)
                            lastTickElapsedNs = 0L
                            continue@routeLoop
                        }
                        currentLat = startLat
                        currentLon = startLon
                        routeStage = if (route.route.size > 1) 1 else 0
                        routeInitialized = true
                        routeNeedsCoordSync = false
                        lastTickElapsedNs = SystemClock.elapsedRealtimeNanos()
                        delay(delayTime)
                        continue@routeLoop
                    }

                    if (lastTickElapsedNs == 0L || routeNeedsCoordSync) {
                        val synced = MockServiceHelper.getLocation(lm)
                        if (synced != null) {
                            currentLat = synced.first
                            currentLon = synced.second
                        }
                        routeNeedsCoordSync = false
                        if (lastTickElapsedNs == 0L) {
                            lastTickElapsedNs = SystemClock.elapsedRealtimeNanos()
                            delay(delayTime)
                            continue@routeLoop
                        }
                    }

                    val nowNs = SystemClock.elapsedRealtimeNanos()
                    val dtSec = ((nowNs - lastTickElapsedNs) / 1_000_000_000.0).coerceIn(0.0, 2.0)
                    lastTickElapsedNs = nowNs

                    val currentSpeed = appContext.speed
                    FakeLoc.speed = currentSpeed
                    if (currentSpeed <= 0.0 || dtSec <= 0.0) {
                        delay(delayTime)
                        continue@routeLoop
                    }

                    val stepDistance = currentSpeed * dtSec
                    val outcome = RouteStepper.advanceAlongRoute(
                        currentLat = currentLat,
                        currentLon = currentLon,
                        currentStage = routeStage,
                        waypoints = route.route,
                        distanceMeters = stepDistance,
                        loop = appContext.routeLoop,
                        fallbackBearing = currentBearing,
                    )

                    currentLat = outcome.lat
                    currentLon = outcome.lon
                    currentBearing = outcome.bearing
                    routeStage = outcome.nextStage

                    if (outcome.finished) {
                        MockServiceHelper.setLocation(lm, currentLat, currentLon, currentBearing)
                        stopRoutePlayback(resetStage = true)
                        lastTickElapsedNs = 0L
                        if (isServiceStart()) {
                            MockKeepAliveService.start(appContext, isRoute = false)
                        } else {
                            MockKeepAliveService.stop(appContext)
                        }
                        continue@routeLoop
                    }

                    Log.d("MockServiceViewModel", "路线推进 -> (${outcome.lat}, ${outcome.lon}), 方位角: $currentBearing, 步长: $stepDistance")
                    if (!MockServiceHelper.stepRouteLocation(lm, currentLat, currentLon, currentBearing)) {
                        Log.e("MockServiceViewModel", "路线移动位置更新失败")
                    }
                    delay(delayTime)
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
                routeNeedsCoordSync = true
                rockerCoroutineController.resume()
            }
        })

        rocker.setRockerAutoListener(object : Rocker.Companion.OnAutoListener {
            override fun onAutoPlay(isPlay: Boolean) {
                val appCtx = runCatching { Awalocation.appContext }.getOrNull()
                if (isPlay) {
                    isRockerLocked = false
                    rockerCoroutineController.pause()
                    routeNeedsCoordSync = true
                    routeMockCoroutine.resume()
                    appCtx?.let { MockKeepAliveService.start(it, isRoute = true) }
                } else {
                    routeMockCoroutine.pause()
                    appCtx?.let {
                        if (isServiceStart()) {
                            MockKeepAliveService.start(it, isRoute = false)
                        } else {
                            MockKeepAliveService.stop(it)
                        }
                    }
                }
            }

            override fun onAutoLock(isLock: Boolean) {}
        })
    }

    fun resetRouteProgress() {
        routeStage = 0
        routeInitialized = false
        routeNeedsCoordSync = false
    }

    fun startRoutePlayback() {
        val appCtx = runCatching { Awalocation.appContext }.getOrNull()
        isRockerLocked = false
        rockerCoroutineController.pause()
        routeMockCoroutine.resume()
        if (::rocker.isInitialized) {
            rocker.autoStatus = true
        }
        appCtx?.let { MockKeepAliveService.start(it, isRoute = true) }
    }

    fun stopRoutePlayback(resetStage: Boolean = true) {
        routeMockCoroutine.pause()
        if (::rocker.isInitialized) {
            rocker.autoStatus = false
        }
        if (resetStage) {
            resetRouteProgress()
        }
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
        if (!isServiceStart()) {
            runCatching { Awalocation.appContext }.getOrNull()?.let {
                MockKeepAliveService.stop(it)
            }
        }
    }
}