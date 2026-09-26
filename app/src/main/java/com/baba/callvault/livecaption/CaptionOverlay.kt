/*
 * CallVault: FOSS call recording, self-contained over embedded ADB
 *  Copyright (C) 2026-present The CallVault Authors
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package com.baba.callvault.livecaption

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.baba.callvault.utils.AppLogger

/**
 * The on-screen caption: a small, non-interactive [WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY]
 * window drawn over whatever call app is in the foreground, showing the most recently translated line.
 *
 * All view work is marshalled onto the main thread: captions arrive from a background coroutine, but a
 * [WindowManager] view may only be touched from the thread that added it. Failures here (permission
 * revoked mid-call, no window manager, etc.) are swallowed — a missing caption is not a reason to
 * disturb the call or the recording underneath it.
 */
object CaptionOverlay {

    private const val TAG = "CV:CaptionOverlay"

    /** How long a caption line stays up before clearing, if nothing newer replaces it. */
    private const val CAPTION_LINGER_MS = 6_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var view: TextView? = null
    private var clearRunnable: Runnable? = null

    /** Adds the (empty) overlay window. No-op if one is already showing. */
    fun show(context: Context) {
        mainHandler.post {
            if (view != null) return@post
            runCatching {
                val app = context.applicationContext
                val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    overlayType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    y = 220
                }
                val textView = TextView(app).apply {
                    setTextColor(Color.WHITE)
                    setBackgroundColor(Color.argb(190, 0, 0, 0))
                    textSize = 16f
                    setPadding(28, 16, 28, 16)
                    text = ""
                }
                wm.addView(textView, params)
                windowManager = wm
                view = textView
                AppLogger.i(TAG, "Caption overlay shown")
            }.onFailure { AppLogger.w(TAG, "Could not show caption overlay: ${it.message}") }
        }
    }

    /** Replaces the caption text; auto-clears after [CAPTION_LINGER_MS] if nothing newer arrives. */
    fun showCaption(text: String) {
        mainHandler.post {
            val tv = view ?: return@post
            tv.text = text
            clearRunnable?.let { mainHandler.removeCallbacks(it) }
            val runnable = Runnable { tv.text = "" }
            clearRunnable = runnable
            mainHandler.postDelayed(runnable, CAPTION_LINGER_MS)
        }
    }

    /** Removes the overlay window entirely. Safe to call even if [show] was never called. */
    fun hide() {
        mainHandler.post {
            clearRunnable?.let { mainHandler.removeCallbacks(it) }
            clearRunnable = null
            val wm = windowManager
            val v = view
            windowManager = null
            view = null
            if (wm != null && v != null) {
                runCatching { wm.removeView(v) }
                    .onFailure { AppLogger.d(TAG, "Could not remove caption overlay: ${it.message}") }
            }
        }
    }
}
