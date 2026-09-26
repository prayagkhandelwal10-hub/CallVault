/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.livecaption

import java.io.ByteArrayOutputStream

/**
 * Wraps raw little-endian PCM16 samples in a minimal 44-byte WAV/RIFF header.
 *
 * The translation API needs a self-describing audio file, not a bare PCM stream, and a live-caption
 * window is a few seconds of audio at most — a full media container (MediaMuxer/MediaCodec) would be
 * pure overhead here, and building one per window would compete with the recording pipeline for
 * codec instances on low-end devices. A WAV header is 44 bytes of arithmetic and nothing else.
 */
object WavEncoder {

    private const val BITS_PER_SAMPLE = 16
    private const val WAV_HEADER_BYTES = 44

    /** @param pcm raw little-endian 16-bit samples. @param channels 1 = mono (what VoIP capture uses). */
    fun wrapPcm16(pcm: ByteArray, sampleRate: Int, channels: Int = 1): ByteArray {
        val blockAlign = channels * (BITS_PER_SAMPLE / 8)
        val byteRate = sampleRate * blockAlign
        val out = ByteArrayOutputStream(WAV_HEADER_BYTES + pcm.size)

        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
            out.write((v ushr 16) and 0xFF)
            out.write((v ushr 24) and 0xFF)
        }
        fun le16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }

        ascii("RIFF")
        le32(36 + pcm.size)          // RIFF chunk size: everything after this field
        ascii("WAVE")
        ascii("fmt ")
        le32(16)                     // PCM fmt sub-chunk size
        le16(1)                      // audio format 1 = linear PCM
        le16(channels)
        le32(sampleRate)
        le32(byteRate)
        le16(blockAlign)
        le16(BITS_PER_SAMPLE)
        ascii("data")
        le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }
}
