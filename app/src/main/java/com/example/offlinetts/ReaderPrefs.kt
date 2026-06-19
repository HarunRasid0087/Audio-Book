package com.example.offlinetts

import android.content.Context

/**
 * Lightweight persistence for reading position and user preferences.
 *
 * Stored in [android.content.SharedPreferences] so the user can close the app
 * mid-book and pick up exactly where they left off — including engine settings
 * (speed, pitch, language, voice).
 */
class ReaderPrefs(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Reading session --------------------------------------------------

    /** The full text currently loaded (so resume works after a cold start). */
    var text: String
        get() = prefs.getString(KEY_TEXT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TEXT, value).apply()

    /** Human-friendly name of the loaded document. */
    var documentName: String
        get() = prefs.getString(KEY_DOC_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_DOC_NAME, value).apply()

    /** Index of the chunk last being read — the resume point. */
    var chunkIndex: Int
        get() = prefs.getInt(KEY_CHUNK, 0)
        set(value) = prefs.edit().putInt(KEY_CHUNK, value).apply()

    val hasSession: Boolean get() = text.isNotBlank()

    fun clearSession() {
        prefs.edit()
            .remove(KEY_TEXT)
            .remove(KEY_DOC_NAME)
            .remove(KEY_CHUNK)
            .apply()
    }

    // ---- Engine preferences ----------------------------------------------

    var speed: Float
        get() = prefs.getFloat(KEY_SPEED, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_SPEED, value).apply()

    var pitch: Float
        get() = prefs.getFloat(KEY_PITCH, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_PITCH, value).apply()

    var languageTag: String
        get() = prefs.getString(KEY_LANG, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LANG, value).apply()

    var voiceName: String
        get() = prefs.getString(KEY_VOICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_VOICE, value).apply()

    companion object {
        private const val FILE = "reader_prefs"
        private const val KEY_TEXT = "session_text"
        private const val KEY_DOC_NAME = "session_doc_name"
        private const val KEY_CHUNK = "session_chunk"
        private const val KEY_SPEED = "pref_speed"
        private const val KEY_PITCH = "pref_pitch"
        private const val KEY_LANG = "pref_lang"
        private const val KEY_VOICE = "pref_voice"
    }
}
