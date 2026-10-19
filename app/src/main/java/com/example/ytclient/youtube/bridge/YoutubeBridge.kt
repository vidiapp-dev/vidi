package com.example.ytclient.youtube.bridge

import com.example.ytclient.sabr.SabrInfoDto
import com.example.ytclient.youtube.models.ChannelInfo
import com.example.ytclient.youtube.models.ChannelPlaylistResult
import com.example.ytclient.youtube.models.CommentPage
import com.example.ytclient.youtube.models.CommentReplies
import com.example.ytclient.youtube.models.DiscoverChannelsResult
import com.example.ytclient.youtube.models.HomeTopics
import com.example.ytclient.youtube.models.LegacyPlaybackInfo
import com.example.ytclient.youtube.models.PoTokenInfo
import com.example.ytclient.youtube.models.SearchResult
import com.example.ytclient.youtube.models.SearchSuggestions
import com.example.ytclient.youtube.models.VideoInfo

/**
 * Stable façade between the Android app and the YouTube protocol runtime.
 *
 * Per the architecture plan: the Android UI must NEVER reach past this
 * interface. Innertube, PO tokens, and BotGuard all live behind this boundary
 * (inside the embedded JS runtime + the hidden PO-token WebView).
 */
interface YoutubeBridge {

    /** Lazily initialise the JS runtime + Innertube session. Idempotent. */
    suspend fun initialize()

    /** Pre-build the authed session + base.js so the first video opens fast. Best-effort. */
    suspend fun warmup()

    /** Search YouTube for [query]. Returns a normalised result list. */
    suspend fun search(query: String): SearchResult

    /**
     * Autocomplete suggestions for [query] (as the user types). Backed by YouTube's own
     * /complete/search endpoint via youtubei.js — a single lightweight HTTP call on the
     * already-warm anonymous session, no session bootstrap. Returns an empty list rather than
     * throwing on a blank/too-short query or a transient fetch failure.
     */
    suspend fun getSearchSuggestions(query: String): SearchSuggestions

    /**
     * Home feed page 1 (fast single call) + seeds a personalized round-robin for [getMoreHomeFeed].
     * [historyVideoIds] (recent watches), [subscribedChannelIds], and [recentSearchTerms] (most
     * recent first) keep later pages personalized. Only a small prefix of each list is actually
     * awaited on page 1 (the JS side caps it) — passing more terms/ids never costs extra latency,
     * it just gives the round-robin more variety for later pages.
     *
     * [discoverChannelIds] (phase 7, stretch — see [discoverSimilarChannels]) are added to the
     * round-robin ONLY, never page 1 — a speculative, low-confidence signal that should surface
     * gradually while scrolling, not compete with an explicit subscription/watch for the fast path.
     *
     * [enabledTopics] is the subset of [HomeTopics.ALL] the user hasn't switched off in the topic
     * settings sheet (see `LibraryStore.disabledTopics`) — these are the terms fetchTopicsBlend
     * actually searches. Defaults to every topic, matching the JS side's own fallback if this were
     * ever omitted.
     *
     * [topicWeights] (phase 14, HOME_FEED_PERSONALIZATION_PLAN.md) — usually
     * `HomeViewModel.topicWeights()` — biases how OFTEN each enabled topic gets a turn in the JS
     * side's topic round-robin (index.js's `pullHomeSource`'s `'group'` case), keyed by the same
     * strings as [enabledTopics]. A topic present in [enabledTopics] but missing from this map (or
     * an empty map altogether) falls back to equal weight on the JS side — this never excludes a
     * topic, only shifts emphasis among whatever's already enabled.
     */
    suspend fun getHomeFeed(
        historyVideoIds: List<String> = emptyList(),
        subscribedChannelIds: List<String> = emptyList(),
        recentSearchTerms: List<String> = emptyList(),
        discoverChannelIds: List<String> = emptyList(),
        enabledTopics: List<String> = HomeTopics.ALL,
        topicWeights: Map<String, Float> = emptyMap(),
    ): SearchResult

    /** Next chunk of the home feed (round-robin across trending + history-related + sub uploads). */
    suspend fun getMoreHomeFeed(): SearchResult

    /** Next page of the current search / category feed (continuation). */
    suspend fun getMoreSearch(): SearchResult

    /** Related / watch-next videos for [videoId] (page 1). */
    suspend fun getRelated(videoId: String): SearchResult

    /** Next page of related videos for the currently-open video (watch-next continuation). */
    suspend fun getMoreRelated(): SearchResult

    /** Latest uploads for a channel [channelId] (UCID), newest-first. */
    suspend fun getChannelVideos(channelId: String): SearchResult

    /**
     * Latest uploads for MULTIPLE channels (the Subscriptions tab), fetched CONCURRENTLY —
     * gated by the slowest single channel's round trip, not the sum of all of them. Prefer this
     * over calling [getChannelVideos] in a loop for more than one channel.
     */
    suspend fun getChannelVideosBatch(channelIds: List<String>): SearchResult

    /**
     * Phase 7 (stretch, HOME_FEED_PERSONALIZATION_PLAN.md): a poor man's collaborative-filtering
     * pass built only from calls the bridge already makes — no external data, no ML. Samples a
     * handful of [subscribedChannelIds], looks at what shows up in the RELATED videos of each
     * seed's recent uploads, and returns the channels that recur across multiple DIFFERENT seeds'
     * related lists (excluding anything already subscribed). This is materially more expensive
     * than the other bridge calls (several chained network calls per seed channel) and speculative
     * in value — callers should run it rarely and in the background (see
     * `LibraryStore.shouldRunDiscovery`), never as part of the fast home-feed page-1 path.
     */
    suspend fun discoverSimilarChannels(subscribedChannelIds: List<String>): DiscoverChannelsResult

    /** Channel header (name, avatar, banner, subscriber text). */
    suspend fun getChannelInfo(channelId: String): ChannelInfo

    /** A channel's playlists. */
    suspend fun getChannelPlaylists(channelId: String): ChannelPlaylistResult

    /** Videos of a playlist [playlistId] in order (channel playlists; for autoplay). */
    suspend fun getPlaylistVideos(playlistId: String): SearchResult

    /**
     * Fetch full video info — title, author, duration, thumbnails, and the
     * playable streaming formats (direct https URLs).
     *
     * [forceFresh] discards the cached client/session/token and re-probes —
     * used by the player's failover retry to recover from stale URLs or a
     * clamped client.
     */
    suspend fun getVideoInfo(videoId: String, forceFresh: Boolean = false): VideoInfo

    /**
     * The heavy part of legacy (non-SABR) playback: direct-URL [formats][LegacyPlaybackInfo.formats]
     * (nsig-deciphered + pot-appended for web-family clients) and a DASH manifest. [getVideoInfo]
     * deliberately no longer builds any of this eagerly — it's real CPU work (info.toDash() alone
     * serializes a 30-100KB manifest) that's wasted on the vast majority of opens where SABR
     * succeeds. Call this ONLY after [getSabrInfo] (via the player's SABR setup) has genuinely
     * failed, and merge the result into the existing [VideoInfo] for the legacy fallback UI.
     */
    suspend fun getLegacyPlayback(videoId: String, forceFresh: Boolean = false): LegacyPlaybackInfo

    /**
     * Surface everything the native SABR streaming client needs to open a session for [videoId]:
     * client profile, deciphered serverAbrStreamingUrl, base64 ustreamer config, visitorData, and
     * the adaptive formats as JSON. The transport replacement for the legacy direct-URL path.
     *
     * [forceFresh] discards the cached authed Innertube session and PO token first, so the /player
     * call is made by a new session with a freshly attested token. Use it when the streaming server
     * has told us the current session is unwelcome (StreamProtectionStatus 2 or 3) — a plain
     * re-fetch reuses the same token and lands in the same place. It does not change visitorData.
     */
    suspend fun getSabrInfo(videoId: String, forceFresh: Boolean = false): SabrInfoDto

    /**
     * Closed captions for [videoId] in the language matched by [languageName] (a display name from
     * [SabrInfoDto.captionTracks]), as WebVTT text. Fetched via Innertube's get_transcript endpoint
     * (the raw timedtext URL returns empty bodies for many requests). Returns "" when unavailable.
     */
    suspend fun getCaptionVtt(videoId: String, languageName: String): String

    /**
     * First page of comments for [videoId]. [sortBy] is "TOP" (default) or "NEWEST". Public data —
     * no login. Returns an empty page (optionally [CommentPage.disabled]) rather than throwing when
     * comments are off or the fetch fails.
     */
    suspend fun getComments(videoId: String, sortBy: String): CommentPage

    /** Next page of the comments opened by the last [getComments] call (continuation). */
    suspend fun getMoreComments(): CommentPage

    /** First batch of replies for [commentId] (a top-level comment from the current page). */
    suspend fun getCommentReplies(commentId: String): CommentReplies

    /**
     * Inspect the runtime's PO-token cache. Useful for surfacing playback
     * readiness in the UI. Does NOT trigger generation — call [refreshPoToken].
     */
    suspend fun poTokenInfo(): PoTokenInfo

    /** Force the runtime to mint a fresh PO token (discarding the cached one). */
    suspend fun refreshPoToken(): PoTokenInfo

    /** Release the JS runtime and any underlying native resources. */
    fun close()
}
