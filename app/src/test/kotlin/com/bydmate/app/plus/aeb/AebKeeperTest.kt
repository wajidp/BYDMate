package com.bydmate.app.plus.aeb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.remote.DiParsData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AebKeeperTest {

    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("aeb_keeper_test", Context.MODE_PRIVATE)
    private val dispatcher = StandardTestDispatcher()
    private var now = 1_000_000L
    private var wall = 1_700_000_000_000L
    private var state: Int? = 1
    private val writes = mutableListOf<Pair<String, Int>>()
    private var reads = 0
    private var stateReads = 0
    private var toasts = 0
    private var send = AebSend.ACCEPTED
    /** Runs inside the state read, i.e. while the attempt is under way. */
    private var duringRead: () -> Unit = {}

    private fun newKeeper() = AebKeeper(
        prefs,
        AebKeepOff(
            AebWriter { action, value, guard ->
                if (!guard()) {
                    AebSend.REFUSED
                } else {
                    writes += action to value
                    // A write with an unknown outcome (timeout) leaves the car as it was.
                    if (send == AebSend.ACCEPTED) state = 0
                    send
                }
            },
            AebStateReader { reads++; duringRead(); state },
            readbackDelayMs = 400,
        ),
        // The widget dot's own reads, counted apart from the attempt's.
        AebDotState { stateReads++; state },
        CoroutineScope(dispatcher),
        AebClocks({ now }, { wall }),
        { toasts++ },
    ).also { it.liveSpeed = { if (liveUnknown) null else liveOverride ?: polledSpeed } }

    /** The service's live speed follows the last poll unless a test pushes something else. */
    private var polledSpeed: Int? = null
    private var liveOverride: Int? = null
    private var liveUnknown = false

    private var keeper = newKeeper()

    @Before fun setUp() {
        prefs.edit().clear().commit()
        keeper = newKeeper()
    }

    private var km = 12_345.0
    private var powerStateValue: Int? = 1

    private fun snapshot(speed: Int?, gear: Int?) = mockk<DiParsData> {
        every { this@mockk.speed } returns speed
        every { this@mockk.gear } returns gear
        every { mileage } returns km
        every { this@mockk.powerState } returns powerStateValue
    }

    /** One snapshot per second for [seconds]; any attempt it starts runs to the end on virtual time. */
    /** Gear D by default: the gear must not matter. */
    private fun poll(seconds: Int, speed: Int? = 0, gear: Int? = D) = repeat(seconds) {
        polledSpeed = speed
        keeper.onPollSnapshot(snapshot(speed, gear))
        dispatcher.scheduler.advanceUntilIdle()
        now += 1_000
        wall += 1_000
    }

    private fun lastLine() = keeper.journalLines().last()

    @Test fun `switched off does nothing at all`() {
        poll(30)
        assertEquals(0, reads + stateReads)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertFalse(keeper.aebOff.value)
    }

    @Test fun `the widget dot follows the car's AEB state, read every 30 s`() {
        keeper.enabled = true
        poll(30)
        assertTrue("switched off by the attempt", keeper.aebOff.value)
        state = 1 // the driver switches AEB back on
        poll(31)
        assertFalse(keeper.aebOff.value)
        assertTrue("one read per 30 s, not per poll", stateReads in 2..4)
        state = 0 // and off again in the car menu
        poll(31)
        assertTrue(keeper.aebOff.value)
        keeper.enabled = false
        assertFalse(keeper.aebOff.value)
    }

    @Test fun `one unreadable state keeps the dot, three in a row clear it`() {
        keeper.enabled = true
        state = 0
        poll(30)
        assertTrue(keeper.aebOff.value)
        state = null
        poll(31)
        assertTrue("one failed read keeps the last answer", keeper.aebOff.value)
        poll(60)
        assertFalse("nobody can confirm AEB off any more", keeper.aebOff.value)
    }

    @Test fun `car off reads and writes nothing`() {
        keeper.enabled = true
        powerStateValue = 0
        poll(120)
        assertEquals(0, reads + stateReads)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun `switching the setting back on re-checks AEB right away, without writing when it is off`() {
        keeper.enabled = true
        state = 0
        poll(30)
        keeper.enabled = false
        val readsBefore = reads
        val dotReadsBefore = stateReads
        keeper.enabled = true
        poll(1)
        assertEquals("the re-armed attempt reads once", readsBefore + 1, reads)
        assertEquals("the attempt reports the state, not the dot", dotReadsBefore, stateReads)
        assertTrue(lastLine().contains("ALREADY_OFF"))
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertTrue(keeper.aebOff.value)
    }

    @Test fun `switching on while slow writes once, switching on again writes nothing more`() {
        keeper.enabled = true
        poll(10)
        assertEquals(2, writes.size)
        keeper.enabled = false
        keeper.enabled = true
        poll(30)
        assertEquals(2, writes.size)
        assertTrue(lastLine().contains("ALREADY_OFF"))
    }

    @Test fun `switching off and on during a running attempt sends nothing twice`() {
        keeper.enabled = true
        duringRead = {
            duringRead = {}
            keeper.enabled = false
            keeper.enabled = true
        }
        poll(30)
        assertEquals(2, writes.size)
        assertTrue(keeper.status, keeper.status.contains("done for this start"))
    }

    @Test fun `AEB is switched off on the first slow poll, once`() {
        keeper.enabled = true
        poll(1)
        assertEquals("no waiting: the first poll under 10 km/h acts", 2, writes.size)
        poll(30)
        assertEquals(2, writes.size)
        assertEquals(1, toasts)
        assertTrue(lastLine().contains("SWITCHED_OFF"))
        state = 1 // the driver switches AEB back on: it stays on for this start
        poll(120)
        assertEquals(2, writes.size)
    }

    @Test fun `10 km per h and over is never written`() {
        keeper.enabled = true
        poll(60, speed = 10, gear = D)
        poll(60, speed = 40, gear = D)
        assertEquals(0, reads)
    }

    @Test fun `the gear does not matter under 10 km per h`() {
        for (gear in listOf(P, null)) {
            prefs.edit().clear().commit()
            writes.clear()
            state = 1
            keeper = newKeeper()
            keeper.enabled = true
            poll(1, speed = 9, gear = gear)
            assertEquals("gear=$gear", 2, writes.size)
        }
    }

    @Test fun `the speed limit is strict and needs a known, non-negative speed`() {
        assertFalse(AebKeeper.slow(null))
        assertFalse(AebKeeper.slow(-1))
        assertTrue(AebKeeper.slow(0))
        assertTrue(AebKeeper.slow(9))
        assertFalse(AebKeeper.slow(10))
    }

    @Test fun `an unknown live speed is not written, whatever the poll says`() {
        keeper.enabled = true
        liveUnknown = true
        poll(1)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertTrue(lastLine(), lastLine().contains("TOO_FAST (live speed unknown)"))
    }

    @Test fun `a car that pulled away since the last poll is not written`() {
        keeper.enabled = true
        // The poll still says 0, the pushed live speed already says 25.
        liveOverride = 25
        poll(1)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertTrue(lastLine(), lastLine().contains("TOO_FAST (live speed 25)"))
        liveOverride = null
        poll(30)
        assertEquals(2, writes.size)
    }

    @Test fun `unknown speed is never written`() {
        keeper.enabled = true
        poll(60, speed = null)
        assertEquals(0, reads)
    }

    @Test fun `already over the limit at start, it acts at the first slow moment`() {
        keeper.enabled = true
        poll(20, speed = 30, gear = D)
        assertEquals(0, reads)
        poll(1, speed = 5, gear = D)
        assertEquals(2, writes.size)
    }

    @Test fun `the car pulling away during the attempt stops the write`() {
        keeper.enabled = true
        duringRead = { keeper.onPollSnapshot(snapshot(10, D)) }
        poll(10)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertTrue(lastLine().contains("TOO_FAST"))
        // Slow again later: tried again, and this time it goes through.
        duringRead = {}
        poll(30)
        assertEquals(2, writes.size)
    }

    @Test fun `a stale snapshot stops the write`() {
        keeper.enabled = true
        // The poll stalls while the attempt is queued behind the daemon.
        duringRead = { now += AebKeeper.SNAPSHOT_FRESH_MS + 1 }
        poll(10)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun `switching the setting off during the attempt stops the write`() {
        keeper.enabled = true
        duringRead = { keeper.enabled = false }
        poll(10)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun `a write with an unknown outcome is never retried`() {
        keeper.enabled = true
        send = AebSend.UNKNOWN
        poll(10)
        val sent = writes.size
        state = 1
        poll(120)
        assertEquals(sent, writes.size)
    }

    @Test fun `an unreadable state retries a bounded number of times, then gives up`() {
        keeper.enabled = true
        state = null
        poll(600)
        assertEquals(AebKeeper.MAX_ATTEMPTS, reads)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertTrue(lastLine().contains("gave up"))
    }

    @Test fun `an app restart inside the same car start does nothing`() {
        keeper.enabled = true
        poll(30)
        assertEquals(2, writes.size)
        state = 1 // the driver switches AEB back on, then the app restarts a minute later
        now += 60_000
        wall += 60_000
        keeper = newKeeper()
        poll(60)
        assertEquals(2, writes.size)
        assertTrue(lastLine().contains("restarted"))
    }

    @Test fun `a new car start after a longer gap acts again`() {
        keeper.enabled = true
        poll(30)
        state = 1
        now += 10 * 60_000
        wall += 10 * 60_000
        keeper = newKeeper()
        poll(30)
        assertEquals(4, writes.size)
    }

    @Test fun `a reboot is a new car start even right after the last poll`() {
        keeper.enabled = true
        poll(30)
        state = 1
        now = 30_000 // elapsedRealtime restarted with the boot
        wall += 60_000
        keeper = newKeeper()
        poll(30)
        assertEquals(4, writes.size)
    }

    @Test fun `an app outage mid-drive, however long, does nothing once the car has moved`() {
        keeper.enabled = true
        poll(30)
        assertEquals(2, writes.size)
        state = 1 // AEB back on, then the app is dead for 20 min while the car drives 25 km
        now += 20 * 60_000
        wall += 20 * 60_000
        km += 25
        keeper = newKeeper()
        poll(60)
        assertEquals(2, writes.size)
        assertTrue(lastLine().contains("restarted"))
    }

    @Test fun `switching on mid-drive after a restart acts on this start once slow`() {
        // The app was updated mid-drive with the switch still off: this start counts as done...
        poll(30)
        now += 60_000
        wall += 60_000
        keeper = newKeeper()
        poll(30, speed = 50, gear = D)
        // ...then the driver switches it on: AEB goes off at the next moment under 10 km/h.
        keeper.enabled = true
        poll(10, speed = 50, gear = D)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertTrue(keeper.status, keeper.status.contains("speed 50"))
        poll(1, speed = 8, gear = D)
        assertEquals(2, writes.size)
        assertTrue(keeper.status, keeper.status.contains("SWITCHED_OFF"))
    }

    @Test fun `the status line says why nothing happens`() {
        keeper.enabled = true
        assertTrue(lastLine().contains("switched on"))
        poll(3, speed = null)
        assertTrue(keeper.status, keeper.status.contains("speed unknown"))
        poll(1, speed = 20)
        assertTrue(keeper.status, keeper.status.contains("under 10 km/h (speed 20)"))
        powerStateValue = 0
        poll(1)
        assertEquals("car off", keeper.status)
        keeper.enabled = false
        poll(1)
        assertEquals("switched off", keeper.status)
    }

    private companion object {
        const val P = 1
        const val D = 4
    }
}
