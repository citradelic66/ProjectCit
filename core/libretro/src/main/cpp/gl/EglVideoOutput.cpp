#include "EglVideoOutput.h"

#include "../host/Log.h"

#if defined(__ANDROID__)
#include <android/native_window.h>
#endif

namespace retrohost {

EglVideoOutput::~EglVideoOutput() {
    if (ready_) {
        renderer_.destroy();
        egl_.destroy();
    }
    releaseWindow();
}

bool EglVideoOutput::init(std::string& error) {
    if (!egl_.init(error)) return false;
    if (!renderer_.init(error)) { egl_.destroy(); return false; }
    ready_ = true;
    return true;
}

void EglVideoOutput::releaseWindow() {
#if defined(__ANDROID__)
    if (window_) ANativeWindow_release(static_cast<ANativeWindow*>(window_));
#endif
    window_ = nullptr;
}

void EglVideoOutput::setWindow(void* nativeWindow) {
    // Same window again (SurfaceView reports a resize on fold/rotate): keep the EGL
    // surface. EGL picks up the new size by itself; tearing it down and recreating it
    // on every resize is needless churn and a failure point (device report: black
    // screens after rotating).
    if (nativeWindow && nativeWindow == window_ && egl_.hasWindow()) {
#if defined(__ANDROID__)
        ANativeWindow_release(static_cast<ANativeWindow*>(nativeWindow));  // drop the extra ref from fromSurface()
#endif
        return;
    }
    egl_.detachWindow();  // must finish with the old surface before releasing it
    releaseWindow();
    if (!nativeWindow || !ready_) return;
    window_ = nativeWindow;
    if (!attach()) releaseWindow();
}

bool EglVideoOutput::attach() {
    std::string err;
    if (egl_.attachWindow(reinterpret_cast<EGLNativeWindowType>(window_), err)) return true;
    ++errors_;
    LOGE("attach window failed: %s (egl 0x%x)", err.c_str(), eglGetError());
    return false;
}

void EglVideoOutput::present(const FrameBuffer& frame, const std::vector<Quad>& quads) {
    if (!ready_ || !window_) return;
    if (!egl_.hasWindow() && !attach()) return;  // a previous swap failure dropped it: retry
    int w = 0, h = 0;
    if (!egl_.windowSize(w, h) || w <= 0 || h <= 0) { ++errors_; return; }
    renderer_.upload(frame);
    renderer_.draw(w, h, quads);
    if (egl_.swap()) { ++presented_; return; }
    // Swap failed: the surface behind our EGLSurface went bad. Recreate it once from
    // the window we still hold; if that fails too, present() retries next frame.
    ++errors_;
    const EGLint e = eglGetError();
    LOGE("eglSwapBuffers failed (0x%x), recreating window surface", e);
    egl_.detachWindow();
    attach();
}

}  // namespace retrohost
