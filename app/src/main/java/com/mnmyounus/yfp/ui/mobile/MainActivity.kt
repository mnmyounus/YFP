package com.mnmyounus.yfp.ui.mobile

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.mnmyounus.yfp.R
import com.mnmyounus.yfp.databinding.ActivityMainBinding
import com.mnmyounus.yfp.engine.OverwritePattern
import com.mnmyounus.yfp.engine.TargetKind
import com.mnmyounus.yfp.engine.WipeConfig
import com.mnmyounus.yfp.engine.WipeState
import com.mnmyounus.yfp.ui.common.WipeViewModel
import com.mnmyounus.yfp.util.StorageInfo
import com.mnmyounus.yfp.util.VolumeInfo

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: WipeViewModel by viewModels()

    private var volumes: List<VolumeInfo> = emptyList()
    private var selectedVolume: VolumeInfo? = null
    /** Set once the user has actually granted a SAF tree (for SD card /
     *  custom folder); null means "no non-internal target picked yet". */
    private var grantedTreeUri: Uri? = null
    private var grantedTreeLabel: String? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way — see requestNotificationPermissionIfNeeded() */ }

    private val openDocumentTreeLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                // Persist the grant so it survives process death / device
                // reboot — without this, the SAF permission silently expires
                // and a resumed wipe job would fail with a permission error.
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                grantedTreeUri = uri
                grantedTreeLabel = selectedVolume?.label ?: getString(R.string.target_custom_folder)
                binding.textSelectedTarget.text = getString(R.string.target_selected_format, grantedTreeLabel)
                binding.buttonStart.isEnabled = true
            } else {
                Toast.makeText(this, R.string.toast_folder_selection_cancelled, Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        setUpTargetSelection()
        setUpPatternSelection()
        setUpControls()
        setUpUtilityLinks()
        observeViewModel()
    }

    override fun onStart() {
        super.onStart()
        viewModel.bindService()
    }

    override fun onStop() {
        super.onStop()
        // Deliberately NOT unbinding here: the whole point of the foreground
        // service is that the wipe keeps running (and stays controllable)
        // if the user backgrounds the app. WipeViewModel.onCleared() (tied
        // to this Activity's real lifecycle end, not a transient onStop)
        // unbinds — see WipeViewModel.
    }

    // ---- Target selection ----

    private fun setUpTargetSelection() {
        volumes = StorageInfo.listVolumes(this)
        val labels = volumes.map { v ->
            "${v.label} — ${StorageInfo.formatBytes(v.freeBytes)} free"
        }

        binding.spinnerTarget.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, labels
        )

        binding.spinnerTarget.setSelection(0)
        selectedVolume = volumes.firstOrNull()

        binding.spinnerTarget.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                selectedVolume = volumes.getOrNull(position)
                grantedTreeUri = null // switching target invalidates any prior SAF grant selection
                onTargetSelectionChanged()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        binding.buttonBrowseCustomFolder.setOnClickListener {
            openDocumentTreeLauncher.launch(null)
        }

        onTargetSelectionChanged()
    }

    private fun onTargetSelectionChanged() {
        val vol = selectedVolume ?: return
        if (vol.isPrimary) {
            // Internal storage needs no SAF grant — writes go straight into
            // the app's own external-files sandbox.
            binding.textSelectedTarget.text = getString(R.string.target_selected_format, vol.label)
            binding.buttonStart.isEnabled = true
            binding.buttonBrowseCustomFolder.visibility = android.view.View.GONE
        } else {
            // Removable/SD volume: still need the user to actually grant a
            // tree via SAF before we can write — merely appearing in
            // StorageManager.storageVolumes is not itself a write grant.
            binding.textSelectedTarget.text = getString(R.string.target_needs_permission_format, vol.label)
            binding.buttonStart.isEnabled = false
            binding.buttonBrowseCustomFolder.visibility = android.view.View.VISIBLE
            binding.buttonBrowseCustomFolder.text = getString(R.string.button_grant_access_to, vol.label)
        }
    }

    // ---- Pattern selection ----

    private fun setUpPatternSelection() {
        binding.radioGroupPattern.check(R.id.radio_pattern_random)
    }

    private fun selectedPattern(): OverwritePattern =
        if (binding.radioGroupPattern.checkedRadioButtonId == R.id.radio_pattern_zero)
            OverwritePattern.ZERO_FILL else OverwritePattern.PSEUDO_RANDOM

    // ---- Controls ----

    private fun setUpControls() {
        binding.buttonStart.setOnClickListener { confirmAndStart() }
        binding.buttonPause.setOnClickListener { viewModel.pause() }
        binding.buttonResume.setOnClickListener { viewModel.resume() }
        binding.buttonCancelDelete.setOnClickListener { confirmCancelAndDelete() }
    }

    private fun confirmAndStart() {
        val vol = selectedVolume ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_confirm_start_title)
            .setMessage(getString(R.string.dialog_confirm_start_message, WipeConfig.DEFAULT_FILL_PERCENT, vol.label))
            .setPositiveButton(R.string.action_start) { _, _ -> startWipe(vol) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun startWipe(vol: VolumeInfo) {
        val kind = if (vol.isPrimary) TargetKind.APP_INTERNAL else TargetKind.SAF_TREE
        val treeUri = if (kind == TargetKind.SAF_TREE) grantedTreeUri else null
        val label = if (kind == TargetKind.SAF_TREE) (grantedTreeLabel ?: vol.label) else vol.label

        if (kind == TargetKind.SAF_TREE && treeUri == null) {
            Toast.makeText(this, R.string.toast_grant_access_first, Toast.LENGTH_SHORT).show()
            return
        }

        viewModel.startWipe(
            targetKind = kind,
            treeUri = treeUri,
            targetLabel = label,
            pattern = selectedPattern()
        )
    }

    private fun confirmCancelAndDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_confirm_delete_title)
            .setMessage(R.string.dialog_confirm_delete_message)
            .setPositiveButton(R.string.action_delete) { _, _ -> viewModel.cancelAndDelete() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- Utility links ----

    private fun setUpUtilityLinks() {
        binding.buttonCheckUpdates.setOnClickListener {
            openExternalUrl(getString(R.string.github_releases_url))
        }
        binding.buttonContactDeveloper.setOnClickListener {
            openMailto(getString(R.string.developer_contact_email))
        }
    }

    private fun openExternalUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_browser_found, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openMailto(email: String) {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")).apply {
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.email_subject_contact_developer))
        }
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_email_app_found, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Notification permission (Android 13+) ----

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // ---- ViewModel observation ----

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
            val eta = progress.estimatedSecondsRemaining
            binding.textEta.text = if (eta != null) formatEta(eta) else getString(R.string.eta_calculating)
        }
    }

    private fun renderState(state: WipeState) {
        val isIdle = state is WipeState.Idle
        val isRunning = state is WipeState.Running
        val isPaused = state is WipeState.Paused
        val isTerminal = state is WipeState.Completed || state is WipeState.Cancelled || state is WipeState.Failed

        binding.groupTargetSelection.visibility = if (isIdle) android.view.View.VISIBLE else android.view.View.GONE
        binding.groupProgress.visibility = if (isRunning || isPaused || isTerminal) android.view.View.VISIBLE else android.view.View.GONE

        binding.buttonStart.visibility = if (isIdle) android.view.View.VISIBLE else android.view.View.GONE
        binding.buttonPause.visibility = if (isRunning) android.view.View.VISIBLE else android.view.View.GONE
        binding.buttonResume.visibility = if (isPaused) android.view.View.VISIBLE else android.view.View.GONE
        binding.buttonCancelDelete.visibility = if (isRunning || isPaused || isTerminal) android.view.View.VISIBLE else android.view.View.GONE

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

    private fun formatEta(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
        else String.format("%d:%02d", m, s)
    }
}
