package com.bydmate.app.plus.upload

/**
 * Reduces the diagnostic header to what may leave the car: technical sections only, with the
 * lines that still name a carrier, a bot, an app list or a secret masked. An allow-list on
 * purpose: a section a later version adds to the header stays out until it is looked at.
 */
internal object DiagnosticRedactor {

    internal val ALLOWED_SECTIONS = setOf(
        "settings", "tariff periods", "charging catch-up", "live snapshot", "hybrid probe",
        "automation params", "vehicle data sources", "audio", "displays", "blind spot",
        "trip counters", "helper daemon", "windows", "seats", "seat command journal",
        "steering heat", "fid resolve", "auto backup", "vehicle writes", "fid push",
        "fid recorder", "last crash", "trace", "autostart",
    )
    private val SECTION = Regex("^--- (.+) ---$")

    /** Replaced inside kept lines: carrier, bot name, app lists, secrets that slipped into an error text. */
    private val MASKS = listOf(
        Regex("^apps: .*") to "apps: *",
        Regex("sim=\\S+") to "sim=*",
        Regex("bot=@\\S+") to "bot=@*",
        Regex("chat=\\S+") to "chat=*",
        Regex("home_packages=\\[[^\\]]*]") to "home_packages=[*]",
        Regex("route_navigator=\\S+") to "route_navigator=*",
        Regex("bot\\d+:[A-Za-z0-9_-]+") to "bot*",
        Regex("https?://\\S+") to "<url>",
        Regex("(?i)\\b(token|key|password|secret|authorization)([=:])\\s*\\S+") to "$1$2*",
        Regex("^(\\s*)error: .*") to "$1error: *",
        Regex("^vehicle_kind: error .*") to "vehicle_kind: error *",
        Regex("\\berror=\\S+") to "error=*",
        Regex("\\(failed to gather ([^:)]*):.*\\)") to "(failed to gather $1)",
    )
    private val CRASH_TIME = Regex("^\\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d$")
    private val CRASH_FRAME = Regex("^\\s+(at .*|\\.\\.\\. \\d+ more)$")
    /** `pkg.ClassName` with an optional message: a bare word or a lowercase dotted word is message text. */
    private val CRASH_HEAD = Regex("^((?:Caused by|Suppressed): )?((?:[a-z_]\\w*\\.)+[A-Z][\\w$]*)(?::.*)?$")
    /** Trace lines that are a timestamped history of the apps the owner opened, or a package list. */
    private val APP_TIMELINE = Regex(
        "\\b(foreground|hint-ignored|not-main|cluster-loss|cluster-density-unsafe|cluster_music)\\b|" +
            "\\b(pkgs?|packages|sessions|owner)=|(^|\\s)(split|cluster)\\s",
    )
    /** A `key: value` / `key=value` line: the start of the next entry after a multi-line error text. */
    private val KEY_LINE = Regex("^[a-z][a-z0-9_]*[:=]")
    private val ERROR_LINE = Regex("^(\\s*error: |vehicle_kind: error)")
    /** `(failed to gather x: <message>` whose message runs on over several lines. */
    private val GATHER_OPEN = Regex("\\(failed to gather ([^:)]*):.*$")

    /**
     * [header] reduced to [ALLOWED_SECTIONS] (the lines above the first section are kept), masked.
     * Every other section is replaced by its title and one note line.
     */
    fun redact(header: String): String {
        val state = State()
        val out = StringBuilder()
        for (line in header.lines()) state.accept(line)?.let { out.append(it).append('\n') }
        return out.toString().replace("\u0000", "").trimEnd('\n') + "\n"
    }

    private fun mask(line: String): String =
        MASKS.fold(line) { acc, (pattern, replacement) -> pattern.replace(acc, replacement) }

    /**
     * A stack trace without its exception messages (they hold whatever text failed to parse):
     * times, `at` frames and exception class names stay, a message and any other line go.
     */
    private fun crashLine(line: String): String? = when {
        line.isEmpty() || line == "(none)" || CRASH_TIME.matches(line) || CRASH_FRAME.matches(line) -> line
        else -> CRASH_HEAD.matchEntire(line)?.let { it.groupValues[1] + it.groupValues[2] }
    }

    /** Where the header is: which section, and whether a multi-line detail is being skipped. */
    private class State {
        private var section: String? = null
        private var keep = true
        /** Skipping the rest of an exception message that runs over several lines. */
        private var inGather = false
        private var inError = false
        private var inSpawnDetail = false

        fun accept(line: String): String? {
            val title = SECTION.matchEntire(line)?.groupValues?.get(1)
            val boundary = title != null || line == DiagnosticUpload.END_MARKER
            if (boundary) {
                inGather = false
                inError = false
            }
            return when {
                title != null -> enter(title, line)
                line == DiagnosticUpload.END_MARKER -> line.also { keep = true }
                !keep || inGather -> null
                inError && !KEY_LINE.containsMatchIn(line) -> null
                else -> filtered(line)?.let(::mask)
            }
        }

        private fun enter(title: String, line: String): String {
            section = title
            keep = title in ALLOWED_SECTIONS
            inSpawnDetail = false
            return if (keep) line else "$line (left out of the upload)"
        }

        private fun filtered(line: String): String? = when {
            line.contains("(failed to gather") -> {
                // The message may run over several lines whatever its first line ends with; only a
                // one-line failure above the first section (nothing to resume at) is trusted to end.
                inGather = section != null || !line.trimEnd().endsWith(")")
                GATHER_OPEN.replace(line, "(failed to gather $1)")
            }
            ERROR_LINE.containsMatchIn(line) -> line.also { inError = true }
            section == "last crash" -> crashLine(line)
            section == "trace" && APP_TIMELINE.containsMatchIn(line) -> null
            section == "helper daemon" -> spawnLine(line)
            else -> line
        }

        /** The daemon log tail under `last_spawn_failure:` can hold exception text: the reason stays. */
        private fun spawnLine(line: String): String? {
            if (line.startsWith("last_spawn_failure")) inSpawnDetail = true
            else if (inSpawnDetail && line.startsWith("  ")) return null
            else inSpawnDetail = false
            return line
        }
    }
}
