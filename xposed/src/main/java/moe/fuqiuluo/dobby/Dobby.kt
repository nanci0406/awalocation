package moe.fuqiuluo.dobby

import moe.fuqiuluo.xposed.utils.Logger

object Dobby {
    private const val TAG = "Dobby"
    @Volatile var isLoaded = false
        internal set
    private var initCalled = false

    @Synchronized
    fun init(cadence: Float, mode: Int = 0, scheme: Int = 0, enabled: Boolean = true) {
        if (!isLoaded) return
        if (initCalled) {
            try {
                nativeSetGaitParams(cadence, mode, scheme, enabled)
            } catch (e: Throwable) {
                Logger.error("Dobby nativeSetGaitParams failed", e)
            }
            return
        }
        try {
            nativeInit(cadence, mode, scheme, enabled)
            initCalled = true
        } catch (e: Throwable) {
            Logger.error("Dobby nativeInit failed", e)
        }
    }

    @Synchronized
    fun setStepSimEnabled(enabled: Boolean) {
        if (!isLoaded) return
        try {
            nativeSetStepSimEnabled(enabled)
        } catch (e: Throwable) {
            Logger.error("Dobby nativeSetStepSimEnabled failed", e)
        }
    }

    @Synchronized
    fun setRouteSimulation(active: Boolean, spm: Float, mode: Int = 0) {
        if (!isLoaded) return
        try {
            nativeSetRouteSimulation(active, spm, mode)
        } catch (e: Throwable) {
            Logger.error("Dobby nativeSetRouteSimulation failed", e)
        }
    }

    @Synchronized
    fun setStepSensorHandles(counterHandle: Int, detectorHandle: Int) {
        if (!isLoaded) return
        try {
            nativeSetStepSensorHandles(counterHandle, detectorHandle)
        } catch (e: Throwable) {
            Logger.error("Dobby nativeSetStepSensorHandles failed", e)
        }
    }

    @Synchronized
    fun setMocking(mocking: Boolean) {
        if (!isLoaded) return
        try {
            nativeSetMocking(if (mocking) 1 else 0)
        } catch (e: Throwable) {
            Logger.error("Dobby nativeSetMocking failed", e)
        }
    }

    @Synchronized
    fun reset() {
        if (!isLoaded) return
        initCalled = false
        try {
            nativeReset()
        } catch (e: Throwable) {
            Logger.error("Dobby nativeReset failed", e)
        }
    }

    external fun setStatus(status: Boolean)

    private external fun nativeInit(cadence: Float, mode: Int, scheme: Int, enabled: Boolean)
    private external fun nativeSetStepSimEnabled(enabled: Boolean)
    private external fun nativeSetRouteSimulation(active: Boolean, spm: Float, mode: Int)
    private external fun nativeSetGaitParams(cadence: Float, mode: Int, scheme: Int, enabled: Boolean)
    private external fun nativeSetMocking(mocking: Int)
    private external fun nativeSetStepSensorHandles(counterHandle: Int, detectorHandle: Int)
    private external fun nativeReset()
    private external fun nativeInitHook(): Boolean
}
