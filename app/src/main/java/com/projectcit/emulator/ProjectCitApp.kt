package com.projectcit.emulator

import android.app.Application
import com.projectcit.storage.RomLibraryRepository

class ProjectCitApp : Application() {
    /** Process-scoped, so it outlives every Activity/configuration change. */
    val library: RomLibraryRepository by lazy { RomLibraryRepository(this) }
}
