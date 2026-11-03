package com.nanci.awalocation.xposed.hooks.sensor

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import com.nanci.awalocation.xposed.BaseDivineService
import com.nanci.awalocation.xposed.utils.FakeLoc
import com.nanci.awalocation.xposed.utils.Logger
import com.nanci.awalocation.xposed.utils.hookAllMethodsBefore
import com.nanci.awalocation.xposed.utils.hookBefore
import java.lang.reflect.Constructor
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * 步频传感器模拟：向注册了 TYPE_STEP_COUNTER / TYPE_STEP_DETECTOR
 * 监听器的进程按指定步频（步/分钟）投喂合成 SensorEvent，并拦截真实
 * 计步事件防止重复计数；合成计步值以最近的真实计步值为基线，保证单调。
 *
 * system_server 内由 [SystemSensorManagerHook] 委托 track/intercept；
 * 目标应用进程（用户在 LSPosed 中将其加入模块作用域）通过 [install]
 * 安装，配置经 [BaseDivineService] 与 system_server 同步并接收命令转发。
 */
object StepSensorHook : BaseDivineService() {
    private const val TAG = "StepSensorHook"

    private data class StepListenerKey(val listener: Any, val sensorType: Int)
    private data class StepListenerEntry(val listener: Any, val sensor: Sensor, val handler: Handler?)

    // (listener, sensorType) -> entry
    private val stepListeners = ConcurrentHashMap<StepListenerKey, StepListenerEntry>()
    private val hookedListenerClasses = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var fakeStepCounter = 0f

    // 拦截到的真实计步值（-1 表示尚未观测到），作为合成计步的基线
    @Volatile private var lastRealStepCounter = -1f

    @Volatile var feederThread: Thread? = null
        internal set
    private var sensorEventConstructor: Constructor<*>? = null

    // 标志：当前线程是否正在投喂合成事件，防止拦截自身投喂的伪造事件
    private val isDeliveringSyntheticEvent = ThreadLocal<Boolean>()

    private fun active(): Boolean = FakeLoc.enableMockStep

    /**
     * 安装到目标应用进程。
     */
    fun install(classLoader: ClassLoader) {
        thread(isDaemon = true, name = "AwalocationStepInit") {
            while (true) {
                val ok = runCatching { initDivineService(TAG) }.getOrDefault(false)
                if (ok) break
                // 模拟未运行或连接失败，等待后重试以获取配置与命令转发
                val interrupted = runCatching { Thread.sleep(2000) }.isFailure
                if (interrupted) break
            }
        }
        hook(classLoader)
    }

    private fun hook(classLoader: ClassLoader) {
        val cSystemSensorManager = XposedHelpers.findClassIfExists("android.hardware.SystemSensorManager", classLoader) ?: run {
            Logger.error("StepSensorHook: SystemSensorManager not found")
            return
        }

        val hookRegister = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val args = param?.args ?: return
                val listener = args.getOrNull(0) ?: return
                val sensor = args.getOrNull(1) as? Sensor ?: return
                val handler = args.getOrNull(3) as? Handler
                trackListener(listener, sensor, handler)
            }
        }

        val hookUnregister = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam?) {
                val args = param?.args ?: return
                val listener = args.getOrNull(0) ?: return
                val sensor = args.getOrNull(1) as? Sensor
                untrackListener(listener, sensor)
            }
        }

        de.robv.android.xposed.XposedBridge.hookAllMethods(cSystemSensorManager, "registerListener", hookRegister)
        de.robv.android.xposed.XposedBridge.hookAllMethods(cSystemSensorManager, "registerListenerImpl", hookRegister)
        de.robv.android.xposed.XposedBridge.hookAllMethods(cSystemSensorManager, "unregisterListener", hookUnregister)
        de.robv.android.xposed.XposedBridge.hookAllMethods(cSystemSensorManager, "unregisterListenerImpl", hookUnregister)

        val cSensorManager = XposedHelpers.findClassIfExists("android.hardware.SensorManager", classLoader)
        if (cSensorManager != null) {
            de.robv.android.xposed.XposedBridge.hookAllMethods(cSensorManager, "registerListener", hookRegister)
            de.robv.android.xposed.XposedBridge.hookAllMethods(cSensorManager, "unregisterListener", hookUnregister)
        }

        Logger.info("StepSensorHook installed")
    }

    /**
     * 由 target 进程或 system_server 侧的 [SystemSensorManagerHook] 调用。
     */
    fun trackListener(listener: Any, sensor: Sensor, handler: Handler? = null) {
        if (sensor.type != Sensor.TYPE_STEP_DETECTOR && sensor.type != Sensor.TYPE_STEP_COUNTER) {
            return
        }
        val key = StepListenerKey(listener, sensor.type)
        stepListeners[key] = StepListenerEntry(listener, sensor, handler)
        Logger.info("Step listener registered: type=${sensor.type}, listener=${listener.javaClass.name}")
        // Install the real-event interception callback. Without this the feeder cannot
        // learn the device's real step-counter baseline and real events stay visible.
        hookListenerCallback(listener)
        wakeFeeder()
    }

    /**
     * 由 target 进程或 system_server 侧的 [SystemSensorManagerHook] 调用。
     */
    fun untrackListener(listener: Any, sensor: Sensor? = null) {
        if (sensor != null) {
            stepListeners.remove(StepListenerKey(listener, sensor.type))
        } else {
            stepListeners.keys.filter { it.listener == listener }.forEach {
                stepListeners.remove(it)
            }
        }
    }

    /**
     * 在监听器 onSensorChanged 之前调用：步频模拟生效时吞掉真实计步事件
     * 并记录真实计步基线。返回 true 表示事件已被拦截。
     */
    fun interceptStepEvent(param: XC_MethodHook.MethodHookParam): Boolean {
        // 合成事件放行，不进行拦截与结果覆盖
        if (isDeliveringSyntheticEvent.get() == true) return false
        val event = param.args.getOrNull(0) as? SensorEvent ?: return false
        when (event.sensor?.type) {
            Sensor.TYPE_STEP_COUNTER -> {
                val real = event.values.getOrNull(0) ?: return false
                if (real > lastRealStepCounter) {
                    lastRealStepCounter = real
                }
            }
            Sensor.TYPE_STEP_DETECTOR -> {}
            else -> return false
        }

        // 即使当前未开启步频模拟，也要记录真实计步基线；只有开启时才吞掉真实事件。
        if (!active()) return false

        param.result = null
        return true
    }

    private fun hookListenerCallback(listener: Any) {
        var clazz: Class<*>? = listener.javaClass
        while (clazz != null && clazz != Any::class.java) {
            val name = clazz.name
            if (name.startsWith("android.") || name.startsWith("java.")) break
            if (hookedListenerClasses.add(name)) {
                clazz.hookAllMethodsBefore("onSensorChanged") {
                    interceptStepEvent(this)
                }
            }
            clazz = clazz.superclass
        }
    }

    fun wakeFeeder() {
        if (!active()) return
        ensureFeeder()
        feederThread?.let {
            if (it.isAlive && it.state == Thread.State.TIMED_WAITING) {
                runCatching { it.interrupt() }
            }
        }
    }

    fun stopFeeder() {
        feederThread?.let {
            if (it.isAlive) {
                runCatching { it.interrupt() }
            }
        }
    }

    fun ensureFeeder() {
        if (feederThread?.isAlive == true) return
        synchronized(this) {
            if (feederThread?.isAlive == true) return
            val t = thread(start = false, isDaemon = true, name = "AwalocationStepFeeder") {
                while (true) {
                    try {
                        if (!active() || stepListeners.isEmpty()) {
                            Thread.sleep(500)
                            continue
                        }
                        val cadence = resolveCadence()
                        if (cadence <= 0f) {
                            Thread.sleep(500)
                            continue
                        }
                        deliverStepEvents()
                        // 步频随机抖动：在基准间隔上引入 ±8% ~ ±10% 的随机浮动，模拟真实步行/跑步步频节奏
                        val baseIntervalMs = 60000.0 / cadence
                        val jitterPercent = Random.nextDouble(-0.10, 0.10)
                        val sleepMs = (baseIntervalMs * (1.0 + jitterPercent)).toLong().coerceIn(20L, 2000L)
                        Thread.sleep(sleepMs)
                    } catch (_: InterruptedException) {
                        if (!active()) break
                        continue
                    } catch (throwable: Throwable) {
                        Logger.error("Step feeder error", throwable)
                        runCatching { Thread.sleep(1000) }
                    }
                }
            }
            feederThread = t
            t.start()
        }
    }

    private fun deliverStepEvents() {
        if (lastRealStepCounter > fakeStepCounter) {
            fakeStepCounter = lastRealStepCounter
        }
        var counterListenerCount = 0
        stepListeners.values.forEach {
            if (it.sensor.type == Sensor.TYPE_STEP_COUNTER) counterListenerCount++
        }
        if (counterListenerCount > 0) {
            fakeStepCounter += 1f
        }
        for (entry in stepListeners.values) {
            runCatching {
                val sensor = entry.sensor
                val listener = entry.listener
                val event = createSensorEvent(sensor) ?: return@runCatching
                event.values[0] = if (sensor.type == Sensor.TYPE_STEP_COUNTER) fakeStepCounter else 1f
                event.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                event.timestamp = SystemClock.elapsedRealtimeNanos()

                val deliverRunnable = Runnable {
                    try {
                        isDeliveringSyntheticEvent.set(true)
                        XposedHelpers.callMethod(listener, "onSensorChanged", event)
                    } finally {
                        isDeliveringSyntheticEvent.remove()
                    }
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("Fed step event: type=${sensor.type}, value=${event.values[0]}")
                    }
                }

                val targetHandler = entry.handler?.takeIf { it.looper.thread.isAlive }
                    ?: runCatching { Handler(Looper.getMainLooper()) }.getOrNull()

                if (targetHandler != null) {
                    targetHandler.post(deliverRunnable)
                } else {
                    deliverRunnable.run()
                }
            }.onFailure {
                Logger.error("Failed to feed step event", it)
            }
        }
    }

    /**
     * 手动指定步频（>0）优先；否则按模拟速度自动推算：步频 = 速度 / 平均步幅(0.762米) * 60。
     * 速度不大于 0 视为未移动，不产生步频。
     */
    private fun resolveCadence(): Float {
        FakeLoc.stepCadence.takeIf { it > 0f }?.let { return it }
        if (FakeLoc.speed <= 0.0) return 0f
        return (FakeLoc.speed / 0.762 * 60.0).toFloat().coerceIn(40f, 240f)
    }

    private fun createSensorEvent(sensor: Sensor): SensorEvent? {
        val constructor = sensorEventConstructor ?: synchronized(this) {
            sensorEventConstructor ?: findSensorEventConstructor(sensor).also {
                sensorEventConstructor = it
            }
        } ?: return null
        return runCatching {
            val event = if (constructor.parameterTypes.size == 1) {
                constructor.newInstance(16)
            } else {
                constructor.newInstance(sensor, 0, 0)
            } as SensorEvent
            XposedHelpers.setObjectField(event, "sensor", sensor)
            if (event.values == null || event.values.isEmpty()) {
                XposedHelpers.setObjectField(event, "values", FloatArray(16))
            }
            event
        }.onFailure {
            Logger.error("Failed to create SensorEvent", it)
        }.getOrNull()
    }

    private fun findSensorEventConstructor(sensor: Sensor): Constructor<*>? {
        val cSensorEvent = runCatching {
            XposedHelpers.findClass("android.hardware.SensorEvent", sensor.javaClass.classLoader)
        }.getOrNull() ?: return null
        // SensorEvent(int) 在各 Android 版本中通用（package-private，values 数组长度即其值）
        runCatching {
            cSensorEvent.getDeclaredConstructor(Int::class.javaPrimitiveType)
        }.getOrNull()?.let {
            it.isAccessible = true
            return it
        }
        // 兜底：遍历其余构造器，参数数量为 3 的隐藏签名（如 SensorEvent(Sensor, int, int)）
        cSensorEvent.declaredConstructors.filter { it.parameterTypes.size == 3 }.forEach {
            runCatching {
                it.isAccessible = true
                (it.newInstance(sensor, 0, 0) as SensorEvent).let { event ->
                    XposedHelpers.setObjectField(event, "sensor", sensor)
                    if (event.values.isNotEmpty()) {
                        return it
                    }
                }
            }
        }
        Logger.error("Failed to find usable SensorEvent constructor")
        return null
    }
}
