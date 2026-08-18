package com.mnmyounus.yfp.util

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

data class VolumeInfo(
    val label: String,
    val isRemovable: Boolean,
    val isPrimary: Boolean,
    val totalBytes: Long,
    val freeBytes: Long,
    /** Null for the primary/internal volume (no SAF picker needed — the app
     *  writes directly into its own sandbox there); non-null hints the UI
     *  that selecting this volume should launch ACTION_OPEN_DOCUMENT_TREE. */
    val storageVolume: StorageVolume?
)

/**
 * Enumerates storage volumes the device exposes (internal + any mounted
 * SD card / USB OTG storage the OS knows about) so the "Target Directory
 * Selection" screen can list real options instead of a hardcoded
 * Internal/SD Card pair — some devices have neither removable storage or
 * have more than one.
 */
object StorageInfo {

    fun listVolumes(context: Context): List<VolumeInfo> {
        val results = mutableListOf<VolumeInfo>()

        // Primary/internal volume: always present, no SAF grant required.
        val internalDir = context.getExternalFilesDir(null)?.parentFile?.parentFile?.parentFile
        val internalStat = try {
            StatFs(Environment.getDataDirectory().path)
        } catch (e: Exception) {
            null
        }
        results.add(
            VolumeInfo(
                label = "Internal Storage",
                isRemovable = false,
                isPrimary = true,
                totalBytes = internalStat?.totalBytes ?: 0L,
                freeBytes = internalStat?.availableBytes ?: 0L,
                storageVolume = null
            )
        )

        // Additional volumes (SD card, USB OTG, etc.) via StorageManager —
        // the only reliable, non-deprecated way to enumerate these on
        // modern Android, since raw paths like /storage/sdcard1 are not
        // guaranteed across OEMs.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val sm = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (volume in sm.storageVolumes) {
                if (volume.isPrimary) continue // already added above as Internal Storage
                val path = try {
                    volume.directory?.path
                } catch (e: Exception) {
                    null
                }
                val stat = path?.let { runCatching { StatFs(it) }.getOrNull() }
                results.add(
                    VolumeInfo(
                        label = volume.getDescriptionCompat(context),
                        isRemovable = volume.isRemovable,
                        isPrimary = false,
                        totalBytes = stat?.totalBytes ?: 0L,
                        freeBytes = stat?.availableBytes ?: 0L,
                        storageVolume = volume
                    )
                )
            }
        }

        return results
    }

    private fun StorageVolume.getDescriptionCompat(context: Context): String {
        return try {
            getDescription(context)
        } catch (e: Exception) {
            if (isRemovable) "SD Card" else "Storage"
        }
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.size - 1) {
            value /= 1024.0
            unitIndex++
        }
        return String.format("%.1f %s", value, units[unitIndex])
    }
}
