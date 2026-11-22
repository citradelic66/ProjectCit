// JNI surface for com.projectcit.libretro.NativeBridge. Thin by design: all logic
// lives in host/ so it is testable without a JVM; this file only marshals types.
#include <jni.h>

#include <cstring>
#include <string>
#include <vector>

#include "host/EmuRunner.h"
#include "host/Log.h"
#include "host/RetroHost.h"

#if defined(__ANDROID__)
#include <android/native_window_jni.h>

#include "android/OboeAudioOutput.h"
#include "gl/EglVideoOutput.h"
#endif

using retrohost::EmuRunner;
using retrohost::RetroHost;

namespace {
constexpr const char* kHostVersion = "0.3.0-phase3";

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

RetroHost* H(jlong h) { return reinterpret_cast<RetroHost*>(h); }
EmuRunner* R(jlong r) { return reinterpret_cast<EmuRunner*>(r); }

std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

void setError(JNIEnv* env, jobjectArray errOut, const std::string& msg) {
    if (!errOut || env->GetArrayLength(errOut) < 1) return;
    jstring s = env->NewStringUTF(msg.c_str());
    env->SetObjectArrayElement(errOut, 0, s);
    env->DeleteLocalRef(s);
}

jobjectArray stringArray(JNIEnv* env, const std::vector<std::string>& v) {
    jclass cls = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(v.size()), cls, nullptr);
    for (size_t i = 0; i < v.size(); ++i) {
        jstring s = env->NewStringUTF(v[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}
}  // namespace

#define JNI_FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_projectcit_libretro_NativeBridge_##name

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM*, void*) {
    LOGI("retrohost %s loaded (%s)", kHostVersion, abiName());
    return JNI_VERSION_1_6;
}

JNI_FN(jstring, nativeHostInfo)(JNIEnv* env, jobject) {
    std::string info = std::string("retrohost ") + kHostVersion + " · " + abiName() +
                       " · C++" + std::to_string(__cplusplus / 100 % 100) + " · libretro API v" + std::to_string(RETRO_API_VERSION);
    return env->NewStringUTF(info.c_str());
}

JNI_FN(jlong, nativeCreate)(JNIEnv* env, jobject, jstring corePath, jstring systemDir, jstring saveDir,
                            jobjectArray keys, jobjectArray values, jobjectArray errOut) {
    retrohost::HostConfig cfg;
    cfg.corePath = str(env, corePath);
    cfg.systemDir = str(env, systemDir);
    cfg.saveDir = str(env, saveDir);
    const jsize n = keys ? env->GetArrayLength(keys) : 0;
    for (jsize i = 0; i < n; ++i) {
        auto k = static_cast<jstring>(env->GetObjectArrayElement(keys, i));
        auto v = static_cast<jstring>(env->GetObjectArrayElement(values, i));
        cfg.optionOverrides.emplace_back(str(env, k), str(env, v));
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(v);
    }
    std::string error;
    auto host = RetroHost::create(cfg, error);
    if (!host) { setError(env, errOut, error); return 0; }
    return reinterpret_cast<jlong>(host.release());
}

JNI_FN(void, nativeDestroy)(JNIEnv*, jobject, jlong h) { delete H(h); }

JNI_FN(jobjectArray, nativeSystemInfo)(JNIEnv* env, jobject, jlong h) {
    const auto& si = H(h)->systemInfo();
    return stringArray(env, {si.library_name ? si.library_name : "", si.library_version ? si.library_version : "",
                             si.valid_extensions ? si.valid_extensions : "", si.need_fullpath ? "1" : "0"});
}

JNI_FN(jboolean, nativeLoadGame)(JNIEnv* env, jobject, jlong h, jstring path, jint fd, jstring saveName, jobjectArray errOut) {
    retrohost::GameSource g;
    g.path = str(env, path);
    g.fd = fd;
    g.saveName = str(env, saveName);
    std::string error;
    if (!H(h)->loadGame(g, error)) { setError(env, errOut, error); return JNI_FALSE; }
    return JNI_TRUE;
}

JNI_FN(void, nativeUnloadGame)(JNIEnv*, jobject, jlong h) { H(h)->unloadGame(); }

JNI_FN(jdoubleArray, nativeAvInfo)(JNIEnv* env, jobject, jlong h) {
    const retro_system_av_info av = H(h)->avInfo();
    const jdouble v[8] = {static_cast<double>(av.geometry.base_width), static_cast<double>(av.geometry.base_height),
                          static_cast<double>(av.geometry.max_width), static_cast<double>(av.geometry.max_height),
                          static_cast<double>(av.geometry.aspect_ratio), av.timing.fps, av.timing.sample_rate,
                          static_cast<double>(H(h)->pixelFormat())};
    jdoubleArray arr = env->NewDoubleArray(8);
    env->SetDoubleArrayRegion(arr, 0, 8, v);
    return arr;
}

JNI_FN(void, nativeRunFrames)(JNIEnv*, jobject, jlong h, jint n) { for (jint i = 0; i < n; ++i) H(h)->runFrame(); }
JNI_FN(void, nativeReset)(JNIEnv*, jobject, jlong h) { H(h)->reset(); }
JNI_FN(void, nativeRebindThread)(JNIEnv*, jobject, jlong h) { H(h)->rebindThread(); }
JNI_FN(void, nativeSetFastForward)(JNIEnv*, jobject, jlong h, jboolean on) { H(h)->setFastForward(on); }
JNI_FN(jlong, nativeFramesRun)(JNIEnv*, jobject, jlong h) { return static_cast<jlong>(H(h)->framesRun()); }

JNI_FN(void, nativeSetButtons)(JNIEnv*, jobject, jlong h, jint port, jint mask) {
    H(h)->input().setButtons(static_cast<unsigned>(port), static_cast<uint32_t>(mask));
}
JNI_FN(void, nativeSetAnalog)(JNIEnv*, jobject, jlong h, jint port, jint stick, jint axis, jint value) {
    H(h)->input().setAnalog(port, stick, axis, static_cast<int16_t>(value));
}
JNI_FN(void, nativeSetPointer)(JNIEnv*, jobject, jlong h, jint x, jint y, jboolean pressed) {
    H(h)->input().setPointer(static_cast<int16_t>(x), static_cast<int16_t>(y), pressed);
}

JNI_FN(jint, nativeFlushSaveRam)(JNIEnv* env, jobject, jlong h, jobjectArray errOut) {
    std::string error;
    bool wrote = false;
    if (!H(h)->flushSaveRam(error, &wrote)) { setError(env, errOut, error); return -1; }
    return wrote ? 1 : 0;
}

JNI_FN(jboolean, nativeSaveState)(JNIEnv* env, jobject, jlong h, jstring path, jobjectArray errOut) {
    std::string error;
    if (!H(h)->saveState(str(env, path), error)) { setError(env, errOut, error); return JNI_FALSE; }
    return JNI_TRUE;
}

JNI_FN(jboolean, nativeLoadState)(JNIEnv* env, jobject, jlong h, jstring path, jobjectArray errOut) {
    std::string error;
    if (!H(h)->loadState(str(env, path), error)) { setError(env, errOut, error); return JNI_FALSE; }
    return JNI_TRUE;
}

/** [width, height, format(0=RGB565,1=XRGB8888), sequence], or null before the first frame. */
JNI_FN(jlongArray, nativeFrameInfo)(JNIEnv* env, jobject, jlong h) {
    jlong v[4];
    if (!H(h)->frame().read([&](const retrohost::FrameBuffer::View& f) {
            v[0] = f.width; v[1] = f.height; v[2] = static_cast<jlong>(f.format); v[3] = static_cast<jlong>(f.sequence);
        })) return nullptr;
    jlongArray arr = env->NewLongArray(4);
    env->SetLongArrayRegion(arr, 0, 4, v);
    return arr;
}

/** [width, height, argb...] ready for Bitmap.createBitmap(colors, w, h, ARGB_8888). */
JNI_FN(jintArray, nativeCopyFrameArgb)(JNIEnv* env, jobject, jlong h) {
    std::vector<uint8_t> rgba;
    unsigned w = 0, hgt = 0;
    if (!H(h)->frame().copyRgba(rgba, w, hgt)) return nullptr;
    std::vector<jint> out(2 + static_cast<size_t>(w) * hgt);
    out[0] = static_cast<jint>(w);
    out[1] = static_cast<jint>(hgt);
    for (size_t i = 0, n = static_cast<size_t>(w) * hgt; i < n; ++i) {
        const uint8_t* p = &rgba[i * 4];
        out[2 + i] = static_cast<jint>((0xFFu << 24) | (uint32_t(p[0]) << 16) | (uint32_t(p[1]) << 8) | p[2]);
    }
    jintArray arr = env->NewIntArray(static_cast<jsize>(out.size()));
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(out.size()), out.data());
    return arr;
}

JNI_FN(jint, nativeReadAudio)(JNIEnv* env, jobject, jlong h, jshortArray dst) {
    const jsize len = env->GetArrayLength(dst);
    std::vector<int16_t> tmp(static_cast<size_t>(len));
    const size_t frames = H(h)->audio().pop(tmp.data(), static_cast<size_t>(len) / 2);
    env->SetShortArrayRegion(dst, 0, static_cast<jsize>(frames * 2), tmp.data());
    return static_cast<jint>(frames);
}

/** [framesAvailable, capacityFrames, droppedFrames] */
JNI_FN(jlongArray, nativeAudioStats)(JNIEnv* env, jobject, jlong h) {
    auto& a = H(h)->audio();
    const jlong v[3] = {static_cast<jlong>(a.available()), static_cast<jlong>(a.capacity()), static_cast<jlong>(a.droppedFrames())};
    jlongArray arr = env->NewLongArray(3);
    env->SetLongArrayRegion(arr, 0, 3, v);
    return arr;
}

JNI_FN(jboolean, nativeSetOption)(JNIEnv* env, jobject, jlong h, jstring key, jstring value) {
    return H(h)->options().set(str(env, key), str(env, value)) ? JNI_TRUE : JNI_FALSE;
}

/** Flattened, 6 strings per option: key, value, default, description, values ('|' joined), visible ("1"/"0"). */
JNI_FN(jobjectArray, nativeGetOptions)(JNIEnv* env, jobject, jlong h) {
    std::vector<std::string> flat;
    for (const auto& o : H(h)->options().snapshot()) {
        std::string joined;
        for (size_t i = 0; i < o.values.size(); ++i) { if (i) joined += '|'; joined += o.values[i]; }
        flat.insert(flat.end(), {o.key, o.value, o.defaultValue, o.description, joined, o.visible ? "1" : "0"});
    }
    return stringArray(env, flat);
}

JNI_FN(jstring, nativePollMessage)(JNIEnv* env, jobject, jlong h) {
    std::string msg;
    return H(h)->pollMessage(msg) ? env->NewStringUTF(msg.c_str()) : nullptr;
}

JNI_FN(jintArray, nativeRumble)(JNIEnv* env, jobject, jlong h) {
    const jint v[2] = {H(h)->rumble().strong.load(), H(h)->rumble().weak.load()};
    jintArray arr = env->NewIntArray(2);
    env->SetIntArrayRegion(arr, 0, 2, v);
    return arr;
}

// ------------------------------------------------------------------ emulation runner

JNI_FN(jlong, nativeRunnerCreate)(JNIEnv*, jobject, jlong hostHandle) {
#if defined(__ANDROID__)
    auto video = std::make_unique<retrohost::EglVideoOutput>();
    auto audio = std::make_unique<retrohost::OboeAudioOutput>();
#else
    // Host (JVM test) build: no window system, no audio device.
    auto video = std::make_unique<retrohost::NullVideoOutput>();
    auto audio = std::make_unique<retrohost::NullAudioOutput>();
#endif
    return reinterpret_cast<jlong>(new EmuRunner(H(hostHandle), std::move(video), std::move(audio)));
}

JNI_FN(jboolean, nativeRunnerStart)(JNIEnv* env, jobject, jlong r, jobjectArray errOut) {
    std::string error;
    if (!R(r)->start(error)) { setError(env, errOut, error); return JNI_FALSE; }
    return JNI_TRUE;
}

JNI_FN(void, nativeRunnerDestroy)(JNIEnv*, jobject, jlong r) { delete R(r); }
JNI_FN(void, nativeRunnerSetPaused)(JNIEnv*, jobject, jlong r, jboolean p) { R(r)->setPaused(p); }
JNI_FN(void, nativeRunnerSetSpeed)(JNIEnv*, jobject, jlong r, jdouble s) { R(r)->setSpeed(s); }
JNI_FN(void, nativeRunnerReset)(JNIEnv*, jobject, jlong r) { R(r)->reset(); }

/** quads: 8 floats each = x, y, w, h (surface px) then u0, v0, u1, v1. */
JNI_FN(void, nativeRunnerSetViewports)(JNIEnv* env, jobject, jlong r, jfloatArray quads) {
    const jsize n = env->GetArrayLength(quads) / 8;
    std::vector<retrohost::Quad> q(static_cast<size_t>(n));
    if (n > 0) env->GetFloatArrayRegion(quads, 0, n * 8, reinterpret_cast<jfloat*>(q.data()));
    R(r)->setViewports(std::move(q));
}

/** Blocks until the emulation thread has switched surfaces (required by surfaceDestroyed). */
JNI_FN(void, nativeRunnerSetSurface)(JNIEnv* env, jobject, jlong r, jobject surface) {
#if defined(__ANDROID__)
    ANativeWindow* window = surface ? ANativeWindow_fromSurface(env, surface) : nullptr;  // +1 ref, owned by the output
    R(r)->setWindow(window);
#else
    (void)env;
    R(r)->setWindow(surface ? reinterpret_cast<void*>(1) : nullptr);
#endif
}

JNI_FN(jboolean, nativeRunnerSaveState)(JNIEnv* env, jobject, jlong r, jstring path, jobjectArray errOut) {
    std::string error;
    if (!R(r)->saveState(str(env, path), error)) { setError(env, errOut, error); return JNI_FALSE; }
    return JNI_TRUE;
}

JNI_FN(jboolean, nativeRunnerLoadState)(JNIEnv* env, jobject, jlong r, jstring path, jobjectArray errOut) {
    std::string error;
    if (!R(r)->loadState(str(env, path), error)) { setError(env, errOut, error); return JNI_FALSE; }
    return JNI_TRUE;
}

JNI_FN(jboolean, nativeRunnerFlushSaveRam)(JNIEnv* env, jobject, jlong r, jobjectArray errOut) {
    std::string error;
    if (!R(r)->flushSaveRam(error)) { setError(env, errOut, error); return JNI_FALSE; }
    return JNI_TRUE;
}

/** [framesRun, presented, measuredFps, targetFps, audioFill, underruns, dropped, drcRatio,
 *   paused, hasWindow, coreShutdown, deviceRate, pacerResyncs, videoErrors] */
JNI_FN(jdoubleArray, nativeRunnerStats)(JNIEnv* env, jobject, jlong r) {
    const auto s = R(r)->stats();
    const jdouble v[14] = {static_cast<double>(s.framesRun), static_cast<double>(s.presented), s.measuredFps, s.targetFps,
                           s.audioFillFrames, static_cast<double>(s.audioUnderruns), static_cast<double>(s.audioDropped),
                           s.drcRatio, s.paused ? 1.0 : 0.0, s.hasWindow ? 1.0 : 0.0, s.coreRequestedShutdown ? 1.0 : 0.0,
                           static_cast<double>(s.deviceSampleRate), static_cast<double>(s.pacerResyncs),
                           static_cast<double>(s.videoErrors)};
    jdoubleArray arr = env->NewDoubleArray(14);
    env->SetDoubleArrayRegion(arr, 0, 14, v);
    return arr;
}
