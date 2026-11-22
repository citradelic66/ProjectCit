#pragma once
#include <memory>
#include <string>

#include "../libretro/libretro.h"

namespace retrohost {

/** A dlopen()'d libretro core with every required entry point resolved. */
class CoreLibrary {
public:
    static std::unique_ptr<CoreLibrary> open(const std::string& path, std::string& error);
    ~CoreLibrary();
    CoreLibrary(const CoreLibrary&) = delete;
    CoreLibrary& operator=(const CoreLibrary&) = delete;

    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    unsigned (*api_version)() = nullptr;
    void (*get_system_info)(retro_system_info*) = nullptr;
    void (*get_system_av_info)(retro_system_av_info*) = nullptr;
    void (*set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*reset)() = nullptr;
    void (*run)() = nullptr;
    size_t (*serialize_size)() = nullptr;
    bool (*serialize)(void*, size_t) = nullptr;
    bool (*unserialize)(const void*, size_t) = nullptr;
    bool (*load_game)(const retro_game_info*) = nullptr;
    void (*unload_game)() = nullptr;
    void* (*get_memory_data)(unsigned) = nullptr;
    size_t (*get_memory_size)(unsigned) = nullptr;

    const std::string& path() const { return path_; }

private:
    CoreLibrary() = default;
    void* handle_ = nullptr;
    std::string path_;
};

}  // namespace retrohost
