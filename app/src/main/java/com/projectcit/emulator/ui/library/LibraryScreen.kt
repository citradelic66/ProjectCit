package com.projectcit.emulator.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.projectcit.emulator.ProjectCitApp
import com.projectcit.libretro.NativeBridge
import com.projectcit.storage.BiosKind
import com.projectcit.storage.ConsoleSystem
import com.projectcit.storage.LibraryState
import com.projectcit.storage.RomEntry

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(onOpenRom: (RomEntry) -> Unit, onOpenLayoutLab: () -> Unit) {
    val app = LocalContext.current.applicationContext as ProjectCitApp
    val vm: LibraryViewModel = viewModel { LibraryViewModel(app.library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onFolderPicked(uri)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ProjectCit") },
                actions = {
                    IconButton(onClick = onOpenLayoutLab) { Icon(Icons.Default.Build, "Layout lab") }
                    IconButton(onClick = vm::rescan, enabled = state.rootUri != null && !state.scanning) {
                        Icon(Icons.Default.Refresh, "Rescan")
                    }
                },
            )
        },
    ) { padding ->
        // Centre a readable column on the unfolded inner screen instead of stretching rows edge-to-edge.
        Column(Modifier.padding(padding).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.scanning) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { FolderCard(state, onPick = { picker.launch(state.rootUri) }) }
                item { BiosCard(state) }
                item {
                    Text(NativeBridge.hostInfo(), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ConsoleSystem.entries.forEach { system ->
                    val roms = state.index.roms.filter { it.system == system }
                    if (roms.isNotEmpty()) {
                        item(key = "h-$system") {
                            Text("${system.label} · ${roms.size}", style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(top = 8.dp))
                        }
                        items(roms, key = { it.uri }) { rom -> RomRow(rom, onClick = { onOpenRom(rom) }) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderCard(state: LibraryState, onPick: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.rootUri == null -> {
                    Text("Choose your games folder", style = MaterialTheme.typography.titleMedium)
                    Text("Pick a folder holding .gba, .gbc, .gb and .nds files. Put optional BIOS files " +
                        "(bios7.bin, bios9.bin, firmware.bin, gba_bios.bin) in a subfolder named system.")
                    Button(onClick = onPick) { Text("Choose folder") }
                }
                else -> {
                    Text("Games folder", style = MaterialTheme.typography.labelMedium)
                    Text(state.rootLabel ?: "", style = MaterialTheme.typography.titleMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("${state.index.roms.size} games indexed")
                    if (state.permissionLost) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.size(8.dp))
                            Text("Access to this folder was lost. Choose it again.", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    OutlinedButton(onClick = onPick) { Text(if (state.permissionLost) "Choose folder again" else "Change folder") }
                }
            }
        }
    }
}

@Composable
private fun BiosCard(state: LibraryState) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("System files (all optional)", style = MaterialTheme.typography.titleSmall)
            BiosKind.entries.forEach { kind ->
                val entry = state.index.bios[kind]
                val (icon, tint, status) = when {
                    entry == null -> Triple(Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant, fallbackText(kind))
                    !entry.sizeValid -> Triple(Icons.Default.Warning, MaterialTheme.colorScheme.error,
                        "wrong size (${entry.size} bytes), ignored → ${fallbackText(kind)}")
                    kind in state.mirroredBios -> Triple(Icons.Default.CheckCircle, Color(0xFF6BD58B),
                        "found in /${entry.parentPath.ifEmpty { "" }}")
                    else -> Triple(Icons.Default.Info, MaterialTheme.colorScheme.onSurfaceVariant, "found, not copied yet")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text("${kind.fileName} · ${kind.label}", fontWeight = FontWeight.Medium,
                            style = MaterialTheme.typography.bodyMedium)
                        Text(status, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private fun fallbackText(kind: BiosKind) =
    if (kind.forSystem == ConsoleSystem.NDS) "not found → built-in FreeBIOS" else "not found → built-in HLE BIOS"

@Composable
private fun RomRow(rom: RomEntry, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(rom.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("%.1f MB · /%s".format(rom.size / 1_048_576.0, rom.parentPath),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.size(8.dp))
            AssistChip(onClick = onClick, label = { Text(rom.system.name) })
        }
    }
    HorizontalDivider(color = Color.Transparent)
}
