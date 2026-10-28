package com.voxstudio.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * In-app reporting of generated audio, as Google Play requires for apps that generate content with AI.
 * A report holds only what the user submits: a reason, an optional note, the text that was spoken and
 * whether a built-in or cloned voice was used. No audio, recordings or device identifiers are sent.
 * Reports are queued on the phone and sent to Firestore's REST API when a connection is available,
 * so reporting works even though the app is normally used offline.
 */
object Reports {
    val reasons = listOf("Offensive or harmful content", "Impersonation or deceptive use", "Sexual content", "Other")
    data class Report(val reason: String, val note: String, val text: String, val voiceKind: String)

    const val NOTE_LIMIT = 1000
    const val TEXT_LIMIT = 600
    private val sender = Executors.newSingleThreadExecutor()
    val configured get() = BuildConfig.REPORT_PROJECT_ID.isNotBlank() && BuildConfig.REPORT_API_KEY.isNotBlank()
    private fun queue(context: Context) = File(context.filesDir, "reports").also { it.mkdirs() }

    /** Firestore REST document for one report; field names match the security rules in docs/play-console.md. */
    fun document(report: Report, createdAt: Date): String {
        fun string(value: String) = JSONObject().put("stringValue", value)
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(createdAt)
        val fields = JSONObject()
            .put("reason", string(report.reason.take(64)))
            .put("note", string(report.note.trim().take(NOTE_LIMIT)))
            .put("text", string(report.text.trim().take(TEXT_LIMIT)))
            .put("voiceKind", string(report.voiceKind))
            .put("appVersion", string(BuildConfig.VERSION_NAME))
            .put("android", JSONObject().put("integerValue", android.os.Build.VERSION.SDK_INT.toString()))
            .put("createdAt", JSONObject().put("timestampValue", iso))
        return JSONObject().put("fields", fields).toString()
    }

    /** Saves the report on the phone, then tries to send everything that is waiting. */
    fun submit(context: Context, report: Report) {
        require(report.reason in reasons) { "Choose a reason." }
        val dir = queue(context)
        val temp = File(dir, ".pending.tmp")
        temp.writeText(document(report, Date()))
        check(temp.renameTo(File(dir, "${System.currentTimeMillis()}-${UUID.randomUUID()}.json"))) { "Could not save the report." }
        flush(context.applicationContext)
    }

    fun pending(context: Context) = queue(context).listFiles { file -> file.name.endsWith(".json") }?.size ?: 0

    /** Sends queued reports in order; stops at the first failure and tries again on the next call. */
    fun flush(context: Context) {
        if (!configured) return
        sender.execute {
            val endpoint = "https://firestore.googleapis.com/v1/projects/${BuildConfig.REPORT_PROJECT_ID}" +
                "/databases/(default)/documents/reports?key=${BuildConfig.REPORT_API_KEY}"
            queue(context).listFiles { file -> file.name.endsWith(".json") }?.sortedBy { it.name }?.forEach { file ->
                val code = runCatching {
                    val connection = URL(endpoint).openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 15000; connection.readTimeout = 15000
                        connection.requestMethod = "POST"; connection.doOutput = true
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        connection.outputStream.use { it.write(file.readBytes()) }
                        connection.responseCode
                    } finally { connection.disconnect() }
                }.getOrDefault(-1)
                // Sent, or permanently rejected (bad request, not rate limiting): either way it leaves the queue.
                // Offline, rate limited or server errors keep it for the next attempt.
                if (code in 200..299 || (code in 400..499 && code != 429)) file.delete() else return@execute
            }
        }
    }
}
