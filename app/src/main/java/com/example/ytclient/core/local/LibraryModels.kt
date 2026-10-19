package com.example.ytclient.core.local

import com.example.ytclient.youtube.models.SearchVideo
import kotlinx.serialization.Serializable

/** A channel the user subscribed to locally (no YouTube account involved). */
@Serializable
data class Subscription(
    val channelId: String,
    val name: String,
    val avatar: String? = null,
    val subscribedAtMs: Long = 0L,
)

/** A channel the user asked not to be recommended ("Don't recommend this channel"). */
@Serializable
data class HiddenChannel(
    val channelId: String,
    val hiddenAtMs: Long = 0L,
)

/**
 * A channel cached from phase 7's (HOME_FEED_PERSONALIZATION_PLAN.md, stretch) two-hop discovery
 * pass — see `YoutubeBridge.discoverSimilarChannels` and `LibraryStore.shouldRunDiscovery`. The
 * whole list is overwritten on each (roughly daily) run rather than accumulated, so a channel that
 * stops recurring in later runs naturally drops out instead of lingering forever.
 */
@Serializable
data class DiscoveredChannel(
    val channelId: String,
    val name: String = "",
    val score: Int = 0,
    val discoveredAtMs: Long = 0L,
)

/**
 * One watched (or partially-watched) video. [positionMs]/[durationSec] drive the
 * "continue watching" progress bar; [lastPlayedAtMs] orders the History list.
 *
 * [tags] are the uploader-supplied video tags/keywords (`VideoInfo.tags`, sourced from
 * youtubei.js's `basic_info.tags`/`keywords` in index.js's getVideoInfo) — recorded purely as a
 * low-cost interest signal for [com.example.ytclient.ui.home.HomeRanker]'s tag-affinity scoring,
 * never shown in the UI. Empty when the source video had none, or for the provisional history
 * entry PlayerViewModel seeds before getVideoInfo resolves.
 */
@Serializable
data class HistoryEntry(
    val videoId: String,
    val title: String,
    val author: String = "",
    val channelId: String? = null,
    val thumbnail: String? = null,
    val duration: String = "",
    val durationSec: Int = 0,
    val views: String = "",
    val published: String = "",
    val positionMs: Long = 0L,
    val lastPlayedAtMs: Long = 0L,
    val tags: List<String> = emptyList(),
    // Phase 14 (HOME_FEED_PERSONALIZATION_PLAN.md): which home-feed TOPIC (e.g. "Gaming"), if any,
    // this video was originally surfaced from — derived from SearchVideo.sourceTag's "topic:" prefix
    // at record time (see PlayerViewModel.loadInternal). Null for anything not sourced from the
    // topics bucket (subscriptions, history-related, search, discover, or a plain search-tab video).
    // Feeds HomeRanker.topicAffinity(), which HomeViewModel uses to weight the JS-side topic
    // round-robin toward topics you actually watch — entirely separate from HistoryEntry.tags
    // (uploader-supplied keywords, used for HomeRanker's tag-affinity scoring instead).
    val topic: String? = null,
) {
    /** 0f..1f watched fraction, or 0 when the duration is unknown. */
    val watchedFraction: Float
        get() = if (durationSec > 0) (positionMs.toFloat() / (durationSec * 1000f)).coerceIn(0f, 1f) else 0f
}

/**
 * One committed search query inside [LibraryStore.searchHistory] — most-recent-first.
 * [searchedAtMs] is REFRESHED (not summed) on a repeat search of the same query, so a term
 * searched once last month and again just now correctly reads as "recent" for
 * [LibraryStore.recentSearchTerms]'s month-window home-feed integration.
 */
@Serializable
data class SearchHistoryEntry(
    val query: String,
    val searchedAtMs: Long = 0L,
)

/** A locally-saved video reference inside a playlist. */
@Serializable
data class PlaylistVideo(
    val id: String,
    val title: String,
    val author: String = "",
    val thumbnail: String? = null,
    val channelId: String? = null,
    val duration: String = "",
    val views: String = "",
    val published: String = "",
    val addedAtMs: Long = 0L,
)

/** A user playlist. The single [isWatchLater] one is created by default and cannot be removed. */
@Serializable
data class Playlist(
    val id: String,
    val name: String,
    val isWatchLater: Boolean = false,
    val createdAtMs: Long = 0L,
    val videos: List<PlaylistVideo> = emptyList(),
)

// ── Conversions to the shared card model ────────────────────────────────────

fun HistoryEntry.toSearchVideo(): SearchVideo = SearchVideo(
    id = videoId,
    title = title,
    thumbnail = thumbnail,
    author = author,
    duration = duration,
    views = views,
    channelId = channelId,
    published = published,
)

fun PlaylistVideo.toSearchVideo(): SearchVideo = SearchVideo(
    id = id,
    title = title,
    thumbnail = thumbnail,
    author = author,
    duration = duration,
    views = views,
    channelId = channelId,
    published = published,
)

fun SearchVideo.toPlaylistVideo(nowMs: Long): PlaylistVideo = PlaylistVideo(
    id = id,
    title = title,
    author = author,
    thumbnail = thumbnail,
    channelId = channelId,
    duration = duration,
    views = views,
    published = published,
    addedAtMs = nowMs,
)
