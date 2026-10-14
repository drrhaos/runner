package com.runner.academy.voice

/** Where the voice lines go: the speech engine on a phone ([AndroidVoiceOutput]), a list in tests. */
interface VoiceOutput {

    /** Queued after what is being said; [VoiceLine.SegmentStart] is the [beep], at once. */
    fun say(line: VoiceLine)

    /** The segment-start beep, outside the speech queue. */
    fun beep()

    /** Speech is playing or queued. */
    val busy: Boolean

    fun release()
}
