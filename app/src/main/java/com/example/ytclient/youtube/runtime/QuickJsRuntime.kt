package com.example.ytclient.youtube.runtime

import android.content.Context
import android.util.Base64
import android.util.Log
import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.binding.function
import com.example.ytclient.core.storage.SessionStore
import com.example.ytclient.core.webpo.WebPoTokenProvider
import com.example.ytclient.sabr.PoTokenBinding
import com.example.ytclient.sabr.SabrSessionIdentity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Owns the embedded QuickJS engine and the loaded `youtube-runtime.js` bundle.
 *
 * Critical invariants:
 *  1. QuickJS is **not** thread-safe. We give the engine our own single-thread
 *     [jsDispatcher] so all internal coroutines stay on one thread; the
 *     [callLock] additionally serialises top-level calls.
 *  2. The bundle exposes a single global object `__yt` with async methods.
 *  3. `fetch` inside the bundle is polyfilled in JS and delegates to
 *     [performFetch] here via the `__nativeFetch` asyncFunction binding.
 *     That binding returns a JS Promise, so the HTTP call runs on
 *     [Dispatchers.IO] without blocking the QuickJS thread.
 */
class QuickJsRuntime(
    private val context: Context,
    private val sessionStore: SessionStore = SessionStore(context),
    private val webPoProvider: WebPoTokenProvider = WebPoTokenProvider(context),
    /**
     * Device region (ISO 3166-1 alpha-2, e.g. "NL") + language (ISO 639-1, e.g. "nl") — see
     * [detectRegion]. Passed to the JS bundle's `__yt.configureRegion()` at the end of
     * [initialize], before any real bridge call can run, so every Innertube session this runtime
     * ever creates picks it up. Null values just leave youtubei.js's own 'US'/'en' defaults alone.
     */
    private val regionLocation: String? = null,
    private val regionLang: String? = null,
    /**
     * English display name of [regionLocation] (e.g. "Netherlands" for "NL") — see
     * [com.example.ytclient.youtube.runtime.RegionInfo.displayName]. Passed through to
     * `configureRegion` alongside location/lang so index.js's News topic query can bias its query
     * TEXT (not just the soft `gl`/`hl` session params) toward the user's country.
     */
    private val regionName: String? = null,
    /**
     * Distinguishes multiple contexts in logs/thread dumps (D1, RUNTIME_PERF_PLAN.md): the app
     * runs one instance for playback-critical calls and one for browse/feed calls, each with its
     * own engine thread and call queue, so the playback path never waits behind feed parsing.
     */
    private val name: String = "quickjs-runtime",
) {

    private val tag = "QuickJsRuntime[$name]"

    // Single-thread executor: QuickJS contexts can only be touched from one thread.
    private val jsExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, name).apply { isDaemon = true }
    }
    private val jsDispatcher: CoroutineDispatcher = jsExecutor.asCoroutineDispatcher()

    private val callLock = Mutex()

    // Serialises initialize(). The single-thread dispatcher alone does not: it serialises THREADS,
    // not coroutines, and initialize() suspends inside qjs.evaluate — so a second caller (App's
    // startup initialize racing a ViewModel's first call) could run while the first was suspended,
    // still see initialized == false, create a second engine and overwrite the first, leaking its
    // native context.
    private val initLock = Mutex()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var quickJs: QuickJs? = null
    @Volatile private var initialized = false

    /** Load and evaluate the bundled runtime. Idempotent, and safe to call concurrently. */
    suspend fun initialize() = initLock.withLock {
        withContext(jsDispatcher) {
            if (initialized) return@withContext
            initializeOnJsThread()
        }
    }

    private suspend fun initializeOnJsThread() {
        Log.i(tag, "Initialising QuickJS runtime…")

        val script = context.assets.open(BUNDLE_ASSET).use { it.readBytes().decodeToString() }
        Log.i(tag, "Loaded $BUNDLE_ASSET (${script.length} chars)")

        // Hand QuickJS our single-thread dispatcher so its internal coroutines
        // stay on the same thread the engine was created on.
        val qjs = QuickJs.create(jsDispatcher)
        quickJs = qjs
        try {
            setUpEngine(qjs, script)
        } catch (t: Throwable) {
            // Don't leave a half-built engine behind: the next initialize() would create another
            // one and this one would never be closed.
            try { qjs.close() } catch (_: Throwable) {}
            quickJs = null
            throw t
        }
        initialized = true
    }

    private suspend fun setUpEngine(qjs: QuickJs, script: String) {
        // Native fetch: returns a JS Promise. Runs the actual HTTP call on
        // Dispatchers.IO so the QuickJS thread is free to interleave other
        // JS work while the network call is in flight.
        qjs.asyncFunction("__nativeFetch") { args ->
            val req = args.getOrNull(0)?.toString() ?: "{}"
            withContext(Dispatchers.IO) { performFetch(req) }
        }

        // ── Player-script eval bridge (nsig / signature decipher) ─────────
        // youtubei.js's Platform `eval` is handed a self-contained script
        // (data.output) that builds and runs YouTube's base.js decipher
        // (nsig + signature). It is MODERN base.js: ES6 `class`, `new.target`,
        // and a URL-constructor-style nsig that needs the `URL`/`URLSearchParams`
        // APIs. QuickJS lacks URL (→ nsig silently no-ops, n unchanged, 403) and
        // Mozilla Rhino can't even parse the ES6 classes. So we run it in the
        // WebView's real Chromium V8 (already warmed for BotGuard, CSP stripped),
        // which executes it natively and correctly. Returns JSON {sig?, n?} for
        // the JS shim to parse. See WebPoTokenProvider.evaluatePlayerScript.
        qjs.asyncFunction("__nativeEvalJs") { args ->
            val code = args.getOrNull(0)?.toString() ?: return@asyncFunction "{}"
            webPoProvider.evaluatePlayerScript(code)
        }

        // Sync log binding — logging is cheap so blocking the JS thread is fine.
        // Route by console level instead of dumping everything at Log.d: getLegacyPlayback's
        // client-probe/pot-mint trail (console.log/info) was previously invisible in any logcat
        // capture that filters below Info (Android Studio's default view included, when a min
        // level is set), and console.warn/error calls (e.g. "gvs pot mint failed") were
        // indistinguishable from routine chatter. 'debug' stays Log.d; everything else is at
        // least Log.i so the JS runtime's own diagnostics show up by default.
        qjs.function("__nativeLog") { args ->
            val level = args.getOrNull(0)?.toString() ?: "log"
            val msg = args.getOrNull(1)?.toString() ?: ""
            val logTag = "$tag/js"
            when (level) {
                "error" -> Log.e(logTag, "[$level] $msg")
                "warn" -> Log.w(logTag, "[$level] $msg")
                "debug" -> Log.d(logTag, "[$level] $msg")
                else -> Log.i(logTag, "[$level] $msg")
            }
            null
        }

        // ── Persistence bridge ───────────────────────────────────────────
        // JS calls these to read/write a single namespaced key. Used by the
        // PO token manager to cache visitorData + poToken across launches.
        qjs.asyncFunction("__nativeStorageGet") { args ->
            val key = args.getOrNull(0)?.toString() ?: return@asyncFunction null
            withContext(Dispatchers.IO) { sessionStore.get(key) }
        }
        qjs.asyncFunction("__nativeStorageSet") { args ->
            val key = args.getOrNull(0)?.toString() ?: return@asyncFunction null
            val value = args.getOrNull(1)?.toString()
            withContext(Dispatchers.IO) {
                if (value == null) sessionStore.remove(key) else sessionStore.put(key, value)
            }
            null
        }

        // ── WebView-backed PO token mint ─────────────────────────────────
        // Replaces the bgutils-js path. The first call here triggers WebView
        // init (~1.5 s); subsequent calls mint a per-identifier token in
        // <100 ms. Identifier is typically the videoId (for SABR-protected
        // playback) or visitorData (for session-level usage).
        qjs.asyncFunction("__nativeMintPoToken") { args ->
            val identifier = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("__nativeMintPoToken: identifier required")
            withContext(Dispatchers.IO) { webPoProvider.generatePoToken(identifier) }
        }

        // Mint a PO token via the in-page att/get attestation (same flow that works for GVS),
        // bound to [identifier] with the session's [visitorData]/[clientVersion] as the challenge
        // context. YouTube now rejects the out-of-page /Create pot on /player itself ("Sign in to
        // confirm you're not a bot" = LOGIN_REQUIRED), so the session/cold-start pot must come from
        // att/get too — binding to visitorData yields the same pot a logged-out browser sends.
        // The youtube.com page's own visitorData, or "" if it has none / didn't answer in time.
        // "" means "carry on with the locally-generated identity" — this must never break playback.
        qjs.asyncFunction("__nativePageVisitorData") {
            withContext(Dispatchers.IO) {
                webPoProvider.awaitPageVisitorData(SabrSessionIdentity.PAGE_IDENTITY_TIMEOUT_MS)
            }
        }

        qjs.asyncFunction("__nativeMintAttestedPoToken") { args ->
            val identifier = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("__nativeMintAttestedPoToken: identifier required")
            val visitorData = args.getOrNull(1)?.toString()?.takeIf { it.isNotEmpty() }
            val clientVersion = args.getOrNull(2)?.toString()?.takeIf { it.isNotEmpty() }
            // The JS side knows which endpoint it is minting for; it must say so, because the
            // identifier alone no longer implies it (under PoTokenBinding.SWAP a /player pot is
            // videoId-bound and a GVS pot is visitorData-bound). Logging only.
            val purpose = args.getOrNull(3)?.toString()?.takeIf { it.isNotEmpty() }
                ?: WebPoTokenProvider.PURPOSE_PLAYER
            withContext(Dispatchers.IO) {
                webPoProvider.generateAttestedPoToken(identifier, purpose, visitorData, clientVersion)
            }
        }

        // Run the bundle. It installs global `__yt`.
        qjs.evaluate<Any?>(script)
        Log.i(tag, "Bundle evaluated, runtime ready.")

        // Push the PO-token binding assignment before anything can create a session. PoTokenBinding
        // is the single source of truth for BOTH halves — this is the Kotlin→JS half of it, and the
        // reason the swap can't drift out of sync between /player and GVS. Same ordering guarantee
        // as configureRegion below: initialize() has not returned, so no bridge call has run yet.
        qjs.evaluate<Any?>(
            "globalThis.__yt.configurePoTokenBinding(${PoTokenBinding.SWAP});",
        )
        // Same ordering guarantee: pushed before any session can exist. Unlike the binding flag this
        // one only takes effect lazily, when the authed session is built and asks the WebView for the
        // page identity — see SabrSessionIdentity for why the anon session is deliberately excluded.
        qjs.evaluate<Any?>(
            "globalThis.__yt.configureSessionIdentity(${SabrSessionIdentity.USE_PAGE_IDENTITY});",
        )

        // Tell the JS side the device's region/language BEFORE returning — i.e. before any real
        // bridge call (search/getHomeFeed/warmup/…) can possibly run, since every one of those
        // calls `initialize()` first and this suspend fun doesn't return until we're done here.
        // That guarantees it lands before createAnonSession()/createAuthedSession()'s first
        // Innertube.create(), which is the only place these values are actually read. Plain sync
        // JS call (no `await` needed — configureRegion does no async work), values are JSON-quoted
        // so they can't break out of the evaluated expression.
        if (regionLocation != null || regionLang != null || regionName != null) {
            val loc = JSONObject.quote(regionLocation ?: "")
            val lng = JSONObject.quote(regionLang ?: "")
            val name = JSONObject.quote(regionName ?: "")
            qjs.evaluate<Any?>("globalThis.__yt.configureRegion($loc, $lng, $name);")
            Log.i(tag, "Configured region: location=$regionLocation, lang=$regionLang, name=$regionName")
        }
    }

    /**
     * Call a top-level async JS function `__yt.<name>(...args)` and return the
     * JSON-encoded result as a String. JS-side methods MUST return a
     * JSON-serialisable object (the Kotlin layer parses it with kotlinx.serialization).
     *
     * Implementation note: dokar3/quickjs-kt's `evaluate()` honours the `await`
     * keyword inside the script but does NOT auto-await a script whose last
     * expression happens to evaluate to a Promise. So we put `await` at the
     * top level — the script's value then IS the resolved JSON string.
     */
    suspend fun callAsync(name: String, vararg args: Any?): String = callLock.withLock {
        withContext(jsDispatcher) {
            val qjs = quickJs ?: error("QuickJsRuntime not initialised")
            val argsJson = JSONArray().apply {
                args.forEach { put(it) }
            }.toString()
            val expression = """
                await (async () => {
                  const fn = globalThis.__yt && globalThis.__yt['$name'];
                  if (typeof fn !== 'function') throw new Error('Unknown __yt method: $name');
                  const res = await fn.apply(globalThis.__yt, $argsJson);
                  return JSON.stringify(res);
                })()
            """.trimIndent()
            val result = qjs.evaluate<Any?>(expression)
            val asString = result?.toString() ?: "null"
            Log.d(
                tag,
                "callAsync($name) → ${result?.javaClass?.simpleName ?: "null"}, " +
                    "len=${asString.length}, head=${asString.take(80)}",
            )
            asString
        }
    }

    /**
     * Terminal: closes the engine ON its own thread (the one-thread rule at the top of this file
     * applies to close too; this used to run on the caller's thread, i.e. main) and then stops that
     * thread. Calls after this fail.
     */
    fun close() {
        try {
            jsExecutor.execute {
                try { quickJs?.close() } catch (_: Throwable) {}
                quickJs = null
                initialized = false
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // Already closed.
        }
        jsExecutor.shutdown()
    }

    // ─── Native fetch bridge ─────────────────────────────────────────────────
    //
    // The JS polyfill calls __nativeFetch(JSON.stringify({method, url, headers,
    // body?, bodyB64?})) and expects back JSON.stringify({status, statusText,
    // headers, body? | bodyB64?}).
    //
    // Binary handling: SABR requests carry a protobuf body and SABR/UMP
    // responses are binary. Those travel as base64 (`bodyB64`) so they survive
    // the JSON bridge without TextDecoder corruption. Text requests/responses
    // (search, /player JSON) stay as plain strings for speed.
    private fun performFetch(requestJson: String): String {
        return try {
            val req = JSONObject(requestJson)
            val method = req.optString("method", "GET").uppercase()
            val url = req.getString("url")
            val headers = req.optJSONObject("headers") ?: JSONObject()
            val bodyStr = if (req.isNull("body")) null else req.optString("body", null)
            val bodyB64 = if (req.isNull("bodyB64")) null else req.optString("bodyB64", null)

            val builder = Request.Builder().url(url)
            // Track headers the caller set so we know which defaults to apply.
            // Chrome always sends Origin + Referer on cross-origin XHR/fetch
            // calls; OkHttp doesn't. googlevideo's WAF treats requests
            // missing those signals as suspect.
            var hasUserAgent = false
            var hasOrigin = false
            var hasReferer = false
            headers.keys().forEach { key ->
                builder.addHeader(key, headers.getString(key))
                when {
                    key.equals("User-Agent", ignoreCase = true) -> hasUserAgent = true
                    key.equals("Origin", ignoreCase = true) -> hasOrigin = true
                    key.equals("Referer", ignoreCase = true) -> hasReferer = true
                }
            }
            if (!hasUserAgent) builder.addHeader("User-Agent", DEFAULT_BROWSER_UA)
            // Origin/Referer only for the Innertube (youtube.com) calls — that's
            // what a browser would send and youtubei.js expects. For googlevideo
            // we mirror yt-dlp's native client, which sends only a User-Agent
            // (no Origin/Referer). Adding them there could trip a server-side
            // cross-origin check, so we deliberately omit them.
            if (url.contains("youtube.com") && !url.contains("googlevideo.com")) {
                if (!hasOrigin)  builder.addHeader("Origin",  "https://www.youtube.com")
                if (!hasReferer) builder.addHeader("Referer", "https://www.youtube.com/")
            }
            // Diagnostic: log final outgoing headers for googlevideo.
            if (url.contains("googlevideo.com")) {
                val req2 = builder.build()
                Log.d(
                    tag,
                    "googlevideo headers: " +
                        req2.headers.toMultimap().entries
                            .joinToString { "${it.key}=${it.value.first().take(80)}" },
                )
            }
            // Request body: prefer the binary (base64) form when present.
            val mt = headers.optString("content-type", "application/json").toMediaTypeOrNull()
            val rb = when {
                bodyB64 != null -> Base64.decode(bodyB64, Base64.DEFAULT).toRequestBody(mt)
                bodyStr != null -> bodyStr.toRequestBody(mt)
                else -> null
            }
            when (method) {
                "GET", "HEAD" -> builder.method(method, null)
                else -> builder.method(method, rb ?: ByteArray(0).toRequestBody(null))
            }

            http.newCall(builder.build()).execute().use { resp ->
                val respHeaders = JSONObject()
                resp.headers.forEach { (k, v) -> respHeaders.put(k.lowercase(), v) }
                val contentType = resp.header("Content-Type")?.lowercase() ?: ""
                // Treat googlevideo media + non-text content types as binary →
                // return base64. Everything else (JSON/HTML/text) as a string.
                val isBinary = url.contains("googlevideo.com") ||
                    contentType.contains("application/vnd.yt-ump") ||
                    contentType.startsWith("video/") ||
                    contentType.startsWith("audio/") ||
                    contentType.contains("octet-stream") ||
                    contentType.contains("protobuf")
                JSONObject().apply {
                    put("status", resp.code)
                    put("statusText", resp.message)
                    put("headers", respHeaders)
                    if (isBinary) {
                        val bytes = resp.body?.bytes() ?: ByteArray(0)
                        put("bodyB64", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    } else {
                        put("body", resp.body?.string() ?: "")
                    }
                }.toString()
            }
        } catch (t: Throwable) {
            Log.w(tag, "fetch failed: ${t.message}")
            JSONObject().apply {
                put("status", 0)
                put("statusText", t.message ?: "fetch error")
                put("headers", JSONObject())
                put("body", "")
                put("error", t.message ?: "fetch error")
            }.toString()
        }
    }

    companion object {
        const val BUNDLE_ASSET = "youtube-runtime.js"

        /**
         * Default UA attached to outbound fetch requests when youtubei.js
         * didn't set one. Must match (or at least be plausibly consistent
         * with) what Innertube claims as `context.client.userAgent` — Google's
         * edge servers cross-check this against the HTTP User-Agent header.
         *
         * Keep this in sync with [WebPoTokenProvider]'s USER_AGENT.
         */
        private const val DEFAULT_BROWSER_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Safari/537.36"
    }
}
