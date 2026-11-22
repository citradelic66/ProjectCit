#pragma once
#include <cstdint>
#include <string>
#include <vector>

#include "AudioRing.h"
#include "FrameBuffer.h"

namespace retrohost {

/** One screen quad: destination in surface pixels (top-left origin) + source UV region of the core frame. */
struct Quad {
    float x, y, w, h;
    float u0, v0, u1, v1;
};

/** Video sink; every method is called on the emulation thread. */
class VideoOutput {
public:
    virtual ~VideoOutput() = default;
    virtual bool init(std::string& error) = 0;
    /** nativeWindow: ANativeWindow* on Android (ownership transferred), nullptr to detach. */
    virtual void setWindow(void* nativeWindow) = 0;
    virtual bool hasWindow() const = 0;
    virtual void present(const FrameBuffer& frame, const std::vector<Quad>& quads) = 0;
    virtual uint64_t presented() const = 0;
    /** Failed attaches/swaps (each one logged); surfaced in the debug overlay. */
    virtual uint64_t errors() const { return 0; }
};

/** Audio sink; start/pause/resume/stop are called on the emulation thread. */
class AudioOutput {
public:
    virtual ~AudioOutput() = default;
    virtual bool start(AudioRing& ring, double coreSampleRate, double coreFps, std::string& error) = 0;
    virtual void pause() = 0;
    virtual void resume() = 0;
    virtual void stop() = 0;
    virtual void setMuted(bool muted) = 0;
    virtual uint64_t underruns() const { return 0; }
    virtual double drcRatio() const { return 1.0; }
    virtual int deviceSampleRate() const { return 0; }
};

class NullVideoOutput final : public VideoOutput {
public:
    bool init(std::string&) override { return true; }
    void setWindow(void* w) override { window_ = w != nullptr; }
    bool hasWindow() const override { return window_; }
    void present(const FrameBuffer&, const std::vector<Quad>&) override { if (window_) ++presented_; }
    uint64_t presented() const override { return presented_; }
private:
    bool window_ = false;
    uint64_t presented_ = 0;
};

class NullAudioOutput final : public AudioOutput {
public:
    bool start(AudioRing&, double, double, std::string&) override { return true; }
    void pause() override {}
    void resume() override {}
    void stop() override {}
    void setMuted(bool) override {}
};

}  // namespace retrohost
