package com.nuvio.tv.livetv.ondemand

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.nuvio.tv.livetv.record.DriveFolders
import java.io.File
import java.io.IOException
import java.io.OutputStream

internal class VodDownloadError(val displayMessage: String) : IOException(displayMessage)

internal class VodDownloadFiles(private val context: Context, folderId: String) {
    private val resolver = context.contentResolver
    private val directory: File?
    private val tree: Uri?

    init {
        if (DriveFolders.isFolderId(folderId)) {
            val saved = DriveFolders.parse(folderId) ?: unavailable()
            val drive = DriveFolders.drives(context).firstOrNull { it.id == saved.drive } ?: unavailable()
            val dir = DriveFolders.dirOf(context, saved, listOf(drive))?.canonicalFile ?: unavailable()
            if (!DriveFolders.hasAccess() || generateSequence(dir) { it.parentFile }.none { it == drive.root.canonicalFile } ||
                !dir.isDirectory || !dir.canWrite()) unavailable()
            directory = dir
            tree = null
        } else {
            val uri = Uri.parse(folderId)
            if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri) ||
                resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission && it.isWritePermission }) unavailable()
            tree = uri
            directory = null
        }
    }

    private fun unavailable(): Nothing = throw VodDownloadError("Reconnect the drive or choose a writable download folder.")
    private fun native(name: String): File {
        val parent = checkNotNull(directory)
        val file = File(parent, name)
        if (file.canonicalFile.parentFile != parent || file.canonicalFile != file) unavailable()
        return file
    }

    private fun find(name: String): Uri? {
        directory?.let { return native(name).takeIf { it.isFile }?.let(Uri::fromFile) }
        val uri = checkNotNull(tree)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val projection = arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_DOCUMENT_ID)
        resolver.query(children, projection, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0) == name)
                    return DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(1))
            }
            return null
        }
        unavailable()
    }

    fun checkWritable() {
        if (directory != null) return
        val uri = checkNotNull(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        resolver.query(parent, arrayOf(Document.COLUMN_FLAGS), null, null, null)?.use {
            if (!it.moveToFirst() || it.getInt(0) and Document.FLAG_DIR_SUPPORTS_CREATE == 0) unavailable()
        } ?: unavailable()
    }

    private fun part(item: SavedVod) = "nuvio-" + item.view.id + ".part"
    private fun finalName(item: SavedVod) = safeVodFileTitle(item.view.title) + " - " + item.view.id + "." + item.extension

    fun createPart(item: SavedVod): Uri {
        val name = part(item)
        directory?.let {
            val file = native(name)
            if (!file.createNewFile()) throw IOException("Partial download already exists")
            return Uri.fromFile(file)
        }
        val uri = checkNotNull(tree)
        val parent = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name) ?: unavailable()
        if (find(name) != created) {
            DocumentsContract.deleteDocument(resolver, created)
            unavailable()
        }
        return created
    }

    fun output(uri: Uri): OutputStream {
        if (directory != null) {
            val file = native(File(checkNotNull(uri.path)).name)
            if (Uri.fromFile(file) != uri) unavailable()
            return file.outputStream()
        }
        return resolver.openOutputStream(uri, "wt") ?: unavailable()
    }

    /** Inspect local tracks before the file gets a playable name. This also catches encrypted MP4
     * with a trailing movie box, which a check of the HTTP headers or first bytes cannot see. */
    fun verifyVideo(uri: Uri) {
        val extractor = MediaExtractor()
        try {
            resolver.openFileDescriptor(uri, "r")?.use { extractor.setDataSource(it.fileDescriptor) } ?: unavailable()
            if (extractor.drmInitData != null || !extractor.psshInfo.isNullOrEmpty())
                throw VodDownloadError("Protected video cannot be saved for offline playback.")
            var video = false
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) video = true
                extractor.selectTrack(index)
                if (extractor.sampleTrackIndex >= 0 && extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_ENCRYPTED != 0)
                    throw VodDownloadError("Protected video cannot be saved for offline playback.")
                extractor.unselectTrack(index)
            }
            if (!video) throw VodDownloadError("The downloaded file contains no readable video track.")
        } catch (error: VodDownloadError) {
            throw error
        } catch (_: Exception) {
            throw VodDownloadError("This device could not verify the downloaded video file.")
        } finally {
            extractor.release()
        }
    }

    fun publish(item: SavedVod, uri: Uri) {
        val name = finalName(item)
        if (find(name) != null) throw IOException("The destination file already exists")
        directory?.let {
            if (!native(part(item)).renameTo(native(name))) unavailable()
            return
        }
        val renamed = DocumentsContract.renameDocument(resolver, uri, name)
            ?: throw VodDownloadError("This folder cannot rename completed downloads. Choose another folder.")
        if (find(name) != renamed) {
            DocumentsContract.deleteDocument(resolver, renamed)
            unavailable()
        }
    }

    fun saved(item: SavedVod): Uri? = if (item.extension.isEmpty()) null else find(finalName(item))

    fun size(uri: Uri): Long = resolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1

    fun clean(item: SavedVod, includeSaved: Boolean) {
        val names = if (!includeSaved || item.extension.isEmpty()) listOf(part(item)) else listOf(part(item), finalName(item))
        for (name in names) {
            val uri = find(name) ?: continue
            val removed = if (directory == null) DocumentsContract.deleteDocument(resolver, uri) else native(name).delete()
            if (!removed) unavailable()
        }
    }
}
