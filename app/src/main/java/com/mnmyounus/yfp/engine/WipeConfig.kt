package com.mnmyounus.yfp.engine

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Overwrite pattern used to fill dummy files.
 *
 * ZERO_FILL writes a buffer of all-zero bytes. It's the fastest option
 * (no per-byte generation cost, and very compressible so it's a poor choice
 * if the goal were "look like real data" — but that is not the goal here;
 * the goal is simply to make sure new, different bytes occupy the disk
 * sectors that used to hold the deleted file's bytes).
 *
 * PSEUDO_RANDOM writes bytes from a fast, non-cryptographic PRNG
 * (xorshift128, see [FastRandomFiller]). This is slower than zero-fill but
 * produces non-repeating content, which some users prefer psychologically
 * and which also defeats filesystem-level sparse-file / compression tricks
 * that could otherwise let a run of zeros not actually touch physical
 * sectors on some filesystems.
 *
 * Neither pattern is a claim of DoD 5220.22-M "military grade" erasure —
 * see the in-app disclaimer copy in strings.xml. On flash storage in
 * particular, wear-leveling means the physical NAND cells backing a given
 * logical sector are not deterministic, which is a limitation of every
 * consumer free-space-wipe tool, not something specific to this app.
 */
enum class OverwritePattern {
    ZERO_FILL,
    PSEUDO_RANDOM
}

/**
 * Where dummy files get written. SAF_TREE covers both the SD card and any
 * "custom user-specified directory" case from the spec, since on modern
 * Android both routes go through the Storage Access Framework once the
 * target is outside the app's own sandbox.
 */
enum class TargetKind {
    APP_INTERNAL,   // Context.filesDir / getExternalFilesDir — no user picker needed.
    SAF_TREE        // SD card or an arbitrary user-picked folder, via ACTION_OPEN_DOCUMENT_TREE.
}

@Parcelize
data class WipeConfig(
    val targetKind: TargetKind,
    /** Populated only when targetKind == SAF_TREE. */
    val treeUri: Uri?,
    /** Human-readable label for the target, shown in the UI (e.g. "Internal Storage", "SD Card"). */
    val targetLabel: String,
    val pattern: OverwritePattern,
    /** 1..99. Spec calls for up to 95%; kept as a config value rather than
     *  a hardcoded constant so the safety-margin discussion below has a
     *  single place to live and so a future settings screen can expose it. */
    val targetFillPercent: Int,
    /** Size of each dummy chunk file, in bytes. 1–4 GiB per the spec. */
    val chunkSizeBytes: Long
) : Parcelable {
    companion object {
        const val MIN_FILL_PERCENT = 50
        const val MAX_FILL_PERCENT = 95
        const val DEFAULT_FILL_PERCENT = 90

        const val MIN_CHUNK_MB = 1024L   // 1 GiB
        const val MAX_CHUNK_MB = 4096L   // 4 GiB
        const val DEFAULT_CHUNK_MB = 2048L // 2 GiB — good balance of few large
        // files (spec's stated goal, to avoid I/O bottlenecks from millions
        // of small files) vs. not holding one single multi-GB file open so
        // long that a Cancel mid-chunk has to discard a huge amount of work.

        val DUMMY_FILE_PREFIX = "yfp_dummy_"
        val DUMMY_FILE_SUFFIX = ".bin"
    }
}
