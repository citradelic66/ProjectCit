#include "RetroHost.h"

#include <chrono>
#include <cstdarg>
#include <cstring>
#include <unistd.h>

#include "Log.h"
#include "SaveStateFile.h"

namespace retrohost {

RetroHost* RetroHost::active_ = nullptr;

// ---------------------------------------------------------------- lifecycle

std::unique_ptr<RetroHost> RetroHost::create(const HostConfig& config, std::string& error) {
    if (active_) { error = "another core is already running"; return nullptr; }
    std::unique_ptr<RetroHost> host(new RetroHost());
    host->config_ = config;
    host->owner_ = std::this_thread::get_id();
    host->options_.setOverrides(config.optionOverrides);
    host->core_ = CoreLibrary::open(config.corePath, error);
    if (!host->core_) return nullptr;

    active_ = host.get();
    CoreLibrary& c = *host->core_;
    // Environment first: cores may query it from any later entry point, and
    // declare their options from it before retro_init returns.
    c.set_environment(&RetroHost::envCb);
    c.get_system_info(&host->systemInfo_);
    c.set_video_refresh(&RetroHost::videoCb);
    c.set_audio_sample(&RetroHost::audioSampleCb);
    c.set_audio_sample_batch(&RetroHost::audioBatchCb);
    c.set_input_poll(&RetroHost::inputPollCb);
    c.set_input_state(&RetroHost::inputStateCb);
    c.init();
    host->inited_ = true;
    LOGI("core %s %s ready (extensions: %s, need_fullpath=%d)",
         host->systemInfo_.library_name, host->systemInfo_.library_version,
         host->systemInfo_.valid_extensions ? host->systemInfo_.valid_extensions : "", host->systemInfo_.need_fullpath);
    return host;
}

RetroHost::~RetroHost() {
    if (core_) {
        if (gameLoaded_) unloadGame();
        if (inited_) core_->deinit();
    }
    if (active_ == this) active_ = nullptr;
    core_.reset();  // dlclose last, after the core stopped calling us
}

bool RetroHost::onThread(const char* what) const {
    if (std::this_thread::get_id() == owner_) return true;
    LOGE("%s called off the emulation thread; ignored", what);
    return false;
}

bool RetroHost::loadGame(const GameSource& game, std::string& error) {
    if (!onThread("loadGame")) { error = "wrong thread"; return false; }
    if (gameLoaded_) unloadGame();

    retro_game_info info{};
    info.path = game.path.empty() ? nullptr : game.path.c_str();
    gameData_.clear();
    if (!systemInfo_.need_fullpath) {
        // Core accepts a memory buffer: read it ourselves (works for SAF fds too).
        if (game.fd >= 0) {
            uint8_t chunk[1 << 16];
            off_t offset = 0;
            ssize_t n;
            while ((n = pread(game.fd, chunk, sizeof chunk, offset)) > 0) {
                gameData_.insert(gameData_.end(), chunk, chunk + n);
                offset += n;
            }
            if (n < 0) { error = "failed reading game fd"; return false; }
        } else if (!game.path.empty() && !readFile(game.path, gameData_)) {
            error = "cannot read " + game.path;
            return false;
        }
        info.data = gameData_.data();
        info.size = gameData_.size();
    }
    if (!core_->load_game(game.path.empty() && supportsNoGame_ ? nullptr : &info)) {
        error = "core rejected the game (retro_load_game returned false)";
        gameData_.clear();
        return false;
    }
    gameLoaded_ = true;
    framesRun_ = 0;
    {
        std::lock_guard<std::mutex> l(avMutex_);
        core_->get_system_av_info(&av_);
    }
    // Port 0 is a standard pad; cores that care (melonDS DS) add the pointer themselves.
    core_->set_controller_port_device(0, RETRO_DEVICE_JOYPAD);

    saveName_ = game.saveName;
    if (saveName_.empty()) {
        std::string base = game.path.substr(game.path.find_last_of('/') + 1);
        saveName_ = base.substr(0, base.find_last_of('.'));
    }
    loadSaveRam();
    audio_.clear();
    LOGI("game loaded: %s (%ux%u @ %.4f fps, %.0f Hz audio)", saveName_.c_str(),
         av_.geometry.base_width, av_.geometry.base_height, av_.timing.fps, av_.timing.sample_rate);
    return true;
}

void RetroHost::unloadGame() {
    if (!gameLoaded_ || !onThread("unloadGame")) return;
    std::string err;
    if (!flushSaveRam(err)) LOGE("final save flush failed: %s", err.c_str());
    core_->unload_game();
    gameLoaded_ = false;
    gameData_.clear();
    gameData_.shrink_to_fit();
}

void RetroHost::runFrame() {
    if (!gameLoaded_ || !onThread("runFrame")) return;
    if (frameTimeCb_.callback) {
        const float fps = static_cast<float>(av_.timing.fps > 0 ? av_.timing.fps : 60.0);
        frameTimeCb_.callback(static_cast<retro_usec_t>(1000000.0f / fps));
    }
    if (audioStatusCb_.callback) {
        const double fill = audio_.fillRatio();
        audioStatusCb_.callback(true, static_cast<unsigned>(fill * 100.0), fill < 0.25);
    }
    core_->run();
    input_.endFrame();
    ++framesRun_;
}

void RetroHost::reset() {
    if (gameLoaded_ && onThread("reset")) core_->reset();
}

// ---------------------------------------------------------------- battery saves

std::string RetroHost::srmPath() const { return config_.saveDir + "/" + saveName_ + ".srm"; }

void RetroHost::loadSaveRam() {
    lastFlushedSram_.clear();
    void* mem = core_->get_memory_data(RETRO_MEMORY_SAVE_RAM);
    const size_t size = core_->get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!mem || size == 0) {
        LOGI("core exposes no SAVE_RAM; it manages saves itself in %s", config_.saveDir.c_str());
        return;
    }
    std::vector<uint8_t> file;
    const char* used = nullptr;
    // .srm is the libretro convention; .sav is what other emulators/flashcarts write.
    if (readFile(srmPath(), file)) used = ".srm";
    else if (readFile(config_.saveDir + "/" + saveName_ + ".sav", file)) used = ".sav";
    if (used) {
        const size_t n = file.size() < size ? file.size() : size;
        std::memcpy(mem, file.data(), n);
        if (file.size() != size) LOGW("save file is %zu bytes, core expects %zu", file.size(), size);
        LOGI("battery save loaded from %s%s", saveName_.c_str(), used);
    }
    lastFlushedSram_.assign(static_cast<uint8_t*>(mem), static_cast<uint8_t*>(mem) + size);
}

bool RetroHost::flushSaveRam(std::string& error, bool* wrote) {
    if (wrote) *wrote = false;
    if (!gameLoaded_) return true;
    if (!onThread("flushSaveRam")) { error = "wrong thread"; return false; }
    void* mem = core_->get_memory_data(RETRO_MEMORY_SAVE_RAM);
    const size_t size = core_->get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (!mem || size == 0) return true;
    if (lastFlushedSram_.size() == size && std::memcmp(lastFlushedSram_.data(), mem, size) == 0) return true;
    if (!writeFileAtomic(srmPath(), static_cast<uint8_t*>(mem), size, error)) return false;
    lastFlushedSram_.assign(static_cast<uint8_t*>(mem), static_cast<uint8_t*>(mem) + size);
    if (wrote) *wrote = true;
    return true;
}

// ---------------------------------------------------------------- save states

bool RetroHost::saveState(const std::string& path, std::string& error) {
    if (!gameLoaded_) { error = "no game loaded"; return false; }
    if (!onThread("saveState")) { error = "wrong thread"; return false; }
    const size_t size = core_->serialize_size();
    if (size == 0) { error = "core does not support save states"; return false; }
    std::vector<uint8_t> payload(size);
    if (!core_->serialize(payload.data(), payload.size())) { error = "retro_serialize failed"; return false; }
    SaveStateHeader h;
    h.coreName = systemInfo_.library_name ? systemInfo_.library_name : "";
    h.coreVersion = systemInfo_.library_version ? systemInfo_.library_version : "";
    h.timestampMs = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::system_clock::now().time_since_epoch()).count());
    return writeSaveState(path, h, payload, error);
}

bool RetroHost::loadState(const std::string& path, std::string& error) {
    if (!gameLoaded_) { error = "no game loaded"; return false; }
    if (!onThread("loadState")) { error = "wrong thread"; return false; }
    SaveStateHeader h;
    std::vector<uint8_t> payload;
    if (!readSaveState(path, h, payload, error)) return false;
    const std::string coreName = systemInfo_.library_name ? systemInfo_.library_name : "";
    if (h.coreName != coreName) { error = "state was made by " + h.coreName + ", not " + coreName; return false; }
    if (h.coreVersion != (systemInfo_.library_version ? systemInfo_.library_version : ""))
        LOGW("state from core version %s, running %s; trying anyway", h.coreVersion.c_str(), systemInfo_.library_version);
    if (!core_->unserialize(payload.data(), payload.size())) { error = "core rejected the state (retro_unserialize failed)"; return false; }
    audio_.clear();
    return true;
}

// ---------------------------------------------------------------- messages

void RetroHost::pushMessage(std::string msg) {
    std::lock_guard<std::mutex> l(messagesMutex_);
    if (messages_.size() >= 32) messages_.pop_front();
    messages_.push_back(std::move(msg));
}

bool RetroHost::pollMessage(std::string& out) {
    std::lock_guard<std::mutex> l(messagesMutex_);
    if (messages_.empty()) return false;
    out = std::move(messages_.front());
    messages_.pop_front();
    return true;
}

// ---------------------------------------------------------------- environment

bool RetroHost::environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            *static_cast<const char**>(data) = config_.systemDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            *static_cast<const char**>(data) = config_.saveDir.c_str();
            return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            const auto fmt = *static_cast<const retro_pixel_format*>(data);
            if (fmt != RETRO_PIXEL_FORMAT_0RGB1555 && fmt != RETRO_PIXEL_FORMAT_RGB565 && fmt != RETRO_PIXEL_FORMAT_XRGB8888) return false;
            pixelFormat_ = fmt;
            return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            static_cast<retro_log_callback*>(data)->log = &RetroHost::logCb;
            return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE:
            *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS:
            return true;
        case RETRO_ENVIRONMENT_GET_INPUT_DEVICE_CAPABILITIES:
            *static_cast<uint64_t*>(data) = (1u << RETRO_DEVICE_JOYPAD) | (1u << RETRO_DEVICE_ANALOG) | (1u << RETRO_DEVICE_POINTER);
            return true;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_MINIMUM_AUDIO_LATENCY:
        case RETRO_ENVIRONMENT_SET_SUPPORT_ACHIEVEMENTS:
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_UPDATE_DISPLAY_CALLBACK:
            return true;
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
            supportsNoGame_ = *static_cast<const bool*>(data);
            return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY: {
            std::lock_guard<std::mutex> l(avMutex_);
            av_.geometry = *static_cast<const retro_game_geometry*>(data);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: {
            std::lock_guard<std::mutex> l(avMutex_);
            av_ = *static_cast<const retro_system_av_info*>(data);
            return true;
        }
        // ---- core options
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            *static_cast<unsigned*>(data) = 2;
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLES:
            options_.defineLegacy(static_cast<const retro_variable*>(data));
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS:
            options_.defineV1(static_cast<const retro_core_option_definition*>(data));
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_INTL:
            options_.defineV1(static_cast<const retro_core_options_intl*>(data)->us);
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2:
            options_.defineV2(static_cast<const retro_core_options_v2*>(data));
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2_INTL:
            options_.defineV2(static_cast<const retro_core_options_v2_intl*>(data)->us);
            return true;
        case RETRO_ENVIRONMENT_SET_CORE_OPTIONS_DISPLAY: {
            const auto* d = static_cast<const retro_core_option_display*>(data);
            if (d) options_.setVisible(d->key, d->visible);
            return true;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE: {
            auto* var = static_cast<retro_variable*>(data);
            var->value = options_.get(var->key);
            return var->value != nullptr;
        }
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
            *static_cast<bool*>(data) = options_.consumeDirty();
            return true;
        case RETRO_ENVIRONMENT_SET_VARIABLE: {
            if (!data) return true;  // capability query
            const auto* var = static_cast<const retro_variable*>(data);
            return var->key && var->value && options_.set(var->key, var->value);
        }
        // ---- misc host state
        case RETRO_ENVIRONMENT_GET_LANGUAGE:
            *static_cast<unsigned*>(data) = RETRO_LANGUAGE_ENGLISH;
            return true;
        case RETRO_ENVIRONMENT_GET_USERNAME:
            *static_cast<const char**>(data) = "Player";
            return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE:
            if (data) *static_cast<int*>(data) = 1 | 2;  // video + audio
            return true;
        case RETRO_ENVIRONMENT_GET_FASTFORWARDING:
            *static_cast<bool*>(data) = fastForward_;
            return true;
        case RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE:
            *static_cast<float*>(data) = targetRefresh_;
            return true;
        case RETRO_ENVIRONMENT_GET_SAVESTATE_CONTEXT:
            if (data) *static_cast<int*>(data) = RETRO_SAVESTATE_CONTEXT_NORMAL;
            return true;
        case RETRO_ENVIRONMENT_GET_JIT_CAPABLE:
            // Android apps may map anonymous executable memory, so JIT cores work.
            *static_cast<bool*>(data) = true;
            return true;
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
            serializationQuirks_ = *static_cast<const uint64_t*>(data);
            return true;
        case RETRO_ENVIRONMENT_GET_MESSAGE_INTERFACE_VERSION:
            *static_cast<unsigned*>(data) = 1;
            return true;
        case RETRO_ENVIRONMENT_SET_MESSAGE: {
            const auto* m = static_cast<const retro_message*>(data);
            if (m && m->msg) pushMessage(m->msg);
            return true;
        }
        case RETRO_ENVIRONMENT_SET_MESSAGE_EXT: {
            const auto* m = static_cast<const retro_message_ext*>(data);
            if (m && m->msg) {
                if (m->target != RETRO_MESSAGE_TARGET_LOG) pushMessage(m->msg);
                LOGI("core message: %s", m->msg);
            }
            return true;
        }
        case RETRO_ENVIRONMENT_GET_RUMBLE_INTERFACE:
            static_cast<retro_rumble_interface*>(data)->set_rumble_state = &RetroHost::rumbleCb;
            return true;
        case RETRO_ENVIRONMENT_SET_FRAME_TIME_CALLBACK:
            frameTimeCb_ = *static_cast<const retro_frame_time_callback*>(data);
            return true;
        case RETRO_ENVIRONMENT_SET_AUDIO_BUFFER_STATUS_CALLBACK:
            if (data) audioStatusCb_ = *static_cast<const retro_audio_buffer_status_callback*>(data);
            else audioStatusCb_.callback = nullptr;
            return true;
        case RETRO_ENVIRONMENT_SHUTDOWN:
            shutdownRequested_ = true;
            return true;
        // Deliberately unsupported: software rendering only (melonDS DS ships
        // its Android build without OpenGL), no VFS (cores fall back to stdio),
        // no camera/sensors/mic/network yet.
        case RETRO_ENVIRONMENT_SET_HW_RENDER:
        case RETRO_ENVIRONMENT_GET_PREFERRED_HW_RENDER:
        case RETRO_ENVIRONMENT_GET_VFS_INTERFACE:
        default:
            return false;
    }
}

// ---------------------------------------------------------------- trampolines

bool RetroHost::envCb(unsigned cmd, void* data) { return active_ && active_->environment(cmd, data); }

void RetroHost::videoCb(const void* data, unsigned w, unsigned h, size_t pitch) {
    if (!active_) return;
    if (!data || data == RETRO_HW_FRAME_BUFFER_VALID) { active_->frame_.markDuped(); return; }
    active_->frame_.write(data, w, h, pitch, active_->pixelFormat_);
}

void RetroHost::audioSampleCb(int16_t l, int16_t r) {
    if (!active_) return;
    const int16_t f[2] = {l, r};
    active_->audio_.push(f, 1);
}

size_t RetroHost::audioBatchCb(const int16_t* data, size_t frames) {
    if (active_) active_->audio_.push(data, frames);
    return frames;  // always "consumed": dropping is the ring's job, not the core's
}

void RetroHost::inputPollCb() {}

int16_t RetroHost::inputStateCb(unsigned port, unsigned device, unsigned index, unsigned id) {
    return active_ ? active_->input_.state(port, device, index, id) : 0;
}

void RetroHost::logCb(enum retro_log_level level, const char* fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    const int prio = level == RETRO_LOG_ERROR ? RH_ERROR : level == RETRO_LOG_WARN ? RH_WARN
                   : level == RETRO_LOG_INFO ? RH_INFO : RH_DEBUG;
    rhLogV(prio, fmt, ap);
    va_end(ap);
}

bool RetroHost::rumbleCb(unsigned port, enum retro_rumble_effect effect, uint16_t strength) {
    if (!active_ || port != 0) return false;
    (effect == RETRO_RUMBLE_STRONG ? active_->rumble_.strong : active_->rumble_.weak).store(strength);
    return true;
}

}  // namespace retrohost
