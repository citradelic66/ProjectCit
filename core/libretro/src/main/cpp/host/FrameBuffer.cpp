#include "FrameBuffer.h"

#include <cstring>
#include <utility>

#include "../libretro/libretro.h"

namespace retrohost {

void FrameBuffer::write(const void* data, unsigned width, unsigned height, size_t srcPitch, int srcFormat) {
    const bool is32 = srcFormat == RETRO_PIXEL_FORMAT_XRGB8888;
    const size_t bpp = is32 ? 4 : 2;
    const size_t rowBytes = width * bpp;
    back_.pixels.resize(rowBytes * height);
    back_.width = width;
    back_.height = height;
    back_.pitch = rowBytes;
    back_.format = is32 ? PixelFormat::XRGB8888 : PixelFormat::RGB565;

    const auto* src = static_cast<const uint8_t*>(data);
    uint8_t* dst = back_.pixels.data();
    if (srcFormat == RETRO_PIXEL_FORMAT_0RGB1555) {
        for (unsigned y = 0; y < height; ++y) {
            const auto* s = reinterpret_cast<const uint16_t*>(src + y * srcPitch);
            auto* d = reinterpret_cast<uint16_t*>(dst + y * rowBytes);
            for (unsigned x = 0; x < width; ++x) {
                const uint16_t p = s[x];
                // 0RRRRRGGGGGBBBBB -> RRRRRGGGGGGBBBBB (replicate green MSB into the new LSB)
                d[x] = static_cast<uint16_t>(((p & 0x7FE0) << 1) | ((p & 0x0200) >> 4) | (p & 0x001F));
            }
        }
    } else if (srcPitch == rowBytes) {
        std::memcpy(dst, src, rowBytes * height);
    } else {
        for (unsigned y = 0; y < height; ++y) std::memcpy(dst + y * rowBytes, src + y * srcPitch, rowBytes);
    }

    std::lock_guard<std::mutex> lock(mutex_);
    std::swap(back_, front_);
    ++sequence_;
}

bool FrameBuffer::copyRgba(std::vector<uint8_t>& out, unsigned& w, unsigned& h) const {
    return read([&](const View& v) {
        w = v.width;
        h = v.height;
        out.resize(static_cast<size_t>(w) * h * 4);
        uint8_t* o = out.data();
        for (unsigned y = 0; y < h; ++y) {
            const uint8_t* row = v.pixels + y * v.pitch;
            for (unsigned x = 0; x < w; ++x, o += 4) {
                if (v.format == PixelFormat::XRGB8888) {
                    uint32_t p;
                    std::memcpy(&p, row + x * 4, 4);
                    o[0] = (p >> 16) & 0xFF; o[1] = (p >> 8) & 0xFF; o[2] = p & 0xFF;
                } else {
                    uint16_t p;
                    std::memcpy(&p, row + x * 2, 2);
                    const unsigned r = (p >> 11) & 0x1F, g = (p >> 5) & 0x3F, b = p & 0x1F;
                    o[0] = static_cast<uint8_t>((r << 3) | (r >> 2));
                    o[1] = static_cast<uint8_t>((g << 2) | (g >> 4));
                    o[2] = static_cast<uint8_t>((b << 3) | (b >> 2));
                }
                o[3] = 0xFF;
            }
        }
    });
}

}  // namespace retrohost
