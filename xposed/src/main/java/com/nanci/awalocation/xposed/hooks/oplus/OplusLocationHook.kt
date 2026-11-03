package com.nanci.awalocation.xposed.hooks.oplus

import com.nanci.awalocation.xposed.BaseLocationHook
import com.nanci.awalocation.xposed.hooks.blindhook.BlindHookLocation.invoke
import com.nanci.awalocation.xposed.hooks.fused.ThirdPartyLocationHook

object OplusLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        ThirdPartyLocationHook(classLoader)
    }
}