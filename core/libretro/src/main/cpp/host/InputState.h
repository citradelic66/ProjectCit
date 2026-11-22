#pragma once
#include <atomic>
#include <cstdint>

#include "../libretro/libretro.h"

namespace retrohost {

/**
 * Input written by the UI thread (touch, gamepad) and read by the core on the
 * emulation thread. Plain atomics: the latest value wins, which is exactly the
 * libretro polling model.
 */
class InputState {
public:
    static constexpr unsigned kPorts = 4;

    void setButtons(unsigned port, uint32_t mask) { if (port < kPorts) buttons_[port].store(mask, std::memory_order_relaxed); }
    uint32_t buttons(unsigned port) const { return port < kPorts ? buttons_[port].load(std::memory_order_relaxed) : 0; }

    /** stick: RETRO_DEVICE_INDEX_ANALOG_LEFT/RIGHT, axis: RETRO_DEVICE_ID_ANALOG_X/Y. */
    void setAnalog(unsigned port, unsigned stick, unsigned axis, int16_t v) {
        if (port < kPorts && stick < 2 && axis < 2) analog_[port][stick][axis].store(v, std::memory_order_relaxed);
    }

    /** x/y already normalized to libretro space [-0x7fff, 0x7fff] over the core's whole video frame. */
    void setPointer(int16_t x, int16_t y, bool pressed) {
        pointer_.store(pack(x, y, pressed), std::memory_order_relaxed);
        // A tap shorter than one frame (16.7 ms) would otherwise never be seen by the
        // core: latch the press (and where it happened) until a frame has consumed it.
        if (pressed) latched_.store(pack(x, y, true), std::memory_order_relaxed);
    }

    /** Called after each retro_run: the latched press has been observed. */
    void endFrame() { latched_.store(0, std::memory_order_relaxed); }

    int16_t state(unsigned port, unsigned device, unsigned index, unsigned id) const {
        if (port >= kPorts) return 0;
        switch (device & RETRO_DEVICE_MASK) {
            case RETRO_DEVICE_JOYPAD: {
                const uint32_t b = buttons_[port].load(std::memory_order_relaxed);
                if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return static_cast<int16_t>(b & 0xFFFF);
                return id < 16 ? static_cast<int16_t>((b >> id) & 1) : 0;
            }
            case RETRO_DEVICE_ANALOG:
                if (index < 2 && id < 2) return analog_[port][index][id].load(std::memory_order_relaxed);
                return 0;
            case RETRO_DEVICE_POINTER: {
                if (port != 0 || index != 0) return 0;  // single touch point
                uint64_t p = pointer_.load(std::memory_order_relaxed);
                if (!((p >> 32) & 1)) {
                    const uint64_t l = latched_.load(std::memory_order_relaxed);
                    if ((l >> 32) & 1) p = l;  // released already, but this frame hasn't seen the tap yet
                }
                const bool pressed = (p >> 32) & 1;
                switch (id) {
                    case RETRO_DEVICE_ID_POINTER_X: return static_cast<int16_t>(p & 0xFFFF);
                    case RETRO_DEVICE_ID_POINTER_Y: return static_cast<int16_t>((p >> 16) & 0xFFFF);
                    case RETRO_DEVICE_ID_POINTER_PRESSED: return pressed ? 1 : 0;
                    case RETRO_DEVICE_ID_POINTER_COUNT: return pressed ? 1 : 0;
                    case RETRO_DEVICE_ID_POINTER_IS_OFFSCREEN: return 0;
                    default: return 0;
                }
            }
            default:
                return 0;
        }
    }

private:
    // x, y and pressed packed into one word so the core never sees a torn update.
    static uint64_t pack(int16_t x, int16_t y, bool pressed) {
        return static_cast<uint64_t>(static_cast<uint16_t>(x)) |
               (static_cast<uint64_t>(static_cast<uint16_t>(y)) << 16) |
               (static_cast<uint64_t>(pressed ? 1 : 0) << 32);
    }
    std::atomic<uint32_t> buttons_[kPorts]{};
    std::atomic<int16_t> analog_[kPorts][2][2]{};
    std::atomic<uint64_t> pointer_{0};
    std::atomic<uint64_t> latched_{0};
};

}  // namespace retrohost
