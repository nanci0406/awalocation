#include <jni.h>
#include "sensor_hook.h"

JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if (vm->GetEnv((void**) &env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // Do NOT call doSensorHook() in JNI_OnLoad.
    // Hooks must only be installed on-demand when Dobby.init() is called by the app,
    // to prevent any risk of crashing or deadlocking system_server during boot.

    return JNI_VERSION_1_6;
}
