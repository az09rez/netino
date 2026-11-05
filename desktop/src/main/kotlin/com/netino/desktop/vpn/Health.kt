package com.netino.desktop.vpn

/**
 * Connection quality, measured rather than guessed, and the decision to move to another server. All pure: the
 * probing itself lives in the controller, so the thresholds can be tested (and changed) without a network.
 */

/**
 * One 0–100 score (higher is better) for "how good is this connection to use". The lowest delay alone is the
 * wrong answer often enough: a 60 ms server losing 8% of its requests is worse than a 110 ms one losing none.
 * Delay, jitter, loss and throughput each count; only what was measured takes part, the missing weight is shared
 * among the rest, so a delay-only test and a full measurement land on the same scale.
 */
object Score {
    private const val W_LATENCY = 0.35
    private const val W_JITTER = 0.15
    private const val W_LOSS = 0.35
    private const val W_SPEED = 0.15

    fun of(latencyMs: Double? = null, jitterMs: Double? = null, lossPct: Double? = null, downKBps: Double? = null): Int {
        val parts = listOfNotNull(
            latencyMs?.let { W_LATENCY to scale(it, good = 150.0, bad = 2000.0) },
            jitterMs?.let { W_JITTER to scale(it, good = 15.0, bad = 400.0) },
            lossPct?.let { W_LOSS to scale(it, good = 0.0, bad = 30.0) },
            downKBps?.let { W_SPEED to speed(it) },
        )
        if (parts.isEmpty()) return 0
        // Everything lost: nothing else matters
        if (lossPct != null && lossPct >= 100.0) return 0
        val total = parts.sumOf { it.first }
        return (parts.sumOf { it.first * it.second } / total * 100).toInt().coerceIn(0, 100)
    }

    /** 1 at [good] or better, 0 at [bad] or worse, straight in between. */
    private fun scale(v: Double, good: Double, bad: Double) = (1 - (v - good) / (bad - good)).coerceIn(0.0, 1.0)

    /** Download speed on a log scale: 64 KB/s → 0, 4 MB/s → 1. */
    private fun speed(kbps: Double): Double = if (kbps <= 64) 0.0 else (kotlin.math.ln(kbps / 64) / kotlin.math.ln(4096.0 / 64)).coerceIn(0.0, 1.0)
}

/**
 * The live connection's last [size] probes (delay in ms, or ≤ 0 for a failed one), as a rolling window: one hiccup
 * can't pass for a bad connection.
 */
class HealthWindow(private val size: Int = 10) {
    private val samples = ArrayDeque<Long>()

    @Synchronized fun add(ms: Long) { samples.addLast(ms); while (samples.size > size) samples.removeFirst() }
    @Synchronized fun clear() = samples.clear()

    data class Stats(val samples: Int, val latencyMs: Double?, val jitterMs: Double?, val lossPct: Double, val score: Int, val lastFailed: Int)

    @Synchronized fun stats(): Stats? {
        if (samples.isEmpty()) return null
        val ok = samples.filter { it > 0 }.map { it.toDouble() }
        val latency = ok.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val jitter = if (ok.size < 2) null else ok.zipWithNext { a, b -> kotlin.math.abs(a - b) }.average()
        val loss = 100.0 * (samples.size - ok.size) / samples.size
        val lastFailed = samples.reversed().takeWhile { it <= 0 }.size
        return Stats(samples.size, latency, jitter, loss, Score.of(latency, jitter, loss), lastFailed)
    }
}

/**
 * Moving to another server: very reluctant to say yes, because a client that hops between two servers every
 * minute is worse than one that never moves. Four brakes:
 *  1. [badChecks] bad probes in a row before anything happens (one bad sample is a hiccup);
 *  2. the other server must be clearly better: [margin] points more on the same 0–100 scale;
 *  3. after a switch, [cooldownMs] of nothing (unless the connection is dead);
 *  4. the server just left is off the table for the cooldown, so A → B → A can't happen.
 */
class Failover(val badChecks: Int = 3, val margin: Int = 15, val cooldownMs: Long = 5 * 60_000L) {
    private var badStreak = 0
    private var lastSwitchAt = 0L
    private var left: String? = null

    /** A probe of the live connection: bad below [BAD_SCORE] or when it failed. */
    fun observe(score: Int, failed: Boolean) { badStreak = if (failed || score < BAD_SCORE) badStreak + 1 else 0 }

    /**
     * Where to move, if anywhere: the best of [candidates] (id to score) that passes every brake. [dead]: the
     * connection answers nothing any more (the cooldown doesn't hold it back then).
     */
    fun decide(now: Long, current: String?, currentScore: Int, candidates: Map<String, Int>, dead: Boolean): String? {
        if (badStreak < badChecks) return null
        if (!dead && now - lastSwitchAt < cooldownMs) return null
        val banned = left.takeIf { now - lastSwitchAt < cooldownMs }
        val floor = (if (dead) 0 else currentScore) + margin
        return candidates.filterKeys { it != current && it != banned }.filterValues { it >= floor }.maxByOrNull { it.value }?.key
    }

    /** A switch happened (from [from]): the cooldown and the return ban start now. */
    fun switched(now: Long, from: String?) { lastSwitchAt = now; left = from; badStreak = 0 }

    fun reset() { badStreak = 0 }

    companion object {
        const val BAD_SCORE = 40

        /** The three temperaments offered in settings. */
        fun preset(name: String) = when (name) {
            "calm" -> Failover(badChecks = 5, margin = 25, cooldownMs = 10 * 60_000L)
            "quick" -> Failover(badChecks = 2, margin = 10, cooldownMs = 2 * 60_000L)
            else -> Failover()
        }
    }
}
