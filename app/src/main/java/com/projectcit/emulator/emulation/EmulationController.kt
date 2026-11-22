package com.projectcit.emulator.emulation

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.projectcit.input.Hotkey
import com.projectcit.input.PhysicalPad
import com.projectcit.layout.PointerSample
import com.projectcit.layout.ScreenViewport
import com.projectcit.libretro.RunnerStats
import com.projectcit.storage.LibraryIndex
import com.projectcit.storage.RomEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Process-scoped owner of the running game. Lives in the Application, so folding,
 * rotation, multi-window and Activity recreation never touch the session: the UI
 * only attaches a Surface, viewports and input to it.
 *
 * The game pauses when the *process* goes to the background (ProcessLifecycleOwner),
 * not when an Activity stops, and an automatic save state is written then.
 */
class EmulationController(private val context: Context, private val systemDir: File) : DefaultLifecycleObserver {

    sealed interface State {
        data object Idle : State
        data class Loading(val rom: RomEntry) : State
        data class Running(val rom: RomEntry, val userPaused: Boolean) : State
        data class Failed(val rom: RomEntry, val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val serial = Dispatchers.IO.limitedParallelism(1)  // start/stop/state ops run in order
    private val scope = CoroutineScope(SupervisorJob() + serial)

    private val lock = Any()
    private var session: GameSession? = null
    private var surface: Surface? = null
    private var quads = FloatArray(0)
    private var backgrounded = false
    private var userPaused = false

    // ---- input: touch pad + physical controllers merge into one libretro mask
    private val physical = PhysicalPad()
    @Volatile private var touchMask = 0
    @Volatile private var physicalMask = 0
    @Volatile private var ffToggle = false
    @Volatile private var ffHeld = false
    private val _fastForward = MutableStateFlow(false)
    val fastForward: StateFlow<Boolean> = _fastForward.asStateFlow()
    private val _hotkeys = MutableSharedFlow<Hotkey>(extraBufferCapacity = 8)
    /** Emulator shortcuts from controllers/keyboards (menu, swap screens) for the UI to act on. */
    val hotkeys: SharedFlow<Hotkey> = _hotkeys.asSharedFlow()

    init {
        // A controller unplugged mid-press must not leave buttons stuck down.
        context.getSystemService(InputManager::class.java).registerInputDeviceListener(object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) = Unit
            override fun onInputDeviceChanged(deviceId: Int) = Unit
            override fun onInputDeviceRemoved(deviceId: Int) {
                physical.reset()
                applyPhysical(physical.mask, physical.fastForwardHeld)
            }
        }, Handler(Looper.getMainLooper()))
    }

    val isRunning: Boolean get() = state.value is State.Running

    /**
     * Real size of the core's last frame (width, height). Single-screen cores can
     * change it at runtime: mGBA switches a Super Game Boy game to 256×224 when its
     * border appears. The layout follows it so the picture is never stretched.
     */
    private val _frameSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val frameSize: StateFlow<Pair<Int, Int>?> = _frameSize.asStateFlow()
    private var frameWatch: kotlinx.coroutines.Job? = null
    private val watchScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val currentRomUri: String? get() = (state.value as? State.Running)?.rom?.uri ?: (state.value as? State.Loading)?.rom?.uri

    fun start(rom: RomEntry, index: LibraryIndex) {
        _state.value = State.Loading(rom)
        scope.launch {
            stopInternal()
            try {
                val s = GameSession.launch(context, rom, index, systemDir)
                synchronized(lock) {
                    session = s
                    val runner = s.startRunner()
                    runner.setViewports(quads)
                    surface?.let(runner::setSurface)
                    userPaused = false
                    if (backgrounded) runner.setPaused(true)
                    touchMask = 0; physicalMask = 0; ffToggle = false; ffHeld = false; _fastForward.value = false
                    physical.reset()
                }
                _frameSize.value = null
                frameWatch = watchScope.launch {
                    while (true) {
                        val f = runCatching { synchronized(lock) { session?.core?.frameInfo() } }.getOrNull()
                        if (f != null && (f.width to f.height) != _frameSize.value) _frameSize.value = f.width to f.height
                        kotlinx.coroutines.delay(250)
                    }
                }
                Log.i(TAG, "started ${rom.title} with ${s.coreId.displayName} (${s.contentStrategy}, imported save: ${s.importedSave})")
                _state.value = State.Running(rom, userPaused = false)
            } catch (e: Exception) {
                Log.e(TAG, "launch failed", e)
                _state.value = State.Failed(rom, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun stop() {
        scope.launch { stopInternal() }
    }

    private fun stopInternal() {
        frameWatch?.cancel()
        frameWatch = null
        _frameSize.value = null
        val s = synchronized(lock) { session.also { session = null } } ?: run { _state.value = State.Idle; return }
        runCatching { s.close() }.onFailure { Log.e(TAG, "close failed", it) }
        _state.value = State.Idle
    }

    // ---- UI attachments (called on the main thread)

    /** Blocks briefly until the emulation thread uses the new surface. */
    fun attachSurface(s: Surface): Unit = synchronized(lock) {
        surface = s
        session?.runner?.setSurface(s)
    }

    /** Must be called from surfaceDestroyed: returns only after the surface is released. */
    fun detachSurface(): Unit = synchronized(lock) {
        surface = null
        session?.runner?.setSurface(null)
    }

    fun setViewports(screens: List<ScreenViewport>): Unit = synchronized(lock) {
        quads = FloatArray(screens.size * 8).also { q ->
            screens.forEachIndexed { i, s ->
                val o = i * 8
                q[o] = s.rect.left; q[o + 1] = s.rect.top; q[o + 2] = s.rect.width; q[o + 3] = s.rect.height
                q[o + 4] = s.sourceULeft; q[o + 5] = s.sourceVTop; q[o + 6] = s.sourceURight; q[o + 7] = s.sourceVBottom
            }
        }
        session?.runner?.setViewports(quads)
    }

    fun setPointer(sample: PointerSample) {
        session?.core?.setPointer(sample.x, sample.y, sample.pressed)  // input setters are thread-safe
    }

    /** Virtual pad mask (main thread). */
    fun setTouchButtons(mask: Int) {
        touchMask = mask
        pushButtons()
    }

    /** Physical key from the Activity. @return true if consumed. */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int): Boolean {
        if (!isRunning) return false
        val u = physical.onKey(keyCode, down, repeatCount) ?: return false
        applyPhysical(u.mask, u.fastForwardHeld)
        u.triggered.forEach { _hotkeys.tryEmit(it) }
        return true
    }

    /** Joystick/hat/trigger axes from the Activity. */
    fun onAxes(deviceId: Int, axes: Map<Int, Float>): Boolean {
        if (!isRunning) return false
        val u = physical.onAxes(deviceId, axes)
        applyPhysical(u.mask, u.fastForwardHeld)
        u.triggered.forEach { _hotkeys.tryEmit(it) }
        return true
    }

    private fun applyPhysical(mask: Int, ffHeldNow: Boolean) {
        physicalMask = mask
        pushButtons()
        if (ffHeldNow != ffHeld) { ffHeld = ffHeldNow; applySpeed() }
    }

    private fun pushButtons() {
        session?.core?.setButtons(0, touchMask or physicalMask)  // atomic store natively; any thread
    }

    fun setUserPaused(paused: Boolean): Unit = synchronized(lock) {
        userPaused = paused
        session?.runner?.setPaused(paused || backgrounded)
        (state.value as? State.Running)?.let { _state.value = it.copy(userPaused = paused) }
    }

    fun setFastForwardToggle(on: Boolean) {
        ffToggle = on
        applySpeed()
    }

    private fun applySpeed() {
        val on = ffToggle || ffHeld
        _fastForward.value = on
        session?.runner?.setSpeed(if (on) FAST_FORWARD_SPEED else 1.0)
    }

    fun reset() {
        session?.runner?.reset()
    }

    fun slots(): List<StateSlots.Slot?> = session?.slots?.list() ?: emptyList()

    fun stats(): RunnerStats? = synchronized(lock) { session?.runner?.stats() }

    suspend fun saveState(slot: Int) = withContext(serial) { session?.saveState(slot) }
    suspend fun loadState(slot: Int) = withContext(serial) { session?.loadState(slot) }

    // ---- process lifecycle

    override fun onStop(owner: LifecycleOwner) {
        synchronized(lock) {
            backgrounded = true
            session?.runner?.setPaused(true)  // also flushes the battery save
        }
        scope.launch {
            runCatching { session?.saveState(StateSlots.AUTO_SLOT) }.onFailure { Log.w(TAG, "auto-save failed", it) }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        synchronized(lock) {
            backgrounded = false
            session?.runner?.setPaused(userPaused)
        }
    }

    companion object {
        private const val TAG = "EmulationController"
        const val FAST_FORWARD_SPEED = 3.0
    }
}
