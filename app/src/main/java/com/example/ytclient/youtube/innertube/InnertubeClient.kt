package com.example.ytclient.youtube.innertube

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/**
 * Native Innertube HTTP client — phase A1 of RUNTIME_PERF_PLAN.md ("Option A").
 *
 * Thin, endpoint-shaped POST helpers over OkHttp + kotlinx.serialization: no youtubei.js, no
 * QuickJS, one parse per response, real coroutine parallelism. Response bodies come back as raw
 * [JsonObject] trees; extraction into app models is [InnertubeParser]'s job, keeping "how to talk
 * to YouTube" and "what YouTube's renderers look like" separately testable.
 *
 * Header policy mirrors QuickJsRuntime.performFetch's youtube.com branch (UA + Origin + Referer —
 * googlevideo is NOT this client's business) plus the standard X-YouTube-Client-Name/-Version pair
 * every Innertube web client sends.
 */
class InnertubeClient(
    private val http: OkHttpClient,
    private val session: InnertubeSession,
) {

    /** POST /youtubei/v1/search — page 1 for [query]; [params] optionally carries a protobuf
     *  filter (e.g. `SearchParams.videosOnly`, raw base64 -- this goes straight into a JSON body
     *  field, NOT a URL, so it must NOT be percent-encoded). */
    suspend fun search(query: String, params: String? = null): JsonObject = post(
        "search",
        buildJsonObject {
            put("context", session.context())
            put("query", query)
            params?.let { put("params", it) }
        },
    )

    /** POST /youtubei/v1/search with a continuation token from a previous page. */
    suspend fun searchContinuation(token: String): JsonObject = post(
        "search",
        buildJsonObject {
            put("context", session.context())
            put("continuation", token)
        },
    )

    /** POST /youtubei/v1/browse — a browseId (channel UCID, playlist VL…, feed FE…) plus optional
     *  tab/sort [params]. */
    suspend fun browse(browseId: String, params: String? = null): JsonObject = post(
        "browse",
        buildJsonObject {
            put("context", session.context())
            put("browseId", browseId)
            params?.let { put("params", it) }
        },
    )

    /** POST /youtubei/v1/browse for a playlist. Playlist browseIds are the playlist id with a
     *  "VL" prefix — the id the app carries is already stripped of it (see ChannelPlaylist). */
    suspend fun playlist(playlistId: String): JsonObject =
        browse(if (playlistId.startsWith("VL")) playlistId else "VL$playlistId")

    /** POST /youtubei/v1/browse with a continuation token (channel tabs, playlists, comments). */
    suspend fun browseContinuation(token: String): JsonObject = post(
        "browse",
        buildJsonObject {
            put("context", session.context())
            put("continuation", token)
        },
    )

    /** POST /youtubei/v1/next — watch-next (related videos, comment section entry points). */
    suspend fun next(videoId: String): JsonObject = post(
        "next",
        buildJsonObject {
            put("context", session.context())
            put("videoId", videoId)
        },
    )

    /** POST /youtubei/v1/next with a continuation token (more related / more comments / replies). */
    suspend fun nextContinuation(token: String): JsonObject = post(
        "next",
        buildJsonObject {
            put("context", session.context())
            put("continuation", token)
        },
    )

    /**
     * Search-box autocomplete via YouTube's own suggest endpoint — session-free (no Innertube
     * context at all), which is what makes it the safe first native cutover (A2). The endpoint
     * answers JSONP (`window.google.ac.h([...])`) or, with `xhr=t`, plain JSON; both are handled
     * by trimming to the outermost array. Result shape: `[query, [[suggestion, …], …], {…}]`.
     */
    suspend fun searchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query, "UTF-8")
        val hl = session.hl?.let { "&hl=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val gl = session.gl?.let { "&gl=" + URLEncoder.encode(it, "UTF-8") } ?: ""
        val url = "$SUGGEST_BASE/complete/search?client=youtube&gs_ri=youtube&ds=yt&xhr=t&q=$q$hl$gl"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", InnertubeSession.USER_AGENT)
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("suggest: HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            parseSuggestions(body)
        }
    }

    private suspend fun post(endpoint: String, body: JsonObject): JsonObject =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url("$INNERTUBE_BASE/youtubei/v1/$endpoint?prettyPrint=false")
                .header("User-Agent", InnertubeSession.USER_AGENT)
                .header("Origin", "https://www.youtube.com")
                .header("Referer", "https://www.youtube.com/")
                .header("X-YouTube-Client-Name", InnertubeSession.CLIENT_NAME_ID)
                .header("X-YouTube-Client-Version", session.clientVersion)
                .apply { session.visitorData?.let { header("X-Goog-Visitor-Id", it) } }
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "/$endpoint failed: HTTP ${resp.code} (${text.take(200)})")
                    throw IOException("/$endpoint: HTTP ${resp.code}")
                }
                json.parseToJsonElement(text).jsonObject
            }
        }

    companion object {
        private const val TAG = "InnertubeClient"
        private const val INNERTUBE_BASE = "https://www.youtube.com"
        private const val SUGGEST_BASE = "https://suggestqueries-clients6.youtube.com"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Trim [body] to its outermost JSON array (drops any JSONP `window.google.ac.h(...)`
         * wrapper) and pull the suggestion strings out of `[query, [[text, …], …], {…}]`.
         * Internal-visible for unit tests.
         */
        internal fun parseSuggestions(body: String): List<String> {
            val start = body.indexOf('[')
            val end = body.lastIndexOf(']')
            if (start < 0 || end <= start) return emptyList()
            val root = runCatching { json.parseToJsonElement(body.substring(start, end + 1)) }
                .getOrNull() as? JsonArray ?: return emptyList()
            val list = root.getOrNull(1) as? JsonArray ?: return emptyList()
            return list.mapNotNull { entry ->
                val first = (entry as? JsonArray)?.getOrNull(0)
                (first as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
            }
        }
    }
}
