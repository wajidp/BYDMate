package com.bydmate.app.plus.upload

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

/** What a call to the server came to, in the terms the spec gives the car (see Retries). */
internal sealed interface ApiResult<out T> {
    data class Ok<T>(val value: T) : ApiResult<T>
    /** 401: stop until the token changes. */
    data object Unauthorized : ApiResult<Nothing>
    /** 409: build the batch again under a new id. */
    data object Conflict : ApiResult<Nothing>
    /** 413: split and send again. */
    data object TooLarge : ApiResult<Nothing>
    /** Any other 4xx: never resend the same body. */
    data class Rejected(val code: Int, val message: String) : ApiResult<Nothing>
    /** Network error, 429, 5xx: retry with backoff, no sooner than [afterMs] when the server said so. */
    data class Retry(val afterMs: Long?, val message: String) : ApiResult<Nothing>
}

internal data class SyncState(val carName: String?, val watermarks: Map<String, Long>)

internal data class BatchAck(val replayed: Boolean, val rejected: Int, val watermarks: Map<String, Long>)

/** The car side of api/openapi.yaml. Blocking; call from Dispatchers.IO. */
internal class UploadApi(
    private val client: OkHttpClient,
    private val baseUrl: () -> String,
    private val token: () -> String,
    private val userAgent: String,
) {

    fun syncState(databaseId: String): ApiResult<SyncState> = guarded {
        val url = url("v1/car/sync-state")?.newBuilder()?.addQueryParameter("database_id", databaseId)?.build()
            ?: return badAddress()
        return call(request(url.toString()).get().build()) { body ->
            val o = JSONObject(body)
            SyncState(
                carName = o.optString("car_name").ifBlank { null },
                watermarks = UploadStore.parseWatermarks(o.optJSONObject("watermarks")?.toString()),
            )
        }
    }

    /** [gzippedBody] is the stored batch exactly as built. */
    fun postBatch(gzippedBody: ByteArray): ApiResult<BatchAck> = guarded {
        val url = url("v1/car/batches") ?: return badAddress()
        val req = request(url.toString())
            .header("Content-Encoding", "gzip")
            .post(gzippedBody.toRequestBody(JSON))
            .build()
        return call(req) { body ->
            val o = JSONObject(body)
            BatchAck(
                replayed = o.optBoolean("replayed"),
                rejected = o.optJSONArray("rejected")?.length() ?: 0,
                watermarks = UploadStore.parseWatermarks(o.optJSONObject("watermarks")?.toString()),
            )
        }
    }

    fun postTrace(tripId: String, trace: JSONObject): ApiResult<Unit> = guarded {
        val url = url("v1/car/trips/$tripId/trace") ?: return badAddress()
        val req = request(url.toString())
            .header("Content-Encoding", "gzip")
            .post(gzip(trace.toString()).toRequestBody(JSON))
            .build()
        return call(req) { }
    }

    /** Small and best effort: sent plain. */
    fun postLive(sample: JSONObject): ApiResult<Unit> = guarded {
        val url = url("v1/car/live") ?: return badAddress()
        return call(request(url.toString()).post(sample.toString().toRequestBody(JSON)).build()) { }
    }

    private fun url(path: String) = baseUrl().trim().trimEnd('/').let { "$it/$path" }.toHttpUrlOrNull()

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${token().trim()}")
        .header("User-Agent", userAgent)

    private fun <T> call(request: Request, parse: (String) -> T): ApiResult<T> = try {
        client.newCall(request).execute().use { r -> classify(r, parse) }
    } catch (e: IOException) {
        ApiResult.Retry(null, e.javaClass.simpleName + (e.message?.let { ": $it" } ?: ""))
    }

    /** OkHttp refuses a header value outside printable ASCII (a pasted token) with an exception. */
    private inline fun <T> guarded(block: () -> ApiResult<T>): ApiResult<T> = try {
        block()
    } catch (e: IllegalArgumentException) {
        ApiResult.Rejected(0, "invalid request: ${e.message}")
    }

    private fun <T> classify(r: Response, parse: (String) -> T): ApiResult<T> {
        val body = r.body?.string().orEmpty()
        return when {
            r.isSuccessful -> try {
                ApiResult.Ok(parse(body))
            } catch (e: org.json.JSONException) {
                ApiResult.Retry(null, "unreadable reply: ${e.message}")
            }
            r.code == HTTP_UNAUTHORIZED -> ApiResult.Unauthorized
            r.code == HTTP_CONFLICT -> ApiResult.Conflict
            r.code == HTTP_TOO_LARGE -> ApiResult.TooLarge
            r.code == HTTP_TOO_MANY || r.code >= HTTP_SERVER_ERROR ->
                ApiResult.Retry(retryAfterMs(r.header("Retry-After")), "HTTP ${r.code}")
            else -> ApiResult.Rejected(r.code, problemText(body) ?: "HTTP ${r.code}")
        }
    }

    private fun badAddress() = ApiResult.Rejected(0, "bad server address")

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_CONFLICT = 409
        private const val HTTP_TOO_LARGE = 413
        private const val HTTP_TOO_MANY = 429
        private const val HTTP_SERVER_ERROR = 500
        private const val MAX_RETRY_AFTER_S = 3_600L

        fun gzip(text: String): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return out.toByteArray()
        }

        /** Seconds only (what the server sends); an HTTP date or junk counts as absent. */
        fun retryAfterMs(header: String?): Long? =
            header?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.coerceAtMost(MAX_RETRY_AFTER_S)?.times(1_000)

        private fun problemText(body: String): String? = try {
            val o = JSONObject(body)
            listOf(o.optString("title"), o.optString("detail")).filter { it.isNotBlank() }.joinToString(": ")
                .ifBlank { null }
        } catch (_: org.json.JSONException) {
            null
        }
    }
}
