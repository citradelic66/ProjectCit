package com.projectcit.emulator.ui.session

import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projectcit.emulator.ProjectCitApp
import com.projectcit.emulator.emulation.EmulationController
import com.projectcit.emulator.posture.PostureSource
import com.projectcit.emulator.posture.RawFold
import com.projectcit.emulator.posture.foldingFeature
import com.projectcit.emulator.posture.rememberGamepadConnected
import com.projectcit.emulator.posture.rememberWindowLayoutInfo
import com.projectcit.emulator.posture.rememberWindowSpec
import com.projectcit.emulator.posture.toRaw
import com.projectcit.emulator.settings.ControllerMode
import com.projectcit.input.Hotkey
import com.projectcit.input.PadButton
import com.projectcit.input.TouchRouter
import com.projectcit.input.VirtualPadBuilder
import com.projectcit.layout.LayoutEngine
import com.projectcit.layout.LayoutOptions
import com.projectcit.layout.LayoutSpec
import com.projectcit.layout.NdsBookMode
import com.projectcit.layout.NdsEmphasis
import com.projectcit.layout.Platform
import com.projectcit.layout.PointerSample
import com.projectcit.layout.Posture
import com.projectcit.layout.WindowSpec
import com.projectcit.libretro.RunnerStats
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The play screen: GL surface underneath, virtual pad and hotkey bar on top.
 * Every finger is routed independently: one that lands on the DS touchscreen
 * becomes the stylus, the rest drive the pad. Physical controllers arrive via
 * MainActivity and hide the pad automatically.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(initialPlatform: Platform, romTitle: String?, isGame: Boolean, onExit: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as ProjectCitApp
    val emu = app.emulation
    val emuState by emu.state.collectAsStateWithLifecycle()
    val settings by app.sessionSettings.values.collectAsStateWithLifecycle()
    val fastForward by emu.fastForward.collectAsStateWithLifecycle()
    val frameSize by emu.frameSize.collectAsStateWithLifecycle()
    val running = isGame && emuState is EmulationController.State.Running
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val density = LocalDensity.current.density

    var platform by rememberSaveable { mutableStateOf(initialPlatform) }
    var source by rememberSaveable { mutableStateOf(PostureSource.REAL) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val haptics by rememberUpdatedState(settings.haptics)
    var hudExpanded by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf<RunnerStats?>(null) }
    var padMask by remember { mutableIntStateOf(0) }
    var slotsVersion by remember { mutableIntStateOf(0) }
    val overlay = settings.debugOverlay || !isGame

    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val info = rememberWindowLayoutInfo()
    val gamepadAttached = rememberGamepadConnected()
    val controller = when (settings.controllerMode) {
        ControllerMode.AUTO -> gamepadAttached
        ControllerMode.FORCE_ON -> true
        ControllerMode.FORCE_OFF -> false
    }

    // The menu pauses the game; closing it resumes.
    LaunchedEffect(menuOpen, running) { if (running) emu.setUserPaused(menuOpen) }
    BackHandler(enabled = isGame) { menuOpen = !menuOpen }
    LaunchedEffect(overlay, running) { while (overlay && running) { stats = emu.stats(); delay(500) } }
    LaunchedEffect(Unit) {
        emu.hotkeys.collect { key ->
            when (key) {
                Hotkey.MENU -> menuOpen = !menuOpen
                Hotkey.SWAP_SCREENS -> if (platform.isDualScreen) app.sessionSettings.update { it.copy(swapScreens = !it.swapScreens) }
                Hotkey.FAST_FORWARD -> Unit  // held: handled inside the controller
            }
        }
    }

    Box(
        Modifier.fillMaxSize().then(if (isGame) Modifier else Modifier.background(Color.Black))
            .onGloballyPositioned { origin = it.positionInWindow(); size = it.size },
    ) {
        if (isGame) GameSurface(emu, Modifier.fillMaxSize())
        if (size != IntSize.Zero) {
            val window = rememberWindowSpec(info, origin, size, source)
            val toolbarButtons = (if (isGame) 2 else 1) + (if (platform.isDualScreen) 2 else 0)
            val frame = frameSize.takeIf { running }
            val options = LayoutOptions(
                platform, settings.ndsBookMode, settings.ndsEmphasis, settings.swapScreens, settings.integerScaling, controller,
                frameWidth = frame?.first ?: 0, frameHeight = frame?.second ?: 0, toolbarButtons = toolbarButtons,
                flatClamshell = settings.flatClamshell,
            )
            val spec = remember(window, options) { LayoutEngine.compute(window, options) }
            val pad = remember(spec, platform) { VirtualPadBuilder.build(spec, platform, density) }
            val router = remember {
                TouchRouter(object : TouchRouter.Sink {
                    override fun pointer(sample: PointerSample) = emu.setPointer(sample)
                    override fun buttons(mask: Int, newlyPressed: Set<PadButton>) {
                        emu.setTouchButtons(mask)
                        padMask = mask
                        if (newlyPressed.isNotEmpty() && haptics) view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    }
                })
            }
            LaunchedEffect(spec) { emu.setViewports(spec.screens) }  // fold/rotate/option -> new GL quads
            LaunchedEffect(spec, pad) { router.setLayout(spec, pad) }
            LayoutCanvas(spec, options, overlay, drawScreens = !running, modifier = Modifier.fillMaxSize())
            if (isGame) {
                VirtualPadOverlay(
                    pad, padMask, settings.padOpacity,
                    Modifier.fillMaxSize().pointerInput(running) { if (running) forwardTouches(router) },
                )
            }
            if (overlay) {
                Hud(
                    spec, options, window, info.foldingFeature()?.toRaw(), source, gamepadAttached, romTitle, stats, hudExpanded,
                    onToggle = { hudExpanded = !hudExpanded },
                    modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                )
            }
            HotkeyBar(
                isGame = isGame, platform = platform, fastForward = fastForward, slot = spec.toolbar,
                onFastForward = { emu.setFastForwardToggle(!fastForward) },
                onSwap = { app.sessionSettings.update { it.copy(swapScreens = !it.swapScreens) } },
                onCycleLayout = {
                    app.sessionSettings.update { s ->
                        if (spec.posture == Posture.BOOK_FLAT || spec.posture == Posture.BOOK_HALF_OPENED) {
                            s.copy(ndsBookMode = if (s.ndsBookMode == NdsBookMode.SIDE_BY_SIDE) NdsBookMode.HYBRID else NdsBookMode.SIDE_BY_SIDE)
                        } else {
                            s.copy(ndsEmphasis = NdsEmphasis.entries[(s.ndsEmphasis.ordinal + 1) % NdsEmphasis.entries.size])
                        }
                    }
                },
                onMenu = { menuOpen = true },
            )
        }
        when (val st = emuState) {
            is EmulationController.State.Loading -> if (isGame) CircularProgressIndicator(Modifier.align(Alignment.Center))
            is EmulationController.State.Failed -> if (isGame) Text(
                "Could not start ${st.rom.title}:\n${st.message}",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp)
                    .background(Color(0xDD3A0000), RoundedCornerShape(8.dp)).padding(16.dp),
            )
            else -> Unit
        }
    }

    if (menuOpen) {
        ModalBottomSheet(
            onDismissRequest = { menuOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            QuickMenu(
                title = romTitle, isGame = isGame, platform = platform, onPlatform = { platform = it },
                settings = settings, onSettings = app.sessionSettings::update,
                fastForward = fastForward, onFastForward = emu::setFastForwardToggle,
                slots = remember(slotsVersion, running) { emu.slots() }, slotsVersion = slotsVersion,
                onSave = { slot ->
                    scope.launch {
                        val r = runCatching { emu.saveState(slot) }
                        slotsVersion++
                        Toast.makeText(context, r.fold({ "Saved to slot $slot" }, { "Save failed: ${it.message}" }), Toast.LENGTH_SHORT).show()
                    }
                },
                onLoad = { slot ->
                    scope.launch {
                        val r = runCatching { emu.loadState(slot) }
                        if (r.isSuccess) menuOpen = false
                        Toast.makeText(context, r.fold({ "Loaded slot $slot" }, { "Load failed: ${it.message}" }), Toast.LENGTH_SHORT).show()
                    }
                },
                onReset = { emu.reset(); menuOpen = false },
                onResume = { menuOpen = false },
                onExit = { menuOpen = false; onExit() },
                postureSource = source, onPostureSource = { source = it },
            )
        }
    }
}

/**
 * Small always-visible buttons: fast-forward, DS swap / layout, menu. Placed in the
 * slot the layout engine reserved (never over a screen, a control or the hinge);
 * a slot taller than wide becomes a column.
 */
@Composable
private fun HotkeyBar(
    isGame: Boolean, platform: Platform, fastForward: Boolean, slot: com.projectcit.layout.Rect?,
    onFastForward: () -> Unit, onSwap: () -> Unit, onCycleLayout: () -> Unit, onMenu: () -> Unit,
) {
    slot ?: return
    val d = LocalDensity.current.density
    val colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0x66000000), contentColor = Color.White)
    val size = Modifier.size(LayoutEngine.TOOLBAR_BUTTON_DP.dp)
    val buttons: List<@Composable () -> Unit> = buildList {
        if (isGame) add {
            FilledTonalIconButton(onClick = onFastForward, modifier = size,
                colors = if (fastForward) IconButtonDefaults.filledTonalIconButtonColors() else colors) { Text("⏩", fontSize = 16.sp) }
        }
        if (platform.isDualScreen) {
            add { FilledTonalIconButton(onClick = onSwap, modifier = size, colors = colors) { Text("⇅", fontSize = 18.sp) } }
            add { FilledTonalIconButton(onClick = onCycleLayout, modifier = size, colors = colors) { Text("▣", fontSize = 16.sp) } }
        }
        add { FilledTonalIconButton(onClick = onMenu, modifier = size, colors = colors) { Text("☰", fontSize = 18.sp) } }
    }
    val place = Modifier
        .offset { IntOffset(slot.left.toInt(), slot.top.toInt()) }
        .size((slot.width / d).dp, (slot.height / d).dp)
        .padding(4.dp)
    val gap = Arrangement.spacedBy(LayoutEngine.TOOLBAR_GAP_DP.dp, Alignment.End)
    if (slot.height > slot.width) {
        Column(place, verticalArrangement = Arrangement.spacedBy(LayoutEngine.TOOLBAR_GAP_DP.dp), horizontalAlignment = Alignment.CenterHorizontally) { buttons.forEach { it() } }
    } else {
        Row(place, horizontalArrangement = gap, verticalAlignment = Alignment.CenterVertically) { buttons.forEach { it() } }
    }
}

/** Forwards raw pointer events to the (unit-tested) TouchRouter. */
private suspend fun PointerInputScope.forwardTouches(router: TouchRouter) {
    try {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                for (c in event.changes) {
                    val id = c.id.value
                    when {
                        c.pressed && !c.previousPressed -> router.down(id, c.position.x, c.position.y)
                        c.pressed -> router.move(id, c.position.x, c.position.y)
                        c.previousPressed -> router.up(id)
                    }
                    c.consume()
                }
            }
        }
    } finally {
        router.releaseAll()  // gesture cancelled or screen left mid-touch: release everything
    }
}

@Composable
private fun Hud(
    spec: LayoutSpec, options: LayoutOptions, window: WindowSpec, raw: RawFold?, source: PostureSource, gamepad: Boolean,
    romTitle: String?, stats: RunnerStats?, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier,
) {
    val lines = buildList {
        add("${spec.posture}  ·  ${romTitle ?: "Layout lab"}")
        stats?.let { st ->
            add("%.2f/%.2f fps · audio %.0f fr · drc %.4f · %d Hz · underruns %d%s".format(
                st.measuredFps, st.targetFps, st.audioBufferedFrames, st.drcRatio, st.deviceSampleRate, st.audioUnderruns,
                if (st.paused) " · PAUSED" else ""))
        }
        if (expanded) {
            add("window %.0f×%.0f dp  w=%s h=%s".format(window.widthDp, window.heightDp, window.widthClass, window.heightClass))
            add(if (raw == null) "FoldingFeature: none reported" else "fold ${raw.state} ${raw.orientation} sep=${raw.isSeparating} occl=${raw.occlusion}")
            raw?.let { add("fold bounds(window px) ${it.boundsInWindow.toShortString()}") }
            add("source ${source.label}  ·  gamepad ${if (gamepad) "connected" else "none"}")
            add("frame %d×%d".format(options.sourceWidth, options.sourceHeight * options.platform.screenCount))
            spec.screens.forEach { s -> add("%s %s ×%.2f".format(s.role, s.rect, s.rect.width / options.sourceWidth)) }
        }
    }
    Column(
        modifier.widthIn(max = 520.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        lines.forEach { Text(it, color = Color(0xFFE0F7FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp) }
        if (!expanded) Text("tap for posture details", color = MaterialTheme.colorScheme.outline, fontSize = 10.sp)
    }
}
