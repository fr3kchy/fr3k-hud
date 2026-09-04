package com.mcpintelligence.fr3k.hud.overlays

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * User preference + utility for the chat overlay's spoken-response feature.
 *
 * The toggle is read from SharedPreferences (`fr3k_hud_prefs`, key
 * `tts_enabled`). When enabled, every chat response is fed through
 * Android's built-in [TextToSpeech] engine.
 *
 * TTS is intentionally minimal:
 * - we do NOT call [TextToSpeech.setPitch] / setSpeechRate; the system
 *   picks defaults so a screen reader on the user's device still works.
 * - we DO short-circuit on empty / error responses so we never speak
 *   `opencode-zen error: ...` out loud (the user can read the transcript).
 */
object TtsPreference {
    private const val PREFS = "fr3k_hud_prefs"
    private const val KEY = "tts_enabled"

    fun isEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun setEnabled(ctx: Context, value: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY, value).apply()
    }

    /**
     * Speak [text] if TTS is enabled. Initialises the engine lazily on the
     * first call. Safe to call from a coroutine on the IO dispatcher.
     */
    fun speakIfEnabled(ctx: Context, text: String) {
        if (!isEnabled(ctx)) return
        val clean = text.trim()
        if (clean.isEmpty()) return
        // Skip obvious error / fallback messages so the user isn't told
        // "opencode-zen error: 401" verbally.
        if (clean.startsWith("opencode-zen error", ignoreCase = true)) return
        if (clean.startsWith("hermes unreachable", ignoreCase = true)) return
        getEngine(ctx).speak(clean, TextToSpeech.QUEUE_FLUSH, null, "fr3k-chat")
    }

    @Volatile
    private var cached: TextToSpeech? = null

    @Synchronized
    private fun getEngine(ctx: Context): TextToSpeech {
        cached?.let { return it }
        val tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                cached?.language = Locale.getDefault()
            }
        }
        cached = tts
        return tts
    }

    /** Release the TTS engine. Call from your activity / service onDestroy. */
    @Synchronized
    fun shutdown() {
        cached?.stop()
        cached?.shutdown()
        cached = null
    }
}
