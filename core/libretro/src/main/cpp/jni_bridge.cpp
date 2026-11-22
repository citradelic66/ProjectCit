#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "retrohost"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
constexpr const char* kHostVersion = "0.1.0-phase1";

constexpr const char* abiName() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__arm__)
    return "armeabi-v7a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__i386__)
    return "x86";
#else
    return "unknown";
#endif
}
}  // namespace

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM* /*vm*/, void* /*reserved*/) {
    LOGI("retrohost %s loaded (%s, C++ %ld)", kHostVersion, abiName(), static_cast<long>(__cplusplus));
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_projectcit_libretro_NativeBridge_nativeHostInfo(JNIEnv* env, jobject /*thiz*/) {
    std::string info = std::string("retrohost ") + kHostVersion + " · " + abiName() +
                       " · C++" + std::to_string(__cplusplus / 100 % 100);
    return env->NewStringUTF(info.c_str());
}
