package com.example.ytclient.core.webpo

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.WebViewMediaIntegrityApiStatusConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Generates YouTube WebPO tokens by running BotGuard inside a hidden Chromium
 * WebView. This works where bgutils-js-in-QuickJS doesn't, because BotGuard's
 * env probes specifically require a real browser runtime.
 *
 * Architecture cribbed from SmartTube's `PoTokenWebView.kt` — see
 * https://github.com/yuliskov/MediaServiceCore/blob/HEAD/youtubeapi/src/main/java/com/liskovsoft/youtubeapi/app/potokennp2/generators/PoTokenWebView.kt
 *
 * Lifecycle:
 *  • Constructed once per process (held by [App]).
 *  • First [generatePoToken] call triggers WebView init + the Create+GenerateIT
 *    handshake. Subsequent calls reuse the same integrity token to mint fresh
 *    per-identifier PO tokens in <100 ms.
 *  • Integrity token re-minted automatically ~11 hours after issue (Google
 *    typically returns ttl=43200s/12h; we apply a 10-minute safety margin).
 *
 * Threading:
 *  • The WebView lives on the main thread. All `webView.*` calls go through
 *    [runOnMain] which posts to [Looper.getMainLooper()].
 *  • JS-bridge callbacks come in on a dedicated bridge thread — we never block
 *    the main thread waiting for one.
 *  • The HTTP calls inside the bridge callbacks run on the bridge thread
 *    (OkHttp blocking calls). That's fine: no UI thread, no JS thread.
 *  • [generatePoToken] is a suspend function — callers from coroutines block
 *    on a CompletableDeferred resolved by the bridge callback.
 */
class WebPoTokenProvider(private val context: Context) {

    private val tag = "WebPoTokenProvider"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private var webView: WebView? = null

    // Init state — completed by onJsInitializationDone (or *Error).
    @Volatile private var initDeferred: CompletableDeferred<Unit>? = null
    @Volatile private var integrityExpiresAtMs: Long = 0L
    // When onJsInitializationDone last fired; 0 if never / reset. Drives awaitPageSettled().
    @Volatile private var initCompletedAtMs: Long = 0L

    // Per-identifier mint requests in flight; keyed by identifier.
    private val pendingMints = ConcurrentHashMap<String, CompletableDeferred<String>>()

    // In-flight in-page SABR/GVS mint (att/get flow); single-slot, serialized by mintMutex.
    @Volatile private var pendingSabrMint: CompletableDeferred<String>? = null

    // The youtube.com page's OWN visitorData — the identity BotGuard actually attests under, since
    // att/get is fetched by the page with the page's cookies. Captured once per page load. Completes
    // with "" when the page has no valid identity to offer, so waiters always make progress rather
    // than hanging. See SabrSessionIdentity for what this is for and why it is flag-guarded.
    @Volatile private var pageVisitorData: CompletableDeferred<String> = CompletableDeferred()

    // Serialises generatePoToken() calls against init/re-init races.
    private val mintMutex = Mutex()

    /**
     * Kick off the WebView + BotGuard bring-up as early as possible — call this from App startup,
     * before anything actually needs a token. The first init is the expensive part (creating the
     * WebView, navigating youtube.com, running BotGuard: several seconds, mostly Android
     * WebView/Chromium bring-up, observed to dominate cold-start player latency). Doing that here,
     * decoupled from the QuickJS runtime entirely, means it happens in the background while the
     * user is just looking at the home feed, instead of stalling behind the QuickJS call queue the
     * way it did when only [generateGvsPoToken] (invoked from inside a getSabrInfo/warmup call)
     * triggered it. Safe to call concurrently with a real mint: mintMutex serializes them, and
     * [ensureInitialized] is a no-op once already initialised.
     *
     * Also absorbs the post-init settle window (see [awaitPageSettled]) so that, in the normal case,
     * it is spent in the background here rather than in front of a mint someone is waiting on.
     */
    suspend fun prewarm() = mintMutex.withLock {
        ensureInitialized()
        awaitPageSettled()
    }

    /**
     * Block until at least [SETTLE_AFTER_INIT_MS] has elapsed since the page finished initialising.
     *
     * Deliberately driven by a TIMESTAMP rather than by "did this call do the init", so it does not
     * depend on which coroutine happens to win [mintMutex] first. App starts [prewarm] and the JS
     * warmup concurrently; whichever reaches a mint first is the one that must wait, and every mint
     * after the window has passed is unaffected (the subtraction goes negative and this returns
     * immediately). Callers must already hold [mintMutex].
     */
    private suspend fun awaitPageSettled() {
        val completedAt = initCompletedAtMs
        if (completedAt == 0L) return
        val remaining = SETTLE_AFTER_INIT_MS - (System.currentTimeMillis() - completedAt)
        if (remaining <= 0) return
        Log.i(tag, "WebPO settling for ${remaining}ms before minting")
        delay(remaining)
    }

    /**
     * Mint a PO token for [identifier]. Identifier is the binding context —
     * the videoId for SABR-protected playback, or visitorData for session-level
     * checks. Returns the URL-safe base64 PO token, or throws.
     */
    suspend fun generatePoToken(identifier: String): String = mintMutex.withLock {
        ensureInitialized()
        // Re-init if integrity token is stale.
        if (System.currentTimeMillis() >= integrityExpiresAtMs) {
            Log.i(tag, "Integrity token expired, re-initialising")
            withContext(Dispatchers.Main) { resetInternal() }
            ensureInitialized()
        }
        awaitPageSettled()
        val deferred = CompletableDeferred<String>()
        pendingMints[identifier] = deferred

        // Build a safe JS string literal. identifier is YT-shaped so usually
        // alphanumeric + dashes/underscores, but encode defensively.
        val safeIdJsLiteral = JSONObject.quote(identifier)
        val u8 = newUint8ArrayJs(identifier.toByteArray(Charsets.UTF_8))
        runOnMain {
            webView?.evaluateJavascript(
                """
                (function() {
                  try {
                    var ident = $safeIdJsLiteral;
                    var u8id = $u8;
                    var poTokenU8 = obtainPoToken(this.webPoSignalOutput, this.integrityToken, u8id);
                    var s = '';
                    for (var i = 0; i < poTokenU8.length; i++) {
                      if (i !== 0) s += ',';
                      s += poTokenU8[i];
                    }
                    $JS_INTERFACE.onObtainPoTokenResult(ident, s);
                  } catch (e) {
                    $JS_INTERFACE.onObtainPoTokenError(${JSONObject.quote(identifier)}, e + '\n' + (e && e.stack));
                  }
                })();
                """.trimIndent(),
                null,
            )
        }
        deferred.await()
    }

    /**
     * Mint a PO token bound to [identifier], running the WHOLE BotGuard pipeline IN-PAGE
     * (same-origin youtube.com): the page itself POSTs `/youtubei/v1/att/get` for the challenge, runs
     * the VM, POSTs `/api/jnn/v1/GenerateIT` for the integrity token, and mints the bound token.
     *
     * This is DISTINCT from [generatePoToken]: that fetches the challenge out-of-page via Kotlin
     * OkHttp (`/api/jnn/v1/Create`), which yields a token the Innertube `/player` call accepts but the
     * GVS streaming server does NOT (it holds the stream at protection status=2, never 1, and cuts
     * media at ~60s). Only a same-origin att/get attestation flips SABR protection to status=1.
     * Mirrors PipePipe's `sabr_potoken_poc.js`. Returns the URL-safe base64 PO token, or throws.
     *
     * ⚠️ This function is binding-agnostic ON PURPOSE. It mints whatever [identifier] it is handed;
     * it does not know or care whether that is a videoId or a visitorData. The endpoint→identity
     * assignment lives in exactly one place — `PoTokenBinding` — so that the two halves cannot drift
     * apart. It used to take a `videoId` first parameter that the QuickJS caller was passing a
     * visitorData into, which made every log line in runs 15-18 claim the wrong binding.
     */
    suspend fun generateAttestedPoToken(
        /** The value the token is cryptographically BOUND to: a videoId or a visitorData. */
        identifier: String,
        /** Which endpoint the token is for — [PURPOSE_PLAYER] or [PURPOSE_GVS]. Logging only. */
        purpose: String = PURPOSE_GVS,
        /** The session identity we attest under. Independent of [identifier]. */
        visitorData: String? = null,
        clientVersion: String? = null,
        /**
         * Discard the page's stored BotGuard attestation and run a fresh one before minting.
         *
         * Normally false: `sabr_potoken.js` now attests once and mints many times from the retained
         * mint callback (see its header). Pass true only when the streaming server has actually
         * REFUSED a token this attestation produced — re-minting from the same VM would just produce
         * another token from the same rejected attestation.
         */
        forceReattest: Boolean = false,
    ): String = mintMutex.withLock {
        try {
            generateAttestedPoTokenOnce(identifier, purpose, visitorData, clientVersion, forceReattest)
        } catch (ce: CancellationException) {
            // Don't treat a cancelled mint (caller moved on — e.g. the player screen closed mid-fetch)
            // as a failure worth resetting the WebView session and retrying over; there's nothing left
            // to retry FOR. Let it propagate so the coroutine actually stops.
            throw ce
        } catch (t: Throwable) {
            // A long screen-locked / backgrounded stretch can leave the hidden BotGuard WebView's
            // renderer stalled or outright killed by Android — it's never attached to a visible
            // window, so the OS is freer to reclaim it under memory pressure than a foregrounded
            // WebView. Its in-page fetch()es then fail with a generic "Failed to fetch" even though
            // connectivity itself is fine, and retrying the SAME page never recovers (this is what
            // surfaced as "GVS mint failed: Failed to fetch" after playing locked for a while — the
            // cold-start grace burst played fine, but the forced re-mint once the server demanded a
            // real token came back from a renderer that had already died). Recreate the WebView +
            // BotGuard session from scratch and retry exactly once before giving up; a genuine
            // network outage fails the same way again on the retry and surfaces normally.
            Log.w(tag, "$purpose mint failed (${t.message}) — resetting WebView session, retrying once")
            withContext(Dispatchers.Main) { resetInternal() }
            // resetInternal() reloaded the page, so the stored attestation is gone with it; the
            // retry attests from scratch whatever the caller asked for.
            generateAttestedPoTokenOnce(identifier, purpose, visitorData, clientVersion, true)
        }
    }

    private suspend fun generateAttestedPoTokenOnce(
        identifier: String,
        purpose: String,
        visitorData: String?,
        clientVersion: String?,
        forceReattest: Boolean,
    ): String {
        ensureInitialized()
        // This is the mint that signs /player and seeds the SABR session, i.e. the one whose
        // freshness the grace-vs-accepted split tracks. See SETTLE_AFTER_INIT_MS.
        awaitPageSettled()
        val deferred = CompletableDeferred<String>()
        pendingSabrMint = deferred
        val sabrJs = context.assets.open(SABR_POTOKEN_JS).bufferedReader().use { it.readText() }
        // Inject the session's OWN visitorData + clientVersion so the script doesn't scrape a garbage
        // value off the page (that produced a 558-char visitorData → invalid header → "Failed to
        // fetch"). Binding to the session's visitorData is also more correct for the GVS pot.
        val setVars = buildString {
            append("window.__SABR_POC_IDENTIFIER=").append(JSONObject.quote(identifier)).append(";\n")
            append("window.__SABR_POC_PURPOSE=").append(JSONObject.quote(purpose)).append(";\n")
            append("window.__SABR_POC_FORCE_REATTEST=").append(forceReattest).append(";\n")
            if (!visitorData.isNullOrEmpty()) {
                append("window.__SABR_VISITOR_DATA=").append(JSONObject.quote(visitorData)).append(";\n")
            }
            if (!clientVersion.isNullOrEmpty()) {
                append("window.__SABR_CLIENT_VERSION=").append(JSONObject.quote(clientVersion)).append(";\n")
            }
        }
        runOnMain {
            val wv = webView
            if (wv == null) {
                pendingSabrMint = null
                deferred.completeExceptionally(PoTokenException("WebView not available for $purpose mint"))
            } else {
                wv.evaluateJavascript(setVars + sabrJs, null)
            }
        }
        // att/get + BotGuard + GenerateIT + mint, all in-page, can take a few seconds.
        return withTimeout(GVS_MINT_TIMEOUT_MS) { deferred.await() }
    }

    /**
     * Evaluate a self-contained player script (youtubei.js's `BuildScriptResult.output`
     * for the n/signature decipher) in the WebView's real Chromium V8 and return the JSON
     * of its result object (`{sig?, n?}`), or `"{}"` on error.
     *
     * Why the WebView and not an embedded engine? The script is modern base.js code —
     * ES6 `class extends Error`, `new.target`, rest/spread, and crucially the nsig
     * transform is **URL-constructor style** (it uses `URL`/`URLSearchParams`). Mozilla
     * Rhino can't parse ES6 classes; QuickJS runs it but silently no-ops the transform
     * (no `URL` API) → the deciphered `n` came back unchanged → serverAbrStreamingUrl 403.
     * A real browser engine runs it natively and correctly. We reuse the already-warmed
     * BotGuard WebView (its youtube.com page has CSP stripped, so injected eval is fine).
     *
     * The script is wrapped so its trailing `return {sig, n}` is captured and any throw is
     * surfaced as `{__error}` (logged, returns `"{}"`). `evaluateJavascript` JSON-encodes
     * the returned object, so the callback string is exactly the JSON the JS shim parses.
     */
    suspend fun evaluatePlayerScript(code: String): String = mintMutex.withLock {
        ensureInitialized()
        val deferred = CompletableDeferred<String>()
        // Outer IIFE scopes the script's top-level `const`s so they don't touch the page;
        // inner IIFE captures its `return {sig, n}`. Returns an OBJECT → single JSON encode.
        val wrapped = buildString {
            append("(function(){try{return (function(){\n")
            append(code)
            append("\n})();}catch(e){return {__error:String((e&&e.message)||e)};}})()")
        }
        runOnMain {
            val wv = webView
            if (wv == null) {
                deferred.completeExceptionally(PoTokenException("WebView not available for eval"))
            } else {
                try {
                    wv.evaluateJavascript(wrapped) { result -> deferred.complete(result ?: "null") }
                } catch (t: Throwable) {
                    deferred.completeExceptionally(t)
                }
            }
        }
        val result = deferred.await()
        val obj = runCatching { JSONObject(result) }.getOrNull()
        when {
            obj == null -> {
                Log.w(tag, "player-script eval: non-object result: ${result.take(120)}")
                "{}"
            }
            obj.has("__error") -> {
                Log.w(tag, "player-script eval error: ${obj.optString("__error")}")
                "{}"
            }
            else -> result
        }
    }

    /** Drop the WebView and integrity token. Next call re-inits from scratch. */
    fun reset() {
        runOnMain { resetInternal() }
    }

    /** Tear down WebView. Call from App.onTerminate (or never — it's cheap). */
    fun close() {
        runOnMain {
            try { webView?.loadUrl("about:blank") } catch (_: Throwable) {}
            try { webView?.onPause() } catch (_: Throwable) {}
            try { webView?.removeAllViews() } catch (_: Throwable) {}
            try { webView?.destroy() } catch (_: Throwable) {}
            webView = null
            initDeferred = null
            integrityExpiresAtMs = 0L
            initCompletedAtMs = 0L
            // The page is being reloaded, so the identity we captured belongs to a page that no
            // longer exists. Replace the deferred rather than clearing it: anyone already awaiting
            // the old one must still be released.
            pageVisitorData.complete("")
            pageVisitorData = CompletableDeferred()
        }
    }

    // ── Init ────────────────────────────────────────────────────────────────

    private suspend fun ensureInitialized() {
        val existing = initDeferred
        if (existing != null && existing.isCompleted && integrityExpiresAtMs > System.currentTimeMillis()) {
            return
        }
        val deferred = CompletableDeferred<Unit>()
        initDeferred = deferred
        withContext(Dispatchers.Main) {
            createWebViewIfNeeded()
            loadHtmlAndKickoff()
        }
        deferred.await()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebViewIfNeeded() {
        if (webView != null) return
        val wv = WebView(context.applicationContext)
        wv.settings.javaScriptEnabled = true
        // Was `true` originally for the pure PO-token mint flow. Now needs to
        // be `false` because the WebView also handles SABR fetches (real
        // Chromium TLS/cookies/HTTP-2 to satisfy Google's WAF). The PO mint
        // still goes through OkHttp from bridge callbacks; only SABR uses
        // the WebView's own network stack.
        wv.settings.blockNetworkLoads = false
        wv.settings.userAgentString = USER_AGENT
        // Domain-storage on so cookies & localStorage persist across reloads
        // of the warmup page.
        wv.settings.domStorageEnabled = true
        setSafeBrowsingEnabledSafely(wv.settings, false)
        // Disable the WebView Media Integrity API rather than let youtube.com's own page JS call it
        // and hit a native Play Integrity StandardIntegrity bind failure. Confirmed via logcat: on
        // any device without a working Play Integrity backend (no Play Store, GMS disabled, a
        // de-Googled ROM), the bind fails and that failure surfaces INSIDE BotGuard's own obfuscated
        // interpreter as an uncaught `OperationError: Internal Error. Retry with an exponential
        // backoff.` (base.js) -- upstream of every try/catch boundary sabr_potoken.js/po_token.html
        // define, so it can't be caught from our side. Disabling it here means the page's own
        // feature-detection sees the API as simply unsupported, which is the SAME environment
        // bgutils-js / desktop Chrome-without-the-flag already runs BotGuard under successfully --
        // this doesn't ask BotGuard to accept a broken signal, it removes the signal so BotGuard
        // never asks in the first place.
        setMediaIntegrityApiStatusDisabledSafely(wv.settings)
        // Make sure the global CookieManager accepts cookies from
        // youtube.com / googlevideo.com — first-party AND third-party.
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
        // Pre-set consent cookies so the EU consent gate doesn't bounce us
        // to consent.youtube.com on first navigation. These are stable
        // public values used by every consent-bypass implementation.
        val cm = CookieManager.getInstance()
        cm.setCookie("https://www.youtube.com", "SOCS=CAESHAgBEhJnd3NfMjAyMzA0MTMtMF9SQzEaAmVuIAEaBgiAo_CmBg; domain=.youtube.com; path=/")
        cm.setCookie("https://www.youtube.com", "CONSENT=YES+42; domain=.youtube.com; path=/")
        wv.addJavascriptInterface(this, JS_INTERFACE)
        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                // Log only. We do NOT treat "Uncaught" here as a fatal init
                // error — once we load the real youtube.com page, YouTube's own
                // scripts (base.js etc.) throw uncaught errors that are
                // unrelated to our PO-token flow. Real failures in our flow are
                // reported explicitly via onJsInitializationError / onObtainPoTokenError.
                Log.d("$tag/js", "${m.messageLevel()} ${m.message()} @${m.sourceId()}:${m.lineNumber()}")
                return true
            }
        }
        wv.webViewClient = object : WebViewClient() {
            /**
             * Intercept the main youtube.com page response so we can strip the
             * `Content-Security-Policy` header before the WebView parses it.
             * Real YouTube ships a Trusted Types policy that blocks
             * `new Function(interpreterJavascript)()` inside BotGuard; we need
             * to disable that to keep the mint flow working.
             *
             * Sub-resources (CSS/JS/images) are passed through untouched.
             */
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url.toString()
                if (request.method != "GET") return null
                // The BotGuard interpreter (att/get challenge) is served from a CROSS-ORIGIN URL
                // (https://www.google.com/js/th/….js). The in-page sabr_potoken.js `fetch`es it from
                // the youtube.com origin, and Google serves it WITHOUT an Access-Control-Allow-Origin
                // header → CORS blocks it → "Failed to fetch". Re-serve it via OkHttp with ACAO:* so
                // the cross-origin fetch is allowed (mirrors PipePipe's fetchWithCors).
                if (url.contains("/js/th/")) {
                    return try { fetchWithCors(url) } catch (e: Exception) {
                        Log.w(tag, "interpreter intercept failed for $url: ${e.message}")
                        null
                    }
                }
                // Only the main HTML navigation needs CSP stripped — sub-
                // resource fetches are fine as-is.
                if (url != "https://www.youtube.com/" &&
                    url != "https://www.youtube.com" &&
                    !url.startsWith("https://www.youtube.com/?")) {
                    return null
                }
                return try { fetchAndStripCsp(url) } catch (e: Exception) {
                    Log.w(tag, "intercept failed for $url: ${e.message}")
                    null
                }
            }

            override fun onPageFinished(view: WebView, url: String) {
                if (!url.startsWith("https://www.youtube.com")) return
                val cookies = CookieManager.getInstance().getCookie("https://www.youtube.com") ?: ""
                Log.d(tag, "youtube.com loaded (cookies len=${cookies.length})")
                injectPoTokenHelpers(view)
                capturePageVisitorData(view)
            }
        }
        webView = wv
    }

    /**
     * Fetch [url] via OkHttp, strip CSP, and return a WebResourceResponse for
     * the WebView to consume. Set-Cookie headers from the OkHttp response are
     * pushed into the global CookieManager so the WebView treats them as if
     * they came in over its own connection.
     */
    private fun fetchAndStripCsp(url: String): WebResourceResponse {
        val cookieHeader = CookieManager.getInstance().getCookie(url) ?: ""
        val okReqB = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
        if (cookieHeader.isNotEmpty()) okReqB.addHeader("Cookie", cookieHeader)
        val okReq = okReqB.build()

        http.newCall(okReq).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()

            // Strip <meta http-equiv="Content-Security-Policy"> tags from body.
            val sanitised = rawBody.replace(
                Regex(
                    """<meta\s+http-equiv\s*=\s*["']?[Cc]ontent-[Ss]ecurity-[Pp]olicy(?:-[Rr]eport-[Oo]nly)?["']?[^>]*>""",
                ),
                "",
            )

            // Forward Set-Cookie headers into the global CookieManager.
            for (cookie in resp.headers("Set-Cookie")) {
                try { CookieManager.getInstance().setCookie(url, cookie) } catch (_: Throwable) {}
            }

            // Build response headers minus CSP-related ones.
            val outHeaders = mutableMapOf<String, String>()
            for ((k, vs) in resp.headers.toMultimap()) {
                val kl = k.lowercase()
                if (kl == "content-security-policy" ||
                    kl == "content-security-policy-report-only" ||
                    kl == "set-cookie" ||                           // already handled
                    kl == "content-length" ||                       // body was rewritten
                    kl == "content-encoding"                         // OkHttp already decoded
                ) continue
                outHeaders[k] = vs.first()
            }

            val mime = resp.header("Content-Type")?.substringBefore(";")?.trim() ?: "text/html"
            val encoding = resp.header("Content-Type")
                ?.let { Regex("""charset=([^;]+)""", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                ?: "utf-8"

            return WebResourceResponse(
                mime,
                encoding,
                resp.code,
                resp.message.ifEmpty { "OK" },
                outHeaders,
                ByteArrayInputStream(sanitised.toByteArray(Charsets.UTF_8)),
            )
        }
    }

    /**
     * Fetch a cross-origin BotGuard interpreter (`/js/th/…` on google.com) via OkHttp and re-serve
     * it to the WebView with `Access-Control-Allow-Origin: *` so the page's same-origin-policy lets
     * the in-page `fetch` read it. Without this the interpreter fetch is CORS-blocked → the att/get
     * GVS-pot mint can't load the VM. Mirrors PipePipe's WebViewPoTokenProvider.fetchWithCors.
     */
    private fun fetchWithCors(url: String): WebResourceResponse {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .build()
        http.newCall(req).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: ByteArray(0)
            val mime = resp.header("Content-Type")?.substringBefore(";")?.trim()
                ?: "application/javascript"
            val headers = mutableMapOf("Access-Control-Allow-Origin" to "*")
            return WebResourceResponse(
                mime,
                "UTF-8",
                if (resp.code in 100..599) resp.code else 200,
                resp.message.ifEmpty { "OK" },
                headers,
                ByteArrayInputStream(bytes),
            )
        }
    }

    /** Inject po_token.html helpers and trigger the BotGuard flow. */
    private fun injectPoTokenHelpers(view: WebView) {
        val helpersHtml = context.assets.open(PO_TOKEN_HTML).bufferedReader().use { it.readText() }
        val scriptStart = helpersHtml.indexOf("<script>")
        val scriptEnd = helpersHtml.lastIndexOf("</script>")
        val js = if (scriptStart >= 0 && scriptEnd > scriptStart) {
            helpersHtml.substring(scriptStart + "<script>".length, scriptEnd)
        } else helpersHtml
        // Top-level eval so function declarations land on `window`.
        val trigger = """

            if (typeof runBotGuard   === 'function') window.runBotGuard   = runBotGuard;
            if (typeof loadBotGuard  === 'function') window.loadBotGuard  = loadBotGuard;
            if (typeof snapshot      === 'function') window.snapshot      = snapshot;
            if (typeof obtainPoToken === 'function') window.obtainPoToken = obtainPoToken;
            console.log('po_token helpers loaded; runBotGuard=' + typeof window.runBotGuard);
            $JS_INTERFACE.downloadAndRunBotguard();
        """.trimIndent()
        view.evaluateJavascript(js + trigger, null)
    }

    private fun loadHtmlAndKickoff() {
        // Navigate to the real youtube.com so we get real cookies for the
        // cross-origin SABR fetches. The WebViewClient.shouldInterceptRequest
        // hook intercepts this main-page request, strips the
        // `Content-Security-Policy` headers (and inline `<meta http-equiv>`
        // CSP tags), and returns the sanitised HTML to the WebView. That
        // bypasses YouTube's Trusted Types policy that would otherwise block
        // `new Function(interpreterJavascript)()` inside BotGuard.
        webView!!.loadUrl("https://www.youtube.com/")
    }

    private fun resetInternal() {
        webView?.let {
            try { it.loadUrl("about:blank") } catch (_: Throwable) {}
        }
        initDeferred = null
        integrityExpiresAtMs = 0L
        // The page is being reloaded from scratch, so the next mint has to wait out a fresh settle
        // window too — it faces exactly the same half-warmed page the first one did.
        initCompletedAtMs = 0L
    }

    private fun onInitError(message: String) {
        Log.e(tag, "init error: $message")
        val d = initDeferred
        initDeferred = null
        d?.completeExceptionally(PoTokenException(message))
        // Fail any in-flight mints too — without init they can't proceed.
        val snapshot = pendingMints.toMap()
        pendingMints.clear()
        snapshot.values.forEach { it.completeExceptionally(PoTokenException(message)) }
    }

    // ── JavaScript bridge callbacks ─────────────────────────────────────────
    // These run on the WebView's JS-bridge thread (not main, not the JS event
    // loop). HTTP calls here block this thread, which is intentional and OK.

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        try {
            Log.d(tag, "downloadAndRunBotguard()")
            val body = postJnn(
                "https://www.youtube.com/api/jnn/v1/Create",
                """["$REQUEST_KEY"]""",
            )
            val challengeJson = ChallengeParser.parseChallengeData(body)
            runOnMain {
                webView?.evaluateJavascript(
                    """
                    (function() {
                      try {
                        var data = $challengeJson;
                        runBotGuard(data).then(function (result) {
                          this.webPoSignalOutput = result.webPoSignalOutput;
                          $JS_INTERFACE.onRunBotguardResult(result.botguardResponse);
                        }, function (error) {
                          $JS_INTERFACE.onJsInitializationError(error + '\n' + (error && error.stack));
                        });
                      } catch (error) {
                        $JS_INTERFACE.onJsInitializationError(error + '\n' + (error && error.stack));
                      }
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        } catch (t: Throwable) {
            onInitError("downloadAndRunBotguard failed: ${t.message}")
        }
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        try {
            Log.d(tag, "onRunBotguardResult, len=${botguardResponse.length}")
            val body = postJnn(
                "https://www.youtube.com/api/jnn/v1/GenerateIT",
                """["$REQUEST_KEY","$botguardResponse"]""",
            )
            val (integrityTokenU8Js, ttlSecs) = ChallengeParser.parseIntegrityTokenData(body)
            // 10-minute safety margin from server-provided ttl.
            integrityExpiresAtMs = System.currentTimeMillis() + (ttlSecs - 600L).coerceAtLeast(60L) * 1000L
            runOnMain {
                webView?.evaluateJavascript(
                    """
                    (function() {
                      this.integrityToken = $integrityTokenU8Js;
                      $JS_INTERFACE.onJsInitializationDone($ttlSecs);
                    })();
                    """.trimIndent(),
                    null,
                )
            }
        } catch (t: Throwable) {
            onInitError("onRunBotguardResult failed: ${t.message}")
        }
    }

    @JavascriptInterface
    fun onJsInitializationDone(ttlSecs: Long) {
        Log.i(tag, "WebPO init complete (ttl=${ttlSecs}s)")
        // Stamp BEFORE completing the deferred: whoever is awaiting init resumes straight into
        // awaitPageSettled(), which must already see a non-zero timestamp or it will wave them through.
        initCompletedAtMs = System.currentTimeMillis()
        initDeferred?.complete(Unit)
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        onInitError("JS init error: $error")
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        try {
            val poToken = u8CsvToUrlSafeBase64(poTokenU8)
            Log.d(tag, "Minted PO token for $identifier (len=${poToken.length})")
            pendingMints.remove(identifier)?.complete(poToken)
        } catch (t: Throwable) {
            pendingMints.remove(identifier)?.completeExceptionally(
                PoTokenException("u8 decode failed for $identifier: ${t.message}"),
            )
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        Log.w(tag, "Mint error for $identifier: $error")
        pendingMints.remove(identifier)?.completeExceptionally(PoTokenException(error))
    }

    /** Result of the in-page att/get mint (sabr_potoken.js → PoTokenBridge). */
    @JavascriptInterface
    fun onSabrPoTokenResult(json: String) {
        val deferred = pendingSabrMint
        pendingSabrMint = null
        if (deferred == null) return
        try {
            val obj = JSONObject(json)
            if (obj.optBoolean("ok", false)) {
                val pot = obj.optString("poToken", "")
                if (pot.isNotEmpty()) {
                    // Report BOTH which endpoint the token is for and what it is bound to. The old
                    // line said "Minted GVS PO token for <videoId>" for every token including the
                    // /player one, and printed a visitorData under the name videoId — see
                    // bindingKind() in sabr_potoken.js.
                    Log.d(
                        tag,
                        "Minted ${obj.optString("purpose", "?")} pot bound to " +
                            "${obj.optString("binding", "?")} = ${obj.optString("identifier")} " +
                            "(len=${pot.length})",
                    )
                    deferred.complete(pot)
                } else {
                    deferred.completeExceptionally(PoTokenException("in-page mint returned empty token"))
                }
            } else {
                deferred.completeExceptionally(
                    PoTokenException("in-page mint failed: ${obj.optString("error")}"),
                )
            }
        } catch (t: Throwable) {
            deferred.completeExceptionally(PoTokenException("in-page mint parse failed: ${t.message}"))
        }
    }

    /**
     * Read the page's own visitorData and remember it.
     *
     * Uses the SHARED extractor asset (visitor_data.js), the same one `sabr_potoken.js` uses for its
     * identity diagnostic — not a Kotlin-side reimplementation. A second copy of the validator could
     * drift from the first, and a disagreement about "what is the page's identity" is precisely the
     * bug class that made the earlier 558-char reading untrustworthy.
     *
     * Never throws and never leaves a waiter hanging: on any failure the deferred completes with "".
     */
    private fun capturePageVisitorData(view: WebView) {
        val deferred = pageVisitorData
        if (deferred.isCompleted) return
        val js = try {
            context.assets.open(VISITOR_DATA_JS).bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.w(tag, "page identity: could not read $VISITOR_DATA_JS (${t.message})")
            deferred.complete("")
            return
        }
        // evaluateJavascript hands back a JSON-encoded value, so "" for an empty string.
        view.evaluateJavascript("$js; window.__ytBestVisitorData();") { raw ->
            val value = try {
                if (raw.isNullOrEmpty() || raw == "null") "" else JSONObject("{\"v\":$raw}").optString("v")
            } catch (t: Throwable) {
                Log.w(tag, "page identity: could not parse result (${t.message})")
                ""
            }
            if (value.isEmpty()) {
                Log.w(tag, "page identity: page offered no valid visitorData")
            } else {
                Log.i(tag, "page identity captured: ${value.take(16)}… (${value.length} chars)")
            }
            deferred.complete(value)
        }
    }

    /**
     * The page's real visitorData, or "" if the page had none or did not answer within [timeoutMs].
     *
     * Callers must treat "" as "carry on with the locally-generated identity" — this is an
     * experiment (SabrSessionIdentity.USE_PAGE_IDENTITY), not a requirement, and it must never be
     * able to stall or break playback.
     */
    suspend fun awaitPageVisitorData(timeoutMs: Long): String =
        try {
            withTimeout(timeoutMs) { pageVisitorData.await() }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Log.w(tag, "page identity: not available within ${timeoutMs}ms — using session identity")
            ""
        }

    // ── HTTP & encoding helpers ─────────────────────────────────────────────

    private fun postJnn(url: String, body: String): String {
        val req = Request.Builder()
            .url(url)
            .addHeader("User-Agent", USER_AGENT)
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json+protobuf")
            .addHeader("x-goog-api-key", GOOGLE_API_KEY)
            .addHeader("x-user-agent", "grpc-web-javascript/0.1")
            .post(body.toRequestBody("application/json+protobuf".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw PoTokenException("HTTP ${resp.code} from $url: ${text.take(200)}")
            }
            return text
        }
    }

    /** Comma-separated u8 bytes ("97,98,99") → URL-safe base64 ("YWJj"). */
    private fun u8CsvToUrlSafeBase64(csv: String): String {
        val bytes = csv.split(',')
            .map { it.toUByte().toByte() }
            .toByteArray()
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    /** Build a `new Uint8Array([…])` JS literal for direct injection. */
    private fun newUint8ArrayJs(bytes: ByteArray): String =
        bytes.joinToString(prefix = "new Uint8Array([", postfix = "])") {
            it.toUByte().toString()
        }

    private fun setSafeBrowsingEnabledSafely(settings: WebSettings, enabled: Boolean) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) return
        try {
            WebSettingsCompat.setSafeBrowsingEnabled(settings, enabled)
        } catch (_: AbstractMethodError) {
            // Known bug on some Android 8/9 WebView versions — ignore.
        }
    }

    /**
     * Turn the WebView Media Integrity API fully off for this WebView, so youtube.com's own page JS
     * (BotGuard) sees it as unsupported rather than present-but-broken. See the call site in
     * [createWebViewIfNeeded] for the full story; this is what actually fixes the
     * "OperationError: Internal Error. Retry with an exponential backoff." crash on devices with no
     * working Play Integrity backend (no Play Store, GMS disabled, GrapheneOS, etc).
     *
     * `WEBVIEW_MEDIA_INTEGRITY_API_DISABLED` is a real default-status constant on
     * [WebViewMediaIntegrityApiStatusConfig] (verified against the actual androidx.webkit:webkit
     * 1.11.0 AAR, not just its docs) — not a per-origin override, so this applies regardless of what
     * origin ends up loaded in this WebView.
     */
    private fun setMediaIntegrityApiStatusDisabledSafely(settings: WebSettings) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEBVIEW_MEDIA_INTEGRITY_API_STATUS)) return
        try {
            val config = WebViewMediaIntegrityApiStatusConfig
                .Builder(WebViewMediaIntegrityApiStatusConfig.WEBVIEW_MEDIA_INTEGRITY_API_DISABLED)
                .build()
            WebSettingsCompat.setWebViewMediaIntegrityApiStatus(settings, config)
        } catch (_: AbstractMethodError) {
            // Mirrors setSafeBrowsingEnabledSafely's guard — some WebView builds implement the
            // feature-check but not the underlying boundary method.
        }
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else Handler(Looper.getMainLooper()).post(action)
    }

    companion object {
        private const val PO_TOKEN_HTML = "po_token.html"
        private const val SABR_POTOKEN_JS = "sabr_potoken.js"
        private const val VISITOR_DATA_JS = "visitor_data.js"
        private const val GVS_MINT_TIMEOUT_MS = 30_000L

        /** Token is for the Innertube `/player` call (`serviceIntegrityDimensions.poToken`). */
        const val PURPOSE_PLAYER = "player"

        /** Token is for the GVS / SABR streaming endpoint (inside the UMP request body). */
        const val PURPOSE_GVS = "gvs"

        /**
         * How long to hold the mint lock after a fresh WebView/BotGuard initialisation, before the
         * first PO token may be minted.
         *
         * `onJsInitializationDone` fires as soon as OUR init script has finished, but the youtube.com
         * page it runs on is still bringing itself up. Run 16's log shows our warmup mint landing 9 ms
         * after "WebPO init complete", and the page's own integrity machinery still going ~2.5 s
         * later:
         *
         * ```
         * 22:56:57.897  WebPO init complete (ttl=43200s)
         * 22:56:58.220  Minted GVS PO token … (len=150)     ← ours
         * 22:57:00.714  PlayCore StandardIntegrity : warmUpIntegrityToken(...)
         * 22:57:00.720  PlayCore StandardIntegrity : Failed to bind to the service.
         * 22:57:00.726  ERROR Uncaught OperationError: Internal Error. Retry with an exponential backoff.
         * ```
         *
         * Across runs 15 and 16, every session whose /player call was signed by that first mint got
         * StreamProtectionStatus=2 (grace, ~60 s of media then a hard stop); every session signed by a
         * later mint on a settled page got status=1. Five for five. This dwell tests whether page
         * readiness is the cause — the competing explanation is that it is simply the FIRST mint of
         * the process that is bad, in which case this will not help and the fix is to re-mint
         * immediately before /player instead.
         *
         * Cost: this runs inside App's background prewarm, so it is normally free — warmup finishes
         * long before the user taps a video. A user who taps within the dwell does wait behind it, but
         * that is still cheaper than the ~9 s grace rebuild that a bad token currently triggers.
         */
        private const val SETTLE_AFTER_INIT_MS = 4_000L
        private const val JS_INTERFACE = "PoTokenBridge"

        // Same constants SmartTube uses. The API key is the WAA-public one.
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}

class PoTokenException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
