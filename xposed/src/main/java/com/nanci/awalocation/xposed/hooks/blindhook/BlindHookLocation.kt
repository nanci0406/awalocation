package com.nanci.awalocation.xposed.hooks.blindhook

import android.location.Location
import com.nanci.awalocation.xposed.BaseLocationHook
import com.nanci.awalocation.xposed.utils.FakeLoc
import com.nanci.awalocation.xposed.utils.Logger

object BlindHookLocation: BaseLocationHook() {
    operator fun invoke(clazz: Class<*>, classLoader: ClassLoader): Int {
        return BlindHook(clazz, classLoader) { method, location: Location? ->
            if (location == null || !FakeLoc.enable) return@BlindHook location

            val newLoc = injectLocation(location)

            if (FakeLoc.enableDebugLog) {
                Logger.debug("${method.name} injected: $newLoc")
            }

            newLoc
        }
    }
}