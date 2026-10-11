package com.runner.academy.sensor

/** A [GattLink] that records the commands; the test plays the radio through [listener]. */
class FakeGattLink : GattLink {

    val commands = mutableListOf<LinkCommand>()
    var listener: GattLink.Listener? = null
        private set
    var stopped = false
        private set

    override var deviceName: String? = null

    override fun start(listener: GattLink.Listener) {
        this.listener = listener
    }

    override fun execute(command: LinkCommand) {
        commands += command
    }

    override fun stop() {
        stopped = true
        listener = null
    }

    /** Commands since the last call. */
    fun drain(): List<LinkCommand> = commands.toList().also { commands.clear() }

    fun event(event: LinkEvent) {
        listener!!.onEvent(event)
    }
}
