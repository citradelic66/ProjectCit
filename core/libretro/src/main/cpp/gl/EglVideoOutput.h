#pragma once
#include "../host/Outputs.h"
#include "EglContext.h"
#include "GlRenderer.h"

namespace retrohost {

/** VideoOutput backed by EGL + GlRenderer. On Android the window is an ANativeWindow*. */
class EglVideoOutput final : public VideoOutput {
public:
    ~EglVideoOutput() override;
    bool init(std::string& error) override;
    void setWindow(void* nativeWindow) override;
    bool hasWindow() const override { return egl_.hasWindow(); }
    void present(const FrameBuffer& frame, const std::vector<Quad>& quads) override;
    uint64_t presented() const override { return presented_; }

private:
    void releaseWindow();
    EglContext egl_;
    GlRenderer renderer_;
    void* window_ = nullptr;
    bool ready_ = false;
    uint64_t presented_ = 0;
};

}  // namespace retrohost
