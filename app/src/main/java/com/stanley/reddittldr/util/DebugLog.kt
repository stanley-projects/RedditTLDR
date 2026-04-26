package com.stanley.reddittldr.util

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Per-session debug log. A "session" begins when the bubble is tapped and ends when
 * a summary is shown (or extraction fails). Sessions are buffered in-memory and
 * flushed to disk so they survive process death — Stanley shares the file from
 * Settings to send back what the app actually did.
 *
 * NOTHING sensitive should be logged: no API key, no full message bodies sent to
 * Claude beyond character counts. Snippets of post text are intentionally short
 * and explicitly marked as snippets.
 *
 * --- TO REMOVE THIS FEATURE LATER ---
 * 1. Delete this file (util/DebugLog.kt).
 * 2. Delete the "Debug logs" Card block in ui/SettingsScreen.kt.
 * 3. Project-wide find/delete: `import com.stanley.reddittldr.util.DebugLog`
 *    and any line matching `DebugLog\.` — all calls are stateless one-liners
 *    so deleting them never affects control flow.
 * 4. Remove the `DebugLog.init(this)` call from MainActivity.onCreate and
 *    BubbleService.onCreate.
 */
object DebugLog {

    private const val MAX_SESSIONS = 8
    private const val LOG_FILE = "reddittldr_debug.log"

    private val sessions = ArrayDeque<Session>()
    private var current: Session? = null
    private var fileDir: File? = null

    private val timestampFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun init(context: Context) {
        if (fileDir == null) {
            fileDir = context.filesDir
            // Lazy-rehydrate previous session content on next dump; we don't read on init.
        }
    }

    @Synchronized
    fun startSession(label: String) {
        finishCurrent()
        val s = Session(label = label, startedAt = System.currentTimeMillis())
        current = s
        sessions.addLast(s)
        while (sessions.size > MAX_SESSIONS) sessions.removeFirst()
        s.lines += "[${timestampFmt.format(Date(s.startedAt))}] ── session start: $label"
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val s = current ?: return
        s.lines += "[${timestampFmt.format(Date())}] [$tag] $message"
    }

    @Synchronized
    fun logKv(tag: String, vararg pairs: Pair<String, Any?>) {
        val rendered = pairs.joinToString(" ") { (k, v) -> "$k=${formatValue(v)}" }
        log(tag, rendered)
    }

    @Synchronized
    fun finishCurrent(outcome: String? = null) {
        val s = current ?: return
        if (outcome != null) {
            s.lines += "[${timestampFmt.format(Date())}] ── session end: $outcome"
        }
        current = null
        flushToFile()
    }

    @Synchronized
    fun dump(): String = buildString {
        append("RedditTLDR debug log — ")
        append(dateFmt.format(Date()))
        append('\n')
        append("(most recent ").append(sessions.size).append(" session(s))\n")
        sessions.toList().asReversed().forEachIndexed { i, s ->
            append("\n══════ Session ").append(sessions.size - i)
            append(" — ").append(s.label)
            append(" — ").append(dateFmt.format(Date(s.startedAt)))
            append(" ══════\n")
            s.lines.forEach { append(it).append('\n') }
        }
    }

    @Synchronized
    fun clear() {
        sessions.clear()
        current = null
        fileDir?.let { File(it, LOG_FILE).delete() }
    }

    /** Truncate string to [max] chars with ellipsis suffix. Used for body snippets. */
    fun snippet(text: String, max: Int = 160): String {
        val flat = text.replace('\n', '⏎').replace('\r', ' ')
        return if (flat.length <= max) flat else flat.take(max) + "…(+${flat.length - max} chars)"
    }

    private fun formatValue(v: Any?): String = when (v) {
        null -> "null"
        is String -> if (v.contains(' ')) "\"$v\"" else v
        else -> v.toString()
    }

    private fun flushToFile() {
        val dir = fileDir ?: return
        try {
            File(dir, LOG_FILE).writeText(dump())
        } catch (_: Exception) {
        }
    }

    private data class Session(
        val label: String,
        val startedAt: Long,
        val lines: MutableList<String> = mutableListOf()
    )
}
