package com.mnmyounus.yfp.engine

import android.content.Context
import android.os.StatFs
import android.os.storage.StorageManager
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * A single dummy file the engine is currently writing/has written, expressed
 * in a way both storage backends (raw File and SAF DocumentFile) can supply.
 */
data class DummyFileHandle(
    val displayName: String,
    /** Bytes already flushed to disk for this file across the whole job
     *  (survives pause/resume within a session; does not survive
     *  process death mid-chunk — see WipeService for that boundary). */
    var bytesWritten: Long,
    val delete: () -> Boolean
)

/**
 * Abstracts "a folder I can create/append-write/delete large files in" over
 * the two possible backends:
 *  - Direct java.io.File access for the app's own internal/external-files
 *    sandbox (no SAF grant needed, works down to minSdk).
 *  - SAF (DocumentFile + ContentResolver) for anything the user picked via
 *    ACTION_OPEN_DOCUMENT_TREE — this is required by Scoped Storage for the
 *    SD card and for any "custom directory" outside the app's sandbox.
 *
 * WipeEngine talks only to this interface, so the actual overwrite loop
 * doesn't need to know or care which backend it's pointed at.
 */
interface WipeTarget {
    /** Total free bytes currently available at this target, straight from
     *  the filesystem (not cached), so the engine can react to space
     *  consumed by *other* apps mid-job, not just its own writes. */
    fun freeSpaceBytes(): Long

    /** Opens a new dummy file for writing and returns a handle + OutputStream.
     *  Caller is responsible for closing the stream. */
    fun createDummyFile(name: String): Pair<DummyFileHandle, OutputStream>

    /** Every yfp_dummy_*.bin file currently sitting at this target, so the
     *  Delete/Cancel action and "resume after process death" logic can find
     *  and remove them without the engine having tracked every handle. */
    fun listExistingDummyFiles(): List<DummyFileHandle>

    companion object {
        fun forAppInternal(context: Context): WipeTarget = InternalWipeTarget(context)

        fun forSafTree(context: Context, treeUriString: String): WipeTarget =
            SafWipeTarget(context, treeUriString)
    }
}

/** Internal storage backend: writes into the app's own external-files sandbox
 *  (Android/data/com.mnmyounus.yfp/files/wipe/), which requires no runtime
 *  permission grant on modern Android and is guaranteed to actually be on
 *  the device's main storage volume (as opposed to filesDir, which on some
 *  OEM skins can be redirected in surprising ways). */
private class InternalWipeTarget(private val context: Context) : WipeTarget {
    private val dir: File by lazy {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        File(base, "wipe").apply { mkdirs() }
    }

    override fun freeSpaceBytes(): Long {
        return try {
            val statFs = StatFs(dir.absolutePath)
            statFs.availableBytes
        } catch (e: Exception) {
            0L
        }
    }

    override fun createDummyFile(name: String): Pair<DummyFileHandle, OutputStream> {
        val file = File(dir, name)
        val stream = FileOutputStream(file, false)
        val handle = DummyFileHandle(
            displayName = name,
            bytesWritten = 0L,
            delete = { file.delete() }
        )
        return handle to stream
    }

    override fun listExistingDummyFiles(): List<DummyFileHandle> {
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith(WipeConfig.DUMMY_FILE_PREFIX) &&
                f.name.endsWith(WipeConfig.DUMMY_FILE_SUFFIX)
        } ?: emptyArray()
        return files.map { f ->
            DummyFileHandle(
                displayName = f.name,
                bytesWritten = f.length(),
                delete = { f.delete() }
            )
        }
    }
}

/** SAF backend: writes into a tree the user granted via
 *  ACTION_OPEN_DOCUMENT_TREE (SD card, or any other folder they pick). */
private class SafWipeTarget(
    private val context: Context,
    private val treeUriString: String
) : WipeTarget {

    private val treeDoc: DocumentFile by lazy {
        val uri = android.net.Uri.parse(treeUriString)
        DocumentFile.fromTreeUri(context, uri)
            ?: throw IllegalStateException("Could not resolve granted tree URI — the user may need to re-pick the folder.")
    }

    override fun freeSpaceBytes(): Long {
        // StorageManager.getStorageVolume(File) doesn't accept a SAF Uri
        // directly on all API levels, so we go through the platform's
        // documented route: ContentResolver + DocumentsContract free-space
        // query, falling back to StatFs on the volume root path when the
        // provider doesn't report it (some third-party SD-card providers
        // omit COLUMN_AVAILABLE_BYTES).
        return try {
            val resolver = context.contentResolver
            val docUri = treeDoc.uri
            val cursor = resolver.query(
                docUri,
                arrayOf(android.provider.DocumentsContract.Root.COLUMN_AVAILABLE_BYTES),
                null, null, null
            )
            cursor?.use {
                if (it.moveToFirst() && !it.isNull(0)) {
                    return it.getLong(0)
                }
            }
            fallbackFreeSpaceViaStorageManager()
        } catch (e: Exception) {
            fallbackFreeSpaceViaStorageManager()
        }
    }

    private fun fallbackFreeSpaceViaStorageManager(): Long {
        return try {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            val volume = sm.getStorageVolume(treeDoc.uri)
            // StorageVolume itself doesn't expose free bytes pre-API 34 in a
            // stable public way; as a last resort, if we can resolve a real
            // filesystem path (legacy SD cards often still have one), use
            // StatFs on it. If not, return a conservative 0 so the engine
            // treats "unknown" as "stop and let the user see an error"
            // rather than silently assuming there's plenty of room.
            val path = volume?.directory?.path
            if (path != null) StatFs(path).availableBytes else 0L
        } catch (e: Exception) {
            0L
        }
    }

    override fun createDummyFile(name: String): Pair<DummyFileHandle, OutputStream> {
        // "application/octet-stream" keeps other apps' media scanners from
        // trying to index these as photos/videos/documents.
        val newFile = treeDoc.createFile("application/octet-stream", name)
            ?: throw IllegalStateException("Failed to create dummy file via SAF — target folder may be full or the grant may have been revoked.")
        val pfd = context.contentResolver.openFileDescriptor(newFile.uri, "w")
            ?: throw IllegalStateException("Failed to open file descriptor for new SAF file.")
        val stream = FileOutputStream(pfd.fileDescriptor)
        val handle = DummyFileHandle(
            displayName = name,
            bytesWritten = 0L,
            delete = { newFile.delete() }
        )
        // Wrap so closing the returned stream also closes the underlying pfd
        // (FileOutputStream(fd) alone does not close the ParcelFileDescriptor).
        return handle to PfdClosingOutputStream(stream, pfd)
    }

    override fun listExistingDummyFiles(): List<DummyFileHandle> {
        val children = treeDoc.listFiles()
        return children.filter { doc ->
            val n = doc.name
            doc.isFile && n != null &&
                n.startsWith(WipeConfig.DUMMY_FILE_PREFIX) &&
                n.endsWith(WipeConfig.DUMMY_FILE_SUFFIX)
        }.map { doc ->
            DummyFileHandle(
                displayName = doc.name ?: "unknown",
                bytesWritten = doc.length(),
                delete = { doc.delete() }
            )
        }
    }
}

/** FileOutputStream over a raw fd doesn't own the ParcelFileDescriptor, so
 *  without this wrapper every SAF-written chunk would leak a file descriptor
 *  — harmless for one file, but this app deliberately writes many
 *  multi-gigabyte files in a row, so a leak here would exhaust the process's
 *  fd table over a long wipe. */
private class PfdClosingOutputStream(
    private val delegate: FileOutputStream,
    private val pfd: android.os.ParcelFileDescriptor
) : OutputStream() {
    override fun write(b: Int) = delegate.write(b)
    override fun write(b: ByteArray) = delegate.write(b)
    override fun write(b: ByteArray, off: Int, len: Int) = delegate.write(b, off, len)
    override fun flush() = delegate.flush()
    override fun close() {
        delegate.close()
        pfd.close()
    }
}
