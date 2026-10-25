package com.bydmate.app.plus.upload

import android.content.Context
import android.os.Environment
import org.json.JSONObject
import java.io.File

/** What the manual «upload last diagnostic dump» button came to. */
internal sealed interface DiagnosticOutcome {
    data class Sent(val fileName: String) : DiagnosticOutcome
    /** Upload off, no token, a rejected token, or the upload database not set up yet. */
    data object NotReady : DiagnosticOutcome
    data object NoDump : DiagnosticOutcome
    data object NoHeader : DiagnosticOutcome
    data class Failed(val message: String) : DiagnosticOutcome
}

/**
 * Sends the header of the newest Settings log recording (bydmate_logs_*.txt) to the cloud:
 * device, settings, probe lines, journals. Never the logcat that follows it, and only when the
 * owner presses the button. The header holds no credentials (token lengths, chat id tail) but
 * does carry free text of the owner's: what was said to the voice assistant, navigation
 * maneuvers, automation rule names, music titles, apps used. [DiagnosticRedactor] sends only the
 * technical sections and masks the rest before anything leaves the car.
 */
internal object DiagnosticUpload {

    const val FILE_PREFIX = "bydmate_logs_"
    /** The line SettingsViewModel.writeDiagnosticHeader ends the header with. */
    const val END_MARKER = "==============================="
    private const val FIRST_LINE = "=== BYDMate diagnostic dump ==="
    /** Real headers are tens of KB; these keep even multi-byte text under the server's 1 MiB. */
    private const val MAX_CHARS = 200_000
    private const val MAX_LINE_CHARS = 20_000
    private const val MAX_BODY_BYTES = 900_000
    private const val TRIES = 5

    /** Where LogRecorder saves a recording, in its order. */
    fun dumpDirs(context: Context): List<File> = listOfNotNull(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        File("/storage/emulated/0/Download"),
        context.getExternalFilesDir(null),
    ).distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath) }

    /** Recordings, newest first. */
    fun recordings(dirs: List<File>): List<File> = dirs.asSequence()
        .flatMap { dir ->
            dir.listFiles { f -> f.isFile && f.name.startsWith(FILE_PREFIX) && f.name.endsWith(".txt") }
                .orEmpty().asSequence()
        }
        .sortedByDescending { it.lastModified() }
        .toList()

    fun latest(dirs: List<File>): File? = recordings(dirs).firstOrNull()

    /**
     * The header up to and including its end line; null when there is none (never the logcat).
     * Throws [java.io.IOException] for a header over the size cap, so it is not mistaken for a
     * file without one.
     */
    fun header(file: File): String? {
        val out = StringBuilder()
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            if (readLine(reader) != FIRST_LINE) return null
            out.append(FIRST_LINE).append('\n')
            while (true) {
                val line = readLine(reader) ?: return null
                out.append(line).append('\n')
                if (line == END_MARKER) return out.toString()
                if (out.length > MAX_CHARS) throw java.io.IOException("header too large")
            }
        }
    }

    /** A line, never longer than [MAX_LINE_CHARS]: a file with no line breaks cannot fill memory. */
    private fun readLine(reader: java.io.Reader): String? {
        val line = StringBuilder()
        while (true) {
            val c = reader.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().removeSuffix("\r")
            line.append(c.toChar())
            if (line.length > MAX_LINE_CHARS) throw java.io.IOException("header line too long")
        }
    }

    /** The header of the newest recording that has one; [problem] says why a try failed. */
    private class Found(val file: File?, val text: String?, val problem: String?)

    /** The newest file can be a recording that has only just started: fall back to an older one. */
    private fun firstHeader(files: List<File>): Found {
        var problem: String? = null
        for (candidate in files.take(TRIES)) {
            try {
                header(candidate)?.let { return Found(candidate, it, problem) }
            } catch (e: java.io.IOException) {
                problem = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
            }
        }
        return Found(null, null, problem)
    }

    private fun mayUpload(settings: UploadSettings): Boolean =
        !UploadRestoreGate.restoring && settings.enabled && settings.token.isNotBlank() && !settings.tokenRejected

    fun send(
        api: UploadApi,
        settings: UploadSettings,
        databaseId: String?,
        appVersion: String,
        dirs: List<File>,
    ): DiagnosticOutcome {
        if (databaseId == null || !mayUpload(settings)) return DiagnosticOutcome.NotReady
        val files = recordings(dirs)
        if (files.isEmpty()) return DiagnosticOutcome.NoDump
        val found = firstHeader(files)
        val file = found.file
        val text = found.text
        if (file == null || text == null) {
            return found.problem?.let { DiagnosticOutcome.Failed(it) } ?: DiagnosticOutcome.NoHeader
        }
        val body = JSONObject()
            .put("database_id", databaseId)
            .put("app_version", appVersion)
            .put("created_at", file.lastModified())
            .put("file_name", file.name)
            .put("text", DiagnosticRedactor.redact(text))
        if (body.toString().toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            return DiagnosticOutcome.Failed("header too large")
        }
        return post(api, settings, body, file.name)
    }

    private fun post(api: UploadApi, settings: UploadSettings, body: JSONObject, fileName: String): DiagnosticOutcome =
        when (val r = api.postDiagnostics(body)) {
            is ApiResult.Ok -> DiagnosticOutcome.Sent(fileName)
            ApiResult.Unauthorized -> {
                settings.tokenRejected = true
                settings.lastError = "token rejected (401)"
                DiagnosticOutcome.Failed("token rejected (401)")
            }
            is ApiResult.Retry -> DiagnosticOutcome.Failed(
                CloudUploader.describe(r) + (r.afterMs?.let { " (try again in ${(it + 999) / 1000} s)" } ?: ""),
            )
            else -> DiagnosticOutcome.Failed(CloudUploader.describe(r))
        }
}
