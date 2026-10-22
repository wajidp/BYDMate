package com.bydmate.app.plus.aeb

import com.bydmate.app.data.vehicle.CommandTranslator
import com.bydmate.app.data.vehicle.WriteAllowlist
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AebKeepOffTest {

    private val writes = mutableListOf<Pair<String, Int>>()

    private fun channel(reads: List<Int?>, send: AebSend = AebSend.ACCEPTED): AebKeepOff {
        val queue = ArrayDeque(reads)
        return AebKeepOff(
            AebWriter { action, value, guard ->
                if (!guard()) AebSend.REFUSED else { writes += action to value; send }
            },
            AebStateReader { if (queue.size > 1) queue.removeFirst() else queue.first() },
        )
    }

    private val slow = { true }

    @Test fun `AEB on is switched off with both writes, value 0 only, and confirmed by readback`() = runTest {
        val out = channel(listOf(1, 0)).apply(slow)
        assertEquals(AebKeepOff.Result.SWITCHED_OFF, out.result)
        assertEquals(
            listOf(WriteAllowlist.AEB_OFF_ACTION to 0, WriteAllowlist.AEB_STATE_OFF_ACTION to 0),
            writes,
        )
        assertTrue(out.final)
    }

    @Test fun `AEB already off writes nothing`() = runTest {
        val out = channel(listOf(0)).apply(slow)
        assertEquals(AebKeepOff.Result.ALREADY_OFF, out.result)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun `an unexpected state value writes nothing`() = runTest {
        val out = channel(listOf(2)).apply(slow)
        assertEquals(AebKeepOff.Result.UNKNOWN_STATE, out.result)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertTrue(out.final)
    }

    @Test fun `a failed read or a sentinel writes nothing and may be retried`() = runTest {
        for (raw in listOf(null, 0xFFFF, 0xFFFFF, -10013, -10011)) {
            val out = channel(listOf(raw)).apply(slow)
            assertEquals("raw=$raw", AebKeepOff.Result.UNREACHABLE, out.result)
            assertFalse(out.final)
        }
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun `a throwing read is a failed read`() = runTest {
        val out = AebKeepOff(
            AebWriter { action, value, _ -> writes += action to value; AebSend.ACCEPTED },
            AebStateReader { error("binder died") },
        ).apply(slow)
        assertEquals(AebKeepOff.Result.UNREACHABLE, out.result)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
    }

    @Test fun `a car no longer under the limit writes nothing and may be retried`() = runTest {
        val out = channel(listOf(1)).apply { false }
        assertEquals(AebKeepOff.Result.TOO_FAST, out.result)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertFalse(out.final)
    }

    @Test fun `a guard that refuses at the transact sends nothing and may be retried`() = runTest {
        var checks = 0
        // Slow when the attempt starts, too fast by the time the write reaches the transport lock.
        val out = channel(listOf(1)).apply { checks++ == 0 }
        assertEquals(AebKeepOff.Result.TOO_FAST, out.result)
        assertEquals(emptyList<Pair<String, Int>>(), writes)
        assertFalse(out.final)
    }

    @Test fun `a write with an unknown outcome is final, never retried`() = runTest {
        // A timed-out transact may still have run in the daemon.
        val out = channel(listOf(1, 1, 1, 1), AebSend.UNKNOWN).apply(slow)
        assertTrue(out.written)
        assertTrue(out.final)
    }

    @Test fun `AEB still on after the writes is no effect`() = runTest {
        val out = channel(listOf(1)).apply(slow)
        assertEquals(AebKeepOff.Result.NO_EFFECT, out.result)
        assertTrue(out.written)
        assertTrue(out.final)
    }

    @Test fun `an unreadable readback is unconfirmed`() = runTest {
        val out = channel(listOf(1, null)).apply(slow)
        assertEquals(AebKeepOff.Result.UNCONFIRMED, out.result)
        assertTrue(out.final)
    }

    @Test fun `the AEB entries load, sit on an unbanned device and can only write 0`() {
        val production = WriteAllowlist.loadProduction { "{}" }
        val entries = listOf(WriteAllowlist.AEB_OFF_ACTION, WriteAllowlist.AEB_STATE_OFF_ACTION)
            .map { requireNotNull(production.find(it)) { "$it not loaded" } }
        assertEquals(
            listOf(WriteAllowlist.AEB_SWITCH_FID, WriteAllowlist.AEB_STATE_FID),
            entries.map { it.writeFid },
        )
        assertFalse(WriteAllowlist.AEB_DEV in WriteAllowlist.BANNED_DEVS)
        entries.forEach {
            assertEquals(WriteAllowlist.AEB_DEV, it.dev)
            assertEquals(0, it.valueMin)
            assertEquals(0, it.valueMax)
            assertFalse(it.validated)
        }
        // Nothing else lands on the ADAS device.
        assertEquals(entries.toSet(), production.allEntries().filter { it.dev == WriteAllowlist.AEB_DEV }.toSet())
    }

    /** Voice, the agent and automation all reach the car through CommandTranslator; AEB must not be in it. */
    @Test fun `no command string resolves to an AEB write`() {
        val aeb = setOf(WriteAllowlist.AEB_OFF_ACTION, WriteAllowlist.AEB_STATE_OFF_ACTION)
        val maps = listOf("table", "composite").map { translatorMap(it) }
        maps.forEach { assertTrue(it.isNotEmpty()) }
        val names = maps.flatMap { it.values }.flatMap(::actionNames) +
            maps.flatMap { it.keys }.flatMap { CommandTranslator.resolve(it as String) }.map { it.actionName }
        assertTrue(names.isNotEmpty())
        assertEquals(emptySet<String>(), names.toSet() intersect aeb)
    }

    private fun translatorMap(field: String): Map<*, *> =
        CommandTranslator::class.java.getDeclaredField(field).apply { isAccessible = true }.get(CommandTranslator) as Map<*, *>

    private fun actionNames(value: Any?): List<String> = when (value) {
        is CommandTranslator.Resolved -> listOf(value.actionName)
        is List<*> -> value.filterIsInstance<CommandTranslator.Resolved>().map { it.actionName }
        else -> emptyList()
    }
}
