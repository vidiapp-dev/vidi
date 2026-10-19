package com.example.ytclient.youtube.innertube

// java.util (API 26+, matching minSdk) rather than android.util.Base64 — identical output, and it
// keeps this class usable from plain JVM unit tests (android.util is stubbed there).
import java.util.Base64

/**
 * Innertube `search.params` filter values — phase A3 of RUNTIME_PERF_PLAN.md.
 *
 * These are a small protobuf message, base64'd: the outer field 2 (LEN) wraps a SearchFilter whose
 * field 2 is the result type and field 3 the duration bucket. Rather than hardcode opaque base64
 * blobs copied from elsewhere, the bytes are built here so each value is self-documenting and a new
 * combination is trivial to add. Sanity check: [videosOnly] encodes to "EgIQAQ==", the
 * well-known value youtubei.js sends for `{type: 'video'}` (verified against a live request).
 */
object SearchParams {

    private const val TYPE_VIDEO = 1

    /** YouTube's duration buckets (SearchFilter field 3). */
    enum class Duration(val value: Int) {
        /** "Under 4 minutes" — teasers, bloopers, shorts-ish uploads. */
        SHORT(1),

        /** "Over 20 minutes". */
        LONG(2),

        /** "4–20 minutes" — featurettes, interviews, most mid-length uploads. */
        MEDIUM(3),
    }

    /** `type: video`, no other filter. */
    val videosOnly: String by lazy { encode(null) }

    /** `type: video` + a duration bucket. */
    fun videosOfDuration(duration: Duration): String = encode(duration)

    private fun encode(duration: Duration?): String {
        // SearchFilter submessage: field2 (type) [+ field3 (duration)], varint-encoded.
        val filter = if (duration == null) {
            byteArrayOf(0x10, TYPE_VIDEO.toByte())
        } else {
            byteArrayOf(0x10, TYPE_VIDEO.toByte(), 0x18, duration.value.toByte())
        }
        // Outer message: field 2, wire type 2 (length-delimited) = 0x12, then length, then payload.
        val message = byteArrayOf(0x12, filter.size.toByte()) + filter
        return Base64.getEncoder().encodeToString(message)
    }

    /**
     * `browse.params` selecting a channel's "Videos" tab (newest uploads) — the classic
     * tab-selector protobuf (`field2 = "videos"` plus the standard sort sub-field). Without it a
     * channel browse returns the Home/Featured tab, which is shelves, not an uploads list.
     */
    const val CHANNEL_VIDEOS_TAB = "EgZ2aWRlb3PyBgQKAjoA"

    /** `browse.params` selecting a channel's "Playlists" tab — same tab-selector shape as
     *  [CHANNEL_VIDEOS_TAB], with "playlists" as the tab name. */
    const val CHANNEL_PLAYLISTS_TAB = "EglwbGF5bGlzdHPyBgQKAkIA"
}
