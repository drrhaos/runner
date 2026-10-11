package com.runner.academy.sensor

/**
 * The BLE thread on a fake clock. Posted tasks run at once, in order, but never inside another
 * task (like a `Handler`); delayed ones run when [advanceBy] passes their time.
 */
class FakeLinkScheduler : LinkScheduler {

    var nowMs = 0L
        private set

    private class Delayed(val token: Any, val atMs: Long, val seq: Long, val task: () -> Unit)

    private val queue = ArrayDeque<() -> Unit>()
    private val delayed = mutableListOf<Delayed>()
    private var seq = 0L
    private var running = false

    override fun post(task: () -> Unit) {
        queue.addLast(task)
        if (running) return
        running = true
        try {
            while (queue.isNotEmpty()) queue.removeFirst()()
        } finally {
            running = false
        }
    }

    override fun postDelayed(token: Any, delayMs: Long, task: () -> Unit) {
        delayed += Delayed(token, nowMs + delayMs, seq++, task)
    }

    override fun cancel(token: Any) {
        delayed.removeAll { it.token == token }
    }

    /** Delayed tasks still waiting. */
    val pendingCount: Int get() = delayed.size

    fun advanceBy(ms: Long) {
        val target = nowMs + ms
        while (true) {
            val next = delayed.filter { it.atMs <= target }.minWithOrNull(compareBy({ it.atMs }, { it.seq })) ?: break
            delayed.remove(next)
            nowMs = next.atMs
            post(next.task)
        }
        nowMs = target
    }
}
