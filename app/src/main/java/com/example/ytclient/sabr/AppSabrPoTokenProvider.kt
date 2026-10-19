package com.example.ytclient.sabr

import android.util.Base64
import android.util.Log
import com.example.ytclient.core.webpo.WebPoTokenProvider
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.schabi.newpipe.extractor.services.youtube.sabr.SabrPoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrProbe
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrStreamState

/**
 * PO-token provider for the SABR session, implementing PipePipe's [SabrPoTokenProvider] boundary.
 *
 * With the WEB profile the WEB BotGuard PO token is natively trusted by the SABR endpoint, so:
 *  - non-forced call → a locally-generated cold-start token (cheap; covers the first requests),
 *  - forced call (server returned status=3 with the current token) → mint a REAL WebView/BotGuard
 *    WEB pot bound to the videoId (the GVS/streaming *content* binding — see PR #47) and return its
 *    raw bytes.
 *
 * Called from the SABR session's request loop on a plain worker thread, so the blocking
 * [runBlocking] around the suspend WebView mint is fine here (never the UI/JS thread).
 */
class AppSabrPoTokenProvider(
    private val videoId: String,
    private val webPoProvider: WebPoTokenProvider,
    // LOCAL ADDITION. Resolves the watch page's own hint for which identity GVS wants THIS session
    // bound to — see WatchPageExperimentFlags's doc. Null when the caller doesn't wire one up
    // (keeps this class usable without it); its own resolution is null on any failure, in which case
    // PoTokenBinding.gvsIdentifier falls back to the hardcoded PoTokenBinding.SWAP exactly as before
    // this parameter existed.
    private val gvsSwapDeferred: Deferred<Boolean?>? = null,
) : SabrPoTokenProvider {

    override fun getPoToken(
        info: YoutubeSabrInfo,
        streamState: YoutubeSabrStreamState,
    ): ByteArray? = getPoToken(info, streamState, false)

    override fun getPoToken(
        info: YoutubeSabrInfo,
        streamState: YoutubeSabrStreamState,
        forceRefresh: Boolean,
    ): ByteArray? {
        // Which identity a GVS pot binds to is decided by PoTokenBinding — see it for the full
        // BgUtils-vs-yt-dlp disagreement and the probe history. Not decided here, and deliberately
        // not decided twice.
        if (!forceRefresh) {
            return runCatching { YoutubeSabrProbe.coldStartPoToken(videoId, 0) }
                .onFailure { Log.w(TAG, "cold-start token failed: ${it.message}") }
                .getOrNull()
        }
        // A forced mint means the server REJECTED the token we're currently sending. Re-minting the
        // SAME binding cannot help — a fresh token with the same (wrong) binding is just as invalid,
        // which is exactly the loop observed in the field. What we vary instead is the attestation
        // (below); the binding itself is a build-time decision in PoTokenBinding.
        //
        // Worth ruling out before blaming the token at all: the "no-media round" log emitted by
        // YoutubeSabrSession. If it shows prot=1 with the buffered edge sitting ~60s ahead of
        // playerTimeMs, this was never a token problem — it's the server's buffer-ahead window
        // waiting on client progress (see YoutubeSabrSession.setPlayHeadMs's doc, which documents
        // that exact ~1-minute death). Only a persistent prot=2/3 means the token is being refused.
        val visitorData = info.visitorData?.takeIf { it.isNotBlank() }
        // First forced mint of this provider is the SESSION SEED (SabrSessionFactory building a new
        // holder) — reusing the page's existing BotGuard attestation is correct and is the whole
        // point of the attest-once/mint-many change. Every LATER forced mint got here because the
        // streaming server refused the token this attestation produced, and re-minting from the same
        // VM would only produce another token from the same rejected attestation — so those force a
        // fresh attestation.
        val serverRefusedPreviousToken = mintedOnce
        mintedOnce = true
        // identifier/bindingName are resolved INSIDE the runBlocking below (awaiting gvsSwapDeferred
        // needs a suspend context) but referenced in the log line after it, hence the lateinit.
        lateinit var identifier: String
        lateinit var bindingName: String
        return try {
            // Mint the GVS/SABR-streaming pot via the IN-PAGE att/get flow, NOT
            // the out-of-page /Create flow (generatePoToken). The GVS server only accepts a token
            // whose BotGuard attestation was obtained same-origin from the youtube.com page; a
            // /Create token is fine for Innertube /player but keeps SABR at protection status=2 and
            // cuts media at ~60s.
            val b64 = runBlocking {
                // The GVS half of the binding assignment. PoTokenBinding.SWAP is the hardcoded
                // default (both halves at once — the /player half lives in js-runtime and is
                // configured from the same constant, see PoTokenBinding for why half a swap is
                // meaningless); gvsSwapDeferred is a LOCAL, GVS-only, per-video override on top of
                // it — see WatchPageExperimentFlags. Bounded wait: that deferred already carries its
                // own ~2.5s internal timeout, this is just a safety belt against it hanging for an
                // unrelated reason.
                // runCatching because release() cancels this Deferred (see
                // SabrSessionHolder.trackAuxJob): awaiting a cancelled one throws, and without this
                // the outer catch would turn "the holder went away" into "the mint failed" rather
                // than the intended "no override, use PoTokenBinding.SWAP".
                val swapOverride = gvsSwapDeferred?.let { d ->
                    runCatching { withTimeoutOrNull(GVS_SWAP_AWAIT_TIMEOUT_MS) { d.await() } }
                        .getOrNull()
                }
                identifier = if (visitorData != null) {
                    PoTokenBinding.gvsIdentifier(videoId, visitorData, swapOverride)
                } else {
                    // Nothing to session-bind to; content binding is the only option available.
                    videoId
                }
                bindingName = if (identifier == videoId) "videoId/content" else "visitorData/session"
                webPoProvider.generateAttestedPoToken(
                    identifier = identifier,
                    purpose = WebPoTokenProvider.PURPOSE_GVS,
                    visitorData = info.visitorData,
                    clientVersion = info.clientVersion,
                    forceReattest = serverRefusedPreviousToken,
                )
            }
            // WebPoTokenProvider returns URL-safe, unpadded base64; SABR wants the raw bytes.
            Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                .also {
                    Log.i(
                        TAG,
                        "minted real WEB PO token bound to $bindingName (${it.size} bytes) — " +
                            "if media resumes after THIS mint, that's the binding GVS wants",
                    )
                }
        } catch (t: Throwable) {
            Log.w(TAG, "real PO token mint failed: ${t.message}")
            null
        }
    }

    /** Has this provider already minted once? See the seed-vs-refusal distinction in getPoToken. */
    private var mintedOnce = false

    private companion object {
        const val TAG = "AppSabrPoToken"

        // See gvsSwapDeferred's field doc — this is a safety belt on top of
        // WatchPageExperimentFlags's own ~2.5s internal timeout, not the primary bound.
        const val GVS_SWAP_AWAIT_TIMEOUT_MS = 3_000L
    }
}
