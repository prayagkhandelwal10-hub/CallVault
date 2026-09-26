/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.server;

/**
 * Delivers far-party VoIP audio to the app, live, during an active call — for real-time speech
 * translation while the call is still happening (as opposed to
 * com.baba.callvault.transcription.TranscriptionEngine, which runs after the file is saved).
 *
 * Implemented by the APP and handed to the daemon via
 * IRecorderService.registerLiveCaptionListener. Calls arrive on the capture loop's own thread inside
 * the privileged daemon, one slot at a time, for as long as a VoIP capture is running. `oneway` so a
 * slow or blocked listener cannot stall that thread and therefore cannot stall the recording itself —
 * the daemon fires and forgets.
 *
 * The app-side implementation should hand each chunk to its own background queue/worker immediately
 * and return, never do network I/O (or anything else slow) directly on this callback.
 */
oneway interface ILiveCaptionListener {

    /**
     * One slot of far-party audio: mono, 16-bit PCM, little-endian, 48000Hz (VoipCaptureSession's
     * capture rate) — the same bytes that slot contributes to the saved recording.
     *
     * @param pcm       Raw PCM bytes for this slot. Callers should not assume a fixed length.
     * @param slotNanos The slot's capture-time timestamp (System.nanoTime() domain, from SlotPairer),
     *                  so the app can buffer/order chunks correctly even if two deliveries race on IPC.
     */
    void onFarPartyAudio(in byte[] pcm, long slotNanos);
}
