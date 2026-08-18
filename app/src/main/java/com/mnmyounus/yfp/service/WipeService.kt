package com.mnmyounus.yfp.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.MutableLiveData
import com.mnmyounus.yfp.R
import com.mnmyounus.yfp.engine.DummyFileCleaner
import com.mnmyounus.yfp.engine.TargetKind
import com.mnmyounus.yfp.engine.WipeConfig
import com.mnmyounus.yfp.engine.WipeEngine
import com.mnmyounus.yfp.engine.WipeProgress
import com.mnmyounus.yfp.engine.WipeState
import com.mnmyounus.yfp.engine.WipeTarget
import com.mnmyounus.yfp.ui.mobile.MainActivity
import java.util.concurrent.Executors

/**
 * Owns the single, app-wide overwrite job.
 *
 * This runs as a foreground service (not a plain background thread in the
 * Activity/ViewModel) for two reasons:
 *  1. A multi-GB write job can legitimately take many minutes; Android will
 *     kill background work far sooner than that once the Activity isn't
 *     visible (screen off, user switches app, low memory).
 *  2. The spec explicitly wants Pause/Resume/Cancel to work reliably and
 *     wants live MB/s + ETA — a foreground service + persistent notification
 *     is the supported, expected pattern for exactly this kind of
 *     long-running, user-visible, user-controlled task, and it means the
 *     job keeps running (and the user can still see/control it) even if
 *     they leave the app or the screen locks.
 *
 * Bound (not just started) so the Activity/ViewModel can get a live
 * reference to LiveData progress without round-tripping through broadcasts.
 * Still also started (startForegroundService) so the service survives after
 * the Activity unbinds (e.g. user backgrounds the app) — the two are not
 * mutually exclusive and this is the standard "long task with a UI" pattern.
 */
class WipeService : Service() {

    companion object {
        const val CHANNEL_ID = "yfp_wipe_progress"
        const val NOTIFICATION_ID = 1001

        const val ACTION_START = "com.mnmyounus.yfp.action.START"
        const val ACTION_PAUSE = "com.mnmyounus.yfp.action.PAUSE"
        const val ACTION_RESUME = "com.mnmyounus.yfp.action.RESUME"
        const val ACTION_CANCEL_AND_DELETE = "com.mnmyounus.yfp.action.CANCEL_AND_DELETE"

        const val EXTRA_CONFIG = "extra_config"

        fun buildStartIntent(context: Context, config: WipeConfig): Intent =
            Intent(context, WipeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CONFIG, config)
            }
    }

    inner class LocalBinder : Binder() {
        fun getService(): WipeService = this@WipeService
    }

    private val binder = LocalBinder()

    /** UI observes this for all state transitions (Running/Paused/Completed/…). */
    val stateLiveData = MutableLiveData<WipeState>(WipeState.Idle)
    /** UI observes this for the high-frequency numeric readout (MB/s, %, ETA)
     *  kept separate from stateLiveData so the UI can throttle/animate the
     *  numeric readout independently of coarser state transitions. */
    val progressLiveData = MutableLiveData<WipeProgress>()

    private var engine: WipeEngine? = null
    private var currentTarget: WipeTarget? = null
    private var currentConfig: WipeConfig? = null
    private val workExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val config = intent.getParcelableConfigCompat()
                if (config != null) startWipe(config)
            }
            ACTION_PAUSE -> pauseWipe()
            ACTION_RESUME -> resumeWipe()
            ACTION_CANCEL_AND_DELETE -> cancelAndDelete()
        }
        // START_NOT_STICKY: if the OS kills this service under memory
        // pressure, we deliberately do NOT want Android to silently restart
        // it with a null Intent later and resume writing files with no user
        // visibility into that having happened. The user can explicitly
        // resume from the app UI, at which point WipeViewModel re-derives
        // "bytes already on disk" from WipeTarget.listExistingDummyFiles()
        // (see WipeEngine.run(resumeFromBytes=)) so no progress is lost —
        // it's just never silently auto-resumed without the user present.
        return START_NOT_STICKY
    }

    private fun startWipe(config: WipeConfig) {
        if (engine != null) return // a job is already running; ignore duplicate start

        val target = resolveTarget(config)
        currentTarget = target
        currentConfig = config

        val resumeBytes = target.listExistingDummyFiles().sumOf { it.bytesWritten }

        val newEngine = WipeEngine(
            target = target,
            config = config,
            onProgress = { progress ->
                progressLiveData.postValue(progress)
                updateNotification(progress, paused = false)
            },
            onStateChange = { state ->
                stateLiveData.postValue(state)
                handleTerminalStateIfAny(state)
            }
        )
        engine = newEngine

        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_starting), 0, ongoing = true))

        workExecutor.execute {
            newEngine.run(resumeFromBytes = resumeBytes)
        }
    }

    private fun pauseWipe() {
        engine?.pause()
    }

    private fun resumeWipe() {
        engine?.resume()
    }

    /** Cancel button per spec: "Hitting Delete instantly purges all dummy
     *  files generated by the application." So Cancel and Delete are the
     *  same action here — there is no "cancel but keep the partially-written
     *  files" state, which matches the spec's stated intent that this
     *  button's whole job is to instantly give the space back. */
    private fun cancelAndDelete() {
        val target = currentTarget
        engine?.cancel()
        // Cleanup runs on the same background executor, after cancel() has
        // been signalled, so it naturally runs after the engine's current
        // write() call returns and closes its stream — deleting a file
        // that's still open for write on some filesystems/providers can
        // silently no-op instead of actually freeing space.
        workExecutor.execute {
            if (target != null) {
                DummyFileCleaner.purgeAll(target)
            }
            stateLiveData.postValue(WipeState.Idle)
            engine = null
            currentTarget = null
            currentConfig = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun handleTerminalStateIfAny(state: WipeState) {
        when (state) {
            is WipeState.Completed -> {
                val text = if (state.hitStorageLimit) {
                    getString(
                        R.string.notification_completed_storage_limit_format,
                        formatBytes(state.progress.bytesWrittenTotal)
                    )
                } else {
                    getString(
                        R.string.notification_completed_format,
                        formatBytes(state.progress.bytesWrittenTotal)
                    )
                }
                // Use the job's actual reached percentage rather than
                // hardcoding 100 — when hitStorageLimit is true the job
                // stopped short of its target, and a full bar would
                // misrepresent that to anyone glancing at the notification.
                updateNotification(text, state.progress.percentComplete, ongoing = false)
                engine = null
                stopForegroundCompat()
                // Deliberately NOT stopSelf() here: service stays alive
                // (but no longer foreground) so the bound Activity can still
                // read final progress/state and so the Delete button remains
                // reachable to purge the completed job's files without the
                // user having to re-navigate target selection.
            }
            is WipeState.Cancelled -> {
                // cancelAndDelete() above already drives cleanup + stopSelf;
                // this branch only fires if cancel happened without going
                // through cancelAndDelete (defensive; not expected given the
                // spec's Cancel==Delete behavior described above).
            }
            is WipeState.Failed -> {
                updateNotification(getString(R.string.notification_failed_format, state.message), 0, ongoing = false)
                engine = null
                stopForegroundCompat()
            }
            else -> { /* Running/Paused/Idle: no terminal handling needed. */ }
        }
    }

    private fun resolveTarget(config: WipeConfig): WipeTarget {
        return when (config.targetKind) {
            TargetKind.APP_INTERNAL -> WipeTarget.forAppInternal(applicationContext)
            TargetKind.SAF_TREE -> {
                val uri = config.treeUri
                    ?: throw IllegalArgumentException("SAF_TREE target selected but no treeUri present in config.")
                WipeTarget.forSafTree(applicationContext, uri.toString())
            }
        }
    }

    // ---- Notification plumbing ----

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW // LOW: silent updates — this is a progress readout, not an alert
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(progress: WipeProgress, paused: Boolean) {
        val text = if (paused) {
            getString(
                R.string.notification_paused_format,
                formatBytes(progress.bytesWrittenTotal),
                progress.percentComplete
            )
        } else {
            getString(
                R.string.notification_running_format,
                formatSpeed(progress.currentWriteSpeedBytesPerSec),
                formatBytes(progress.bytesWrittenTotal),
                formatBytes(progress.bytesTarget),
                progress.percentComplete
            )
        }
        updateNotification(text, progress.percentComplete, ongoing = !paused)
    }

    private fun updateNotification(text: String, percent: Int, ongoing: Boolean) {
        val notification = buildNotification(text, percent, ongoing)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String, percent: Int, ongoing: Boolean): Notification {
        val contentIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_shield)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setProgress(100, percent.coerceIn(0, 100), false)
            .setOngoing(ongoing)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format("%.2f GB", gb)
        else String.format("%.0f MB", bytes / (1024.0 * 1024.0))
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        val mbps = bytesPerSec / (1024.0 * 1024.0)
        return String.format("%.1f MB/s", mbps)
    }

    private fun Intent.getParcelableConfigCompat(): WipeConfig? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(EXTRA_CONFIG, WipeConfig::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(EXTRA_CONFIG)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        workExecutor.shutdownNow()
    }
}
