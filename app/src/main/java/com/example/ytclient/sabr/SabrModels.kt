package com.example.ytclient.sabr

import kotlinx.serialization.Serializable

/**
 * Everything the native SABR client needs to open a streaming session for a video, surfaced by the
 * JS runtime's `__yt.getSabrInfo(videoId)` and decoded here.
 *
 * Field notes:
 *  - [profile] is a [org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile]
 *    enum constant name (e.g. "WEB", "IOS", "ANDROID", "TVHTML5", "WEB_EMBEDDED").
 *  - [serverAbrStreamingUrl] already has its `n` parameter deciphered on the JS side.
 *  - [ustreamerConfig] is the base64 `videoPlaybackUstreamerConfig`.
 *  - [adaptiveFormatsJson] is the raw JSON array shaped for
 *    `YoutubeSabrFormat.fromAdaptiveFormats` (camelCase keys, exact format-id fields).
 *  - [clientVersion] may be null for non-WEB profiles; the factory falls back to the profile default.
 */
@Serializable
data class SabrInfoDto(
    val profile: String = "WEB",
    val videoId: String,
    val visitorData: String? = null,
    val clientVersion: String? = null,
    val serverAbrStreamingUrl: String? = null,
    val ustreamerConfig: String? = null,
    val adaptiveFormatsJson: String = "[]",
    val audioTracks: List<AudioTrackInfo> = emptyList(),
    val captionTracks: List<CaptionTrackInfo> = emptyList(),
    val durationSec: Double? = null,
    val title: String = "",
    val isLive: Boolean = false,
)

/**
 * One closed-caption track. [url] is the timedtext URL already normalised to WebVTT (`&fmt=vtt`), so
 * it can be side-loaded into ExoPlayer as a subtitle. [isAuto] marks auto-generated (ASR) captions.
 */
@Serializable
data class CaptionTrackInfo(
    val languageCode: String = "",
    val name: String = "",
    val url: String = "",
    val isAuto: Boolean = false,
)

/**
 * One selectable audio track (language). [id] matches `YoutubeSabrFormat.getAudioTrackId()` so the
 * factory can pick the matching AAC format; [isDefault] marks the video's ORIGINAL audio.
 */
@Serializable
data class AudioTrackInfo(
    val id: String = "",
    val displayName: String = "",
    val language: String = "",
    val isDefault: Boolean = false,
)
