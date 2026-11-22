#pragma once
#include <atomic>
#include <condition_variable>
#include <deque>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "FramePacer.h"
#include "Outputs.h"
#include "RetroHost.h"

namespace retrohost {

/**
 * The emulation thread. Owns *all* core execution, the GL context (via
 * VideoOutput) and pacing, so nothing about it depends on an Activity:
 * surfaces come and go (fold, rotate, background) through setWindow(), and the
 * core never notices.
 *
 * Every public method is thread-safe. Methods returning results block until
 * the emulation thread has executed them (between two frames).
 */
class EmuRunner {
public:
    struct Stats {
        uint64_t framesRun = 0, presented = 0, videoErrors = 0, audioUnderruns = 0, audioDropped = 0, pacerResyncs = 0;
        double measuredFps = 0, targetFps = 0, drcRatio = 1, audioFillFrames = 0;
        bool paused = false, hasWindow = false, coreRequestedShutdown = false;
        int deviceSampleRate = 0;
    };

    EmuRunner(RetroHost* host, std::unique_ptr<VideoOutput> video, std::unique_ptr<AudioOutput> audio);
    ~EmuRunner();  // stops and joins

    /** Starts the thread (paused=false). Returns false with error if video init failed. */
    bool start(std::string& error);
    void stop();

    void setPaused(bool paused);
    /** 1.0 = normal. >1 fast-forwards (audio muted), <1 slow motion. */
    void setSpeed(double speed);
    void setViewports(std::vector<Quad> quads);
    /** Blocks until the window is attached/detached on the emulation thread. */
    void setWindow(void* nativeWindow);

    bool saveState(const std::string& path, std::string& error);
    bool loadState(const std::string& path, std::string& error);
    bool flushSaveRam(std::string& error);
    void reset();

    Stats stats() const;

private:
    void loop();
    /** Runs fn on the emulation thread and waits for it. */
    void call(std::function<void()> fn);
    void post(std::function<void()> fn);
    void drainCommands();

    RetroHost* host_;
    std::unique_ptr<VideoOutput> video_;
    std::unique_ptr<AudioOutput> audio_;
    std::thread thread_;

    mutable std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<std::function<void()>> commands_;
    bool quit_ = false;
    bool paused_ = false;
    double speed_ = 1.0;
    std::vector<Quad> quads_;
    bool started_ = false;
    std::string startError_;

    // stats (written on the emulation thread)
    std::atomic<uint64_t> presented_{0}, resyncs_{0}, videoErrors_{0};
    std::atomic<double> measuredFps_{0}, targetFps_{0};
    std::atomic<bool> hasWindow_{false};
};

}  // namespace retrohost
