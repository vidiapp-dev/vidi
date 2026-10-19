package com.example.ytclient.youtube.models

import kotlinx.serialization.Serializable

/** Channel header info for the channel page + storing a subscription avatar. */
@Serializable
data class ChannelInfo(
    val id: String,
    val name: String = "",
    val avatar: String? = null,
    val banner: String? = null,
    val subscriberText: String = "",
)

/** A playlist owned by / surfaced on a channel. [id] has the leading "VL" stripped. */
@Serializable
data class ChannelPlaylist(
    val id: String,
    val title: String,
    val thumbnail: String? = null,
    val videoCount: String = "",
)

@Serializable
data class ChannelPlaylistResult(
    val playlists: List<ChannelPlaylist> = emptyList(),
)

/**
 * A channel surfaced by phase 7's (HOME_FEED_PERSONALIZATION_PLAN.md, stretch) two-hop "similar
 * channels" discovery pass — see [com.example.ytclient.youtube.bridge.YoutubeBridge.discoverSimilarChannels].
 * [score] is how many distinct sampled subscribed channels it recurred as a related-video uploader
 * under (higher = stronger signal; NOT a subscriber count).
 */
@Serializable
data class DiscoveredChannel(
    val id: String,
    val name: String = "",
    val score: Int = 0,
)

@Serializable
data class DiscoverChannelsResult(
    val channels: List<DiscoveredChannel> = emptyList(),
)
