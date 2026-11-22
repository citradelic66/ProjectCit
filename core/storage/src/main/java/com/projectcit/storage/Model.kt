package com.projectcit.storage

/**
 * libretro core shipped in jniLibs; resolved via ApplicationInfo.nativeLibraryDir.
 * Android's installer only extracts files named lib*.so, so the buildbot file
 * (e.g. mgba_libretro_android.so) is packaged as lib + that name.
 */
enum class CoreId(val libraryFileName: String, val displayName: String, val abis: Set<String>) {
    MGBA("mgba_libretro_android.so", "mGBA", setOf("arm64-v8a", "armeabi-v7a", "x86_64")),
    // melonDS DS publishes Android builds for arm64-v8a only.
    MELONDS_DS("melondsds_libretro_android.so", "melonDS DS", setOf("arm64-v8a"));

    val installedFileName: String get() = "lib$libraryFileName"
}

enum class ConsoleSystem(val extensions: Set<String>, val core: CoreId, val label: String) {
    GB(setOf("gb"), CoreId.MGBA, "Game Boy"),
    GBC(setOf("gbc"), CoreId.MGBA, "Game Boy Color"),
    GBA(setOf("gba"), CoreId.MGBA, "Game Boy Advance"),
    NDS(setOf("nds"), CoreId.MELONDS_DS, "Nintendo DS");

    companion object {
        fun forFileName(name: String): ConsoleSystem? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

/**
 * Optional system files. Every one is optional: melonDS DS falls back to its
 * built-in FreeBIOS/generated firmware, and mGBA has an HLE BIOS.
 */
enum class BiosKind(val fileName: String, val validSizes: Set<Long>, val forSystem: ConsoleSystem, val label: String) {
    NDS_ARM7("bios7.bin", setOf(16_384L), ConsoleSystem.NDS, "DS ARM7 BIOS"),
    NDS_ARM9("bios9.bin", setOf(4_096L), ConsoleSystem.NDS, "DS ARM9 BIOS"),
    NDS_FIRMWARE("firmware.bin", setOf(131_072L, 262_144L, 524_288L), ConsoleSystem.NDS, "DS firmware"),
    GBA_BIOS("gba_bios.bin", setOf(16_384L), ConsoleSystem.GBA, "GBA BIOS");

    companion object {
        fun forFileName(name: String): BiosKind? = entries.firstOrNull { it.fileName.equals(name, ignoreCase = true) }
    }
}

/** One row from a SAF directory listing. Plain data so classification is JVM-testable. */
data class DocumentEntry(
    val documentId: String,
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    /** Folder path inside the picked tree, e.g. "roms/nds" ("" for the root). */
    val parentPath: String,
)

data class RomEntry(
    val uri: String,
    val name: String,
    val size: Long,
    val lastModified: Long,
    val system: ConsoleSystem,
    val parentPath: String,
) {
    val title: String get() = name.substringBeforeLast('.')
}

data class BiosEntry(
    val kind: BiosKind,
    val uri: String,
    val size: Long,
    val lastModified: Long,
    val parentPath: String,
) {
    val sizeValid: Boolean get() = size in kind.validSizes
}

/** A battery save sitting next to a ROM in the user's folder (from another emulator or a flashcart). */
data class ExternalSave(val uri: String, val name: String, val size: Long, val lastModified: Long)

data class LibraryIndex(
    val roms: List<RomEntry>,
    val bios: Map<BiosKind, BiosEntry>,
    val scannedAtMillis: Long,
    /** Keyed by [saveKey] of the ROM it belongs to. */
    val saves: Map<String, ExternalSave> = emptyMap(),
) {
    fun externalSaveFor(rom: RomEntry): ExternalSave? = saves[saveKey(rom.parentPath, rom.title)]

    companion object {
        val EMPTY = LibraryIndex(emptyList(), emptyMap(), 0L)
        val SAVE_EXTENSIONS = setOf("sav", "srm")
        fun saveKey(parentPath: String, title: String) = "$parentPath/$title".lowercase()
    }
}
