#include "EglContext.h"

#include <EGL/eglext.h>

#include <cstring>

#include "../host/Log.h"

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x00000040
#endif
#ifndef EGL_PLATFORM_SURFACELESS_MESA
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#endif

namespace retrohost {

bool EglContext::init(std::string& error) {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint major = 0, minor = 0;
    if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, &major, &minor)) {
#if !defined(__ANDROID__)
        // Headless Linux (CI, tests): Mesa's surfaceless platform.
        display_ = eglGetPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
        if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, &major, &minor)) {
            error = "eglInitialize failed";
            return false;
        }
#else
        error = "eglInitialize failed";
        return false;
#endif
    }
    eglBindAPI(EGL_OPENGL_ES_API);

    const EGLint surfaceKinds[] = {EGL_WINDOW_BIT | EGL_PBUFFER_BIT, EGL_PBUFFER_BIT, 0};
    EGLint numConfigs = 0;
    for (EGLint kinds : surfaceKinds) {
        const EGLint attribs[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, kinds,
                                  EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
        if (eglChooseConfig(display_, attribs, &config_, 1, &numConfigs) && numConfigs > 0) break;
    }
    if (numConfigs < 1) { error = "no EGL config with OpenGL ES 3"; return false; }

    const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttribs);
    if (context_ == EGL_NO_CONTEXT) { error = "eglCreateContext(ES 3) failed"; return false; }

    const EGLint pbAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    pbuffer_ = eglCreatePbufferSurface(display_, config_, pbAttribs);  // may fail: surfaceless fallback
    if (!makeOffscreenCurrent()) { error = "cannot make EGL context current offscreen"; return false; }
    LOGI("EGL %d.%d, GLES context ready", major, minor);
    return true;
}

bool EglContext::makeOffscreenCurrent() {
    return eglMakeCurrent(display_, pbuffer_, pbuffer_, context_) == EGL_TRUE;
}

bool EglContext::attachWindow(EGLNativeWindowType window, std::string& error) {
    detachWindow();
    window_ = eglCreateWindowSurface(display_, config_, window, nullptr);
    if (window_ == EGL_NO_SURFACE) { error = "eglCreateWindowSurface failed"; return false; }
    if (!eglMakeCurrent(display_, window_, window_, context_)) {
        eglDestroySurface(display_, window_);
        window_ = EGL_NO_SURFACE;
        makeOffscreenCurrent();
        error = "eglMakeCurrent(window) failed";
        return false;
    }
    // Present without waiting for vsync: pacing is ours (console rate), and the
    // compositor latches the newest buffer, so this does not tear.
    eglSwapInterval(display_, 0);
    return true;
}

void EglContext::detachWindow() {
    if (window_ == EGL_NO_SURFACE) return;
    makeOffscreenCurrent();
    eglDestroySurface(display_, window_);
    window_ = EGL_NO_SURFACE;
}

bool EglContext::swap() { return window_ != EGL_NO_SURFACE && eglSwapBuffers(display_, window_) == EGL_TRUE; }

bool EglContext::windowSize(int& w, int& h) const {
    if (window_ == EGL_NO_SURFACE) return false;
    return eglQuerySurface(display_, window_, EGL_WIDTH, &w) && eglQuerySurface(display_, window_, EGL_HEIGHT, &h);
}

void EglContext::destroy() {
    if (display_ == EGL_NO_DISPLAY) return;
    detachWindow();
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    if (pbuffer_ != EGL_NO_SURFACE) eglDestroySurface(display_, pbuffer_);
    if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
    eglTerminate(display_);
    display_ = EGL_NO_DISPLAY;
    context_ = EGL_NO_CONTEXT;
    pbuffer_ = EGL_NO_SURFACE;
}

}  // namespace retrohost
