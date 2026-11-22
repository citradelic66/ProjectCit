#pragma once
#include <oboe/Oboe.h>

#include <atomic>
#include <memory>
#include <mutex>

#include "../host/AudioResampler.h"
#include "../host/Outputs.h"

namespace retrohost {

/**
 * Oboe (AAudio, OpenSL ES fallback) low-latency float stereo stream. The device
 * picks its native rate (usually 48 kHz); AudioResampler converts from the core
 * rate with dynamic rate control. Reopens itself when the device changes
 * (headphones unplugged, Bluetooth connected).
 */
class OboeAudioOutput final : public AudioOutput,
                              public oboe::AudioStreamDataCallback,
                              public oboe::AudioStreamErrorCallback {
public:
    ~OboeAudioOutput() override { stop(); }

    bool start(AudioRing& ring, double coreSampleRate, double coreFps, std::string& error) override;
    void pause() override;
    void resume() override;
    void stop() override;
    void setMuted(bool muted) override { if (resampler_) resampler_->setMuted(muted); }
    uint64_t underruns() const override { return resampler_ ? resampler_->underruns() : 0; }
    double drcRatio() const override { return resampler_ ? resampler_->ratio() : 1.0; }
    int deviceSampleRate() const override { return deviceRate_; }

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData, int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    bool open(std::string& error);
    std::mutex mutex_;
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<AudioResampler> resampler_;
    AudioRing* ring_ = nullptr;
    double coreRate_ = 32768, coreFps_ = 60;
    std::atomic<int> deviceRate_{0};
    std::atomic<bool> paused_{false};
    std::atomic<bool> stopped_{true};
};

}  // namespace retrohost
