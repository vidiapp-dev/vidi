package com.example.ytclient.core.sponsorblock

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

/** One auto-skippable segment of a video, in milliseconds. */
data class SponsorSegment(val startMs: Long, val endMs: Long, val category: String)

/**
 * Minimal SponsorBlock client (https://sponsor.ajay.app). Fetches community-submitted segments for a
 * video and returns the auto-skip ones. Only `actionType == "skip"` segments in the requested
 * categories are returned; the player seeks past them during playback.
 */
object SponsorBlock {

    private const val TAG = "SponsorBlock"
    private const val BASE = "https://sponsor.ajay.app/api/skipSegments"

    // The commonly-skipped, non-content categories. (intro/outro/preview left out — many viewers want
    // to keep those; can be made configurable later.)
    private val CATEGORIES = listOf("sponsor", "selfpromo", "interaction", "music_offtopic")

    suspend fun fetch(videoId: String, client: OkHttpClient): List<SponsorSegment> =
        withContext(Dispatchers.IO) {
            val cats = CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "$BASE?videoID=$videoId&categories=$cats"
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.code == 404) return@withContext emptyList() // none submitted
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || body.isBlank()) return@withContext emptyList()
                    parse(body)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "fetch failed for $videoId: ${t.message}")
                emptyList()
            }
        }

    private fun parse(body: String): List<SponsorSegment> {
        val out = ArrayList<SponsorSegment>()
        val arr = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("actionType", "skip") != "skip") continue
            val seg = o.optJSONArray("segment") ?: continue
            if (seg.length() < 2) continue
            val startMs = (seg.optDouble(0, 0.0) * 1000).toLong()
            val endMs = (seg.optDouble(1, 0.0) * 1000).toLong()
            if (endMs > startMs) out.add(SponsorSegment(startMs, endMs, o.optString("category", "")))
        }
        return out.sortedBy { it.startMs }
    }
}
