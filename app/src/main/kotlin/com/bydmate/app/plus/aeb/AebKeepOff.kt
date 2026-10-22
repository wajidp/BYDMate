package com.bydmate.app.plus.aeb

import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.vehicle.WriteAllowlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Raw AEB state fid value, sentinels kept. Null means the read did not complete. */
fun interface AebStateReader {
    suspend fun read(): Int?
}

/** What became of one write: never sent (the guard refused), sent and accepted, or anything else. */
enum class AebSend { REFUSED, ACCEPTED, UNKNOWN }

/**
 * One allowlisted write by action name. [guard] must be re-checked right before the transact
 * (VehicleApiImpl.doWriteOutcome's beforeSend in production) and its refusal reported as REFUSED.
 */
fun interface AebWriter {
    suspend fun write(action: String, value: Int, guard: () -> Boolean): AebSend
}

/**
 * BYDMate+: switches AEB off once, the way the factory menu does. dev=1038 (ADAS) only — never a
 * banned device — and value 0 only, so this can never switch AEB on or touch anything else.
 *
 * The two fids and their encoding come from two independent DiLink 5.0 tools (Saqr and
 * AEB Manager, decompiled 2026-10-20): both write 0 to 944767020 and 852492332 and read
 * 852492332 back, 1 = on, 0 = off. Neither has been validated on this car yet, so the state read
 * decides what happens:
 *  - read failed or a sentinel → UNREACHABLE, nothing written (retryable)
 *  - [guard] false (car at or over the speed limit, switch turned off) → TOO_FAST, nothing written (retryable)
 *  - 0                         → ALREADY_OFF, nothing written
 *  - any value other than 1    → UNKNOWN_STATE, nothing written (not the encoding we expect)
 *  - 1 → both writes, then read back: 0 → SWITCHED_OFF, still 1 → NO_EFFECT, else UNCONFIRMED
 * Once the first write may have reached the car the attempt is final whatever its status says:
 * a timed-out transact can still have run in the daemon, and a retry after the driver switched
 * AEB back on would switch it off again.
 */
class AebKeepOff(
    private val writer: AebWriter,
    private val reader: AebStateReader,
    private val readbackDelayMs: Long = READBACK_DELAY_MS,
) {
    enum class Result { SWITCHED_OFF, ALREADY_OFF, NO_EFFECT, UNCONFIRMED, UNKNOWN_STATE, UNREACHABLE, TOO_FAST }

    /** [before]/[after] are the raw state reads (null = failed), [written] whether a write may have been sent. */
    data class Outcome(val result: Result, val before: Int?, val after: Int?, val written: Boolean) {
        /** False only when nothing was sent and another try later is safe. */
        val final: Boolean get() = result != Result.UNREACHABLE && result != Result.TOO_FAST
    }

    suspend fun apply(guard: () -> Boolean): Outcome {
        val before = read()
        val state = before?.let { SentinelDecoder.decodeInt(it) }
            ?: return Outcome(Result.UNREACHABLE, before, null, written = false)
        if (state == STATE_OFF) return Outcome(Result.ALREADY_OFF, before, null, written = false)
        if (state != STATE_ON) return Outcome(Result.UNKNOWN_STATE, before, null, written = false)
        if (!guard()) return Outcome(Result.TOO_FAST, before, null, written = false)

        if (writer.write(WriteAllowlist.AEB_OFF_ACTION, STATE_OFF, guard) == AebSend.REFUSED) {
            return Outcome(Result.TOO_FAST, before, null, written = false)
        }
        // From here the attempt is final. A refused second write (the car just moved) is left out;
        // the readback says what the car ended up with.
        writer.write(WriteAllowlist.AEB_STATE_OFF_ACTION, STATE_OFF, guard)

        var after: Int? = null
        repeat(READBACK_ATTEMPTS) {
            delay(readbackDelayMs)
            after = read()
            if (after == STATE_OFF) return Outcome(Result.SWITCHED_OFF, before, after, written = true)
        }
        val result = if (after == STATE_ON) Result.NO_EFFECT else Result.UNCONFIRMED
        return Outcome(result, before, after, written = true)
    }

    /** A throwing read is a failed read, not a verdict. */
    private suspend fun read(): Int? = runCatching { reader.read() }
        .onFailure { if (it is CancellationException) throw it }
        .getOrNull()

    private companion object {
        const val STATE_ON = 1
        const val STATE_OFF = 0
        const val READBACK_ATTEMPTS = 3
        /** CAN publishes the new state with a delay, same budget as the HUD and steering heat channels. */
        const val READBACK_DELAY_MS = 400L
    }
}
