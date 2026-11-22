#pragma once
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

namespace retrohost {

/** Pixel formats handed to consumers. Legacy 0RGB1555 is converted to RGB565 on write. */
enum class PixelFormat : int { RGB565 = 0, XRGB8888 = 1 };

/**
 * Latest video frame, double-buffered. The emulation thread writes (copying out of
 * the core's buffer, which is only valid during the callback); the GL thread in
 * Phase 3 reads the front buffer under the same short lock.
 */
class FrameBuffer {
public:
    struct View {
        const uint8_t* pixels;
        unsigned width, height;
        size_t pitch;   // bytes per row (tightly packed)
        PixelFormat format;
        uint64_t sequence;
    };

    /** @param srcFormat the RETRO_PIXEL_FORMAT_* value the core negotiated. */
    void write(const void* data, unsigned width, unsigned height, size_t srcPitch, int srcFormat);
    /** Core passed NULL (frame dupe): content unchanged, but count it. */
    void markDuped() { ++dupes_; }

    /** Runs fn(View) with the front buffer locked. Returns false if no frame yet. */
    template <typename Fn> bool read(Fn&& fn) const {
        std::lock_guard<std::mutex> lock(mutex_);
        if (front_.width == 0) return false;
        fn(View{front_.pixels.data(), front_.width, front_.height, front_.pitch, front_.format, sequence_});
        return true;
    }

    /** Converts the front buffer to RGBA8888 (R,G,B,A byte order) for PNG thumbnails. */
    bool copyRgba(std::vector<uint8_t>& out, unsigned& w, unsigned& h) const;

    uint64_t sequence() const { std::lock_guard<std::mutex> l(mutex_); return sequence_; }
    uint64_t dupes() const { return dupes_; }

private:
    struct Buf {
        std::vector<uint8_t> pixels;
        unsigned width = 0, height = 0;
        size_t pitch = 0;
        PixelFormat format = PixelFormat::RGB565;
    };
    Buf back_, front_;
    uint64_t sequence_ = 0;
    uint64_t dupes_ = 0;
    mutable std::mutex mutex_;
};

}  // namespace retrohost
