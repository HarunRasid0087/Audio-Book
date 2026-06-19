package com.example.offlinetts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/**
 * Wrapper around Android's on-device [TextToSpeech] engine.
 *
 * Works fully OFFLINE as long as the user's selected TTS engine has the
 * relevant voice data downloaded (Settings > Accessibility > Text-to-speech).
 *
 * Text is split into chunks and spoken one-at-a-time so long documents can be
 * paused, resumed, skipped, and persisted (resume after app restart).
 */
class TtsManager(
    context: Context,
    private val onReady: (Boolean) -> Unit,
    private val onProgress: (current: Int, total: Int) -> Unit,
    private val onDone: () -> Unit,
    private val onError: (String) -> Unit
) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private var chunks: List<String> = emptyList()
    private var currentIndex = 0
    private var paused = false

    val isPaused: Boolean get() = paused
    val hasContent: Boolean get() = chunks.isNotEmpty() && currentIndex < chunks.size
    val index: Int get() = currentIndex
    val total: Int get() = chunks.size

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                tts?.language = Locale.getDefault()
                attachProgressListener()
                onReady(true)
            } else {
                onReady(false)
                onError("Failed to initialize the Text-to-Speech engine.")
            }
        }
    }

    private fun attachProgressListener() {
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                onProgress(idx + 1, chunks.size)
            }

            override fun onDone(utteranceId: String?) {
                val idx = utteranceId?.toIntOrNull() ?: return
                if (paused) return
                if (idx >= chunks.size - 1) {
                    currentIndex = chunks.size
                    onDone()
                } else {
                    currentIndex = idx + 1
                    speakChunk(currentIndex, TextToSpeech.QUEUE_FLUSH)
                }
            }

            @Deprecated("Deprecated in API level 21")
            override fun onError(utteranceId: String?) {
                onError("Playback error on chunk $utteranceId")
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                onError("Playback error (code $errorCode) on chunk $utteranceId")
            }
        })
    }

    /** Prepare text without auto-playing. Useful for restoring a saved session. */
    fun load(text: String, startIndex: Int = 0) {
        chunks = splitIntoChunks(text)
        currentIndex = startIndex.coerceIn(0, maxOf(chunks.size - 1, 0))
        paused = false
    }

    /** Speak from the start (or re-speak loaded content from [currentIndex]). */
    fun speak(text: String) {
        if (!isInitialized) {
            onError("Engine not ready yet.")
            return
        }
        if (text.isBlank()) {
            onError("Nothing to read — the text is empty.")
            return
        }
        chunks = splitIntoChunks(text)
        currentIndex = 0
        paused = false
        speakChunk(currentIndex, TextToSpeech.QUEUE_FLUSH)
    }

    /** Begin/continue speaking from the remembered [currentIndex]. */
    fun speakFromCurrent() {
        if (!isInitialized || chunks.isEmpty()) return
        paused = false
        speakChunk(currentIndex, TextToSpeech.QUEUE_FLUSH)
    }

    private fun speakChunk(index: Int, queueMode: Int) {
        val chunk = chunks.getOrNull(index) ?: return
        tts?.speak(chunk, queueMode, Bundle(), index.toString())
    }

    fun pause() {
        if (!hasContent) return
        paused = true
        tts?.stop()
    }

    fun resume() {
        if (!isInitialized || !paused || !hasContent) return
        paused = false
        speakChunk(currentIndex, TextToSpeech.QUEUE_FLUSH)
    }

    /** Jump to next chunk and keep playing. */
    fun skipNext() {
        if (chunks.isEmpty()) return
        currentIndex = (currentIndex + 1).coerceAtMost(chunks.size - 1)
        paused = false
        speakChunk(currentIndex, TextToSpeech.QUEUE_FLUSH)
    }

    /** Jump to previous chunk and keep playing. */
    fun skipPrevious() {
        if (chunks.isEmpty()) return
        currentIndex = (currentIndex - 1).coerceAtLeast(0)
        paused = false
        speakChunk(currentIndex, TextToSpeech.QUEUE_FLUSH)
    }

    fun stop() {
        paused = false
        currentIndex = 0
        chunks = emptyList()
        tts?.stop()
    }

    fun setSpeechRate(rate: Float) { tts?.setSpeechRate(rate) }
    fun setPitch(pitch: Float) { tts?.setPitch(pitch) }

    fun setLanguage(locale: Locale): Boolean {
        val result = tts?.setLanguage(locale)
        return result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
    }

    fun availableLanguages(): List<Locale> {
        val langs = tts?.availableLanguages ?: return emptyList()
        return langs.toList().sortedBy { it.displayName }
    }

    fun availableVoices(locale: Locale? = null): List<Voice> {
        val all = tts?.voices?.toList() ?: return emptyList()
        return all
            .filter { !it.isNetworkConnectionRequired }
            .filter { locale == null || it.locale.language == locale.language }
            .sortedBy { it.name }
    }

    fun setVoice(voice: Voice): Boolean = tts?.setVoice(voice) == TextToSpeech.SUCCESS

    /** Restore a voice by its stored name, if still installed. */
    fun setVoiceByName(name: String): Boolean {
        if (name.isBlank()) return false
        val match = tts?.voices?.firstOrNull { it.name == name } ?: return false
        return setVoice(match)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    private fun splitIntoChunks(text: String, maxLen: Int = 3500): List<String> {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return emptyList()
        if (clean.length <= maxLen) return listOf(clean)

        val result = mutableListOf<String>()
        val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
        val sb = StringBuilder()

        for (sentence in sentences) {
            if (sb.length + sentence.length + 1 > maxLen) {
                if (sb.isNotEmpty()) {
                    result.add(sb.toString().trim())
                    sb.clear()
                }
                if (sentence.length > maxLen) {
                    var start = 0
                    while (start < sentence.length) {
                        val end = minOf(start + maxLen, sentence.length)
                        result.add(sentence.substring(start, end))
                        start = end
                    }
                } else {
                    sb.append(sentence).append(' ')
                }
            } else {
                sb.append(sentence).append(' ')
            }
        }
        if (sb.isNotEmpty()) result.add(sb.toString().trim())
        return result
    }
}
