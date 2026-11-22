package com.projectcit.emulator.emulation

import android.graphics.Bitmap
import com.projectcit.libretro.ArgbFrame
import java.io.File

/** Save-state slot files: `<dir>/<saveName>.s<N>.state` plus a `.png` thumbnail. */
class StateSlots(private val dir: File, private val saveName: String) {

    data class Slot(val index: Int, val state: File, val thumbnail: File?, val savedAtMillis: Long)

    fun stateFile(slot: Int): File = File(dir, "$saveName.s$slot.state")
    fun thumbnailFile(slot: Int): File = File(dir, "$saveName.s$slot.png")

    fun list(count: Int = SLOT_COUNT): List<Slot?> = (0 until count).map { i ->
        stateFile(i).takeIf { it.exists() }?.let { f ->
            Slot(i, f, thumbnailFile(i).takeIf { it.exists() }, f.lastModified())
        }
    }

    fun writeThumbnail(slot: Int, frame: ArgbFrame) {
        dir.mkdirs()
        val bmp = Bitmap.createBitmap(frame.pixels, frame.width, frame.height, Bitmap.Config.ARGB_8888)
        val tmp = File(dir, thumbnailFile(slot).name + ".tmp")
        tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        tmp.renameTo(thumbnailFile(slot))
    }

    companion object {
        const val SLOT_COUNT = 10
        /** Slot reserved for the automatic state written when a session is suspended. */
        const val AUTO_SLOT = 0
    }
}
