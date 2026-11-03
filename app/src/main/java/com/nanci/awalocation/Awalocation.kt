package com.nanci.awalocation

import android.app.Application
import android.content.Context
import com.amap.api.location.AMapLocationClient
import com.amap.api.maps.MapsInitializer
import com.amap.api.services.core.ServiceSettings
import com.tencent.bugly.crashreport.CrashReport
import com.nanci.awalocation.android.Bugly

class Awalocation: Application() {

    override fun onCreate() {
        super.onCreate()

        // 高德 SDK 隐私合规（必须在任何高德 SDK 调用前执行）
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        ServiceSettings.updatePrivacyShow(this, true, true)
        ServiceSettings.updatePrivacyAgree(this, true)
        MapsInitializer.initialize(this)

        CrashReport.initCrashReport(applicationContext)

        CrashReport.setUserId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceModel(applicationContext, Bugly.getDeviceModel())
        CrashReport.setCollectPrivacyInfo(applicationContext, true)

        appContext = applicationContext

        //CrashReport.setAllThreadStackEnable(applicationContext, true, true)
    }

    companion object {
        lateinit var appContext: Context
        val app: Context get() = appContext
    }
}