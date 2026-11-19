package com.sweepscan.hid

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingDeque

/** LINK: the connection dropped with barcodes still queued; the user checks the PC and resumes. */
enum class PauseReason { UNCERTAIN, USER, LINK }

/**
 * The barcode queue, one barcode at a time, driven by [step] from the sender thread.
 *
 * After an [HidTypist.Outcome.UNCERTAIN] barcode the queue stops until the user resumes: the ERP
 * field may hold half-typed digits, and the next barcode would be appended to them. It also stops
 * when the link drops between barcodes: after a reconnect nobody knows which window has focus.
 */
class HidSender(
    private val typist: HidTypist,
    private val timing: () -> HidTiming,
    private val listener: Listener,
    private val events: (HidEvent) -> Unit,
    private val sleep: (Long) -> Unit,
    private val clock: () -> Long,
) {
    interface Listener {
        /** Called on the sender thread right before the first key, so the caller can journal it. */
        fun onStarted(entryId: Long)
        /** Nothing reached the PC; the barcode is back in the queue. */
        fun onNotStarted(entryId: Long)
        fun onSent(entryId: Long)
        fun onUncertain(entryId: Long, text: String, result: HidTypist.Result)
        fun onPauseChanged(reason: PauseReason?)
    }

    private class Job(val id: Long, val text: String)

    private val queue = LinkedBlockingDeque<Job>()
    private val queued = ConcurrentHashMap.newKeySet<Long>()
    /** Values already typed in this send run; reset when the queue runs dry. */
    private val typedValues = HashSet<String>()
    private var wasConnected = false
    private var lastReportMs = 0L

    @Volatile var paused: PauseReason? = null
        private set

    val pending: Int get() = queue.size

    fun enqueue(entryId: Long, text: String) {
        if (queued.add(entryId)) queue.offer(Job(entryId, text))
    }

    /** Removes a barcode that has not started typing. False if it is typing, done, or unknown. */
    fun cancel(entryId: Long): Boolean {
        val removed = queue.removeIf { it.id == entryId }
        if (removed) queued.remove(entryId)
        return removed
    }

    fun isQueued(entryId: Long) = entryId in queued

    fun pause() = setPaused(PauseReason.USER)

    fun resume() = setPaused(null)

    /** One iteration of the send loop. */
    fun step(connected: Boolean) {
        if (!connected) {
            if (wasConnected && paused == null && queue.isNotEmpty()) setPaused(PauseReason.LINK)
            wasConnected = false
            sleep(IDLE_MS)
            return
        }
        if (!wasConnected) {
            // Fresh connection: make sure no key is left down on the PC, then let it settle.
            wasConnected = true
            typist.releaseAll()
            lastReportMs = clock()
            events(HidEvent("reconnect_release"))
            sleep(RECONNECT_SETTLE_MS)
            return
        }
        if (paused != null) {
            sleep(IDLE_MS)
            return
        }
        val job = queue.pollFirst()
        if (job == null) {
            typedValues.clear()
            if (clock() - lastReportMs > KEEPALIVE_MS) {
                if (!typist.releaseAll()) events(HidEvent("keepalive_fail"))
                lastReportMs = clock()
            }
            sleep(IDLE_MS)
            return
        }

        val t = timing()
        events(HidEvent("job_start", id = job.id, len = job.text.length))
        listener.onStarted(job.id)
        val started = clock()
        val result = typist.type(job.text, t)
        val typeMs = clock() - started
        lastReportMs = clock()
        when (result.outcome) {
            HidTypist.Outcome.NOT_STARTED -> {
                queue.offerFirst(job)
                listener.onNotStarted(job.id)
                events(HidEvent("not_started", id = job.id, reason = result.reason, typeMs = typeMs))
                sleep(NOT_STARTED_RETRY_MS)
            }
            HidTypist.Outcome.SENT -> {
                queued.remove(job.id)
                val gap = t.gapAfter(firstOfValue = typedValues.add(job.text))
                events(HidEvent("sent", id = job.id, len = job.text.length, typed = result.typed, enter = true, typeMs = typeMs, gapMs = gap))
                listener.onSent(job.id)
                sleep((gap - t.confirmGraceMs).coerceAtLeast(0))
            }
            HidTypist.Outcome.UNCERTAIN -> {
                queued.remove(job.id)
                events(
                    HidEvent(
                        "uncertain", id = job.id, len = job.text.length, typed = result.typed,
                        enter = result.enterSent, reason = result.reason, typeMs = typeMs,
                    ),
                )
                listener.onUncertain(job.id, job.text, result)
                setPaused(PauseReason.UNCERTAIN)
            }
        }
    }

    private fun setPaused(reason: PauseReason?) {
        if (paused == reason) return
        paused = reason
        events(HidEvent(if (reason == null) "resume" else "pause", reason = reason?.name))
        listener.onPauseChanged(reason)
    }

    private companion object {
        const val IDLE_MS = 200L
        const val RECONNECT_SETTLE_MS = 1000L
        const val NOT_STARTED_RETRY_MS = 500L
        const val KEEPALIVE_MS = 5000L
    }
}
