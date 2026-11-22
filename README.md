# ProjectCit

Libretro-based GBA / GB / GBC / Nintendo DS emulator for Android, built for candybar
phones and book-style foldables (Galaxy Z Fold).

## Status: Phase 1 of 4

| Phase | Scope | State |
|---|---|---|
| 1 | Gradle/NDK setup, SAF ROM + BIOS indexer, posture-aware layout engine, hinge visualizer | ✅ done |
| 2 | C++17 libretro host (`dlopen`), pointer input bridge, save/state serialization, JNI | next |
| 3 | EGL / GLES 3.0 single + dual-quad renderer, Oboe audio, lifecycle-free emu thread | |
| 4 | Touch gamepad overlay, HID mapping, haptics, fast-forward, save-state UI | |

## Modules

| Module | What it is |
|---|---|
| `:app` | Compose UI: library, session shell, WindowManager posture adapter, gamepad detection |
| `:core:layout` | **Pure Kotlin/JVM** layout engine: (window, hinge, insets, options) → screen quads + control zones. Fully unit-tested. |
| `:core:storage` | SAF tree walker, ROM/BIOS classifier, `system/` BIOS mirror, cached index |
| `:core:libretro` | NDK/CMake C++17 library (`libretrohost.so`). Phase 1 = toolchain smoke test. |

## Build

Requirements: JDK 17+ (Android Studio's bundled JDK 21 works), Android SDK 36, NDK 27.2.12479018, CMake 3.22.1.

```bash
./gradlew test            # layout + storage unit tests
./gradlew assembleDebug   # app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew :core:layout:test` also writes `core/layout/build/layout-gallery/index.html`,
a rendering of the engine's output for 10 device/posture cases.

## Games folder layout

```
Emulation/            ← pick this folder in the app
├── gba/ …            .gba .gbc .gb (any sub-folders, up to 8 deep)
├── nds/ …            .nds
└── system/           optional: bios7.bin bios9.bin firmware.bin gba_bios.bin
```

BIOS files are validated by size and copied into app-private storage, because libretro
cores need a real filesystem path. Missing BIOS falls back to melonDS DS's FreeBIOS or mGBA's HLE BIOS.
