package com.nanci.awalocation.xposed

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import com.nanci.awalocation.dobby.Dobby
import com.nanci.awalocation.xposed.hooks.LocationServiceHook
import com.nanci.awalocation.xposed.hooks.sensor.StepSensorHook
import com.nanci.awalocation.xposed.utils.FakeLoc
import com.nanci.awalocation.xposed.utils.BinderUtils
import com.nanci.awalocation.xposed.utils.Logger
import java.io.File
import java.util.Collections
import kotlin.random.Random

object RemoteCommandHandler {
    private val proxyBinders by lazy { Collections.synchronizedList(arrayListOf<IBinder>()) }
    private val needProxyCmd = arrayOf(
        "start", "stop", "set_speed_amp", "set_altitude", "set_speed",
        "update_location", "set_bearing", "move", "put_config",
        "start_gnss_mock", "stop_gnss_mock", "start_wifi_mock", "stop_wifi_mock",
        "start_step_mock", "stop_step_mock", "set_step_cadence"
    )
    internal val randomKey by lazy { "awalocation_" + Random.nextDouble() }

    fun ensureNativeLibraryLoaded(): Boolean {
        if (Dobby.isLoaded) return true
        val candidates = listOf(
            java.io.File("/data/local/awalocation-lib/libawalocation.so")
        )
        for (stagedSo in candidates) {
            if (stagedSo.exists() && stagedSo.canRead()) {
                try {
                    System.load(stagedSo.absolutePath)
                    Dobby.isLoaded = true
                    Logger.info("Loaded libawalocation.so from ${stagedSo.absolutePath}")
                    resolveSystemSensorHandles()
                    return true
                } catch (e: Throwable) {
                    Logger.warn("Failed to load libawalocation.so from ${stagedSo.absolutePath}: ${e.message}")
                }
            }
        }
        return false
    }

    fun resolveSystemSensorHandles() {
        kotlin.runCatching {
            val atClz = Class.forName("android.app.ActivityThread")
            val at = atClz.getMethod("currentActivityThread").invoke(null) ?: return@runCatching
            val ctx = atClz.getMethod("getSystemContext").invoke(at) as? android.content.Context ?: return@runCatching
            val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as? android.hardware.SensorManager ?: return@runCatching
            val getHandle = android.hardware.Sensor::class.java.getDeclaredMethod("getHandle").apply { isAccessible = true }
            val sc = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_COUNTER)
            val sd = sm.getDefaultSensor(android.hardware.Sensor.TYPE_STEP_DETECTOR)
            val counterHandle = (sc?.let { getHandle.invoke(it) as? Int }) ?: -1
            val detectorHandle = (sd?.let { getHandle.invoke(it) as? Int }) ?: -1
            Logger.info("resolveSystemSensorHandles: counter=$counterHandle, detector=$detectorHandle")
            if (counterHandle >= 0 || detectorHandle >= 0) {
                Dobby.setStepSensorHandles(counterHandle, detectorHandle)
            }
        }.onFailure {
            Logger.warn("resolveSystemSensorHandles failed: ${it.message}")
        }
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    fun handleInstruction(command: String, rely: Bundle): Boolean {
        // Exchange key -> returns a random key -> is used to verify that it is the AwalocationManager
        if (command == "exchange_key") {
            val userId = BinderUtils.getCallerUid()
            if (BinderUtils.isLocationProviderEnabled(userId)) {
                rely.putString("key", randomKey)
                return true
            }
            // Go back and see if the instruction has been processed to prevent it from being detected by others
        } else if (command != randomKey) {
            return false
        }
        val commandId = rely.getString("command_id") ?: return false

        kotlin.runCatching {
            if (proxyBinders.isNotEmpty() && needProxyCmd.any { it == commandId }) {
                proxyBinders.removeIf {
                    if (it.isBinderAlive && it.pingBinder()) {
                        val data = Parcel.obtain()
                        data.writeBundle(rely)
                        it.transact(1, data, null, 0)
                        data.recycle()
                        false
                    } else true
                }
            }
        }.onFailure {
            Logger.error("Failed to transact with proxyBinder", it)
        }

        if (FakeLoc.enableDebugLog) {
            Logger.debug("commandId=$commandId, rely=$rely")
        }

        when (commandId) {
            "set_proxy" -> {
                Logger.info("SubProxyBinder: ${rely.getBinder("proxy")} from ${BinderUtils.getUidPackageNames()}!")
                rely.getBinder("proxy")?.let {
                    proxyBinders.add(it)
                }
                return true
            }
            "start" -> {
                val speed = rely.getDouble("speed", FakeLoc.speed)
                val altitude = rely.getDouble("altitude", FakeLoc.altitude)
                val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)

                FakeLoc.enable = true
                if (FakeLoc.enableMockStep) {
                    ensureNativeLibraryLoaded()
                }
                if (Dobby.isLoaded) {
                    Dobby.setStatus(true)
                    Dobby.setMocking(true)
                    if (FakeLoc.enableMockStep) {
                        val spm = if (FakeLoc.stepCadence > 0f) FakeLoc.stepCadence else (FakeLoc.speed * 35.0 + 70.0).toFloat().coerceIn(60f, 220f)
                        Dobby.init(spm, mode = 0, scheme = 0, enabled = true)
                        Dobby.setRouteSimulation(true, spm, mode = 0)
                        Dobby.setStepSimEnabled(true)
                    }
                }

                FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy

                return true
            }
            "stop" -> {
                FakeLoc.enable = false
                FakeLoc.hasBearings = false
                if (Dobby.isLoaded) {
                    Dobby.setStatus(false)
                    Dobby.setMocking(false)
                    Dobby.reset()
                }
                return true
            }
            "is_start" -> {
                rely.putBoolean("is_start", FakeLoc.enable)
                return true
            }
            "start_gnss_mock" -> {
                FakeLoc.enableMockGnss = true
                if (FakeLoc.isSystemServerProcess) {
                    LocationServiceHook.startGnssMockDaemon()
                    LocationServiceHook.callOnGnssStatusChanged()
                }
                return true
            }
            "stop_gnss_mock" -> {
                FakeLoc.enableMockGnss = false
                return true
            }
            "is_gnss_start" -> {
                rely.putBoolean("is_gnss_start", FakeLoc.enableMockGnss)
                return true
            }
            "is_wifi_mock_start" -> {
                rely.putBoolean("is_wifi_mock_start", FakeLoc.enableMockWifi)
                return true
            }
            "start_wifi_mock" -> {
                FakeLoc.enableMockWifi = true
                return true
            }
            "stop_wifi_mock" -> {
                FakeLoc.enableMockWifi = false
                return true
            }
            "start_step_mock" -> {
                FakeLoc.stepCadence = rely.getFloat("cadence", FakeLoc.stepCadence)
                FakeLoc.enableMockStep = true
                ensureNativeLibraryLoaded()
                val spm = if (FakeLoc.stepCadence > 0f) FakeLoc.stepCadence else (FakeLoc.speed * 35.0 + 70.0).toFloat().coerceIn(60f, 220f)
                if (Dobby.isLoaded) {
                    Dobby.init(spm, mode = 0, scheme = 0, enabled = true)
                    Dobby.setRouteSimulation(true, spm, mode = 0)
                    Dobby.setStepSimEnabled(true)
                }
                StepSensorHook.wakeFeeder()
                return true
            }
            "stop_step_mock" -> {
                FakeLoc.enableMockStep = false
                if (Dobby.isLoaded) {
                    Dobby.setStepSimEnabled(false)
                    Dobby.setRouteSimulation(false, FakeLoc.stepCadence, mode = 0)
                }
                StepSensorHook.stopFeeder()
                return true
            }
            "is_step_start" -> {
                rely.putBoolean("is_step_start", FakeLoc.enableMockStep)
                return true
            }
            "set_step_cadence" -> {
                val cadence = rely.getFloat("cadence", 0f)
                FakeLoc.stepCadence = if (cadence < 0f) 0f else cadence
                if (FakeLoc.enableMockStep) {
                    ensureNativeLibraryLoaded()
                }
                val spm = if (FakeLoc.stepCadence > 0f) FakeLoc.stepCadence else (FakeLoc.speed * 35.0 + 70.0).toFloat().coerceIn(60f, 220f)
                if (Dobby.isLoaded && FakeLoc.enableMockStep) {
                    Dobby.init(spm, mode = 0, scheme = 0, enabled = true)
                    Dobby.setRouteSimulation(true, spm, mode = 0)
                    Dobby.setStepSimEnabled(true)
                }
                StepSensorHook.wakeFeeder()
                return true
            }
            "get_location" -> {
                rely.putDouble("lat", FakeLoc.latitude)
                rely.putDouble("lon", FakeLoc.longitude)
                return true
            }
            "get_listener_size" -> {
                rely.putInt("size", LocationServiceHook.locationListeners.size)
                return true
            }
            "get_speed" -> {
                rely.putDouble("speed", FakeLoc.speed)
                return true
            }
            "get_bearing" -> {
                rely.putDouble("bearing", FakeLoc.bearing)
                return true
            }
            "get_altitude" -> {
                rely.putDouble("altitude", FakeLoc.altitude)
                return true
            }
            "set_speed_amp" -> {
                val speedAmplitude = rely.getDouble("speed_amplitude", 1.0)
                FakeLoc.speedAmplitude = speedAmplitude
                return true
            }
            "set_altitude" -> {
                val altitude = rely.getDouble("altitude", 0.0)
                FakeLoc.altitude = altitude
                return true
            }
            "set_speed" -> {
                val speed = rely.getDouble("speed", 0.0)
                FakeLoc.speed = speed
                return true
            }
            "set_bearing" -> {
                val bearing = rely.getDouble("bearing", 0.0)
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
                return true
            }
            "move" -> {
                val distance = rely.getDouble("n", 0.0)
                if (distance == 0.0) return true
                val bearing = rely.getDouble("bearing", 0.0)
                val newLoc = FakeLoc.moveLocation(
                    n = distance,
                    angle = bearing
                )
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("move: distance=$distance, bearing=$bearing, newLoc=$newLoc")
                }
                FakeLoc.bearing = bearing
                FakeLoc.hasBearings = true
                return updateCoordinate(newLoc.first, newLoc.second).also {
                    if (FakeLoc.isSystemServerProcess) LocationServiceHook.callOnLocationChanged()
                }
            }
            "update_location" -> {
                val mode = rely.getString("mode")
                var newLat = rely.getDouble("lat", 0.0)
                var newLon = rely.getDouble("lon", 0.0)
                when(mode) {
                    "+" -> {
                        newLat += FakeLoc.latitude
                        newLon += FakeLoc.longitude
                        return updateCoordinate(newLat, newLon)
                    }
                    "-" -> {
                        newLat = FakeLoc.latitude - newLat
                        newLon = FakeLoc.longitude - newLon
                        return updateCoordinate(newLat, newLon)
                    }
                    "*" -> {
                        newLat *= FakeLoc.latitude
                        newLon *= FakeLoc.longitude
                        return updateCoordinate(newLat, newLon)
                    }
                    "/" -> {
                        if (newLat == 0.0 || newLon == 0.0) {
                            return false
                        }
                        newLat /= FakeLoc.latitude
                        newLon /= FakeLoc.longitude
                        return updateCoordinate(newLat, newLon)
                    }
                    "=" -> {
                        return updateCoordinate(newLat, newLon)
                    }
                    "random" -> {
                        return updateCoordinate(Random.nextDouble(-90.0, 90.0), Random.nextDouble(-180.0, 180.0))
                    }
                }
                return true
            }
            "put_config" -> {
                val enable = rely.getBoolean("enable", FakeLoc.enable)
                val speed = rely.getDouble("speed", FakeLoc.speed)
                val altitude = rely.getDouble("altitude", FakeLoc.altitude)
                val accuracy = rely.getFloat("accuracy", FakeLoc.accuracy)
                val enableDebugLog = rely.getBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                val disableGetCurrentLocation = rely.getBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
                val disableRegisterLocationListener = rely.getBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
                val disableFusedLocation = rely.getBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                val needDowngradeToCdma = rely.getBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                var minSatellites = rely.getInt("min_satellites", 12)
                if (minSatellites < 0) {
                    minSatellites = 12
                }

                val enableAGPS = rely.getBoolean("enable_agps", FakeLoc.enableAGPS)
                val enableNMEA = rely.getBoolean("enable_nmea", FakeLoc.enableNMEA)
                val disableRequestGeofence = rely.getBoolean("disable_request_geofence", FakeLoc.disableRequestGeofence)
                val disableGetFromLocation = rely.getBoolean("disable_get_from_location", FakeLoc.disableGetFromLocation)
                val enableMockStep = rely.getBoolean("enable_step_mock", FakeLoc.enableMockStep)
                val stepCadence = rely.getFloat("step_cadence", FakeLoc.stepCadence)
                val enableMockGnss = rely.getBoolean("enable_gnss_mock", FakeLoc.enableMockGnss)
                val enableMockWifi = rely.getBoolean("enable_mock_wifi", FakeLoc.enableMockWifi)

                FakeLoc.enable = enable
                FakeLoc.speed = speed
                FakeLoc.altitude = altitude
                FakeLoc.accuracy = accuracy
                FakeLoc.enableDebugLog = enableDebugLog
                FakeLoc.disableGetCurrentLocation = disableGetCurrentLocation
                FakeLoc.disableRegisterLocationListener = disableRegisterLocationListener
                FakeLoc.disableFusedLocation = disableFusedLocation
                FakeLoc.needDowngradeToCdma = needDowngradeToCdma
                FakeLoc.minSatellites = minSatellites
                FakeLoc.enableAGPS = enableAGPS
                FakeLoc.enableNMEA = enableNMEA
                FakeLoc.disableRequestGeofence = disableRequestGeofence
                FakeLoc.disableGetFromLocation = disableGetFromLocation
                FakeLoc.enableMockStep = enableMockStep
                FakeLoc.stepCadence = if (stepCadence < 0f) 0f else stepCadence
                val spm = if (FakeLoc.stepCadence > 0f) FakeLoc.stepCadence else (FakeLoc.speed * 35.0 + 70.0).toFloat().coerceIn(60f, 220f)
                if (enableMockStep) {
                    ensureNativeLibraryLoaded()
                    if (Dobby.isLoaded) {
                        Dobby.init(spm, mode = 0, scheme = 0, enabled = true)
                        Dobby.setRouteSimulation(true, spm, mode = 0)
                        Dobby.setStepSimEnabled(true)
                        Dobby.setMocking(FakeLoc.enable)
                    }
                    StepSensorHook.wakeFeeder()
                } else {
                    if (Dobby.isLoaded) {
                        Dobby.setStepSimEnabled(false)
                        Dobby.setRouteSimulation(false, spm, mode = 0)
                    }
                    StepSensorHook.stopFeeder()
                }
                FakeLoc.enableMockGnss = enableMockGnss
                FakeLoc.enableMockWifi = enableMockWifi
                if (enableMockGnss && FakeLoc.isSystemServerProcess) {
                    LocationServiceHook.startGnssMockDaemon()
                    LocationServiceHook.callOnGnssStatusChanged()
                }
                return true
            }
            "sync_config" -> {
                rely.putBoolean("enable", FakeLoc.enable)
                rely.putBoolean("enable_gnss_mock", FakeLoc.enableMockGnss)
                rely.putDouble("latitude", FakeLoc.latitude)
                rely.putDouble("longitude", FakeLoc.longitude)
                rely.putDouble("altitude", FakeLoc.altitude)
                rely.putDouble("speed", FakeLoc.speed)
                rely.putFloat("accuracy", FakeLoc.accuracy)
                rely.putDouble("speed_amplitude", FakeLoc.speedAmplitude)
                rely.putBoolean("has_bearings", FakeLoc.hasBearings)
                rely.putDouble("bearing", FakeLoc.bearing)
                rely.putParcelable("last_location", FakeLoc.lastLocation)
                rely.putBoolean("enable_log", FakeLoc.enableLog)
                rely.putBoolean("enable_debug_log", FakeLoc.enableDebugLog)
                rely.putBoolean("disable_get_current_location", FakeLoc.disableGetCurrentLocation)
                rely.putBoolean("disable_register_location_listener", FakeLoc.disableRegisterLocationListener)
                rely.putBoolean("disable_fused_location", FakeLoc.disableFusedLocation)
                rely.putBoolean("enable_agps", FakeLoc.enableAGPS)
                rely.putBoolean("enable_nmea", FakeLoc.enableNMEA)
                rely.putBoolean("hide_mock", FakeLoc.hideMock)
                rely.putBoolean("hook_wifi", FakeLoc.hookWifi)
                rely.putBoolean("need_downgrade_to_2g", FakeLoc.needDowngradeToCdma)
                rely.putBoolean("enable_step_mock", FakeLoc.enableMockStep)
                rely.putFloat("step_cadence", FakeLoc.stepCadence)
                rely.putBoolean("enable_mock_wifi", FakeLoc.enableMockWifi)
                return true
            }
            "broadcast_location" -> {
                LocationServiceHook.callOnLocationChanged()
                return true
            }
            "load_library" -> {
                val path = rely.getString("path") ?: return false
                val allowedFile = runCatching {
                    File("/data/local/awalocation-lib/libawalocation.so").canonicalFile
                }.getOrNull() ?: return false
                val requestedFile = runCatching { File(path).canonicalFile }.getOrNull()
                if (requestedFile != allowedFile) {
                    Logger.warn("Rejected load_library path: $path")
                    rely.putString("result", "rejected path")
                    return false
                }

                if (Dobby.isLoaded) {
                    resolveSystemSensorHandles()
                    rely.putString("result", "success")
                    return true
                }
                runCatching {
                    System.load(allowedFile.path)
                }.onSuccess {
                    rely.putString("result", "success")
                    Dobby.isLoaded = true
                    resolveSystemSensorHandles()
                }.onFailure {
                    rely.putString("result", it.stackTraceToString())
                }

                if (Dobby.isLoaded) {
                    Dobby.setStatus(FakeLoc.enable)
                    Dobby.setMocking(FakeLoc.enable)
                    if (FakeLoc.enableMockStep) {
                        val spm = if (FakeLoc.stepCadence > 0f) FakeLoc.stepCadence else (FakeLoc.speed * 35.0 + 70.0).toFloat().coerceIn(60f, 220f)
                        Dobby.init(spm, mode = 0, scheme = 0, enabled = true)
                        Dobby.setRouteSimulation(true, spm, mode = 0)
                        Dobby.setStepSimEnabled(true)
                    }
                }

                return true
            }
            else -> return false
        }
    }

//    private var hasHookSensor = false
//
//    private fun tryHookSensor(classLoader: ClassLoader = FakeLoc::class.java.classLoader!!) {
//        if (hasHookSensor || proxyBinders.isNullOrEmpty()) return
//
//
//
//        hasHookSensor = true
//    }

//    private fun generateLocation(): Location {
//        val (location, realLocation) = if (FakeLocationConfig.lastLocation != null) {
//            (FakeLocationConfig.lastLocation!! to true)
//        } else {
//            (Location(LocationManager.GPS_PROVIDER) to false)
//        }
//
//        return LocationServiceProxyHook.injectLocation(location, realLocation)
//    }

    private fun updateCoordinate(newLat: Double, newLon: Double): Boolean {
        if (newLat in -90.0..90.0 && newLon in -180.0..180.0) {
            FakeLoc.latitude = newLat
            FakeLoc.longitude = newLon
            return true
        } else {
            Logger.error("Invalid latitude or longitude: $newLat, $newLon")
            return false
        }
    }
}