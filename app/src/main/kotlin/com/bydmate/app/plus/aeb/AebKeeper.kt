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
 * [AebKeepOff] runs only while the car is parked — gear P and speed 0 on the poll the service
 * already makes, held for [SETTLE_MS] — and every write re-checks that against the freshest
 * snapshot right before its transact, so a car that pulls away while the attempt queues is never
 * written. No extra read of any other device is added. It acts once: a final outcome ends it for
 * this start and nothing watches AEB afterwards. Only an attempt that sent nothing (state
 * unreadable, car no longer parked) is tried again, every [RETRY_MS], within [WINDOW_MS] of the
 * switch being seen on.
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
    @Volatile private var lastGear: Int? = null
    @Volatile private var lastSnapshotAt: Long? = null
    private var firstSnapshot = true
    private var lastMarkAt: Long? = null
    private var enabledSeenAt: Long? = null
    private var parkedSince: Long? = null
    private var lastAttemptAt: Long? = null
    private var attempts = 0

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
            if (!value) dot.reset()
        }

    /** Called with every poll snapshot by TrackingService. Cheap when the switch is off. */
    @Synchronized
    fun onPollSnapshot(data: DiParsData) {
        val now = clock()
        lastSpeed = data.speed
        lastGear = data.gear
        lastSnapshotAt = now
        if (firstSnapshot) onFirstSnapshot(now, data.mileage)
        // Not while the car reports off: a process that outlives car off must not make the next
        // real start look like the same drive.
        if (data.powerState != 0) markAlive(now, data.mileage)
        // Kept current while an attempt runs, so a retry after the car moved settles again first.
        if (!parked(data.speed, data.gear)) parkedSince = null
        if (!enabled) {
            dot.reset()
            return
        }
        // Car reported off (powerState 0): nothing to read or switch. Unknown power state = carry on.
        if (data.powerState == 0) return
        if (mayStartAttempt(now, parked(data.speed, data.gear))) {
            lastAttemptAt = now
            job = scope.launch { attempt() }
        }
        // Not while an attempt runs: it reports the state itself.
        if (job?.isActive != true) dot.refreshIfDue(scope, now) { enabled }
    }

    private fun mayStartAttempt(now: Long, parkedNow: Boolean): Boolean {
        if (done || job?.isActive == true) return false
        return !outOfTries(now) && readyToTry(now, parkedNow)
    }

    private fun onFirstSnapshot(now: Long, mileage: Double?) {
        firstSnapshot = false
        if (!sameStartAsPreviousProcess(now, mileage)) return
        done = true
        if (enabled) journal.append("app restarted during this car start: nothing done")
    }

    /** Ends this start for good once the window or the attempts are used up. */
    private fun outOfTries(now: Long): Boolean {
        val seen = enabledSeenAt ?: now.also { enabledSeenAt = it }
        if (now - seen <= WINDOW_MS && attempts < MAX_ATTEMPTS) return false
        done = true
        journal.append("gave up: attempts=$attempts, car not parked or AEB unreadable")
        return true
    }

    /** Parked for [SETTLE_MS], and [RETRY_MS] since the last attempt. */
    private fun readyToTry(now: Long, parkedNow: Boolean): Boolean {
        if (!parkedNow) {
            parkedSince = null
            return false
        }
        val since = parkedSince ?: now.also { parkedSince = it }
        if (now - since < SETTLE_MS) return false
        return lastAttemptAt?.let { now - it >= RETRY_MS } ?: true
    }

    /** Still allowed to write right now: switched on, and the freshest snapshot is parked. */
    private fun mayWrite(): Boolean {
        val at = lastSnapshotAt ?: return false
        return enabled && clock() - at <= SNAPSHOT_FRESH_MS && parked(lastSpeed, lastGear)
    }

    private suspend fun attempt() {
        val outcome = runCatching { channel.apply(::mayWrite) }.getOrElse {
            Log.w(TAG, "AEB keep-off failed: ${it.message}")
            // Unknown whether anything was sent: final, never retried.
            AebKeepOff.Outcome(AebKeepOff.Result.UNCONFIRMED, null, null, written = true)
        }
        synchronized(this) {
            if (outcome.final) done = true
            // Only a failed state read uses up an attempt; a car that moved off is simply re-armed.
            if (outcome.result == AebKeepOff.Result.UNREACHABLE) attempts++
        }
        journal.append(
            "${outcome.result} before=${outcome.before ?: "err"} after=${outcome.after ?: "-"} " +
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
        /** gear: 1 = P (DiParsData). */
        const val GEAR_P = 1
        const val SETTLE_MS = 5_000L
        const val RETRY_MS = 10_000L
        /** Failed state reads allowed per start; the daemon may take a while after a cold boot. */
        const val MAX_ATTEMPTS = 30
        const val WINDOW_MS = 10 * 60_000L
        /** A write is refused when the newest snapshot is older than this. */
        const val SNAPSHOT_FRESH_MS = 10_000L
        /** The previous process's last poll closer than this = the same car start. */
        const val SAME_START_GAP_MS = 120_000L
        /** Odometer moved more than this since the previous process's mark = it was driving. */
        const val SAME_START_KM = 1.0
        const val MARK_EVERY_MS = 20_000L
        const val CLOCK_TOLERANCE_MS = 5_000L
        const val MAX_JOURNAL = 30

        fun parked(speed: Int?, gear: Int?): Boolean = speed == 0 && gear == GEAR_P

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
