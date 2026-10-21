package com.runner.academy.voice

/** A [VoiceOutput] that keeps what it was told; [onSay] sees each line as it comes. */
class FakeVoiceOutput(private val onSay: (VoiceLine) -> Unit = {}) : VoiceOutput {

    val lines = mutableListOf<VoiceLine>()
    var beeps = 0
        private set
    var released = false
        private set

    override var busy: Boolean = false

    override var canSpeak: Boolean = true

    override fun say(line: VoiceLine) {
        lines += line
        onSay(line)
    }

    override fun beep() {
        beeps++
    }

    override fun release() {
        released = true
    }
}
