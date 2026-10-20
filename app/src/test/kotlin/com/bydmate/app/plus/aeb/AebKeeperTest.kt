package com.bydmate.app.plus.aeb

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.remote.DiParsData
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Assert.assertEquals
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
        CoroutineScope(dispatcher),
        { now },
        { wall },
        { toasts++ },
    )

    private var keeper = newKeeper()

    @Before fun setUp() {
        prefs.edit().clear().commit()
        keeper = newKeeper()
    }

    private var km = 12_345.0

    private fun snapshot(speed: Int?, gear: Int?) = mockk<DiParsData> {
        every { this@mockk.speed } returns speed
        every { this@mockk.gear } returns gear
        every { mileage } returns km
    }

    /** One snapshot per second for [seconds]; any attempt it starts runs to the end on virtual time. */
    private fun poll(seconds: Int, speed: Int? = 0, gear: Int? = P) = repeat(seconds) {
        keeper.onPollSnapshot(snapshot(speed, gear))
        dispatcher.scheduler.advanceUntilIdle()
        now += 1_000
        wall += 1_000
    }

    private fun lastLine() = keeper.journalLines().last()

    @Test fun `switched off does nothing at all`() {
        poll(30)
        assertEquals(0, reads)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun `parked, AEB is switched off once after the car settles`() {
        keeper.enabled = true
        poll(5)
        assertEquals("nothing before the car has been parked for 5 s", 0, reads)
        poll(30)
        assertEquals(2, writes.size)
        assertEquals(1, toasts)
        assertTrue(lastLine().contains("SWITCHED_OFF"))
        state = 1 // the driver switches AEB back on: it stays on for this start
        poll(120)
        assertEquals(2, writes.size)
    }

    @Test fun `a moving car is never written`() {
        keeper.enabled = true
        poll(120, speed = 40, gear = D)
        assertEquals(0, reads)
    }

    @Test fun `standing in D at a light is not parked`() {
        keeper.enabled = true
        poll(120, speed = 0, gear = D)
        assertEquals(0, reads)
    }

    @Test fun `unknown speed or gear is not parked`() {
        keeper.enabled = true
        poll(30, speed = null)
        poll(30, gear = null)
        assertEquals(0, reads)
    }

    @Test fun `a stop that is too short resets the settle time`() {
        keeper.enabled = true
        repeat(10) {
            poll(4)
            poll(1, speed = 5, gear = D)
        }
        assertEquals(0, reads)
    }

    @Test fun `the car pulling away during the attempt stops the write`() {
        keeper.enabled = true
        duringRead = { keeper.onPollSnapshot(snapshot(10, D)) }
        poll(10)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertTrue(lastLine().contains("NOT_STILL"))
        // Parked again later: tried again, and this time it goes through.
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

    private companion object {
        const val P = 1
        const val D = 4
    }
}
