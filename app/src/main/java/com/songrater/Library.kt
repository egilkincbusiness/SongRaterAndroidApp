package com.songrater

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileNotFoundException
import java.io.IOException

data class Track(val uri: Uri, val name: String)

/** Folder access through the Storage Access Framework (the folders the user picked in Settings). */
object Library {
    private const val BUF = 64 * 1024

    /** MP3 files directly inside [tree] (no subfolders). */
    fun list(ctx: Context, tree: Uri): List<Track> {
        val out = ArrayList<Track>()
        for ((id, name, mime) in children(ctx.contentResolver, tree)) {
            if (mime == Document.MIME_TYPE_DIR) continue
            if (!name.endsWith(".mp3", ignoreCase = true)) continue
            out.add(Track(DocumentsContract.buildDocumentUriUsingTree(tree, id), name))
        }
        return out
    }

    /**
     * Writes a copy of [track] into [destTree] with its rating set to [stars], then deletes the original.
     * If anything fails, the partial copy is removed and the original is left alone.
     */
    fun moveRated(ctx: Context, track: Track, destTree: Uri, stars: Int) {
        val r = ctx.contentResolver
        val destParent = DocumentsContract.buildDocumentUriUsingTree(
            destTree, DocumentsContract.getTreeDocumentId(destTree)
        )
        val taken = children(r, destTree).mapTo(HashSet()) { it.second.lowercase() }
        val name = uniqueName(track.name, taken)
        val srcSize = querySize(r, track.uri)

        val dest = DocumentsContract.createDocument(r, destParent, "audio/mpeg", name)
            ?: throw IOException("could not create a file in the destination folder")
        var success = false
        try {
            var result: Id3Tag.CopyResult? = null
            r.openInputStream(track.uri).use { rawIn ->
                if (rawIn == null) throw IOException("could not open the song")
                r.openOutputStream(dest, "w").use { rawOut ->
                    if (rawOut == null) throw IOException("could not write to the destination folder")
                    result = Id3Tag.copyWithRating(
                        BufferedInputStream(rawIn, BUF), BufferedOutputStream(rawOut, BUF), stars
                    )
                }
            }
            val res = result ?: throw IOException("copy did not run")
            if (srcSize >= 0 && res.read != srcSize) {
                throw IOException("read ${res.read} of $srcSize bytes")
            }
            val destSize = querySize(r, dest)
            if (destSize >= 0 && destSize != res.written) {
                throw IOException("copy check failed ($destSize of ${res.written} bytes)")
            }
            val deleted = try {
                DocumentsContract.deleteDocument(r, track.uri)
            } catch (e: FileNotFoundException) {
                true
            }
            if (!deleted) throw IOException("could not delete the original after copying")
            success = true
        } finally {
            if (!success) {
                try {
                    DocumentsContract.deleteDocument(r, dest)
                } catch (e: Exception) {
                    // nothing more we can do; the original is untouched
                }
            }
        }
    }

    private fun uniqueName(name: String, taken: Set<String>): String {
        if (name.lowercase() !in taken) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate.lowercase() !in taken) return candidate
            n++
        }
    }

    private fun children(r: ContentResolver, tree: Uri): List<Triple<String, String, String>> {
        val parentId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Triple<String, String, String>>()
        val cursor = r.query(
            childrenUri,
            arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE),
            null, null, null
        ) ?: throw IOException("could not read the folder")
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                out.add(Triple(id, name, c.getString(2) ?: ""))
            }
        }
        return out
    }

    private fun querySize(r: ContentResolver, uri: Uri): Long = try {
        r.query(uri, arrayOf(Document.COLUMN_SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
        } ?: -1L
    } catch (e: Exception) {
        -1L
    }
}
