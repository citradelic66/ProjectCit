package com.projectcit.emulator.emulation

import android.content.Context
import com.projectcit.storage.CoreId
import java.io.File

/** Where each bundled core lives, and the option overrides the app depends on. */
object CoreRegistry {

    fun coreFile(context: Context, core: CoreId): File =
        File(context.applicationInfo.nativeLibraryDir, core.installedFileName)

    fun isInstalled(context: Context, core: CoreId): Boolean = coreFile(context, core).exists()

    /**
     * Options forced at load. melonDS DS must emit one stacked 256×384 frame
     * (top screen = upper half) because our renderer UV-slices it and PointerMapper
     * assumes it. Keys/values verified against melonDS DS 1.4.0
     * (src/libretro/config/constants.hpp).
     */
    fun overrides(core: CoreId): Map<String, String> = when (core) {
        CoreId.MELONDS_DS -> mapOf(
            "melonds_number_of_screen_layouts" to "1",
            "melonds_screen_layout1" to "top-bottom",
            "melonds_screen_gap" to "0",
            "melonds_touch_mode" to "touch",        // RETRO_DEVICE_POINTER, not joystick cursor
            "melonds_show_cursor" to "disabled",    // the real stylus/finger is the cursor
            "melonds_render_mode" to "software",    // Android build ships without OpenGL
        )
        CoreId.MGBA -> emptyMap()
    }
}
