package com.nanci.awalocation.xposed.hooks.gnss

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.nanci.awalocation.xposed.BaseLocationHook
import com.nanci.awalocation.xposed.hooks.LocationServiceHook
import com.nanci.awalocation.xposed.hooks.blindhook.BlindHookLocation
import com.nanci.awalocation.xposed.hooks.blindhook.BlindHookLocation.invoke
import com.nanci.awalocation.xposed.utils.FakeLoc
import com.nanci.awalocation.xposed.utils.Logger
import com.nanci.awalocation.xposed.utils.beforeHook
import com.nanci.awalocation.xposed.utils.hookAllMethods
import com.nanci.awalocation.xposed.utils.onceHookAllMethod
import java.lang.reflect.Method
import java.util.Collections

object GnssHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        hookFrameworkGnss(classLoader)
        hookHALGnss(classLoader)
    }

    private fun hookFrameworkGnss(classLoader: ClassLoader) {
        // Android 12+ 包名迁移至 com.android.server.location.gnss.GnssManagerService
        val cGnssManagerService =
            XposedHelpers.findClassIfExists("com.android.server.location.gnss.GnssManagerService", classLoader)
                ?: XposedHelpers.findClassIfExists("com.android.server.location.GnssManagerService", classLoader)

        // 拦截 Android 12+ GnssStatusProvider 的真实硬件上报，防止室内 0 颗卫星覆盖模拟星图
        XposedHelpers.findClassIfExists("com.android.server.location.gnss.GnssStatusProvider", classLoader)?.let { cGnssStatusProvider ->
            cGnssStatusProvider.onceHookAllMethod("onReportSvStatus", beforeHook {
                if (!FakeLoc.enableMockGnss || args.isEmpty()) return@beforeHook
                val mockStatus = LocationServiceHook.buildCurrentMockGnssStatus()
                if (mockStatus != null && args[0] is android.location.GnssStatus) {
                    args[0] = mockStatus
                }
            })
        }

        if (cGnssManagerService == null) return

        val doNothingMethod = object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                if (param == null || param.args.isEmpty()) return

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("doNothingMethod: ${param.method.name}")
                }

                if (FakeLoc.enableMockGnss && !FakeLoc.enableAGPS) {
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("${param.method.name}: disable")
                    }
                    val returnType = (param.method as? Method)?.returnType
                    param.result = when (returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        else -> null
                    }
                }
            }
        }

        // AGPS Listener?
        XposedBridge.hookAllMethods(cGnssManagerService, "addGnssAntennaInfoListener", doNothingMethod)
        XposedBridge.hookAllMethods(cGnssManagerService, "addGnssMeasurementsListener", doNothingMethod)
        XposedBridge.hookAllMethods(cGnssManagerService, "addGnssNavigationMessageListener", doNothingMethod)
        XposedBridge.hookAllMethods(cGnssManagerService, "removeGnssAntennaInfoListener", doNothingMethod)
        XposedBridge.hookAllMethods(cGnssManagerService, "removeGnssMeasurementsListener", doNothingMethod)
        XposedBridge.hookAllMethods(cGnssManagerService, "removeGnssNavigationMessageListener", doNothingMethod)

        run {
            val hookedGnssCallback = Collections.synchronizedSet(HashSet<String>())
            val unhooks = cGnssManagerService.declaredMethods.filter {
                it.name == "registerGnssNmeaCallback" && it.parameterTypes.size > 1
            }.map { method ->
                XposedBridge.hookMethod(method, object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam?) {
                        if (param == null || param.args.isEmpty() || param.args[0] == null) return
                        val classListener = param.args[0].javaClass

                        if (hookedGnssCallback.contains(classListener.name)) return
                        hookedGnssCallback.add(classListener.name)

                        if (FakeLoc.enableDebugLog)
                            Logger.debug("registerGnssNmeaCallback: $classListener")
                        kotlin.runCatching {
                            XposedHelpers.findAndHookMethod(classListener, "onNmeaReceived", Long::class.java, String::class.java, object: XC_MethodHook() {
                                override fun beforeHookedMethod(param: MethodHookParam) {
                                    if (FakeLoc.enableMockGnss && !FakeLoc.enableAGPS) {
                                        if (FakeLoc.enableDebugLog)
                                            Logger.debug("GnssManagerService.onNmeaReceived: disable")
                                        param.result = null
                                        return
                                    }
                                    if (!FakeLoc.enable) return

                                    val nmea = param.args.getOrNull(1) as? String ?: return
                                    param.args[1] = injectNMEA(nmea) ?: nmea
                                }
                            })
                        }.onFailure {
                            Logger.error("[onNmeaReceived hook failed: ${it.message} from GnssManagerService, please issue?", it)
                        }
                    }
                })
            }

            if (FakeLoc.enableDebugLog)
                Logger.debug("found ${unhooks.size} registerGnssNmeaCallback")
        }
    }

    private fun hookHALGnss(classLoader: ClassLoader) {
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V1_0.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V1_1.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V2_1.IGnss\$Stub", classLoader))
//        hookGnssHALAnyVersion(XposedHelpers.findClassIfExists("android.hardware.gnss.V2_0.IGnss\$Stub", classLoader))
        var hookedGnssLocationProvider = 0
        val cGnssLocationProviderImpl = XposedHelpers.findClassIfExists("com.android.server.location.gnss.GnssLocationProviderImpl", classLoader)
        if (cGnssLocationProviderImpl != null) {
            BlindHookLocation(cGnssLocationProviderImpl, classLoader)
            hookedGnssLocationProvider = 1
        }

        if (hookedGnssLocationProvider == 0) {
            val cGnssLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.gnss.GnssLocationProvider", classLoader)
            if (cGnssLocationProvider != null) {
                BlindHookLocation(cGnssLocationProvider, classLoader)
                hookedGnssLocationProvider = 2
            }
        }

        if (hookedGnssLocationProvider == 0) {
            Logger.error("Failed to find GnssLocationProvider!")
        } else {
            Logger.info("GnssLocationProvider done, status: $hookedGnssLocationProvider")
        }

        val cGnssNative = XposedHelpers.findClassIfExists("com.android.server.location.gnss.hal.GnssNative", classLoader)
        if (cGnssNative != null) {
            BlindHookLocation(cGnssNative, classLoader)

            cGnssNative.hookAllMethods("reportLocationBatch", beforeHook {
                if (FakeLoc.enable && args.isNotEmpty() && args[0] is Array<*>) {
                    @Suppress("UNCHECKED_CAST")
                    val locations = args[0] as? Array<Location> ?: return@beforeHook
                    locations.forEachIndexed { index, location ->
                        locations[index] = injectLocation(location)
                    }
                }
            })

            cGnssNative.onceHookAllMethod("reportSvStatus", beforeHook {
                if (!FakeLoc.enableMockGnss || args.isEmpty()) return@beforeHook
                if (args[0] is android.location.GnssStatus) {
                    LocationServiceHook.buildCurrentMockGnssStatus()?.let {
                        args[0] = it
                    }
                }
            })
        } else {
            Logger.error("GnssNative not found")
        }
    }

    private fun hookGnssHALAnyVersion(cIGnssStub: Class<*>?) {
        if (cIGnssStub != null) {
            cIGnssStub.hookAllMethods("onTransact", beforeHook {
                val clazz = thisObject.javaClass
                clazz.onceHookAllMethod("injectBestLocation", beforeHook {
                    if (FakeLoc.enable) {
                        result = null
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("injectLocation: $method")
                    }
                })

                clazz.onceHookAllMethod("injectLocation", beforeHook {
                    if (FakeLoc.enable) {
                        result = null
                    }

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("injectLocation: $method")
                    }
                })
            })
        } else {
            Logger.error("IGnss\$Stub not found")
        }
    }
}