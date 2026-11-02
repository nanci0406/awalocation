package com.nanci.awalocation.ext

import android.content.Context
import android.provider.Settings

fun Context.drawOverOtherAppsEnabled(): Boolean {
    return Settings.canDrawOverlays(this)
}
