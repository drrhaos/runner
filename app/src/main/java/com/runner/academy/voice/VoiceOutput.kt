package com.runner.academy.voice

/** Where the voice lines go: the speech engine on a phone ([AndroidVoiceOutput]), a list in tests. */
interface VoiceOutput {

    /** Queued after what is being said; [VoiceLine.SegmentStart] is the [beep], at once. */
    fun say(line: VoiceLine)

    /** The segment-start beep, outside the speech queue. */
    fun beep()

    /** Speech is playing or queued, or waits for the engine to be ready. */
    val busy: Boolean

    /**
     * Speech can be heard at all: false once the engine failed (no engine, no language data) or
     * was released. The target hints count as not spoken then, so TalkBack takes them.
     */
    val canSpeak: Boolean

    fun release()
}
