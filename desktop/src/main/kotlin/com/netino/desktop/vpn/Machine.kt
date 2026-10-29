package com.netino.desktop.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The connection's state machine: the one place that decides which state the connection is in, and whether work
 * in flight is still wanted. Nothing here knows about Xray, ports or Windows, so it can be tested on its own.
 *
 *  - **phase**: disconnected → connecting → connected → disconnecting → disconnected. Moves not in [TRANSITIONS]
 *    are refused, so a crash landing at the same moment as a user's disconnect can't put the machine back into a
 *    state the user already left.
 *  - **epoch**: counts attempts. It goes up when a new attempt starts (entering connecting) and when the machine
 *    comes to rest (entering disconnected), so a result that arrives late can tell it belongs to an attempt that
 *    is over. connecting → connected is the same attempt and keeps the epoch.
 *  - **activity**: the one cancellable piece of long work (a server search, a reconnect countdown, a rescue). At
 *    most one runs; starting another cancels the first. That is what makes "disconnect" reliably stop work that
 *    hasn't touched the system yet.
 *  - **exclusive**: everything that touches the system (the core, the Windows proxy, routes, the firewall) runs
 *    one at a time, so a connect and a disconnect never interleave.
 */
class Machine(private val scope: CoroutineScope) {

    enum class Phase { DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING }

    private val _phase = MutableStateFlow(Phase.DISCONNECTED)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    @Volatile var epoch = 0
        private set

    /** Called after every accepted move, with the reason given. */
    var onChange: (from: Phase, to: Phase, reason: String) -> Unit = { _, _, _ -> }

    fun can(next: Phase) = next in TRANSITIONS.getValue(_phase.value)

    /** Moves to [next] if that is legal from here; false (and nothing changes) otherwise. */
    @Synchronized
    fun to(next: Phase, reason: String = ""): Boolean {
        val from = _phase.value
        if (next == from || !can(next)) return false
        _phase.value = next
        if (next == Phase.CONNECTING || next == Phase.DISCONNECTED) epoch++
        onChange(from, next, reason)
        return true
    }

    fun isCurrent(epoch: Int) = this.epoch == epoch

    // ---- the one activity ----

    /** A cancellable span of long work, of a [kind] (for the log and the UI). */
    class Activity internal constructor(val kind: String, internal val job: Job) {
        val live get() = job.isActive
        fun cancel() = job.cancel()
        /** Whether [j] is this activity's own job (code checking it runs inside the activity). */
        fun isJob(j: Job?) = j === job
        /** Whether [j] is this activity's own job (code checking it runs inside the activity). */
        fun isJob(j: Job?) = j === job
    }

    @Volatile var activity: Activity? = null
        private set

    /** The running activity, if it is still running. */
    val running: Activity? get() = activity?.takeIf { it.live }

    /** Starts [block] as the activity, cancelling the one before. [onEnd] runs when it ends, however it ends. */
    @Synchronized
    fun begin(kind: String, onEnd: () -> Unit = {}, block: suspend CoroutineScope.() -> Unit): Activity {
        activity?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        val a = Activity(kind, job)
        activity = a
        job.invokeOnCompletion { synchronized(this) { if (activity === a) activity = null }; onEnd() }
        job.start()
        return a
    }

    /** Cancels the running activity, if any; true if there was one. */
    @Synchronized
    fun cancelActivity(): Boolean {
        val a = activity ?: return false
        activity = null
        a.cancel()
        return true
    }

    /** True while [a] is the machine's activity and hasn't been cancelled: late reports from older ones are dropped. */
    fun isRunning(a: Activity?) = a != null && a === activity && a.live

    // ---- the exclusive section ----

    private val lock = Mutex()

    /** Runs [block] with nothing else in the section: queued, never concurrent. */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    companion object {
        /**
         * Every legal move:
         *  - connecting → disconnected: an attempt that failed;
         *  - connected → disconnected: the connection dropped under us (the core stopped);
         *  - connected → connecting: a new attempt while the old connection still carries traffic (switching
         *    server, quick connect while connected). A failed attempt then goes back to connected.
         */
        val TRANSITIONS: Map<Phase, Set<Phase>> = mapOf(
            Phase.DISCONNECTED to setOf(Phase.CONNECTING),
            Phase.CONNECTING to setOf(Phase.CONNECTED, Phase.DISCONNECTING, Phase.DISCONNECTED),
            Phase.CONNECTED to setOf(Phase.CONNECTING, Phase.DISCONNECTING, Phase.DISCONNECTED),
            Phase.DISCONNECTING to setOf(Phase.DISCONNECTED),
        )
    }
}

/**
 * How hard and how often to try again after the connection drops on its own: [maxAttempts] tries, waiting
 * [baseMs], then twice as long each time (capped at [capMs]). A successful connection resets the count, so a
 * flaky link that recovers each time never runs out, while one that stays down gives up.
 */
class ReconnectPolicy(val maxAttempts: Int = 5, val baseMs: Long = 2_000, val capMs: Long = 60_000) {
    var attempts = 0
        private set

    /** The wait before the next try, or null when it should give up. */
    fun next(): Long? {
        if (attempts >= maxAttempts) return null
        val wait = (baseMs shl attempts.coerceAtMost(20)).coerceAtMost(capMs)
        attempts++
        return wait
    }

    fun reset() { attempts = 0 }
}
