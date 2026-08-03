package com.runner.academy.ui.tracking

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.speech.tts.TextToSpeech
import android.util.Log
import com.runner.academy.R
import com.runner.academy.data.WorkoutSession
import com.runner.academy.util.FormatUtils

/**
 * Manages audio/voice feedback for the workout tracking screen.
 *
 * Responsibilities:
 * - Text-to-speech initialization and lifecycle
 * - Distance milestone announcements
 * - Interval upcoming announcements and start beep
 * - Voice feedback configuration from UserPreferences
 */
class VoiceFeedbackManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "VoiceFeedbackManager"
        private const val INTERVAL_BEEP_DURATION_MS = 900
        private const val PART_PAUSE_MS = 450L
    }

    private var tts: TextToSpeech? = null
    private var toneGenerator: ToneGenerator? = null
    private var lastDistanceKmVoiceSpoken = -1

    fun initTTS() {
        ensureToneGenerator()
        tts = TextToSpeech(context, object : TextToSpeech.OnInitListener {
            override fun onInit(status: Int) {
                if (status == TextToSpeech.SUCCESS) {
                    val locale = context.resources.configuration.locales[0]
                    when (tts?.isLanguageAvailable(locale)) {
                        TextToSpeech.LANG_AVAILABLE,
                        TextToSpeech.LANG_COUNTRY_AVAILABLE,
                        TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> {
                            Log.d(TAG, "TTS onInit: setting language ${locale.language}")
                            tts?.language = locale
                        }
                        TextToSpeech.LANG_MISSING_DATA -> {
                            Log.d(TAG, "TTS onInit: missing language data")
                            destroyTtsOnly()
                        }
                        TextToSpeech.LANG_NOT_SUPPORTED -> {
                            Log.d(TAG, "TTS onInit: language unsupported")
                            destroyTtsOnly()
                        }
                    }
                } else {
                    Log.d(TAG, "TTS onInit: failure")
                    destroyTtsOnly()
                }
            }
        })
    }

    fun resetMilestones() {
        lastDistanceKmVoiceSpoken = -1
    }

    fun notifyGpsStatus(current: com.runner.academy.data.GpsStatus, previous: com.runner.academy.data.GpsStatus?) {
        if (previous == current) return
        when {
            current == com.runner.academy.data.GpsStatus.LOST &&
                previous != null &&
                previous != com.runner.academy.data.GpsStatus.LOST -> {
                speak(context.getString(R.string.voice_gps_lost), "gps_lost")
            }
            current == com.runner.academy.data.GpsStatus.FOUND &&
                previous == com.runner.academy.data.GpsStatus.LOST -> {
                speak(context.getString(R.string.voice_gps_recovered), "gps_recovered")
            }
        }
    }

    fun notifyDistance(session: WorkoutSession) {
        val completedKm = kotlin.math.floor(session.distance.toDouble()).toInt()
        if (completedKm < 1) return
        if (completedKm <= lastDistanceKmVoiceSpoken) return

        lastDistanceKmVoiceSpoken = completedKm

        val distanceTTS = FormatUtils.formatDistanceForTTS(session.distance, context)
        val timeTTS = FormatUtils.formatTimeForTTS(session.currentTime, context)
        val paceTTS = FormatUtils.formatPaceForTTS(session.avgPace, context)

        val notification = context.getString(R.string.voice_notif_text_each_km, distanceTTS, timeTTS, paceTTS)
        speak(notification, "distance_${completedKm}km")
    }

    /**
     * Upcoming interval: title, then pause, then goal, then pause, then pace.
     */
    fun announceIntervalUpcoming(
        title: String,
        goalPart: String? = null,
        pacePart: String? = null
    ) {
        val parts = buildList {
            add(context.getString(R.string.voice_interval_upcoming_in_30s, title))
            goalPart?.takeIf { it.isNotBlank() }?.let { add(it) }
            pacePart?.takeIf { it.isNotBlank() }?.let { add(it) }
        }
        speakPartsWithPauses(parts, "interval_upcoming")
    }

    /** Long beep at the start of each interval (no speech). */
    fun playIntervalBeep() {
        try {
            ensureToneGenerator()
            toneGenerator?.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, INTERVAL_BEEP_DURATION_MS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to play interval beep", e)
            try {
                toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, INTERVAL_BEEP_DURATION_MS)
            } catch (e2: Exception) {
                Log.w(TAG, "Fallback beep failed", e2)
            }
        }
    }

    fun destroy() {
        destroyTtsOnly()
        try {
            toneGenerator?.release()
        } catch (_: Exception) {
        }
        toneGenerator = null
    }

    private fun speak(text: String, utteranceId: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
    }

    private fun speakPartsWithPauses(parts: List<String>, idPrefix: String) {
        val engine = tts ?: return
        if (parts.isEmpty()) return
        parts.forEachIndexed { index, part ->
            engine.speak(part, TextToSpeech.QUEUE_ADD, null, "${idPrefix}_$index")
            if (index < parts.lastIndex) {
                engine.playSilentUtterance(PART_PAUSE_MS, TextToSpeech.QUEUE_ADD, "${idPrefix}_pause_$index")
            }
        }
    }

    private fun destroyTtsOnly() {
        val active = tts
        if (active != null) {
            active.stop()
            active.shutdown()
            tts = null
        }
    }

    private fun ensureToneGenerator() {
        if (toneGenerator != null) return
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator unavailable", e)
            toneGenerator = null
        }
    }
}
