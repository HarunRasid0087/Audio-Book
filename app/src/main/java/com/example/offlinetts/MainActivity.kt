package com.example.offlinetts

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.speech.tts.Voice
import android.util.Log
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.offlinetts.databinding.ActivityMainBinding
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var ttsManager: TtsManager
    private lateinit var prefs: ReaderPrefs

    private var engineReady = false
    private var isSpeaking = false
    private var isPaused = false

    private var languages: List<Locale> = emptyList()
    private var voices: List<Voice> = emptyList()
    private var spinnersInitialised = false

    private var sleepTimer: CountDownTimer? = null

    // ---- Foreground playback service --------------------------------------

    private var playbackService: PlaybackService? = null
    private var serviceBound = false

    private val serviceController = object : PlaybackService.Controller {
        override fun onPlay() = runOnUiThread {
            // From the notification, Play means "resume if paused, else start".
            if (isPaused) binding.pauseButton.performClick()
            else if (!isSpeaking) binding.playButton.performClick()
        }
        override fun onPause() = runOnUiThread { if (isSpeaking) binding.pauseButton.performClick() }
        override fun onStop() = runOnUiThread { binding.stopButton.performClick() }
        override fun onSkipNext() = runOnUiThread { binding.nextButton.performClick() }
        override fun onSkipPrevious() = runOnUiThread { binding.prevButton.performClick() }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as PlaybackService.LocalBinder).service
            playbackService?.setController(serviceController)
            serviceBound = true
            pushNotificationState()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            playbackService = null
            serviceBound = false
        }
    }

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* best-effort */ }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadDocument(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        // Guard the heavy/risky startup work. If anything in here throws, we show
        // the reason on-screen instead of letting the app vanish in a second.
        // The exact stack trace is still saved by CrashReporter for adb pull.
        try {
            prefs = ReaderPrefs(this)
            DocumentParser.init(this)
            setupTts()
            setupUi()
            restoreSession()
            bindPlaybackService()
            requestNotificationPermission()
            handleShareIntent(intent)
        } catch (t: Throwable) {
            Log.e(CrashReporter.TAG, "Startup failed in onCreate", t)
            CrashReporter.lastCrashText(this) // ensures dir exists for later pulls
            showStartupError(t)
        }
    }

    /** Friendly, non-crashing fallback so a startup bug is visible, not silent. */
    private fun showStartupError(t: Throwable) {
        val message = getString(
            R.string.startup_error,
            t.javaClass.simpleName,
            t.message ?: "no message"
        )
        runCatching { binding.statusText.text = message }
        runCatching {
            binding.playButton.isEnabled = false
            binding.openFileButton.isEnabled = false
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun bindPlaybackService() {
        val intent = Intent(this, PlaybackService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Title shown in the media notification. */
    private fun documentTitle(): String =
        prefs.documentName.ifBlank { getString(R.string.app_name) }

    /** Mirror current playback state into the foreground media notification. */
    private fun pushNotificationState() {
        if (!serviceBound) return
        val hasContent = isSpeaking || isPaused
        val subtitle = when {
            isPaused -> getString(R.string.paused)
            isSpeaking -> getString(
                R.string.reading_progress, ttsManager.index + 1, ttsManager.total
            )
            else -> getString(R.string.notif_reading)
        }
        if (hasContent) PlaybackService.start(this)
        playbackService?.update(
            title = documentTitle(),
            subtitle = subtitle,
            isPlaying = isSpeaking,
            hasContent = hasContent
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    // ---- TTS --------------------------------------------------------------

    private fun setupTts() {
        ttsManager = TtsManager(
            context = this,
            onReady = { ok ->
                engineReady = ok
                runOnUiThread {
                    binding.statusText.text =
                        if (ok) getString(R.string.engine_ready)
                        else getString(R.string.engine_failed)
                    binding.playButton.isEnabled = ok
                    if (ok) {
                        populateLanguageSpinner()
                        applyControls()
                        restoreVoicePref()
                    }
                }
            },
            onProgress = { current, total ->
                runOnUiThread {
                    binding.statusText.text = getString(R.string.reading_progress, current, total)
                    updateProgressBar(current, total)
                    prefs.chunkIndex = ttsManager.index
                    // Refresh notification subtitle with the new chunk position.
                    if (serviceBound && isSpeaking) {
                        playbackService?.update(
                            title = documentTitle(),
                            subtitle = getString(R.string.reading_progress, current, total),
                            isPlaying = true,
                            hasContent = true
                        )
                    }
                }
            },
            onDone = {
                runOnUiThread {
                    isSpeaking = false
                    isPaused = false
                    binding.statusText.text = getString(R.string.finished)
                    updateProgressBar(1, 1)
                    prefs.clearSession()
                    cancelSleepTimer()
                    updateButtons()
                    PlaybackService.stop(this)
                }
            },
            onError = { msg ->
                runOnUiThread {
                    isSpeaking = false
                    isPaused = false
                    binding.statusText.text = msg
                    updateButtons()
                }
            }
        )
    }

    // ---- UI ---------------------------------------------------------------

    private fun setupUi() {
        binding.playButton.isEnabled = false

        binding.openFileButton.setOnClickListener {
            openDocument.launch(
                arrayOf("text/plain", "application/pdf", "application/epub+zip", "*/*")
            )
        }

        binding.playButton.setOnClickListener {
            val text = binding.inputText.text?.toString().orEmpty()
            if (text.isBlank()) {
                toast(getString(R.string.empty_text))
                return@setOnClickListener
            }
            applyControls()
            // Resume from saved chunk if this exact text is the saved session.
            if (text == prefs.text && prefs.chunkIndex > 0) {
                ttsManager.load(text, prefs.chunkIndex)
                ttsManager.speakFromCurrent()
            } else {
                prefs.text = text
                prefs.chunkIndex = 0
                ttsManager.speak(text)
            }
            isSpeaking = true
            isPaused = false
            updateButtons()
        }

        binding.pauseButton.setOnClickListener {
            if (isPaused) {
                applyControls()
                ttsManager.resume()
                isPaused = false
                isSpeaking = true
            } else if (isSpeaking) {
                ttsManager.pause()
                isPaused = true
                isSpeaking = false
                binding.statusText.text = getString(R.string.paused)
            }
            updateButtons()
        }

        binding.stopButton.setOnClickListener {
            ttsManager.stop()
            isSpeaking = false
            isPaused = false
            binding.statusText.text = getString(R.string.stopped)
            updateProgressBar(0, 1)
            prefs.chunkIndex = 0
            cancelSleepTimer()
            updateButtons()
            PlaybackService.stop(this)
        }

        binding.nextButton.setOnClickListener {
            if (isSpeaking || isPaused) {
                ttsManager.skipNext()
                isSpeaking = true; isPaused = false; updateButtons()
            }
        }
        binding.prevButton.setOnClickListener {
            if (isSpeaking || isPaused) {
                ttsManager.skipPrevious()
                isSpeaking = true; isPaused = false; updateButtons()
            }
        }

        // Material Sliders (0.5x..2.0x)
        binding.speedSeek.value = prefs.speed
        binding.speedLabel.text = getString(R.string.speed_label, prefs.speed)
        binding.speedSeek.addOnChangeListener { _, value, _ ->
            binding.speedLabel.text = getString(R.string.speed_label, value)
            prefs.speed = value
            ttsManager.setSpeechRate(value)
        }

        binding.pitchSeek.value = prefs.pitch
        binding.pitchLabel.text = getString(R.string.pitch_label, prefs.pitch)
        binding.pitchSeek.addOnChangeListener { _, value, _ ->
            binding.pitchLabel.text = getString(R.string.pitch_label, value)
            prefs.pitch = value
            ttsManager.setPitch(value)
        }

        setupSleepTimer()
        setupLanguageVoiceListeners()
        updateButtons()
    }

    private fun updateProgressBar(current: Int, total: Int) {
        val pct = if (total <= 0) 0 else (current * 100 / total)
        binding.progressBar.setProgressCompat(pct, true)
        binding.percentText.text = getString(R.string.progress_percent, pct)
    }

    // ---- Document loading -------------------------------------------------

    private fun loadDocument(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        binding.statusText.text = getString(R.string.parsing)
        binding.openFileButton.isEnabled = false
        thread {
            try {
                val name = DocumentParser.displayName(this, uri)
                val text = DocumentParser.extractText(this, uri) { current, total ->
                    runOnUiThread {
                        binding.statusText.text =
                            getString(R.string.ocr_progress, current, total)
                    }
                }
                runOnUiThread {
                    binding.inputText.setText(text)
                    binding.docNameText.text = name
                    binding.statusText.text = getString(R.string.loaded_file, name)
                    binding.openFileButton.isEnabled = true
                    updateProgressBar(0, 1)
                    // New document => fresh session.
                    prefs.text = text
                    prefs.documentName = name
                    prefs.chunkIndex = 0
                }
            } catch (e: Exception) {
                runOnUiThread {
                    binding.statusText.text =
                        getString(R.string.parse_failed, e.message ?: "unknown error")
                    binding.openFileButton.isEnabled = true
                }
            }
        }
    }

    /** Handle TXT/PDF/EPUB opened from another app (Share / Open with). */
    private fun handleShareIntent(intent: Intent?) {
        val uri: Uri? = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
        if (uri != null) loadDocument(uri)
    }

    /** Restore a previous reading session on cold start. */
    private fun restoreSession() {
        if (prefs.hasSession) {
            binding.inputText.setText(prefs.text)
            binding.docNameText.text =
                prefs.documentName.ifBlank { getString(R.string.no_document) }
            if (prefs.documentName.isNotBlank()) {
                binding.statusText.text =
                    getString(R.string.resumed_session, prefs.documentName)
            }
        }
    }

    // ---- Sleep timer ------------------------------------------------------

    private fun setupSleepTimer() {
        val labels = resources.getStringArray(R.array.sleep_timer_labels)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels)
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        binding.sleepSpinner.adapter = adapter

        binding.sleepSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    val minutes = resources.getIntArray(R.array.sleep_timer_minutes)[pos]
                    cancelSleepTimer()
                    if (minutes > 0) startSleepTimer(minutes)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
    }

    private fun startSleepTimer(minutes: Int) {
        toast(getString(R.string.sleep_set, minutes))
        sleepTimer = object : CountDownTimer(minutes * 60_000L, 60_000L) {
            override fun onTick(msLeft: Long) {}
            override fun onFinish() {
                if (isSpeaking || isPaused) {
                    ttsManager.pause()
                    isPaused = true
                    isSpeaking = false
                    binding.statusText.text = getString(R.string.sleep_fired)
                    updateButtons()
                }
                binding.sleepSpinner.setSelection(0)
            }
        }.start()
    }

    private fun cancelSleepTimer() {
        sleepTimer?.cancel()
        sleepTimer = null
    }

    // ---- Language & Voice -------------------------------------------------

    private fun populateLanguageSpinner() {
        languages = ttsManager.availableLanguages()
        val labels = languages.map { it.displayName }
        binding.languageSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

        val savedTag = prefs.languageTag
        val idx = when {
            savedTag.isNotBlank() ->
                languages.indexOfFirst { it.toLanguageTag() == savedTag }
            else ->
                languages.indexOfFirst { it.language == Locale.getDefault().language }
        }.let { if (it >= 0) it else 0 }

        spinnersInitialised = false
        if (languages.isNotEmpty()) binding.languageSpinner.setSelection(idx)
        populateVoiceSpinner(languages.getOrNull(idx))
        spinnersInitialised = true
    }

    private fun populateVoiceSpinner(locale: Locale?) {
        voices = ttsManager.availableVoices(locale)
        val labels = if (voices.isEmpty()) listOf(getString(R.string.default_voice))
        else voices.map { prettyVoiceName(it) }
        binding.voiceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_item, labels
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }

    private fun restoreVoicePref() {
        val name = prefs.voiceName
        if (name.isNotBlank()) {
            ttsManager.setVoiceByName(name)
            val idx = voices.indexOfFirst { it.name == name }
            if (idx >= 0) binding.voiceSpinner.setSelection(idx)
        }
    }

    private fun setupLanguageVoiceListeners() {
        binding.languageSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    val locale = languages.getOrNull(pos) ?: return
                    ttsManager.setLanguage(locale)
                    prefs.languageTag = locale.toLanguageTag()
                    if (spinnersInitialised) populateVoiceSpinner(locale)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

        binding.voiceSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: android.view.View?, pos: Int, id: Long) {
                    val voice = voices.getOrNull(pos) ?: return
                    ttsManager.setVoice(voice)
                    prefs.voiceName = voice.name
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
    }

    private fun prettyVoiceName(v: Voice): String {
        val quality = when {
            v.quality >= Voice.QUALITY_VERY_HIGH -> "★★★"
            v.quality >= Voice.QUALITY_HIGH -> "★★"
            else -> "★"
        }
        val short = v.name.substringAfterLast('/').take(28)
        return "$short  $quality"
    }

    // ---- Helpers ----------------------------------------------------------

    private fun applyControls() {
        ttsManager.setSpeechRate(binding.speedSeek.value)
        ttsManager.setPitch(binding.pitchSeek.value)
    }

    private fun updateButtons() {
        binding.playButton.isEnabled = engineReady && !isSpeaking && !isPaused
        binding.pauseButton.isEnabled = engineReady && (isSpeaking || isPaused)
        binding.pauseButton.text =
            getString(if (isPaused) R.string.resume else R.string.pause)
        binding.pauseButton.setIconResource(
            if (isPaused) R.drawable.ic_play else R.drawable.ic_pause
        )
        binding.stopButton.isEnabled = isSpeaking || isPaused
        binding.prevButton.isEnabled = isSpeaking || isPaused
        binding.nextButton.isEnabled = isSpeaking || isPaused
        pushNotificationState()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        cancelSleepTimer()
        if (serviceBound) {
            playbackService?.setController(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
        // If the user closes the app while nothing is playing, tear the service down.
        if (!isSpeaking && !isPaused) PlaybackService.stop(this)
        ttsManager.shutdown()
        super.onDestroy()
    }
}
