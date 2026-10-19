package com.example.ytclient.youtube.models

import kotlinx.serialization.Serializable

/**
 * A single search result. Intentionally a thin, UI-friendly shape — the JS runtime
 * is responsible for translating whatever YouTube.js / Innertube returns into this.
 */
@Serializable
data class SearchVideo(
    val id: String,
    val title: String,
    val thumbnail: String? = null,
    val author: String = "",
    val duration: String = "",
    val views: String = "",
    /** Channel UCID, when the source node exposed it. Drives the "subscribed" badge + subscribe. */
    val channelId: String? = null,
    /** Relative upload time text (e.g. "2 weeks ago"), when available. */
    val published: String = "",
    /**
     * Which home-feed source produced this card — `"topic:<Name>"` for a fetchTopicsBlend pick
     * (e.g. `"topic:Movies"`), `"personalized:<kind>"` for a subscription/history/search/discover
     * pick, or null outside the home feed (plain search, related, etc. never set this). Purely a
     * client-side diversification signal for [com.example.ytclient.ui.home.HomeRanker] — never
     * shown in the UI.
     */
    val sourceTag: String? = null,
)

/** A channel hit shown inline in search results (links to the channel page). */
@Serializable
data class SearchChannel(
    val id: String,
    val name: String = "",
    val avatar: String? = null,
    val subscriberText: String = "",
    /**
     * How many videos preceded this channel in YouTube's actual ranked search order — the UI
     * inserts the channel card right before videos[position] instead of always leading the list.
     */
    val position: Int = 0,
)

@Serializable
data class SearchResult(
    val videos: List<SearchVideo> = emptyList(),
    val channels: List<SearchChannel> = emptyList(),
    /** Whether a continuation page is available (drives infinite scroll). */
    val hasMore: Boolean = false,
)

/** Autocomplete suggestions for the search box, as returned by YouTube's own suggest endpoint. */
@Serializable
data class SearchSuggestions(
    val suggestions: List<String> = emptyList(),
)
