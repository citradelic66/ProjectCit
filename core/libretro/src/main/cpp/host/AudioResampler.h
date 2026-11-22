#pragma once
#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

#include "AudioRing.h"

namespace retrohost {

/**
 * Consumer side of the audio path, run from the audio device callback.
 *
 * Converts the core's rate (mGBA/melonDS: 32768 Hz) to the device rate
 * (usually 48000 Hz) by linear interpolation, with dynamic rate control:
 * the resampling ratio is nudged by at most ±maxDrift so the ring buffer
 * hovers around targetFrames. That absorbs the mismatch between the emulated
 * clock (paced at 59.73/59.83 Hz) and the audio device's crystal, so the
 * buffer never drains (crackle) or overflows (dropped samples), without the
 * audible pitch change of larger corrections.
 *
 * Real-time safe: no allocation, no locks after configure().
 */
class AudioResampler {
public:
    explicit AudioResampler(AudioRing& ring) : ring_(ring), chunk_(kChunk * 2) {}

    void configure(double inRate, double outRate, size_t targetFrames, double maxDrift = 0.005) {
        baseStep_ = inRate / outRate;
        target_ = static_cast<double>(targetFrames);
        maxDrift_ = maxDrift;
        smoothedErr_ = 0.0;
        reprime();
    }

    /** Muted (fast-forward): drain everything and play silence. */
    void setMuted(bool m) { muted_.store(m, std::memory_order_relaxed); }

    /** After pause/underrun: wait for the buffer to refill to target before playing. */
    void reprime() { priming_ = true; pos_ = 0.0; idx_ = len_ = 0; a_[0] = a_[1] = b_[0] = b_[1] = 0.0f; }

    /** Fills numFrames interleaved stereo floats. Returns frames of real (non-silent) output. */
    size_t render(float* out, size_t numFrames) {
        if (muted_.load(std::memory_order_relaxed)) {
            int16_t sink[kChunk * 2];
            while (ring_.pop(sink, kChunk) > 0) {}
            std::fill(out, out + numFrames * 2, 0.0f);
            reprime();
            return 0;
        }
        const double fill = static_cast<double>(ring_.available() + (len_ - idx_));
        if (priming_) {
            if (fill < target_) { std::fill(out, out + numFrames * 2, 0.0f); return 0; }
            priming_ = false;
            if (!pull(a_) || !pull(b_)) { std::fill(out, out + numFrames * 2, 0.0f); priming_ = true; return 0; }
        }
        // Dynamic rate control: proportional to the buffer error, smoothed.
        double err = (fill - target_) / target_;
        err = std::clamp(err, -1.0, 1.0);
        smoothedErr_ += 0.05 * (err - smoothedErr_);
        step_ = baseStep_ * (1.0 + maxDrift_ * smoothedErr_);

        size_t real = 0;
        for (size_t i = 0; i < numFrames; ++i) {
            const float t = static_cast<float>(pos_);
            out[i * 2] = (a_[0] + (b_[0] - a_[0]) * t) * (1.0f / 32768.0f);
            out[i * 2 + 1] = (a_[1] + (b_[1] - a_[1]) * t) * (1.0f / 32768.0f);
            ++real;
            pos_ += step_;
            while (pos_ >= 1.0) {
                pos_ -= 1.0;
                a_[0] = b_[0]; a_[1] = b_[1];
                if (!pull(b_)) {  // underrun: silence the rest and refill before resuming
                    underruns_.fetch_add(1, std::memory_order_relaxed);
                    std::fill(out + (i + 1) * 2, out + numFrames * 2, 0.0f);
                    reprime();
                    return real;
                }
            }
        }
        return real;
    }

    double ratio() const { return step_ / baseStep_; }
    uint64_t underruns() const { return underruns_.load(std::memory_order_relaxed); }

private:
    static constexpr size_t kChunk = 256;

    bool pull(float* frame) {
        if (idx_ >= len_) {
            len_ = ring_.pop(chunk_.data(), kChunk);
            idx_ = 0;
            if (len_ == 0) return false;
        }
        frame[0] = chunk_[idx_ * 2];
        frame[1] = chunk_[idx_ * 2 + 1];
        ++idx_;
        return true;
    }

    AudioRing& ring_;
    std::vector<int16_t> chunk_;
    size_t idx_ = 0, len_ = 0;
    float a_[2]{}, b_[2]{};
    double pos_ = 0.0, step_ = 1.0, baseStep_ = 1.0;
    double target_ = 2048.0, maxDrift_ = 0.005, smoothedErr_ = 0.0;
    bool priming_ = true;
    std::atomic<bool> muted_{false};
    std::atomic<uint64_t> underruns_{0};
};

}  // namespace retrohost
