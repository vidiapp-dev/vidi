package com.example.ytclient.youtube.innertube

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The native Innertube request context — phase A1 of RUNTIME_PERF_PLAN.md ("Option A").
 *
 * Mirrors what youtubei.js sends for an anonymous WEB session so responses come back in the same
 * renderer shapes the parsers ([InnertubeParser]) expect: clientName WEB + a pinned desktop
 * clientVersion, the device's hl/gl (same values Kotlin already feeds the JS runtime via
 * `configureRegion` — see RegionDetector), and the same browser User-Agent QuickJsRuntime attaches
 * to its fetches (Google's edge cross-checks the header against context.client.userAgent).
 *
 * No sign-in, no visitorData by default: browse/search/next work anonymously without one (the JS
 * side's sessions regenerate visitorData per launch anyway, so there's no continuity to preserve).
 * [visitorData] exists as a hook for later phases if continuation behavior ever benefits from a
 * stable identity within a session.
 */
class InnertubeSession(
    /** ISO 3166-1 alpha-2 region for `gl` (e.g. "NL"), or null to let YouTube default. */
    private val location: String? = null,
    /** ISO 639-1 language for `hl` (e.g. "nl"), or null to let YouTube default. */
    private val lang: String? = null,
    /** Optional stable visitor identity — omitted from the context when null (anonymous). */
    val visitorData: String? = null,
    /** Explicit Innertube WEB client version override; when null, [clientVersionSource] is
     *  consulted lazily and [DEFAULT_CLIENT_VERSION] is the last resort. */
    explicitClientVersion: String? = null,
    /**
     * Lazily-invoked source for the CURRENT WEB client version. YouTube 400-rejects requests
     * carrying a stale/unknown clientVersion (observed live during the A2 rollout), so a pinned
     * constant alone rots. The bridge wires this to a scan of the bundled youtubei.js runtime
     * (see YoutubeBridgeImpl.bundledWebClientVersion) — that bundle is rebuilt from the youtubei.js
     * npm dependency, whose maintainers track YouTube's current version, so the native client
     * auto-follows every `npm run build` without a separate constant to remember to bump.
     * Invoked once, on first use (off the main thread — first use is inside the client's IO calls).
     */
    private val clientVersionSource: (() -> String?)? = null,
) {

    private val explicitVersion = explicitClientVersion

    /** Resolved once on first request: explicit override → [clientVersionSource] → default. */
    val clientVersion: String by lazy {
        explicitVersion
            ?: clientVersionSource?.let { src -> runCatching(src).getOrNull() }?.takeIf { !it.isNullOrBlank() }
            ?: DEFAULT_CLIENT_VERSION
    }

    val hl: String? get() = lang
    val gl: String? get() = location

    /**
     * The `context` object embedded in every Innertube POST body. Rebuilt per call (cheap) rather
     * than cached, so a future mutable field (visitor rotation, safety mode) can't go stale.
     */
    fun context(): JsonObject = buildJsonObject {
        putJsonObject("client") {
            put("clientName", CLIENT_NAME)
            put("clientVersion", clientVersion)
            put("platform", "DESKTOP")
            put("clientFormFactor", "UNKNOWN_FORM_FACTOR")
            put("userAgent", USER_AGENT)
            lang?.let { put("hl", it) }
            location?.let { put("gl", it) }
            visitorData?.let { put("visitorData", it) }
            put("utcOffsetMinutes", 0)
        }
        putJsonObject("user") { put("lockedSafetyMode", false) }
        putJsonObject("request") { put("useSsl", true) }
    }

    companion object {
        const val CLIENT_NAME = "WEB"

        /** The numeric client id matching [CLIENT_NAME], sent as X-YouTube-Client-Name. */
        const val CLIENT_NAME_ID = "1"

        /**
         * LAST-RESORT fallback only — the live version normally comes from [clientVersionSource]
         * (the bundled youtubei.js runtime, which updates with every bundle rebuild). YouTube
         * 400-rejects stale/unknown versions, so if requests start failing with the fallback in
         * play, this needs a bump to whatever the JS path currently logs in its request bodies.
         * (The playback path's own client selection — CLIENT_PREFERENCE probing in the JS runtime
         * — is entirely separate and unaffected.)
         */
        const val DEFAULT_CLIENT_VERSION = "2.20260206.01.00"

        /**
         * Keep in sync with QuickJsRuntime.DEFAULT_BROWSER_UA / WebPoTokenProvider.USER_AGENT —
         * Google's edge servers cross-check the HTTP header against context.client.userAgent.
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
    }
}
