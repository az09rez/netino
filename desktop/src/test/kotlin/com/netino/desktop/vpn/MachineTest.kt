package com.netino.desktop.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MachineTest {

    @Test fun legalMovesOnly() = runBlocking {
        val m = Machine(this)
        assertFalse(m.to(Machine.Phase.CONNECTED), "can't be connected without connecting")
        assertTrue(m.to(Machine.Phase.CONNECTING))
        assertTrue(m.to(Machine.Phase.CONNECTED))
        assertTrue(m.to(Machine.Phase.DISCONNECTING))
        assertFalse(m.to(Machine.Phase.CONNECTED), "a late 'connected' can't undo a disconnect")
        assertTrue(m.to(Machine.Phase.DISCONNECTED))
        assertEquals(Machine.Phase.DISCONNECTED, m.phase.value)
    }

    @Test fun epochMarksAttempts() = runBlocking {
        val m = Machine(this)
        m.to(Machine.Phase.CONNECTING)
        val attempt = m.epoch
        m.to(Machine.Phase.CONNECTED)
        assertTrue(m.isCurrent(attempt), "connected is the same attempt")
        m.to(Machine.Phase.CONNECTING)   // a new attempt while connected (switching server)
        assertFalse(m.isCurrent(attempt))
        val second = m.epoch
        m.to(Machine.Phase.DISCONNECTED)
        assertNotEquals(second, m.epoch, "coming to rest ends the attempt")
    }

    @Test fun oneActivityAtATime() = runBlocking {
        val m = Machine(this)
        val firstEnded = CompletableDeferred<Unit>()
        val first = m.begin("search", onEnd = { firstEnded.complete(Unit) }) { delay(10_000) }
        yield()
        val second = m.begin("reconnect") { delay(10_000) }
        firstEnded.await()
        assertFalse(first.live, "starting another activity cancels the first")
        assertFalse(m.isRunning(first), "the first's late reports are dropped")
        assertTrue(m.isRunning(second))
        assertTrue(m.cancelActivity())
        assertNull(m.running)
        assertFalse(m.cancelActivity(), "nothing left to cancel")
    }

    @Test fun exclusiveNeverOverlaps() = runBlocking {
        val m = Machine(this)
        var inside = 0
        var most = 0
        val jobs = List(5) {
            launch {
                m.exclusive {
                    inside++; most = maxOf(most, inside)
                    delay(20)
                    inside--
                }
            }
        }
        jobs.forEach { it.join() }
        assertEquals(1, most)
    }
}

class ReconnectPolicyTest {
    @Test fun growingWaitsThenGivesUp() {
        val p = ReconnectPolicy(maxAttempts = 4, baseMs = 1_000, capMs = 5_000)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 5_000L), List(4) { p.next() })
        assertNull(p.next(), "gives up after the last try")
        p.reset()
        assertEquals(1_000L, p.next(), "a successful connection starts the count again")
    }
}
