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
    egl_.detachWindow();  // must finish with the old surface before releasing it
    releaseWindow();
    if (!nativeWindow || !ready_) return;
    window_ = nativeWindow;
    std::string err;
    if (!egl_.attachWindow(reinterpret_cast<EGLNativeWindowType>(nativeWindow), err)) {
        LOGE("attach window failed: %s", err.c_str());
        releaseWindow();
    }
}

void EglVideoOutput::present(const FrameBuffer& frame, const std::vector<Quad>& quads) {
    if (!ready_ || !egl_.hasWindow()) return;
    int w = 0, h = 0;
    if (!egl_.windowSize(w, h) || w <= 0 || h <= 0) return;
    renderer_.upload(frame);
    renderer_.draw(w, h, quads);
    if (egl_.swap()) ++presented_;
}

}  // namespace retrohost
