package com.example.ytclient.player.sabr

import misc.Common
import video_streaming.BufferedRangeOuterClass
import video_streaming.ClientAbrStateOuterClass
import video_streaming.FormatInitializationMetadataOuterClass
import video_streaming.MediaHeaderOuterClass
import video_streaming.NextRequestPolicyOuterClass
import video_streaming.SabrErrorOuterClass
import video_streaming.SabrRedirectOuterClass
import video_streaming.StreamProtectionStatusOuterClass
import video_streaming.StreamerContextOuterClass
import video_streaming.VideoPlaybackAbrRequestOuterClass

// ─────────────────────────────────────────────────────────────────────────────
// Aliases for the protoc-generated (Java-lite) classes. protoc appends
// "OuterClass" when a message shares its file's name, and nests enums/messages;
// these aliases give the rest of the code clean names. Generated packages are
// `video_streaming` and `misc` (see app/src/main/proto).
// ─────────────────────────────────────────────────────────────────────────────
typealias PbVideoPlaybackAbrRequest = VideoPlaybackAbrRequestOuterClass.VideoPlaybackAbrRequest
typealias PbStreamerContext = StreamerContextOuterClass.StreamerContext
typealias PbClientInfo = StreamerContextOuterClass.StreamerContext.ClientInfo
typealias PbClientAbrState = ClientAbrStateOuterClass.ClientAbrState
typealias PbFormatId = Common.FormatId
typealias PbBufferedRange = BufferedRangeOuterClass.BufferedRange
typealias PbMediaHeader = MediaHeaderOuterClass.MediaHeader
typealias PbFormatInitMetadata = FormatInitializationMetadataOuterClass.FormatInitializationMetadata
typealias PbSabrRedirect = SabrRedirectOuterClass.SabrRedirect
typealias PbSabrError = SabrErrorOuterClass.SabrError
typealias PbStreamProtectionStatus = StreamProtectionStatusOuterClass.StreamProtectionStatus
typealias PbNextRequestPolicy = NextRequestPolicyOuterClass.NextRequestPolicy

// ─────────────────────────────────────────────────────────────────────────────
// Inputs to a SABR session (Kotlin-side, populated from the player response).
// ─────────────────────────────────────────────────────────────────────────────

/** One adaptive stream format (a single video-only or audio-only track). */
data class SabrFormat(
    val itag: Int,
    val lastModified: Long, // `lmt`
    val xtags: String? = null,
    val isVideo: Boolean,
)

/**
 * Innertube client identity that produced the player response + ustreamer
 * config. The SABR server cross-checks this, so it must match the client used
 * to fetch `serverAbrStreamingUrl` / `videoPlaybackUstreamerConfig`.
 */
data class SabrClientInfo(
    val clientName: Int, // innertube client id (e.g. 3=ANDROID, 5=IOS, 1=WEB, 7=TVHTML5)
    val clientVersion: String,
    val osName: String,
    val osVersion: String,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
)

/** Everything needed to drive SABR for one video. */
data class SabrConfig(
    val serverAbrStreamingUrl: String,
    val ustreamerConfig: ByteArray, // videoPlaybackUstreamerConfig (raw bytes, base64-decoded)
    val poToken: ByteArray?,
    val clientInfo: SabrClientInfo,
)

/** What we already hold for a format, so the server knows what to send next. */
data class SabrBufferedRange(
    val format: SabrFormat,
    val startTimeMs: Long,
    val durationMs: Long,
    val startSegmentIndex: Int,
    val endSegmentIndex: Int,
)

// ─────────────────────────────────────────────────────────────────────────────
// Decoded response.
// ─────────────────────────────────────────────────────────────────────────────

/** Per-format initialization info (segment count + duration), from FORMAT_INITIALIZATION_METADATA. */
data class SabrInitMetadata(
    val itag: Int,
    val lastModified: Long,
    val mimeType: String?,
    val totalSegments: Long, // end_segment_number
    val durationMs: Long,
)

/** One fully-assembled media segment (init or media) for a format. */
data class SabrSegment(
    val itag: Int,
    val lastModified: Long,
    val isInit: Boolean,
    val sequenceNumber: Int,
    val startMs: Long,
    val durationMs: Long,
    val data: ByteArray,
)

/** Structured result of decoding one SABR/UMP response body. */
data class SabrResponse(
    val segments: List<SabrSegment>,
    val initMetadata: List<SabrInitMetadata>,
    val redirectUrl: String?,
    val playbackCookie: ByteArray?,
    val backoffTimeMs: Int,
    val protectionStatus: Int?, // StreamProtectionStatus.status (3 ⇒ attestation/pot required)
    val sabrError: String?,
)
