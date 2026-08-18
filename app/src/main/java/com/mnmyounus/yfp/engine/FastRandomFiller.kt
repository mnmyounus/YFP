package com.mnmyounus.yfp.engine

import java.security.SecureRandom

/**
 * Fills a ByteArray with pseudo-random bytes fast enough to sustain
 * multi-hundred-MB/s writes.
 *
 * java.security.SecureRandom.nextBytes() is deliberately slow (it's drawing
 * from a CSPRNG designed for key material, not bulk throughput) — on typical
 * mobile SoCs it can be the actual bottleneck instead of the storage medium
 * itself, which defeats the "maximize write speed" requirement in the spec.
 *
 * Instead this uses xorshift128, a well-known fast non-cryptographic PRNG.
 * This is the right tool for the job: the goal of the pseudo-random pattern
 * here is to make sure new bit patterns land on disk (defeating naive
 * "look for the old file's byte pattern" recovery, and defeating sparse-file
 * shortcuts some filesystems take with long runs of zeros) — not to produce
 * bytes that need to resist cryptanalysis. The seed itself is drawn once
 * from SecureRandom so the starting state isn't predictable/reproducible
 * across app runs, but the per-byte generation is intentionally the fast
 * non-crypto algorithm.
 */
class FastRandomFiller {
    private var s0: Long
    private var s1: Long

    init {
        val seedSource = SecureRandom()
        s0 = seedSource.nextLong().let { if (it == 0L) 0x9E3779B97F4A7C15UL.toLong() else it }
        s1 = seedSource.nextLong().let { if (it == 0L) 0xBF58476D1CE4E5B9UL.toLong() else it }
    }

    /** Fills [buffer] entirely with pseudo-random bytes, 8 at a time. */
    fun fill(buffer: ByteArray) {
        var i = 0
        val len = buffer.size
        // Write 8 bytes per xorshift128+ step.
        while (i + 8 <= len) {
            val rnd = nextLong()
            buffer[i] = (rnd ushr 0).toByte()
            buffer[i + 1] = (rnd ushr 8).toByte()
            buffer[i + 2] = (rnd ushr 16).toByte()
            buffer[i + 3] = (rnd ushr 24).toByte()
            buffer[i + 4] = (rnd ushr 32).toByte()
            buffer[i + 5] = (rnd ushr 40).toByte()
            buffer[i + 6] = (rnd ushr 48).toByte()
            buffer[i + 7] = (rnd ushr 56).toByte()
            i += 8
        }
        // Tail bytes (buffer length not a multiple of 8).
        if (i < len) {
            val rnd = nextLong()
            var shift = 0
            while (i < len) {
                buffer[i] = (rnd ushr shift).toByte()
                shift += 8
                i++
            }
        }
    }

    /** xorshift128+ step. */
    private fun nextLong(): Long {
        var x = s0
        val y = s1
        s0 = y
        x = x xor (x shl 23)
        x = x xor (x ushr 17)
        x = x xor y xor (y ushr 26)
        s1 = x
        return x + y
    }
}
