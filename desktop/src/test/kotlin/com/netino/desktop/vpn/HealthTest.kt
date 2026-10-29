package com.netino.desktop.vpn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreTest {
    @Test fun lossBeatsAFewMilliseconds() {
        val fastButLossy = Score.of(latencyMs = 60.0, jitterMs = 10.0, lossPct = 8.0)
        val slowerButSteady = Score.of(latencyMs = 110.0, jitterMs = 10.0, lossPct = 0.0)
        assertTrue(slowerButSteady > fastButLossy, "$slowerButSteady should beat $fastButLossy")
    }

    @Test fun missingMetricsShareTheirWeight() {
        assertEquals(100, Score.of(latencyMs = 100.0))
        assertEquals(0, Score.of(latencyMs = 3000.0))
        assertEquals(0, Score.of(latencyMs = 100.0, lossPct = 100.0), "everything lost is worthless")
        assertEquals(0, Score.of())
    }
}

class HealthWindowTest {
    @Test fun rollingStats() {
        val w = HealthWindow(size = 4)
        listOf(100L, 120L, -1L, 110L, 130L).forEach(w::add)   // the first falls out
        val s = w.stats()!!
        assertEquals(4, s.samples)
        assertEquals(25.0, s.lossPct)
        assertEquals(0, s.lastFailed)
        w.add(-1); w.add(-1)
        assertEquals(2, w.stats()!!.lastFailed)
    }
}

class FailoverTest {
    @Test fun oneBadSampleIsAHiccup() {
        val f = Failover(badChecks = 3, margin = 15, cooldownMs = 1000)
        f.observe(score = 10, failed = false)
        assertNull(f.decide(10_000, "a", 10, mapOf("b" to 90), dead = false))
    }

    @Test fun needsAClearMarginAndBansTheWayBack() {
        val f = Failover(badChecks = 2, margin = 15, cooldownMs = 1000)
        repeat(2) { f.observe(score = 30, failed = false) }
        assertNull(f.decide(10_000, "a", 30, mapOf("b" to 40), dead = false), "40 isn't clearly better than 30")
        assertEquals("b", f.decide(10_000, "a", 30, mapOf("b" to 60), dead = false))
        f.switched(10_000, from = "a")
        repeat(2) { f.observe(score = 20, failed = false) }
        assertNull(f.decide(10_500, "b", 20, mapOf("a" to 90), dead = false), "cooldown")
        assertNull(f.decide(10_500, "b", 0, mapOf("a" to 90), dead = true), "a dead link skips the cooldown, but not the ban on going back")
        assertEquals("c", f.decide(10_500, "b", 0, mapOf("a" to 90, "c" to 50), dead = true))
        assertEquals("a", f.decide(12_000, "b", 20, mapOf("a" to 90), dead = false), "after the cooldown the old server is allowed again")
    }
}
