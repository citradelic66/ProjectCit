# ProjectCit

Libretro-based GBA / GB / GBC / Nintendo DS emulator for Android, built for candybar
phones and book-style foldables (Galaxy Z Fold).

## Status: Phase 4 of 4 (feature-complete, not yet device-validated)

| Phase | Scope | State |
|---|---|---|
| 1 | Gradle/NDK setup, SAF ROM + BIOS indexer, posture-aware layout engine, hinge visualizer | ✅ done |
| 2 | C++17 libretro host (`dlopen`), pointer input bridge, save/state serialization, JNI | ✅ done |
| 3 | EGL / GLES 3.0 single + dual-quad renderer, Oboe audio, lifecycle-free emu thread | ✅ done |
| 4 | Touch gamepad overlay, HID mapping, haptics, fast-forward, save-state UI | ✅ done |

## Modules

| Module | What it is |
|---|---|
| `:app` | Compose UI: library, session shell, WindowManager posture adapter, gamepad detection |
| `:core:layout` | **Pure Kotlin/JVM** layout engine: (window, hinge, insets, options) → screen quads + control zones. Fully unit-tested. |
| `:core:input` | **Pure Kotlin/JVM** input rules: virtual-pad geometry and hit-testing, multi-touch tracking, stylus-vs-pad routing, controller/keyboard mapping with hysteresis. Fully unit-tested. |
| `:core:storage` | SAF tree walker, ROM/BIOS classifier, `system/` BIOS mirror, cached index |
| `:core:libretro` | Native emulation thread (`EmuRunner`: console-rate pacing, EGL/GLES 3.0 dual-quad renderer, Oboe audio with dynamic rate control) on top of the C++17 libretro host (`libretrohost.so`): `dlopen` loader, environment, core options v0–v2, pointer/joypad input, battery saves, CRC-checked save states, frame + audio buffers. Kotlin API: `LibretroCore`, `ContentGame`. |

## Build

Requirements: JDK 17+ (Android Studio's bundled JDK 21 works), Android SDK 36, NDK 27.2.12479018, CMake 3.22.1.

```bash
./gradlew test            # layout, storage, and Kotlin→JNI→host tests (Linux runs the native parts)
./gradlew :core:libretro:nativeHostTest   # C++ host tests under ASan/UBSan (Linux)
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

Add `-PmgbaCore=/path/to/mgba_libretro.so` (a Linux build of mGBA's libretro core) to also run
the real-core tests: a hand-assembled GBA ROM boots, writes cartridge SRAM, and the host
persists it to `.srm` and round-trips save states.

The GL renderer tests need Mesa's EGL/GLES on Linux (`apt-get install libegl-dev libgles-dev libegl-mesa0`);
without it they are skipped and everything else still runs.

### How a session runs
```
UI thread ──surface / viewports / touch──▶ EmulationController (process-scoped)
                                                 │
                                   EmuRunner thread (native, one per game)
                                   ├─ retro_run() paced at 59.7275 / 59.8261 Hz
                                   ├─ EGL context (survives surface loss) → GLES 3.0 quads
                                   └─ AudioRing ─▶ Oboe callback: resample + dynamic rate control
```
Folding, rotating or recreating the Activity only swaps the Surface; the game never stops.
Backgrounding the app pauses, flushes the battery save and writes an automatic save state.

### Controls
| Physical input | Game | Notes |
|---|---|---|
| Bottom / right / left / top face button | B / A / Y / X | Positional (Nintendo) layout: buttons sit where the GBA/DS has them |
| L1 / R1 | L / R | |
| D-pad, left stick, hat | D-pad | Stick uses hysteresis (press 0.5, release 0.35) |
| Start / Select | Start / Select | |
| **R2** (hold) | Fast-forward ×3 | GBA/DS games never use L2/R2 |
| **L2** | Swap DS screens | |
| Mode/Guide, right-stick click | Quick menu | |
| Keyboard | X=A, Z=B, S=X, A=Y, Q=L, W=R, Enter=Start, Shift/Backspace=Select, Tab=fast-forward, Esc=menu, F1=swap | |

On-screen: the pad appears automatically and hides when a controller is connected. A finger
on the DS touchscreen is the stylus; every other finger drives the pad (stylus + D-pad + A at
once works). Back opens the quick menu (save-state slots with thumbnails, fast-forward,
layout, vibration, controls opacity).

### Bundling cores
Cores are not committed. Drop the Android builds into `app/cores/<abi>/` (see
[`app/cores/README.md`](app/cores/README.md)); the build renames them to `lib*.so` so Android extracts them.
melonDS DS publishes **arm64-v8a only**, so NDS is unavailable on 32-bit phones and the x86_64 emulator.

`./gradlew :core:layout:test` also writes `core/layout/build/layout-gallery/index.html`,
a rendering of the engine's output for 10 device/posture cases.

## Games folder layout

```
Emulation/            ← pick this folder in the app
├── gba/ …            .gba .gbc .gb (any sub-folders, up to 8 deep)
├── nds/ …            .nds
└── system/           optional: bios7.bin bios9.bin firmware.bin gba_bios.bin
```

Battery saves (`.sav`/`.srm`) next to a ROM are imported on first launch, never overwriting an in-app save.

BIOS files are validated by size and copied into app-private storage, because libretro
cores need a real filesystem path. Missing BIOS falls back to melonDS DS's FreeBIOS or mGBA's HLE BIOS.
