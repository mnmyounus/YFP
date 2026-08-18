package com.mnmyounus.yfp.ui.tv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.mnmyounus.yfp.R
import com.mnmyounus.yfp.databinding.ActivityTvMainBinding
import com.mnmyounus.yfp.engine.OverwritePattern
import com.mnmyounus.yfp.engine.TargetKind
import com.mnmyounus.yfp.engine.WipeState
import com.mnmyounus.yfp.ui.common.WipeViewModel
import com.mnmyounus.yfp.util.StorageInfo
import com.mnmyounus.yfp.util.VolumeInfo

/**
 * Android TV / Leanback entry point.
 *
 * Deliberately built as focusable full-width row buttons (each a single
 * D-pad-selectable target) rather than reusing the mobile layout's Spinner
 * and RadioGroup, which are awkward or unusable with D-pad-only input —
 * a Spinner's dropdown especially tends to trap focus in ways that are
 * confusing without a touchscreen or pointer.
 *
 * Shares WipeViewModel (and therefore WipeService) with MainActivity: the
 * underlying wipe job and its state are identical regardless of which UI
 * started or is currently observing it, matching a device where a user
 * might start a wipe from the phone-like UI (if sideloaded oddly) and check
 * on it from the TV UI, or more realistically, just so there's exactly one
 * implementation of "how does Start/Pause/Resume/Cancel actually work".
 */
class TvMainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTvMainBinding
    private val viewModel: WipeViewModel by viewModels()

    private var volumes: List<VolumeInfo> = emptyList()
    private var selectedVolume: VolumeInfo? = null
    private var grantedTreeUri: Uri? = null
    private var selectedPattern: OverwritePattern = OverwritePattern.PSEUDO_RANDOM

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                grantedTreeUri = uri
                binding.buttonStart.isEnabled = true
                binding.textSelectedTarget.text = getString(
                    R.string.target_selected_format,
                    selectedVolume?.label ?: getString(R.string.target_custom_folder)
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTvMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setUpTargetRows()
        setUpPatternToggle()
        setUpControls()
        setUpUtilityLinks()
        observeViewModel()

        // Land initial D-pad focus on the first actionable row so a user
        // with only a remote in hand isn't stuck with no visible focus
        // highlight on launch.
        binding.buttonTargetInternal.requestFocus()
    }

    override fun onStart() {
        super.onStart()
        viewModel.bindService()
    }

    private fun setUpTargetRows() {
        volumes = StorageInfo.listVolumes(this)
        val internalVol = volumes.firstOrNull { it.isPrimary }
        val removableVol = volumes.firstOrNull { !it.isPrimary }

        binding.buttonTargetInternal.text = getString(
            R.string.tv_target_internal_format,
            StorageInfo.formatBytes(internalVol?.freeBytes ?: 0L)
        )
        binding.buttonTargetInternal.setOnClickListener {
            selectedVolume = internalVol
            grantedTreeUri = null
            binding.textSelectedTarget.text = getString(R.string.target_selected_format, internalVol?.label ?: "Internal Storage")
            binding.buttonStart.isEnabled = true
        }

        if (removableVol != null) {
            binding.buttonTargetRemovable.visibility = View.VISIBLE
            binding.buttonTargetRemovable.text = getString(
                R.string.tv_target_removable_format,
                removableVol.label,
                StorageInfo.formatBytes(removableVol.freeBytes)
            )
            binding.buttonTargetRemovable.setOnClickListener {
                selectedVolume = removableVol
                binding.buttonStart.isEnabled = false
                binding.textSelectedTarget.text = getString(R.string.target_needs_permission_format, removableVol.label)
                openDocumentTreeLauncher.launch(null)
            }
        } else {
            binding.buttonTargetRemovable.visibility = View.GONE
        }

        // Default selection so a user who just mashes "Start" without
        // exploring gets a sane, always-available target.
        selectedVolume = internalVol
        binding.textSelectedTarget.text = getString(R.string.target_selected_format, internalVol?.label ?: "Internal Storage")
    }

    private fun setUpPatternToggle() {
        updatePatternButtons()
        binding.buttonPatternRandom.setOnClickListener {
            selectedPattern = OverwritePattern.PSEUDO_RANDOM
            updatePatternButtons()
        }
        binding.buttonPatternZero.setOnClickListener {
            selectedPattern = OverwritePattern.ZERO_FILL
            updatePatternButtons()
        }
    }

    private fun updatePatternButtons() {
        binding.buttonPatternRandom.isSelected = selectedPattern == OverwritePattern.PSEUDO_RANDOM
        binding.buttonPatternZero.isSelected = selectedPattern == OverwritePattern.ZERO_FILL
    }

    private fun setUpControls() {
        binding.buttonStart.setOnClickListener {
            val vol = selectedVolume ?: return@setOnClickListener
            val kind = if (vol.isPrimary) TargetKind.APP_INTERNAL else TargetKind.SAF_TREE
            val treeUri = if (kind == TargetKind.SAF_TREE) grantedTreeUri else null
            if (kind == TargetKind.SAF_TREE && treeUri == null) {
                Toast.makeText(this, R.string.toast_grant_access_first, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            viewModel.startWipe(
                targetKind = kind,
                treeUri = treeUri,
                targetLabel = vol.label,
                pattern = selectedPattern
            )
        }
        binding.buttonPause.setOnClickListener { viewModel.pause() }
        binding.buttonResume.setOnClickListener { viewModel.resume() }
        binding.buttonCancelDelete.setOnClickListener { viewModel.cancelAndDelete() }
    }

    private fun setUpUtilityLinks() {
        binding.buttonCheckUpdates.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.github_releases_url))))
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.toast_no_browser_found, Toast.LENGTH_SHORT).show()
            }
        }
        binding.buttonContactDeveloper.setOnClickListener {
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${getString(R.string.developer_contact_email)}"))
            try {
                startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, R.string.toast_no_email_app_found, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.state.observe(this) { state -> renderState(state) }
        viewModel.progress.observe(this) { progress ->
            binding.textProgressPercent.text = getString(R.string.progress_percent_format, progress.percentComplete)
            binding.progressBar.progress = progress.percentComplete
            binding.textWrittenTotals.text = getString(
                R.string.progress_written_format,
                StorageInfo.formatBytes(progress.bytesWrittenTotal),
                StorageInfo.formatBytes(progress.bytesTarget)
            )
            binding.textSpeed.text = getString(
                R.string.progress_speed_format,
                progress.currentWriteSpeedBytesPerSec / (1024.0 * 1024.0)
            )
        }
    }

    private fun renderState(state: WipeState) {
        val isIdle = state is WipeState.Idle
        val isRunning = state is WipeState.Running
        val isPaused = state is WipeState.Paused
        val isTerminal = state is WipeState.Completed || state is WipeState.Cancelled || state is WipeState.Failed

        binding.groupTargetSelection.visibility = if (isIdle) View.VISIBLE else View.GONE
        binding.groupProgress.visibility = if (isRunning || isPaused || isTerminal) View.VISIBLE else View.GONE

        binding.buttonStart.visibility = if (isIdle) View.VISIBLE else View.GONE
        binding.buttonPause.visibility = if (isRunning) View.VISIBLE else View.GONE
        binding.buttonResume.visibility = if (isPaused) View.VISIBLE else View.GONE
        binding.buttonCancelDelete.visibility = if (isRunning || isPaused || isTerminal) View.VISIBLE else View.GONE

        // When the state changes such that a previously-focused control is
        // now GONE, move focus to whatever is now the primary action so the
        // D-pad selection highlight never silently disappears (which would
        // strand a TV user with no visible way to see what's focused).
        when {
            isRunning -> binding.buttonPause.requestFocus()
            isPaused -> binding.buttonResume.requestFocus()
            isTerminal -> binding.buttonCancelDelete.requestFocus()
        }

        binding.textStatusLine.text = when (state) {
            is WipeState.Idle -> getString(R.string.status_idle)
            is WipeState.Running -> getString(R.string.status_running)
            is WipeState.Paused -> getString(R.string.status_paused)
            is WipeState.Completed -> if (state.hitStorageLimit) {
                getString(R.string.status_completed_storage_limit)
            } else {
                getString(R.string.status_completed)
            }
            is WipeState.Cancelled -> getString(R.string.status_cancelled)
            is WipeState.Failed -> getString(R.string.status_failed_format, state.message)
        }
    }
}
