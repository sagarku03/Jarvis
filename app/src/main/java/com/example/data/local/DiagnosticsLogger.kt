package com.example.data.local

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Anonymized, local-only diagnostics and crash logger.
 * No network uploads. API keys, passwords, and sensitive user text are strictly redacted.
 */
object DiagnosticsLogger {

    private const val MAX_LOG_ENTRIES = 200
    private const val CRASH_LOG_FILE = "crash_log.txt"

    private val logQueue = ConcurrentLinkedQueue<String>()
    private val voiceEventQueue = ConcurrentLinkedQueue<com.example.domain.model.VoiceEvent>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val keyPattern = Regex("AIza[0-9A-Za-z\\-_]{30,}")

    fun install(context: Context) {
        val existingHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                recordCrash(context, thread, throwable)
            }
            existingHandler?.uncaughtException(thread, throwable)
        }
    }

    @Synchronized
    fun log(tag: String, message: String) {
        val sanitized = sanitize(message)
        val timestamp = timeFormat.format(Date())
        val entry = "[$timestamp][$tag] $sanitized"
        logQueue.add(entry)
        while (logQueue.size > MAX_LOG_ENTRIES) {
            logQueue.poll()
        }
    }

    fun logVoiceEvent(type: String, message: String, latencyMs: Long = 0L) {
        val event = com.example.domain.model.VoiceEvent(
            timestampMs = System.currentTimeMillis(),
            type = type,
            message = sanitize(message),
            latencyMs = latencyMs
        )
        voiceEventQueue.add(event)
        while (voiceEventQueue.size > 50) {
            voiceEventQueue.poll()
        }
        log("Voice", "[$type] $message (${latencyMs}ms)")
    }

    fun getRecentVoiceEvents(): List<com.example.domain.model.VoiceEvent> = voiceEventQueue.toList()

    fun clearVoiceLogs() {
        voiceEventQueue.clear()
    }

    fun getRecentLogs(): List<String> = logQueue.toList()

    fun getCrashLog(context: Context): String {
        return runCatching {
            val file = getCrashFile(context)
            if (file.exists()) file.readText() else "No crashes recorded."
        }.getOrDefault("Unable to read crash log.")
    }

    fun shareLog(context: Context) {
        val recent = logQueue.joinToString("\n")
        val crash = getCrashLog(context)
        val report = buildString {
            append("JARVIS Diagnostics Report\n")
            append("Device: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})\n\n")
            append("=== RECENT DIAGNOSTIC EVENTS ===\n")
            append(recent.ifEmpty { "No recent events recorded." })
            append("\n\n=== RECENT CRASH LOG ===\n")
            append(crash)
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "JARVIS Diagnostics Log")
            putExtra(Intent.EXTRA_TEXT, report)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val chooser = Intent.createChooser(intent, "Share Diagnostics Log").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }

    private fun recordCrash(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val rawStack = sw.toString()
        val sanitizedStack = sanitize(rawStack)

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val dump = """
            --------------------------------------------------
            Crash at: $timestamp
            Thread: ${thread.name}
            Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})
            Exception: ${throwable.javaClass.simpleName}
            Message: ${sanitize(throwable.message.orEmpty())}
            Stack Trace:
            $sanitizedStack
            --------------------------------------------------
        """.trimIndent()

        val file = getCrashFile(context)
        file.appendText(dump + "\n\n")
    }

    private fun getCrashFile(context: Context): File {
        val dir = context.noBackupFilesDir ?: context.filesDir
        if (!dir.exists()) dir.mkdirs()
        return File(dir, CRASH_LOG_FILE)
    }

    private fun sanitize(input: String): String {
        if (input.isBlank()) return ""
        return keyPattern.replace(input, "[REDACTED-KEY]")
    }
}
