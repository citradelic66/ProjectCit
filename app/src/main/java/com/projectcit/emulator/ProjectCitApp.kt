package com.projectcit.emulator

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.projectcit.emulator.emulation.EmulationController
import com.projectcit.emulator.settings.SessionSettings
import com.projectcit.storage.RomLibraryRepository

class ProjectCitApp : Application() {
    /** Process-scoped, so it outlives every Activity/configuration change. */
    val library: RomLibraryRepository by lazy { RomLibraryRepository(this) }

    /** The running game. Process-scoped for the same reason: folding must never reset it. */
    val emulation: EmulationController by lazy { EmulationController(this, library.systemDir) }

    val sessionSettings: SessionSettings by lazy { SessionSettings(this) }

    override fun onCreate() {
        super.onCreate()
        ProcessLifecycleOwner.get().lifecycle.addObserver(emulation)
    }
}
