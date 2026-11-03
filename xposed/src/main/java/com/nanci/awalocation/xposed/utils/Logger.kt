package com.nanci.awalocation.xposed.utils

import de.robv.android.xposed.XposedBridge

object Logger {
    private fun isEnableLog(): Boolean {
        return FakeLoc.enableLog
    }

    fun info(msg: String) {
        if (isEnableLog()) {
            XposedBridge.log("[Awalocation] $msg")
        }
    }

    fun info(msg: String, throwable: Throwable) {
        if (isEnableLog()) {
            XposedBridge.log("[Awalocation] $msg: ${throwable.stackTraceToString()}")
        }
    }

    fun debug(msg: String) {
        XposedBridge.log("[Awalocation][DEBUG] $msg")
    }

    fun debug(msg: String, throwable: Throwable) {
        XposedBridge.log("[Awalocation][DEBUG] $msg: ${throwable.stackTraceToString()}")
    }

    fun error(msg: String) {
        XposedBridge.log("[Awalocation][ERROR] $msg")
    }

    fun error(msg: String, throwable: Throwable) {
        XposedBridge.log("[Awalocation][ERROR] $msg: ${throwable.stackTraceToString()}")
    }

    fun warn(msg: String) {
        XposedBridge.log("[Awalocation][WARN] $msg")
    }

    fun warn(msg: String, throwable: Throwable) {
        XposedBridge.log("[Awalocation][WARN] $msg: ${throwable.stackTraceToString()}")
    }
}