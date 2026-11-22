#include "CoreLibrary.h"

#include <dlfcn.h>

#include "Log.h"

namespace retrohost {

namespace {
template <typename T>
bool bind(void* handle, const char* name, T& out, std::string& error) {
    out = reinterpret_cast<T>(dlsym(handle, name));
    if (!out) error = std::string("core is missing symbol ") + name;
    return out != nullptr;
}
}  // namespace

std::unique_ptr<CoreLibrary> CoreLibrary::open(const std::string& path, std::string& error) {
    // RTLD_LOCAL keeps each core's symbols private; RTLD_NOW surfaces missing
    // dependencies (e.g. a core built against a newer libc++) here, not mid-game.
    void* h = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!h) {
        const char* e = dlerror();
        error = std::string("dlopen failed: ") + (e ? e : "unknown error");
        return nullptr;
    }
    std::unique_ptr<CoreLibrary> lib(new CoreLibrary());
    lib->handle_ = h;
    lib->path_ = path;
    const bool ok =
        bind(h, "retro_set_environment", lib->set_environment, error) &&
        bind(h, "retro_set_video_refresh", lib->set_video_refresh, error) &&
        bind(h, "retro_set_audio_sample", lib->set_audio_sample, error) &&
        bind(h, "retro_set_audio_sample_batch", lib->set_audio_sample_batch, error) &&
        bind(h, "retro_set_input_poll", lib->set_input_poll, error) &&
        bind(h, "retro_set_input_state", lib->set_input_state, error) &&
        bind(h, "retro_init", lib->init, error) &&
        bind(h, "retro_deinit", lib->deinit, error) &&
        bind(h, "retro_api_version", lib->api_version, error) &&
        bind(h, "retro_get_system_info", lib->get_system_info, error) &&
        bind(h, "retro_get_system_av_info", lib->get_system_av_info, error) &&
        bind(h, "retro_set_controller_port_device", lib->set_controller_port_device, error) &&
        bind(h, "retro_reset", lib->reset, error) &&
        bind(h, "retro_run", lib->run, error) &&
        bind(h, "retro_serialize_size", lib->serialize_size, error) &&
        bind(h, "retro_serialize", lib->serialize, error) &&
        bind(h, "retro_unserialize", lib->unserialize, error) &&
        bind(h, "retro_load_game", lib->load_game, error) &&
        bind(h, "retro_unload_game", lib->unload_game, error) &&
        bind(h, "retro_get_memory_data", lib->get_memory_data, error) &&
        bind(h, "retro_get_memory_size", lib->get_memory_size, error);
    if (!ok) return nullptr;  // destructor dlcloses
    if (lib->api_version() != RETRO_API_VERSION) {
        error = "core API version " + std::to_string(lib->api_version()) + " != host " + std::to_string(RETRO_API_VERSION);
        return nullptr;
    }
    LOGI("loaded core %s", path.c_str());
    return lib;
}

CoreLibrary::~CoreLibrary() {
    if (handle_) dlclose(handle_);
}

}  // namespace retrohost
