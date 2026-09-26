/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.livecaption

import android.content.Context
import android.provider.Settings
import com.baba.callvault.data.AppPreferences
import com.baba.callvault.server.ILiveCaptionListener
import com.baba.callvault.server.RecorderConnection
import com.baba.callvault.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream

/**
 * Real-time (DURING-the-call) speech translation, layered on [VoipCaptureSession.liveCaptionSink] —
 * as opposed to [com.baba.callvault.transcription.TranscriptionEngine], which transcribes the finished
 * file after the call has already ended. This is the feature the on-device transcript cannot replace:
 * a translation that arrives after the call is over is too late to be useful in the conversation.
 *
 * Driven by [com.baba.callvault.services.recording.VoipRecordingCoordinator]'s own lifecycle — [start]
 * when a VoIP recording begins, [stop] when it ends — so captioning never runs a moment longer than the
 * recording it rides on. Entirely additive and best-effort: every failure here is caught and logged,
 * never allowed anywhere near the recording path itself. At worst a call gets no captions.
 *
 * Pipeline per audio window: [ILiveCaptionListener.onFarPartyAudio] delivers ~20ms slots from the
 * daemon -> buffered here until [WINDOW_MS] has accumulated -> wrapped as a WAV ([WavEncoder]) ->
 * sent to [SpeechTranslationClient] -> shown via [CaptionOverlay]. The window length plus one network
 * round trip is where the few seconds of caption lag comes from — inherent to speech translation, the
 * same order of magnitude as a professional simultaneous interpreter, not a defect of this pipeline.
 */
object LiveCaptionCoordinator {

    private const val TAG = "CV:LiveCaption"

    /** Matches VoipCaptureSession's capture rate; the daemon always delivers 48kHz mono PCM16. */
    private const val SAMPLE_RATE = 48_000
    private const val BYTES_PER_SAMPLE = 2

    /** How much far-party audio to batch before translating — the latency/accuracy tradeoff knob. */
    private const val WINDOW_MS = 3_000L
    private val WINDOW_BYTES = (SAMPLE_RATE * BYTES_PER_SAMPLE * WINDOW_MS / 1000L).toInt()

    /** A window with less real signal than this is silence; skip the request rather than pay for it. */
    private val MIN_LOUD_BYTES = SAMPLE_RATE * BYTES_PER_SAMPLE / 10 // ~100ms of above-threshold audio
    private const val SILENCE_THRESHOLD = 150 // matches VoipCaptureSession.FAR_SILENCE_THRESHOLD's order

    private val bufferLock = Mutex()
    private var buffer = ByteArrayOutputStream()
    private var loudBytes = 0

    @Volatile private var sessionScope: CoroutineScope? = null
    @Volatile private var listener: ILiveCaptionListener.Stub? = null
    @Volatile private var active = false

    /**
     * Starts captioning for the VoIP recording that just began, if the feature is fully configured
     * (enabled, has an API key, has the overlay permission) and a recorder is connected. Otherwise a
     * quiet no-op — this must never be the reason a call fails to record.
     */
    fun start(context: Context) {
        val prefs = AppPreferences(context)
        if (!prefs.isLiveCaptionEnabled()) return
        val apiKey = prefs.getLiveCaptionApiKey()
        if (apiKey.isNullOrBlank()) {
            AppLogger.w(TAG, "Live captioning is on but no API key is set; not starting")
            return
        }
        if (!Settings.canDrawOverlays(context)) {
            AppLogger.w(TAG, "Live captioning is on but the overlay permission is not granted; not starting")
            return
        }
        val service = RecorderConnection.service
        if (service == null) {
            AppLogger.w(TAG, "Live captioning: no recorder connection; not starting")
            return
        }

        buffer = ByteArrayOutputStream()
        loudBytes = 0
        active = true
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        sessionScope = scope
        CaptionOverlay.show(context)

        val stub = object : ILiveCaptionListener.Stub() {
            override fun onFarPartyAudio(pcm: ByteArray, slotNanos: Long) {
                // Called on the daemon's own capture-loop thread over binder — must return immediately.
                if (!active) return
                scope.launch { onChunk(apiKey, pcm) }
            }
        }
        listener = stub
        runCatching { service.registerLiveCaptionListener(stub) }
            .onFailure { AppLogger.w(TAG, "registerLiveCaptionListener failed (older daemon build?): ${it.message}") }
        AppLogger.i(TAG, "Live captioning started")
    }

    /** Stops captioning: unregisters from the daemon, cancels in-flight work, removes the overlay. */
    fun stop(context: Context) {
        if (!active) return
        active = false
        val stub = listener
        listener = null
        runCatching { RecorderConnection.service?.unregisterLiveCaptionListener(stub) }
            .onFailure { AppLogger.d(TAG, "unregisterLiveCaptionListener failed: ${it.message}") }
        sessionScope?.cancel()
        sessionScope = null
        buffer = ByteArrayOutputStream()
        CaptionOverlay.hide()
        AppLogger.i(TAG, "Live captioning stopped")
    }

    private suspend fun onChunk(apiKey: String, pcm: ByteArray) {
        val windowToSend: ByteArray? = bufferLock.withLock {
            buffer.write(pcm)
            var i = 0
            while (i + 1 < pcm.size) {
                val sample = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort().toInt()
                if ((if (sample < 0) -sample else sample) > SILENCE_THRESHOLD) loudBytes += 2
                i += 2
            }
            if (buffer.size() < WINDOW_BYTES) return@withLock null
            val out = buffer.toByteArray()
            val wasLoud = loudBytes
            buffer = ByteArrayOutputStream()
            loudBytes = 0
            if (wasLoud < MIN_LOUD_BYTES) null else out
        } ?: return

        if (!active) return
        val wav = WavEncoder.wrapPcm16(windowToSend, SAMPLE_RATE)
        val text = runCatching { SpeechTranslationClient.translate(wav, apiKey) }
            .onFailure { AppLogger.w(TAG, "translation request failed: ${it.message}") }
            .getOrNull()
        if (active && !text.isNullOrBlank()) {
            CaptionOverlay.showCaption(text)
        }
    }
}
