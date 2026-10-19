package com.example.ytclient.youtube.models

import kotlinx.serialization.Serializable

@Serializable
data class Thumbnail(
    val url: String,
    val width: Int = 0,
    val height: Int = 0,
)

/**
 * One playable format from YouTube's streaming data.
 *
 * `url == null` means SABR-only (you have to go through the server-side ABR
 * streaming endpoint to get bytes). `url != null` is a direct HTTPS URL that
 * Media3 can stream from.
 *
 * For adaptive playback we end up picking one video-only and one audio-only
 * format (`hasVideo && !hasAudio` + `hasAudio && !hasVideo`) and merging them
 * via Media3's MergingMediaSource. Progressive formats (`hasVideo && hasAudio`)
 * can play standalone.
 */
@Serializable
data class StreamFormat(
    val itag: Int,
    val mimeType: String,
    val quality: String,
    val bitrate: Int = 0,
    val width: Int = 0,
    val height: Int = 0,
    val fps: Int = 0,
    val contentLength: String? = null,
    val url: String? = null,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val isDrc: Boolean = false,
    val audioTrack: AudioTrack? = null,
    /** True for the video's original/default audio track. */
    val audioIsDefault: Boolean = false,
    /** True for an auto-dubbed / alternate-language audio track. */
    val isDubbed: Boolean = false,
    val language: String? = null,
)

@Serializable
data class AudioTrack(
    val id: String,
    val displayName: String,
)

/**
 * A YouTube scrub-preview sprite sheet spec (one resolution "board" out of the video's storyboard —
 * see index.js's extractStoryboard). [templateUrl] still carries a literal "$M" placeholder for
 * which sprite sheet (of [storyboardCount], each covering [columns] x [rows] tiles) to fetch — the
 * player substitutes it based on the seek position being scrubbed to.
 */
@Serializable
data class StoryboardInfo(
    val templateUrl: String,
    val thumbWidth: Int = 0,
    val thumbHeight: Int = 0,
    val columns: Int = 0,
    val rows: Int = 0,
    /** Milliseconds of video covered by each successive tile. */
    val intervalMs: Int = 0,
    val thumbCount: Int = 0,
    val storyboardCount: Int = 0,
)

@Serializable
data class StreamingMeta(
    /**
     * Absolute expiry time, Unix epoch in milliseconds. `null` if the upstream
     * didn't report one (rare). This is NOT a relative duration — youtubei.js
     * passes through Google's wall-clock expiry verbatim.
     */
    val expiresAtMs: Long? = null,
    val hlsManifestUrl: String? = null,
    val dashManifestUrl: String? = null,
    val serverAbrStreamingUrl: String? = null,
    /** Required by googlevideo's SabrStream — opaque per-video SABR params. */
    val videoPlaybackUstreamerConfig: String? = null,
)

@Serializable
data class VideoInfo(
    val id: String,
    val title: String,
    val author: String = "",
    /** Channel UCID for the uploader — used to subscribe from the player. */
    val channelId: String? = null,
    val durationSec: Int? = null,
    val viewCount: Long? = null,
    val isLive: Boolean = false,
    val thumbnails: List<Thumbnail> = emptyList(),
    val formats: List<StreamFormat> = emptyList(),
    /**
     * Which Innertube client produced this info (IOS / ANDROID / MWEB / WEB …).
     * Determines how the URLs must be fetched (e.g. the required User-Agent).
     */
    val sourceClient: String? = null,
    /**
     * User-Agent that the streaming URLs are bound to. Mobile-client URLs 403
     * unless the download uses the matching app UA, so the player must set
     * this on its HTTP data source.
     */
    val streamUserAgent: String? = null,
    /**
     * DASH MPD manifest (XML) built from the adaptive formats. Primary playback
     * path — gives native seeking + adaptive bitrate. Null for live or if
     * manifest generation failed (then the player falls back to [formats]).
     */
    val dashManifest: String? = null,
    val streaming: StreamingMeta = StreamingMeta(),
    /**
     * Why the video can't be played, when YouTube reports it unavailable (region lock, removed,
     * sign-in/age required, …). Surfaced to the user instead of a generic "no format" message.
     * Empty/null when playable.
     */
    val playabilityReason: String? = null,
    /** Scrub-preview sprite sheet spec for the seek bar, or null if this video has none. */
    val storyboard: StoryboardInfo? = null,
    /**
     * Uploader-supplied tags/keywords (youtubei.js `basic_info.tags`/`keywords`) — not shown
     * anywhere in the UI, only recorded into [com.example.ytclient.core.local.HistoryEntry.tags]
     * as a low-cost interest signal for [com.example.ytclient.ui.home.HomeRanker]'s tag-affinity
     * scoring. Empty when the video declared none.
     */
    val tags: List<String> = emptyList(),
)

/**
 * The heavy part of legacy (non-SABR) playback: direct-URL formats + a DASH manifest, built by
 * [com.example.ytclient.youtube.bridge.YoutubeBridge.getLegacyPlayback]. [VideoInfo] no longer
 * carries these eagerly — they're fetched on demand only when the native SABR path fails, since
 * building them (nsig deciphering, PO-token URL rewriting, and especially `info.toDash()`'s
 * 30-100KB manifest serialization) is real CPU work that's wasted on the ~100% of opens where
 * SABR succeeds.
 */
@Serializable
data class LegacyPlaybackInfo(
    val formats: List<StreamFormat> = emptyList(),
    val sourceClient: String? = null,
    val streamUserAgent: String? = null,
    val dashManifest: String? = null,
)
