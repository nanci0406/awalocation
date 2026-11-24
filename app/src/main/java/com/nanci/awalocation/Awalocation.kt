package com.nanci.awalocation

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatDelegate
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.DynamicColorsOptions
import com.tencent.bugly.crashreport.CrashReport
import com.nanci.awalocation.android.Bugly
import com.nanci.awalocation.ext.dynamicColorEnabled
import com.nanci.awalocation.ext.themeMode

class Awalocation : Application() {

    override fun onCreate() {
        super.onCreate()

        AppCompatDelegate.setDefaultNightMode(themeMode)

        // Android 12+ 动态取色（可在设置中切换，关闭时回退至默认蓝白主题）
        DynamicColors.applyToActivitiesIfAvailable(
            this,
            DynamicColorsOptions.Builder()
                .setPrecondition { activity, _ -> activity.dynamicColorEnabled }
                .build()
        )

        // 高德 SDK 隐私合规 + 强制 HTTPS（Android 9+ 默认禁止明文 HTTP，
        // 而高德 Search/Location SDK 默认协议为 HTTP，不切 HTTPS 会导致搜索和逆地理编码全部失败）
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)
        MapsInitializer.setProtocol(MapsInitializer.HTTPS)
        ServiceSettings.getInstance().protocol = ServiceSettings.HTTPS
        AMapLocationClientOption.setLocationProtocol(
            AMapLocationClientOption.AMapLocationProtocol.HTTPS
        )
        MapsInitializer.initialize(this)

        initBuglyIfConfigured()

        appContext = applicationContext
    }

    private fun initBuglyIfConfigured() {
        val appId = runCatching {
            val info = packageManager.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
            info.metaData?.getString("BUGLY_APPID")?.trim().orEmpty()
        }.getOrDefault("")
        if (appId.isEmpty()) return

        CrashReport.initCrashReport(applicationContext)
        CrashReport.setUserId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceModel(applicationContext, Bugly.getDeviceModel())
        CrashReport.setCollectPrivacyInfo(applicationContext, true)
    }

    companion object {
        lateinit var appContext: Context
        val app: Context get() = appContext
    }
}
