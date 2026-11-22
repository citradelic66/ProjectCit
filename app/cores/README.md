# Bundled libretro cores

Cores are not committed (licensing: melonDS DS is GPL-3.0, mGBA is MPL-2.0).
Download them and place them here **by ABI**; the build renames them to `lib*.so`.

```
app/cores/
├── arm64-v8a/
│   ├── mgba_libretro_android.so
│   └── melondsds_libretro_android.so   ← melonDS DS ships arm64-v8a only
├── armeabi-v7a/
│   └── mgba_libretro_android.so
└── x86_64/
    └── mgba_libretro_android.so         ← Android Studio emulator
```

Sources:
- mGBA: https://buildbot.libretro.com/nightly/android/latest/<abi>/mgba_libretro_android.so.zip
- melonDS DS: https://github.com/JesseTG/melonds-ds/releases (asset `melondsds_libretro-android.zip`)
