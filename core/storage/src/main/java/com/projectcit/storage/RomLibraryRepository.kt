package com.projectcit.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class LibraryState(
    val rootUri: Uri? = null,
    val rootLabel: String? = null,
    /** Persisted grant vanished (user revoked it, or the folder was deleted). */
    val permissionLost: Boolean = false,
    val scanning: Boolean = false,
    val index: LibraryIndex = LibraryIndex.EMPTY,
    val mirroredBios: Set<BiosKind> = emptySet(),
    val error: String? = null,
)

/**
 * Single source of truth for the ROM library. Owns the persisted SAF tree grant,
 * the cached index (instant cold start) and the system/ BIOS mirror.
 */
class RomLibraryRepository(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val prefs = app.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val cacheFile = File(app.filesDir, "library-index.json")
    val systemDir: File = File(app.filesDir, "system")
    private val mirror = SystemDirMirror(resolver, systemDir)
    private val scanLock = Mutex()

    private val _state = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    init {
        val root = prefs.getString(KEY_TREE, null)?.let(Uri::parse)
        val granted = root != null && resolver.persistedUriPermissions.any { it.uri == root && it.isReadPermission }
        _state.value = LibraryState(
            rootUri = root,
            rootLabel = root?.let(::labelFor),
            permissionLost = root != null && !granted,
            index = runCatching { readCache() }.getOrDefault(LibraryIndex.EMPTY),
            mirroredBios = BiosKind.entries.filter { File(systemDir, it.fileName).exists() }.toSet(),
        )
    }

    /** Call with the Uri returned by ACTION_OPEN_DOCUMENT_TREE. */
    suspend fun setRoot(tree: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        // Release the previous grant: apps are capped at 128–512 persisted grants.
        _state.value.rootUri?.takeIf { it != tree }?.let { old ->
            runCatching { resolver.releasePersistableUriPermission(old, flags) }
        }
        runCatching { resolver.takePersistableUriPermission(tree, flags) }
            .recoverCatching { resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.edit().putString(KEY_TREE, tree.toString()).apply()
        _state.update { it.copy(rootUri = tree, rootLabel = labelFor(tree), permissionLost = false, error = null) }
        rescan()
    }

    suspend fun rescan() = scanLock.withLock {
        val root = _state.value.rootUri ?: return@withLock
        _state.update { it.copy(scanning = true, error = null) }
        try {
            val result = withContext(Dispatchers.IO) {
                val index = LibraryClassifier.classify(SafTreeWalker(resolver).walk(root), System.currentTimeMillis())
                mirror.sync(index.bios)
                writeCache(index)
                index
            }
            _state.update {
                it.copy(
                    scanning = false, index = result,
                    mirroredBios = BiosKind.entries.filter { k -> File(systemDir, k.fileName).exists() }.toSet(),
                )
            }
        } catch (e: SecurityException) {
            _state.update { it.copy(scanning = false, permissionLost = true, error = "Folder access was revoked. Pick the folder again.") }
        } catch (e: Exception) {
            _state.update { it.copy(scanning = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun labelFor(tree: Uri): String =
        runCatching { android.provider.DocumentsContract.getTreeDocumentId(tree).substringAfter(':') }
            .getOrNull()?.ifEmpty { "Storage root" } ?: tree.toString()

    // ---- index cache ----------------------------------------------------------
    private fun writeCache(index: LibraryIndex) {
        val json = JSONObject()
            .put("version", 1)
            .put("scannedAt", index.scannedAtMillis)
            .put("roms", JSONArray().apply {
                index.roms.forEach { r ->
                    put(JSONObject().put("uri", r.uri).put("name", r.name).put("size", r.size)
                        .put("mtime", r.lastModified).put("system", r.system.name).put("path", r.parentPath))
                }
            })
            .put("saves", JSONArray().apply {
                index.saves.forEach { (key, sv) ->
                    put(JSONObject().put("key", key).put("uri", sv.uri).put("name", sv.name).put("size", sv.size).put("mtime", sv.lastModified))
                }
            })
            .put("bios", JSONArray().apply {
                index.bios.values.forEach { b ->
                    put(JSONObject().put("kind", b.kind.name).put("uri", b.uri).put("size", b.size)
                        .put("mtime", b.lastModified).put("path", b.parentPath))
                }
            })
        val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
        tmp.writeText(json.toString())
        tmp.renameTo(cacheFile)
    }

    private fun readCache(): LibraryIndex {
        if (!cacheFile.exists()) return LibraryIndex.EMPTY
        val json = JSONObject(cacheFile.readText())
        val roms = json.getJSONArray("roms").let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val system = runCatching { ConsoleSystem.valueOf(o.getString("system")) }.getOrNull() ?: return@mapNotNull null
                RomEntry(o.getString("uri"), o.getString("name"), o.getLong("size"), o.getLong("mtime"), system, o.optString("path"))
            }
        }
        val bios = json.getJSONArray("bios").let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val kind = runCatching { BiosKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                kind to BiosEntry(kind, o.getString("uri"), o.getLong("size"), o.getLong("mtime"), o.optString("path"))
            }.toMap()
        }
        val saves = json.optJSONArray("saves")?.let { a ->
            (0 until a.length()).associate { i ->
                val o = a.getJSONObject(i)
                o.getString("key") to ExternalSave(o.getString("uri"), o.getString("name"), o.getLong("size"), o.getLong("mtime"))
            }
        } ?: emptyMap()
        return LibraryIndex(roms, bios, json.getLong("scannedAt"), saves)
    }

    private companion object { const val KEY_TREE = "tree_uri" }
}
