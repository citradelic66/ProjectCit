package com.projectcit.libretro

import java.io.File

/**
 * One running libretro core (wraps native RetroHost). Only one may be open at a time.
 *
 * Threading contract: [open], [loadGame], [runFrame], [reset], save/state calls and
 * [close] must happen on a single "emulation thread" (Phase 3 owns it). If that
 * thread differs from the one that called [open], call [rebindToCurrentThread] on it
 * first. Input setters, [frameInfo]/[copyFrameArgb], audio and option reads are safe
 * from any thread. Calls on the wrong thread are refused natively (logged no-ops).
 */
class LibretroCore private constructor(private var handle: Long) : AutoCloseable {

    companion object {
        fun open(
            corePath: String,
            systemDir: File,
            saveDir: File,
            optionOverrides: Map<String, String> = emptyMap(),
        ): LibretroCore {
            NativeBridge.loadError?.let { throw LibretroException("native host unavailable: ${it.message}") }
            systemDir.mkdirs()
            saveDir.mkdirs()
            val err = arrayOfNulls<String>(1)
            val h = NativeBridge.nativeCreate(
                corePath, systemDir.absolutePath, saveDir.absolutePath,
                optionOverrides.keys.toTypedArray(), optionOverrides.values.toTypedArray(), err,
            )
            if (h == 0L) throw LibretroException(err[0] ?: "failed to open core $corePath")
            return LibretroCore(h)
        }
    }

    internal fun h(): Long = handle.also { check(it != 0L) { "LibretroCore is closed" } }

    private var runner: EmulationRunner? = null

    /**
     * Hands this core to a dedicated native emulation thread (see [EmulationRunner]).
     * While it runs, direct core calls from other threads are refused natively;
     * use the runner's methods instead.
     */
    fun createRunner(): EmulationRunner {
        check(runner == null) { "a runner is already attached" }
        return EmulationRunner(this, NativeBridge.nativeRunnerCreate(h())).also { runner = it }
    }

    internal fun onRunnerClosed() {
        runner = null
        NativeBridge.nativeRebindThread(h())  // core belongs to the caller again
    }

    val systemInfo: CoreSystemInfo by lazy {
        val a = NativeBridge.nativeSystemInfo(h())
        CoreSystemInfo(a[0], a[1], a[2].split('|').filter { it.isNotEmpty() }, a[3] == "1")
    }

    var gameLoaded: Boolean = false
        private set

    /**
     * @param path what the core sees; must keep the real file extension.
     * @param fd readable descriptor for the same content (SAF), or -1 to read [path].
     * @param saveName base name for the battery save (.srm).
     */
    fun loadGame(path: String, fd: Int = -1, saveName: String = File(path).nameWithoutExtension) {
        val err = arrayOfNulls<String>(1)
        if (!NativeBridge.nativeLoadGame(h(), path, fd, saveName, err)) throw LibretroException(err[0] ?: "load failed")
        gameLoaded = true
    }

    fun unloadGame() {
        NativeBridge.nativeUnloadGame(h())
        gameLoaded = false
    }

    val avInfo: AvInfo
        get() = NativeBridge.nativeAvInfo(h()).let {
            AvInfo(it[0].toInt(), it[1].toInt(), it[2].toInt(), it[3].toInt(), it[4].toFloat(), it[5], it[6])
        }

    val framesRun: Long get() = NativeBridge.nativeFramesRun(h())

    fun runFrame() = NativeBridge.nativeRunFrames(h(), 1)
    fun runFrames(n: Int) = NativeBridge.nativeRunFrames(h(), n)
    fun reset() = NativeBridge.nativeReset(h())
    fun rebindToCurrentThread() = NativeBridge.nativeRebindThread(h())

    var fastForward: Boolean = false
        set(value) { field = value; NativeBridge.nativeSetFastForward(h(), value) }

    // ---- input (any thread)
    fun setButtons(port: Int, mask: Int) = NativeBridge.nativeSetButtons(h(), port, mask)
    fun setAnalog(port: Int, stick: Int, axis: Int, value: Int) = NativeBridge.nativeSetAnalog(h(), port, stick, axis, value)
    /** x/y in libretro pointer space [-0x7fff, 0x7fff] over the whole core frame (see PointerMapper). */
    fun setPointer(x: Int, y: Int, pressed: Boolean) = NativeBridge.nativeSetPointer(h(), x, y, pressed)

    // ---- persistence (emulation thread)
    /** @return true if the battery save changed and was written. */
    fun flushSaveRam(): Boolean {
        val err = arrayOfNulls<String>(1)
        return when (NativeBridge.nativeFlushSaveRam(h(), err)) {
            -1 -> throw LibretroException(err[0] ?: "save flush failed")
            1 -> true
            else -> false
        }
    }

    fun saveState(file: File) {
        file.parentFile?.mkdirs()
        val err = arrayOfNulls<String>(1)
        if (!NativeBridge.nativeSaveState(h(), file.absolutePath, err)) throw LibretroException(err[0] ?: "save state failed")
    }

    fun loadState(file: File) {
        val err = arrayOfNulls<String>(1)
        if (!NativeBridge.nativeLoadState(h(), file.absolutePath, err)) throw LibretroException(err[0] ?: "load state failed")
    }

    // ---- video / audio (any thread)
    fun frameInfo(): FrameInfo? = NativeBridge.nativeFrameInfo(h())?.let {
        FrameInfo(it[0].toInt(), it[1].toInt(), if (it[2] == 1L) FrameFormat.XRGB8888 else FrameFormat.RGB565, it[3])
    }

    fun copyFrameArgb(): ArgbFrame? = NativeBridge.nativeCopyFrameArgb(h())?.let {
        ArgbFrame(it[0], it[1], it.copyOfRange(2, it.size))
    }

    /** Pops interleaved stereo samples; returns frames read. Phase 3's Oboe stream reads natively instead. */
    fun readAudio(dst: ShortArray): Int = NativeBridge.nativeReadAudio(h(), dst)

    fun audioStats(): AudioStats = NativeBridge.nativeAudioStats(h()).let { AudioStats(it[0], it[1], it[2]) }

    // ---- options / messages
    fun setOption(key: String, value: String): Boolean = NativeBridge.nativeSetOption(h(), key, value)

    fun options(): List<CoreOption> = NativeBridge.nativeGetOptions(h()).toList().chunked(6).map {
        CoreOption(it[0], it[1], it[2], it[3], it[4].split('|').filter(String::isNotEmpty), it[5] == "1")
    }

    fun pollMessage(): String? = NativeBridge.nativePollMessage(h())

    /** [strong, weak] rumble strength 0..65535 last requested by the core. */
    fun rumble(): IntArray = NativeBridge.nativeRumble(h())

    /** Flushes the battery save, unloads the game and dlcloses the core. */
    override fun close() {
        if (handle == 0L) return
        runner?.close()
        NativeBridge.nativeRebindThread(handle)  // teardown (incl. final .srm flush) runs on this thread
        NativeBridge.nativeDestroy(handle)
        handle = 0L
        gameLoaded = false
    }
}
