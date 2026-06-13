#include <jni.h>
#include <android/log.h>
#include "es_types.h"

#define LOG_TAG "EzDentSensor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * JNI bridge: Maps Kotlin SensorImageBridge calls to C++ processing functions.
 */

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_ezdent_sensorpoc_sensor_SensorImageBridge_nativeInit(
        JNIEnv* env, jobject /* this */,
        jint pid, jint frameWidth, jint frameHeight, jint descrambleMode) {

    LOGI("nativeInit: PID=0x%X %dx%d descramble=%d", pid, frameWidth, frameHeight, descrambleMode);

    auto* ctx = new ES_ProcessContext();
    ctx->info.pid = pid;
    ctx->info.frameWidth = frameWidth;
    ctx->info.frameHeight = frameHeight;
    ctx->info.descrambleMode = descrambleMode;

    // Pre-allocate processing buffers
    int pixelCount = frameWidth * frameHeight;
    ctx->buffer1.resize(pixelCount);
    ctx->buffer2.resize(pixelCount);

    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jshortArray JNICALL
Java_com_ezdent_sensorpoc_sensor_SensorImageBridge_nativeDeScramble(
        JNIEnv* env, jobject /* this */,
        jlong handle, jbyteArray rawData, jint width, jint height) {

    auto* ctx = reinterpret_cast<ES_ProcessContext*>(handle);
    if (!ctx) return nullptr;

    int rawSize = env->GetArrayLength(rawData);
    auto* rawBytes = env->GetByteArrayElements(rawData, nullptr);

    LOGI("nativeDeScramble: %dx%d rawSize=%d", width, height, rawSize);

    int pixelCount = width * height;
    std::vector<int16_t> output(pixelCount);

    bool success = es::descramble(
        ctx->info,
        reinterpret_cast<const uint8_t*>(rawBytes), rawSize,
        output.data(), width, height
    );

    env->ReleaseByteArrayElements(rawData, rawBytes, JNI_ABORT);

    if (!success) {
        LOGE("Descramble failed");
        return nullptr;
    }

    jshortArray result = env->NewShortArray(pixelCount);
    env->SetShortArrayRegion(result, 0, pixelCount, output.data());
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_ezdent_sensorpoc_sensor_SensorImageBridge_nativeCalibrate(
        JNIEnv* env, jobject /* this */,
        jlong handle, jshortArray data, jint width, jint height,
        jstring darkFramePath, jstring bpmPath, jstring brightFramePath) {

    auto* ctx = reinterpret_cast<ES_ProcessContext*>(handle);
    if (!ctx) return nullptr;

    // Load calibration data if not already loaded
    if (!ctx->hasCalibration) {
        const char* darkCStr = env->GetStringUTFChars(darkFramePath, nullptr);
        const char* bpmCStr = env->GetStringUTFChars(bpmPath, nullptr);
        const char* brightCStr = env->GetStringUTFChars(brightFramePath, nullptr);

        LOGI("Loading calibration: dark=%s bpm=%s bright=%s", darkCStr, bpmCStr, brightCStr);

        es::loadCalibration(*ctx, darkCStr, bpmCStr, brightCStr);

        env->ReleaseStringUTFChars(darkFramePath, darkCStr);
        env->ReleaseStringUTFChars(bpmPath, bpmCStr);
        env->ReleaseStringUTFChars(brightFramePath, brightCStr);
    }

    int pixelCount = width * height;
    auto* inputData = env->GetShortArrayElements(data, nullptr);
    std::memcpy(ctx->buffer1.data(), inputData, pixelCount * sizeof(int16_t));
    env->ReleaseShortArrayElements(data, inputData, JNI_ABORT);

    bool success = es::calibrate(*ctx, ctx->buffer1.data(), width, height);

    if (!success) {
        LOGW("Calibration skipped (no calibration data)");
        // Return input data unchanged
        return data;
    }

    jshortArray result = env->NewShortArray(pixelCount);
    env->SetShortArrayRegion(result, 0, pixelCount, ctx->buffer1.data());
    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_ezdent_sensorpoc_sensor_SensorImageBridge_nativeProcess(
        JNIEnv* env, jobject /* this */,
        jlong handle, jshortArray data, jint width, jint height,
        jstring iniPath) {

    auto* ctx = reinterpret_cast<ES_ProcessContext*>(handle);
    if (!ctx) return nullptr;

    int pixelCount = width * height;
    auto* inputData = env->GetShortArrayElements(data, nullptr);
    std::memcpy(ctx->buffer1.data(), inputData, pixelCount * sizeof(int16_t));
    env->ReleaseShortArrayElements(data, inputData, JNI_ABORT);

    LOGI("nativeProcess: %dx%d", width, height);

    const char* iniCStr = env->GetStringUTFChars(iniPath, nullptr);
    bool success = es::process(*ctx, ctx->buffer1.data(), width, height, iniCStr);
    env->ReleaseStringUTFChars(iniPath, iniCStr);

    if (!success) {
        LOGW("Processing failed, returning unprocessed data");
        return data;
    }

    jshortArray result = env->NewShortArray(pixelCount);
    env->SetShortArrayRegion(result, 0, pixelCount, ctx->buffer1.data());
    return result;
}

JNIEXPORT jintArray JNICALL
Java_com_ezdent_sensorpoc_sensor_SensorImageBridge_nativeToPixels(
        JNIEnv* env, jobject /* this */,
        jshortArray data, jint width, jint height, jboolean invert) {

    int pixelCount = width * height;
    auto* inputData = env->GetShortArrayElements(data, nullptr);

    std::vector<int32_t> pixels(pixelCount);
    es::toARGBPixels(inputData, width, height, pixels.data(), invert);

    env->ReleaseShortArrayElements(data, inputData, JNI_ABORT);

    jintArray result = env->NewIntArray(pixelCount);
    env->SetIntArrayRegion(result, 0, pixelCount, pixels.data());
    return result;
}

JNIEXPORT void JNICALL
Java_com_ezdent_sensorpoc_sensor_SensorImageBridge_nativeRelease(
        JNIEnv* env, jobject /* this */, jlong handle) {

    auto* ctx = reinterpret_cast<ES_ProcessContext*>(handle);
    if (ctx) {
        LOGI("nativeRelease: freeing context");
        delete ctx;
    }
}

} // extern "C"
