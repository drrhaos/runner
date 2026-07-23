package com.drrhaos.runner.ui.tracking

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.drrhaos.runner.R
import com.drrhaos.runner.data.WorkoutSession
import com.drrhaos.runner.util.FormatUtils

/**
 * Manages audio/voice feedback for the workout tracking screen.
 *
 * Responsibilities:
 * - Text-to-speech initialization and lifecycle
 * - Distance milestone announcements
 * - Voice feedback configuration from UserPreferences
 */
class VoiceFeedbackManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "VoiceFeedbackManager"
    }

    private var tts: TextToSpeech? = null
    private var lastDistanceKmVoiceSpoken = -1

    fun initTTS() {
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
                            destroy()
                        }
                        TextToSpeech.LANG_NOT_SUPPORTED -> {
                            Log.d(TAG, "TTS onInit: language unsupported")
                            destroy()
                        }
                    }
                } else {
                    Log.d(TAG, "TTS onInit: failure")
                    destroy()
                }
            }
        })
    }

    fun resetMilestones() {
        lastDistanceKmVoiceSpoken = -1
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
        tts?.speak(notification, TextToSpeech.QUEUE_ADD, null, "distance_${completedKm}km")
    }

    fun destroy() {
        val _tts = tts
        if (_tts != null) {
            _tts.stop()
            _tts.shutdown()
            tts = null
        }
    }
}
