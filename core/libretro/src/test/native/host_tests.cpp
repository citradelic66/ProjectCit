// Native tests for the libretro host. Usage:
//   host_tests <testcore.so> <scratch dir> [mgba_libretro.so]
// The mGBA section runs a hand-assembled GBA ROM that writes 0x42 to cartridge SRAM.
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

#include <cstdio>
#include <cstring>
#include <functional>
#include <string>
#include <thread>
#include <vector>

#include "host/AudioRing.h"
#include "host/FrameBuffer.h"
#include "host/RetroHost.h"
#include "host/SaveStateFile.h"

using namespace retrohost;

static int failures = 0, checks = 0;
#define CHECK(cond) do { ++checks; if (!(cond)) { ++failures; std::fprintf(stderr, "FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); } } while (0)
#define CHECK_EQ(a, b) do { ++checks; auto _a = (a); auto _b = (b); if (!(_a == _b)) { ++failures; \
    std::fprintf(stderr, "FAIL %s:%d  %s == %s  (%lld vs %lld)\n", __FILE__, __LINE__, #a, #b, (long long)_a, (long long)_b); } } while (0)

static std::string coreTest, scratch, coreMgba;

static uint32_t px(RetroHost& h, unsigned i) {
    std::vector<uint8_t> rgba; unsigned w = 0, hh = 0;
    if (!h.frame().copyRgba(rgba, w, hh)) return 0xFFFFFFFF;
    return (uint32_t(rgba[i * 4]) << 16) | (uint32_t(rgba[i * 4 + 1]) << 8) | rgba[i * 4 + 2];
}

static HostConfig cfg(const std::string& core, const std::string& tag) {
    HostConfig c;
    c.corePath = core;
    c.systemDir = scratch + "/" + tag + "-system";
    c.saveDir = scratch + "/" + tag + "-saves";
    mkdir(c.systemDir.c_str(), 0755);
    mkdir(c.saveDir.c_str(), 0755);
    return c;
}

static void writeBytes(const std::string& path, const std::vector<uint8_t>& b) {
    FILE* f = std::fopen(path.c_str(), "wb"); std::fwrite(b.data(), 1, b.size(), f); std::fclose(f);
}

static void testUnits() {
    // 0RGB1555 -> RGB565: pure red/green/blue and white.
    FrameBuffer fb;
    const uint16_t src[4] = {0x7C00, 0x03E0, 0x001F, 0x7FFF};
    fb.write(src, 4, 1, 8, RETRO_PIXEL_FORMAT_0RGB1555);
    fb.read([&](const FrameBuffer::View& v) {
        const auto* p = reinterpret_cast<const uint16_t*>(v.pixels);
        CHECK_EQ(p[0], 0xF800); CHECK_EQ(p[1], 0x07E0); CHECK_EQ(p[2], 0x001F); CHECK_EQ(p[3], 0xFFFF);
        CHECK(v.format == PixelFormat::RGB565);
    });
    // Pitch larger than width (cores often pad rows).
    std::vector<uint32_t> padded(8 * 2, 0); padded[0] = 0x112233; padded[8] = 0x445566;
    fb.write(padded.data(), 2, 2, 8 * 4, RETRO_PIXEL_FORMAT_XRGB8888);
    std::vector<uint8_t> rgba; unsigned w, h;
    CHECK(fb.copyRgba(rgba, w, h)); CHECK_EQ(w, 2u); CHECK_EQ(rgba[8], 0x44); CHECK_EQ(rgba[9], 0x55); CHECK_EQ(rgba[11], 0xFF);

    // Audio ring: wrap-around and overflow accounting.
    AudioRing ring(8);
    std::vector<int16_t> in(12 * 2), out(12 * 2);
    for (int i = 0; i < 24; ++i) in[i] = static_cast<int16_t>(i);
    CHECK_EQ(ring.push(in.data(), 6), 6u);
    CHECK_EQ(ring.pop(out.data(), 4), 4u);
    CHECK_EQ(ring.push(in.data(), 12), 6u);          // only 6 free slots
    CHECK_EQ(ring.droppedFrames(), 6u);
    CHECK_EQ(ring.pop(out.data(), 12), 8u);
    CHECK_EQ(out[0], 8); CHECK_EQ(out[4], 0); CHECK_EQ(out[5], 1);  // frames 4,5 then wrapped batch

    // Save-state container: CRC catches corruption, truncation detected.
    SaveStateHeader hdr; hdr.coreName = "X"; hdr.coreVersion = "1";
    std::string err;
    const std::string p = scratch + "/unit.state";
    CHECK(writeSaveState(p, hdr, {1, 2, 3, 4}, err));
    SaveStateHeader rh; std::vector<uint8_t> payload;
    CHECK(readSaveState(p, rh, payload, err)); CHECK_EQ(payload.size(), 4u); CHECK(rh.coreName == "X");
    std::vector<uint8_t> raw; readFile(p, raw); raw.back() ^= 0xFF; writeBytes(p, raw);
    CHECK(!readSaveState(p, rh, payload, err)); CHECK(err.find("CRC") != std::string::npos);
    raw.pop_back(); writeBytes(p, raw);
    CHECK(!readSaveState(p, rh, payload, err)); CHECK(err.find("truncated") != std::string::npos);
}

static void testFakeCore() {
    std::string err;
    CHECK(!RetroHost::create(cfg("/nonexistent/core.so", "bad"), err));
    CHECK(err.find("dlopen") != std::string::npos);

    auto c = cfg(coreTest, "fake");
    c.optionOverrides = {{"test_layout", "top-bottom"}, {"test_speed", "9"}};  // second is invalid
    const std::string rom = scratch + "/game.tst";
    writeBytes(rom, {0xDE, 0xAD, 0xBE, 0xEF});
    {
        auto host = RetroHost::create(c, err);
        CHECK(host != nullptr);
        if (!host) { std::fprintf(stderr, "create failed: %s\n", err.c_str()); return; }
        CHECK(!RetroHost::create(c, err));  // one core at a time
        CHECK(std::string(host->systemInfo().library_name) == "TestCore");
        CHECK(std::string(host->options().get("test_layout")) == "top-bottom");
        CHECK(std::string(host->options().get("test_speed")) == "1");  // invalid override ignored

        // Load from an fd (how SAF content arrives) with a symlink-style path.
        const int fd = open(rom.c_str(), O_RDONLY);
        GameSource g; g.path = scratch + "/Some Game.tst"; g.fd = fd; g.saveName = "Some Game";
        CHECK(host->loadGame(g, err));
        close(fd);
        std::string msg;
        CHECK(host->pollMessage(msg) && msg == "hello from testcore");

        host->runFrame(); host->runFrame(); host->runFrame();
        CHECK_EQ(px(*host, 0), 3u);
        CHECK_EQ(px(*host, 5), 1u);   // override reached the core
        CHECK_EQ(px(*host, 7), c.systemDir.size());
        CHECK_EQ(host->audio().available(), 3u * 800u);
        CHECK(host->frame().read([](const FrameBuffer::View& v) { CHECK_EQ(v.width, 256u); CHECK_EQ(v.height, 384u); CHECK(v.format == PixelFormat::XRGB8888); }));

        // Pointer + buttons through the libretro input callbacks.
        host->input().setPointer(-0x7fff, 0x7fff, true);
        host->input().setButtons(0, (1u << RETRO_DEVICE_ID_JOYPAD_A) | (1u << RETRO_DEVICE_ID_JOYPAD_START));
        host->runFrame();
        CHECK_EQ(px(*host, 1), 0x8001u);   // -0x7fff as uint16
        CHECK_EQ(px(*host, 2), 0x7fffu);
        CHECK_EQ(px(*host, 3), 1u);
        CHECK_EQ(px(*host, 4), (1u << 8) | (1u << 3));
        host->input().setPointer(0, 0, false);
        host->runFrame();
        CHECK_EQ(px(*host, 3), 0u);

        // Runtime option change -> GET_VARIABLE_UPDATE -> core re-reads.
        CHECK(host->options().set("test_layout", "left-right"));
        CHECK(!host->options().set("test_layout", "diagonal"));
        host->runFrame();
        CHECK_EQ(px(*host, 5), 0u);

        // Frame dupes keep the previous image.
        for (int i = 0; i < 3; ++i) host->runFrame();   // frames 7..9
        const uint64_t seq = host->frame().sequence();
        host->runFrame();                                // frame 10 = dupe
        CHECK_EQ(host->frame().sequence(), seq);
        CHECK_EQ(host->frame().dupes(), 1u);
        CHECK_EQ(px(*host, 0), 9u);

        // Battery save: press B -> SRAM changes -> flush writes .srm exactly once.
        bool wrote = false;
        CHECK(host->flushSaveRam(err, &wrote)); CHECK(!wrote);
        host->input().setButtons(0, 1u << RETRO_DEVICE_ID_JOYPAD_B);
        host->runFrame();
        host->input().setButtons(0, 0);
        CHECK(host->flushSaveRam(err, &wrote)); CHECK(wrote);
        CHECK(host->flushSaveRam(err, &wrote)); CHECK(!wrote);

        // Save state round trip.
        const std::string st = scratch + "/fake.state";
        for (int i = 0; i < 4; ++i) host->runFrame();     // -> frame 15
        CHECK(host->saveState(st, err));
        for (int i = 0; i < 20; ++i) host->runFrame();
        CHECK(host->loadState(st, err));
        host->runFrame();
        CHECK_EQ(px(*host, 0), 16u);

        // Threading guard: calls from another thread are refused.
        const uint64_t before = host->framesRun();
        std::thread([&] { host->runFrame(); }).join();
        CHECK_EQ(host->framesRun(), before);
    }
    // New session: the .srm written above is loaded back into the core.
    {
        auto host = RetroHost::create(c, err);
        CHECK(host != nullptr);
        GameSource g; g.path = rom; g.saveName = "Some Game";
        CHECK(host->loadGame(g, err));
        host->runFrame();
        CHECK_EQ(px(*host, 6), 0xABu);
    }
}

static std::vector<uint8_t> makeGbaRom() {
    std::vector<uint8_t> rom(1024, 0);
    auto put32 = [&](size_t off, uint32_t v) { for (int i = 0; i < 4; ++i) rom[off + i] = static_cast<uint8_t>(v >> (8 * i)); };
    put32(0x00, 0xEA00002E);          // b 0xC0 (skip header)
    std::memcpy(&rom[0xA0], "PROJECTCIT  ", 12);
    rom[0xB2] = 0x96;                 // fixed header byte mGBA uses to identify a GBA ROM
    put32(0xC0, 0xE3A0040E);          // mov r0, #0x0E000000   (cartridge SRAM)
    put32(0xC4, 0xE3A01042);          // mov r1, #0x42
    put32(0xC8, 0xE5C01000);          // strb r1, [r0]
    put32(0xCC, 0xE3A02404);          // mov r2, #0x04000000   (I/O registers)
    put32(0xD0, 0xE3A03080);          // mov r3, #0x80
    put32(0xD4, 0xE5C23084);          // strb r3, [r2, #0x84]  SOUNDCNT_X: master sound enable
    put32(0xD8, 0xEAFFFFFE);          // b .
    std::memcpy(&rom[0x100], "SRAM_V113", 9);  // save-type marker mGBA scans for
    return rom;
}

static void testMgba() {
    if (coreMgba.empty()) { std::printf("mGBA tests skipped (no core path given)\n"); return; }
    std::string err;
    const std::string rom = scratch + "/sram.gba";
    writeBytes(rom, makeGbaRom());
    auto c = cfg(coreMgba, "mgba");
    {
        auto host = RetroHost::create(c, err);
        CHECK(host != nullptr);
        if (!host) { std::fprintf(stderr, "mGBA create failed: %s\n", err.c_str()); return; }
        std::printf("mGBA: %s %s, options declared: %zu\n", host->systemInfo().library_name,
                    host->systemInfo().library_version, host->options().snapshot().size());
        CHECK(host->options().snapshot().size() > 5);
        GameSource g; g.path = rom;
        CHECK(host->loadGame(g, err));
        if (!host->gameLoaded()) { std::fprintf(stderr, "mGBA load failed: %s\n", err.c_str()); return; }
        const auto av = host->avInfo();
        CHECK_EQ(av.geometry.base_width, 240u);
        CHECK_EQ(av.geometry.base_height, 160u);
        CHECK(av.timing.fps > 59.72 && av.timing.fps < 59.74);
        for (int i = 0; i < 60; ++i) host->runFrame();
        CHECK(host->frame().read([](const FrameBuffer::View& v) { CHECK_EQ(v.width, 240u); CHECK_EQ(v.height, 160u); }));
        // ~1 s of audio at 32768 Hz: more than the ring holds, so it fills and counts the overflow.
        CHECK_EQ(host->audio().available(), host->audio().capacity());
        CHECK(host->audio().available() + host->audio().droppedFrames() > 32000);
        std::printf("mGBA: audio %zu buffered + %llu dropped after 60 frames\n", host->audio().available(),
                    static_cast<unsigned long long>(host->audio().droppedFrames()));
        bool wrote = false;
        CHECK(host->flushSaveRam(err, &wrote)); CHECK(wrote);

        const std::string st = scratch + "/mgba.state";
        CHECK(host->saveState(st, err));
        CHECK(host->loadState(st, err));
        // A state from a different core must be refused before it reaches the core.
        CHECK(!host->loadState(scratch + "/fake.state", err));
        CHECK(err.find("TestCore") != std::string::npos);
        std::printf("mGBA: %llu frames, state ok, %zu audio frames buffered\n",
                    static_cast<unsigned long long>(host->framesRun()), host->audio().available());
    }
    std::vector<uint8_t> srm;
    CHECK(readFile(c.saveDir + "/sram.srm", srm));
    CHECK(!srm.empty() && srm[0] == 0x42);
    std::printf("mGBA: sram.srm is %zu bytes, byte0=0x%02X\n", srm.size(), srm.empty() ? 0 : srm[0]);
}

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: host_tests <testcore.so> <scratch> [mgba.so]\n"); return 2; }
    coreTest = argv[1];
    scratch = argv[2];
    if (argc > 3) coreMgba = argv[3];
    mkdir(scratch.c_str(), 0755);
    testUnits();
    testFakeCore();
    testMgba();
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
