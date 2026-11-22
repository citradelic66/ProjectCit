#pragma once
#include <EGL/egl.h>

#include <string>

namespace retrohost {

/**
 * EGL context that lives as long as the emulation session, independent of any
 * window. With no window it is current on a 1×1 pbuffer (or surfaceless), so GL
 * objects survive surface destruction on fold/rotate/background.
 */
class EglContext {
public:
    bool init(std::string& error);
    void destroy();
    bool attachWindow(EGLNativeWindowType window, std::string& error);
    void detachWindow();
    bool hasWindow() const { return window_ != EGL_NO_SURFACE; }
    bool swap();
    /** Current window surface size in pixels. */
    bool windowSize(int& w, int& h) const;

private:
    bool makeOffscreenCurrent();
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface pbuffer_ = EGL_NO_SURFACE;
    EGLSurface window_ = EGL_NO_SURFACE;
};

}  // namespace retrohost
