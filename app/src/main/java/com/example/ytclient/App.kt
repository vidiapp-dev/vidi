package com.example.ytclient

import android.app.Application
import android.os.Build
import android.util.Log
import android.widget.Toast
import coil.Coil
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.ytclient.core.local.LibraryStore
import com.example.ytclient.core.perf.Startup
import com.example.ytclient.core.settings.PlaybackSettings
import com.example.ytclient.core.webpo.WebPoTokenProvider
import com.example.ytclient.sabr.SabrReloadProvider
import com.example.ytclient.youtube.bridge.YoutubeBridge
import com.example.ytclient.youtube.bridge.YoutubeBridgeImpl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class App : Application() {

    /**
     * Single process-wide WebView-backed PO token provider. Holds one hidden
     * WebView for the entire app lifetime — BotGuard init costs ~1.5 s on
     * first launch but per-identifier mints are fast (<100 ms) after that.
     */
    val webPoProvider: WebPoTokenProvider by lazy { WebPoTokenProvider(this) }

    /**
     * Shared OkHttp stack for SABR media POSTs (serverAbrStreamingUrl). Reused across player
     * sessions so connection pooling / TLS sessions to googlevideo persist. Generous read timeout
     * because a single SABR response can carry several MB of media.
     */
    val sabrHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Single process-wide [YoutubeBridge]. Owning it here means there's exactly
     * one QuickJS runtime + one Innertube session across every screen — no
     * per-ViewModel reinitialisation, no duplicated state.
     *
     * Access from a ViewModel via `(application as App).youtubeBridge`.
     */
    val youtubeBridge: YoutubeBridge by lazy { YoutubeBridgeImpl(this, webPoProvider) }

    /**
     * Process-wide local library (subscriptions, watch history, playlists). Single instance so a
     * mutation on any screen (subscribe in the player, record a play) is observed everywhere live.
     */
    val library: LibraryStore by lazy { LibraryStore(this) }

    /**
     * Process-wide playback preferences (currently just the SABR-vs-DASH transport choice). Single
     * instance so the Settings tab and every video load read/write the same value — see
     * [PlaybackSettings]'s doc for why this isn't part of the player's own prefs.
     */
    val playbackSettings: PlaybackSettings by lazy { PlaybackSettings(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Completed once by HomeViewModel's very first feed load (see [com.example.ytclient.ui.home.HomeViewModel]).
     * The background discovery pass awaits this (bounded) so it doesn't race the feed's own first
     * getHomeFeed call for the BROWSE context's call queue — the two genuinely share one queue
     * (see YoutubeBridgeImpl's D1 doc), unlike warmup(), which since D1 runs on the separate
     * playback context and no longer needs any ordering against the feed at all.
     */
    val homeFeedReady = CompletableDeferred<Unit>()

    override fun onCreate() {
        super.onCreate()

        // Stamp the cold-start origin before anything else runs — every `Startup.mark` below (and
        // in HomeViewModel) is a delta from here. `adb logcat -s Startup` reads as a timeline.
        Startup.begin()

        // LOCAL ADDITION — diagnostic only, no behavior change. Logs the device identity BotGuard's
        // own snapshot phase would also be able to see (via the WebView's canvas/WebGL fingerprint
        // and Android's Build.* properties), so a logcat capture makes it unambiguous whether a given
        // SABR grace→protected run happened on a real handset or an emulator. This matters because
        // an emulator's software GL renderer (GFXSTREAM/goldfish/SwiftShader — visible to page JS via
        // WebGL just like a canvas fingerprint) and its generic, widely-reused AVD build fingerprint
        // are well-known bot-detection signals industry-wide, independent of whether the minted PO
        // token itself is valid — see the WebPoTokenProvider logs showing `fallback=no` (a genuine,
        // non-degraded integrity token) on every mint even while sessions still went protected, which
        // rules out a broken/fallback token as the cause and points at a device-trust signal instead.
        logDeviceIdentity()

        // ── Coil, set up FIRST so the disk-cache warm below has something to warm ────────────
        //
        // Building the loader is cheap: `memoryCache {}` / `diskCache {}` / `okHttpClient {}` all
        // take INITIALIZERS that Coil wraps in a `lazy`, so nothing here touches disk or the
        // network on the main thread. Everything expensive is deferred to the warm coroutine.
        val imageLoader = ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.20)   // 20 % of available RAM for bitmaps
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    // An ABSOLUTE cap, not `maxSizePercent(0.02)`. The percent form is computed
                    // from the filesystem's TOTAL block count (coil's DiskCache.kt: `maxSizePercent
                    // * stats.blockCountLong * stats.blockSizeLong`), not free space, and is then
                    // clamped into [minimumMaxSizeBytes, maximumMaxSizeBytes] = [10 MB, 250 MB]. On
                    // any modern phone 2 % of total storage is well past 250 MB, so the old setting
                    // did not mean the "≈ 50 MB typical" its comment claimed — it pinned the cache
                    // at coil's 250 MB CEILING, i.e. ~8k thumbnails. That matters because the
                    // journal is read and every entry stat'd on first use (see the warm below), and
                    // that cost scales with entry count. 128 MB is still thousands of thumbnails.
                    .maxSizeBytes(IMAGE_DISK_CACHE_BYTES)
                    .build()
            }
            .okHttpClient {
                OkHttpClient.Builder()
                    // EVERY thumbnail in the app is on i.ytimg.com (see VideoRow/VideoCard, which
                    // build the URL from the video id), and OkHttp's default Dispatcher allows
                    // `maxRequestsPerHost = 5` — verified in okhttp 4.12's Dispatcher.kt. So a
                    // screenful of cards downloaded in strict waves of five, each wave on a cold
                    // start paying its own connection setup, which is most of why the feed appeared
                    // to fill in slowly rather than all at once. Thumbnails are small, independent
                    // GETs against a CDN; there is no reason to keep them at the default meant for
                    // protecting ordinary API hosts. `maxRequests` (64) is left alone.
                    .dispatcher(Dispatcher().apply { maxRequestsPerHost = THUMBNAIL_PARALLELISM })
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS)
                    .build()
            }
            .crossfade(true)
            .build()

        Coil.setImageLoader(imageLoader)

        // Force the image disk cache open OFF the main thread, before any thumbnail asks for it.
        //
        // The code this replaces set the shared ImageLoader here and claimed that made
        // `DiskLruCache.initialize()` run "exactly once, eagerly". It did not, and the failure mode
        // it was written to prevent was still live: `ImageLoader.Builder.diskCache {}` stores a
        // `lazy`, and coil's DiskLruCache initialises itself from inside `get`/`edit` — both of
        // which are `@Synchronized` on the whole cache object. So the FIRST thumbnail request
        // resolved the lazy and then ran `readJournal()` + `processJournal()` (which stats every
        // entry) while holding the one monitor every other request needs, and the rest of the first
        // screenful queued behind it.
        //
        // `DiskCache.size` is the cheapest public thing that forces both steps: coil's
        // `DiskLruCache.size()` is `@Synchronized` and calls `initialize()`. Touching it here means
        // the journal walk happens on an IO thread while the user is still looking at a spinner.
        appScope.launch {
            try {
                val bytes = imageLoader.diskCache?.size
                Startup.mark("image disk cache opened (${bytes?.div(1024)?.div(1024) ?: 0} MB on disk)")
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w("App", "image disk cache warm failed (will initialise lazily): ${t.message}")
            }
        }

        // Build the local library and read every collection off disk HERE, on an IO thread, rather
        // than letting HomeViewModel's constructor do it during composition.
        //
        // LibraryStore's constructor used to decode nine JSON blobs — history (up to 500 entries,
        // each carrying its uploader tag list), every playlist (up to 2000 videos each), subs,
        // search history, … — and the first of those reads also blocks on SharedPreferences
        // finishing its full-file XML parse. All of it ran on the main thread, because
        // `HomeViewModel` touches `app.library` in its constructor and `viewModel()` builds it
        // during composition. Its loads are lazy now (see LibraryStore.prewarm), so whoever asks
        // first pays — and this makes that "first" an IO thread, well before the UI exists.
        appScope.launch {
            Startup.timed("library prewarmed") { library.prewarm() }
        }

        // Same reasoning for the bridge, and with a sharper edge: `App.youtubeBridge` is a
        // `synchronized` lazy that HomeViewModel's constructor ALSO resolves, so the main thread
        // and the playback-warmup coroutine below raced for it and whichever arrived second blocked
        // on the other. RUNTIME_PERF_PLAN.md's A3 section already recorded the result of losing
        // that race — a ~2 s `SynchronizedLazyImpl` monitor contention on the first-frame path,
        // filed as "worth a look later". Resolving it here, first, plus HomeViewModel now holding
        // its own `by lazy` so it never touches this from the constructor at all, means the main
        // thread is no longer a participant. Constructing it is itself non-trivial: two
        // QuickJsRuntime instances (a thread and an OkHttp stack each), another OkHttp stack for
        // the native Innertube client, and `detectRegion`'s two TelephonyManager binder calls.
        appScope.launch {
            Startup.timed("youtubeBridge constructed") { youtubeBridge }
        }

        // Kick off the PO-token WebView's BotGuard bring-up IMMEDIATELY and INDEPENDENTLY of the
        // QuickJS runtime. This is the expensive first-launch step (creating the hidden WebView,
        // navigating youtube.com, running BotGuard — observed to take several seconds, almost all of
        // it Android WebView/Chromium init, not our code) and it used to only start once something
        // inside the QuickJS call queue asked for a token (warmup()/getSabrInfo()), which serialized
        // it behind whatever else was using that queue. WebPoTokenProvider has its own separate lock
        // (mintMutex) — completely unrelated to QuickJsRuntime's callLock — so running it here means
        // it warms up in the background while the user is just looking at the home feed, and by the
        // time a video is opened the token is very likely already minted.
        appScope.launch {
            try {
                webPoProvider.prewarm()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w("App", "PO token prewarm failed (will retry lazily on first mint): ${t.message}")
            }
        }

        // Warm up the JS runtimes and mint the PO token at startup (not when the
        // search screen opens), so playback is ready by the time the user taps
        // a video. Only surfaces a toast if it fails.
        //
        // D1 (RUNTIME_PERF_PLAN.md) removed the ordering dance that used to live here
        // (waitBeforeWarmup / searchActive): warmup() now runs on the dedicated PLAYBACK QuickJS
        // context, so it no longer competes with the home feed's getHomeFeed or the search bar's
        // getSearchSuggestions (both on the separate browse context) — starting it immediately
        // just means playback is ready sooner. poTokenInfo() after warmup() is cheap (it reads the
        // token warmup() already minted), and prewarm() above usually has the WebView ready by the
        // time warmup()'s own token request lands.
        //
        // "Cheap" depends on an invariant that broke silently once: poTokenInfo() passes no
        // identifier, so it mints under whatever ensurePoToken() considers the session identity. In
        // run 23 that was still the ANON visitorData while warmup had minted under the PAGE one, so
        // the cache key differed, this call minted a THIRD token nothing plays under, and it tore
        // down the page-identity BotGuard VM on the way. Both now resolve through
        // resolveSessionVisitorData(); outputs/mint_identity_test.mjs pins the cache hit.
        appScope.launch {
            try {
                // Let a SABR session re-probe its own player response. Without this the reload path
                // — the server asking for one, or our retry-budget check spending the server's
                // stated maxRetries — hits "no SabrInfoProvider configured", which surfaces as
                // `SABR pump fatal` and kills the pump thread. Installed on this coroutine rather
                // than inline in onCreate() because reading `youtubeBridge` can block on that
                // lazy's construction (now usually already done by the dedicated coroutine above),
                // and this must not sit on the main thread to set a static hook. Setting the hook
                // itself is free, and it lands well before any playback session can exist.
                SabrReloadProvider.install(youtubeBridge)
                youtubeBridge.initialize()
                Startup.mark("playback QuickJS context initialised")
                youtubeBridge.warmup()
                Startup.mark("playback warmup complete")
                val info = youtubeBridge.poTokenInfo()
                Startup.mark("PO token ready (hasToken=${info.hasToken})")
                if (!info.hasToken) {
                    toast("Playback setup failed" + (info.error?.let { ": $it" } ?: ""))
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                toast("Playback setup failed: ${t.message ?: "unknown error"}")
            }
        }

        Startup.mark("App.onCreate returned")
    }

    private suspend fun toast(message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(this@App, message, Toast.LENGTH_LONG).show()
    }

    /**
     * LOCAL ADDITION — diagnostic only. Logs Build.* identity fields and a best-effort emulator
     * heuristic, once, at process start. See the call site in [onCreate] for why this exists: it's
     * the difference between "the code is wrong" and "this test run's device signal is what the
     * server is reacting to," and that distinction is invisible in a logcat capture unless it's
     * logged explicitly. Heuristic only — false negatives (an emulator not caught here) don't
     * indicate anything; a positive is a strong signal, not a certainty.
     */
    private fun logDeviceIdentity() {
        val emulatorHit = listOf(
            Build.FINGERPRINT.contains("generic", ignoreCase = true),
            Build.FINGERPRINT.contains("sdk_gphone", ignoreCase = true),
            Build.FINGERPRINT.startsWith("generic"),
            Build.MODEL.contains("google_sdk", ignoreCase = true),
            Build.MODEL.contains("sdk_gphone", ignoreCase = true),
            Build.MODEL.contains("Emulator", ignoreCase = true),
            Build.MODEL.contains("Android SDK built for", ignoreCase = true),
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true),
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")),
            Build.PRODUCT.equals("sdk", ignoreCase = true),
            Build.PRODUCT.contains("sdk_gphone", ignoreCase = true),
            Build.PRODUCT.contains("vbox86p", ignoreCase = true),
            Build.HARDWARE.contains("goldfish", ignoreCase = true),
            Build.HARDWARE.contains("ranchu", ignoreCase = true),
            Build.HARDWARE.contains("vbox86", ignoreCase = true),
        ).any { it }
        Log.i(
            "App",
            "device identity: brand=${Build.BRAND} model=${Build.MODEL} product=${Build.PRODUCT} " +
                "hardware=${Build.HARDWARE} fingerprint=${Build.FINGERPRINT} " +
                "likelyEmulator=$emulatorHit — a positive here means BotGuard's own WebView-side " +
                "fingerprinting (WebGL renderer string, etc.) very likely sees the same signal, " +
                "independent of PO-token validity",
        )
    }

    override fun onTerminate() {
        super.onTerminate()
        youtubeBridge.close()
        webPoProvider.close()
    }

    private companion object {
        /** See the `diskCache {}` block in [onCreate] for why this is absolute, not a percentage. */
        const val IMAGE_DISK_CACHE_BYTES = 128L * 1024 * 1024

        /**
         * Concurrent thumbnail downloads allowed against i.ytimg.com — see the `okHttpClient {}`
         * block in [onCreate]. Sized to comfortably cover one screenful of cards in the grouped
         * home layout (several shelves, a few visible cards each) so the first paint is one wave
         * rather than three, without being so high that a fast scroll can starve the connection
         * pool of sockets for anything else.
         */
        const val THUMBNAIL_PARALLELISM = 15
    }
}
