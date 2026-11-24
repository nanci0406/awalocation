package com.nanci.awalocation.service

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.nanci.awalocation.Awalocation
import com.nanci.awalocation.android.root.ShellUtils
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.debug
import com.nanci.awalocation.ext.disableFusedProvider
import com.nanci.awalocation.ext.disableGetCurrentLocation
import com.nanci.awalocation.ext.disableRegisterLocationListener
import com.nanci.awalocation.ext.disableWifiScan
import com.nanci.awalocation.ext.enableAGPS
import com.nanci.awalocation.ext.enableGetFromLocation
import com.nanci.awalocation.ext.enableNMEA
import com.nanci.awalocation.ext.enableRequestGeofence
import com.nanci.awalocation.ext.enableStepMock
import com.nanci.awalocation.ext.minSatelliteCount
import com.nanci.awalocation.ext.needDowngradeToCdma
import com.nanci.awalocation.ext.needOpenSELinux
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.ext.reportDuration
import com.nanci.awalocation.ext.loopBroadcastlocation
import com.nanci.awalocation.ext.stepCadence
import com.nanci.awalocation.xposed.utils.FakeLoc
import java.io.File

object MockServiceHelper {
    const val PROVIDER_NAME = "awalocation"
    private lateinit var randomKey: String

    private var loopThread :Thread ?= null
    @Volatile private var isRunning = false

    fun tryInitService(locationManager: LocationManager) {
        val rely = Bundle()
        Log.d("MockServiceHelper", "Try to init service")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, "exchange_key", rely)) {
            rely.getString("key")?.let {
                randomKey = it
                Log.d("MockServiceHelper", "Service init success, key: $randomKey")
            }
        } else {
            Log.e("MockServiceHelper", "Failed to init service")
        }
    }

    fun isMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_start")
        }
        return false
    }

    fun isGnssMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_gnss_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_gnss_start")
        }
        return false
    }

    fun startGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) FakeLoc.enableMockGnss = true
        }
    }

    fun stopGnssMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_gnss_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) FakeLoc.enableMockGnss = false
        }
    }

    fun isWifiMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_wifi_mock_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_wifi_mock_start")
        }
        return false
    }

    fun startWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "start_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) FakeLoc.enableMockWifi = true
        }
    }

    fun stopWifiMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_wifi_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) FakeLoc.enableMockWifi = false
        }
    }

    fun isStepMockStart(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "is_step_start")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getBoolean("is_step_start")
        }
        return false
    }

    fun startStepMock(locationManager: LocationManager, cadence: Float): Boolean {
        if (!::randomKey.isInitialized) {
            tryInitService(locationManager)
            if (!::randomKey.isInitialized) {
                return false
            }
        }
        runCatching { Awalocation.appContext }.getOrNull()?.let { loadAwalocationLibrary(it) }
        val rely = Bundle()
        rely.putString("command_id", "start_step_mock")
        rely.putFloat("cadence", cadence)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) {
                FakeLoc.enableMockStep = true
                FakeLoc.stepCadence = cadence
            }
        }
    }

    fun stopStepMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            tryInitService(locationManager)
            if (!::randomKey.isInitialized) {
                return false
            }
        }
        val rely = Bundle()
        rely.putString("command_id", "stop_step_mock")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) FakeLoc.enableMockStep = false
        }
    }

    fun setStepCadence(locationManager: LocationManager, cadence: Float): Boolean {
        if (!::randomKey.isInitialized) {
            tryInitService(locationManager)
            if (!::randomKey.isInitialized) {
                return false
            }
        }
        val rely = Bundle()
        rely.putString("command_id", "set_step_cadence")
        rely.putFloat("cadence", cadence)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) FakeLoc.stepCadence = cadence
        }
    }

    fun tryOpenMock(
        locationManager: LocationManager,
        speed: Double,
        altitude: Double,
        accuracy: Float,
    ): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        // 启动模拟前先同步本地配置到 system_server，并按用户设置切换 SELinux 宽容模式；
        // native 传感器 Hook 仅在开启步频模拟时按需加载。
        runCatching { Awalocation.appContext }.getOrNull()?.let { appCtx ->
            if (appCtx.needOpenSELinux && ShellUtils.hasRoot()) {
                ShellUtils.setEnforceMode(false)
            }
            if (appCtx.enableStepMock) {
                loadAwalocationLibrary(appCtx)
            }
            putConfig(locationManager, appCtx)
        }
        val rely = Bundle()
        rely.putString("command_id", "start")
        rely.putDouble("speed", speed)
        rely.putDouble("altitude", altitude)
        rely.putFloat("accuracy", accuracy)
        return if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            isMockStart(locationManager).also { started ->
                if (started) {
                    FakeLoc.enable = true
                    startLoopBroadcastLocation(locationManager)
                }
            }
        } else {
            false
        }
    }

    fun tryCloseMock(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "stop")
        stopLoopBroadcastLocation()
        if (locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return (!isMockStart(locationManager)).also { closed ->
                if (closed) FakeLoc.enable = false
            }
        }
        return false
    }

    fun getLocation(locationManager: LocationManager): Pair<Double, Double>? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_location")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return Pair(rely.getDouble("lat"), rely.getDouble("lon"))
        }
        return null
    }

    fun getLocationListenerSize(locationManager: LocationManager): Int? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_listener_size")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getInt("size")
        }
        return null
    }

    fun broadcastLocation(locationManager: LocationManager): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "broadcast_location")
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setBearing(locationManager: LocationManager, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_bearing")
        rely.putDouble("bearing", bearing)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) {
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
            }
        }
    }

    fun setSpeed(locationManager: LocationManager, speed: Float): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed")
        // RemoteCommandHandler uses getDouble("speed"), so keep the Bundle type consistent.
        rely.putDouble("speed", speed.toDouble())
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) FakeLoc.speed = speed.toDouble()
        }
    }

    fun setAltitude(locationManager: LocationManager, altitude: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_altitude")
        rely.putDouble("altitude", altitude)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely).also {
            if (it) FakeLoc.altitude = altitude
        }
    }

    fun setSpeedAmplitude(locationManager: LocationManager, speedAmplitude: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "set_speed_amp")
        rely.putDouble("speed_amplitude", speedAmplitude)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun getSpeed(locationManager: LocationManager): Float? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_speed")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getDouble("speed").toFloat()
        }
        return null
    }

    fun getBearing(locationManager: LocationManager): Float? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_bearing")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getDouble("bearing").toFloat()
        }
        return null
    }

    fun getAltitude(locationManager: LocationManager): Double? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "get_altitude")
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getDouble("altitude")
        }
        return null
    }

    fun move(locationManager: LocationManager, distance: Double, bearing: Double): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "move")
        rely.putDouble("n", distance)
        rely.putDouble("bearing", bearing)

        if (FakeLoc.enableDebugLog) {
            Log.d("MockServiceHelper", "move: distance=$distance, bearing=$bearing")
        }

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun setLocation(locationManager: LocationManager, lat: Double, lon: Double): Boolean {
        return updateLocation(locationManager, lat, lon, "=")
    }

    fun updateLocation(locationManager: LocationManager, lat: Double, lon: Double, mode: String): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }
        val rely = Bundle()
        rely.putString("command_id", "update_location")
        rely.putDouble("lat", lat)
        rely.putDouble("lon", lon)
        rely.putString("mode", mode)
        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun loadLibrary(locationManager: LocationManager, path: String): String? {
        if (!::randomKey.isInitialized) {
            return null
        }
        val rely = Bundle()
        rely.putString("command_id", "load_library")
        rely.putString("path", path)
        if(locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)) {
            return rely.getString("result")
        }
        return null
    }

    fun putConfig(locationManager: LocationManager, context: Context): Boolean {
        if (!::randomKey.isInitialized) {
            return false
        }

        FakeLoc.altitude = context.altitude
        FakeLoc.speed = context.speed
        FakeLoc.accuracy = context.accuracy
        FakeLoc.enableDebugLog = context.debug
        FakeLoc.disableGetCurrentLocation = context.disableGetCurrentLocation
        FakeLoc.disableRegisterLocationListener = context.disableRegisterLocationListener
        FakeLoc.disableFusedLocation = context.disableFusedProvider
        FakeLoc.needDowngradeToCdma = context.needDowngradeToCdma
        FakeLoc.minSatellites = context.minSatelliteCount
        FakeLoc.enableAGPS = context.enableAGPS
        FakeLoc.enableNMEA = context.enableNMEA
        FakeLoc.disableRequestGeofence = !context.enableRequestGeofence
        FakeLoc.disableGetFromLocation = !context.enableGetFromLocation
        FakeLoc.enableMockStep = context.enableStepMock
        FakeLoc.stepCadence = context.stepCadence
        FakeLoc.enableMockWifi = context.disableWifiScan

        val rely = Bundle()
        rely.putString("command_id", "put_config")
        rely.putDouble("altitude", FakeLoc.altitude)
        rely.putDouble("speed", FakeLoc.speed)
        rely.putFloat("accuracy", FakeLoc.accuracy)
        rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
        rely.putBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
        rely.putBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
        rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
        rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
        rely.putInt("min_satellites", FakeLoc.minSatellites)
        rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
        rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
        rely.putBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
        rely.putBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)
        rely.putBoolean("enable_step_mock", FakeLoc.enableMockStep)
        rely.putFloat("step_cadence", FakeLoc.stepCadence)
        rely.putBoolean("enable_mock_wifi", FakeLoc.enableMockWifi)

        return locationManager.sendExtraCommand(PROVIDER_NAME, randomKey, rely)
    }

    fun isServiceInit(): Boolean {
        return ::randomKey.isInitialized
    }


    private fun startLoopBroadcastLocation(locationManager: LocationManager){
        val appContext = Awalocation.appContext

        if(isRunning) return
        if(!appContext.loopBroadcastlocation) return

        isRunning=true
        loopThread=Thread{
            Log.d("MockServiceHelper","loopBoardcast: Start")
            while(isRunning){
                try {
                    broadcastLocation(locationManager)
                    Thread.sleep(appContext.reportDuration.toLong().coerceAtLeast(20L))
                }catch (e:InterruptedException){
                    if (FakeLoc.enableDebugLog) {
                        Log.d("MockServiceHelper","loopBoardcast: Stop")
                    }
                    break
                }
            }
        }
        loopThread!!.start()
    }

    private fun stopLoopBroadcastLocation(){
        isRunning =false
        loopThread?.interrupt()
        loopThread = null
    }


    fun loadAwalocationLibrary(context: Context): Boolean {
        if (!ShellUtils.hasRoot()) return false

        val soDir = File("/data/local/awalocation-lib")
        val soFile = File(soDir, "libawalocation.so")
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"

        runCatching {
            var sourceSo: File? = null
            val apkSoFile = File(context.applicationInfo.nativeLibraryDir, "libawalocation.so")
            if (apkSoFile.exists() && apkSoFile.canRead()) {
                sourceSo = apkSoFile
            } else {
                val apkPath = context.applicationInfo.sourceDir
                val apkFile = java.util.zip.ZipFile(apkPath)
                val entry = apkFile.getEntry("lib/$abi/libawalocation.so")
                if (entry != null) {
                    val cacheSo = File(context.cacheDir, "libawalocation.so")
                    apkFile.getInputStream(entry).use { input ->
                        cacheSo.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    sourceSo = cacheSo
                }
                apkFile.close()
            }

            if (sourceSo != null && sourceSo.exists()) {
                ShellUtils.executeCommand("mkdir -p ${soDir.absolutePath}")
                ShellUtils.executeCommand("chmod 777 ${soDir.absolutePath}")
                ShellUtils.executeCommand("chcon u:object_r:system_file:s0 ${soDir.absolutePath}")
                ShellUtils.executeCommand("cp ${sourceSo.absolutePath} ${soFile.absolutePath}")
                ShellUtils.executeCommand("chmod 777 ${soFile.absolutePath}")
                ShellUtils.executeCommand("chcon u:object_r:system_file:s0 ${soFile.absolutePath}")
                Log.i("MockServiceHelper", "Deployed libawalocation.so to ${soFile.absolutePath}")
            }
        }.onFailure {
            Log.w("MockServiceHelper", "Failed to deploy awalocation library", it)
        }

        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        val result = loadLibrary(lm, soFile.absolutePath)
        Log.d("MockServiceHelper", "load awalocation library result: $result")
        return result == "success"
    }
}