package com.projectcit.emulator.ui.session

import android.graphics.BitmapFactory
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.projectcit.emulator.emulation.StateSlots
import com.projectcit.emulator.posture.PostureSource
import com.projectcit.emulator.settings.ControllerMode
import com.projectcit.emulator.settings.SessionSettings
import com.projectcit.layout.NdsBookMode
import com.projectcit.layout.NdsEmphasis
import com.projectcit.layout.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** In-game menu: play controls, save-state slots with thumbnails, layout and input options. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickMenu(
    title: String?,
    isGame: Boolean,
    platform: Platform,
    onPlatform: (Platform) -> Unit,
    settings: SessionSettings.Values,
    onSettings: ((SessionSettings.Values) -> SessionSettings.Values) -> Unit,
    fastForward: Boolean,
    onFastForward: (Boolean) -> Unit,
    slots: List<StateSlots.Slot?>,
    slotsVersion: Int,
    onSave: (Int) -> Unit,
    onLoad: (Int) -> Unit,
    onReset: () -> Unit,
    onResume: () -> Unit,
    onExit: () -> Unit,
    postureSource: PostureSource,
    onPostureSource: (PostureSource) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title ?: "Layout lab", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onResume) { Text(if (isGame) "Resume" else "Close") }
            if (isGame) {
                OutlinedButton(onClick = onReset) { Text("Reset") }
                OutlinedButton(onClick = onExit) { Text("Exit game") }
            }
        }
        if (isGame) {
            SwitchRow("Fast-forward (×3)", fastForward, onFastForward)
            Text("Save states", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                slots.forEachIndexed { i, slot -> SlotCard(i, slot, slotsVersion, onSave, onLoad) }
            }
        }

        @Composable
        fun <T> group(name: String, values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
            Text(name, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                values.forEach { v -> FilterChip(selected = v == selected, onClick = { onSelect(v) }, label = { Text(label(v)) }) }
            }
        }
        if (!isGame) group("Platform", Platform.entries, platform, { it.name }, onPlatform)
        if (platform.isDualScreen) {
            group("DS layout on an unfolded Fold", NdsBookMode.entries, settings.ndsBookMode,
                { if (it == NdsBookMode.SIDE_BY_SIDE) "Side-by-side" else "Hybrid" }) { v -> onSettings { it.copy(ndsBookMode = v) } }
            group("DS screen size (single pane)", NdsEmphasis.entries, settings.ndsEmphasis,
                { when (it) { NdsEmphasis.BALANCED -> "Equal"; NdsEmphasis.TOP_LARGE -> "Top large"; NdsEmphasis.BOTTOM_LARGE -> "Touch large" } }) { v -> onSettings { it.copy(ndsEmphasis = v) } }
            SwitchRow("Swap screens", settings.swapScreens) { v -> onSettings { it.copy(swapScreens = v) } }
        }
        SwitchRow("Flex layout when the Fold is open flat (upright: DS only)", settings.flatClamshell) { v -> onSettings { it.copy(flatClamshell = v) } }
        SwitchRow("Pixel-perfect (integer) scaling", settings.integerScaling) { v -> onSettings { it.copy(integerScaling = v) } }
        SwitchRow("Vibrate on button press", settings.haptics) { v -> onSettings { it.copy(haptics = v) } }
        group("On-screen controls", listOf(0.3f, 0.55f, 0.85f), settings.padOpacity,
            { when (it) { 0.3f -> "Faint"; 0.55f -> "Normal"; else -> "Solid" } }) { v -> onSettings { it.copy(padOpacity = v) } }
        group("Physical controller", ControllerMode.entries, settings.controllerMode,
            { when (it) { ControllerMode.AUTO -> "Auto-detect"; ControllerMode.FORCE_ON -> "Hide touch controls"; ControllerMode.FORCE_OFF -> "Always show touch controls" } }) { v -> onSettings { it.copy(controllerMode = v) } }
        SwitchRow("Debug overlay (fps, audio, hinge)", settings.debugOverlay) { v -> onSettings { it.copy(debugOverlay = v) } }
        if (settings.debugOverlay || !isGame) group("Posture source", PostureSource.entries, postureSource, { it.label }, onPostureSource)
        Text(" ")
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SlotCard(index: Int, slot: StateSlots.Slot?, version: Int, onSave: (Int) -> Unit, onLoad: (Int) -> Unit) {
    val thumb by produceState<ImageBitmap?>(null, slot?.thumbnail, slot?.savedAtMillis, version) {
        value = slot?.thumbnail?.let { f -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.path)?.asImageBitmap() } }
    }
    Card(Modifier.width(156.dp)) {
        Column(Modifier.padding(8.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(4f / 3f).background(Color.Black), contentAlignment = Alignment.Center) {
                val t = thumb
                if (t != null) Image(t, null, Modifier.fillMaxWidth(), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None)
                else Text(if (slot == null) "Empty" else "No preview", color = Color.Gray, textAlign = TextAlign.Center)
            }
            Text(
                (if (index == StateSlots.AUTO_SLOT) "Auto" else "Slot $index") +
                    (slot?.let { " · " + DateUtils.getRelativeTimeSpanString(it.savedAtMillis) } ?: ""),
                style = MaterialTheme.typography.labelMedium, maxLines = 1,
            )
            Row {
                if (index != StateSlots.AUTO_SLOT) TextButton(onClick = { onSave(index) }) { Text("Save") }
                TextButton(onClick = { onLoad(index) }, enabled = slot != null) { Text("Load") }
            }
        }
    }
}
