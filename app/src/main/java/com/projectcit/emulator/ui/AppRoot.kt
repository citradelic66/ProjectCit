package com.projectcit.emulator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.projectcit.emulator.ui.library.LibraryScreen
import com.projectcit.emulator.ui.session.SessionScreen
import com.projectcit.layout.Platform
import com.projectcit.storage.ConsoleSystem

/** Minimal two-destination navigation; Phase 4 may swap in navigation-compose. */
@Composable
fun AppRoot() {
    // Saved as primitives so it survives process death as well as config changes.
    var sessionPlatform by rememberSaveable { mutableStateOf<String?>(null) }
    var sessionTitle by rememberSaveable { mutableStateOf<String?>(null) }

    val platform = sessionPlatform?.let(Platform::valueOf)
    if (platform == null) {
        LibraryScreen(
            onOpenRom = { rom ->
                sessionPlatform = rom.system.toLayoutPlatform().name
                sessionTitle = rom.title
            },
            onOpenLayoutLab = {
                sessionPlatform = Platform.NDS.name
                sessionTitle = null
            },
        )
    } else {
        BackHandler { sessionPlatform = null }
        SessionScreen(initialPlatform = platform, romTitle = sessionTitle, onExit = { sessionPlatform = null })
    }
}

fun ConsoleSystem.toLayoutPlatform(): Platform = when (this) {
    ConsoleSystem.GB -> Platform.GB
    ConsoleSystem.GBC -> Platform.GBC
    ConsoleSystem.GBA -> Platform.GBA
    ConsoleSystem.NDS -> Platform.NDS
}
