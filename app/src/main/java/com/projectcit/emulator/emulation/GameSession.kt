package com.projectcit.emulator.emulation

import android.content.Context
import android.net.Uri
import com.projectcit.libretro.ContentGame
import com.projectcit.libretro.LibretroCore
import com.projectcit.libretro.LibretroException
import com.projectcit.storage.CoreId
import com.projectcit.storage.LibraryIndex
import com.projectcit.storage.RomEntry
import java.io.Closeable
import java.io.File

/**
 * Core + game + persistence for one play session.
 * Create and use it only on the emulation thread (Phase 3 owns that thread).
 */
class GameSession private constructor(
    val rom: RomEntry,
    val coreId: CoreId,
    val core: LibretroCore,
    private val content: ContentGame,
    val slots: StateSlots,
    val importedSave: Boolean,
) : Closeable {

    val contentStrategy: ContentGame.Strategy get() = content.strategy

    /** Writes the state and its PNG thumbnail (taken from the last displayed frame). */
    fun saveState(slot: Int) {
        core.saveState(slots.stateFile(slot))
        core.copyFrameArgb()?.let { slots.writeThumbnail(slot, it) }
    }

    fun loadState(slot: Int) {
        val f = slots.stateFile(slot)
        if (!f.exists()) throw LibretroException("slot $slot is empty")
        core.loadState(f)
    }

    /** Flushes the battery save, unloads and releases the ROM descriptor. */
    override fun close() {
        try { core.close() } finally { content.close() }
    }

    companion object {
        fun launch(context: Context, rom: RomEntry, index: LibraryIndex, systemDir: File): GameSession {
            val coreId = rom.system.core
            val coreFile = CoreRegistry.coreFile(context, coreId)
            if (!coreFile.exists()) {
                throw LibretroException("${coreId.displayName} is not installed in this build (${coreId.installedFileName})")
            }
            val saveDir = File(context.filesDir, "saves/${coreId.name.lowercase()}")
            val stateDir = File(context.filesDir, "states/${coreId.name.lowercase()}")
            val saveName = rom.title
            val imported = importExternalSave(context, rom, index, saveDir, saveName)

            val core = LibretroCore.open(coreFile.path, systemDir, saveDir, CoreRegistry.overrides(coreId))
            try {
                val content = ContentGame.open(context, Uri.parse(rom.uri), rom.name, saveName, core.systemInfo.needFullPath)
                try {
                    core.loadGame(content.path, content.fd, saveName)
                } catch (e: Exception) {
                    content.close(); throw e
                }
                return GameSession(rom, coreId, core, content, StateSlots(stateDir, saveName), imported)
            } catch (e: Exception) {
                core.close(); throw e
            }
        }

        /**
         * First launch only: copy a .sav/.srm found next to the ROM into the app's
         * save directory so progress from another emulator carries over. Never
         * overwrites an existing in-app save.
         */
        private fun importExternalSave(context: Context, rom: RomEntry, index: LibraryIndex, saveDir: File, saveName: String): Boolean {
            val external = index.externalSaveFor(rom) ?: return false
            saveDir.mkdirs()
            val target = File(saveDir, "$saveName.srm")
            if (target.exists() || File(saveDir, "$saveName.sav").exists()) return false
            return runCatching {
                val tmp = File(saveDir, "$saveName.srm.tmp")
                context.contentResolver.openInputStream(Uri.parse(external.uri))!!.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
                tmp.renameTo(target)
            }.getOrDefault(false)
        }
    }
}
