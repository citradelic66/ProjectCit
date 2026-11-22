package com.projectcit.emulator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.projectcit.emulator.ProjectCitApp
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
    var sessionRomUri by rememberSaveable { mutableStateOf<String?>(null) }

    val app = LocalContext.current.applicationContext as ProjectCitApp
    val library by app.library.state.collectAsStateWithLifecycle()
    // Single start path for taps *and* process-death restore: if the UI says a game
    // is open but the controller isn't running it, start it.
    LaunchedEffect(sessionRomUri, library.index) {
        val uri = sessionRomUri ?: return@LaunchedEffect
        if (app.emulation.currentRomUri == uri) return@LaunchedEffect
        library.index.roms.firstOrNull { it.uri == uri }?.let { app.emulation.start(it, library.index) }
    }
    val exit = {
        app.emulation.stop()
        sessionPlatform = null
        sessionRomUri = null
    }

    val platform = sessionPlatform?.let(Platform::valueOf)
    if (platform == null) {
        LibraryScreen(
            onOpenRom = { rom ->
                sessionPlatform = rom.system.toLayoutPlatform().name
                sessionTitle = rom.title
                sessionRomUri = rom.uri
            },
            onOpenLayoutLab = {
                sessionPlatform = Platform.NDS.name
                sessionTitle = null
                sessionRomUri = null
            },
        )
    } else {
        BackHandler { exit() }
        SessionScreen(initialPlatform = platform, romTitle = sessionTitle, isGame = sessionRomUri != null, onExit = exit)
    }
}

fun ConsoleSystem.toLayoutPlatform(): Platform = when (this) {
    ConsoleSystem.GB -> Platform.GB
    ConsoleSystem.GBC -> Platform.GBC
    ConsoleSystem.GBA -> Platform.GBA
    ConsoleSystem.NDS -> Platform.NDS
}
