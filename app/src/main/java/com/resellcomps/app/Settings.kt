package com.resellcomps.app

import android.content.Context

/** The key lives in this app's private storage on the phone and is only sent to its own service. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Free AI identification: a Groq (gsk_…) or OpenRouter (sk-or-…) key. */
    var freeAiKey: String
        get() = prefs.getString("free_ai_key", "").orEmpty()
        set(v) = prefs.edit().putString("free_ai_key", v.trim()).apply()

    /** What goes with a photo sent to Google Lens; empty means "my search words". */
    var lensQuestion: String
        get() = prefs.getString("lens_question", "").orEmpty()
        set(v) = prefs.edit().putString("lens_question", v).apply()

    /** Your own AI server (e.g. Ollama on a home PC via Tailscale); empty = not used. */
    var serverUrl: String
        get() = prefs.getString("server_url", "").orEmpty()
        set(v) = prefs.edit().putString("server_url", v.trim()).apply()

    /** Use the on-phone AI (Gemini Nano) when the free cloud limit runs out or there's no signal. */
    var phoneAi: Boolean
        get() = prefs.getBoolean("phone_ai", true)
        set(v) = prefs.edit().putBoolean("phone_ai", v).apply()

    /** Run "Dig deeper on the tag" automatically after each scan that shows a tag. */
    var autoDigDeeper: Boolean
        get() = prefs.getBoolean("auto_dig_deeper", true)
        set(v) = prefs.edit().putBoolean("auto_dig_deeper", v).apply()

    /** Camera shoots square (1:1) photos instead of the full frame. */
    var squarePhotos: Boolean
        get() = prefs.getBoolean("square_photos", false)
        set(v) = prefs.edit().putBoolean("square_photos", v).apply()

    var seenSettings: Boolean
        get() = prefs.getBoolean("seen_settings", false)
        set(v) = prefs.edit().putBoolean("seen_settings", v).apply()
}
