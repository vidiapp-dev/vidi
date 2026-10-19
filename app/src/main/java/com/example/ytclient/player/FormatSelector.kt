package com.example.ytclient.player

import com.example.ytclient.youtube.models.StreamFormat
import com.example.ytclient.youtube.models.VideoInfo

/**
 * A concrete playback choice derived from a [VideoInfo]'s format list.
 *
 * - [videoUrl] always set.
 * - [audioUrl] set when we picked separate adaptive video-only + audio-only
 *   tracks (the common case) → caller merges them. Null when [videoUrl] is a
 *   muxed (video+audio) progressive format that plays standalone.
 */
data class PlaybackChoice(
    val videoUrl: String,
    val audioUrl: String?,
    val videoLabel: String,
    val audioLabel: String,
    /** Video height of the chosen track (0 if unknown / muxed). */
    val height: Int = 0,
)

/** A selectable quality level for the player's quality menu. */
data class QualityOption(val height: Int, val label: String)

/**
 * Picks a sensible default video+audio pair for playback.
 *
 * Strategy:
 *  1. Prefer adaptive: best H.264/MP4 video-only (≤ [maxHeight]) + best
 *     AAC/MP4 audio-only. MP4/H.264/AAC is the most universally decodable
 *     combo on Android (hardware-accelerated everywhere). We fall back to
 *     any codec if MP4 isn't present.
 *  2. If no adaptive pair is available, use the best muxed progressive format.
 */
object FormatSelector {

    fun select(info: VideoInfo, maxHeight: Int = 1080): PlaybackChoice? {
        val withUrl = info.formats.filter { !it.url.isNullOrBlank() }
        if (withUrl.isEmpty()) return null

        val videoOnly = withUrl.filter { it.hasVideo && !it.hasAudio }
        val audioOnly = withUrl.filter { it.hasAudio && !it.hasVideo }

        val bestVideo = pickVideo(videoOnly, maxHeight)
        val bestAudio = pickAudio(audioOnly)

        if (bestVideo != null && bestAudio != null) {
            return adaptiveChoice(bestVideo, bestAudio)
        }

        // Fallback: best muxed progressive (single URL, video+audio together).
        val muxed = withUrl.filter { it.hasVideo && it.hasAudio }
            .filter { it.height in 1..maxHeight || it.height == 0 }
            .maxByOrNull { it.height * 100_000 + it.bitrate }
        if (muxed != null) {
            return PlaybackChoice(
                videoUrl = muxed.url!!,
                audioUrl = null,
                videoLabel = "${muxed.qualityOrSize()} ${muxed.codecShort()} (muxed)",
                audioLabel = "included",
                height = muxed.height,
            )
        }
        return null
    }

    /**
     * Distinct selectable qualities (desc). Restricted to MP4/H.264 video-only
     * formats with a usable URL and a sane height — the same family
     * [choiceForHeight] will actually pick, so every offered option is
     * decodable. (Other codecs/odd heights like "20206p" are filtered out.)
     */
    fun qualityOptions(info: VideoInfo): List<QualityOption> =
        info.formats
            .filter {
                !it.url.isNullOrBlank() && it.hasVideo && !it.hasAudio &&
                    it.mimeType.contains("mp4") && it.height in 1..4320
            }
            .distinctBy { it.height }
            .sortedByDescending { it.height }
            .map { QualityOption(it.height, it.quality.ifBlank { "${it.height}p" }) }

    /** Build a choice locked to a specific video [height] (+ best audio). */
    fun choiceForHeight(info: VideoInfo, height: Int): PlaybackChoice? {
        val withUrl = info.formats.filter { !it.url.isNullOrBlank() }
        val video = withUrl
            .filter { it.hasVideo && !it.hasAudio && it.height == height }
            .sortedWith(
                compareByDescending<StreamFormat> { if (it.mimeType.contains("mp4")) 1 else 0 }
                    .thenByDescending { it.bitrate },
            )
            .firstOrNull() ?: return select(info)
        val audio = pickAudio(withUrl.filter { it.hasAudio && !it.hasVideo }) ?: return select(info)
        return adaptiveChoice(video, audio)
    }

    private fun adaptiveChoice(video: StreamFormat, audio: StreamFormat) = PlaybackChoice(
        videoUrl = video.url!!,
        audioUrl = audio.url!!,
        videoLabel = "${video.qualityOrSize()} ${video.codecShort()}",
        audioLabel = "${audio.bitrate / 1000}kbps ${audio.codecShort()}",
        height = video.height,
    )

    private fun pickVideo(videoOnly: List<StreamFormat>, maxHeight: Int): StreamFormat? {
        val capped = videoOnly.filter { it.height in 1..maxHeight }
            .ifEmpty { videoOnly }            // if everything is above cap, take what we have
        // Prefer MP4/H.264 for decode reliability, then highest resolution, then bitrate.
        return capped
            .sortedWith(
                compareByDescending<StreamFormat> { if (it.mimeType.contains("mp4")) 1 else 0 }
                    .thenByDescending { it.height }
                    .thenByDescending { it.bitrate },
            )
            .firstOrNull()
    }

    private fun pickAudio(audioOnly: List<StreamFormat>): StreamFormat? {
        return audioOnly
            .filter { !it.isDrc }                 // avoid DRC (loudness-normalised) tracks
            .ifEmpty { audioOnly }
            .sortedWith(
                // Original/default track first, never an auto-dub (e.g. French),
                // then MP4/AAC for decode reliability, then highest bitrate.
                compareByDescending<StreamFormat> { if (it.audioIsDefault) 1 else 0 }
                    .thenBy { if (it.isDubbed) 1 else 0 }
                    .thenByDescending { if (it.mimeType.contains("mp4")) 1 else 0 }
                    .thenByDescending { it.bitrate },
            )
            .firstOrNull()
    }

    private fun StreamFormat.qualityOrSize(): String =
        when {
            quality.isNotBlank() -> quality
            height > 0 -> "${height}p"
            else -> "?"
        }

    private fun StreamFormat.codecShort(): String {
        val sub = mimeType.substringAfter('/').substringBefore(';')
        return sub
    }
}
