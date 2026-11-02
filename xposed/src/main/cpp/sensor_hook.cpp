#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <dobby.h>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <cerrno>
#include <sys/types.h>
#include <cinttypes>
#include <cmath>
#include <atomic>
#include <mutex>
#include <chrono>
#include <cstdlib>
#include <unistd.h>

#include "sensor_simulator.h"
#include "kail_log.h"
#include "elf_sym_resolver.h"
#include "sensor_hook.h"

#define LOG_TAG "PortalNativeSensor"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const char *kHookTag = "NativeSensorHook.portal";

#define SENSOR_TYPE_ACCELEROMETER 1
#define SENSOR_TYPE_GYROSCOPE 4
#define SENSOR_TYPE_LIGHT 5
#define SENSOR_TYPE_LINEAR_ACCELERATION 10
#define SENSOR_TYPE_STEP_DETECTOR 18
#define SENSOR_TYPE_STEP_COUNTER 19

#define EVENT_SIZE 0x68

typedef void (*SendObjectsFunc)(long*, void*, long, long);
static SendObjectsFunc original_send_objects = nullptr;
static bool send_objects_hook_installed = false;
static std::atomic<bool> route_simulation_active{false};
static uint64_t send_objects_offset = 0;

typedef void (*ConvertToSensorEventFunc)(void* param_1, void* param_2);
static ConvertToSensorEventFunc original_convert_to_sensor_event = nullptr;
static bool convert_to_sensor_event_hook_installed = false;
static uint64_t convert_to_sensor_event_offset = 0;

static const char* kSendObjectsSymbols[] = {
    "_ZN7android7BitTube11sendObjectsERKNS_2spIS0_EEPKvmm",
    "_ZN7android7BitTube11sendObjectsERKNS_2spIS0_EEPKvjj",
};

static const char* kConvertSymbols[] = {
    // AIDL sensors HAL (Android 12+): convertToSensorEvent(aidl Event, ...)
    "_ZN7android8hardware7sensors14implementation20convertToSensorEventERKN4aidl7android8hardware7sensors5EventEP15sensors_event_t",
    // HIDL V1_0 fallback (older ROMs): convertToSensorEvent(V1_0 Event, ...)
    "_ZN7android8hardware7sensors4V1_014implementation20convertToSensorEventERKNS2_5EventEP15sensors_event_t",
};

static int mSensorHandleStepDetector = -1;
static int mSensorHandleStepCounter = -1;
static std::atomic<int> isMocking{0};
static std::atomic<int> step_sim_enabled{1};
static std::atomic<float> current_spm{120.0f};
static int step_event_counter = 0;
static std::atomic<uint64_t> step_synth_events{0};
static std::mutex hook_install_mutex;
static std::mutex sensor_simulator_mutex;
static std::mutex step_state_mutex;

static int64_t step_last_ts_ns = 0;
static double  step_debt = 0.0;
static uint64_t step_count_total = 0;
static bool    step_have_base = false;
static int     pending_step_detector_events = 0;
static int     pending_step_counter_events = 0;
static int     step_emit_phase = 0;
static const int64_t kStepMaxGapNs = 5LL * 1000000000LL;

static bool is_plausible_userspace_ptr(const void* ptr) {
    uintptr_t value = (uintptr_t)ptr;
#if UINTPTR_MAX > 0xffffffffULL
    value &= 0x00ffffffffffffffULL;
#endif
    if (value < 0x100000ULL) return false;
    return true;
}

static void updateSensorSimulatorParams(float spm, int mode, int scheme, bool enable) {
    std::lock_guard<std::mutex> lock(sensor_simulator_mutex);
    gait::SensorSimulator::Get().UpdateParams(spm, mode, scheme, enable);
}

static void processSensorSimulatorEvents(sensors_event_t* events, size_t count) {
    std::lock_guard<std::mutex> lock(sensor_simulator_mutex);
    gait::SensorSimulator::Get().ProcessSensorEvents(events, count);
}

static void initSensorSimulator() {
    std::lock_guard<std::mutex> lock(sensor_simulator_mutex);
    gait::SensorSimulator::Get().Init();
}

static bool reloadSensorSimulatorConfig() {
    std::lock_guard<std::mutex> lock(sensor_simulator_mutex);
    return gait::SensorSimulator::Get().ReloadConfig();
}

void setRouteSimulationActive(bool active) {
    route_simulation_active.store(active, std::memory_order_release);
    if (!active) {
        updateSensorSimulatorParams(120.0f, 0, 0, false);
    }
}

static void resetStepDebtClock(bool zeroCounter) {
    std::lock_guard<std::mutex> lock(step_state_mutex);
    step_last_ts_ns = 0;
    step_debt = 0.0;
    pending_step_detector_events = 0;
    pending_step_counter_events = 0;
    step_emit_phase = 0;
    if (zeroCounter) {
        step_count_total = 0;
        step_have_base = false;
    }
}

static void resetStepHandles() {
    std::lock_guard<std::mutex> lock(step_state_mutex);
    mSensorHandleStepDetector = -1;
    mSensorHandleStepCounter = -1;
}

static void setStepSensorHandles(int counterHandle, int detectorHandle) {
    std::lock_guard<std::mutex> lock(step_state_mutex);
    if (counterHandle >= 0) {
        mSensorHandleStepCounter = counterHandle;
    }
    if (detectorHandle >= 0) {
        mSensorHandleStepDetector = detectorHandle;
    }
    KLOGI(kHookTag, "step sensor handles set: counter=%d detector=%d",
          mSensorHandleStepCounter, mSensorHandleStepDetector);
}

static bool isStepCarrierType(int sensorType) {
    return sensorType == SENSOR_TYPE_ACCELEROMETER ||
           sensorType == SENSOR_TYPE_LINEAR_ACCELERATION ||
           sensorType == SENSOR_TYPE_GYROSCOPE ||
           sensorType == SENSOR_TYPE_LIGHT;
}

static bool synthesizeStepEventFromCarrierLocked(void* eventOut, int carrierType) {
    if (!eventOut) return false;

    if (mSensorHandleStepDetector < 0 && mSensorHandleStepCounter < 0) {
        static int warned = 0;
        if (!warned) {
            warned = 1;
            KLOGW(kHookTag, "step synth skipped: no step sensor handles yet");
        }
        return false;
    }

    int64_t ts = *(int64_t*)((char*)eventOut + 0x10);
    float spm = current_spm.load(std::memory_order_acquire);
    if (spm < 1.0f) spm = 1.0f;
    if (spm > 400.0f) spm = 400.0f;
    const double sps = (double)spm / 60.0;

    if (step_last_ts_ns == 0) {
        step_last_ts_ns = ts;
    }
    int64_t delta = ts - step_last_ts_ns;
    if (delta < 0) delta = 0;
    if (delta > kStepMaxGapNs) delta = kStepMaxGapNs;
    step_last_ts_ns = ts;

    // Apply natural human gait cadence jitter (+/- 6%)
    float jitter = 1.0f + ((float)((rand() % 13) - 6) / 100.0f);
    step_debt += (double)delta * 1e-9 * sps * (double)jitter;

    uint64_t due = (uint64_t)std::floor(step_debt);
    if (due > 0) {
        step_debt -= (double)due;
        step_count_total += due;
        if (mSensorHandleStepDetector >= 0) {
            uint64_t detectorPending = (uint64_t)pending_step_detector_events + due;
            pending_step_detector_events = detectorPending > 1000 ? 1000 : (int)detectorPending;
        }
        if (mSensorHandleStepCounter >= 0) {
            pending_step_counter_events = 1;
        }
    }

    bool canEmitCounter = pending_step_counter_events > 0 && mSensorHandleStepCounter >= 0;
    bool canEmitDetector = pending_step_detector_events > 0 && mSensorHandleStepDetector >= 0;
    if (canEmitCounter && (!canEmitDetector || step_emit_phase == 0)) {
        pending_step_counter_events--;
        *(int*)((char*)eventOut + 0x04) = mSensorHandleStepCounter;
        *(int*)((char*)eventOut + 0x08) = SENSOR_TYPE_STEP_COUNTER;
        *(uint64_t*)((char*)eventOut + 0x18) = step_count_total;
        step_emit_phase = 1;
        uint64_t synth = step_synth_events.fetch_add(1, std::memory_order_relaxed) + 1;
        if (synth <= 5 || (synth % 100ULL) == 0ULL) {
            KLOGI(kHookTag, "step COUNTER emit #%llu carrier=%d handle=%d total=%llu pendingDetector=%d",
                  (unsigned long long)synth, carrierType, mSensorHandleStepCounter,
                  (unsigned long long)step_count_total, pending_step_detector_events);
        }
        return true;
    } else if (canEmitDetector) {
        pending_step_detector_events--;
        *(int*)((char*)eventOut + 0x04) = mSensorHandleStepDetector;
        *(int*)((char*)eventOut + 0x08) = SENSOR_TYPE_STEP_DETECTOR;
        *(float*)((char*)eventOut + 0x18) = 1.0f;
        step_emit_phase = 0;
        uint64_t synth = step_synth_events.fetch_add(1, std::memory_order_relaxed) + 1;
        if (synth <= 5 || (synth % 100ULL) == 0ULL) {
            KLOGI(kHookTag, "step DETECTOR emit #%llu carrier=%d handle=%d total=%llu pendingCounter=%d",
                  (unsigned long long)synth, carrierType, mSensorHandleStepDetector,
                  (unsigned long long)step_count_total, pending_step_counter_events);
        }
        return true;
    }
    return false;
}

extern "C" void hooked_send_objects(long* param_1, void* param_2, long param_3, long param_4) {
    if (!route_simulation_active.load(std::memory_order_acquire)) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    if (!param_2) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    int count = (int)param_3;
    if (count <= 0 || count > 1000) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    size_t buffer_size = count * EVENT_SIZE;
    if (buffer_size > 65536) {
        if (original_send_objects) {
            original_send_objects(param_1, param_2, param_3, param_4);
        }
        return;
    }

    char* heap_buffer = new char[buffer_size];
    memcpy(heap_buffer, param_2, buffer_size);

    for (int i = 0; i < count; i++) {
        void* event = heap_buffer + i * EVENT_SIZE;
        uintptr_t addr = (uintptr_t)event;
        if (addr < 0x10000) continue;

        int type = *(int*)((char*)event + 0x08);
        int64_t timestamp = *(int64_t*)((char*)event + 0x10);

        if (type == SENSOR_TYPE_STEP_COUNTER) {
            uint64_t data0 = *(uint64_t*)((char*)event + 0x18);
            sensors_event_t se;
            memset(&se, 0, sizeof(se));
            se.type = type;
            se.timestamp = timestamp;
            se.data[0] = (float)data0;

            processSensorSimulatorEvents(&se, 1);
            *(uint64_t*)((char*)event + 0x18) = (uint64_t)se.data[0];
        } else {
            float data0 = *(float*)((char*)event + 0x18);
            float data1 = *(float*)((char*)event + 0x1C);
            float data2 = *(float*)((char*)event + 0x20);

            sensors_event_t se;
            memset(&se, 0, sizeof(se));
            se.type = type;
            se.timestamp = timestamp;
            se.data[0] = data0;
            se.data[1] = data1;
            se.data[2] = data2;

            processSensorSimulatorEvents(&se, 1);

            *(float*)((char*)event + 0x18) = se.data[0];
            *(float*)((char*)event + 0x1C) = se.data[1];
            *(float*)((char*)event + 0x20) = se.data[2];
        }

        int outType = *(int*)((char*)event + 0x08);
        if ((isMocking.load(std::memory_order_acquire) != 0) &&
            (step_sim_enabled.load(std::memory_order_acquire) != 0) &&
            isStepCarrierType(outType)) {
            std::lock_guard<std::mutex> lock(step_state_mutex);
            synthesizeStepEventFromCarrierLocked(event, outType);
        }
    }

    memcpy(param_2, heap_buffer, buffer_size);
    delete[] heap_buffer;

    if (original_send_objects) {
        original_send_objects(param_1, param_2, param_3, param_4);
    }
}

extern "C" void hooked_convert_to_sensor_event(void* param_1, void* param_2) {
    if (!is_plausible_userspace_ptr(param_2) || !is_plausible_userspace_ptr(param_1)) {
        return;
    }

    if (original_convert_to_sensor_event) {
        original_convert_to_sensor_event(param_1, param_2);
    }

    int sensor_type = *(int*)((char*)param_2 + 0x08);
    bool isStepType = (sensor_type == SENSOR_TYPE_STEP_DETECTOR ||
                       sensor_type == SENSOR_TYPE_STEP_COUNTER);
    bool isCarrierType = isStepCarrierType(sensor_type);

    if (isStepType || isCarrierType) {
        std::lock_guard<std::mutex> lock(step_state_mutex);
        if (sensor_type == SENSOR_TYPE_STEP_DETECTOR) {
            mSensorHandleStepDetector = *(int*)((char*)param_2 + 0x04);
        } else if (sensor_type == SENSOR_TYPE_STEP_COUNTER) {
            mSensorHandleStepCounter = *(int*)((char*)param_2 + 0x04);
            uint64_t realCounter = *(uint64_t*)((char*)param_2 + 0x18);
            if (!step_have_base || realCounter > step_count_total) {
                step_count_total = realCounter;
                step_have_base = true;
            }
        }

        if ((isMocking.load(std::memory_order_acquire) != 0) &&
            (step_sim_enabled.load(std::memory_order_acquire) != 0) &&
            isCarrierType) {
            synthesizeStepEventFromCarrierLocked(param_2, sensor_type);
        }
    }
}

static void install_send_objects_hook() {
    std::lock_guard<std::mutex> lock(hook_install_mutex);
    if (send_objects_hook_installed) return;

    const char* libs[] = {"libsensor.so", "libsensorservice.so", "libgui.so"};
    void* addr = nullptr;
    for (const char* lib : libs) {
        addr = kailsym::resolve(lib, kSendObjectsSymbols,
                                (int)(sizeof(kSendObjectsSymbols) / sizeof(kSendObjectsSymbols[0])),
                                &send_objects_offset);
        if (addr) break;
    }

    if (!addr) {
        KLOGW(kHookTag, "install_send_objects_hook: symbol resolve failed");
        return;
    }

    int ret = DobbyHook(addr, (void*)hooked_send_objects, (void**)&original_send_objects);
    if (ret == 0) {
        send_objects_hook_installed = true;
        KLOGI(kHookTag, "hooked send_objects at %p", addr);
    } else {
        KLOGE(kHookTag, "DobbyHook send_objects failed rc=%d at %p", ret, addr);
    }
}

static void install_convert_to_sensor_event_hook() {
    std::lock_guard<std::mutex> lock(hook_install_mutex);
    if (convert_to_sensor_event_hook_installed) return;

    void* addr = kailsym::resolve("libsensorservice.so", kConvertSymbols,
                                  (int)(sizeof(kConvertSymbols) / sizeof(kConvertSymbols[0])),
                                  &convert_to_sensor_event_offset);
    if (!addr) {
        KLOGW(kHookTag, "install_convert_to_sensor_event_hook: symbol resolve failed");
        return;
    }

    int ret = DobbyHook(addr, (void*)hooked_convert_to_sensor_event, (void**)&original_convert_to_sensor_event);
    if (ret == 0) {
        convert_to_sensor_event_hook_installed = true;
        KLOGI(kHookTag, "hooked convert_to_sensor_event at %p", addr);
    } else {
        KLOGE(kHookTag, "DobbyHook convert_to_sensor_event failed rc=%d at %p", ret, addr);
    }
}

void doSensorHook() {
    initSensorSimulator();
    install_send_objects_hook();
    install_convert_to_sensor_event_hook();
    reloadSensorSimulatorConfig();
    KLOGI(kHookTag, "doSensorHook complete: sendObjects=%d convert=%d",
          send_objects_hook_installed, convert_to_sensor_event_hook_installed);
}

// ============================================================
// JNI Exports for moe.fuqiuluo.dobby.Dobby
// ============================================================
extern "C" {

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_dobby_Dobby_setStatus(JNIEnv* env, jobject thiz, jboolean status) {
    bool s = (status != JNI_FALSE);
    isMocking.store(s ? 1 : 0, std::memory_order_release);
    step_sim_enabled.store(s ? 1 : 0, std::memory_order_release);
    setRouteSimulationActive(s);
    KLOGI(kHookTag, "Dobby.setStatus: status=%d", s ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_dobby_Dobby_nativeInit(
    JNIEnv* env, jclass clazz, jfloat spm, jint mode, jint scheme, jboolean enable) {
    if (spm > 0.0f) current_spm.store(spm, std::memory_order_release);
    updateSensorSimulatorParams(spm, mode, scheme, enable != JNI_FALSE);
    doSensorHook();
    KLOGI(kHookTag, "Dobby.nativeInit: spm=%.2f mode=%d scheme=%d enable=%d",
          spm, mode, scheme, enable ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_dobby_Dobby_nativeSetStepSimEnabled(
    JNIEnv* env, jclass clazz, jboolean enabled) {
    step_sim_enabled.store((enabled != JNI_FALSE) ? 1 : 0, std::memory_order_release);
    KLOGI(kHookTag, "Dobby.nativeSetStepSimEnabled: enabled=%d", enabled ? 1 : 0);
}

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_dobby_Dobby_nativeSetRouteSimulation(
    JNIEnv* env, jclass clazz, jboolean active, jfloat spm, jint mode) {
    bool isActive = (active != JNI_FALSE);
    if (isActive) {
        current_spm.store(spm, std::memory_order_release);
        setRouteSimulationActive(true);
        updateSensorSimulatorParams(spm, mode, 0, true);
        isMocking.store(1, std::memory_order_release);
        step_event_counter = 0;
        resetStepDebtClock(false);
    } else {
        setRouteSimulationActive(false);
        isMocking.store(0, std::memory_order_release);
    }
    KLOGI(kHookTag, "Dobby.nativeSetRouteSimulation: active=%d spm=%.2f mode=%d", isActive ? 1 : 0, spm, mode);
}

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_dobby_Dobby_nativeSetGaitParams(
    JNIEnv* env, jclass clazz, jfloat spm, jint mode, jint scheme, jboolean enable) {
    if (spm > 0.0f) current_spm.store(spm, std::memory_order_release);
    updateSensorSimulatorParams(spm, mode, scheme, enable != JNI_FALSE);
}

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_dobby_Dobby_nativeSetMocking(
    JNIEnv* env, jclass clazz, jint mocking) {
    isMocking.store((int)mocking, std::memory_order_release);
}

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_dobby_Dobby_nativeSetStepSensorHandles(
    JNIEnv* env, jclass clazz, jint counterHandle, jint detectorHandle) {
    setStepSensorHandles((int)counterHandle, (int)detectorHandle);
}

JNIEXPORT void JNICALL
Java_moe_fuqiuluo_dobby_Dobby_nativeReset(JNIEnv* env, jclass clazz) {
    step_sim_enabled.store(0, std::memory_order_release);
    route_simulation_active.store(false, std::memory_order_release);
    isMocking.store(0, std::memory_order_release);
    step_event_counter = 0;
    step_synth_events.store(0, std::memory_order_relaxed);
    resetStepDebtClock(true);
    resetStepHandles();
    current_spm.store(120.0f, std::memory_order_release);
    KLOGI(kHookTag, "Dobby.nativeReset executed");
}

JNIEXPORT jboolean JNICALL
Java_moe_fuqiuluo_dobby_Dobby_nativeInitHook(JNIEnv* env, jclass clazz) {
    doSensorHook();
    return (send_objects_hook_installed || convert_to_sensor_event_hook_installed) ? JNI_TRUE : JNI_FALSE;
}

// Compatibility exports
JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeInit(
    JNIEnv* env, jclass clazz, jfloat spm, jint mode, jint scheme, jboolean enable) {
    Java_moe_fuqiuluo_dobby_Dobby_nativeInit(env, clazz, spm, mode, scheme, enable);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeSetStepSimEnabled(
    JNIEnv* env, jclass clazz, jboolean enabled) {
    Java_moe_fuqiuluo_dobby_Dobby_nativeSetStepSimEnabled(env, clazz, enabled);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeSetRouteSimulation(
    JNIEnv* env, jclass clazz, jboolean active, jfloat spm, jint mode) {
    Java_moe_fuqiuluo_dobby_Dobby_nativeSetRouteSimulation(env, clazz, active, spm, mode);
}

JNIEXPORT void JNICALL
Java_com_kail_locationxposed_xposed_sensor_NativeSensorHook_nativeReset(JNIEnv* env, jclass clazz) {
    Java_moe_fuqiuluo_dobby_Dobby_nativeReset(env, clazz);
}

}
