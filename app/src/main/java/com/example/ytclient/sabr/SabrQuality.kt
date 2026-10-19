package com.example.ytclient.sabr

import android.content.Context
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo

/** One selectable video quality (a distinct height), resolved to the best decodable itag for it. */
data class VideoQuality(val itag: Int, val height: Int, val label: String)

/**
 * Video-quality options + decodability for SABR. A SABR session is locked to a single video format,
 * so "quality selection" = rebuild the session with a different video itag (audio stays AAC). We only
 * surface / pick formats the device can decode in HARDWARE: AVC is universal; VP9 and AV1 (which is
 * what 1440p/4K almost always are) only when a hardware decoder exists, otherwise they melt the CPU
 * or fail outright. Mirrors PipePipe's hardware-friendly pick, extended to drive a quality menu.
 */
object SabrQuality {

    private const val TAG = "SabrQuality"

    // Hardware-decoder probes are cheap but not free; resolve once per process.
    private val hwVp9 by lazy { hasHardwareDecoder("video/x-vnd.on2.vp9") }
    private val hwAv1 by lazy { hasHardwareDecoder("video/av01") }

    /**
     * Whether AVC itself decodes in hardware. Previously assumed — see [deviceMaxVideoHeight] — but
     * it is false on the Android emulator (only `c2.goldfish.h264.decoder`, a software decoder) and
     * on a handful of low-end devices.
     */
    private val hwAvc by lazy { hasHardwareDecoder("video/avc") }

    /**
     * Ceiling applied when AVC has no hardware decoder. Was 720, measured down to 480: with the cap
     * working, a 720p (itag 298) stream still hit `repeated rebuffering` and auto-dropped to 480p
     * within 23 seconds of the player being built. 480p is what actually sustains here, so start
     * there instead of paying a session rebuild to discover it every time.
     */
    private const val SOFTWARE_DECODE_MAX_HEIGHT = 480

    /**
     * Floor for automatic quality DOWNGRADES. On hardware decode, dropping below 480p is almost
     * always the wrong answer — the link is the problem and 480p already fits. On software decode it
     * is the decoder that can't keep up, and 360p/240p are legitimate landing places: the field log
     * showed `repeated rebuffering … requesting lower quality` firing three times at 480p and doing
     * nothing at all, because the old hard floor of 480 left no rung to step to.
     */
    fun minAutoDowngradeHeight(): Int = if (hwAvc) 480 else 144

    /**
     * The tallest video to offer: roughly the SHORT side of the display in pixels (the vertical
     * resolution a video gets in fullscreen landscape), but FLOORED at 1080 and CEILINGED at 1440.
     *
     *  - Floor 1080: `resources.displayMetrics` reports the APP-WINDOW size, which on real devices can
     *    under-report the physical panel (insets/cutout/rounding) — that previously hid 1080p on phones
     *    that clearly support it. 1080p is AVC and light, so it's always safe to offer.
     *  - Ceiling 1440: never offer 4K (2160p VP9/AV1), whose ~20s buffers OOM'd the player; 1440p on a
     *    QHD panel is fine with largeHeap.
     *
     * The "1080p is AVC and light, so it's always safe" premise above holds only where AVC decodes in
     * HARDWARE. It does not on the Android emulator, whose sole H.264 decoder is the software
     * `c2.goldfish.h264.decoder`, and the resulting failure is easy to misread as a network problem:
     * the decoder falls behind real time, the SABR cache keeps filling ahead of it, the heap climbs
     * into large-object territory (~73MB with ~36MB LOS observed), GC pauses reach a full second,
     * AudioTrack underruns — and the player reports "repeated rebuffering" and drops quality. In one
     * measured run only ~3s of content played in ~23s of wall clock. So when AVC is software-only,
     * floor and ceiling both collapse to [SOFTWARE_DECODE_MAX_HEIGHT].
     */
    // Log the decode verdict once, not per call: this runs on every PlayerViewModel construction and
    // the field log showed it six times in one session (each quality-menu popup re-triggered it).
    private var loggedDecodeVerdict = false

    fun deviceMaxVideoHeight(context: Context): Int {
        val cap = if (!hwAvc) {
            SOFTWARE_DECODE_MAX_HEIGHT
        } else {
            val m = context.resources.displayMetrics
            val short = minOf(m.widthPixels, m.heightPixels)
            (if (short > 0) short else 1080).coerceIn(1080, 1440)
        }
        if (!loggedDecodeVerdict) {
            loggedDecodeVerdict = true
            Log.i(
                TAG,
                "decode: hwAvc=$hwAvc hwVp9=$hwVp9 hwAv1=$hwAv1 → max ${cap}p, " +
                    "auto-downgrade floor ${minAutoDowngradeHeight()}p",
            )
        }
        return cap
    }

    /**
     * Distinct decodable heights up to [maxHeight], highest first, each as the best itag for that
     * height (AVC preferred over VP9 over AV1 at a given height for decode safety; higher bitrate
     * breaks ties). One entry per height so the menu isn't cluttered with codecs.
     */
    fun videoOptions(info: YoutubeSabrInfo, maxHeight: Int = Int.MAX_VALUE): List<VideoQuality> {
        val byHeight = LinkedHashMap<Int, YoutubeSabrFormat>()
        info.formats
            .filter { it.isVideo && it.height in 1..maxHeight && isDecodable(it) }
            .sortedWith(
                compareByDescending<YoutubeSabrFormat> { it.height }
                    .thenByDescending { codecRank(it.mimeType) }
                    .thenByDescending { it.bitrate },
            )
            .forEach { f -> byHeight.getOrPut(f.height) { f } }
        return byHeight.values.map { VideoQuality(it.itag, it.height, "${it.height}p") }
    }

    /**
     * Resolve the video format to stream. [maxHeight] is the device-display hard cap; [preferredHeight]
     * (>0) is the user's "up to this height" preference, which caps further. Picks the highest decodable
     * format at or below the effective cap (AVC-preferred at a height), matching videoOptions()'s order
     * so the streamed format equals the menu's selected entry. If everything is above the cap, the
     * smallest available; if nothing is decodable, any format (last resort so a session still builds).
     */
    fun pickVideoFormat(
        info: YoutubeSabrInfo,
        preferredHeight: Int,
        maxHeight: Int = Int.MAX_VALUE,
    ): YoutubeSabrFormat? {
        val videos = info.formats.filter { it.isVideo }
        if (videos.isEmpty()) return null
        val cap = if (preferredHeight > 0) minOf(maxHeight, preferredHeight) else maxHeight
        val ordered = videos.filter { isDecodable(it) }.ifEmpty { videos }
            .sortedWith(
                compareByDescending<YoutubeSabrFormat> { it.height }
                    .thenByDescending { codecRank(it.mimeType) }
                    .thenByDescending { it.bitrate },
            )
        return ordered.firstOrNull { it.height in 1..cap } ?: ordered.lastOrNull()
    }

    fun isDecodable(format: YoutubeSabrFormat): Boolean = when (codecFamily(format.mimeType)) {
        "avc" -> true
        "vp9" -> hwVp9
        "av1" -> hwAv1
        else -> false
    }

    /** Prefer AVC, then VP9, then AV1 at the same height (most-to-least universally hw-decoded). */
    private fun codecRank(mimeType: String?): Int = when (codecFamily(mimeType)) {
        "avc" -> 3
        "vp9" -> 2
        "av1" -> 1
        else -> 0
    }

    private fun codecFamily(mimeType: String?): String {
        if (mimeType == null) return ""
        return when {
            mimeType.contains("avc1") || mimeType.contains("avc3") -> "avc"
            mimeType.contains("vp9") || mimeType.contains("vp09") -> "vp9"
            mimeType.contains("av01") -> "av1"
            else -> ""
        }
    }

    /**
     * True if the device exposes a non-software (hardware) decoder for [mimeType].
     *
     * Prefers [MediaCodecInfo.isHardwareAccelerated] (API 29+), which is the platform's own
     * authoritative answer. The name-prefix heuristic below is only a pre-29 fallback, and it is
     * not sufficient on its own: the Android emulator's software AVC decoder is called
     * `c2.goldfish.h264.decoder`, which matches none of the `c2.android.` / `c2.google.` /
     * `omx.google.` prefixes and was therefore being reported as hardware.
     */
    private fun hasHardwareDecoder(mimeType: String): Boolean = try {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.any { codec ->
            if (codec.isEncoder) return@any false
            if (!codec.supportedTypes.any { it.equals(mimeType, ignoreCase = true) }) {
                return@any false
            }
            val name = codec.name.lowercase()
            // The Android emulator's `c2.goldfish.*` codecs report isHardwareAccelerated == TRUE
            // (confirmed in the field: "hardware AVC present — max quality 1344p (hwVp9=true …)"),
            // because they are vendor codecs rather than `c2.android.*` ones. They are not hardware
            // in any useful sense — they are host-side software, and 1080p AVC through them runs at
            // roughly an eighth of real time. Exclude them before trusting the platform's answer,
            // otherwise the software-decode cap below never engages where it is needed most.
            if (name.startsWith("c2.goldfish.")) return@any false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                codec.isHardwareAccelerated
            } else {
                !name.startsWith("c2.android.") && !name.startsWith("c2.google.")
                    && !name.startsWith("omx.google.") && !name.contains(".sw.")
            }
        }
    } catch (_: Throwable) {
        false // probing failed -> be conservative (treat as no HW decoder)
    }
}
