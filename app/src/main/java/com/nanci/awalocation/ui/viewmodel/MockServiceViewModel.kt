package com.nanci.awalocation.ui.viewmodel

import android.app.Activity
import android.location.LocationManager
import android.util.Log
import androidx.lifecycle.ViewModel
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.nanci.awalocation.android.coro.CoroutineController
import com.nanci.awalocation.android.coro.CoroutineRouteMock
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.reportDuration
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.mock.HistoricalLocation
import com.nanci.awalocation.ui.mock.HistoricalRoute
import com.nanci.awalocation.ui.mock.Rocker
import moe.fuqiuluo.xposed.utils.FakeLoc
import net.sf.geographiclib.Geodesic

class MockServiceViewModel : ViewModel() {
    lateinit var rocker: Rocker
    private lateinit var rockerJob: Job
    private lateinit var routeMockJob: Job
    var isRockerLocked = false
    var routeStage = 0
    val rockerCoroutineController = CoroutineController()
    val routeMockCoroutine = CoroutineRouteMock()

    var isRouteStart = false

    var locationManager: LocationManager? = null
        set(value) {
            field = value
            if (value != null)
                MockServiceHelper.tryInitService(value)
        }

    var selectedLocation: HistoricalLocation? = null
    var selectedRoute: HistoricalRoute? = null


    @OptIn(DelicateCoroutinesApi::class)
    fun initRocker(activity: Activity): Rocker {
        if (!::rocker.isInitialized) {
            rocker = Rocker(activity)
        }

        if (!::rockerJob.isInitialized || rockerJob.isCancelled) {
            rockerCoroutineController.pause()
            val delayTime = activity.reportDuration.toLong()
            val applicationContext = activity.applicationContext
            rockerJob = GlobalScope.launch {
                do {
                    rockerCoroutineController.controlledCoroutine()
                    delay(delayTime)

                    CrashReport.setUserSceneTag(applicationContext, 261773)
                    val currentSpeed = activity.speed
                    FakeLoc.speed = currentSpeed
                    val stepDistance = currentSpeed * (delayTime / 1000.0)
                    if(!MockServiceHelper.move(locationManager!!, stepDistance, FakeLoc.bearing)) {
                        Log.e("MockServiceViewModel", "Failed to move")
                    }

//                    if (MockServiceHelper.broadcastLocation(locationManager!!)) {
//                        Log.d("MockServiceViewModel", "Broadcast location")
//                    } else {
//                        Log.e("MockServiceViewModel", "Failed to broadcast location")
//                    }
                } while (isActive)
            }
        }

        FakeLoc.speed = activity.speed
        FakeLoc.altitude = activity.altitude
        FakeLoc.accuracy = activity.accuracy

        if (!::routeMockJob.isInitialized || routeMockJob.isCancelled) {
            routeMockCoroutine.pause()
            val delayTime = activity.reportDuration.toLong()
            routeMockJob = GlobalScope.launch {
                do {
                    routeMockCoroutine.routeMockCoroutine()
                    delay(delayTime)
                    // 如果是第0阶段，定位到第一个点
                    if (routeStage == 0) {
                        MockServiceHelper.setLocation(
                            locationManager!!,
                            selectedRoute!!.route[0].first,
                            selectedRoute!!.route[0].second
                        )
                        routeStage++
                    }
                    val route = selectedRoute!!.route

                    // 处理所有已到达的阶段
                    var reachedWaypointInThisTick = false
                    while (routeStage < route.size) {
                        val target = route[routeStage]
                        val location = MockServiceHelper.getLocation(locationManager!!)
                        val currentLat = location!!.first
                        val currentLon = location.second

                        val inverse = Geodesic.WGS84.Inverse(
                            currentLat,
                            currentLon,
                            target.first,
                            target.second
                        )
                        val currentSpeed = activity.speed
                        FakeLoc.speed = currentSpeed
                        val stepDistance = currentSpeed * (delayTime / 1000.0)
                        // 判断距离是否小于1米或本次步长
                        if (inverse.s12 < 1.0 || inverse.s12 < stepDistance) {
                            // 精确设置位置到目标点并进入下一阶段
                            MockServiceHelper.setLocation(
                                locationManager!!,
                                target.first,
                                target.second
                            )
                            routeStage++
                            reachedWaypointInThisTick = true
                        } else {
                            break
                        }
                    }

                    // 检查是否已完成所有阶段
                    if (routeStage >= route.size) {
                        routeMockCoroutine.pause()
                        rocker.autoStatus = false
                        // 重设阶段
                        routeStage = 0
                        break // 退出循环
                    }

                    if (reachedWaypointInThisTick) {
                        continue
                    }

                    // 处理当前目标点的移动
                    val target = route[routeStage]
                    val location = MockServiceHelper.getLocation(locationManager!!)
                    val currentLat = location!!.first
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

                    val currentSpeed = activity.speed
                    FakeLoc.speed = currentSpeed
                    val stepDistance = currentSpeed * (delayTime / 1000.0)
                    Log.d("MockServiceViewModel", "从 $currentLat, $currentLon 移动到 ${target.first}, ${target.second}, 方位角: $azimuth, 步长: $stepDistance")
                    if (!MockServiceHelper.move(
                            locationManager!!,
                            stepDistance,
                            azimuth
                        )
                    ) {
                        Log.e("MockServiceViewModel", "移动失败")
                    }
                } while (isActive)
            }
        }

        return rocker
    }

    fun isServiceStart(): Boolean {
        return locationManager != null && MockServiceHelper.isServiceInit() && MockServiceHelper.isMockStart(
            locationManager!!
        )
    }
}