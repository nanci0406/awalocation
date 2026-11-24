package com.nanci.awalocation.xposed.hooks

import android.location.Location
import android.location.LocationManager
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import com.nanci.awalocation.xposed.BaseLocationHook
import com.nanci.awalocation.xposed.hooks.blindhook.BlindHookLocation
import com.nanci.awalocation.xposed.utils.FakeLoc
import com.nanci.awalocation.xposed.utils.Logger
import com.nanci.awalocation.xposed.utils.hookAllMethodsAfter
import com.nanci.awalocation.xposed.utils.hookAllMethodsBefore
import com.nanci.awalocation.xposed.utils.toClassOrThrow

object BasicLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
//        val hookSetLatitude = object: XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//                if (!FakeLocationConfig.enable) return
//
//            }
//        }
//        val hookSetLongitude = object: XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//
//
//            }
//        }
//        XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "setLatitude", Double::class.java, hookSetLatitude)
//        XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "setLongitude", Double::class.java, hookSetLongitude)

        kotlin.runCatching {
            val cLocationResult = "android.location.LocationResult".toClassOrThrow(classLoader)
            BlindHookLocation(cLocationResult, classLoader)

            cLocationResult.hookAllMethodsAfter("asList") {
                if (!FakeLoc.enable) return@hookAllMethodsAfter

                val locations = result as? List<*> ?: return@hookAllMethodsAfter
                result = locations.map { (it as? Location)?.let(::injectLocation) ?: it }
            }

            cLocationResult.hookAllMethodsBefore("writeToParcel") {
                if (!FakeLoc.enable) return@hookAllMethodsBefore

                val locationResult = thisObject
                val mLocationsField = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
                if (mLocationsField == null) {
                    Logger.error("Failed to find mLocations in LocationResult")
                    return@hookAllMethodsBefore
                }
                mLocationsField.isAccessible = true
                val mLocations = mLocationsField.get(locationResult) as? ArrayList<*>
                    ?: return@hookAllMethodsBefore

                val originLocation = mLocations.firstOrNull() as? Location
                    ?: Location(LocationManager.GPS_PROVIDER)
                val location = injectLocation(originLocation)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    location.isMock = false
                }
                mLocationsField.set(locationResult, arrayListOf(location))
            }
        }.onFailure {
           Logger.error("Failed to hook LocationResult", it)
        }

        Location::class.java.hookAllMethodsBefore("set") {
            if (!FakeLoc.enable) return@hookAllMethodsBefore
            val location = args.getOrNull(0) as? Location ?: return@hookAllMethodsBefore
            args[0] = injectLocation(location)
        }
//        if (FakeLocationConfig.DEBUG) {
//            // Track the invocation of AutoNavi map system services
//            val cBundle = XposedHelpers.findClass("android.os.Bundle", classLoader)
//            XposedBridge.hookAllMethods(cBundle, "putInt", object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    val key = param.args[0] as? String
//                    val value = param.args[1] as Int
//
//                    if (key == "amap" || key == "resubtype" || key == "maxCn0") {
//                        XposedBridge.log(RuntimeException())
//                    }
//                }
//            })
//            XposedBridge.hookAllMethods(Location::class.java, "setExtras", object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    XposedBridge.log(RuntimeException())
//                }
//            })
//        }


    }
}