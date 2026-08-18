package com.mnmyounus.yfp.engine

data class CleanupResult(
    val filesDeleted: Int,
    val filesFailedToDelete: Int
)

/**
 * Deletes every dummy file this app has previously written to [target].
 * Used both by the explicit "Delete" button (purge everything, keep no
 * reclaimed-but-still-full-of-garbage files around) and internally right
 * after a Cancel, so cancelling a wipe actually gives the user their space
 * back immediately rather than leaving partial multi-GB files sitting there.
 *
 * Deliberately re-lists from disk (via WipeTarget.listExistingDummyFiles())
 * rather than only deleting files the *current* WipeEngine instance knows
 * about, so that dummy files left over from a previous run that crashed or
 * was killed by the OS still get cleaned up correctly.
 */
object DummyFileCleaner {
    fun purgeAll(target: WipeTarget): CleanupResult {
        val files = target.listExistingDummyFiles()
        var deleted = 0
        var failed = 0
        for (f in files) {
            if (f.delete()) deleted++ else failed++
        }
        return CleanupResult(deleted, failed)
    }
}
