package com.example.ytclient.sabr

import android.util.Log
import com.example.ytclient.youtube.bridge.YoutubeBridge
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrClientProfile
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrInfo
import org.schabi.newpipe.extractor.services.youtube.sabr.YoutubeSabrProbe
import java.io.IOException

/**
 * Supplies a fresh [YoutubeSabrInfo] when the SABR session asks to reload the player response.
 *
 * Two things reach this: the server setting `reloadPlayerResponse`, and our own retry-budget check
 * in `BuiltinSabrSessionPolicy` firing `TRY_RELOAD` once the server's stated `maxRetries` is spent.
 * Until this provider existed, both hit `YoutubeSabrProbe.fetchSabrInfo`'s "no SabrInfoProvider
 * configured" throw, which surfaced as `SABR pump fatal` and killed the pump thread — recovery then
 * depended on ExoPlayer noticing the starved readers and the view-model rebuilding, which in the
 * run-15 log took a further 33 seconds.
 *
 * The re-fetch is deliberately `forceFresh`: a reload that reuses the cached Innertube session and
 * PO token asks the same rejected identity for new URLs. Run 15 is the evidence — two sessions
 * built on the warmup token opened in grace and died at 60s, and the one built after the watchdog
 * dropped the session and re-minted opened at status 1 and played out the rest of the run.
 *
 * A new content-playback nonce is minted per reload. That matches upstream, where `fetchSabrInfo`
 * re-runs the whole extraction and naturally produces a new one.
 */
object SabrReloadProvider {

    private const val TAG = "SabrReloadProvider"

    /**
     * How long the pump thread may block inside a reload. A `forceFresh` re-probe is expensive —
     * att/get + BotGuard attestation + the /player round trip measured ~18s in the run-15 log — but
     * it must not be unbounded, because this runs on the single pump thread and nothing streams
     * while it is in flight.
     */
    private const val RELOAD_TIMEOUT_MS = 45_000L

    /** Install the reload hook. Idempotent; call once per process. */
    fun install(bridge: YoutubeBridge) {
        YoutubeSabrProbe.setSabrInfoProvider(Impl(bridge))
    }

    private class Impl(private val bridge: YoutubeBridge) : YoutubeSabrProbe.SabrInfoProvider {

        override fun fetch(
            videoId: String,
            profile: YoutubeSabrClientProfile,
            localization: Localization,
            contentCountry: ContentCountry,
        ): YoutubeSabrInfo {
            Log.i(TAG, "SABR reload requested for $videoId — re-probing with a fresh session/token")
            // The pump thread is a plain Java thread with no coroutine context of its own, and
            // YoutubeSabrSession.maybeReload is a blocking call that expects the info back or an
            // exception. Blocking here is therefore correct rather than merely convenient: there is
            // no media to serve until the new streaming URL exists.
            val dto = try {
                runBlocking {
                    withTimeout(RELOAD_TIMEOUT_MS) { bridge.getSabrInfo(videoId, forceFresh = true) }
                }
            } catch (timeout: TimeoutCancellationException) {
                throw IOException("SABR reload timed out after ${RELOAD_TIMEOUT_MS}ms", timeout)
            } catch (t: Throwable) {
                // Surface as IOException so the session's own reload budget treats it as a failed
                // re-probe and gives up cleanly, rather than an unchecked throw escaping the pump.
                throw IOException("SABR reload failed: ${t.message}", t)
            }

            if (dto.serverAbrStreamingUrl.isNullOrEmpty() || dto.ustreamerConfig.isNullOrEmpty()) {
                // maybeReload already treats a blank streaming URL as "reload came back unusable"
                // and returns false, but say why in the log — a silent false is hard to diagnose.
                Log.w(
                    TAG,
                    "SABR reload for $videoId came back incomplete " +
                        "(abrUrl=${!dto.serverAbrStreamingUrl.isNullOrEmpty()} " +
                        "ustreamer=${!dto.ustreamerConfig.isNullOrEmpty()})",
                )
            }

            val clientVersion = dto.clientVersion?.takeIf { it.isNotEmpty() } ?: profile.clientVersion
            return YoutubeSabrProbe.buildInfo(
                profile,
                dto.videoId,
                SabrSessionFactory.generateCpn(),
                clientVersion,
                dto.visitorData,
                dto.serverAbrStreamingUrl,
                dto.ustreamerConfig,
                dto.adaptiveFormatsJson,
            )
        }
    }
}
