package com.mnmyounus.yfp.ui.common

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import com.mnmyounus.yfp.engine.OverwritePattern
import com.mnmyounus.yfp.engine.TargetKind
import com.mnmyounus.yfp.engine.WipeConfig
import com.mnmyounus.yfp.engine.WipeProgress
import com.mnmyounus.yfp.engine.WipeState
import com.mnmyounus.yfp.service.WipeService

/**
 * Single source of truth shared by MainActivity (mobile) and TvMainActivity
 * (Android TV). Both UIs talk to the same WipeService instance through this
 * ViewModel rather than each Activity binding independently and duplicating
 * connection-lifecycle bookkeeping — there is exactly one wipe job at a
 * time app-wide, which matches the spec (one target, one job, with
 * pause/resume/cancel controls over it).
 *
 * Uses AndroidViewModel (not plain ViewModel) because binding a Service
 * legitimately needs an application Context, and holding the *Application*
 * context here (never an Activity context) is what makes it safe to do so
 * without leaking an Activity across configuration changes.
 */
class WipeViewModel(application: Application) : AndroidViewModel(application) {

    val state: LiveData<WipeState> get() = _state
    private val _state = MediatorLiveData<WipeState>()

    val progress: LiveData<WipeProgress> get() = _progress
    private val _progress = MediatorLiveData<WipeProgress>()

    private var boundService: WipeService? = null
    private var isBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = (binder as WipeService.LocalBinder).getService()
            boundService = service
            isBound = true
            _state.addSource(service.stateLiveData) { _state.value = it }
            _progress.addSource(service.progressLiveData) { _progress.value = it }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            boundService?.let {
                _state.removeSource(it.stateLiveData)
                _progress.removeSource(it.progressLiveData)
            }
            boundService = null
            isBound = false
        }
    }

    /** Call from onStart() of whichever Activity is currently in front
     *  (mobile or TV) — safe to call from both across a hand-off since
     *  bindService is idempotent per-Context and this ViewModel is the only
     *  thing holding the connection. */
    fun bindService() {
        val context = getApplication<Application>()
        val intent = Intent(context, WipeService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbindService() {
        if (isBound) {
            val context = getApplication<Application>()
            context.unbindService(connection)
            isBound = false
        }
    }

    fun startWipe(
        targetKind: TargetKind,
        treeUri: Uri?,
        targetLabel: String,
        pattern: OverwritePattern,
        targetFillPercent: Int = WipeConfig.DEFAULT_FILL_PERCENT,
        chunkSizeMb: Long = WipeConfig.DEFAULT_CHUNK_MB
    ) {
        val context = getApplication<Application>()
        val config = WipeConfig(
            targetKind = targetKind,
            treeUri = treeUri,
            targetLabel = targetLabel,
            pattern = pattern,
            targetFillPercent = targetFillPercent.coerceIn(
                WipeConfig.MIN_FILL_PERCENT,
                WipeConfig.MAX_FILL_PERCENT
            ),
            chunkSizeBytes = chunkSizeMb.coerceIn(WipeConfig.MIN_CHUNK_MB, WipeConfig.MAX_CHUNK_MB) * 1024L * 1024L
        )
        val intent = WipeService.buildStartIntent(context, config)
        androidx.core.content.ContextCompat.startForegroundService(context, intent)
    }

    fun pause() = sendAction(WipeService.ACTION_PAUSE)
    fun resume() = sendAction(WipeService.ACTION_RESUME)
    fun cancelAndDelete() = sendAction(WipeService.ACTION_CANCEL_AND_DELETE)

    private fun sendAction(action: String) {
        val context = getApplication<Application>()
        val intent = Intent(context, WipeService::class.java).apply { this.action = action }
        context.startService(intent)
    }

    override fun onCleared() {
        super.onCleared()
        unbindService()
    }
}
