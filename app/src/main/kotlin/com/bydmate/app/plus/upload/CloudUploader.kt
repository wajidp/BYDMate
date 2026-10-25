package com.bydmate.app.plus.upload

import android.content.Context
import android.location.Location
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.concurrent.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BYDMate+ uploader to BYDMate Cloud (api/openapi.yaml): one-way, read-only. It sends rows the
 * app already stores plus samples of the poll snapshot it is handed. Its one read of the car is
 * the owner's SOC target on a hybrid, a getInt every 30 s ([SocTargetReader]); it never writes.
 *
 * One batch in flight at a time, each stored before it is sent, so a retry is byte-identical.
 * Started by TrackingService; everything runs on its own coroutine and never throws into the
 * service.
 */
@Singleton
@Suppress("LongParameterList", "TooManyFunctions") // collaborators + test clocks; one owner of the upload loop
class CloudUploader internal constructor(
    private val settings: UploadSettings,
    private val store: UploadStore,
    private val traces: () -> TraceStore,
    private val api: UploadApi,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
    private val fixOf: (Location?) -> Fix? = ::freshFix,
    private val socTarget: suspend () -> Int? = { null },
) {

    @Inject constructor(@ApplicationContext context: Context, database: AppDatabase, helper: HelperClient) : this(
        context, UploadSettings(context), database, SocTargetReader(helper),
    )

    private constructor(
        context: Context,
        settings: UploadSettings,
        database: AppDatabase,
        socTargetReader: SocTargetReader,
    ) : this(
        settings = settings,
        store = UploadStore({ database.openHelper.writableDatabase }, File(context.filesDir, BATCH_DIR)),
        traces = lazyTraceStore(context),
        api = UploadApi(httpClient(), { settings.serverUrl }, { settings.token }, "BYDMatePlus/${BuildConfig.VERSION_NAME}"),
        appVersion = "plus-${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
        socTarget = socTargetReader::read,
    )

    private sealed interface BatchStep {
        data object Drained : BatchStep
        data object Sent : BatchStep
        data class Wait(val ms: Long) : BatchStep
    }

    enum class State { OFF, NO_TOKEN, TOKEN_REJECTED, SYNCING, UP_TO_DATE, RETRYING, FAILED }

    @Volatile var state: State = State.OFF
        private set

    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var job: Job? = null
    @Volatile private var scope: CoroutineScope? = null
    @OptIn(ExperimentalCoroutinesApi::class)
    private val sampleWriter = Dispatchers.IO.limitedParallelism(1)

    @Volatile private var synced = false
    private var databaseId: String? = null
    @Volatile private var failures = 0
    private var maxRows = MAX_ROWS

    @Volatile private var lastTraceSecond = -1L
    @Volatile private var lastLiveAt = 0L
    @Volatile private var liveInFlight = false
    @Volatile private var lastSocTarget: Int? = null
    @Volatile private var lastSocTargetAt = 0L

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        this.scope = scope
        job = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val wait = try {
                    runCycle()
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // Whatever goes wrong in one pass (disk, database), the loop must keep running.
                    Log.w(TAG, "upload cycle failed", e)
                    retry(e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""), null)
                }
                withTimeoutOrNull(wait) { wake.receive() }
            }
        }
    }

    /** Settings changed or the owner asked for a sync: ask the server again, now. */
    internal fun wakeUp() {
        synced = false
        failures = 0
        wake.trySend(Unit)
    }

    internal val settingsForUi: UploadSettings get() = settings

    /** The owner pressed «upload last diagnostic dump». Blocking: call from Dispatchers.IO. */
    internal fun uploadDiagnostics(dirs: List<File>): DiagnosticOutcome {
        if (UploadRestoreGate.restoring || !settings.enabled) return DiagnosticOutcome.NotReady
        // The upload database is read under the restore gate's lock, like every batch.
        val databaseId = UploadRestoreGate.lock.withLock {
            if (UploadRestoreGate.restoring) null else store.databaseId()
        }
        return DiagnosticUpload.send(api, settings, databaseId, appVersion, dirs)
    }

    internal fun backlog(): Int = if (store.isInstalled()) store.backlog() else 0

    /** Called on every poll tick by TrackingService. Cheap when off; never blocks the tick. */
    fun onPollSnapshot(data: DiParsData, location: Location?) {
        if (!settings.enabled || settings.token.isBlank() || settings.tokenRejected) return
        if (!UploadSamples.isDriving(data)) return
        val launcher = scope ?: return
        val nowMs = now()
        val fix = fixOf(location)
        val second = nowMs / MS_PER_S
        if (second != lastTraceSecond) {
            lastTraceSecond = second
            val body = UploadSamples.trace(nowMs, data, fix, recentSocTarget(nowMs)).toString()
            launcher.launch(sampleWriter) { runCatching { traces().add(nowMs, body) } }
        }
        if (nowMs - lastLiveAt >= LIVE_INTERVAL_MS && !liveInFlight && launcher.isActive) {
            lastLiveAt = nowMs
            liveInFlight = true
            launcher.launch(Dispatchers.IO) {
                try {
                    // A hybrid also reports the owner's SOC target: one read-only getInt every 30 s.
                    val target = if (data.fuelPercent != null) readSocTarget(nowMs) else null
                    val sample = UploadSamples.live(nowMs, data, fix, target)
                    // Best effort: a sample that could not be sent is dropped, never queued.
                    if (api.postLive(sample) == ApiResult.Unauthorized) tokenRejected()
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    // This scope has no handler: anything escaping here would kill the service.
                    Log.w(TAG, "live sample failed", e)
                } finally {
                    liveInFlight = false
                }
            }
        }
    }

    /** The last SOC target read, while it is still fresh enough to stamp on the per-second trace. */
    private fun recentSocTarget(nowMs: Long): Int? =
        lastSocTarget?.takeIf { nowMs - lastSocTargetAt <= SOC_TARGET_MAX_AGE_MS }

    private suspend fun readSocTarget(nowMs: Long): Int? {
        val target = try {
            socTarget()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "soc target read failed", e)
            null
        }
        lastSocTarget = target
        lastSocTargetAt = nowMs
        return target
    }

    /** One pass: sync state once, drain batches, then traces. Returns how long to wait. */
    internal fun runCycle(): Long {
        blocked()?.let { return idle(it) }
        // First, whatever happens next: samples must not pile up while the server is unreachable.
        traces().prune(now())
        state = State.SYNCING
        val dbId = databaseId ?: store.ensureInstalled().also { databaseId = it }
        if (!synced) syncState(dbId)?.let { return it }
        var more = false
        for (i in 0 until MAX_BATCHES_PER_CYCLE) {
            when (val outcome = nextBatch()) {
                BatchStep.Drained -> break
                is BatchStep.Wait -> return outcome.ms
                BatchStep.Sent -> more = i == MAX_BATCHES_PER_CYCLE - 1
            }
        }
        uploadTraces()?.let { return it }
        failures = 0
        settings.lastOkAt = now()
        state = State.UP_TO_DATE
        return if (more) SOON_MS else CYCLE_MS
    }

    /** One batch, never while a backup restore runs (see [UploadRestoreGate]). */
    private fun nextBatch(): BatchStep = UploadRestoreGate.lock.withLock {
        if (UploadRestoreGate.restoring) {
            BatchStep.Wait(idle(State.OFF))
        } else {
            val batch = store.pending()
                ?: store.buildBatch(maxRows, appVersion, Instant.ofEpochMilli(now()).toString())
            when {
                batch == null -> BatchStep.Drained
                else -> sendBatch(batch)?.let { BatchStep.Wait(it) } ?: BatchStep.Sent
            }
        }
    }

    /** Null = synced; else how long to wait. */
    private fun syncState(dbId: String): Long? = when (val r = api.syncState(dbId)) {
        is ApiResult.Ok -> {
            r.value.watermarks.values.maxOrNull()?.let(store::raiseClock)
            store.adoptServerWatermarks(r.value.watermarks)
            settings.carName = r.value.carName
            synced = true
            null
        }
        else -> failed(r)
    }

    /** Null = go on with the next batch; else how long to wait. */
    private fun sendBatch(batch: PendingBatch): Long? = when (val r = api.postBatch(batch.file.readBytes())) {
        is ApiResult.Ok -> {
            store.ack(batch)
            r.value.watermarks.values.maxOrNull()?.let(store::raiseClock)
            settings.lastError = if (r.value.rejected > 0) "${r.value.rejected} rows rejected by the server" else null
            null
        }
        ApiResult.Conflict -> {
            store.dropPending()
            null
        }
        ApiResult.TooLarge -> {
            store.dropPending()
            maxRows = maxOf(MIN_ROWS, maxRows / 2)
            null
        }
        is ApiResult.Rejected -> {
            // Rebuilt, the same rows would fail the same way: wait long, then try again.
            store.dropPending()
            failed(r)
        }
        else -> failed(r)
    }

    /** Traces of finished trips that have samples here; null = done, else the wait after a failure. */
    private fun uploadTraces(): Long? {
        val pendingSamples = traces()
        val nowMs = now()
        val oldest = pendingSamples.oldest()
        if (oldest != null) {
            for (trip in store.finishedTrips(oldest, nowMs - TRACE_SETTLE_MS)) {
                // A trip whose end moved later (enrichment, a merge) sends the new part only.
                val sentEnd = pendingSamples.sentEnd(trip.recordId)
                if (sentEnd != null && sentEnd >= trip.endTs) continue
                val from = if (sentEnd == null) trip.startTs else sentEnd + 1
                uploadTrace(trip, pendingSamples.samples(from, trip.endTs))?.let { return it }
                pendingSamples.markSent(trip.recordId, from, trip.endTs, nowMs)
            }
        }
        return null
    }

    /** Null = this trip's trace is done (sent, or given up on); else how long to wait. */
    private fun uploadTrace(trip: TripWindow, samples: List<String>): Long? {
        val chunks = samples.chunked(TRACE_CHUNK)
        for ((i, chunk) in chunks.withIndex()) {
            val body = JSONObject()
                .put("complete", i == chunks.lastIndex)
                .put("samples", JSONArray(chunk.map(::JSONObject)))
            when (val r = api.postTrace(trip.recordId, body)) {
                is ApiResult.Ok -> Unit
                is ApiResult.Rejected, ApiResult.Conflict, ApiResult.TooLarge -> {
                    // Never resend the same body: this trace is given up.
                    settings.lastError = "trace ${trip.recordId}: ${describe(r)}"
                    return null
                }
                else -> return failed(r)
            }
        }
        return null
    }

    /** Why nothing may be sent now, or null. */
    private fun blocked(): State? = when {
        UploadRestoreGate.restoring -> State.OFF
        !settings.enabled -> State.OFF
        settings.token.isBlank() -> State.NO_TOKEN
        settings.tokenRejected -> State.TOKEN_REJECTED
        else -> null
    }

    private fun idle(s: State): Long {
        state = s
        return IDLE_MS
    }

    private fun failed(r: ApiResult<*>): Long = when (r) {
        ApiResult.Unauthorized -> {
            tokenRejected()
            IDLE_MS
        }
        is ApiResult.Retry -> retry(r.message, r.afterMs)
        else -> {
            settings.lastError = describe(r)
            state = State.FAILED
            REJECTED_WAIT_MS
        }
    }

    private fun retry(message: String, afterMs: Long?): Long {
        failures++
        settings.lastError = message
        state = State.RETRYING
        return maxOf(afterMs ?: 0L, backoffMs(failures))
    }

    private fun tokenRejected() {
        settings.tokenRejected = true
        settings.lastError = "token rejected (401)"
        state = State.TOKEN_REJECTED
    }

    companion object {
        private const val TAG = "PlusUpload"
        private const val BATCH_DIR = "plus_upload"
        private const val MS_PER_S = 1_000L
        internal const val MAX_ROWS = 2_000
        private const val MIN_ROWS = 50
        private const val MAX_BATCHES_PER_CYCLE = 16
        internal const val TRACE_CHUNK = 5_000
        /** A trip row may still be enriched right after it appears. */
        internal const val TRACE_SETTLE_MS = 2 * 60_000L
        internal const val LIVE_INTERVAL_MS = 30_000L
        internal const val CYCLE_MS = 60_000L
        private const val SOON_MS = 5_000L
        internal const val IDLE_MS = 5 * 60_000L
        internal const val REJECTED_WAIT_MS = 60 * 60_000L
        private const val BACKOFF_BASE_MS = 30_000L
        private const val BACKOFF_MAX_MS = 15 * 60_000L
        private const val FIX_MAX_AGE_NS = 15_000_000_000L
        private const val MAX_SHIFT = 10
        private const val SOC_TARGET_MAX_AGE_MS = 90_000L

        internal fun backoffMs(failures: Int): Long =
            (BACKOFF_BASE_MS shl (failures - 1).coerceIn(0, MAX_SHIFT)).coerceAtMost(BACKOFF_MAX_MS)

        internal fun describe(r: ApiResult<*>): String = when (r) {
            is ApiResult.Rejected -> if (r.code > 0) "HTTP ${r.code}: ${r.message}" else r.message
            is ApiResult.Retry -> r.message
            ApiResult.Conflict -> "HTTP 409"
            ApiResult.TooLarge -> "HTTP 413"
            ApiResult.Unauthorized -> "HTTP 401"
            is ApiResult.Ok -> "ok"
        }

        private fun freshFix(location: Location?): Fix? {
            location ?: return null
            val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
            return if (age in 0..FIX_MAX_AGE_NS) Fix(location.latitude, location.longitude) else null
        }

        private fun lazyTraceStore(context: Context): () -> TraceStore {
            val store by lazy { TraceStore(context.applicationContext) }
            return { store }
        }

        private fun httpClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            // A hard cap per call: a restore waits for a batch in flight (UploadRestoreGate).
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }
}
