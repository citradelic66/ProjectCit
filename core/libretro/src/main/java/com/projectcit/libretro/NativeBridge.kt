package com.projectcit.libretro

/** Phase 1: proves the NDK/CMake/JNI path end to end. Phase 2 grows this into the libretro host API. */
object NativeBridge {
    val loadError: Throwable? = runCatching { System.loadLibrary("retrohost") }.exceptionOrNull()

    fun hostInfo(): String =
        if (loadError == null) nativeHostInfo() else "native library failed to load: ${loadError.message}"

    private external fun nativeHostInfo(): String
}
