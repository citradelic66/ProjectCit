package com.projectcit.storage

/** libretro core shipped in jniLibs; resolved via ApplicationInfo.nativeLibraryDir. */
enum class CoreId(val libraryFileName: String, val displayName: String) {
    MGBA("mgba_libretro_android.so", "mGBA"),
    MELONDS_DS("melondsds_libretro_android.so", "melonDS DS"),
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

data class LibraryIndex(
    val roms: List<RomEntry>,
    val bios: Map<BiosKind, BiosEntry>,
    val scannedAtMillis: Long,
) {
    companion object { val EMPTY = LibraryIndex(emptyList(), emptyMap(), 0L) }
}
