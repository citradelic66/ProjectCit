package com.projectcit.libretro

/**
 * Raw JNI surface of libretrohost.so; use [LibretroCore] instead.
 * Not `internal`: Kotlin mangles internal names (nativeCreate$module), which breaks JNI lookup.
 * Handles are opaque native pointers; 0 means "none".
 */
object NativeBridge {
    val loadError: Throwable? = runCatching { System.loadLibrary("retrohost") }.exceptionOrNull()

    fun hostInfo(): String =
        if (loadError == null) nativeHostInfo() else "native library failed to load: ${loadError.message}"

    private external fun nativeHostInfo(): String

    external fun nativeCreate(corePath: String, systemDir: String, saveDir: String,
                                       keys: Array<String>, values: Array<String>, errorOut: Array<String?>): Long
    external fun nativeDestroy(h: Long)
    external fun nativeSystemInfo(h: Long): Array<String>
    external fun nativeLoadGame(h: Long, path: String, fd: Int, saveName: String, errorOut: Array<String?>): Boolean
    external fun nativeUnloadGame(h: Long)
    external fun nativeAvInfo(h: Long): DoubleArray
    external fun nativeRunFrames(h: Long, n: Int)
    external fun nativeReset(h: Long)
    external fun nativeRebindThread(h: Long)
    external fun nativeSetFastForward(h: Long, on: Boolean)
    external fun nativeFramesRun(h: Long): Long
    external fun nativeSetButtons(h: Long, port: Int, mask: Int)
    external fun nativeSetAnalog(h: Long, port: Int, stick: Int, axis: Int, value: Int)
    external fun nativeSetPointer(h: Long, x: Int, y: Int, pressed: Boolean)
    external fun nativeFlushSaveRam(h: Long, errorOut: Array<String?>): Int
    external fun nativeSaveState(h: Long, path: String, errorOut: Array<String?>): Boolean
    external fun nativeLoadState(h: Long, path: String, errorOut: Array<String?>): Boolean
    external fun nativeFrameInfo(h: Long): LongArray?
    external fun nativeCopyFrameArgb(h: Long): IntArray?
    external fun nativeReadAudio(h: Long, dst: ShortArray): Int
    external fun nativeAudioStats(h: Long): LongArray
    external fun nativeSetOption(h: Long, key: String, value: String): Boolean
    external fun nativeGetOptions(h: Long): Array<String>
    external fun nativePollMessage(h: Long): String?
    external fun nativeRumble(h: Long): IntArray

    external fun nativeRunnerCreate(hostHandle: Long): Long
    external fun nativeRunnerStart(r: Long, errorOut: Array<String?>): Boolean
    external fun nativeRunnerDestroy(r: Long)
    external fun nativeRunnerSetPaused(r: Long, paused: Boolean)
    external fun nativeRunnerSetSpeed(r: Long, speed: Double)
    external fun nativeRunnerReset(r: Long)
    external fun nativeRunnerSetViewports(r: Long, quads: FloatArray)
    external fun nativeRunnerSetSurface(r: Long, surface: android.view.Surface?)
    external fun nativeRunnerSaveState(r: Long, path: String, errorOut: Array<String?>): Boolean
    external fun nativeRunnerLoadState(r: Long, path: String, errorOut: Array<String?>): Boolean
    external fun nativeRunnerFlushSaveRam(r: Long, errorOut: Array<String?>): Boolean
    external fun nativeRunnerStats(r: Long): DoubleArray
}
