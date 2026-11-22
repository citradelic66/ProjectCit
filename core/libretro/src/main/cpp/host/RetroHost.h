#pragma once
#include <atomic>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

#include "../libretro/libretro.h"
#include "AudioRing.h"
#include "CoreLibrary.h"
#include "CoreOptions.h"
#include "FrameBuffer.h"
#include "InputState.h"

namespace retrohost {

struct HostConfig {
    std::string corePath;
    std::string systemDir;   // BIOS mirror (Phase 1 SystemDirMirror)
    std::string saveDir;     // battery saves (.srm) and core-managed saves
    std::vector<std::pair<std::string, std::string>> optionOverrides;
};

struct GameSource {
    /** Path the core sees. Must end in the real extension (cores check it). */
    std::string path;
    /** Optional readable fd for the same content; used to read into memory when the core allows it. */
    int fd = -1;
    /** Base name for .srm files; defaults to the file name of path without extension. */
    std::string saveName;
};

struct RumbleState { std::atomic<uint16_t> strong{0}, weak{0}; };

/**
 * One running libretro core + game. libretro cores keep global state and their
 * callbacks carry no user pointer, so exactly one RetroHost may be alive at a time.
 *
 * Threading: every method that calls into the core must run on the owner thread
 * (the one that called create(), or the one passed to rebindThread()). Input
 * setters, frame/audio readers and option getters are safe from any thread.
 */
class RetroHost {
public:
    static std::unique_ptr<RetroHost> create(const HostConfig& config, std::string& error);
    ~RetroHost();

    bool loadGame(const GameSource& game, std::string& error);
    void unloadGame();
    bool gameLoaded() const { return gameLoaded_; }

    void runFrame();
    void reset();

    /** Writes <saveDir>/<saveName>.srm if battery RAM changed since the last flush. */
    bool flushSaveRam(std::string& error, bool* wrote = nullptr);
    bool saveState(const std::string& path, std::string& error);
    bool loadState(const std::string& path, std::string& error);

    void rebindThread() { owner_ = std::this_thread::get_id(); }
    void setFastForward(bool on) { fastForward_ = on; }
    void setTargetRefreshRate(float hz) { targetRefresh_ = hz; }

    const retro_system_info& systemInfo() const { return systemInfo_; }
    retro_system_av_info avInfo() const { std::lock_guard<std::mutex> l(avMutex_); return av_; }
    int pixelFormat() const { return pixelFormat_; }
    uint64_t framesRun() const { return framesRun_; }
    bool shutdownRequested() const { return shutdownRequested_; }

    InputState& input() { return input_; }
    FrameBuffer& frame() { return frame_; }
    AudioRing& audio() { return audio_; }
    CoreOptions& options() { return options_; }
    const RumbleState& rumble() const { return rumble_; }
    /** Next OSD message from the core (RETRO_ENVIRONMENT_SET_MESSAGE[_EXT]), or false. */
    bool pollMessage(std::string& out);

private:
    RetroHost() = default;
    bool onThread(const char* what) const;
    bool environment(unsigned cmd, void* data);
    void pushMessage(std::string msg);
    std::string srmPath() const;
    void loadSaveRam();

    // C trampolines -> active instance
    static RetroHost* active_;
    static bool envCb(unsigned cmd, void* data);
    static void videoCb(const void* data, unsigned w, unsigned h, size_t pitch);
    static void audioSampleCb(int16_t l, int16_t r);
    static size_t audioBatchCb(const int16_t* data, size_t frames);
    static void inputPollCb();
    static int16_t inputStateCb(unsigned port, unsigned device, unsigned index, unsigned id);
    static void logCb(enum retro_log_level level, const char* fmt, ...);
    static bool rumbleCb(unsigned port, enum retro_rumble_effect effect, uint16_t strength);

    std::unique_ptr<CoreLibrary> core_;
    HostConfig config_;
    retro_system_info systemInfo_{};
    retro_system_av_info av_{};
    mutable std::mutex avMutex_;
    int pixelFormat_ = RETRO_PIXEL_FORMAT_0RGB1555;  // libretro default
    std::thread::id owner_;
    bool inited_ = false;
    bool gameLoaded_ = false;
    bool supportsNoGame_ = false;
    std::atomic<bool> shutdownRequested_{false};
    std::atomic<bool> fastForward_{false};
    std::atomic<float> targetRefresh_{60.0f};
    uint64_t serializationQuirks_ = 0;
    uint64_t framesRun_ = 0;

    std::string saveName_;
    std::vector<uint8_t> gameData_;        // owned copy when the core takes data, not a path
    std::vector<uint8_t> lastFlushedSram_;

    retro_frame_time_callback frameTimeCb_{};
    retro_audio_buffer_status_callback audioStatusCb_{};

    InputState input_;
    FrameBuffer frame_;
    AudioRing audio_;
    CoreOptions options_;
    RumbleState rumble_;
    std::deque<std::string> messages_;
    std::mutex messagesMutex_;
};

}  // namespace retrohost
