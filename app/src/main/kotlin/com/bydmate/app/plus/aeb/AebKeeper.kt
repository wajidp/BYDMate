package com.bydmate.app.plus.aeb

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.bydmate.app.R
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.vehicle.VehicleApiImpl
import com.bydmate.app.data.vehicle.WriteAllowlist
import com.bydmate.app.data.vehicle.WriteOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * BYDMate+ «Keep AEB off»: once per car start, switches AEB off for the owner, who otherwise does
 * it by hand in the factory menu after every start. Default off; the settings card asks for an
 * explicit confirmation before it can be switched on.
 *
 * A car start is a new process (the firmware force-stops every app at car off, see
 * AutomationEngine's service_start) — unless this process is a restart inside the same drive
 * (crash, OOM, our own update), which does nothing, so AEB the driver switched back on stays on.
 * Same drive = the previous process polled the car less than [SAME_START_GAP_MS] ago on this
 * boot, or the odometer moved more than [SAME_START_KM] since its last mark (a car that was off
 * did not move, whatever the gap). A car switched off and on within [SAME_START_GAP_MS] is taken
 * for the same start too: AEB then stays on, the safe side. When unsure, nothing is written.
 *
 * [AebKeepOff] runs on the first poll that shows the car below [MAX_SPEED_KMH] — the limit the
 * factory menu itself keeps for switching AEB, in any gear — and every write re-checks that right
 * before its transact against the poll (no older than [SNAPSHOT_FRESH_MS]) and the live speed the fid
 * push channel lays into the service snapshot. Without speed pushes the check is only as fresh as the
 * poll, hence the short freshness window. Unknown speed = no write. The gear is not looked at. No extra read of any other
 * device is added. It acts once: a final outcome ends it for this start and nothing watches AEB
 * afterwards. Only an attempt that sent nothing (state unreadable, car too fast by the write) is
 * tried again, every [RETRY_MS], within [WINDOW_MS] of the switch being seen on.
 *
 * Decisions go to a small persistent ring ([journalLines]), shown on the settings card, since
 * logcat on DiLink is gone within minutes.
 *
 * [aebOff] drives the red dot on the floating widget: while the switch is on, the AEB state fid
 * is read (read only) every [AebDotState.REFRESH_MS], so the dot goes away once the driver switches AEB
 * back on. Switch off → no reads, no dot.
 */
@Singleton
class AebKeeper internal constructor(
    private val prefs: SharedPreferences,
    private val channel: AebKeepOff,
    private val dot: AebDotState,
    private val scope: CoroutineScope,
    private val clocks: AebClocks,
    /** Tells the driver AEB was just switched off (a toast in production). */
    private val onSwitchedOff: suspend () -> Unit,
) {
    @Inject constructor(
        @ApplicationContext context: Context,
        vehicleApi: VehicleApi,
        helper: HelperClient,
    ) : this(
        context,
        vehicleApi,
        AebStateReader { helper.read(WriteAllowlist.AEB_DEV, WriteAllowlist.AEB_STATE_FID)?.toInt() },
    )

    private constructor(context: Context, vehicleApi: VehicleApi, reader: AebStateReader) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        AebKeepOff(AebWriter { action, value, guard -> write(vehicleApi, action, value, guard) }, reader),
        AebDotState(reader),
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        AebClocks({ SystemClock.elapsedRealtime() }, { System.currentTimeMillis() }),
        {
            withContext(Dispatchers.Main) {
                runCatching { Toast.makeText(context, R.string.plus_aeb_toast_switched_off, Toast.LENGTH_LONG).show() }
            }
        },
    )

    /** True while the car last reported AEB off and the switch is on. */
    val aebOff: StateFlow<Boolean> get() = dot.aebOff
    private val clock = clocks.elapsed
    private val wallClock = clocks.wall
    private val journal = AebJournal(prefs, clocks.wall)

    @Volatile private var done = false
    @Volatile private var job: Job? = null
    @Volatile private var lastSpeed: Int? = null
    @Volatile private var lastSnapshotAt: Long? = null
    /** Why [mayWrite] last refused, for the journal. */
    @Volatile private var refusal: String? = null

    /** The service's live snapshot speed: the poll, patched by fid pushes between ticks. */
    internal var liveSpeed: () -> Int? = { com.bydmate.app.service.TrackingService.lastData.value?.speed }
    private var firstSnapshot = true
    private var lastMarkAt: Long? = null
    private var enabledSeenAt: Long? = null
    private var lastAttemptAt: Long? = null
    private var attempts = 0
    /** Why this start is over, once it is; shown in [status]. */
    @Volatile private var doneReason: String? = null

    /** What the keeper is doing right now, for the settings card (English, like the journal). */
    @Volatile var status: String = "waiting for the car"
        private set

    /**
     * Switching it on is also a request for this start: whatever ended it before (a restart mid-drive
     * while it was off, an earlier result) is forgotten, and AEB goes off once the car is slow enough.
     */
    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            val wasOn = prefs.getBoolean(KEY_ENABLED, false)
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (!value) {
                dot.reset()
            } else if (!wasOn) {
                synchronized(this) {
                    done = false
                    doneReason = null
                    attempts = 0
                    enabledSeenAt = null
                    lastAttemptAt = null
                    status = "waiting for under $MAX_SPEED_KMH km/h"
                }
                journal.append("switched on: AEB goes off once the car is under $MAX_SPEED_KMH km/h")
            }
        }

    /** Called with every poll snapshot by TrackingService. Cheap when the switch is off. */
    @Synchronized
    fun onPollSnapshot(data: DiParsData) {
        val now = clock()
        lastSpeed = data.speed
        lastSnapshotAt = now
        if (firstSnapshot) onFirstSnapshot(now, data.mileage)
        // Not while the car reports off: a process that outlives car off must not make the next
        // real start look like the same drive.
        if (data.powerState != 0) markAlive(now, data.mileage)
        if (!enabled) {
            dot.reset()
            status = "switched off"
            return
        }
        // Car reported off (powerState 0): nothing to read or switch. Unknown power state = carry on.
        if (data.powerState == 0) {
            status = "car off"
            return
        }
        if (mayStartAttempt(now, slow(data.speed))) {
            lastAttemptAt = now
            job = scope.launch { attempt() }
        }
        status = describe(doneReason, job?.isActive == true, data.speed)
        // Not while an attempt runs: it reports the state itself.
        if (job?.isActive != true) dot.refreshIfDue(scope, now) { enabled }
    }

    private fun mayStartAttempt(now: Long, slowNow: Boolean): Boolean {
        if (done || job?.isActive == true) return false
        return !outOfTries(now) && readyToTry(now, slowNow)
    }

    private fun onFirstSnapshot(now: Long, mileage: Double?) {
        firstSnapshot = false
        if (!sameStartAsPreviousProcess(now, mileage)) return
        done = true
        doneReason = "app restarted mid-drive: nothing until the next car start"
        if (enabled) journal.append("app restarted during this car start: nothing done")
    }

    /** Ends this start for good once the window or the attempts are used up. */
    private fun outOfTries(now: Long): Boolean {
        val seen = enabledSeenAt ?: now.also { enabledSeenAt = it }
        if (now - seen <= WINDOW_MS && attempts < MAX_ATTEMPTS) return false
        done = true
        doneReason = "gave up for this start"
        journal.append("gave up: attempts=$attempts, car too fast or AEB unreadable")
        return true
    }

    /** Below [MAX_SPEED_KMH] now, and [RETRY_MS] since the last attempt. */
    private fun readyToTry(now: Long, slowNow: Boolean): Boolean {
        if (!slowNow) return false
        return lastAttemptAt?.let { now - it >= RETRY_MS } ?: true
    }

    /**
     * Still allowed to write right now: switched on, a fresh poll below the limit, and the live
     * speed below it too. The poll may be up to a tick old (5 s at a standstill), so a car that
     * pulled away since would still look slow there; [liveSpeed] carries the fid push the service
     * already receives, no extra read. Why it refused goes to the journal line.
     */
    private fun mayWrite(): Boolean {
        val at = lastSnapshotAt
        val live = runCatching { liveSpeed() }.getOrNull()
        refusal = when {
            !enabled -> "switched off"
            at == null || clock() - at > SNAPSHOT_FRESH_MS -> "no fresh poll"
            !slow(lastSpeed) -> "speed ${lastSpeed ?: "unknown"}"
            !slow(live) -> "live speed ${live ?: "unknown"}"
            else -> null
        }
        return refusal == null
    }

    private suspend fun attempt() {
        val outcome = runCatching { channel.apply(::mayWrite) }.getOrElse {
            Log.w(TAG, "AEB keep-off failed: ${it.message}")
            // Unknown whether anything was sent: final, never retried.
            AebKeepOff.Outcome(AebKeepOff.Result.UNCONFIRMED, null, null, written = true)
        }
        synchronized(this) {
            if (outcome.final) {
                done = true
                doneReason = "done for this start: ${outcome.result}"
                status = doneReason.orEmpty()
            }
            // Only a failed state read uses up an attempt; a car too fast is simply re-armed.
            if (outcome.result == AebKeepOff.Result.UNREACHABLE) attempts++
        }
        val why = refusal.takeIf { outcome.result == AebKeepOff.Result.TOO_FAST }?.let { " ($it)" }.orEmpty()
        journal.append(
            "${outcome.result}$why before=${outcome.before ?: "err"} after=${outcome.after ?: "-"} " +
                "written=${outcome.written}",
        )
        when (outcome.result) {
            AebKeepOff.Result.SWITCHED_OFF, AebKeepOff.Result.ALREADY_OFF -> dot.set(enabled)
            AebKeepOff.Result.NO_EFFECT -> dot.set(false)
            else -> Unit
        }
        if (outcome.result == AebKeepOff.Result.SWITCHED_OFF) runCatching { onSwitchedOff() }
    }

    /**
     * True when the previous process saw the car less than [SAME_START_GAP_MS] ago on this same
     * boot. Both clocks must have moved by the same amount since its mark: a reboot resets
     * elapsedRealtime, so the gaps disagree and the mark is ignored.
     */
    private fun sameStartAsPreviousProcess(now: Long, mileage: Double?): Boolean {
        val markKm = prefs.getFloat(KEY_MARK_KM, -1f)
        if (mileage != null && markKm >= 0f && mileage - markKm > SAME_START_KM) return true
        val markElapsed = prefs.getLong(KEY_MARK_ELAPSED, -1L)
        val markWall = prefs.getLong(KEY_MARK_WALL, -1L)
        if (markElapsed < 0 || markWall < 0) return false
        val gap = now - markElapsed
        if (gap < 0 || gap > SAME_START_GAP_MS) return false
        return abs((wallClock() - markWall) - gap) <= CLOCK_TOLERANCE_MS
    }

    private fun markAlive(now: Long, mileage: Double?) {
        if (lastMarkAt?.let { now - it < MARK_EVERY_MS } == true) return
        lastMarkAt = now
        val edit = prefs.edit().putLong(KEY_MARK_ELAPSED, now).putLong(KEY_MARK_WALL, wallClock())
        mileage?.let { edit.putFloat(KEY_MARK_KM, it.toFloat()) }
        edit.apply()
    }

    /** Newest last. */
    fun journalLines(): List<String> = journal.lines()

    internal companion object {
        const val TAG = "AebKeeper"
        const val PREFS_NAME = "plus_aeb"
        const val KEY_ENABLED = "keep_aeb_off"
        const val KEY_JOURNAL = "journal"
        const val KEY_MARK_ELAPSED = "mark_elapsed"
        const val KEY_MARK_WALL = "mark_wall"
        const val KEY_MARK_KM = "mark_km"
        /** The factory menu lets AEB be switched only below this speed; we keep the same limit. */
        const val MAX_SPEED_KMH = 10
        const val RETRY_MS = 10_000L
        /** Failed state reads allowed per start; the daemon may take a while after a cold boot. */
        const val MAX_ATTEMPTS = 30
        const val WINDOW_MS = 10 * 60_000L
        /** A write is refused when the newest snapshot is older than this. */
        const val SNAPSHOT_FRESH_MS = 3_000L
        /** The previous process's last poll closer than this = the same car start. */
        const val SAME_START_GAP_MS = 120_000L
        /** Odometer moved more than this since the previous process's mark = it was driving. */
        const val SAME_START_KM = 1.0
        const val MARK_EVERY_MS = 20_000L
        const val CLOCK_TOLERANCE_MS = 5_000L
        const val MAX_JOURNAL = 30

        /** Known speed below [MAX_SPEED_KMH], any gear. Unknown speed is never slow enough. */
        fun slow(speed: Int?): Boolean = speed != null && speed in 0 until MAX_SPEED_KMH

        /** The settings card's "Now:" line. */
        fun describe(doneReason: String?, running: Boolean, speed: Int?): String =
            when {
                running -> "switching AEB off"
                doneReason != null -> doneReason
                else -> "waiting for under $MAX_SPEED_KMH km/h (speed ${speed ?: "unknown"})"
            }

        /**
         * The allowlisted, audited write path (vehicle_write_log). The cast keeps this fork feature
         * out of the upstream VehicleApi interface; without it nothing is written. [guard] runs
         * inside the transport lock right before the transact (beforeSend).
         */
        suspend fun write(vehicleApi: VehicleApi, action: String, value: Int, guard: () -> Boolean): AebSend {
            val impl = vehicleApi as? VehicleApiImpl ?: return AebSend.REFUSED
            // HelperClient re-runs the guard before a dead-binder retry: a refusal then may follow a
            // first transact that did reach the daemon, so only a refusal on the very first call
            // proves nothing was sent.
            var guardCalls = 0
            var refusedFirst = false
            val outcome = impl.doWriteOutcome(action, value, journaled = false, beforeSend = {
                guardCalls++
                guard().also { if (!it && guardCalls == 1) refusedFirst = true }
            })
            return when {
                refusedFirst && guardCalls == 1 -> AebSend.REFUSED
                outcome == WriteOutcome.REAL || outcome == WriteOutcome.NOOP -> AebSend.ACCEPTED
                else -> AebSend.UNKNOWN
            }
        }
    }
}

/** Elapsed (elapsedRealtime) and wall (currentTimeMillis) time; wall is only compared with elapsed to tell a reboot apart. */
internal class AebClocks(val elapsed: () -> Long, val wall: () -> Long)

/** The widget dot: the AEB state fid read (read only) at most every [REFRESH_MS]. */
internal class AebDotState(private val reader: AebStateReader) {
    private val _aebOff = MutableStateFlow(false)
    val aebOff: StateFlow<Boolean> = _aebOff.asStateFlow()
    @Volatile private var job: Job? = null
    private var lastReadAt: Long? = null

    @Volatile private var failures = 0

    fun set(off: Boolean) {
        _aebOff.value = off
    }

    /** Switch turned off: no dot, and the next switch-on reads right away. */
    @Synchronized
    fun reset() {
        _aebOff.value = false
        lastReadAt = null
        failures = 0
    }

    /**
     * [enabled] is re-checked when the read lands, so a switch turned off meanwhile shows no dot.
     * After [MAX_FAILURES] failed reads in a row the dot goes away: a dot nobody can confirm any
     * more must not keep saying AEB is off.
     */
    @Synchronized
    fun refreshIfDue(scope: CoroutineScope, now: Long, enabled: () -> Boolean) {
        if (job?.isActive == true) return
        if (lastReadAt?.let { now - it < REFRESH_MS } == true) return
        lastReadAt = now
        job = scope.launch {
            val state = runCatching { reader.read() }.getOrNull()?.let { SentinelDecoder.decodeInt(it) }
            if (state == null) {
                if (++failures >= MAX_FAILURES) _aebOff.value = false
                return@launch
            }
            failures = 0
            _aebOff.value = enabled() && state == 0
        }
    }

    companion object {
        const val REFRESH_MS = 30_000L
        const val MAX_FAILURES = 3
    }
}

/** Persistent ring of the keeper's decisions (logcat on DiLink rotates within minutes). */
internal class AebJournal(private val prefs: SharedPreferences, private val wall: () -> Long) {
    /** Newest last. */
    fun lines(): List<String> = prefs.getString(KEY, "").orEmpty().lines().filter { it.isNotBlank() }

    @Synchronized
    fun append(payload: String) {
        Log.i(AebKeeper.TAG, payload)
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(wall()))
        prefs.edit().putString(KEY, (lines() + "$ts $payload").takeLast(MAX).joinToString("\n")).apply()
    }

    private companion object {
        const val KEY = AebKeeper.KEY_JOURNAL
        const val MAX = AebKeeper.MAX_JOURNAL
    }
}
