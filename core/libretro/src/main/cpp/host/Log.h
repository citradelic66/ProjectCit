#pragma once
// Logging that works on Android (logcat) and on the Linux host test build (stderr).
#include <cstdarg>
#include <cstdio>

#if defined(__ANDROID__)
#include <android/log.h>
#define RH_LOG(prio, ...) __android_log_print(prio, "retrohost", __VA_ARGS__)
#define RH_DEBUG ANDROID_LOG_DEBUG
#define RH_INFO ANDROID_LOG_INFO
#define RH_WARN ANDROID_LOG_WARN
#define RH_ERROR ANDROID_LOG_ERROR
inline void rhLogV(int prio, const char* fmt, va_list ap) { __android_log_vprint(prio, "retrohost", fmt, ap); }
#else
#define RH_DEBUG 3
#define RH_INFO 4
#define RH_WARN 5
#define RH_ERROR 6
#define RH_LOG(prio, ...) \
    do { if ((prio) >= RH_INFO) { std::fprintf(stderr, "[retrohost] " __VA_ARGS__); std::fputc('\n', stderr); } } while (0)
inline void rhLogV(int prio, const char* fmt, va_list ap) {
    if (prio < RH_INFO) return;
    std::fputs("[core] ", stderr);
    std::vfprintf(stderr, fmt, ap);
}
#endif

#define LOGD(...) RH_LOG(RH_DEBUG, __VA_ARGS__)
#define LOGI(...) RH_LOG(RH_INFO, __VA_ARGS__)
#define LOGW(...) RH_LOG(RH_WARN, __VA_ARGS__)
#define LOGE(...) RH_LOG(RH_ERROR, __VA_ARGS__)
