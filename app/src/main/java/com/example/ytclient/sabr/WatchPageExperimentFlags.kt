package com.example.ytclient.sabr

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * LOCAL ADDITION (2026-08-23). Reads the SAME upfront signal GrayJay's YouTube plugin reads before
 * ever minting a GVS pot: the watch page's own `serializedExperimentFlags` (embedded in `ytcfg.set`,
 * under `WEB_PLAYER_CONTEXT_CONFIGS.WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH`), specifically the
 * `html5_generate_content_po_token` / `html5_generate_session_po_token` query-string flags packed
 * inside it. See `YTSessionClient.getClientInit` / `getContentDetails` in GrayJay's plugin
 * (plugins.grayjay.app/Youtube/YoutubeScript.js) — confirmed via live fetch 2026-08-23, per
 * CLAUDE.md's live-lookup rule.
 *
 * GrayJay does not guess the GVS pot binding and reactively fix it up the way our two-phase
 * bootstrap+upgrade does (and race against the grace burst doing it — see BACKLOG.md's "grace
 * session flips to protected" history): it reads which binding YouTube itself says the CURRENT
 * session wants, straight off the watch page, and mints exactly that once. This is the same idea
 * applied here as an OPTIONAL override on top of [PoTokenBinding]'s existing hardcoded default —
 * never a replacement for it, since that default is itself the result of two carefully-run A/B
 * experiments (see [PoTokenBinding.SWAP]'s doc) and this override must never regress behind it.
 *
 * Mechanism: a plain HTTP GET of the watch page HTML — no WebView, no JS execution needed, since the
 * field is static embedded JSON in the initial response (mirrors GrayJay's own
 * `getClientConfig(homeHtml, …)`, which likewise operates on a fetched HTML string, not a rendered
 * page). Deliberately isolated from [com.example.ytclient.core.webpo.WebPoTokenProvider]'s
 * attestation WebView: that WebView intentionally stays on the generic https://www.youtube.com/ root
 * (not a per-video watch page) to keep attestation reusable across videos and avoid re-entangling
 * with its carefully-tuned cold-start timing (see BACKLOG.md's cold-start entries) — this is a
 * separate, best-effort, per-video signal that runs concurrently with session setup, not inside it.
 *
 * NOT YET CONFIRMED against a live watch page fetch from the environment this was written in —
 * outbound fetches to youtube.com were rate-limited (HTTP 429) while investigating, both for the
 * page shape itself and for exercising this exact extraction. The extraction is written defensively
 * so a wrong assumption about the page's structure degrades to "no override" (a null return), never
 * to a worse binding than today's default. The first real on-device run is the actual verification —
 * look for this class's log line either way and treat it as the thing to check first if SABR
 * behavior doesn't change.
 */
object WatchPageExperimentFlags {

    private const val TAG = "WatchPageExpFlags"

    // Bounded: this must never meaningfully delay the background upgrade mint it feeds, let alone the
    // fast bootstrap mint (which never awaits this at all — see SabrSessionFactory.create). A watch
    // page is a few hundred KB; if it hasn't arrived by this deadline, the caller falls back to the
    // existing default exactly as if this class didn't exist.
    private const val FETCH_TIMEOUT_MS = 2_500L

    // Same static, public consent-bypass cookies WebPoTokenProvider sets on its WebView (see its
    // doc) — needed here too, or an EU-region fetch bounces to a consent interstitial instead of the
    // watch page and the field is simply absent.
    private const val CONSENT_COOKIES =
        "SOCS=CAESHAgBEhJnd3NfMjAyMzA0MTMtMF9SQzEaAmVuIAEaBgiAo_CmBg; CONSENT=YES+42"

    // A regular signed-out desktop Chrome UA. This is a plain content fetch (not a stream request),
    // so it does not need to match any entry in js-runtime's STREAM_UA map.
    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"

    private val FLAG_REGEX = Regex(""""serializedExperimentFlags"\s*:\s*"((?:[^"\\]|\\.)*)"""")

    /**
     * Best-effort. `true`/`false` is the resolved [PoTokenBinding]-style swap (GVS pot bound to
     * session/visitorData when true, to videoId/content when false — see [PoTokenBinding.SWAP]'s doc
     * for what each means and which is the current default). `null` means "could not resolve" for
     * ANY reason — network failure, timeout, field not found, both/neither flag present, YouTube
     * changed the page shape — and callers MUST treat that as "use the existing default", never as a
     * signal to change behavior.
     */
    suspend fun resolveGvsSwap(videoId: String, httpClient: OkHttpClient): Boolean? =
        withContext(Dispatchers.IO) {
            try {
                val client = httpClient.newBuilder()
                    .callTimeout(FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .build()
                val request = Request.Builder()
                    .url("https://www.youtube.com/watch?v=$videoId")
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Cookie", CONSENT_COOKIES)
                    .build()
                val html = client.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.i(TAG, "watch page fetch for $videoId: http ${resp.code}")
                        return@use null
                    }
                    resp.body?.string()
                }
                if (html.isNullOrEmpty()) return@withContext null
                val match = FLAG_REGEX.find(html)
                if (match == null) {
                    Log.i(TAG, "watch page for $videoId: serializedExperimentFlags not found")
                    return@withContext null
                }
                // The captured text is a JSON string BODY (may contain \" \\ \/ escapes). Wrapping it
                // back into a one-element JSON array and letting org.json unescape it is simpler and
                // more correct than hand-rolling JSON string-escape handling ourselves.
                val raw = JSONArray("[\"${match.groupValues[1]}\"]").getString(0)
                // Split FIRST, then decode each half. The blob is a raw `k=v&k=v` string, not a
                // percent-encoded query: decoding it whole would turn any %26/%3D inside a value
                // into real separators, rewrite '+' as a space, and — for a single stray '%'
                // anywhere in ~10KB of flags — throw IllegalArgumentException. That last one is
                // caught below and degrades to "no override", which is safe but silently inert
                // for every video, i.e. exactly the kind of failure nobody notices.
                val params = raw.split("&").mapNotNull { pair ->
                    val i = pair.indexOf('=')
                    if (i < 0) return@mapNotNull null
                    val k = runCatching { URLDecoder.decode(pair.substring(0, i), "UTF-8") }
                        .getOrElse { pair.substring(0, i) }
                    val v = runCatching { URLDecoder.decode(pair.substring(i + 1), "UTF-8") }
                        .getOrElse { pair.substring(i + 1) }
                    k to v
                }.toMap()
                val useVideoIdPot = params["html5_generate_content_po_token"] == "true"
                val useSessionPot = params["html5_generate_session_po_token"] == "true"
                val swap = when {
                    // Exactly one flag true: unambiguous, mirrors GrayJay's own reading of these two
                    // flags (see this object's doc). swap=true means GVS <- session/visitorData.
                    useSessionPot && !useVideoIdPot -> true
                    useVideoIdPot && !useSessionPot -> false
                    // Both or neither true: ambiguous, or YouTube changed the shape -- no override.
                    else -> null
                }
                Log.i(
                    TAG,
                    "watch page for $videoId: html5_generate_content_po_token=$useVideoIdPot " +
                        "html5_generate_session_po_token=$useSessionPot -> swap=$swap",
                )
                swap
            } catch (t: Throwable) {
                Log.w(TAG, "watch page fetch/parse failed for $videoId: ${t.message}")
                null
            }
        }
}
