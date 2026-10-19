package com.example.ytclient.sabr

import android.util.Base64
import android.util.Log
import com.example.ytclient.core.webpo.WebPoTokenProvider
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrFormat
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrProbe
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrSession
import androidx.media3.common.util.UnstableApi
import java.security.SecureRandom

/**
 * Builds a [YoutubeSabrSession] from a [SabrInfoDto] (surfaced by the JS runtime).
 *
 * Responsibilities:
 *  - install the shared OkHttp stack into the probe's static I/O hook,
 *  - translate the DTO into a `YoutubeSabrInfo` (via the probe's public `buildInfo` factory),
 *  - pick the best audio + video formats,
 *  - attach the reactive (server-triggered-refresh) WebView PO-token provider, and
 *  - seed the session's initial pot and kick off the background content-binding upgrade — see the
 *    two-phase bootstrap in [create].
 *
 * The ExoPlayer bridge (next phase) consumes the returned session.
 */
object SabrSessionFactory {

    private const val TAG = "SabrSessionFactory"

    // PipePipe's content-playback-nonce alphabet; 16 chars per session.
    private const val CPN_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val RANDOM = SecureRandom()

    // See gvsSwapDeferred's usage below — a safety belt on top of WatchPageExperimentFlags's own
    // ~2.5s internal timeout, not the primary bound.
    private const val GVS_SWAP_AWAIT_TIMEOUT_MS = 3_000L

    /**
     * @throws IllegalStateException if the info lacks a usable audio/video format or streaming URL.
     */
    @UnstableApi
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun create(
        dto: SabrInfoDto,
        webPoProvider: WebPoTokenProvider,
        httpClient: OkHttpClient,
        preferredVideoHeight: Int = 0,
        maxVideoHeight: Int = Int.MAX_VALUE,
        preferredAudioTrackId: String? = null,
        seedPoToken: ByteArray? = null,
        // Whether [seedPoToken] is already the content(videoId)-bound upgrade token (i.e. the
        // holder it came from already completed the two-phase bootstrap below) — see
        // SabrSessionHolder.isPoTokenUpgraded. Only meaningful when seedPoToken != null.
        seedPoTokenUpgraded: Boolean = false,
    ): SabrSessionHolder {
        YoutubeSabrProbe.setHttpClient(httpClient)

        val profile = parseProfile(dto.profile)
        val clientVersion = dto.clientVersion?.takeIf { it.isNotEmpty() } ?: profile.clientVersion
        val cpn = generateCpn()

        val info = YoutubeSabrProbe.buildInfo(
            profile,
            dto.videoId,
            cpn,
            clientVersion,
            dto.visitorData,
            dto.serverAbrStreamingUrl,
            dto.ustreamerConfig,
            dto.adaptiveFormatsJson,
        )

        val audio = pickAudioFormat(info, preferredAudioTrackId)
            ?: throw IllegalStateException("SABR info has no audio format (videoId=${dto.videoId})")
        // Quality: maxVideoHeight is the device-display hard cap; preferredVideoHeight (0 = best) is
        // the user's pick. Only hardware-decodable formats are eligible (AVC always; VP9/AV1 — i.e.
        // 1440p/4K — only with a HW decoder).
        val video = SabrQuality.pickVideoFormat(info, preferredVideoHeight, maxVideoHeight)
            ?: throw IllegalStateException("SABR info has no video format (videoId=${dto.videoId})")

        // LOCAL ADDITION. Kicked off here, concurrently with everything below — NOT awaited by the
        // fast bootstrap mint (see the two-phase bootstrap doc further down), only by the background
        // upgrade job and by AppSabrPoTokenProvider's own reactive mint, both of which already run in
        // the background. See WatchPageExperimentFlags's doc for what this resolves and why.
        // (create() is already @OptIn(DelicateCoroutinesApi::class) — same GlobalScope rationale as
        // the existing upgrade job below: this must outlive a single request/retry attempt too.)
        val gvsSwapDeferred: Deferred<Boolean?> = GlobalScope.async(Dispatchers.IO) {
            runCatching { WatchPageExperimentFlags.resolveGvsSwap(dto.videoId, httpClient) }
                .getOrNull()
        }

        val provider = AppSabrPoTokenProvider(dto.videoId, webPoProvider, gvsSwapDeferred)
        val session = YoutubeSabrSession(info, audio, video, provider)
        val holder = SabrSessionHolder(dto.videoId, info, session, audio, video)
        // Cancellable with the holder — otherwise each of a burst of SABR retries leaves its own
        // watch-page GET running on the shared media OkHttp client for a session already released.
        holder.trackAuxJob(gvsSwapDeferred)

        // NOTE: the official-web clientAbrState fields (setWriteOfficialWebClientAbrFields /
        // PreferredFormats) are intentionally NOT enabled. They were a wall-era hack to look like a
        // real desktop web player, but they write FROZEN hardcoded telemetry (field 36 "elapsed wall
        // time"=41, field 39 "time since last action"=80, …) that never advances across requests.
        // With the reader-driven pump (which reports the live buffered edge as playerTime), the
        // server reads that frozen wall-time and concludes we're permanently over-buffered after the
        // initial fill, so it stops sending media (~63s in: a burst, then 71-byte empty responses).
        // The pump's correct playerTime/buffered reporting is what keeps the server delivering.

        // ── Two-phase PO token bootstrap ───────────────────────────────────────────────────────
        // THROUGH RUN 20: mint the real, videoId-bound (content) pot BEFORE the first request, so
        // every session opens with the "right" binding already in place. That still left GVS's
        // opening verdict close to a coin flip (~30% observed on devices with a working Play
        // Integrity backend, plausibly far worse on ones without — see PlayerViewModel's SABR
        // notes), and every retry meant tearing the whole session down and minting an entirely new
        // attestation — repeatedly asking the server for a fresh identity is exactly the pattern
        // its abuse defenses are built to notice.
        //
        // NOW: mint a REAL, session(visitorData)-bound pot FIRST — cheap, and usually already
        // covered by the app-wide BotGuard attestation from prewarm, so it costs no per-video wait
        // — and open the session on THAT. It is expected to read as grace (status=2): that is fine,
        // grace still serves real media (~60s worth observed), and while it plays the precisely
        // content(videoId)-bound token mints in the BACKGROUND from the very same BotGuard
        // attestation (sabr_potoken.js mints many times per attestation — see
        // WebPoTokenProvider.generateAttestedPoToken's doc — so this second mint is a cheap
        // `mintWith(identifier)` call, not a second attestation). The moment it is ready it is
        // pushed into this SAME live session (YoutubeSabrSession#upgradePoToken) — no teardown, no
        // second session, no second attestation. If GVS accepts the upgrade the very next response
        // comes back status=1.
        //
        // Exactly one mint of each kind, ever, per session. If the upgrade doesn't take, the
        // existing playback-error path (PlayerViewModel.retryAfterPlaybackError, via
        // SabrStreamPump's own "grace burst actually died" detection) is the one remaining safety
        // net — not another round of this.
        var needsUpgrade = true
        if (seedPoToken != null && seedPoToken.isNotEmpty()) {
            // Reuse an already-minted pot (e.g. a quality/audio switch rebuilding the session for
            // the SAME video) instead of re-running the bootstrap — skips a fresh mint entirely.
            session.streamState.setPoToken(seedPoToken)
            if (seedPoTokenUpgraded) {
                holder.markPoTokenUpgraded()
                needsUpgrade = false
                Log.i(TAG, "reused already content-bound WEB pot (${seedPoToken.size} bytes)")
            } else {
                Log.i(TAG, "reused session-bound WEB pot (${seedPoToken.size} bytes); upgrade still pending")
            }
        } else {
            val visitorData = dto.visitorData?.takeIf { it.isNotBlank() }
            // Nothing to session-bind to (no visitorData) — content binding is the only option, so
            // there's nothing left to upgrade to either.
            if (visitorData == null) needsUpgrade = false
            val bootstrapIdentifier = visitorData ?: dto.videoId
            val bootstrapPot = mintRealPot(
                webPoProvider, bootstrapIdentifier, dto.visitorData, clientVersion, forceReattest = false,
            )
            if (bootstrapPot != null) {
                session.streamState.setPoToken(bootstrapPot)
                Log.i(
                    TAG,
                    "seeded REAL bootstrap pot bound to " +
                        (if (visitorData != null) "visitorData/session" else "videoId/content") +
                        " (${bootstrapPot.size} bytes)",
                )
            } else {
                Log.w(TAG, "real bootstrap pot mint failed; seeding local cold-start token")
                runCatching {
                    session.streamState.setPoToken(YoutubeSabrProbe.coldStartPoToken(bootstrapIdentifier, 0))
                }
            }
        }

        if (needsUpgrade) {
            // Fire-and-forget in the sense that session creation never awaits this — the whole
            // point is that the FIRST request goes out immediately on the bootstrap pot — but NOT
            // uncancellable: the Job is tracked on `holder` below so a release() (video switch,
            // playback-error retry, quality change) cancels it instead of leaving it to mint to
            // completion for a session nobody can use any more. Before this, a burst of failed SABR
            // retries (each fetchSabr() call reaches this same create()) left their orphaned upgrade
            // mints piling up on WebPoTokenProvider's single process-wide mintMutex — each holding it
            // for up to GVS_MINT_TIMEOUT_MS — which starved the NEXT retry's own bootstrap mint, and,
            // since DASH's legacy-playback po-token mint shares that same mutex
            // (QuickJsRuntime.__nativeMintAttestedPoToken), starved DASH's fallback mint too. That is
            // the mechanism behind "both SABR and DASH fail together": not the pump over-fetching,
            // but retries never seeding forward (see PlayerViewModel.fetchSabr's seedFrom) compounded
            // by these orphaned mints serializing behind each other.
            //
            // Applying to `holder` after it's released is still a harmless no-op on its own (see
            // YoutubeSabrSession#upgradePoToken's doc) — cancellation here is about not occupying the
            // mutex pointlessly, not about correctness.
            val job = GlobalScope.launch(Dispatchers.IO) {
                // LOCAL ADDITION. If the watch page itself says GVS wants session/visitorData
                // binding for THIS video, the bootstrap pot above is already that — there is nothing
                // to upgrade TO, and minting a content(videoId)-bound pot here would be actively
                // wrong (see PoTokenBinding.gvsIdentifier's CAUTION: that is the exact configuration
                // run 19 found worse). Bounded wait: gvsSwapDeferred already carries its own ~2.5s
                // internal timeout; this is a safety belt, not the primary bound.
                val swapOverride = withTimeoutOrNull(GVS_SWAP_AWAIT_TIMEOUT_MS) {
                    gvsSwapDeferred.await()
                }
                if (swapOverride == true) {
                    holder.markPoTokenUpgraded()
                    Log.i(
                        TAG,
                        "watch page says GVS wants session-bound for ${dto.videoId} — bootstrap " +
                            "pot is already correct, skipping the content-bound upgrade mint",
                    )
                } else {
                    val upgraded = mintRealPot(
                        webPoProvider, dto.videoId, dto.visitorData, clientVersion, forceReattest = false,
                    )
                    if (upgraded != null) {
                        holder.applyUpgradedPoToken(upgraded)
                        Log.i(TAG, "upgraded ${dto.videoId} to content-bound pot (${upgraded.size} bytes)")
                    } else {
                        Log.w(TAG, "videoId-bound upgrade mint failed for ${dto.videoId}; staying on bootstrap pot")
                    }
                }
            }
            holder.trackUpgradeJob(job)
        }

        Log.i(
            TAG,
            "SABR session ready: profile=$profile audioItag=${audio.itag} videoItag=${video.itag} " +
                "(${video.width}x${video.height})",
        )

        return holder
    }

    /** Mint a real WebView/BotGuard WEB pot bound to [identifier] via the in-page att/get flow (the
     *  only flow GVS accepts — see AppSabrPoTokenProvider). Returns null (logged) on any failure;
     *  callers fall back to the local cold-start token or simply skip the optional upgrade. */
    private suspend fun mintRealPot(
        webPoProvider: WebPoTokenProvider,
        identifier: String,
        visitorData: String?,
        clientVersion: String,
        forceReattest: Boolean,
    ): ByteArray? = runCatching {
        val b64 = webPoProvider.generateAttestedPoToken(
            identifier = identifier,
            purpose = WebPoTokenProvider.PURPOSE_GVS,
            visitorData = visitorData,
            clientVersion = clientVersion,
            forceReattest = forceReattest,
        )
        Base64.decode(b64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }.onFailure {
        Log.w(TAG, "pot mint for identifier-bound $identifier failed: ${it.message}")
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    // Prefer AAC/mp4 audio (itag 140) over the "best" (Opus/webm). PipePipe found Opus/webm just
    // does NOT work through the media3 chunk pipeline — it under-supplies the audio renderer →
    // AudioTrack underruns → constant rebuffering. AAC (mp4, hardware-decoded, ~same bitrate) plays
    // smooth. Fall back to the best available audio if no mp4/AAC track is present.
    private fun pickAudioFormat(
        info: YoutubeSabrInfo,
        preferredAudioTrackId: String?,
    ): YoutubeSabrFormat? {
        val aac = info.formats.filter { it.isAudio && (it.mimeType?.contains("mp4") == true) }
        if (aac.isEmpty()) return info.findBestAudioFormat()
        // Requested language (its audioTrackId), if present.
        if (!preferredAudioTrackId.isNullOrEmpty()) {
            val match = aac.filter { it.audioTrackId == preferredAudioTrackId }
            if (match.isNotEmpty()) return match.maxByOrNull { it.bitrate }
        }
        // No preference / not found: prefer the ORIGINAL track (no audioTrackId) over any dub,
        // which fixes "random Japanese voiceover" on multi-audio videos.
        val original = aac.filter { it.audioTrackId == null }
        return (if (original.isNotEmpty()) original else aac).maxByOrNull { it.bitrate }
    }

    private fun parseProfile(name: String): YoutubeSabrClientProfile =
        runCatching { YoutubeSabrClientProfile.valueOf(name) }
            .getOrDefault(YoutubeSabrClientProfile.WEB)

    /** Also used by [SabrReloadProvider] — a reload re-probes, so it mints a new nonce like upstream. */
    internal fun generateCpn(): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(CPN_ALPHABET[RANDOM.nextInt(CPN_ALPHABET.length)]) }
        return sb.toString()
    }
}
