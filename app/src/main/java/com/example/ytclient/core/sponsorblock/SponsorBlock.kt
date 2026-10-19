package com.example.ytclient.core.sponsorblock

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.security.MessageDigest

/** One auto-skippable segment of a video, in milliseconds. */
data class SponsorSegment(val startMs: Long, val endMs: Long, val category: String)

/**
 * Minimal SponsorBlock client (https://sponsor.ajay.app). Fetches community-submitted segments for a
 * video and returns the auto-skip ones. Only `actionType == "skip"` segments in the requested
 * categories are returned; the player seeks past them during playback.
 *
 * Privacy: uses SponsorBlock's k-anonymity endpoint, `GET /api/skipSegments/:sha256HashPrefix`
 * (API docs, checked 2026-10-20: "the first 4 - 32 characters (4 is recommended)"). We send only the
 * first [HASH_PREFIX_LENGTH] hex characters of SHA-256(videoId); the server answers with the segments
 * of every video sharing that prefix, and we keep the entry whose `videoID` is ours. So the server
 * never learns which video was opened. The old `?videoID=` form told it every video, and SponsorBlock
 * is on by default.
 */
object SponsorBlock {

    private const val TAG = "SponsorBlock"
    private const val BASE = "https://sponsor.ajay.app/api/skipSegments"
    private const val HASH_PREFIX_LENGTH = 4 // the API docs' recommendation

    // The commonly-skipped, non-content categories. (intro/outro/preview left out — many viewers want
    // to keep those; can be made configurable later.)
    private val CATEGORIES = listOf("sponsor", "selfpromo", "interaction", "music_offtopic")

    suspend fun fetch(videoId: String, client: OkHttpClient): List<SponsorSegment> =
        withContext(Dispatchers.IO) {
            val cats = CATEGORIES.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "$BASE/${hashPrefix(videoId)}?categories=$cats"
            try {
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (resp.code == 404) return@withContext emptyList() // none submitted
                    val body = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful || body.isBlank()) return@withContext emptyList()
                    parse(body, videoId)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "fetch failed for $videoId: ${t.message}")
                emptyList()
            }
        }

    private fun hashPrefix(videoId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(HASH_PREFIX_LENGTH)

    /**
     * The hash-prefix response is one entry per matching video, `[{videoID, segments: [...]}]`;
     * everything except our own [videoId]'s entry is other people's videos and is ignored.
     */
    private fun parse(body: String, videoId: String): List<SponsorSegment> {
        val videos = runCatching { JSONArray(body) }.getOrNull() ?: return emptyList()
        for (i in 0 until videos.length()) {
            val v = videos.optJSONObject(i) ?: continue
            if (v.optString("videoID") != videoId) continue
            return parseSegments(v.optJSONArray("segments") ?: return emptyList())
        }
        return emptyList()
    }

    private fun parseSegments(arr: JSONArray): List<SponsorSegment> {
        val out = ArrayList<SponsorSegment>()
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
