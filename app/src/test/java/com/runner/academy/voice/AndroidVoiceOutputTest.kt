package com.runner.academy.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import android.speech.tts.TextToSpeech
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioManager
import org.robolectric.shadows.ShadowTextToSpeech
import org.robolectric.shadows.ShadowToneGenerator
import java.time.Duration

/**
 * The phone's voice against the shadow speech engine and audio manager: the audio focus around
 * a batch of phrases and the beep. The shadow engine ends each part on the main looper.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidVoiceOutputTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val audio: ShadowAudioManager = shadowOf(context.getSystemService(AudioManager::class.java))
    private lateinit var output: AndroidVoiceOutput
    private lateinit var tts: ShadowTextToSpeech

    private val summary = VoiceLine.Summary(listOf(SummaryPart.Time(60_000L), SummaryPart.CurrentHr(150)))

    @Before
    fun setUp() {
        ShadowToneGenerator.reset()
        output = AndroidVoiceOutput(context)
        tts = shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance())
    }

    @After
    fun tearDown() = output.release()

    private val looper get() = shadowOf(Looper.getMainLooper())

    @Test
    fun `two phrases in a row take the focus once and give it back 500 ms after the last part`() {
        output.say(VoiceLine.Status(StatusKind.GPS_LOST))
        val request = audio.lastAudioFocusRequest
        assertNotNull(request)
        output.say(summary)

        assertSame("one request for the batch", request, audio.lastAudioFocusRequest)
        assertEquals(listOf("GPS signal lost", "Time 1 minute", "Heart rate 150"), tts.spokenTextList)
        assertEquals(TextToSpeech.QUEUE_ADD, tts.queueMode)
        assertTrue(output.busy)

        // Every part ends now; the focus is held 500 ms more
        looper.idle()
        assertFalse(output.busy)
        looper.idleFor(Duration.ofMillis(499))
        assertNull(audio.lastAbandonedAudioFocusRequest)
        looper.idleFor(Duration.ofMillis(1))
        assertSame(request!!.audioFocusRequest, audio.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `the focus asks for ducking as navigation speech`() {
        output.say(VoiceLine.Status(StatusKind.AUTO_PAUSE))

        val request = audio.lastAudioFocusRequest!!.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK, request.focusGain)
        assertEquals(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        assertFalse(request.willPauseWhenDucked())
    }

    @Test
    fun `a refused focus (a call) drops the phrase`() {
        audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)

        output.say(summary)

        assertEquals(emptyList<String>(), tts.spokenTextList)
        assertFalse(output.busy)
    }

    @Test
    fun `a phrase during the hold keeps the focus without a new request`() {
        output.say(VoiceLine.Status(StatusKind.GPS_LOST))
        val request = audio.lastAudioFocusRequest
        looper.idle()
        looper.idleFor(Duration.ofMillis(300))

        output.say(VoiceLine.Status(StatusKind.GPS_RECOVERED))
        assertSame(request, audio.lastAudioFocusRequest)
        looper.idle()
        looper.idleFor(Duration.ofMillis(300))
        assertNull("the hold restarted", audio.lastAbandonedAudioFocusRequest)
        looper.idleFor(Duration.ofMillis(200))
        assertNotNull(audio.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `the segment start is a beep on the music stream, the focus held for it and 500 ms`() {
        output.say(VoiceLine.SegmentStart)

        assertEquals(emptyList<String>(), tts.spokenTextList)
        val tone = ShadowToneGenerator.getPlayedTones().single()
        assertEquals(Duration.ofMillis(AndroidVoiceOutput.BEEP_MS.toLong()), tone.duration())
        assertNotNull(audio.lastAudioFocusRequest)
        looper.idleFor(Duration.ofMillis(1_399))
        assertNull(audio.lastAbandonedAudioFocusRequest)
        looper.idleFor(Duration.ofMillis(1))
        assertNotNull(audio.lastAbandonedAudioFocusRequest)
    }

    @Test
    fun `losing the focus to a call drops the rest of the queue`() {
        output.say(summary)
        // The system calls the request's listener (a hidden getter)
        val request = audio.lastAudioFocusRequest!!.audioFocusRequest
        request.javaClass.getDeclaredMethod("getOnAudioFocusChangeListener").invoke(request)
            .let { it as AudioManager.OnAudioFocusChangeListener }
            .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)

        assertTrue(tts.isStopped)
        assertFalse(output.busy)
    }
}
