@file:Suppress("LocalVariableName", "PrivateApi", "UNCHECKED_CAST")
package moe.fuqiuluo.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.IXposedHookZygoteInit
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import moe.fuqiuluo.xposed.hooks.LocationManagerHook
import moe.fuqiuluo.xposed.hooks.LocationServiceHook
import moe.fuqiuluo.xposed.hooks.fused.AndroidFusedLocationProviderHook
import moe.fuqiuluo.xposed.hooks.fused.ThirdPartyLocationHook
import moe.fuqiuluo.xposed.hooks.oplus.OplusLocationHook
import moe.fuqiuluo.xposed.hooks.telephony.miui.MiuiTelephonyManagerHook
import moe.fuqiuluo.xposed.hooks.sensor.StepSensorHook
import moe.fuqiuluo.xposed.hooks.sensor.SystemSensorManagerHook
import moe.fuqiuluo.xposed.hooks.telephony.TelephonyHook
import moe.fuqiuluo.xposed.hooks.wlan.WlanHook
import moe.fuqiuluo.xposed.utils.FakeLoc
import moe.fuqiuluo.xposed.utils.Logger

class FakeLocation: IXposedHookLoadPackage, IXposedHookZygoteInit {
    private lateinit var cServiceManager: Class<*> // android.os.ServiceManager
    private val mServiceManagerCache by lazy {
        kotlin.runCatching { cServiceManager.getDeclaredField("sCache") }.onSuccess {
            it.isAccessible = true
        }.getOrNull()
        // the field is not guaranteed to exist
    }

    /**
     * Called very early during startup of Zygote.
     * @param startupParam Details about the module itself and the started process.
     * @throws Throwable everything is caught, but will prevent further initialization of the module.
     */
    override fun initZygote(startupParam: IXposedHookZygoteInit.StartupParam?) {
        if(startupParam == null) return
        startupParam.modulePath?.let {
            FakeLoc.moduleApkPath = it
        }
    }

    /**
     * This method is called when an app is loaded. It's called very early, even before
     * [Application.onCreate] is called.
     * Modules can set up their app-specific hooks here.
     *
     * @param lpparam Information about the app.
     * @throws Throwable Everything the callback throws is caught and logged.
     */
    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam?) {
        val packageName = lpparam?.packageName ?: return
        val allowedSystemPackages = setOf(
            "android",
            "com.android.phone",
            "com.android.location.fused",
            "com.xiaomi.location.fused",
            "com.oplus.location"
        )
        if (packageName !in allowedSystemPackages) {
            handleTargetProcess(packageName, lpparam.classLoader)
            return
        }

        val systemClassLoader = (kotlin.runCatching {
            lpparam.classLoader.loadClass("android.app.ActivityThread")
                ?: Class.forName("android.app.ActivityThread")
        }.onFailure {
            Logger.error("Failed to find ActivityThread", it)
        }.getOrNull() ?: return)
            .getMethod("currentActivityThread")
            .invoke(null)
            .javaClass
            .getClassLoader()

        if (systemClassLoader == null) {
            Logger.error("Failed to get system class loader")
            return
        }

        if(System.getProperty("portal.injected_${lpparam.packageName}") == "true") {
            return
        } else {
            System.setProperty("portal.injected_${lpparam.packageName}", "true")
        }

        when (lpparam.packageName) {
            "com.android.phone" -> {
                Logger.info("Found com.android.phone")
                TelephonyHook(lpparam.classLoader)
                MiuiTelephonyManagerHook(lpparam.classLoader)
            }
            "android" -> {
                Logger.info("Debug Log Status: ${FakeLoc.enableDebugLog}")
                FakeLoc.isSystemServerProcess = true
                startFakeLocHook(systemClassLoader)
                TelephonyHook.hookSubOnTransact(lpparam.classLoader)
                WlanHook(systemClassLoader)
                AndroidFusedLocationProviderHook(lpparam.classLoader)
                SystemSensorManagerHook(lpparam.classLoader)

                ThirdPartyLocationHook(lpparam.classLoader)
            }
            "com.android.location.fused" -> {
                AndroidFusedLocationProviderHook(lpparam.classLoader)
            }
            "com.xiaomi.location.fused" -> {
                ThirdPartyLocationHook(lpparam.classLoader)
            }
            "com.oplus.location" -> {
                OplusLocationHook(lpparam.classLoader)
            }
        }
    }

    private fun startFakeLocHook(classLoader: ClassLoader) {
        cServiceManager = XposedHelpers.findClass("android.os.ServiceManager", classLoader)

        XposedHelpers.findClassIfExists("com.android.server.TelephonyRegistry", classLoader)?.let {
            TelephonyHook.hookTelephonyRegistry(it)
        } // for MUMU emulator

        val cLocationManager =
            XposedHelpers.findClass("android.location.LocationManager", classLoader)

        LocationServiceHook(classLoader)
        LocationManagerHook(cLocationManager)  // intrusive hooks
    }

    /**
     * 系统进程之外的目标进程：用户在 LSPosed 中把目标应用加入模块作用域后，
     * 为其提供步频传感器模拟。位置类 Hook 仍只在 system_server 内完成。
     */
    private fun handleTargetProcess(packageName: String, classLoader: ClassLoader) {
        if (packageName == SELF_PACKAGE) return
        if (packageName in TARGET_PROVIDER_PACKAGES) return
        if (packageName in SYSTEM_CRITICAL_PACKAGES) return
        if (packageName.startsWith("android.") || packageName.startsWith("com.android.")) return
        if (packageName.startsWith("com.google.android.")) return
        if (packageName.startsWith("com.miui.") || packageName.startsWith("com.xiaomi.")) return
        if (packageName.startsWith("com.oplus.") || packageName.startsWith("com.coloros.")) return
        if (packageName.startsWith("com.vivo.")) return
        if (packageName.startsWith("com.huawei.") || packageName.startsWith("com.honor.")) return
        if (System.getProperty("portal.injected_$packageName") == "true") return
        System.setProperty("portal.injected_$packageName", "true")

        Logger.info("Mocking step sensor in target process: $packageName")
        StepSensorHook.install(classLoader)
    }

    companion object {
        private const val SELF_PACKAGE = "com.nanci.awalocation"
        private val TARGET_PROVIDER_PACKAGES = arrayOf(
            "com.android.location.fused",
            "com.xiaomi.location.fused",
            "com.oplus.location"
        )
        private val SYSTEM_CRITICAL_PACKAGES = setOf(
            "com.android.systemui",
            "com.google.android.webview",
            "com.android.webview"
        )
    }
}