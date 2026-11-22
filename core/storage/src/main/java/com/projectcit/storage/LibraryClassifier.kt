package com.projectcit.storage

object LibraryClassifier {
    private val systemDirNames = setOf("system", "bios")

    fun classify(entries: Sequence<DocumentEntry>, now: Long): LibraryIndex {
        val roms = ArrayList<RomEntry>()
        val biosCandidates = HashMap<BiosKind, MutableList<BiosEntry>>()
        val saves = HashMap<String, ExternalSave>()
        for (e in entries) {
            if (e.isDirectory || e.name.startsWith(".")) continue
            BiosKind.forFileName(e.name)?.let { kind ->
                biosCandidates.getOrPut(kind) { mutableListOf() } +=
                    BiosEntry(kind, e.uri, e.size, e.lastModified, e.parentPath)
                return@let
            }
            val ext = e.name.substringAfterLast('.', "").lowercase()
            if (ext in LibraryIndex.SAVE_EXTENSIONS && e.size > 0) {
                val key = LibraryIndex.saveKey(e.parentPath, e.name.substringBeforeLast('.'))
                // Prefer .srm over .sav when both exist (libretro format wins).
                if (ext == "srm" || key !in saves) saves[key] = ExternalSave(e.uri, e.name, e.size, e.lastModified)
                continue
            }
            val system = ConsoleSystem.forFileName(e.name) ?: continue
            if (e.size <= 0L) continue
            roms += RomEntry(e.uri, e.name, e.size, e.lastModified, system, e.parentPath)
        }
        val bios = biosCandidates.mapValues { (_, list) -> list.maxWith(biosPreference) }
        roms.sortWith(compareBy<RomEntry> { it.system.ordinal }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        return LibraryIndex(roms, bios, now, saves)
    }

    /** Prefer a correctly sized dump, then one inside system/ or bios/, then shallowest. */
    private val biosPreference = compareBy<BiosEntry> { it.sizeValid }
        .thenBy { inSystemDir(it.parentPath) }
        .thenByDescending { it.parentPath.count { c -> c == '/' } }

    private fun inSystemDir(path: String): Boolean =
        path.split('/').any { it.lowercase() in systemDirNames }
}
