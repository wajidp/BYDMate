package com.bydmate.app.plus.upload

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
import org.junit.Test
import java.util.zip.GZIPInputStream

class UploadApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: UploadApi

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        api = UploadApi(OkHttpClient(), { server.url("/").toString() }, { " bmc_token " }, "test-agent")
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `sync-state sends the token and database id and reads watermarks`() {
        server.enqueue(MockResponse().setBody("""{"car_id":"c","database_id":"d","server_time":"x","car_name":"Sealion","watermarks":{"trips":5,"bogus":9}}"""))
        val r = api.syncState("db-1") as ApiResult.Ok
        assertEquals(mapOf("trips" to 5L), r.value.watermarks)
        assertEquals("Sealion", r.value.carName)
        val req = server.takeRequest()
        assertEquals("/v1/car/sync-state?database_id=db-1", req.path)
        assertEquals("Bearer bmc_token", req.getHeader("Authorization"))
    }

    @Test fun `a batch goes out gzipped exactly as stored`() {
        server.enqueue(MockResponse().setBody("""{"batch_id":"b","replayed":true,"received_at":"x","upserted":{},"rejected":[{"table":"trips","index":0,"message":"m"}],"watermarks":{}}"""))
        val body = UploadApi.gzip("""{"batch_id":"b"}""")
        val r = api.postBatch(body) as ApiResult.Ok
        assertEquals(1, r.value.rejected)
        assertTrue(r.value.replayed)
        val req = server.takeRequest()
        assertEquals("gzip", req.getHeader("Content-Encoding"))
        assertTrue(req.getHeader("Content-Type")!!.startsWith("application/json"))
        val sent = GZIPInputStream(req.body.inputStream()).readBytes().toString(Charsets.UTF_8)
        assertEquals("""{"batch_id":"b"}""", sent)
    }

    @Test fun `status codes map to what the spec tells the car to do`() {
        val cases = listOf(
            401 to ApiResult.Unauthorized,
            409 to ApiResult.Conflict,
            413 to ApiResult.TooLarge,
        )
        for ((code, expected) in cases) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals(expected, api.postBatch(ByteArray(0)))
        }
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"title":"Unprocessable","status":422,"detail":"no batch_id"}"""))
        assertEquals(ApiResult.Rejected(422, "Unprocessable: no batch_id"), api.postBatch(ByteArray(0)))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "7"))
        assertEquals(ApiResult.Retry(7_000L, "HTTP 429"), api.postBatch(ByteArray(0)))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ApiResult.Retry(null, "HTTP 503"), api.postBatch(ByteArray(0)))
    }

    @Test fun `a network failure is a retry`() {
        server.shutdown()
        assertTrue(api.postLive(JSONObject().put("ts", 1)) is ApiResult.Retry)
    }

    @Test fun `trace goes to the trip's path, gzipped`() {
        server.enqueue(MockResponse().setBody("""{"trip_id":"t","received":1,"inserted":1,"complete":true}"""))
        assertEquals(ApiResult.Ok(Unit), api.postTrace("abc", JSONObject().put("complete", true)))
        val req = server.takeRequest()
        assertEquals("/v1/car/trips/abc/trace", req.path)
        assertEquals("gzip", req.getHeader("Content-Encoding"))
    }

    @Test fun `live is sent plain`() {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(ApiResult.Ok(Unit), api.postLive(JSONObject().put("ts", 1)))
        assertNull(server.takeRequest().getHeader("Content-Encoding"))
    }

    @Test fun `a bad server address is rejected without a call`() {
        val bad = UploadApi(OkHttpClient(), { "not a url" }, { "t" }, "ua")
        assertTrue(bad.postLive(JSONObject()) is ApiResult.Rejected)
    }

    @Test fun `retry-after takes seconds only, capped at an hour`() {
        assertEquals(3_600_000L, UploadApi.retryAfterMs("99999"))
        assertNull(UploadApi.retryAfterMs("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(UploadApi.retryAfterMs("0"))
    }

    @Test fun `a token with a character no header allows is a rejection, never a crash`() {
        val pasted = UploadApi(OkHttpClient(), { server.url("/").toString() }, { "bmc_tok\u00A0en" }, "ua")
        assertTrue(pasted.postLive(JSONObject().put("ts", 1)) is ApiResult.Rejected)
        assertTrue(pasted.postBatch(ByteArray(0)) is ApiResult.Rejected)
    }

    @Test fun `token validation takes printable ASCII only`() {
        assertTrue(UploadSettings.isValidToken("bmc_AbC-123_x"))
        assertTrue(UploadSettings.isValidToken("  bmc_padded  "))
        assertFalse(UploadSettings.isValidToken("bmc_tok\u00A0en"))
        assertFalse(UploadSettings.isValidToken("токен"))
        assertFalse(UploadSettings.isValidToken(" "))
    }
}
