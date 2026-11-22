// Minimal libretro core used only by tests. Each frame it writes a 256x384
// XRGB8888 image whose first pixels encode what it observed:
//   px0 frame counter   px1 pointer X   px2 pointer Y   px3 pointer pressed
//   px4 joypad mask     px5 test_layout=="top-bottom"   px6 sram[0]   px7 system dir length
#include <cstdio>
#include <cstring>
#include <string>

#include "libretro/libretro.h"

namespace {
retro_environment_t env;
retro_video_refresh_t video;
retro_audio_sample_batch_t audioBatch;
retro_input_poll_t inputPoll;
retro_input_state_t inputState;
retro_log_printf_t logf;

struct State { uint32_t frame; uint8_t sram[512]; } st;
uint32_t pixels[256 * 384];
int16_t audio[800 * 2];
bool topBottom = false;
std::string systemDir;

void readOptions() {
    retro_variable v{"test_layout", nullptr};
    topBottom = env(RETRO_ENVIRONMENT_GET_VARIABLE, &v) && v.value && std::strcmp(v.value, "top-bottom") == 0;
}
}  // namespace

extern "C" {
RETRO_API void retro_set_environment(retro_environment_t cb) {
    env = cb;
    static retro_core_option_v2_definition defs[] = {
        {"test_layout", "Layout", nullptr, nullptr, nullptr, nullptr,
         {{"left-right", nullptr}, {"top-bottom", nullptr}, {nullptr, nullptr}}, "left-right"},
        {"test_speed", "Speed", nullptr, nullptr, nullptr, nullptr, {{"1", nullptr}, {"2", nullptr}, {nullptr, nullptr}}, nullptr},
        {nullptr, nullptr, nullptr, nullptr, nullptr, nullptr, {{nullptr, nullptr}}, nullptr},
    };
    static retro_core_option_v2_category cats[] = {{nullptr, nullptr, nullptr}};
    static retro_core_options_v2 opts{cats, defs};
    unsigned version = 0;
    if (env(RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION, &version) && version >= 2)
        env(RETRO_ENVIRONMENT_SET_CORE_OPTIONS_V2, &opts);
}
RETRO_API void retro_set_video_refresh(retro_video_refresh_t cb) { video = cb; }
RETRO_API void retro_set_audio_sample(retro_audio_sample_t) {}
RETRO_API void retro_set_audio_sample_batch(retro_audio_sample_batch_t cb) { audioBatch = cb; }
RETRO_API void retro_set_input_poll(retro_input_poll_t cb) { inputPoll = cb; }
RETRO_API void retro_set_input_state(retro_input_state_t cb) { inputState = cb; }
RETRO_API void retro_init() {
    retro_log_callback l{};
    if (env(RETRO_ENVIRONMENT_GET_LOG_INTERFACE, &l)) logf = l.log;
    std::memset(&st, 0, sizeof st);
}
RETRO_API void retro_deinit() {}
RETRO_API unsigned retro_api_version() { return RETRO_API_VERSION; }
RETRO_API void retro_get_system_info(retro_system_info* i) {
    i->library_name = "TestCore";
    i->library_version = "1.0";
    i->valid_extensions = "tst|bin";
    i->need_fullpath = false;
    i->block_extract = false;
}
RETRO_API void retro_get_system_av_info(retro_system_av_info* i) {
    i->geometry = {256, 384, 256, 384, 256.0f / 384.0f};
    i->timing = {59.8261, 48000.0};
}
RETRO_API void retro_set_controller_port_device(unsigned, unsigned) {}
RETRO_API void retro_reset() { st.frame = 0; }
RETRO_API void retro_run() {
    bool updated = false;
    if (env(RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE, &updated) && updated) readOptions();
    inputPoll();
    const int16_t mask = inputState(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK);
    const int16_t px = inputState(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_X);
    const int16_t py = inputState(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_Y);
    const int16_t pp = inputState(0, RETRO_DEVICE_POINTER, 0, RETRO_DEVICE_ID_POINTER_PRESSED);
    if (inputState(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B)) { st.sram[0] = 0xAB; st.sram[1] = st.frame & 0xFF; }
    ++st.frame;
    pixels[0] = st.frame & 0xFFFFFF;
    pixels[1] = static_cast<uint16_t>(px);
    pixels[2] = static_cast<uint16_t>(py);
    pixels[3] = static_cast<uint32_t>(pp);
    pixels[4] = static_cast<uint16_t>(mask);
    pixels[5] = topBottom ? 1 : 0;
    pixels[6] = st.sram[0];
    pixels[7] = static_cast<uint32_t>(systemDir.size());
    for (int i = 0; i < 800 * 2; ++i) audio[i] = static_cast<int16_t>(st.frame);
    audioBatch(audio, 800);
    if (st.frame % 10 == 0) video(nullptr, 256, 384, 256 * 4);  // exercise frame dupes
    else video(pixels, 256, 384, 256 * 4);
}
RETRO_API size_t retro_serialize_size() { return sizeof st; }
RETRO_API bool retro_serialize(void* d, size_t s) { if (s < sizeof st) return false; std::memcpy(d, &st, sizeof st); return true; }
RETRO_API bool retro_unserialize(const void* d, size_t s) { if (s != sizeof st) return false; std::memcpy(&st, d, sizeof st); return true; }
RETRO_API void retro_cheat_reset() {}
RETRO_API void retro_cheat_set(unsigned, bool, const char*) {}
RETRO_API bool retro_load_game(const retro_game_info* g) {
    retro_pixel_format fmt = RETRO_PIXEL_FORMAT_XRGB8888;
    if (!env(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &fmt)) return false;
    if (!g || !g->data || g->size == 0) return false;
    const char* dir = nullptr;
    if (env(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, &dir) && dir) systemDir = dir;
    readOptions();
    retro_message msg{"hello from testcore", 60};
    env(RETRO_ENVIRONMENT_SET_MESSAGE, &msg);
    if (logf) logf(RETRO_LOG_INFO, "testcore loaded %zu bytes\n", g->size);
    return true;
}
RETRO_API bool retro_load_game_special(unsigned, const retro_game_info*, size_t) { return false; }
RETRO_API void retro_unload_game() {}
RETRO_API unsigned retro_get_region() { return RETRO_REGION_NTSC; }
RETRO_API void* retro_get_memory_data(unsigned id) { return id == RETRO_MEMORY_SAVE_RAM ? st.sram : nullptr; }
RETRO_API size_t retro_get_memory_size(unsigned id) { return id == RETRO_MEMORY_SAVE_RAM ? sizeof st.sram : 0; }
}
