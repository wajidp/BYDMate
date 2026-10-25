package com.bydmate.app.plus.upload

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CloudUploaderTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var settings: UploadSettings
    private lateinit var store: UploadStore
    private lateinit var traces: TraceStore
    private lateinit var uploader: CloudUploader
    private var now = 1_800_000_000_000L

    /** Per path: the next status codes to answer with (200/204 once the list runs out). */
    private val scripted = mutableMapOf<String, ArrayDeque<Int>>()
    private val requests = mutableListOf<Pair<String, String>>()

    @Before fun setUp() {
        UploadRestoreGate.resetForTest()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path!!.substringBefore('?')
                val raw = request.body.readByteArray()
                val body = if (request.getHeader("Content-Encoding") == "gzip") {
                    GZIPInputStream(raw.inputStream()).readBytes().toString(Charsets.UTF_8)
                } else raw.toString(Charsets.UTF_8)
                synchronized(requests) { requests += path to body }
                scripted[path]?.removeFirstOrNull()?.let { return MockResponse().setResponseCode(it) }
                return when {
                    path.endsWith("sync-state") ->
                        MockResponse().setBody("""{"car_id":"c","database_id":"d","server_time":"x","car_name":"Sealion 06","watermarks":{}}""")
                    path.endsWith("batches") -> {
                        val id = JSONObject(body).getString("batch_id")
                        MockResponse().setBody("""{"batch_id":"$id","replayed":false,"received_at":"x","upserted":{},"watermarks":{}}""")
                    }
                    path.endsWith("trace") -> MockResponse().setBody("""{"trip_id":"t","received":1,"inserted":1,"complete":true}""")
                    else -> MockResponse().setResponseCode(204)
                }
            }
        }
        server.start()
        context.getSharedPreferences(UploadSettings.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        settings = UploadSettings(context).apply {
            serverUrl = server.url("/").toString()
            token = "bmc_test"
            enabled = true
        }
        store = UploadStore({ db.openHelper.writableDatabase }, tmp.newFolder("b"), now = { now })
        traces = TraceStore(context, name = null)
        uploader = CloudUploader(
            settings = settings,
            store = store,
            traces = { traces },
            api = UploadApi(OkHttpClient(), { settings.serverUrl }, { settings.token }, "test"),
            appVersion = "plus-test",
            now = { now },
            fixOf = { null },
        )
    }

    @After fun tearDown() {
        server.shutdown()
        traces.close()
        db.close()
    }

    private fun paths() = synchronized(requests) { requests.map { it.first } }
    private fun batches() = synchronized(requests) { requests.filter { it.first.endsWith("batches") }.map { JSONObject(it.second) } }

    @Test fun `switched off it sends nothing and installs nothing`() {
        settings.enabled = false
        assertEquals(CloudUploader.IDLE_MS, uploader.runCycle())
        assertEquals(CloudUploader.State.OFF, uploader.state)
        assertTrue(paths().isEmpty())
        assertFalse(store.isInstalled())
    }

    @Test fun `first cycle asks for the sync state, then uploads history`() = runBlocking {
        db.tripDao().insert(TripEntity(startTs = 1L, endTs = 2L))
        assertEquals(CloudUploader.CYCLE_MS, uploader.runCycle())
        assertEquals(listOf("/v1/car/sync-state", "/v1/car/batches"), paths())
        val batch = batches().single()
        assertEquals(store.databaseId(), batch.getString("database_id"))
        assertEquals("plus-test", batch.getString("app_version"))
        assertEquals(CloudUploader.State.UP_TO_DATE, uploader.state)
        assertEquals("Sealion 06", settings.carName)
        assertNull(store.pending())

        uploader.runCycle()
        assertEquals("nothing new: no second batch, no second sync", 2, paths().size)
    }

    @Test fun `a rejected token stops everything until a new one is saved`() {
        scripted["/v1/car/sync-state"] = ArrayDeque(listOf(401))
        uploader.runCycle()
        assertEquals(CloudUploader.State.TOKEN_REJECTED, uploader.state)
        assertTrue(settings.tokenRejected)
        uploader.runCycle()
        assertEquals(1, paths().size)

        settings.token = "bmc_new"
        uploader.wakeUp()
        uploader.runCycle()
        assertEquals(CloudUploader.State.UP_TO_DATE, uploader.state)
    }

    @Test fun `a 503 keeps the stored batch and the retry sends the same batch id`() = runBlocking {
        db.tripDao().insert(TripEntity(startTs = 1L))
        scripted["/v1/car/batches"] = ArrayDeque(listOf(503))
        val wait = uploader.runCycle()
        assertEquals(CloudUploader.State.RETRYING, uploader.state)
        assertTrue(wait >= 30_000L)
        uploader.runCycle()
        val (first, second) = batches()
        assertEquals(first.getString("batch_id"), second.getString("batch_id"))
        assertEquals(first.getJSONObject("tables").toString(), second.getJSONObject("tables").toString())
    }

    @Test fun `a 409 builds the batch again under a new id`() = runBlocking {
        db.tripDao().insert(TripEntity(startTs = 1L))
        scripted["/v1/car/batches"] = ArrayDeque(listOf(409))
        uploader.runCycle()
        val (first, second) = batches()
        assertTrue(first.getString("batch_id") != second.getString("batch_id"))
        assertEquals(CloudUploader.State.UP_TO_DATE, uploader.state)
    }

    @Test fun `a 422 drops the batch and waits an hour`() = runBlocking {
        db.tripDao().insert(TripEntity(startTs = 1L))
        scripted["/v1/car/batches"] = ArrayDeque(listOf(422))
        assertEquals(CloudUploader.REJECTED_WAIT_MS, uploader.runCycle())
        assertEquals(CloudUploader.State.FAILED, uploader.state)
        assertNull(store.pending())
    }

    @Test fun `the drive trace goes up once its trip exists, in the trip's name`() = runBlocking {
        val start = now - 3_600_000L
        val end = now - 600_000L
        traces.add(start + 1_000L, JSONObject().put("t", start + 1_000L).put("speed", 40).toString())
        traces.add(start + 2_000L, JSONObject().put("t", start + 2_000L).put("speed", 41).toString())
        uploader.runCycle()
        assertTrue("no trip yet, no trace", paths().none { it.endsWith("trace") })

        db.tripDao().insert(TripEntity(startTs = start, endTs = end, bydId = 5L, source = "energydata"))
        uploader.runCycle()
        val trace = synchronized(requests) { requests.single { it.first.endsWith("trace") } }
        assertEquals("/v1/car/trips/${UploadUuid.trip(5L)}/trace", trace.first)
        val body = JSONObject(trace.second)
        assertTrue(body.getBoolean("complete"))
        assertEquals(2, body.getJSONArray("samples").length())
        assertNull("sent samples are deleted", traces.oldest())
    }

    @Test fun `live samples come every 30 s on the move and every 60 s standing`() {
        assertEquals(30_000L, CloudUploader.liveIntervalMs(50))
        assertEquals(60_000L, CloudUploader.liveIntervalMs(0))
        assertEquals(60_000L, CloudUploader.liveIntervalMs(null))
    }

    @Test fun `backoff doubles to a cap`() {
        assertEquals(30_000L, CloudUploader.backoffMs(1))
        assertEquals(60_000L, CloudUploader.backoffMs(2))
        assertEquals(15 * 60_000L, CloudUploader.backoffMs(30))
    }

    @Test fun `a trip that grows after its trace was sent sends the new part only`() = runBlocking {
        val start = now - 3_600_000L
        val end = now - 1_200_000L
        traces.add(start + 1_000L, JSONObject().put("t", start + 1_000L).toString())
        traces.add(end + 60_000L, JSONObject().put("t", end + 60_000L).toString())
        val id = db.tripDao().insert(TripEntity(startTs = start, endTs = end))
        uploader.runCycle()
        db.tripDao().update(TripEntity(id = id, startTs = start, endTs = end + 120_000L))
        uploader.runCycle()
        val sent = synchronized(requests) { requests.filter { it.first.endsWith("trace") }.map { JSONObject(it.second) } }
        assertEquals(2, sent.size)
        assertEquals(end + 60_000L, sent[1].getJSONArray("samples").getJSONObject(0).getLong("t"))
        assertEquals(1, sent[1].getJSONArray("samples").length())
    }

    @Test fun `old samples are pruned even while the server is down`() {
        traces.add(now - TraceStore.SAMPLE_KEEP_MS - 1_000L, "{}")
        scripted["/v1/car/sync-state"] = ArrayDeque(listOf(503))
        uploader.runCycle()
        assertEquals(CloudUploader.State.RETRYING, uploader.state)
        assertNull(traces.oldest())
    }

    @Test fun `a running backup restore pauses every batch`() = runBlocking {
        db.tripDao().insert(TripEntity(startTs = 1L))
        UploadRestoreGate.enter(wait = true)
        uploader.runCycle()
        assertTrue("nothing is sent at all while a restore runs", paths().isEmpty())
        assertEquals(CloudUploader.State.OFF, uploader.state)
    }
}
