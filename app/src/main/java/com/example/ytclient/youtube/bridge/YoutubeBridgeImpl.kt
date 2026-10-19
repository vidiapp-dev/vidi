package com.example.ytclient.youtube.bridge

import android.content.Context
import android.util.Log
import com.example.ytclient.core.storage.SessionStore
import com.example.ytclient.core.webpo.WebPoTokenProvider
import com.example.ytclient.youtube.innertube.CommentsParser
import com.example.ytclient.youtube.innertube.HomeFeedEngine
import com.example.ytclient.youtube.innertube.InnertubeClient
import com.example.ytclient.youtube.innertube.InnertubeParser
import com.example.ytclient.youtube.innertube.InnertubeSession
import com.example.ytclient.youtube.innertube.SearchParams
import com.example.ytclient.sabr.SabrInfoDto
import com.example.ytclient.youtube.models.ChannelInfo
import com.example.ytclient.youtube.models.ChannelPlaylistResult
import com.example.ytclient.youtube.models.CommentPage
import com.example.ytclient.youtube.models.CommentReplies
import com.example.ytclient.youtube.models.DiscoverChannelsResult
import com.example.ytclient.youtube.models.DiscoveredChannel
import com.example.ytclient.youtube.models.LegacyPlaybackInfo
import com.example.ytclient.youtube.models.PoTokenInfo
import com.example.ytclient.youtube.models.SearchResult
import com.example.ytclient.youtube.models.SearchSuggestions
import com.example.ytclient.youtube.models.SearchVideo
import com.example.ytclient.youtube.models.VideoInfo
import com.example.ytclient.youtube.runtime.QuickJsRuntime
import com.example.ytclient.youtube.runtime.detectRegion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The single implementation of [YoutubeBridge]. Owns the QuickJS runtimes and
 * marshals between Kotlin types and the JSON the runtime returns.
 *
 * D1 (RUNTIME_PERF_PLAN.md): there are TWO [QuickJsRuntime] contexts, not one. QuickJS is
 * single-threaded per engine, so the old single engine + single call lock meant every call ran
 * strictly head-of-line — opening a video queued its playback-critical `getSabrInfo` behind
 * whatever feed/comments/channel parsing happened to be mid-flight. Splitting by workload class
 * gives each class its own engine thread + queue:
 *
 *  • [playbackRuntime] — everything on or near the playback path: warmup/PO-token state,
 *    getSabrInfo, getVideoInfo, getLegacyPlayback, getCaptionVtt. These deliberately still share
 *    ONE queue with each other (loadInternal's "fetchSabr goes first" ordering stays meaningful).
 *  • [browseRuntime] — feed/search/related/comments/channel browsing.
 *
 * Session-continuation pairs (search/getMoreSearch, getHomeFeed/getMoreHomeFeed, getRelated/
 * getMoreRelated, getComments/getMoreComments/getCommentReplies) live in JS globals, so each pair
 * MUST stay within one context — the routing below keeps every pair together. Both contexts load
 * the same bundle and share [sessionStore] (client-probe cache, PO-token cache) and the process-
 * wide [WebPoTokenProvider] (which serialises actual mints behind its own mutex). Each context
 * creates its own anonymous Innertube session — visitorData is generated locally per session
 * anyway ([generate_session_locally] in index.js), and browse calls don't need attestation, so
 * the two identities never conflict.
 */
class YoutubeBridgeImpl(
    context: Context,
    webPoProvider: WebPoTokenProvider,
) : YoutubeBridge {

    private val sessionStore = SessionStore(context.applicationContext)
    private val region = detectRegion(context.applicationContext)
    private val playbackRuntime = QuickJsRuntime(
        context.applicationContext,
        sessionStore,
        webPoProvider,
        region.location,
        region.lang,
        region.displayName,
        name = "js-playback",
    )
    private val browseRuntime = QuickJsRuntime(
        context.applicationContext,
        sessionStore,
        webPoProvider,
        region.location,
        region.lang,
        region.displayName,
        name = "js-browse",
    )
    private val json = Json { ignoreUnknownKeys = true }

    private val appContext = context.applicationContext

    // Native Innertube client (Option A, RUNTIME_PERF_PLAN.md) — replaces the browse QuickJS
    // context endpoint group by endpoint group; getSearchSuggestions is the first cutover (A2).
    // Its own small OkHttp stack: browse-sized JSON payloads, nothing like SABR's media reads.
    // clientVersionSource: see bundledWebClientVersion below — YouTube 400-rejects stale client
    // versions, so the native session reads the current one out of the bundled youtubei.js
    // runtime instead of trusting a pinned constant.
    private val innertube = InnertubeClient(
        OkHttpClient.Builder()
            // NO `.dispatcher(...)` here, deliberately — and if you're about to add one to raise
            // `maxRequestsPerHost`, read this first. It has no effect on this client.
            //
            // OkHttp enforces `maxRequests`/`maxRequestsPerHost` only in
            // `Dispatcher.promoteAndExecute()`, which iterates `readyAsyncCalls` — i.e. calls made
            // with `enqueue()`. A synchronous `execute()` goes through `Dispatcher.executed()`,
            // which just appends to `runningSyncCalls` with no limit check (both verified in
            // okhttp-4.12.0's Dispatcher.kt). InnertubeClient only ever calls `execute()`, so
            // every Innertube request has always run with exactly as much concurrency as the
            // coroutines issuing it.
            //
            // 0d52585 got this wrong: it set `maxRequestsPerHost = 16` here on the theory that the
            // home feed's 22-request opening warm was being serialised into waves of 5. It wasn't
            // — the warm was already fully concurrent — so that change did nothing at runtime, and
            // the latency win its commit message describes never happened. The same mistake then
            // produced a second wrong diagnosis: that raising the limit had removed an accidental
            // brake on getChannelVideosBatch/discoverSimilarChannels. There was never a brake.
            // Those fan-outs were unbounded from the day they were written.
            //
            // The thumbnail loader's identical-looking fix in App.onCreate IS real: coil's
            // HttpUriFetcher uses `Call.await()`, which `enqueue()`s. Whether a dispatcher limit
            // means anything depends entirely on which of the two call styles the client uses.
            //
            // So concurrency on this client is governed where the coroutines are launched: by
            // construction for the home feed (HomeFeedEngine's per-bucket warm caps), and by an
            // explicit Semaphore for each unbounded fan-out (SUBS_FANOUT_PARALLELISM,
            // DISCOVER_PARALLELISM below).
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build(),
        InnertubeSession(
            region.location,
            region.lang,
            clientVersionSource = { bundledWebClientVersion() },
        ),
    )

    /** Native home-feed candidate generator (A3) — the 5-bucket weighted blend, natively. */
    private val homeFeedEngine = HomeFeedEngine(innertube, region.displayName)

    /**
     * The WEB client version the bundled youtubei.js runtime would itself send — extracted
     * straight from the `youtube-runtime.js` asset (its CLIENTS table: `WEB: { NAME: "WEB",
     * VERSION: "…" }`). The bundle is rebuilt from the youtubei.js npm dependency, whose releases
     * track YouTube's current version, so the native client auto-follows every `npm run build`.
     * A ~1.5 MB asset read + one regex, invoked ONCE lazily from the session's first request
     * (already on Dispatchers.IO inside InnertubeClient). Null on any failure → the session falls
     * back to InnertubeSession.DEFAULT_CLIENT_VERSION.
     */
    private fun bundledWebClientVersion(): String? = runCatching {
        appContext.assets.open(QuickJsRuntime.BUNDLE_ASSET).bufferedReader().use { reader ->
            WEB_CLIENT_VERSION_RE.find(reader.readText())?.groupValues?.get(1)
        }
    }.getOrNull().also {
        if (it == null) Log.w(TAG, "no WEB client version found in bundle — using built-in fallback")
        else Log.i(TAG, "native Innertube client version from bundle: $it")
    }

    /** The playback-class context, initialised on first use (idempotent, per-instance). */
    private suspend fun playback(): QuickJsRuntime {
        playbackRuntime.initialize()
        return playbackRuntime
    }

    /** The browse-class context, initialised on first use (idempotent, per-instance). */
    private suspend fun browse(): QuickJsRuntime {
        browseRuntime.initialize()
        return browseRuntime
    }

    override suspend fun initialize() {
        // ONLY the playback context is warmed eagerly. Evaluating the 1.5 MB bundle costs real CPU
        // (~0.7s on an emulator, more with two contexts competing), and since A2/A3 the browse
        // context serves nothing on the startup path — search, suggestions and the home feed are
        // all native now. It initialises on demand instead (first related/comments/channel call,
        // or a native fallback), which keeps that cost off cold start entirely. Once A4/A5 land it
        // should disappear altogether.
        playbackRuntime.initialize()
    }

    override suspend fun warmup() {
        playback().callAsync("warmup")
    }

    // ── Native search session (A2, RUNTIME_PERF_PLAN.md) ───────────────────
    // Mirrors the JS runtime's `searchSession`: the continuation token for the next page plus the
    // ids already handed out, so later pages never repeat a card (index.js's freshVideos()).
    // Non-null only while the CURRENT search session is native — a fallback to the JS path clears
    // it so getMoreSearch continues on whichever side actually served page 1. Note the same
    // sharing quirk the JS session has, on purpose: category feeds (HomeViewModel.loadCategory)
    // go through search() too, so a search-bar query and a category tab share this one session —
    // HomeViewModel already handles that by reloading the category on clearSearch().
    private class NativeSearchSession(var continuation: String?, val seen: MutableSet<String>)
    @Volatile private var nativeSearch: NativeSearchSession? = null

    override suspend fun search(query: String): SearchResult {
        return try {
            val page = InnertubeParser.parseSearch(innertube.search(query))
            nativeSearch = NativeSearchSession(
                page.continuation,
                page.result.videos.mapTo(HashSet()) { it.id },
            )
            Log.i(
                TAG,
                "native search(\"$query\") → ${page.result.videos.size} videos, " +
                    "${page.result.channels.size} channels, more=${page.result.hasMore}",
            )
            page.result
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native search failed, falling back to JS: ${t.message}")
            nativeSearch = null // continuations must follow the side that served page 1
            val raw = browse().callAsync("search", query)
            json.decodeFromString(SearchResult.serializer(), raw)
        }
    }

    override suspend fun getSearchSuggestions(query: String): SearchSuggestions {
        // A2 (RUNTIME_PERF_PLAN.md): first native cutover. The suggest endpoint needs no Innertube
        // session at all, so this skips the JS runtime — and, on a cold start, its whole
        // bundle-eval/session bring-up — entirely. JS path stays as the fallback while the native
        // client earns trust; remove it once A2 completes.
        return try {
            SearchSuggestions(innertube.searchSuggestions(query))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native getSearchSuggestions failed, falling back to JS: ${t.message}")
            val raw = browse().callAsync("getSearchSuggestions", query)
            json.decodeFromString(SearchSuggestions.serializer(), raw)
        }
    }

    // ── Native home feed session (A3, RUNTIME_PERF_PLAN.md) ────────────────
    // Non-null only while the current feed session is native; a page-1 fallback to JS clears it so
    // getMoreHomeFeed continues on whichever side actually built the session (same rule as search).
    @Volatile private var nativeHome: HomeFeedEngine.Session? = null

    override suspend fun getHomeFeed(
        historyVideoIds: List<String>,
        subscribedChannelIds: List<String>,
        recentSearchTerms: List<String>,
        discoverChannelIds: List<String>,
        enabledTopics: List<String>,
        topicWeights: Map<String, Float>,
    ): SearchResult {
        try {
            val session = homeFeedEngine.createSession(
                enabledTopics = enabledTopics,
                topicWeights = topicWeights,
                subscribedChannelIds = subscribedChannelIds,
                historyVideoIds = historyVideoIds,
                searchTerms = recentSearchTerms,
                discoverChannelIds = discoverChannelIds,
            )
            val videos = homeFeedEngine.pullBatch(session)
            // An empty page 1 means every bucket failed or had nothing — fall back rather than
            // hand the UI a blank feed (HomeViewModel would render "Nothing to show").
            if (videos.isEmpty()) throw IllegalStateException("native home feed returned nothing")
            nativeHome = session
            Log.i(TAG, "native getHomeFeed → ${videos.size} (buckets=${session.buckets.size})")
            return SearchResult(videos = videos, hasMore = session.buckets.isNotEmpty())
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native getHomeFeed failed, falling back to JS: ${t.message}")
            nativeHome = null
        }
        // callAsync JSON-encodes each arg; pass the id/term lists as JSON-array strings, and
        // topicWeights (phase 14) as a JSON-object string, the runtime parses.
        val weightsJson = org.json.JSONObject().apply {
            topicWeights.forEach { (topic, weight) -> put(topic, weight.toDouble()) }
        }.toString()
        val raw = browse().callAsync(
            "getHomeFeed",
            org.json.JSONArray(historyVideoIds).toString(),
            org.json.JSONArray(subscribedChannelIds).toString(),
            org.json.JSONArray(recentSearchTerms).toString(),
            org.json.JSONArray(discoverChannelIds).toString(),
            org.json.JSONArray(enabledTopics).toString(),
            weightsJson,
        )
        return json.decodeFromString(SearchResult.serializer(), raw)
    }

    override suspend fun getMoreHomeFeed(): SearchResult {
        val session = nativeHome
        if (session != null) {
            // No JS fallback: the JS runtime never built this session, so it couldn't continue it.
            return try {
                val videos = homeFeedEngine.pullBatch(session)
                val hasMore = videos.isNotEmpty() && session.buckets.any { !it.first.exhausted }
                Log.i(TAG, "native getMoreHomeFeed → +${videos.size} (more=$hasMore)")
                SearchResult(videos = videos, hasMore = hasMore)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "native getMoreHomeFeed failed: ${t.message}")
                SearchResult(hasMore = false)
            }
        }
        val raw = browse().callAsync("getMoreHomeFeed")
        return json.decodeFromString(SearchResult.serializer(), raw)
    }

    override suspend fun getMoreSearch(): SearchResult {
        val s = nativeSearch
        if (s != null) {
            // No fallback to JS here: the JS runtime never saw this query, so its own session
            // couldn't continue it — an exhausted/failed native continuation just ends paging,
            // same as the JS path behaves when its continuation runs dry.
            val token = s.continuation ?: return SearchResult(hasMore = false)
            return try {
                val c = InnertubeParser.collect(innertube.searchContinuation(token))
                s.continuation = c.continuation
                val fresh = c.videos.filter { s.seen.add(it.id) }
                Log.i(TAG, "native getMoreSearch → +${fresh.size} (more=${c.continuation != null})")
                SearchResult(videos = fresh, hasMore = c.continuation != null)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "native getMoreSearch failed: ${t.message}")
                SearchResult(hasMore = false)
            }
        }
        val raw = browse().callAsync("getMoreSearch")
        return json.decodeFromString(SearchResult.serializer(), raw)
    }

    // ── Native related + comments sessions (A4, RUNTIME_PERF_PLAN.md) ──────
    private class NativeRelatedSession(var continuation: String?, val seen: MutableSet<String>, val videoId: String)
    @Volatile private var nativeRelated: NativeRelatedSession? = null

    private class NativeCommentsSession(
        var continuation: String?,
        val replyTokens: MutableMap<String, String>,
        val sortTokens: MutableMap<String, String>,
    )
    @Volatile private var nativeComments: NativeCommentsSession? = null

    override suspend fun getRelated(videoId: String): SearchResult {
        return try {
            val c = InnertubeParser.parseRelated(innertube.next(videoId), videoId)
            nativeRelated = NativeRelatedSession(
                c.continuation,
                c.videos.mapTo(HashSet()) { it.id },
                videoId,
            )
            Log.i(TAG, "native getRelated → ${c.videos.size} (more=${c.continuation != null})")
            SearchResult(videos = c.videos, hasMore = c.continuation != null)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native getRelated failed, falling back to JS: ${t.message}")
            nativeRelated = null
            val raw = browse().callAsync("getRelated", videoId)
            json.decodeFromString(SearchResult.serializer(), raw)
        }
    }

    override suspend fun getMoreRelated(): SearchResult {
        val s = nativeRelated
        if (s != null) {
            val token = s.continuation ?: return SearchResult(hasMore = false)
            return try {
                val c = InnertubeParser.parseRelated(innertube.nextContinuation(token), s.videoId)
                s.continuation = c.continuation
                val fresh = c.videos.filter { s.seen.add(it.id) }
                Log.i(TAG, "native getMoreRelated → +${fresh.size} (more=${c.continuation != null})")
                SearchResult(videos = fresh, hasMore = c.continuation != null)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "native getMoreRelated failed: ${t.message}")
                SearchResult(hasMore = false)
            }
        }
        val raw = browse().callAsync("getMoreRelated")
        return json.decodeFromString(SearchResult.serializer(), raw)
    }

    /** Uploads for one channel, author/channelId backfilled from the header (A5). */
    private suspend fun nativeChannelVideos(channelId: String): List<SearchVideo> {
        val response = innertube.browse(channelId, SearchParams.CHANNEL_VIDEOS_TAB)
        val name = InnertubeParser.channelTitle(response)
        return InnertubeParser.collect(response).videos.map { v ->
            v.copy(author = v.author.ifBlank { name }, channelId = v.channelId ?: channelId)
        }
    }

    override suspend fun getChannelVideos(channelId: String): SearchResult {
        return try {
            val videos = nativeChannelVideos(channelId)
            if (videos.isEmpty()) throw IllegalStateException("no channel videos parsed")
            Log.i(TAG, "native getChannelVideos($channelId) → ${videos.size}")
            SearchResult(videos = videos)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native getChannelVideos failed, falling back to JS: ${t.message}")
            val raw = browse().callAsync("getChannelVideos", channelId)
            json.decodeFromString(SearchResult.serializer(), raw)
        }
    }

    override suspend fun getChannelVideosBatch(channelIds: List<String>): SearchResult {
        return try {
            // CONCURRENT across channels — the JS side had to await these through one engine; this
            // is the Subscriptions tab's whole load, so it's the most visible win of A5.
            //
            // BOUNDED, explicitly — see SUBS_FANOUT_PARALLELISM. This used to launch one request
            // per subscribed channel with no limit at all (SubscriptionsViewModel passes every
            // sub, and the HTTP client never limited synchronous calls), so N subscriptions meant
            // N browses in flight AND N large channel-tab documents being parsed at once on
            // Dispatchers.IO. The permit wraps fetch AND parse ([nativeChannelVideos] does both),
            // because the parse is as much of the cost as the network.
            val gate = Semaphore(SUBS_FANOUT_PARALLELISM)
            val videos = coroutineScope {
                channelIds.map { id ->
                    async { runCatching { gate.withPermit { nativeChannelVideos(id) } }.getOrDefault(emptyList()) }
                }
                    .awaitAll()
                    .flatten()
            }
            if (videos.isEmpty()) throw IllegalStateException("no batch channel videos parsed")
            Log.i(TAG, "native getChannelVideosBatch(${channelIds.size}) → ${videos.size}")
            SearchResult(videos = videos)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native getChannelVideosBatch failed, falling back to JS: ${t.message}")
            val raw = browse().callAsync("getChannelVideosBatch", org.json.JSONArray(channelIds).toString())
            json.decodeFromString(SearchResult.serializer(), raw)
        }
    }

    override suspend fun discoverSimilarChannels(subscribedChannelIds: List<String>): DiscoverChannelsResult {
        return try {
            if (subscribedChannelIds.isEmpty()) return DiscoverChannelsResult()
            val seeds = subscribedChannelIds.shuffled().take(MAX_DISCOVER_SEEDS)
            val exclude = subscribedChannelIds.toSet()
            // Two hops, both fanned out concurrently: each seed channel's newest uploads, then each
            // sampled video's related channels.
            //
            // ONE gate across both hops, at DISCOVER_PARALLELISM. This is a background pass (at
            // most daily, kicked off by HomeViewModel once the feed has painted) that used to put
            // MAX_DISCOVER_SEEDS x (1 + DISCOVER_VIDEOS_PER_SEED) = 18 requests and their parses in
            // flight at once with no bound at all — landing, by design, right after page 1 and
            // so right on top of the grouped feed's auto-paging. Its whole job is to be invisible,
            // so it gets the smallest share.
            //
            // The permit is taken around each NETWORK CALL + PARSE, never around a whole `async`
            // body, and that placement is load-bearing: a seed's coroutine spawns the second hop's
            // coroutines, so if it held a permit while awaiting them, DISCOVER_PARALLELISM seed
            // coroutines could hold every permit while their children wait for one forever — a
            // deadlock that a test with few seeds would never show. Releasing after the first hop
            // and before spawning the second is what makes a single shared gate safe here.
            val gate = Semaphore(DISCOVER_PARALLELISM)
            val perSeed = coroutineScope {
                seeds.map { seed ->
                    async {
                        runCatching {
                            val sample = gate.withPermit { nativeChannelVideos(seed) }.take(DISCOVER_VIDEOS_PER_SEED)
                            coroutineScope {
                                sample.map { v ->
                                    async {
                                        runCatching {
                                            gate.withPermit {
                                                InnertubeParser.parseRelated(innertube.next(v.id), v.id).videos
                                            }
                                        }.getOrDefault(emptyList())
                                    }
                                }.awaitAll().flatten()
                            }
                        }.getOrDefault(emptyList())
                    }
                }.awaitAll()
            }
            // Count each channel ONCE per seed: recurring twice under one seed is a weaker signal
            // than recurring once under two different seeds ("related to multiple things you
            // watch", not "related to one thing, twice") — same rule as the JS pass.
            val counts = HashMap<String, Pair<String, Int>>()
            for (hits in perSeed) {
                val seenThisSeed = HashSet<String>()
                for (v in hits) {
                    val cid = v.channelId ?: continue
                    if (cid in exclude || !seenThisSeed.add(cid)) continue
                    val cur = counts[cid]
                    counts[cid] = (cur?.first?.takeIf { it.isNotBlank() } ?: v.author) to ((cur?.second ?: 0) + 1)
                }
            }
            val channels = counts.entries
                .filter { it.value.second >= MIN_DISCOVER_RECURRENCE }
                .sortedByDescending { it.value.second }
                .take(MAX_DISCOVER_RESULTS)
                .map { DiscoveredChannel(id = it.key, name = it.value.first, score = it.value.second) }
            Log.i(TAG, "native discoverSimilarChannels(${seeds.size} seeds) → ${channels.size}")
            DiscoverChannelsResult(channels)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native discoverSimilarChannels failed, falling back to JS: ${t.message}")
            val raw = browse().callAsync("discoverSimilarChannels", org.json.JSONArray(subscribedChannelIds).toString())
            json.decodeFromString(DiscoverChannelsResult.serializer(), raw)
        }
    }

    override suspend fun getChannelInfo(channelId: String): ChannelInfo {
        return try {
            val info = InnertubeParser.channelInfo(innertube.browse(channelId), channelId)
            if (info.name.isBlank()) throw IllegalStateException("no channel name parsed")
            Log.i(TAG, "native getChannelInfo($channelId) → ${info.name}")
            info
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native getChannelInfo failed, falling back to JS: ${t.message}")
            val raw = browse().callAsync("getChannelInfo", channelId)
            json.decodeFromString(ChannelInfo.serializer(), raw)
        }
    }

    override suspend fun getChannelPlaylists(channelId: String): ChannelPlaylistResult {
        return try {
            val playlists = InnertubeParser.collectPlaylists(
                innertube.browse(channelId, SearchParams.CHANNEL_PLAYLISTS_TAB),
            )
            if (playlists.isEmpty()) throw IllegalStateException("no playlists parsed")
            Log.i(TAG, "native getChannelPlaylists($channelId) → ${playlists.size}")
            ChannelPlaylistResult(playlists)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native getChannelPlaylists failed, falling back to JS: ${t.message}")
            val raw = browse().callAsync("getChannelPlaylists", channelId)
            json.decodeFromString(ChannelPlaylistResult.serializer(), raw)
        }
    }

    override suspend fun getPlaylistVideos(playlistId: String): SearchResult {
        return try {
            val videos = InnertubeParser.collect(innertube.playlist(playlistId)).videos
            if (videos.isEmpty()) throw IllegalStateException("no playlist videos parsed")
            Log.i(TAG, "native getPlaylistVideos($playlistId) → ${videos.size}")
            SearchResult(videos = videos)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native getPlaylistVideos failed, falling back to JS: ${t.message}")
            val raw = browse().callAsync("getPlaylistVideos", playlistId)
            json.decodeFromString(SearchResult.serializer(), raw)
        }
    }

    override suspend fun getVideoInfo(videoId: String, forceFresh: Boolean): VideoInfo {
        val raw = playback().callAsync("getVideoInfo", videoId, forceFresh)
        return json.decodeFromString(VideoInfo.serializer(), raw)
    }

    override suspend fun getLegacyPlayback(videoId: String, forceFresh: Boolean): LegacyPlaybackInfo {
        val raw = playback().callAsync("getLegacyPlayback", videoId, forceFresh)
        return json.decodeFromString(LegacyPlaybackInfo.serializer(), raw)
    }

    override suspend fun getSabrInfo(videoId: String, forceFresh: Boolean): SabrInfoDto {
        val raw = playback().callAsync("getSabrInfo", videoId, forceFresh)
        return json.decodeFromString(SabrInfoDto.serializer(), raw)
    }

    override suspend fun getCaptionVtt(videoId: String, languageName: String): String {
        // callAsync returns JSON.stringify(result); for a JS string that's a quoted JSON literal.
        val raw = playback().callAsync("getCaptionVtt", videoId, languageName)
        return runCatching { json.decodeFromString(String.serializer(), raw) }.getOrDefault("")
    }

    override suspend fun getComments(videoId: String, sortBy: String): CommentPage {
        try {
            // A watch-next response only ships a PLACEHOLDER comment section, so reading comments
            // is inherently two round trips: next(videoId) to get the section's token, then the
            // continuation that actually returns comments.
            val watchNext = innertube.next(videoId)
            val sectionToken = CommentsParser.findCommentSectionToken(watchNext)
                ?: throw IllegalStateException("no comment section token")
            var parsed = CommentsParser.parse(innertube.nextContinuation(sectionToken))
            // "Newest first" needs a third call: its token only exists in the sort menu that comes
            // back with the (default, top-comments) first page. TOP is the default, so it's free.
            if (sortBy.equals("NEWEST", ignoreCase = true)) {
                // LOCAL FIX (static review, 2026-08-18): the sort menu's token order is stable and
                // locale-independent -- YouTube always returns [Top comments, Newest first] in that
                // order (CommentsParser populates sortTokens as a LinkedHashMap in menu order) -- unlike
                // matching the label TEXT, which only worked for English ("new"); e.g. Dutch's label is
                // "Nieuwste eerst" and silently failed to match, quietly serving TOP order instead.
                val entries = parsed.sortTokens.entries.toList()
                val newestToken = entries.drop(1).firstOrNull()?.value
                    ?: entries.firstOrNull { it.key.contains("new", ignoreCase = true) }?.value
                if (newestToken != null) parsed = CommentsParser.parse(innertube.nextContinuation(newestToken))
            }
            // An empty page that ISN'T "comments are off" means the parse missed the shape (or
            // YouTube changed it) — fall back rather than show a video as having no comments.
            if (parsed.page.comments.isEmpty() && !parsed.page.disabled) {
                throw IllegalStateException("no comments parsed")
            }
            nativeComments = NativeCommentsSession(
                parsed.continuation,
                parsed.replyTokens.toMutableMap(),
                parsed.sortTokens.toMutableMap(),
            )
            Log.i(
                TAG,
                "native getComments → ${parsed.page.comments.size} " +
                    "(more=${parsed.continuation != null}, disabled=${parsed.page.disabled})",
            )
            return parsed.page
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(TAG, "native getComments failed, falling back to JS: ${t.message}")
            nativeComments = null
        }
        val raw = browse().callAsync("getComments", videoId, sortBy)
        return json.decodeFromString(CommentPage.serializer(), raw)
    }

    override suspend fun getMoreComments(): CommentPage {
        val s = nativeComments
        if (s != null) {
            val token = s.continuation ?: return CommentPage()
            return try {
                val parsed = CommentsParser.parse(innertube.nextContinuation(token))
                s.continuation = parsed.continuation
                s.replyTokens.putAll(parsed.replyTokens)
                Log.i(TAG, "native getMoreComments → +${parsed.page.comments.size}")
                parsed.page
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "native getMoreComments failed: ${t.message}")
                CommentPage()
            }
        }
        val raw = browse().callAsync("getMoreComments")
        return json.decodeFromString(CommentPage.serializer(), raw)
    }

    override suspend fun getCommentReplies(commentId: String): CommentReplies {
        val s = nativeComments
        if (s != null) {
            val token = s.replyTokens[commentId] ?: return CommentReplies()
            return try {
                val parsed = CommentsParser.parse(innertube.nextContinuation(token))
                CommentReplies(replies = parsed.page.comments)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w(TAG, "native getCommentReplies failed: ${t.message}")
                CommentReplies()
            }
        }
        val raw = browse().callAsync("getCommentReplies", commentId)
        return json.decodeFromString(CommentReplies.serializer(), raw)
    }

    override suspend fun poTokenInfo(): PoTokenInfo {
        val raw = playback().callAsync("poTokenInfo")
        return json.decodeFromString(PoTokenInfo.serializer(), raw)
    }

    override suspend fun refreshPoToken(): PoTokenInfo {
        val raw = playback().callAsync("refreshPoToken")
        return json.decodeFromString(PoTokenInfo.serializer(), raw)
    }

    override fun close() {
        playbackRuntime.close()
        browseRuntime.close()
    }

    private companion object {
        const val TAG = "YoutubeBridge"

        /**
         * Subscriptions-tab fan-out ([getChannelVideosBatch]) — one browse + parse per channel.
         *
         * A NEW limit, not a restored one: this fan-out had no bound at all, ever (see the
         * comment on [innertube]'s OkHttpClient for why the HTTP client never supplied one). So
         * every subscription was one concurrent browse AND one concurrent parse of a large
         * channel-tab document, all at once on Dispatchers.IO — a cost that grows with the
         * subscription list, which is the one input here that only ever gets bigger with use.
         *
         * That makes it a trade, not a fix: with a long subscription list this tab now loads in
         * waves (wall-clock somewhat longer), in exchange for not flooding the shared client and
         * the IO pool while the user is on another screen. 8 is sized for that trade on a phone;
         * raise it if the subscriptions tab itself is the thing that feels slow.
         *
         * **Any new fan-out on [innertube] needs its own Semaphore** — nothing else bounds it.
         */
        const val SUBS_FANOUT_PARALLELISM = 8

        /**
         * Background similar-channel discovery ([discoverSimilarChannels]) — the smallest share,
         * because the pass's entire job is to be invisible. It runs at most daily and nothing waits
         * on it, so two in flight costs it some wall-clock time and costs the user nothing.
         */
        const val DISCOVER_PARALLELISM = 2

        // Discovery pass bounds — mirrors the JS constants (index.js) this replaced. Each seed
        // costs 1 channel browse + DISCOVER_VIDEOS_PER_SEED watch-next calls, so the seed count is
        // what bounds the whole pass.
        const val DISCOVER_VIDEOS_PER_SEED = 2
        const val MAX_DISCOVER_SEEDS = 6
        const val MIN_DISCOVER_RECURRENCE = 2
        const val MAX_DISCOVER_RESULTS = 15

        // `WEB: { NAME: "WEB", VERSION: "2.20260206.01.00", … }` in the bundled youtubei.js
        // CLIENTS table — the NAME anchor keeps MWEB/WEB_KIDS/... from matching.
        val WEB_CLIENT_VERSION_RE =
            Regex("NAME:\\s*\"WEB\"\\s*,\\s*VERSION:\\s*\"(2\\.\\d{8}\\.\\d{2}\\.\\d{2})\"")
    }
}
