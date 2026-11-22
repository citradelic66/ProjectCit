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

#include <chrono>
#include <cmath>

#include "host/AudioResampler.h"
#include "host/AudioRing.h"
#include "host/EmuRunner.h"
#include "host/FramePacer.h"
#include "host/FrameBuffer.h"
#include "host/RetroHost.h"
#include "host/SaveStateFile.h"

#if defined(RETROHOST_HAS_GL)
#include <GLES3/gl3.h>
#include "gl/EglContext.h"
#include "gl/GlRenderer.h"
#endif

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

        // A tap that starts and ends between two frames is still delivered, exactly once.
        host->input().setPointer(1234, -1234, true);
        host->input().setPointer(0, 0, false);
        host->runFrame();
        CHECK_EQ(px(*host, 3), 1u);
        CHECK_EQ(px(*host, 1), 1234u);
        CHECK_EQ(px(*host, 2), static_cast<uint16_t>(-1234));
        host->runFrame();
        CHECK_EQ(px(*host, 3), 0u);

        // Runtime option change -> GET_VARIABLE_UPDATE -> core re-reads.
        CHECK(host->options().set("test_layout", "left-right"));
        CHECK(!host->options().set("test_layout", "diagonal"));
        host->runFrame();
        CHECK_EQ(px(*host, 5), 0u);

        // Frame dupes keep the previous image.
        for (int i = 0; i < 1; ++i) host->runFrame();   // frame 9
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


// ------------------------------------------------------------------ Phase 3

static void testPacer() {
    using C = FramePacer::Clock;
    FramePacer p;
    p.setRate(59.7275);
    const auto t0 = C::time_point{};
    p.reset(t0);
    C::time_point d{};
    for (int i = 0; i < 59727; ++i) d = p.next(t0 + std::chrono::milliseconds(i / 100));  // always on time
    // 59727 frames at 59.7275 Hz = 1000.0 s, with no accumulated rounding drift.
    const double secs = std::chrono::duration<double>(d - t0).count();
    CHECK(std::fabs(secs - 59727 / 59.7275) < 0.001);
    CHECK_EQ(p.resyncs(), 0u);
    // Falling 1 s behind (e.g. paused in a debugger) resyncs instead of bursting 60 frames.
    const auto late = d + std::chrono::seconds(1);
    CHECK(p.next(late) == late);
    CHECK_EQ(p.resyncs(), 1u);
    p.setRate(59.8261, 2.0);
    CHECK(std::fabs(std::chrono::duration<double>(p.period()).count() - 1.0 / (59.8261 * 2)) < 1e-6);
}

/** Simulates a core producing audio at core rate in per-frame bursts while a device consumes at its own rate. */
struct AudioSim {
    double coreRate, deviceRate, fps;
    size_t burst;      // device callback size
    double clockSkew;  // >1: emulation runs fast relative to the audio crystal
};

static void runAudioSim(const AudioSim& sim, double seconds, size_t& underruns, uint64_t& dropped, double& minFill, double& maxFill, double& lastRatio) {
    AudioRing ring(16384);
    AudioResampler rs(ring);
    const size_t target = static_cast<size_t>(sim.coreRate / sim.fps * 4);
    rs.configure(sim.coreRate, sim.deviceRate, target);
    std::vector<int16_t> frame;
    std::vector<float> out(sim.burst * 2);
    double tProduce = 0, tConsume = 0, produced = 0;
    const double framePeriod = 1.0 / (sim.fps * sim.clockSkew);
    const double burstPeriod = sim.burst / sim.deviceRate;
    minFill = 1e9; maxFill = 0;
    uint64_t phase = 0;
    while (tConsume < seconds) {
        if (tProduce <= tConsume) {
            // One emulated frame worth of samples (fractional accumulation like a real core).
            const double want = sim.coreRate / sim.fps;
            const size_t n = static_cast<size_t>(produced + want) - static_cast<size_t>(produced);
            produced += want;
            frame.resize(n * 2);
            for (size_t i = 0; i < n; ++i, ++phase) {
                const int16_t v = static_cast<int16_t>(8000 * std::sin(2 * M_PI * 1000.0 * phase / sim.coreRate));
                frame[i * 2] = frame[i * 2 + 1] = v;
            }
            ring.push(frame.data(), n);
            tProduce += framePeriod;
        } else {
            rs.render(out.data(), sim.burst);
            tConsume += burstPeriod;
            if (tConsume > 2.0) {  // ignore start-up priming
                const double f = static_cast<double>(ring.available());
                minFill = std::min(minFill, f); maxFill = std::max(maxFill, f);
            }
        }
    }
    underruns = rs.underruns();
    dropped = ring.droppedFrames();
    lastRatio = rs.ratio();
}

static void testResampler() {
    // 1) Pitch: a 1 kHz tone at 32768 Hz must still be 1 kHz at 48000 Hz.
    {
        AudioRing ring(65536);
        AudioResampler rs(ring);
        rs.configure(32768, 48000, 1024, 0.0);  // DRC off: pure conversion
        std::vector<int16_t> in(32768 * 2);
        for (int i = 0; i < 32768; ++i) in[i * 2] = in[i * 2 + 1] = static_cast<int16_t>(10000 * std::sin(2 * M_PI * 1000.0 * i / 32768));
        ring.push(in.data(), 32768);
        std::vector<float> out(40000 * 2);
        CHECK_EQ(rs.render(out.data(), 40000), 40000u);
        int crossings = 0;
        for (int i = 1; i < 40000; ++i) if ((out[(i - 1) * 2] < 0) != (out[i * 2] < 0)) ++crossings;
        const double hz = crossings / 2.0 / (40000 / 48000.0);
        CHECK(std::fabs(hz - 1000.0) < 3.0);
        std::printf("resampler: 1 kHz in -> %.1f Hz out\n", hz);
    }
    size_t underruns; uint64_t dropped; double minF, maxF, ratio;
    // 2) Matched clocks: steady state with no glitches.
    runAudioSim({32768, 48000, 59.7275, 192, 1.0}, 60, underruns, dropped, minF, maxF, ratio);
    std::printf("audio sim GBA matched: underruns=%zu dropped=%llu fill=[%.0f,%.0f] ratio=%.5f\n", underruns, (unsigned long long)dropped, minF, maxF, ratio);
    CHECK_EQ(underruns, 0u); CHECK_EQ(dropped, 0u);
    // 3) Emulation 0.3% fast (device crystal slow): without DRC the ring would overflow after ~1 min.
    runAudioSim({32768, 48000, 59.8261, 240, 1.003}, 300, underruns, dropped, minF, maxF, ratio);
    std::printf("audio sim NDS +0.3%%: underruns=%zu dropped=%llu fill=[%.0f,%.0f] ratio=%.5f\n", underruns, (unsigned long long)dropped, minF, maxF, ratio);
    CHECK_EQ(underruns, 0u); CHECK_EQ(dropped, 0u); CHECK(ratio > 1.002);
    // 4) Emulation 0.3% slow: without DRC it would starve and crackle.
    runAudioSim({32768, 44100, 59.7275, 256, 0.997}, 300, underruns, dropped, minF, maxF, ratio);
    std::printf("audio sim GBA -0.3%%: underruns=%zu dropped=%llu fill=[%.0f,%.0f] ratio=%.5f\n", underruns, (unsigned long long)dropped, minF, maxF, ratio);
    CHECK_EQ(underruns, 0u); CHECK_EQ(dropped, 0u); CHECK(ratio < 0.998);
    // 5) Muted (fast-forward) drains and plays silence.
    {
        AudioRing ring(4096);
        AudioResampler rs(ring);
        rs.configure(32768, 48000, 1024);
        std::vector<int16_t> in(2000 * 2, 1000);
        ring.push(in.data(), 2000);
        rs.setMuted(true);
        std::vector<float> out(256 * 2, 1.f);
        CHECK_EQ(rs.render(out.data(), 256), 0u);
        CHECK_EQ(ring.available(), 0u); CHECK(out[0] == 0.f);
    }
}

static void testRunner(const std::string& corePath, const char* tag, const std::string& romPath) {
    std::string err;
    auto host = RetroHost::create(cfg(corePath, tag), err);
    CHECK(host != nullptr);
    if (!host) return;
    GameSource g; g.path = romPath;
    CHECK(host->loadGame(g, err));
    const double fps = host->avInfo().timing.fps;
    auto video = std::make_unique<NullVideoOutput>();
    {
        EmuRunner runner(host.get(), std::move(video), std::make_unique<NullAudioOutput>());
        CHECK(runner.start(err));
        // Core calls from this thread are now refused: the runner owns it.
        const uint64_t f0 = host->framesRun();
        host->runFrame();
        CHECK_EQ(host->framesRun(), f0);

        runner.setViewports({{0, 0, 240, 160, 0, 0, 1, 1}});
        runner.setWindow(reinterpret_cast<void*>(1));
        std::this_thread::sleep_for(std::chrono::milliseconds(2000));
        auto s = runner.stats();
        const double rate = s.framesRun / 2.0;
        std::printf("runner %s: %llu frames in 2 s (%.2f/s, core %.4f Hz), presented %llu, measured %.2f fps\n", tag,
                    (unsigned long long)s.framesRun, rate, fps, (unsigned long long)s.presented, s.measuredFps);
        CHECK(std::fabs(rate - fps) < 2.0);            // paced at the console rate, not 60 or "as fast as possible"
        CHECK(std::fabs(s.measuredFps - fps) < 0.25);  // and the counter must say so precisely
        CHECK(s.hasWindow && s.presented > 100);
        // A long window: 59.7275 vs 60 must be distinguishable (0.27 frames/s).
        const auto w0 = runner.stats().framesRun;
        const auto t0 = std::chrono::steady_clock::now();
        std::this_thread::sleep_for(std::chrono::milliseconds(4000));
        const double longRate = (runner.stats().framesRun - w0) /
            std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
        std::printf("runner %s: 4 s window %.3f frames/s (core %.4f)\n", tag, longRate, fps);
        CHECK(std::fabs(longRate - fps) < 0.35);

        // Hammering it with commands must not speed it up.
        const uint64_t before = runner.stats().framesRun;
        for (int i = 0; i < 400; ++i) { runner.setViewports({{0, 0, 10, 10, 0, 0, 1, 1}}); std::this_thread::sleep_for(std::chrono::milliseconds(2)); }
        const double busyRate = (runner.stats().framesRun - before) / 0.8;
        CHECK(busyRate < fps + 8);

        // Pause really stops the core; state ops still work while paused.
        runner.setPaused(true);
        std::this_thread::sleep_for(std::chrono::milliseconds(100));
        const uint64_t paused = runner.stats().framesRun;
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        CHECK_EQ(runner.stats().framesRun, paused);
        CHECK(runner.stats().paused);
        const std::string st = scratch + "/" + tag + "-runner.state";
        CHECK(runner.saveState(st, err));
        CHECK(runner.loadState(st, err));
        CHECK(runner.flushSaveRam(err));

        // Surface gone (fold/background): emulation keeps running, nothing presented.
        runner.setPaused(false);
        runner.setWindow(nullptr);
        const uint64_t presentedBefore = runner.stats().presented;
        std::this_thread::sleep_for(std::chrono::milliseconds(300));
        CHECK(runner.stats().framesRun > paused + 10);
        CHECK_EQ(runner.stats().presented, presentedBefore);

        // Fast-forward x3.
        runner.setWindow(reinterpret_cast<void*>(1));
        runner.setSpeed(3.0);
        const uint64_t ff0 = runner.stats().framesRun;
        std::this_thread::sleep_for(std::chrono::milliseconds(1000));
        const double ffRate = static_cast<double>(runner.stats().framesRun - ff0);
        std::printf("runner %s: fast-forward x3 -> %.0f frames/s\n", tag, ffRate);
        CHECK(ffRate > fps * 2.5);
        runner.stop();
    }
    // Runner gone: ownership returns to whoever rebinds (LibretroCore.close does this).
    host->rebindThread();
    const uint64_t f1 = host->framesRun();
    host->runFrame();
    CHECK_EQ(host->framesRun(), f1 + 1);
}

#if defined(RETROHOST_HAS_GL)
static void testGl() {
    EglContext egl;
    std::string err;
    if (!egl.init(err)) { ++failures; std::fprintf(stderr, "FAIL EGL init: %s\n", err.c_str()); return; }
    GlRenderer r;
    CHECK(r.init(err));
    if (!err.empty()) std::fprintf(stderr, "GL: %s\n", err.c_str());
    std::printf("GL: %s | %s\n", glGetString(GL_RENDERER), glGetString(GL_VERSION));

    const int W = 300, H = 400;
    GLuint fbo, rb;
    glGenFramebuffers(1, &fbo); glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glGenRenderbuffers(1, &rb); glBindRenderbuffer(GL_RENDERBUFFER, rb);
    glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, W, H);
    glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rb);
    CHECK(glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE);

    auto readPx = [&](int x, int y) {  // top-left origin, like our quads
        uint8_t p[4];
        glReadPixels(x, H - 1 - y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, p);
        return (uint32_t(p[0]) << 16) | (uint32_t(p[1]) << 8) | p[2];
    };

    // A DS-shaped 256x384 XRGB8888 frame: top screen red, bottom screen blue,
    // with a green marker at the very first row of the bottom screen.
    std::vector<uint32_t> ds(256 * 384);
    for (int y = 0; y < 384; ++y) for (int x = 0; x < 256; ++x) ds[y * 256 + x] = y < 192 ? 0xFF0000 : 0x0000FF;
    for (int x = 0; x < 256; ++x) ds[192 * 256 + x] = 0x00FF00;
    FrameBuffer fb;
    fb.write(ds.data(), 256, 384, 256 * 4, RETRO_PIXEL_FORMAT_XRGB8888);
    r.upload(fb);
    // Swapped & separated at exactly 1x: touchscreen on top-left, top screen below it, offset.
    const std::vector<Quad> quads = {
        {0, 0, 256, 192, 0, 0.5f, 1, 1},       // BOTTOM screen (v 0.5..1)
        {40, 204, 256, 192, 0, 0, 1, 0.5f},    // TOP screen (v 0..0.5)
    };
    r.draw(W, H, quads);
    CHECK_EQ(readPx(64, 0), 0x00FF00u);     // touchscreen's first row (frame row 192) is the quad's top row
    CHECK_EQ(readPx(64, 1), 0x0000FFu);     // ...and only that row
    CHECK_EQ(readPx(64, 191), 0x0000FFu);   // touchscreen's last row
    CHECK_EQ(readPx(100, 204), 0xFF0000u);  // top screen's first row: no bleed from the other half
    CHECK_EQ(readPx(100, 395), 0xFF0000u);  // top screen's last row (frame row 191): no green bleed
    CHECK_EQ(readPx(64, 198), 0x000000u);   // the gap between quads (a hinge) stays black
    CHECK_EQ(readPx(20, 300), 0x000000u);   // left of the offset top screen: black

    // RGB565 path (mGBA on Android): a white/black 2x2 checker scaled 50x.
    const uint16_t chk[4] = {0xFFFF, 0x0000, 0x0000, 0xF800};
    fb.write(chk, 2, 2, 4, RETRO_PIXEL_FORMAT_RGB565);
    r.upload(fb);
    r.draw(W, H, {{0, 0, 100, 100, 0, 0, 1, 1}});
    CHECK_EQ(readPx(25, 25), 0xFFFFFFu);
    CHECK_EQ(readPx(75, 25), 0x000000u);
    CHECK_EQ(readPx(75, 75), 0xFF0000u);    // nearest-neighbour: no blending at 50x
    CHECK_EQ(readPx(49, 25), 0xFFFFFFu);
    CHECK_EQ(readPx(50, 25), 0x000000u);
    CHECK(glGetError() == GL_NO_ERROR);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDeleteFramebuffers(1, &fbo); glDeleteRenderbuffers(1, &rb);
    r.destroy();
    egl.destroy();
}
#endif

int main(int argc, char** argv) {
    if (argc < 3) { std::fprintf(stderr, "usage: host_tests <testcore.so> <scratch> [mgba.so]\n"); return 2; }
    coreTest = argv[1];
    scratch = argv[2];
    if (argc > 3) coreMgba = argv[3];
    mkdir(scratch.c_str(), 0755);
    testUnits();
    testFakeCore();
    testMgba();
    testPacer();
    testResampler();
    {
        const std::string rom = scratch + "/runner.tst";
        writeBytes(rom, {1, 2, 3});
        testRunner(coreTest, "runner-fake", rom);
    }
    if (!coreMgba.empty()) testRunner(coreMgba, "runner-mgba", scratch + "/sram.gba");
#if defined(RETROHOST_HAS_GL)
    testGl();
#else
    std::printf("GL tests skipped (no EGL/GLES on this machine)\n");
#endif
    std::printf("%d checks, %d failures\n", checks, failures);
    return failures == 0 ? 0 : 1;
}
