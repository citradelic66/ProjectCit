package com.projectcit.emulator.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projectcit.emulator.ProjectCitApp
import com.projectcit.emulator.emulation.EmulationController
import com.projectcit.emulator.posture.PostureSource
import com.projectcit.layout.PointerMapper
import com.projectcit.layout.PointerSample
import com.projectcit.libretro.RunnerStats
import kotlinx.coroutines.delay
import com.projectcit.emulator.posture.RawFold
import com.projectcit.emulator.posture.foldingFeature
import com.projectcit.emulator.posture.rememberGamepadConnected
import com.projectcit.emulator.posture.rememberWindowLayoutInfo
import com.projectcit.emulator.posture.rememberWindowSpec
import com.projectcit.emulator.posture.toRaw
import com.projectcit.layout.LayoutEngine
import com.projectcit.layout.LayoutOptions
import com.projectcit.layout.LayoutSpec
import com.projectcit.layout.NdsBookMode
import com.projectcit.layout.NdsEmphasis
import com.projectcit.layout.Platform
import com.projectcit.layout.WindowSpec

enum class ControllerMode { AUTO, FORCE_ON, FORCE_OFF }

/**
 * Phase 1 session shell: runs the real layout engine against live WindowManager
 * data and draws placeholder quads where the GL renderer (Phase 3) will draw.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(initialPlatform: Platform, romTitle: String?, isGame: Boolean, onExit: () -> Unit) {
    val app = LocalContext.current.applicationContext as ProjectCitApp
    val emu = app.emulation
    val emuState by emu.state.collectAsStateWithLifecycle()
    val running = isGame && emuState is EmulationController.State.Running
    var stats by remember { mutableStateOf<RunnerStats?>(null) }
    var platform by rememberSaveable { mutableStateOf(initialPlatform) }
    var bookMode by rememberSaveable { mutableStateOf(NdsBookMode.SIDE_BY_SIDE) }
    var emphasis by rememberSaveable { mutableStateOf(NdsEmphasis.BALANCED) }
    var swap by rememberSaveable { mutableStateOf(false) }
    var integer by rememberSaveable { mutableStateOf(false) }
    var controllerMode by rememberSaveable { mutableStateOf(ControllerMode.AUTO) }
    var source by rememberSaveable { mutableStateOf(PostureSource.REAL) }
    var overlay by rememberSaveable { mutableStateOf(true) }
    var hudExpanded by rememberSaveable { mutableStateOf(true) }
    var showSheet by remember { mutableStateOf(false) }

    LaunchedEffect(overlay, running) {
        while (overlay && running) { stats = emu.stats(); delay(500) }
    }

    var origin by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val info = rememberWindowLayoutInfo()
    val gamepadAttached = rememberGamepadConnected()
    val controller = when (controllerMode) {
        ControllerMode.AUTO -> gamepadAttached
        ControllerMode.FORCE_ON -> true
        ControllerMode.FORCE_OFF -> false
    }

    Box(
        Modifier.fillMaxSize().then(if (isGame) Modifier else Modifier.background(Color.Black))
            .onGloballyPositioned { origin = it.positionInWindow(); size = it.size },
    ) {
        if (isGame) GameSurface(emu, Modifier.fillMaxSize())
        if (size != IntSize.Zero) {
            val window = rememberWindowSpec(info, origin, size, source)
            val options = LayoutOptions(platform, bookMode, emphasis, swap, integer, controller)
            val spec = remember(window, options) { LayoutEngine.compute(window, options) }
            // Every layout change (fold, rotate, option) re-targets the GL quads.
            LaunchedEffect(spec) { emu.setViewports(spec.screens) }
            LayoutCanvas(
                spec, platform, overlay, drawScreens = !running,
                modifier = Modifier.fillMaxSize().pointerInput(spec, running) { if (running) touchToPointer(spec, emu) },
            )
            if (overlay) {
                Hud(
                    spec, platform, window, info.foldingFeature()?.toRaw(), source, gamepadAttached, romTitle, stats, hudExpanded,
                    onToggle = { hudExpanded = !hudExpanded },
                    modifier = Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
                )
            }
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
        Row(
            Modifier.align(Alignment.TopEnd).windowInsetsPadding(WindowInsets.safeDrawing).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilledTonalIconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            FilledTonalIconButton(onClick = { showSheet = true }) { Icon(Icons.Default.Settings, "Layout options") }
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = { showSheet = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            OptionsSheet(
                platform, { platform = it }, bookMode, { bookMode = it }, emphasis, { emphasis = it },
                swap, { swap = it }, integer, { integer = it }, controllerMode, { controllerMode = it },
                source, { source = it }, overlay, { overlay = it },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsSheet(
    platform: Platform, setPlatform: (Platform) -> Unit,
    bookMode: NdsBookMode, setBookMode: (NdsBookMode) -> Unit,
    emphasis: NdsEmphasis, setEmphasis: (NdsEmphasis) -> Unit,
    swap: Boolean, setSwap: (Boolean) -> Unit,
    integer: Boolean, setInteger: (Boolean) -> Unit,
    controller: ControllerMode, setController: (ControllerMode) -> Unit,
    source: PostureSource, setSource: (PostureSource) -> Unit,
    overlay: Boolean, setOverlay: (Boolean) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        @Composable
        fun <T> group(title: String, values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { v -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label(v)) }) }
            }
        }
        group("Platform", Platform.entries, platform, { it.name }, setPlatform)
        group("Posture source (hinge visualizer)", PostureSource.entries, source, { it.label }, setSource)
        if (platform.isDualScreen) {
            group("NDS book layout (vertical hinge)", NdsBookMode.entries, bookMode,
                { if (it == NdsBookMode.SIDE_BY_SIDE) "Side-by-side" else "Hybrid" }, setBookMode)
            group("NDS emphasis (single pane)", NdsEmphasis.entries, emphasis,
                { when (it) { NdsEmphasis.BALANCED -> "Balanced"; NdsEmphasis.TOP_LARGE -> "Top large"; NdsEmphasis.BOTTOM_LARGE -> "Touch large" } },
                setEmphasis)
            group("Swap screens", listOf(false, true), swap, { if (it) "Swapped" else "Normal" }, setSwap)
        }
        group("Scaling", listOf(false, true), integer, { if (it) "Integer (pixel-perfect)" else "Fit" }, setInteger)
        group("Physical controller", ControllerMode.entries, controller,
            { when (it) { ControllerMode.AUTO -> "Auto-detect"; ControllerMode.FORCE_ON -> "Pretend connected"; ControllerMode.FORCE_OFF -> "Ignore" } },
            setController)
        group("Debug overlay", listOf(true, false), overlay, { if (it) "On" else "Off" }, setOverlay)
        Text(" ")
    }
}

@Composable
private fun Hud(
    spec: LayoutSpec, platform: Platform, window: WindowSpec, raw: RawFold?, source: PostureSource, gamepad: Boolean,
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
            add(
                if (raw == null) "FoldingFeature: none reported"
                else "fold ${raw.state} ${raw.orientation} sep=${raw.isSeparating} occl=${raw.occlusion}"
            )
            raw?.let { add("fold bounds(window px) ${it.boundsInWindow.toShortString()}") }
            add("source ${source.label}  ·  gamepad ${if (gamepad) "connected" else "none"}")
            spec.screens.forEach { s ->
                add("%s %s ×%.2f".format(s.role, s.rect, s.rect.width / platform.screenWidth))
            }
        }
    }
    Column(
        modifier.widthIn(max = 520.dp)
            .background(Color(0xCC000000), RoundedCornerShape(8.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        lines.forEach { Text(it, color = Color(0xFFE0F7FF), fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 13.sp) }
    }
}

/**
 * Stylus/finger on the DS touchscreen quad -> RETRO_DEVICE_POINTER. A touch that
 * starts on the touchscreen keeps tracking (clamped to the edge) if it slides off,
 * like a real stylus on the bezel; touches starting elsewhere are left for the
 * Phase 4 controls.
 */
private suspend fun PointerInputScope.touchToPointer(spec: LayoutSpec, emu: EmulationController) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val first = PointerMapper.map(down.position.x, down.position.y, spec) ?: return@awaitEachGesture
        emu.setPointer(first)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break
            PointerMapper.map(change.position.x, change.position.y, spec, clampToEdge = true)?.let(emu::setPointer)
            change.consume()
        }
        emu.setPointer(PointerSample.RELEASED)
    }
}
