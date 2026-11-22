#pragma once
#include <chrono>

namespace retrohost {

/**
 * Paces emulation at the console's own rate (59.7275 Hz GBA, 59.8261 Hz NDS)
 * from a monotonic clock, independent of the display's 60/90/120 Hz vsync.
 * Deadlines accumulate (no drift); if we fall far behind (pause, slow device,
 * debugger) it resyncs instead of fast-forwarding to catch up.
 */
class FramePacer {
public:
    using Clock = std::chrono::steady_clock;

    void setRate(double fps, double speed = 1.0) {
        const double hz = fps * (speed > 0 ? speed : 1.0);
        period_ = std::chrono::duration_cast<Clock::duration>(std::chrono::duration<double>(1.0 / hz));
    }
    Clock::duration period() const { return period_; }

    void reset(Clock::time_point now) { deadline_ = now; resyncs_ = 0; }

    /** Call after each emulated frame; returns when the next one should start. */
    Clock::time_point next(Clock::time_point now) {
        deadline_ += period_;
        if (now - deadline_ > period_ * kMaxLagFrames) {
            deadline_ = now;  // too far behind: drop the debt rather than burst
            ++resyncs_;
        }
        return deadline_;
    }
    unsigned resyncs() const { return resyncs_; }

private:
    static constexpr int kMaxLagFrames = 4;
    Clock::duration period_ = std::chrono::microseconds(16667);
    Clock::time_point deadline_{};
    unsigned resyncs_ = 0;
};

}  // namespace retrohost
