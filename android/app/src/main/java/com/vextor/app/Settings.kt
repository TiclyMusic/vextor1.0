package com.vextor.app

import android.content.Context

const val DEFAULT_SYSTEM_PROMPT =
    "Sei Vextor, un assistente esperto di web design. Quando l'utente chiede un sito, " +
        "rispondi con UN SOLO file HTML completo dentro un blocco ```html, con CSS in <style> " +
        "e JavaScript in <script>. Il design deve essere pulito, moderno, responsive e accessibile. " +
        "Usa immagini placeholder da https://picsum.photos quando servono."

/** Preferenze dell'app (SharedPreferences). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("vextor", Context.MODE_PRIVATE)

    var activeModel: String?
        get() = prefs.getString("active_model", null)
        set(v) = prefs.edit().putString("active_model", v).apply()

    var customModelUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(v) = prefs.edit().putString("custom_url", v).apply()

    var systemPrompt: String
        get() = prefs.getString("system_prompt", DEFAULT_SYSTEM_PROMPT) ?: DEFAULT_SYSTEM_PROMPT
        set(v) = prefs.edit().putString("system_prompt", v).apply()

    var temperature: Float
        get() = prefs.getFloat("temperature", 0.3f)
        set(v) = prefs.edit().putFloat("temperature", v).apply()

    var topP: Float
        get() = prefs.getFloat("top_p", 0.9f)
        set(v) = prefs.edit().putFloat("top_p", v).apply()

    var maxTokens: Int
        get() = prefs.getInt("max_tokens", 6144)
        set(v) = prefs.edit().putInt("max_tokens", v).apply()

    var contextSize: Int
        get() = prefs.getInt("n_ctx", 16384)
        set(v) = prefs.edit().putInt("n_ctx", v).apply()

    var threads: Int
        get() = prefs.getInt("threads", defaultThreads())
        set(v) = prefs.edit().putInt("threads", v).apply()

    companion object {
        fun defaultThreads(): Int =
            (Runtime.getRuntime().availableProcessors() - 2).coerceIn(2, 6)
    }
}
