#include "OboeAudioOutput.h"

#include "../host/Log.h"

namespace retrohost {

bool OboeAudioOutput::start(AudioRing& ring, double coreSampleRate, double coreFps, std::string& error) {
    std::lock_guard<std::mutex> l(mutex_);
    ring_ = &ring;
    coreRate_ = coreSampleRate > 0 ? coreSampleRate : 32768;
    coreFps_ = coreFps > 0 ? coreFps : 60;
    resampler_ = std::make_unique<AudioResampler>(ring);
    stopped_ = false;
    return open(error);
}

bool OboeAudioOutput::open(std::string& error) {
    oboe::AudioStreamBuilder b;
    b.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        ->setSharingMode(oboe::SharingMode::Exclusive)
        ->setFormat(oboe::AudioFormat::Float)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setUsage(oboe::Usage::Game)
        ->setContentType(oboe::ContentType::Music)
        ->setDataCallback(this)
        ->setErrorCallback(this);
    oboe::Result r = b.openStream(stream_);
    if (r != oboe::Result::OK) { error = std::string("openStream: ") + oboe::convertToText(r); return false; }
    deviceRate_ = stream_->getSampleRate();
    // Keep ~4 emulated frames buffered (~67 ms): enough to ride out the bursty
    // once-per-frame production without audible latency.
    const auto target = static_cast<size_t>(coreRate_ / coreFps_ * 4.0);
    resampler_->configure(coreRate_, deviceRate_, target);
    stream_->setBufferSizeInFrames(stream_->getFramesPerBurst() * 2);
    if (!paused_) {
        r = stream_->requestStart();
        if (r != oboe::Result::OK) { error = std::string("requestStart: ") + oboe::convertToText(r); return false; }
    }
    LOGI("audio: %d Hz device, core %.0f Hz, burst %d, api %s", deviceRate_.load(), coreRate_,
         stream_->getFramesPerBurst(), stream_->getAudioApi() == oboe::AudioApi::AAudio ? "AAudio" : "OpenSL ES");
    return true;
}

void OboeAudioOutput::pause() {
    std::lock_guard<std::mutex> l(mutex_);
    paused_ = true;
    if (stream_) stream_->requestPause();
}

void OboeAudioOutput::resume() {
    std::lock_guard<std::mutex> l(mutex_);
    paused_ = false;
    if (resampler_) resampler_->reprime();
    if (stream_) stream_->requestStart();
}

void OboeAudioOutput::stop() {
    std::lock_guard<std::mutex> l(mutex_);
    stopped_ = true;
    if (stream_) {
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
}

oboe::DataCallbackResult OboeAudioOutput::onAudioReady(oboe::AudioStream*, void* audioData, int32_t numFrames) {
    resampler_->render(static_cast<float*>(audioData), static_cast<size_t>(numFrames));
    return oboe::DataCallbackResult::Continue;
}

void OboeAudioOutput::onErrorAfterClose(oboe::AudioStream*, oboe::Result error) {
    // Typically ErrorDisconnected: the output device changed. Reopen on the new one.
    LOGW("audio stream closed (%s), reopening", oboe::convertToText(error));
    std::lock_guard<std::mutex> l(mutex_);
    if (stopped_) return;
    stream_.reset();
    std::string err;
    if (!open(err)) LOGE("audio reopen failed: %s", err.c_str());
}

}  // namespace retrohost
