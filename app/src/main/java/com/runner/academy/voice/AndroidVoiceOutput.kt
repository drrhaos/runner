package com.runner.academy.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log

/**
 * The phone's voice: the system speech engine in the app's language, the segment beep and the
 * audio focus. Lives with the workout in the service, so it speaks without the screen.
 *
 * - Speech is navigation guidance ([AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE]):
 *   players duck the music for it instead of pausing.
 * - The focus ([AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK]) is taken before the first
 *   phrase of a batch and given back [FOCUS_HOLD_MS] after the last one ends, so the music does
 *   not swing up and down between the parts of a phrase. Refused (a call): the phrase is dropped,
 *   nothing is said over a conversation; lost mid-phrase: the queue is dropped.
 * - The beep plays on the music stream: as loud as the voice and heard in silent mode; it holds
 *   the focus for its length plus [FOCUS_HOLD_MS].
 * - Lines before the engine is ready wait for it (no focus meanwhile); no engine or no language
 *   data for the app's language: silence, as before.
 */
class AndroidVoiceOutput(private val context: Context) : VoiceOutput {

    companion object {
        private const val TAG = "AndroidVoiceOutput"
        const val FOCUS_HOLD_MS = 500L
        const val BEEP_MS = 900
        private const val BEEP_VOLUME = 80
        private const val PART_PAUSE_MS = 450L
        private const val SPEECH_ID = "voice_"
        private const val PAUSE_ID = "pause_"
        /** Lines kept until the engine is ready; older ones are stale by then. */
        private const val MAX_WAITING = 4
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener({ change -> onFocusChange(change) }, handler)
        .build()

    private var tts: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null
    private var focusHeld = false
    /** The engine is bound and speaks the app's language; lines before that wait in [waiting]. */
    private var ready = false
    private val waiting = ArrayDeque<VoiceLine>()
    /**
     * Ids of the spoken parts queued and not ended yet (pauses not counted: they end before the
     * next part). A late end of a part dropped by a focus loss is not in it, so it cannot give the
     * focus back in the middle of the next phrase.
     */
    private val pendingIds = mutableSetOf<String>()
    private var beepEndsAt = 0L
    private var nextId = 0L
    private val releaseFocus = Runnable { if (pendingIds.isEmpty()) abandonFocus() }

    override val busy: Boolean get() = pendingIds.isNotEmpty()

    init {
        tts = TextToSpeech(context) { status -> onInit(status) }.apply {
            setAudioAttributes(attributes)
            setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) = ended(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = ended(utteranceId)
                override fun onError(utteranceId: String?, errorCode: Int) = ended(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = ended(utteranceId)
            })
        }
    }

    private fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.d(TAG, "TTS onInit: failure")
            shutdownTts()
            return
        }
        val locale = context.resources.configuration.locales[0]
        when (tts?.isLanguageAvailable(locale)) {
            TextToSpeech.LANG_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_AVAILABLE,
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                tts?.language = locale
                ready = true
                while (waiting.isNotEmpty()) say(waiting.removeFirst())
            }
            else -> {
                Log.d(TAG, "TTS onInit: no language data for ${locale.language}")
                shutdownTts()
            }
        }
    }

    override fun say(line: VoiceLine) {
        if (line == VoiceLine.SegmentStart) {
            beep()
            return
        }
        val engine = tts ?: return
        if (!ready) {
            // The engine binds in a moment after the start: the first line waits, no focus yet
            if (waiting.size == MAX_WAITING) waiting.removeFirst()
            waiting.addLast(line)
            return
        }
        val parts = VoiceLineText.render(line, context)
        if (parts.isEmpty() || !acquireFocus()) return
        parts.forEachIndexed { index, part ->
            val id = SPEECH_ID + nextId++
            if (engine.speak(part, TextToSpeech.QUEUE_ADD, null, id) == TextToSpeech.SUCCESS) {
                pendingIds += id
            }
            if (index < parts.lastIndex) {
                engine.playSilentUtterance(PART_PAUSE_MS, TextToSpeech.QUEUE_ADD, PAUSE_ID + nextId++)
            }
        }
        // Refused by the engine: nothing will end, give the focus back
        if (pendingIds.isEmpty()) releaseLater()
    }

    override fun beep() {
        if (!acquireFocus()) return
        try {
            val tone = toneGenerator ?: ToneGenerator(AudioManager.STREAM_MUSIC, BEEP_VOLUME).also { toneGenerator = it }
            tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, BEEP_MS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Interval beep failed", e)
        }
        beepEndsAt = SystemClock.uptimeMillis() + BEEP_MS
        releaseLater()
    }

    override fun release() {
        handler.removeCallbacks(releaseFocus)
        shutdownTts()
        try {
            toneGenerator?.release()
        } catch (_: RuntimeException) {
        }
        toneGenerator = null
        pendingIds.clear()
        abandonFocus()
    }

    /** From the speech engine's thread. */
    private fun ended(utteranceId: String?) {
        if (utteranceId?.startsWith(SPEECH_ID) != true) return
        handler.post {
            if (pendingIds.remove(utteranceId) && pendingIds.isEmpty()) releaseLater()
        }
    }

    private fun acquireFocus(): Boolean {
        handler.removeCallbacks(releaseFocus)
        if (focusHeld) return true
        focusHeld = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun releaseLater() {
        handler.removeCallbacks(releaseFocus)
        val delay = maxOf(FOCUS_HOLD_MS, beepEndsAt + FOCUS_HOLD_MS - SystemClock.uptimeMillis())
        handler.postDelayed(releaseFocus, delay)
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusHeld = false
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // A call or another app took the audio: what is left of the queue is stale by then.
                // The request is abandoned for real, or the player would not get the focus back
                // after the call until our next line.
                handler.removeCallbacks(releaseFocus)
                pendingIds.clear()
                tts?.stop()
                abandonFocus()
            }
        }
    }

    private fun shutdownTts() {
        ready = false
        waiting.clear()
        tts?.let {
            it.stop()
            it.shutdown()
        }
        tts = null
    }
}
