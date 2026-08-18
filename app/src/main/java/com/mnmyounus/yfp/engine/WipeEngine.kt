package com.mnmyounus.yfp.engine

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

sealed class WipeState {
    object Idle : WipeState()
    data class Running(val progress: WipeProgress) : WipeState()
    data class Paused(val progress: WipeProgress) : WipeState()
    /**
     * [hitStorageLimit] distinguishes two genuinely different outcomes that
     * both end with "the loop stopped and files are sitting on disk":
     *  - false: the configured target percentage was reached normally.
     *  - true: writing stopped early because live free space dropped to/
     *    below the safety floor, or a write hit ENOSPC — i.e. the device
     *    had less headroom than the pre-flight calculation assumed (most
     *    often because something else was writing to the same volume
     *    concurrently). The UI surfaces this distinctly rather than
     *    silently reporting it as a clean 100%-of-target success.
     */
    data class Completed(val progress: WipeProgress, val hitStorageLimit: Boolean = false) : WipeState()
    data class Cancelled(val progress: WipeProgress) : WipeState()
    data class Failed(val message: String, val progress: WipeProgress?) : WipeState()
}

data class WipeProgress(
    val bytesWrittenTotal: Long,
    val bytesTarget: Long,
    val currentWriteSpeedBytesPerSec: Long,
    val filesCreated: Int,
    val startedAtMillis: Long
) {
    val percentComplete: Int
        get() = if (bytesTarget <= 0L) 0
        else ((bytesWrittenTotal.toDouble() / bytesTarget.toDouble()) * 100.0)
            .coerceIn(0.0, 100.0)
            .toInt()

    val estimatedSecondsRemaining: Long?
        get() {
            if (currentWriteSpeedBytesPerSec <= 0L) return null
            val remaining = (bytesTarget - bytesWrittenTotal).coerceAtLeast(0L)
            return remaining / currentWriteSpeedBytesPerSec
        }
}

/**
 * Drives the actual overwrite job: repeatedly creates dummy files at
 * [WipeTarget] and fills them until the configured percentage of free space
 * has been consumed, reporting progress via [onProgress] and terminal state
 * via [onStateChange].
 *
 * This class does the writing; it does not decide *when* to run (that's
 * WipeService's job, since only a foreground service should own a
 * long-lived background thread on modern Android) and does not touch UI.
 *
 * Threading: run() is a blocking call meant to be invoked from a background
 * thread/coroutine the caller owns. pause()/resume()/cancel() are safe to
 * call from any thread and communicate with the running loop via the lock +
 * flags below, rather than via thread interruption, so a pause always lands
 * on a clean chunk/buffer boundary instead of leaving a half-written buffer.
 */
class WipeEngine(
    private val target: WipeTarget,
    private val config: WipeConfig,
    private val onProgress: (WipeProgress) -> Unit,
    private val onStateChange: (WipeState) -> Unit
) {
    companion object {
        /**
         * Never let the free-space calculation drive the device below this
         * many bytes free, *regardless* of what targetFillPercent implies.
         * This exists because:
         *   1. StatFs.availableBytes on some OEM filesystems already reserves
         *      a small amount for the FS journal, but not always enough to
         *      avoid the OS itself becoming unstable (background processes
         *      failing to write logs/cache, system UI misbehaving) once free
         *      space hits genuine single-digit MB.
         *   2. Free space can be consumed concurrently by other apps while
         *      this job runs; without a floor, a job that started with a
         *      safe margin could still race itself down to zero.
         * 64 MiB is comfortably more than any modern Android build needs for
         * routine housekeeping, while being small enough not to meaningfully
         * change the *effective* max fill percentage on any realistically
         * sized volume the app would be pointed at.
         */
        const val MIN_FREE_SPACE_FLOOR_BYTES: Long = 64L * 1024 * 1024

        /** Buffer size for each write() call — large enough to amortize
         *  syscall overhead, small enough to keep pause latency low and
         *  avoid a huge transient allocation. */
        const val WRITE_BUFFER_BYTES: Int = 4 * 1024 * 1024 // 4 MiB
    }

    private val pauseLock = ReentrantLock()
    private val pauseCondition = pauseLock.newCondition()
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)

    private val randomFiller by lazy { FastRandomFiller() }
    private val zeroBuffer by lazy { ByteArray(WRITE_BUFFER_BYTES) } // all-zero by default in Kotlin/JVM
    private val randomBuffer by lazy { ByteArray(WRITE_BUFFER_BYTES) }

    @Volatile private var filesCreatedCount = 0

    fun pause() {
        isPaused.set(true)
    }

    fun resume() {
        pauseLock.withLock {
            isPaused.set(false)
            pauseCondition.signalAll()
        }
    }

    fun cancel() {
        isCancelled.set(true)
        // In case cancel() arrives while paused, wake the loop so it can
        // observe isCancelled and unwind instead of blocking forever.
        pauseLock.withLock { pauseCondition.signalAll() }
    }

    /**
     * Blocking call: runs until the target fill percentage is reached,
     * cancellation is requested, or an unrecoverable error occurs.
     *
     * [resumeFromBytes] lets a caller resume a job across a service restart:
     * if the service process died mid-wipe (e.g. killed by the OS under
     * memory pressure) and is restarted, WipeService can pass in the sum of
     * bytes already sitting in existing dummy files on disk so this run
     * continues topping-up toward the same target instead of restarting the
     * whole percentage calculation from zero and potentially exceeding the
     * user's chosen cap.
     */
    fun run(resumeFromBytes: Long = 0L) {
        val startedAt = System.currentTimeMillis()
        var totalWritten = resumeFromBytes

        val initialFree = target.freeSpaceBytes()
        if (initialFree <= 0L) {
            onStateChange(
                WipeState.Failed(
                    "Could not read free space on the selected target. It may have been unmounted or the permission grant may have been revoked.",
                    null
                )
            )
            return
        }

        // "Available to fill" = current free space, minus what we already
        // hold in progress (resumeFromBytes already occupies disk, it's not
        // additional headroom), minus the safety floor.
        val fillableNow = (initialFree - MIN_FREE_SPACE_FLOOR_BYTES).coerceAtLeast(0L)
        val targetBytes = ((fillableNow + resumeFromBytes) * (config.targetFillPercent / 100.0))
            .toLong()
            .coerceAtLeast(resumeFromBytes) // never target *less* than what's already written

        var progress = WipeProgress(
            bytesWrittenTotal = totalWritten,
            bytesTarget = targetBytes,
            currentWriteSpeedBytesPerSec = 0L,
            filesCreated = filesCreatedCount,
            startedAtMillis = startedAt
        )
        onStateChange(WipeState.Running(progress))

        try {
            while (totalWritten < targetBytes && !isCancelled.get()) {
                waitWhilePaused(progress)
                if (isCancelled.get()) break

                val remainingForTarget = targetBytes - totalWritten
                val chunkTarget = minOf(remainingForTarget, config.chunkSizeBytes)
                if (chunkTarget <= 0L) break

                val fileName = "${WipeConfig.DUMMY_FILE_PREFIX}${filesCreatedCount}_${System.currentTimeMillis()}${WipeConfig.DUMMY_FILE_SUFFIX}"
                val (handle, stream) = target.createDummyFile(fileName)
                filesCreatedCount++

                var chunkWritten = 0L
                var speedWindowStart = System.currentTimeMillis()
                var speedWindowBytes = 0L
                var currentSpeed = 0L

                stream.use { out ->
                    while (chunkWritten < chunkTarget && !isCancelled.get()) {
                        waitWhilePaused(progress)
                        if (isCancelled.get()) break

                        // Re-check real free space periodically (not on every
                        // single buffer write, to avoid a StatFs syscall per
                        // 4 MiB) so a device that's genuinely almost out of
                        // room — independent of our target math above,
                        // e.g. another app is simultaneously eating space —
                        // doesn't drive us into an ENOSPC crash loop or
                        // below the safety floor.
                        val liveFree = target.freeSpaceBytes()
                        if (liveFree <= MIN_FREE_SPACE_FLOOR_BYTES) {
                            handle.bytesWritten = chunkWritten
                            progress = progress.copy(
                                bytesWrittenTotal = totalWritten + chunkWritten,
                                currentWriteSpeedBytesPerSec = currentSpeed,
                                filesCreated = filesCreatedCount
                            )
                            onStateChange(WipeState.Completed(progress, hitStorageLimit = true))
                            onProgress(progress)
                            return
                        }

                        val remainingInChunk = chunkTarget - chunkWritten
                        val writeSize = minOf(remainingInChunk, WRITE_BUFFER_BYTES.toLong()).toInt()

                        val buffer = when (config.pattern) {
                            OverwritePattern.ZERO_FILL -> zeroBuffer
                            OverwritePattern.PSEUDO_RANDOM -> {
                                randomFiller.fill(randomBuffer)
                                randomBuffer
                            }
                        }

                        try {
                            out.write(buffer, 0, writeSize)
                        } catch (io: IOException) {
                            // Most commonly ENOSPC racing ahead of our
                            // freeSpaceBytes() check above (another app wrote
                            // a large file in the same instant). Treat this
                            // exactly like hitting the floor: stop cleanly
                            // and report what was actually achieved, rather
                            // than surfacing a raw IOException to the user.
                            handle.bytesWritten = chunkWritten
                            progress = progress.copy(
                                bytesWrittenTotal = totalWritten + chunkWritten,
                                currentWriteSpeedBytesPerSec = currentSpeed,
                                filesCreated = filesCreatedCount
                            )
                            onStateChange(WipeState.Completed(progress, hitStorageLimit = true))
                            onProgress(progress)
                            return
                        }

                        chunkWritten += writeSize
                        speedWindowBytes += writeSize

                        val now = System.currentTimeMillis()
                        val elapsed = now - speedWindowStart
                        if (elapsed >= 500) { // refresh speed twice/sec — enough for a smooth-looking MB/s readout without recomputing on every 4MB buffer
                            currentSpeed = (speedWindowBytes * 1000L) / elapsed.coerceAtLeast(1L)
                            speedWindowStart = now
                            speedWindowBytes = 0L
                        }

                        handle.bytesWritten = chunkWritten
                        progress = progress.copy(
                            bytesWrittenTotal = totalWritten + chunkWritten,
                            currentWriteSpeedBytesPerSec = currentSpeed,
                            filesCreated = filesCreatedCount
                        )
                        onProgress(progress)
                    }
                    out.flush()
                }

                totalWritten += chunkWritten

                if (isCancelled.get()) {
                    break
                }
            }

            progress = progress.copy(bytesWrittenTotal = totalWritten, filesCreated = filesCreatedCount)

            if (isCancelled.get()) {
                onStateChange(WipeState.Cancelled(progress))
            } else {
                onStateChange(WipeState.Completed(progress))
            }
        } catch (e: Exception) {
            onStateChange(WipeState.Failed(e.message ?: "Unknown error during wipe.", progress))
        }
    }

    /** Blocks the calling (background) thread while isPaused is true,
     *  waking immediately on resume() or cancel(). Emits a Paused state
     *  transition exactly once per pause (not spuriously on every wakeup
     *  check), so the UI doesn't flicker between Running/Paused. */
    private var lastEmittedPaused = false
    private fun waitWhilePaused(currentProgress: WipeProgress) {
        pauseLock.withLock {
            if (isPaused.get() && !isCancelled.get()) {
                if (!lastEmittedPaused) {
                    onStateChange(WipeState.Paused(currentProgress))
                    lastEmittedPaused = true
                }
                while (isPaused.get() && !isCancelled.get()) {
                    pauseCondition.await()
                }
                if (!isCancelled.get()) {
                    lastEmittedPaused = false
                    onStateChange(WipeState.Running(currentProgress))
                }
            }
        }
    }
}
