package com.projectcit.libretro

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import java.io.Closeable
import java.io.File
import java.io.FileInputStream

/**
 * Makes a SAF content:// ROM consumable by a libretro core without copying it.
 *
 * Cores want a filesystem path ending in the real extension. We open the document
 * as a file descriptor and create `cache/content/<name>.<ext>` as a symlink to
 * `/proc/self/fd/<fd>`; opening the link reaches the same open file. If the
 * platform refuses (SELinux policy can vary by OEM), and the core truly needs a
 * path, we fall back to a one-off copy in cache. Cores that accept memory buffers
 * (mGBA) are fed straight from the fd natively, so the link only has to carry a name.
 */
class ContentGame private constructor(
    val path: String,
    val fd: Int,
    val saveName: String,
    val strategy: Strategy,
    private val pfd: ParcelFileDescriptor,
    private val temp: File?,
) : Closeable {

    enum class Strategy { FD_SYMLINK, FD_ONLY, CACHE_COPY }

    companion object {
        fun open(context: Context, uri: Uri, displayName: String, saveName: String, needsReadablePath: Boolean): ContentGame {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw LibretroException("cannot open $displayName")
            val dir = File(context.cacheDir, "content").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }  // one game at a time; clear stale links/copies
            val safe = displayName.replace(Regex("""[/\\:*?"<>|\u0000]"""), "_")
            val link = File(dir, safe)
            val linked = try {
                Os.symlink("/proc/self/fd/${pfd.fd}", link.path)
                FileInputStream(link).use { it.read() } // prove the link is readable through policy
                true
            } catch (e: ErrnoException) {
                false
            } catch (e: java.io.IOException) {
                false
            } catch (e: SecurityException) {
                false
            }
            return when {
                linked -> ContentGame(link.path, pfd.fd, saveName, Strategy.FD_SYMLINK, pfd, link)
                !needsReadablePath -> ContentGame(link.path, pfd.fd, saveName, Strategy.FD_ONLY, pfd, null)
                else -> {
                    link.delete()
                    FileInputStream(pfd.fileDescriptor).use { input -> link.outputStream().use { input.copyTo(it) } }
                    ContentGame(link.path, pfd.fd, saveName, Strategy.CACHE_COPY, pfd, link)
                }
            }
        }
    }

    override fun close() {
        temp?.delete()
        runCatching { pfd.close() }
    }
}
