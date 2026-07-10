package dev.xangma.hidratespark.healthconnect

import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class HomeAssistantClient(private val settings: ConnectionSettings) {
    fun fetchBottles(): List<Bottle> {
        val root = requestJson("/api/hidratespark/bottles")
        val values = root.getJSONArray("bottles")
        return buildList(values.length()) {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                add(
                    Bottle(
                        entryId = value.getString("entry_id"),
                        journalId = value.getString("journal_id"),
                        name = value.optString("name", "HidrateSpark"),
                        address = value.optString("address", ""),
                    ),
                )
            }
        }
    }

    fun fetchSips(entryId: String, after: Long, limit: Int = 200): SipPage {
        val encodedEntryId = URLEncoder.encode(entryId, StandardCharsets.UTF_8.name())
        val root = requestJson(
            "/api/hidratespark/bottles/$encodedEntryId/sips?after=$after&limit=$limit",
        )
        val values = root.getJSONArray("sips")
        val sips = buildList(values.length()) {
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                add(
                    Sip(
                        id = value.getString("id"),
                        sequence = value.getLong("sequence"),
                        timestampSeconds = value.getDouble("timestamp"),
                        volumeMl = value.getInt("volume_ml"),
                    ),
                )
            }
        }
        return SipPage(
            journalId = root.getString("journal_id"),
            sips = sips,
            nextAfter = root.getLong("next_after"),
            hasMore = root.getBoolean("has_more"),
            truncated = root.getBoolean("truncated"),
        )
    }

    private fun requestJson(path: String): JSONObject {
        val connection = URL(settings.baseUrl + path).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer ${settings.token}")
            connection.setRequestProperty("Accept", "application/json")

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                val detail = runCatching { JSONObject(body).optString("message") }
                    .getOrNull()
                    .takeUnless { it.isNullOrBlank() }
                    ?: runCatching { JSONObject(body).optString("error") }.getOrNull()
                throw IOException(
                    when (status) {
                        HttpURLConnection.HTTP_UNAUTHORIZED ->
                            "Home Assistant rejected the access token"
                        HttpURLConnection.HTTP_NOT_FOUND ->
                            "HidrateSpark sync API not found; install this fork and restart Home Assistant"
                        else -> "Home Assistant returned HTTP $status${detail?.let { ": $it" }.orEmpty()}"
                    },
                )
            }
            return try {
                JSONObject(body)
            } catch (error: JSONException) {
                throw IOException("Home Assistant returned invalid JSON", error)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}
