/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.livecaption

import com.baba.callvault.utils.AppLogger
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/**
 * Minimal client for Groq's `/openai/v1/audio/translations` endpoint.
 *
 * Groq, not OpenAI: it serves the same `whisper-large-v3` model behind an OpenAI-compatible API for
 * free — no card on file, a free-tier key from a Google/email sign-in at console.groq.com — which is
 * what makes this feature usable by anyone testing the app rather than only someone paying per minute.
 * Anyone who wants OpenAI's paid endpoint instead only has to change [ENDPOINT] and [MODEL] below and
 * paste an `sk-...` key into the same Settings field; the request shape is identical either way.
 *
 * Deliberately the translate endpoint, not `/transcriptions` + a separate translation call: it
 * transcribes non-English speech AND translates it to English text in a single round trip, which is
 * what makes a live caption feasible at all — a two-step pipeline would double the network latency
 * on top of the windowing delay that is already unavoidable. Only `whisper-large-v3` supports
 * translation on Groq (the faster `whisper-large-v3-turbo` is transcription-only).
 *
 * Hand-rolled multipart body over [HttpURLConnection], matching this codebase's existing house style
 * for network calls (see [com.baba.callvault.system.updates.GitHubReleases]) rather than adding
 * OkHttp/Retrofit as a dependency for one endpoint.
 */
object SpeechTranslationClient {

    private const val TAG = "CV:SpeechTranslation"
    private const val ENDPOINT = "https://api.groq.com/openai/v1/audio/translations"
    private const val MODEL = "whisper-large-v3"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 15_000
    private val CRLF = "\r\n".toByteArray(Charsets.US_ASCII)

    /**
     * Sends one audio window and returns its English translation, or null on any failure (network,
     * a non-2xx response, or a body with no `text`). Blocking network I/O — call off the main thread;
     * [com.baba.callvault.livecaption.LiveCaptionCoordinator] always does this from a background
     * coroutine, never from the daemon callback thread itself.
     */
    fun translate(wavBytes: ByteArray, apiKey: String): String? {
        val boundary = "CallVaultLiveCaption-${UUID.randomUUID()}"
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            conn.apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            }
            conn.outputStream.use { os ->
                fun field(name: String, value: String) {
                    os.write("--$boundary".toByteArray(Charsets.US_ASCII)); os.write(CRLF)
                    os.write("Content-Disposition: form-data; name=\"$name\"".toByteArray(Charsets.US_ASCII)); os.write(CRLF)
                    os.write(CRLF)
                    os.write(value.toByteArray(Charsets.UTF_8)); os.write(CRLF)
                }
                field("model", MODEL)
                field("response_format", "json")
                os.write("--$boundary".toByteArray(Charsets.US_ASCII)); os.write(CRLF)
                os.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"".toByteArray(Charsets.US_ASCII)
                )
                os.write(CRLF)
                os.write("Content-Type: audio/wav".toByteArray(Charsets.US_ASCII)); os.write(CRLF)
                os.write(CRLF)
                os.write(wavBytes); os.write(CRLF)
                os.write("--$boundary--".toByteArray(Charsets.US_ASCII)); os.write(CRLF)
            }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }
                .orEmpty()
            if (code !in 200..299) {
                AppLogger.w(TAG, "translation request failed: HTTP $code: ${body.take(300)}")
                return null
            }
            JSONObject(body).optString("text").takeIf { it.isNotBlank() }
        } catch (t: Throwable) {
            AppLogger.w(TAG, "translation request threw: ${t.message}")
            null
        } finally {
            conn.disconnect()
        }
    }
}
