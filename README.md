# ProjectCit

Libretro-based GBA / GB / GBC / Nintendo DS emulator for Android, built for candybar
phones and book-style foldables (Galaxy Z Fold).

## Status: Phase 2 of 4

| Phase | Scope | State |
|---|---|---|
| 1 | Gradle/NDK setup, SAF ROM + BIOS indexer, posture-aware layout engine, hinge visualizer | ✅ done |
| 2 | C++17 libretro host (`dlopen`), pointer input bridge, save/state serialization, JNI | ✅ done |
| 3 | EGL / GLES 3.0 single + dual-quad renderer, Oboe audio, lifecycle-free emu thread | next |
| 4 | Touch gamepad overlay, HID mapping, haptics, fast-forward, save-state UI | |

## Modules

| Module | What it is |
|---|---|
| `:app` | Compose UI: library, session shell, WindowManager posture adapter, gamepad detection |
| `:core:layout` | **Pure Kotlin/JVM** layout engine: (window, hinge, insets, options) → screen quads + control zones. Fully unit-tested. |
| `:core:storage` | SAF tree walker, ROM/BIOS classifier, `system/` BIOS mirror, cached index |
| `:core:libretro` | C++17 libretro host (`libretrohost.so`): `dlopen` loader, environment, core options v0–v2, pointer/joypad input, battery saves, CRC-checked save states, frame + audio buffers. Kotlin API: `LibretroCore`, `ContentGame`. |

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
