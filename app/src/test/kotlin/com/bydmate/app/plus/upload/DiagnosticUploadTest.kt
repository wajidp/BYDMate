package com.bydmate.app.plus.upload

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.io.File
import java.util.zip.GZIPInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DiagnosticUploadTest {

    @get:Rule val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var settings: UploadSettings
    private lateinit var api: UploadApi
    private lateinit var dir: File

    private val header = "=== BYDMate diagnostic dump ===\napp: x v4.0.1.8\n--- hybrid probe ---\n" +
        "soc_target_dr[dev=1023 fid=873463848 tx=5]=50\n${DiagnosticUpload.END_MARKER}\n"

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        context.getSharedPreferences(UploadSettings.FILE, Context.MODE_PRIVATE).edit().clear().commit()
        settings = UploadSettings(context).apply {
            serverUrl = server.url("/").toString()
            token = "bmc_test"
            enabled = true
        }
        api = UploadApi(OkHttpClient(), { settings.serverUrl }, { settings.token }, "test")
        dir = tmp.newFolder("dl")
    }

    @After fun tearDown() = server.shutdown()

    private fun dump(name: String, text: String, modified: Long = 1_000L) =
        File(dir, name).apply { writeText(text); setLastModified(modified) }

    private fun send(dbId: String? = "db-1") =
        DiagnosticUpload.send(api, settings, dbId, "plus-4.0.1.8 (50801)", listOf(dir))

    @Test fun `only the header is read, never the logcat after it`() {
        val f = dump("bydmate_logs_20261025_083900.txt", header + "10-25 08:39:01 secret location 12.3,45.6\n")
        assertEquals(header, DiagnosticUpload.header(f))
    }

    @Test fun `only technical sections are kept and secrets are masked`() {
        val text = "=== BYDMate diagnostic dump ===\napp: x\n--- voice ---\nheard=\"call mum\"\n" +
            "--- hybrid probe ---\nsoc_now=1\n--- hud ---\nmaneuverRaw=Elm Street\n" +
            "--- rules ---\nrule Home\n--- a section from the future ---\nanything\n" +
            "--- settings ---\ncloud sim=40445/in apn=x home_packages=[a.b, c.d] route_navigator=geo:0,0?q=Elm\n" +
            "--- auto backup ---\ntelegram: bot=@mybot chat=…1234\n" +
            "--- steering key ---\na11y_secure_setting: com.foo/.Svc\n" +
            "--- last crash ---\nboom https://x.test/p?token=abc bot123:AAH-x key=sekret\n" +
            "--- trace ---\nscreen foreground pkg=a.b\nnet ok\n${DiagnosticUpload.END_MARKER}\n"
        val out = DiagnosticRedactor.redact(text)
        for (gone in listOf(
            "call mum", "Elm Street", "rule Home", "anything", "40445", "a.b, c.d", "mybot",
            "com.foo", "x.test", "AAH-x", "sekret", "foreground", "geo:0,0",
        )) assertFalse("$gone must not be uploaded", out.contains(gone))
        for (kept in listOf("app: x", "soc_now=1", "apn=x", "chat=*", "net ok", "sim=*", "bot=@*")) {
            assertTrue("$kept must stay", out.contains(kept))
        }
        assertTrue(out.contains("--- voice --- (left out of the upload)"))
        assertTrue(out.contains("--- a section from the future --- (left out of the upload)"))
        assertTrue(out.endsWith("${DiagnosticUpload.END_MARKER}\n"))
    }

    @Test fun `every section the header writes is either allowed or deliberately left out`() {
        val source = listOf("src/main/kotlin", "app/src/main/kotlin")
            .map { java.io.File(it, "com/bydmate/app/ui/settings/SettingsViewModel.kt") }
            .first { it.isFile }.readText()
        val written = Regex("""appendLine\("--- ([^"]+) ---"\)""").findAll(source).map { it.groupValues[1] }.toSet()
        val leftOut = setOf(
            "rules", "automation journal", "agent", "voice", "hud", "cluster music", "cluster", "split",
            "steering key", "native assistant packages",
        )
        val unknown = written - DiagnosticRedactor.ALLOWED_SECTIONS - leftOut
        assertTrue("new header sections need a decision (allow or leave out): $unknown", unknown.isEmpty())
        assertTrue(DiagnosticRedactor.ALLOWED_SECTIONS.all { it in written })
    }

    @Test fun `the crash log keeps frames and class names but not exception messages`() {
        val text = "=== BYDMate diagnostic dump ===\n--- last crash ---\n2026-10-25 08:39:00\n" +
            "java.lang.IllegalStateException: bad reply from 'call mum'\n\tat com.x.Y.z(Y.kt:12)\n" +
            "Caused by: org.json.JSONException: Value Elm Street of type java.lang.String\n\t... 3 more\n" +
            "free text line with the owner's words\n--- trace ---\n${DiagnosticUpload.END_MARKER}\n"
        val out = DiagnosticRedactor.redact(text)
        assertTrue(out.contains("java.lang.IllegalStateException\n"))
        assertTrue(out.contains("Caused by: org.json.JSONException\n"))
        assertTrue(out.contains("\tat com.x.Y.z(Y.kt:12)"))
        assertTrue(out.contains("2026-10-25 08:39:00"))
        for (gone in listOf("call mum", "Elm Street", "owner's words")) assertFalse(out.contains(gone))
    }

    @Test fun `the url, bot and key masks work inside a kept section`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\n--- settings ---\nerr https://x.test/p?token=abc bot123:AAH-x key=sekret\n" +
                "${DiagnosticUpload.END_MARKER}\n",
        )
        assertTrue(out.contains("err <url> bot* key=*"))
        for (gone in listOf("x.test", "abc", "AAH-x", "sekret")) assertFalse(out.contains(gone))
    }

    @Test fun `a multi-line failed-to-gather message is dropped after its first line`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\n--- settings ---\n(failed to gather x: first line private\n" +
                "second line private\nthird private)\nafter: ok\n${DiagnosticUpload.END_MARKER}\n",
        )
        assertTrue(out.contains("(failed to gather x)"))
        assertFalse(out.contains("private"))
        // the message can end anywhere, so everything up to the next section goes with it
        assertFalse(out.contains("after: ok"))
    }

    @Test fun `the apps line, error lines and spawn-failure details are masked`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\napps: yandexnavi=1.2 spotify=3\n--- hybrid probe ---\n" +
                "error: java.io.IOException private path /a/b\nvehicle_kind: error secret thing\n" +
                "--- helper daemon ---\nholder: ok\nlast_spawn_failure: 2026-10-25 08:39:00 reason=exit\n" +
                "  log line private one\n  log line private two\nafter: fine\n" +
                "--- auto backup ---\ntelegram: bot=@mybot chat=…1234\n${DiagnosticUpload.END_MARKER}\n",
        )
        for (gone in listOf("yandexnavi", "spotify", "private", "secret thing", "…1234")) {
            assertFalse("$gone must not be uploaded", out.contains(gone))
        }
        for (kept in listOf("apps: *", "error: *", "holder: ok", "reason=exit", "after: fine", "chat=*")) {
            assertTrue("$kept must stay", out.contains(kept))
        }
    }

    @Test fun `split, cluster and music trace lines are dropped`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\n--- trace ---\n08:39:01 split  start_narrow_a.b d=x\n" +
                "08:39:02 cluster relaunch_c.d\ncar cluster_music target=1 sessions=e.f:playing owner=g.h\n" +
                "08:39:03 net    call ok ms=5\n${DiagnosticUpload.END_MARKER}\n",
        )
        for (gone in listOf("a.b", "c.d", "e.f", "g.h")) assertFalse("$gone must not be uploaded", out.contains(gone))
        assertTrue(out.contains("net    call ok ms=5"))
    }

    @Test fun `the rest of a multi-line error text is dropped until the next entry`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\n--- hybrid probe ---\nerror: first private\nsecond private\n" +
                "third (private)\nnext_key=1\n--- settings ---\n(failed to gather x: first private\nlast (a)\nsecret\n" +
                "--- seats ---\nseat=1\n${DiagnosticUpload.END_MARKER}\n",
        )
        assertFalse(out.contains("private"))
        assertFalse(out.contains("secret"))
        assertTrue(out.contains("error: *"))
        assertTrue(out.contains("next_key=1"))
        assertTrue(out.contains("seat=1"))
        assertTrue(out.endsWith("${DiagnosticUpload.END_MARKER}\n"))
    }

    @Test fun `a continuation that looks like a cause or a path is dropped after an error line`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\n--- fid resolve ---\nerror: boom\nCaused by: private text\n" +
                "Value Elm Street: x\n${DiagnosticUpload.END_MARKER}\n",
        )
        assertFalse(out.contains("private"))
        assertFalse(out.contains("Elm Street"))
        assertTrue(out.endsWith("${DiagnosticUpload.END_MARKER}\n"))
    }

    @Test fun `a gather line that ends in a bracket still swallows its continuation`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\n--- seats ---\n(failed to gather x: Foo(bar)\nprivate tail\n" +
                "${DiagnosticUpload.END_MARKER}\n",
        )
        assertTrue(out.contains("(failed to gather x)"))
        assertFalse(out.contains("private"))
        assertTrue(out.endsWith("${DiagnosticUpload.END_MARKER}\n"))
    }

    @Test fun `package lists in the trace are dropped`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\n--- trace ---\nwidget home-packages pkgs=a.b,c.d\nnet ok\n" +
                "${DiagnosticUpload.END_MARKER}\n",
        )
        for (gone in listOf("a.b", "c.d", "e.f", "private")) assertFalse(out.contains(gone))
        assertTrue(out.contains("net ok"))
    }

    @Test fun `a trace error keeps the fields after it, and a one-word crash line is not kept`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\n--- trace ---\nnet call error=SocketTimeoutException ms=75000 by=42\n" +
                "--- last crash ---\njava.lang.IllegalStateException: bad\nsecret\njohn.smith\n" +
                "${DiagnosticUpload.END_MARKER}\n",
        )
        assertTrue(out.contains("error=* ms=75000 by=42"))
        assertTrue(out.contains("java.lang.IllegalStateException\n"))
        assertFalse(out.contains("secret"))
        assertFalse(out.contains("john.smith"))
    }

    @Test fun `a failed-to-gather line loses its exception text`() {
        val out = DiagnosticRedactor.redact(
            "=== BYDMate diagnostic dump ===\n--- settings ---\n(failed to gather x: boom 'private')\n" +
                "${DiagnosticUpload.END_MARKER}\n",
        )
        assertTrue(out.contains("(failed to gather x)"))
        assertFalse(out.contains("private"))
    }

    @Test fun `what is posted has no personal section in it`() {
        dump(
            "bydmate_logs_p.txt",
            "=== BYDMate diagnostic dump ===\napp: x\n--- voice ---\nheard=\"call mum\"\n" +
                "--- hybrid probe ---\nsoc_now=1\n${DiagnosticUpload.END_MARKER}\n",
        )
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"i","received_at":"x"}"""))
        assertTrue(send() is DiagnosticOutcome.Sent)
        val req = server.takeRequest()
        val text = JSONObject(GZIPInputStream(req.body.inputStream()).readBytes().toString(Charsets.UTF_8)).getString("text")
        assertFalse(text.contains("call mum"))
        assertTrue(text.contains("soc_now=1"))
    }

    @Test fun `nothing is sent while a backup restore runs`() {
        dump("bydmate_logs_x.txt", header)
        UploadRestoreGate.enter(wait = true)
        try {
            assertEquals(DiagnosticOutcome.NotReady, send())
        } finally {
            UploadRestoreGate.resetForTest()
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun `a body over the byte cap is not sent`() {
        // 190k chars: under the char caps, over 900 KB once JSON escapes each control char to 6 bytes.
        val wide = "\u0001".repeat(19_000)
        dump("bydmate_logs_w.txt", "=== BYDMate diagnostic dump ===\n" + "$wide\n".repeat(10) + "${DiagnosticUpload.END_MARKER}\n")
        assertEquals(DiagnosticOutcome.Failed("header too large"), send())
        assertEquals(0, server.requestCount)
    }

    @Test fun `a recording that cannot be read does not hide an older good one`() {
        dump("bydmate_logs_old.txt", header, modified = 1_000L)
        dump("bydmate_logs_bad.txt", "=== BYDMate diagnostic dump ===\n" + "z".repeat(50_000), modified = 9_000L)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"i","received_at":"x"}"""))
        assertEquals(DiagnosticOutcome.Sent("bydmate_logs_old.txt"), send())
    }

    @Test fun `an older recording is used when the newest has no header yet`() {
        dump("bydmate_logs_old.txt", header, modified = 1_000L)
        dump("bydmate_logs_new.txt", "", modified = 9_000L)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"i","received_at":"x"}"""))
        assertEquals(DiagnosticOutcome.Sent("bydmate_logs_old.txt"), send())
    }

    @Test fun `a header over the cap is a failure, not a missing header`() {
        val big = "=== BYDMate diagnostic dump ===\n" + "x".repeat(100).plus("\n").repeat(3_000)
        dump("bydmate_logs_big.txt", big)
        val r = send()
        assertEquals(DiagnosticOutcome.Failed("IOException: header too large"), r)
    }

    @Test fun `a file with no line breaks cannot fill memory`() {
        dump("bydmate_logs_flat.txt", "=== BYDMate diagnostic dump ===\n" + "y".repeat(50_000))
        assertEquals(DiagnosticOutcome.Failed("IOException: header line too long"), send())
        assertEquals(0, server.requestCount)
    }

    @Test fun `a file without the end line or the first line has no header`() {
        assertNull(DiagnosticUpload.header(dump("bydmate_logs_a.txt", header.removeSuffix(DiagnosticUpload.END_MARKER + "\n"))))
        assertNull(DiagnosticUpload.header(dump("bydmate_logs_b.txt", "logcat only\n${DiagnosticUpload.END_MARKER}\n")))
    }

    @Test fun `the newest recording wins and other files are ignored`() {
        dump("bydmate_logs_old.txt", header, modified = 1_000L)
        val newest = dump("bydmate_logs_new.txt", header, modified = 9_000L)
        dump("other.txt", header, modified = 99_000L)
        assertEquals(newest, DiagnosticUpload.latest(listOf(dir)))
    }

    @Test fun `a sent dump carries the header and the contract's fields`() {
        dump("bydmate_logs_20261025_083900.txt", header + "logcat line\n", modified = 7_000L)
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"i","received_at":"x"}"""))
        assertEquals(DiagnosticOutcome.Sent("bydmate_logs_20261025_083900.txt"), send())
        val req = server.takeRequest()
        assertEquals("/v1/car/diagnostics", req.path)
        assertEquals("gzip", req.getHeader("Content-Encoding"))
        val body = JSONObject(GZIPInputStream(req.body.inputStream()).readBytes().toString(Charsets.UTF_8))
        assertEquals("db-1", body.getString("database_id"))
        assertEquals("plus-4.0.1.8 (50801)", body.getString("app_version"))
        assertEquals(7_000L, body.getLong("created_at"))
        assertEquals("bydmate_logs_20261025_083900.txt", body.getString("file_name"))
        assertEquals(header, body.getString("text"))
        assertFalse(body.getString("text").contains("logcat line"))
    }

    @Test fun `nothing is sent unless upload is on with a good token and a database`() {
        dump("bydmate_logs_x.txt", header)
        assertEquals(DiagnosticOutcome.NotReady, send(dbId = null))
        settings.enabled = false
        assertEquals(DiagnosticOutcome.NotReady, send())
        settings.enabled = true
        settings.tokenRejected = true
        assertEquals(DiagnosticOutcome.NotReady, send())
        assertEquals(0, server.requestCount)
    }

    @Test fun `no recording and no header are told apart`() {
        assertEquals(DiagnosticOutcome.NoDump, send())
        dump("bydmate_logs_x.txt", "garbage")
        assertEquals(DiagnosticOutcome.NoHeader, send())
        assertEquals(0, server.requestCount)
    }

    @Test fun `a 401 marks the token rejected and a 429 is a plain failure`() {
        dump("bydmate_logs_x.txt", header)
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "10"))
        assertEquals(DiagnosticOutcome.Failed("HTTP 429 (try again in 10 s)"), send())
        assertFalse(settings.tokenRejected)
        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(DiagnosticOutcome.Failed("token rejected (401)"), send())
        assertTrue(settings.tokenRejected)
    }
}
