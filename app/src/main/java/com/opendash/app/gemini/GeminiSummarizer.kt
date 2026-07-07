package com.opendash.app.gemini

import com.opendash.app.logging.AppLogger
import com.opendash.app.notifications.NotificationEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Summarizes the current notification queue into single-line entries via
 * the Gemini API. Called on-demand when the Notification screen opens, not
 * continuously, to keep API usage and battery impact low.
 */
class GeminiSummarizer(
    private val apiKey: String,
    private val model: String = com.opendash.app.settings.AppSettings.DEFAULT_GEMINI_MODEL,
) {

    /** Returns one summary line per input entry, same order, best-effort (falls back to raw text on any failure). */
    suspend fun summarize(entries: List<NotificationEntry>): List<String> = withContext(Dispatchers.IO) {
        if (entries.isEmpty()) return@withContext emptyList()
        try {
            val prompt = buildString {
                append("Summarize each of these phone notifications as one short line (under 60 characters), ")
                append("same order, one summary per line, no numbering, no extra commentary:\n\n")
                entries.forEachIndexed { i, e -> append("${i + 1}. [${e.appLabel}] ${e.title}: ${e.text}\n") }
            }
            val responseText = callGemini(prompt)
            val lines = responseText.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (lines.size == entries.size) {
                AppLogger.log("Gemini", "Summarized ${entries.size} entries")
                lines
            } else {
                AppLogger.log("Gemini", "Line count mismatch (${lines.size} vs ${entries.size}), using fallback")
                entries.map { fallbackSummary(it) }
            }
        } catch (e: Exception) {
            AppLogger.log("Gemini", "!! Summarize failed: ${e.message}, using fallback")
            entries.map { fallbackSummary(it) }
        }
    }

    private fun fallbackSummary(entry: NotificationEntry): String {
        val combined = if (entry.title.isNotBlank()) "${entry.title}: ${entry.text}" else entry.text
        return if (combined.length > 60) combined.take(57) + "..." else combined
    }

    private fun callGemini(prompt: String): String {
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        // Bound the wait so a slow/hung network can't keep the summarize
        // coroutine (and the loading spinner) alive indefinitely.
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000

        val body = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            ))
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }

        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val responseBody = stream.bufferedReader().use { it.readText() }
        if (responseCode !in 200..299) throw RuntimeException("Gemini API error $responseCode: $responseBody")

        val json = JSONObject(responseBody)
        return json.getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
            .getJSONObject(0)
            .getString("text")
    }
}
