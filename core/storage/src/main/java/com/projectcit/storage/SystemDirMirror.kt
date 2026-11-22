package com.projectcit.storage

import android.content.ContentResolver
import android.net.Uri
import java.io.File

/**
 * libretro's RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY must return a real filesystem
 * path, and SAF only gives content:// URIs. So the user's BIOS files are mirrored
 * into app-private storage, which becomes the cores' system/ directory.
 *
 * Files removed from the SAF folder are removed here too, so the core falls back
 * to FreeBIOS / HLE instead of silently using a stale dump.
 */
class SystemDirMirror(private val resolver: ContentResolver, val systemDir: File) {

    data class Result(val copied: List<BiosKind>, val unchanged: List<BiosKind>, val removed: List<String>)

    fun sync(bios: Map<BiosKind, BiosEntry>): Result {
        systemDir.mkdirs()
        val copied = mutableListOf<BiosKind>()
        val unchanged = mutableListOf<BiosKind>()
        val wanted = bios.filterValues { it.sizeValid }
        for ((kind, entry) in wanted) {
            val target = File(systemDir, kind.fileName)
            val stamp = File(systemDir, ".${kind.fileName}.src")
            val signature = "${entry.uri}|${entry.size}|${entry.lastModified}"
            if (target.length() == entry.size && stamp.exists() && stamp.readText() == signature) {
                unchanged += kind
                continue
            }
            val tmp = File(systemDir, "${kind.fileName}.tmp")
            resolver.openInputStream(Uri.parse(entry.uri))?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: continue
            if (!tmp.renameTo(target)) { tmp.delete(); continue }
            stamp.writeText(signature)
            copied += kind
        }
        val removed = mutableListOf<String>()
        for (kind in BiosKind.entries) {
            if (kind in wanted) continue
            val f = File(systemDir, kind.fileName)
            if (f.exists() && f.delete()) removed += kind.fileName
            File(systemDir, ".${kind.fileName}.src").delete()
        }
        return Result(copied, unchanged, removed)
    }
}
