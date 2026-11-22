package com.projectcit.libretro

import android.view.Surface
import java.io.File

data class RunnerStats(
    val framesRun: Long,
    val framesPresented: Long,
    val measuredFps: Double,
    val targetFps: Double,
    val audioBufferedFrames: Double,
    val audioUnderruns: Long,
    val audioDroppedFrames: Long,
    /** Dynamic-rate-control resampling ratio; 1.0 = no correction. */
    val drcRatio: Double,
    val paused: Boolean,
    val hasSurface: Boolean,
    val coreRequestedShutdown: Boolean,
    val deviceSampleRate: Int,
    val pacerResyncs: Long,
    /** Failed surface attaches / buffer swaps on the GL side (0 when healthy). */
    val videoErrors: Long,
)

/**
 * Native emulation thread for one [LibretroCore]: runs the core at the console's
 * exact rate, presents through EGL/GLES 3.0 to whatever Surface is attached (none
 * is fine), and streams audio through Oboe. It is not tied to any Activity: attach
 * and detach surfaces as they come and go, and the game simply continues.
 *
 * All methods are thread-safe. State/save methods block until executed between frames.
 */
class EmulationRunner internal constructor(private val core: LibretroCore, private var handle: Long) : AutoCloseable {

    private fun r(): Long = handle.also { check(it != 0L) { "EmulationRunner is closed" } }

    fun start() {
        val err = arrayOfNulls<String>(1)
        if (!NativeBridge.nativeRunnerStart(r(), err)) throw LibretroException(err[0] ?: "runner failed to start")
    }

    fun setPaused(paused: Boolean) = NativeBridge.nativeRunnerSetPaused(r(), paused)

    /** 1.0 normal; >1 fast-forward (audio muted); <1 slow motion. */
    fun setSpeed(speed: Double) = NativeBridge.nativeRunnerSetSpeed(r(), speed)

    fun reset() = NativeBridge.nativeRunnerReset(r())

    /** 8 floats per screen: x, y, w, h (surface px), u0, v0, u1, v1 (core-frame UV). */
    fun setViewports(quads: FloatArray) {
        require(quads.size % 8 == 0) { "quads must be 8 floats each" }
        NativeBridge.nativeRunnerSetViewports(r(), quads)
    }

    /**
     * Attach (non-null) or detach (null) the output surface. Blocks until the
     * emulation thread has switched, which SurfaceHolder.Callback.surfaceDestroyed
     * requires before it returns.
     */
    fun setSurface(surface: Surface?) = NativeBridge.nativeRunnerSetSurface(r(), surface)

    fun saveState(file: File) {
        file.parentFile?.mkdirs()
        val err = arrayOfNulls<String>(1)
        if (!NativeBridge.nativeRunnerSaveState(r(), file.absolutePath, err)) throw LibretroException(err[0] ?: "save state failed")
    }

    fun loadState(file: File) {
        val err = arrayOfNulls<String>(1)
        if (!NativeBridge.nativeRunnerLoadState(r(), file.absolutePath, err)) throw LibretroException(err[0] ?: "load state failed")
    }

    fun flushSaveRam() {
        val err = arrayOfNulls<String>(1)
        if (!NativeBridge.nativeRunnerFlushSaveRam(r(), err)) throw LibretroException(err[0] ?: "save flush failed")
    }

    fun stats(): RunnerStats = NativeBridge.nativeRunnerStats(r()).let {
        RunnerStats(
            it[0].toLong(), it[1].toLong(), it[2], it[3], it[4], it[5].toLong(), it[6].toLong(), it[7],
            it[8] != 0.0, it[9] != 0.0, it[10] != 0.0, it[11].toInt(), it[12].toLong(), it[13].toLong(),
        )
    }

    /** Stops the thread (flushing the battery save) and returns the core to the caller. */
    override fun close() {
        if (handle == 0L) return
        NativeBridge.nativeRunnerDestroy(handle)
        handle = 0L
        core.onRunnerClosed()
    }
}
