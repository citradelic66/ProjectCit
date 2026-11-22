#pragma once
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace retrohost {

/**
 * Lock-free single-producer / single-consumer ring of interleaved stereo int16
 * frames. Producer = emulation thread (libretro audio callbacks); consumer = the
 * Oboe callback thread in Phase 3. When full, new frames are dropped and counted
 * (the Phase 3 rate controller keeps it from filling in steady state).
 */
class AudioRing {
public:
    explicit AudioRing(size_t capacityFrames = 16384)
        : buf_(roundUpPow2(capacityFrames) * 2), mask_(roundUpPow2(capacityFrames) - 1) {}

    size_t capacity() const { return mask_ + 1; }
    size_t available() const {
        return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire);
    }
    double fillRatio() const { return static_cast<double>(available()) / static_cast<double>(capacity()); }
    uint64_t droppedFrames() const { return dropped_.load(std::memory_order_relaxed); }

    /** Producer side. Returns frames accepted. */
    size_t push(const int16_t* interleaved, size_t frames) {
        const size_t head = head_.load(std::memory_order_relaxed);
        const size_t tail = tail_.load(std::memory_order_acquire);
        const size_t room = capacity() - (head - tail);
        const size_t n = frames < room ? frames : room;
        for (size_t i = 0; i < n; ++i) {
            const size_t slot = ((head + i) & mask_) * 2;
            buf_[slot] = interleaved[i * 2];
            buf_[slot + 1] = interleaved[i * 2 + 1];
        }
        head_.store(head + n, std::memory_order_release);
        if (n < frames) dropped_.fetch_add(frames - n, std::memory_order_relaxed);
        return n;
    }

    /** Consumer side. Returns frames copied into out (interleaved). */
    size_t pop(int16_t* out, size_t frames) {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        const size_t head = head_.load(std::memory_order_acquire);
        const size_t have = head - tail;
        const size_t n = frames < have ? frames : have;
        for (size_t i = 0; i < n; ++i) {
            const size_t slot = ((tail + i) & mask_) * 2;
            out[i * 2] = buf_[slot];
            out[i * 2 + 1] = buf_[slot + 1];
        }
        tail_.store(tail + n, std::memory_order_release);
        return n;
    }

    /** Only safe while neither side is running (e.g. on load/reset). */
    void clear() { tail_.store(head_.load()); }

private:
    static size_t roundUpPow2(size_t v) { size_t p = 1; while (p < v) p <<= 1; return p; }
    std::vector<int16_t> buf_;
    const size_t mask_;
    alignas(64) std::atomic<size_t> head_{0};
    alignas(64) std::atomic<size_t> tail_{0};
    std::atomic<uint64_t> dropped_{0};
};

}  // namespace retrohost
