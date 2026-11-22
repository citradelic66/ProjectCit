#include "EmuRunner.h"

#include <cmath>
#include <future>

#include "Log.h"

namespace retrohost {

using Clock = FramePacer::Clock;

EmuRunner::EmuRunner(RetroHost* host, std::unique_ptr<VideoOutput> video, std::unique_ptr<AudioOutput> audio)
    : host_(host), video_(std::move(video)), audio_(std::move(audio)) {}

EmuRunner::~EmuRunner() { stop(); }

bool EmuRunner::start(std::string& error) {
    if (thread_.joinable()) return true;
    std::promise<bool> ready;
    auto readyFuture = ready.get_future();
    thread_ = std::thread([this, &ready] {
        host_->rebindThread();  // the core now belongs to this thread
        std::string err;
        if (!video_->init(err)) {
            startError_ = err;
            ready.set_value(false);
            return;
        }
        const auto av = host_->avInfo();
        if (!audio_->start(host_->audio(), av.timing.sample_rate, av.timing.fps, err)) {
            LOGW("audio unavailable, running silent: %s", err.c_str());
        }
        ready.set_value(true);
        loop();
        audio_->stop();
        video_->setWindow(nullptr);
    });
    if (!readyFuture.get()) {
        thread_.join();
        error = "video init failed: " + startError_;
        return false;
    }
    started_ = true;
    return true;
}

void EmuRunner::stop() {
    if (!thread_.joinable()) return;
    {
        std::lock_guard<std::mutex> l(mutex_);
        quit_ = true;
    }
    cv_.notify_all();
    thread_.join();
    // Remaining commands (if any) were never run; release any waiters.
    std::lock_guard<std::mutex> l(mutex_);
    commands_.clear();
}

void EmuRunner::post(std::function<void()> fn) {
    {
        std::lock_guard<std::mutex> l(mutex_);
        commands_.push_back(std::move(fn));
    }
    cv_.notify_all();
}

void EmuRunner::call(std::function<void()> fn) {
    if (!thread_.joinable()) return;
    if (std::this_thread::get_id() == thread_.get_id()) { fn(); return; }
    auto done = std::make_shared<std::promise<void>>();
    auto fut = done->get_future();
    post([fn = std::move(fn), done] { fn(); done->set_value(); });
    fut.wait();
}

void EmuRunner::drainCommands() {
    for (;;) {
        std::function<void()> fn;
        {
            std::lock_guard<std::mutex> l(mutex_);
            if (commands_.empty()) return;
            fn = std::move(commands_.front());
            commands_.pop_front();
        }
        fn();
    }
}

void EmuRunner::setPaused(bool paused) {
    {
        std::lock_guard<std::mutex> l(mutex_);
        if (paused_ == paused) return;
        paused_ = paused;
    }
    post([this, paused] {
        if (paused) {
            audio_->pause();
            std::string err;
            if (!host_->flushSaveRam(err)) LOGE("flush on pause failed: %s", err.c_str());
        } else {
            audio_->resume();
        }
    });
}

void EmuRunner::setSpeed(double speed) {
    {
        std::lock_guard<std::mutex> l(mutex_);
        speed_ = speed > 0.1 ? speed : 0.1;
    }
    cv_.notify_all();
}

void EmuRunner::setViewports(std::vector<Quad> quads) {
    std::lock_guard<std::mutex> l(mutex_);
    quads_ = std::move(quads);
}

void EmuRunner::setWindow(void* nativeWindow) {
    call([this, nativeWindow] {
        video_->setWindow(nativeWindow);
        hasWindow_ = video_->hasWindow();
        // Draw the current frame immediately so a new surface is never left black while paused.
        std::vector<Quad> q;
        { std::lock_guard<std::mutex> l(mutex_); q = quads_; }
        video_->present(host_->frame(), q);
        presented_ = video_->presented();
        videoErrors_ = video_->errors();
    });
}

bool EmuRunner::saveState(const std::string& path, std::string& error) {
    bool ok = false;
    call([&] { ok = host_->saveState(path, error); });
    if (!thread_.joinable()) error = "not running";
    return ok;
}

bool EmuRunner::loadState(const std::string& path, std::string& error) {
    bool ok = false;
    call([&] { ok = host_->loadState(path, error); });
    if (!thread_.joinable()) error = "not running";
    return ok;
}

bool EmuRunner::flushSaveRam(std::string& error) {
    bool ok = false;
    call([&] { ok = host_->flushSaveRam(error); });
    return ok;
}

void EmuRunner::reset() { post([this] { host_->reset(); }); }

EmuRunner::Stats EmuRunner::stats() const {
    Stats s;
    s.framesRun = host_->framesRun();
    s.presented = presented_;
    s.videoErrors = videoErrors_;
    s.pacerResyncs = resyncs_;
    s.measuredFps = measuredFps_;
    s.targetFps = targetFps_;
    s.audioUnderruns = audio_->underruns();
    s.audioDropped = host_->audio().droppedFrames();
    s.audioFillFrames = static_cast<double>(host_->audio().available());
    s.drcRatio = audio_->drcRatio();
    s.deviceSampleRate = audio_->deviceSampleRate();
    s.hasWindow = hasWindow_;
    s.coreRequestedShutdown = host_->shutdownRequested();
    std::lock_guard<std::mutex> l(mutex_);
    s.paused = paused_;
    return s;
}

void EmuRunner::loop() {
    FramePacer pacer;
    double speed = -1;
    bool wasRunning = false;
    std::vector<Quad> quads;
    Clock::time_point nextFrameAt = Clock::now();
    Clock::time_point fpsWindowStart{};
    uint64_t fpsWindowFrames = 0;
    bool fpsWindowOpen = false;
    auto lastPresent = Clock::now() - std::chrono::seconds(1);
    constexpr auto kMinPresentInterval = std::chrono::microseconds(8000);  // cap at ~120 Hz when fast-forwarding

    for (;;) {
        drainCommands();
        double wantSpeed;
        {
            std::unique_lock<std::mutex> l(mutex_);
            if (quit_) break;
            const bool paused = paused_ || !host_->gameLoaded() || host_->shutdownRequested();
            if (paused) {
                // Idle until a command, resume, or quit: no busy loop while paused.
                cv_.wait_for(l, std::chrono::milliseconds(250), [this] { return quit_ || !commands_.empty() || !paused_; });
                wasRunning = false;
                fpsWindowOpen = false;
                continue;
            }
            wantSpeed = speed_;
            if (wasRunning && wantSpeed == speed && Clock::now() < nextFrameAt) {
                // Not time yet. Commands wake us early, but they never pull a frame forward.
                cv_.wait_until(l, nextFrameAt, [&] { return quit_ || !commands_.empty() || paused_ || speed_ != speed; });
                continue;
            }
            quads = quads_;
        }

        if (!wasRunning || wantSpeed != speed) {
            const auto av = host_->avInfo();
            const bool ffChanged = (wantSpeed > 1.0) != (speed > 1.0) || speed < 0;
            speed = wantSpeed;
            pacer.setRate(av.timing.fps > 0 ? av.timing.fps : 60.0, speed);
            pacer.reset(Clock::now());
            if (ffChanged) audio_->setMuted(speed > 1.0);
            targetFps_ = av.timing.fps * speed;
            wasRunning = true;
        }

        host_->runFrame();
        const auto now = Clock::now();
        // fps = frame intervals / elapsed, measured between two frame starts
        // (counting the window's first frame too would over-report by one frame).
        if (!fpsWindowOpen) {
            fpsWindowStart = now;
            fpsWindowFrames = 0;
            fpsWindowOpen = true;
        } else if (++fpsWindowFrames, now - fpsWindowStart >= std::chrono::seconds(1)) {
            measuredFps_ = fpsWindowFrames / std::chrono::duration<double>(now - fpsWindowStart).count();
            fpsWindowStart = now;
            fpsWindowFrames = 0;
        }

        if (speed <= 1.0 || now - lastPresent >= kMinPresentInterval) {
            video_->present(host_->frame(), quads);
            presented_ = video_->presented();
            videoErrors_ = video_->errors();
            lastPresent = now;
        }
        // Battery saves: cheap memcmp each second, a file write only when changed.
        if (host_->framesRun() % 60 == 0) {
            std::string err;
            if (!host_->flushSaveRam(err)) LOGE("periodic save flush failed: %s", err.c_str());
        }
        nextFrameAt = pacer.next(Clock::now());
        resyncs_ = pacer.resyncs();
    }
    std::string err;
    if (!host_->flushSaveRam(err)) LOGE("final flush failed: %s", err.c_str());
}

}  // namespace retrohost
