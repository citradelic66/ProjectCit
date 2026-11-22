package com.projectcit.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Breadth-first walk of a SAF tree using raw DocumentsContract queries — one query
 * per directory. (DocumentFile.listFiles() issues an extra query *per file*, which
 * is what makes naive SAF scanners take minutes on large ROM sets.)
 */
class SafTreeWalker(
    private val resolver: ContentResolver,
    private val maxDepth: Int = 8,
    private val maxEntries: Int = 50_000,
) {
    private val projection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_SIZE,
        Document.COLUMN_LAST_MODIFIED,
    )

    fun walk(treeUri: Uri): Sequence<DocumentEntry> = sequence {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        val queue = ArrayDeque<Triple<String, String, Int>>() // docId, path, depth
        queue.addLast(Triple(rootId, "", 0))
        var emitted = 0
        while (queue.isNotEmpty() && emitted < maxEntries) {
            val (dirId, path, depth) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirId)
            val rows = ArrayList<DocumentEntry>()
            resolver.query(children, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val isDir = c.getString(2) == Document.MIME_TYPE_DIR
                    rows += DocumentEntry(
                        documentId = id,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                        name = name,
                        size = if (c.isNull(3)) -1L else c.getLong(3),
                        lastModified = if (c.isNull(4)) 0L else c.getLong(4),
                        isDirectory = isDir,
                        parentPath = path,
                    )
                }
            }
            for (row in rows) {
                if (row.isDirectory) {
                    if (depth + 1 <= maxDepth && !row.name.startsWith(".")) {
                        queue.addLast(Triple(row.documentId, if (path.isEmpty()) row.name else "$path/${row.name}", depth + 1))
                    }
                } else {
                    emitted++
                    yield(row)
                }
            }
        }
    }
}
