// Entry point for the QuickJS runtime bundle.
//
// 1. Install browser polyfills (console/Event/URL/Intl/Headers/window/...).
// 2. Inject those implementations into youtubei.js via Platform.load() — this
//    is the supported extension point. We are NOT relying on youtubei.js
//    picking up our globalThis.fetch implicitly.
// 3. Bootstrap an Innertube session WITHOUT a PO token (just to obtain
//    visitorData). Search uses this session as-is.
// 4. PoTokenManager (lazy, on first playback need or explicit refresh) mints
//    a token via bgutils-js + persists it via __nativeStorage.
// 5. Re-create the Innertube session with the PO token once we have one.
// 6. Expose async methods on globalThis.__yt for Kotlin to invoke.
import './polyfills.js';
import { Innertube, Platform } from 'youtubei.js';
import { sha1Hash } from './sha1.js';
import {
  getOrMintPoToken,
  invalidatePoToken,
  bindingIdFor,
  poTokenSnapshot,
} from './poTokenManager.js';

// ── Inject our implementations into youtubei.js ────────────────────────────
class NullCache {
  async get(_key) { return undefined; }
  async set(_key, _value) {}
  async remove(_key) {}
}

Platform.load({
  runtime: 'browser',
  server: false,
  Cache: NullCache,
  sha1Hash,
  uuidv4: () => globalThis.crypto.randomUUID(),
  // youtubei.js calls eval(BuildScriptResult, env) — NOT eval(string). `data.output` is a
  // self-contained function-body script (it builds `exportedVars` with the n/sig decipher fn using
  // globalThis/window/document fallbacks) and ends with `return process(n,sp,s)` returning
  // { n?, sig? }. The n/sp/sig values are baked into data.output by youtubei's getNsigProcessorFn.
  //
  // We run that script in the WebView's real Chromium V8 (native bridge __nativeEvalJs), not
  // QuickJS. The script is MODERN base.js (ES6 `class`, `new.target`, and a URL-constructor nsig
  // needing `URL`/`URLSearchParams`): QuickJS lacks `URL` so it silently no-ops the transform
  // (n-decipher result=unchanged → 403), and Rhino can't even parse ES6 classes. A real browser
  // engine runs it natively. The bridge returns JSON of {sig, n}.
  eval: async (data, _env) => {
    let code = (data && typeof data === 'object' && typeof data.output === 'string')
      ? data.output
      : String(data);

    // ── NSIG PROCESSOR FIX ──────────────────────────────────────────────────
    // youtubei.js's getNsigProcessorFn applies the transform by calling EVERY non-blacklisted
    // prototype method of the object `exportedVars.nsigFunction(url,sp,s)` returns, then reading
    // `.get('n')`. Diagnostic on base.js 69e2a55d proved the transform is ALREADY applied during
    // construction — `get('n')` immediately returns the correct deciphered n. The processor then
    // calls the extra prototype method `zn()` with no args, which REVERTS the transform → the
    // input n comes back → serverAbrStreamingUrl 403. (protoMethods: constructor,set,get,zn,clone;
    // nBefore=3X0DxvulW4p41Uh → nGetBefore=xC5HrN4MXPOiPw, i.e. already transformed.)
    //
    // Fix: replace youtubei.js's appended `function process(){…}; return process(…)` with our own
    // processor that calls nsigFunction once and reads the params directly — NO method iteration.
    // This stays engine-agnostic (runs in the WebView) and needs no base.js regex extraction,
    // since youtubei.js's AST extraction of nsigFunction itself is correct.
    const fnIdx = code.lastIndexOf('function process(');
    const argsMatch = fnIdx !== -1
      ? code.slice(fnIdx).match(/return process\(\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*,\s*"((?:[^"\\]|\\.)*)"\s*\)/)
      : null;
    if (code.indexOf('nsigFunction') !== -1 && fnIdx !== -1 && argsMatch) {
      const nIn = argsMatch[1], spIn = argsMatch[2], sIn = argsMatch[3];
      const fixedProcessor = '\nfunction process(n, sp, s) {'
        + '\n  var mockStreamingURL = "https://ytjs.googlevideo.com/videoplayback?expire=1234567890&n=" + encodeURIComponent(n);'
        + '\n  var fn = exportedVars.nsigFunction || (function(){ throw new Error("No n/sig decipher function extracted"); });'
        + '\n  var urlCtor = fn(mockStreamingURL, sp, s);'
        // The transform is applied during construction; read the params straight off the returned
        // URL object. Do NOT iterate/call the extra prototype methods (e.g. zn()) — that reverts it.
        + '\n  var sigResult = sp ? urlCtor.get(sp) : undefined;'
        + '\n  var nResult = urlCtor.get("n");'
        + '\n  return {'
        + '\n    sig: sigResult ? decodeURIComponent(sigResult) : undefined,'
        + '\n    n: nResult ? decodeURIComponent(nResult) : undefined'
        + '\n  };'
        + '\n}'
        + '\nreturn process(' + JSON.stringify(nIn) + ', ' + JSON.stringify(spIn) + ', ' + JSON.stringify(sIn) + ');';
      code = code.slice(0, fnIdx) + fixedProcessor;
    }
    // ── END NSIG PROCESSOR FIX ──────────────────────────────────────────────

    const json = await globalThis.__nativeEvalJs(code);
    try {
      return JSON.parse(json);
    } catch (e) {
      console.log('[__yt] player-script eval returned non-JSON: ' + String(json).slice(0, 120));
      return {};
    }
  },
  fetch: globalThis.fetch,
  Request: globalThis.Request,
  Response: globalThis.Response,
  Headers: globalThis.Headers,
  FormData: globalThis.FormData,
  File: globalThis.File,
  ReadableStream: globalThis.ReadableStream,
  CustomEvent: globalThis.CustomEvent,
});
console.log('[__yt] Platform shim installed.');

// ── Innertube sessions (anonymous + authenticated) ──────────────────────────
//
// We hold up to two Innertube instances:
//  • `anonSessionPromise` — no PO token. Used for search and any other call
//    that doesn't require attestation. Always available.
//  • `authedSessionPromise` — with PO token. Lazily created on first call that
//    needs it (getVideoInfo, createPlaybackSession in later milestones).
let anonSessionPromise = null;
let authedSessionPromise = null;
// Which identity the CURRENT authed session's /player pot is bound to. When the swap is on this is
// a videoId, so opening a different video must rebuild the session; when off it is the visitorData
// and one session serves everything. Compared in createAuthedSession.
let authedSessionBindingId = null;
// Mirror of PoTokenBinding.SWAP, pushed in by configurePoTokenBinding(). See that method.
let swapPoTokenBindings = false;
// Mirror of SabrSessionIdentity.USE_PAGE_IDENTITY, pushed in by configureSessionIdentity().
let usePageIdentity = false;

// Device region/language, set once by the Kotlin side (RegionDetector.kt) via configureRegion()
// before the first session is created — see the call site right after the bundle is evaluated in
// QuickJsRuntime.initialize(). Innertube.create() otherwise hardcodes 'US'/'en' for every session,
// which is why News/Movies topic-blend results used to skew toward whatever's globally popular in
// the US feed instead of anything actually relevant to the user's own region. null until configured
// (or if the Kotlin side never calls in, e.g. an older cached bundle) — regionOptions() below then
// omits the fields entirely so youtubei.js falls back to its own 'US'/'en' defaults, same as before.
let regionLocation = null;
let regionLang = null;
// English display name of regionLocation (e.g. "Netherlands" for "NL"), also set by
// configureRegion(). Not used by Innertube.create() at all — gl/hl are the only session-level
// region params it understands — this exists purely so homeTopicQuery() can fold it into the
// News query TEXT below, since gl/hl alone is too soft a signal to beat globally popular channels
// on a generic one-word "News" query. null under the same conditions regionLocation/regionLang are.
let regionName = null;

// Only include location/lang in Innertube.create() when we actually have them — passing explicit
// `undefined` for either would override youtubei.js's own default with a literal undefined instead
// of just falling through to it.
function regionOptions() {
  const opts = {};
  if (regionLocation) opts.location = regionLocation;
  if (regionLang) opts.lang = regionLang;
  return opts;
}

/**
 * Undo youtubei.js's percent-encoding of the locally generated visitorData.
 *
 * With `generate_session_locally: true`, youtubei.js builds the visitorData itself via:
 *
 *     encodeVisitorData(id, ts) =>
 *       encodeURIComponent(u8ToBase64(...).replace(/\+/g,'-').replace(/\//g,'_'))
 *
 * The base64url replaces already handle `+` and `/`, so the ONLY thing encodeURIComponent can
 * still touch is the `=` padding — which it turns into `%3D`. The value that then travels as our
 * client identity is `…Bg%3D%3D` instead of `…Bg==`.
 *
 * That is not cosmetic. It is the identity string in THREE places at once:
 *   1. `context.client.visitorData` in every Innertube JSON body,
 *   2. the `X-Goog-Visitor-Id` request header, and
 *   3. the value handed to the WebView when minting the GVS/SABR PO token.
 *
 * YouTube cannot base64-decode `%3D%3D`, so it discards our identity and mints a brand-new
 * visitorData in `responseContext` on EVERY call — visible in the logs as a different
 * visitorData coming back from each /player request than the one we sent. The knock-on effect is
 * the one that actually breaks playback: the BotGuard attestation is performed under a session
 * identity the server never accepted, so the resulting PO token does not match the streaming
 * session the same /player response set up. GVS answers that with an opening grace burst and then
 * StreamProtectionStatus=3 — the ~60s cliff.
 *
 * Decoding once here fixes all three consumers, since the /player session, the SABR DTO and the
 * WebView mint all read this same field. Idempotent: a value with no `%` is returned untouched.
 */
function normaliseVisitorData(it) {
  try {
    const client = it && it.session && it.session.context && it.session.context.client;
    if (!client) return;
    const raw = String(client.visitorData || '');
    if (!raw || raw.indexOf('%') === -1) return;
    const decoded = decodeURIComponent(raw);
    // ⚠️ Only adopt the decoded form if it is still safe to put in a header.
    //
    // This function exists because youtubei.js percent-encodes the "=" padding, which YouTube cannot
    // decode. But percent-decoding a REAL server-issued visitorData turns %XX sequences back into
    // raw bytes, and on 2026-08-02 that produced control characters in X-Goog-Visitor-Id, which made
    // every fetch() throw "Failed to fetch". That is one of the two regressions the page-identity
    // experiment has to avoid (see SabrSessionIdentity), and the cheapest way to avoid it is to
    // refuse the decode rather than to reason about which values are safe.
    if (decoded && /[^\x20-\x7E]/.test(decoded)) {
      console.log('[__yt] visitorData left percent-encoded — decoding it would put non-ASCII bytes'
        + ' in X-Goog-Visitor-Id (' + raw.length + ' chars)');
      return;
    }
    if (decoded && decoded !== raw) {
      client.visitorData = decoded;
      console.log('[__yt] visitorData un-percent-encoded (' + raw.length + '→'
        + decoded.length + ' chars) — youtubei.js encodeVisitorData() URL-encodes the "="'
        + ' padding, which YouTube cannot decode');
    }
  } catch (e) {
    console.log('[__yt] visitorData normalise failed: ' + (e && e.message));
  }
}

// ── Player cache ────────────────────────────────────────────────────────────
// youtubei.js already has the hard part: Player.create() resolves the CURRENT player id from
// /iframe_api (one small fetch), looks it up in whatever cache we hand it, and only downloads +
// parses base.js on a miss. What it ships is a browser IndexedDB cache, which does not exist in
// QuickJS, so without one of these every Innertube.create({retrieve_player: true}) re-downloads
// 1.5 MB of base.js and re-runs JsAnalyzer/JsExtractor over it.
//
// That cost is not theoretical: in run 16 the grace-session rebuild spent 7.6 of its 9 seconds
// re-parsing the SAME player (ca042962) it already had — base.js came back at 22:57:25.342 and
// nothing else happened until 22:57:32.977.
//
// Staleness is handled for us and is worth being explicit about, because getting it wrong means
// silently deciphering signatures with an outdated player:
//   * the cache KEY is the player id, and Player.create re-resolves that id on every call, so a
//     player rotation is a cache miss, not a stale hit;
//   * youtubei.js stamps its own library version into the blob and rejects mismatches;
//   * the signature timestamp travels inside the blob, so a restored player carries the same
//     signatureTimestamp it was extracted with.
// We add nothing to that logic — deliberately. This is a store, not a policy.
const PLAYER_CACHE_PREFIX = 'yt_player_v1:';
// Persist only small blobs. __nativeStorage is backed by a Preferences DataStore (SessionStore.kt),
// which holds the entire preference file in memory and re-serialises + fsyncs ALL of it on every
// edit — so a large value here would tax every unrelated write (the PO token JSON, the preferred
// client, …), not just this one. The extracted player is normally tens of KB, well under this. If a
// real blob ever exceeds it, the right fix is a separate file-backed store, NOT a bigger cap; until
// then an oversized player stays memory-only, which still covers the case this exists for
// (rebuilds within one process).
const PLAYER_CACHE_MAX_PERSIST_BYTES = 512 * 1024;
// Player ids rotate on YouTube's schedule (roughly daily), so the live set is 1. Keep a couple for
// the rotation boundary and evict oldest-first; this bound is what stops a long-lived process from
// accumulating parsed players.
const PLAYER_CACHE_MAX_MEMORY_ENTRIES = 3;
const playerMemoryCache = new Map();

function rememberPlayerInMemory(playerId, buffer) {
  // Re-insert to refresh insertion order, then trim oldest-first (Map preserves insertion order).
  playerMemoryCache.delete(playerId);
  playerMemoryCache.set(playerId, buffer);
  while (playerMemoryCache.size > PLAYER_CACHE_MAX_MEMORY_ENTRIES) {
    playerMemoryCache.delete(playerMemoryCache.keys().next().value);
  }
}

// Every method swallows its own errors and degrades to a miss: a broken cache must cost a re-parse,
// never a failed session. Player.create() treats `undefined` from get() as "not cached".
const playerCache = {
  async get(playerId) {
    const inMemory = playerMemoryCache.get(playerId);
    if (inMemory) return inMemory;
    try {
      const b64 = await globalThis.__nativeStorageGet(PLAYER_CACHE_PREFIX + playerId);
      if (!b64) return undefined;
      const u8 = globalThis.__uint8FromBase64(b64);
      // Hand back a copy's buffer; youtubei.js wraps it in `new Uint8Array(buffer)`.
      const buffer = u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength);
      rememberPlayerInMemory(playerId, buffer);
      console.log(`[__yt] player cache: restored ${playerId} from disk (${u8.length}b)`);
      return buffer;
    } catch (e) {
      console.log('[__yt] player cache: disk read failed — ' + ((e && e.message) || e));
      return undefined;
    }
  },
  async set(playerId, value) {
    try {
      const u8 = value instanceof Uint8Array
        ? value
        : (ArrayBuffer.isView(value) ? new Uint8Array(value.buffer, value.byteOffset, value.byteLength)
          : new Uint8Array(value));
      const buffer = u8.buffer.slice(u8.byteOffset, u8.byteOffset + u8.byteLength);
      rememberPlayerInMemory(playerId, buffer);
      if (u8.length > PLAYER_CACHE_MAX_PERSIST_BYTES) {
        console.log(`[__yt] player cache: ${playerId} is ${u8.length}b, keeping in memory only`);
        return;
      }
      await globalThis.__nativeStorageSet(
        PLAYER_CACHE_PREFIX + playerId, globalThis.__base64FromUint8(u8));
      console.log(`[__yt] player cache: stored ${playerId} (${u8.length}b)`);
    } catch (e) {
      console.log('[__yt] player cache: store failed — ' + ((e && e.message) || e));
    }
  },
  async remove(playerId) {
    playerMemoryCache.delete(playerId);
    try {
      await globalThis.__nativeStorageSet(PLAYER_CACHE_PREFIX + playerId, null);
    } catch (_) { /* best effort */ }
  },
};

function createAnonSession() {
  if (anonSessionPromise) return anonSessionPromise;
  console.log(`[__yt] Creating anonymous Innertube session… region=${regionLocation || '(default)'}/${regionLang || '(default)'}`);
  anonSessionPromise = Innertube.create({
    ...regionOptions(),
    // Pin the context userAgent. youtubei.js defaults this to getRandomUserAgent('desktop'), which
    // draws from a 27-entry pool per session — including Opera ("…Chrome/137… OPR/121"), Safari
    // ("…Version/16.6.1 Safari/605.1.15", no Chrome token at all) and several macOS strings. It does
    // NOT vary browserName/browserVersion/osName alongside it, so ~1 draw in 5 produced a context
    // claiming browserName=Chrome, browserVersion=125, osName=Windows while the UA said Opera or
    // Safari or macOS. Run 15 saw two Opera draws answer StreamProtectionStatus=2 (grace) and one
    // Chrome draw answer status=1, but that is n=3 and confounded with a token refresh — this is
    // pinned to remove a random variable from the next run's diagnosis, not because the UA is a
    // proven cause. It cannot be the whole story: non-Chrome draws are ~22% of the pool while grace
    // has been the majority outcome across runs 9-15.
    user_agent: DESKTOP_UA,
    retrieve_player: false,
    // MUST be false. With `true`, youtubei.js does NOT ask YouTube for an identity — it invents one:
    //     visitor_data: visitor_data || encodeVisitorData(generateRandomString(11), now)
    // i.e. a random 11-character id that YouTube has never issued and has no record of. The server
    // cannot resolve it, so it silently substitutes its own and returns THAT in responseContext —
    // visible in every log as a different visitorData coming back than the one we sent.
    //
    // Fixing the percent-encoding (see normaliseVisitorData) made the value well-formed but did not
    // make it REAL, and prot=3 came back. The consequence is specific to playback: the GVS PO token
    // is attested in the WebView against this fabricated identity, while the stream is issued to
    // whatever session the /player call actually resolved to. Those don't match, so GVS grants the
    // opening grace burst (~60s of media) and then answers StreamProtectionStatus=3. Re-minting can
    // never fix it — every mint reproduces the same fabricated identity.
    //
    // ⚠️ REVERTED to `true` on 2026-08-02. Setting it to `false` (GET /sw.js_data, real server-issued
    // visitorData) was tried and made things WORSE — the stream stopped starting at all rather than
    // dying at 60s. Two regressions came with it, neither yet isolated:
    //
    //   1. clientVersion split. /sw.js_data returns its own clientVersion (2.20260731.00.00), so the
    //      Innertube session used that while the SABR DTO and the GVS pot mint kept the bundled
    //      2.20260206.01.00. The /player call and the token that is supposed to authorise its stream
    //      were then made under two different client versions.
    //   2. The real visitorData is ~556 chars, not ~28. That long form propagates into the WebView
    //      and into the X-Goog-Visitor-Id header — the exact value readVisitorData() in
    //      sabr_potoken.js already warns about ("a 558-char garbage value whose control chars made
    //      the X-Goog-Visitor-Id header invalid"). It is not garbage, but nothing downstream was
    //      sized or tested for it.
    //
    // And it did not even fix the target problem: the improved identity diagnostic showed
    // `session=visitorData-H… page=visitorData-I… MATCH=false` — still mismatched, because the WebView
    // holds its OWN session, independent of whatever /sw.js_data hands the QuickJS runtime.
    //
    // That is the actual lesson: the identity has to come FROM THE WEBVIEW, since that is where the
    // BotGuard attestation happens. Fetching a *different* real identity just swaps one mismatch for
    // another. See BACKLOG.md "Adopt the WebView's visitorData as the session identity".
    generate_session_locally: true,
    // Skip the POST /v1/config round-trip — it's config-experiment data we never read (we drive
    // client selection ourselves via CLIENT_PREFERENCE probing). Without this, EVERY cold start
    // paid a blocking network call before the first search/home-feed request could even go out.
    retrieve_innertube_config: false,
  }).then((it) => {
    normaliseVisitorData(it);
    console.log('[__yt] Anonymous session ready. visitorData=' +
      String(it.session.context.client.visitorData || '').slice(0, 16) + '…');
    return it;
  }).catch((err) => {
    anonSessionPromise = null;
    throw err;
  });
  return anonSessionPromise;
}

/**
 * WHICH VISITOR DOES PLAYBACK RUN AS?
 *
 * Factored out of createAuthedSession in run 23, because two other places needed the same answer and
 * were quietly getting a different one. Everything that mints, attests or streams has to agree on
 * this single value; see SabrSessionIdentity for why.
 *
 * Returns the page's real identity when we have it and USE_PAGE_IDENTITY is on, else the anon
 * session's locally-invented one. Cheap to call repeatedly: the page value is a resolved
 * CompletableDeferred after the first capture.
 *
 * Deliberately silent — createAuthedSession owns the adoption log line, so calling this from the
 * mint path does not triple it.
 */
async function resolveSessionVisitorData() {
  const anon = await createAnonSession();
  const localVisitorData = anon.session.context.client.visitorData;
  if (!usePageIdentity) return { visitorData: localVisitorData, localVisitorData, fromPage: false };
  let pageVisitorData = '';
  try {
    pageVisitorData = (await globalThis.__nativePageVisitorData()) || '';
  } catch (e) {
    console.warn('[__yt] page identity lookup failed: ' + ((e && e.message) || e));
  }
  return pageVisitorData
    ? { visitorData: pageVisitorData, localVisitorData, fromPage: true }
    : { visitorData: localVisitorData, localVisitorData, fromPage: false };
}

/**
 * Mint (or fetch from cache) a PO token for [identifier].
 * If [identifier] is null we default to the session visitorData — appropriate
 * for the session-level Innertube auth path (player request, etc). For
 * SABR-protected playback the caller MUST pass the videoId.
 *
 * ── RUN 23: THE OTHER HALF OF THE IDENTITY MISMATCH ─────────────────────────────────────────────
 * This used to read visitorData off the ANON session unconditionally, which was correct only while
 * the anon and authed sessions shared an identity. Once the authed session adopted the page's
 * identity, run 23 showed the /player mint splitting down the middle:
 *
 *     [po] minting player pot (att/get) bound to visitorData-W   ← page identity
 *     [sabr-pot] context ok visitorLen=28                               ← LOCAL identity
 *     [sabr-pot] identity: session=visitorData-X page=visitorData-W MATCH=false
 *
 * The token BOUND to the page visitor while att/get declared the local one in its body and in
 * X-Goog-Visitor-Id. Exactly the mismatch SabrSessionIdentity exists to close, surviving in the one
 * mint that authorises /player.
 *
 * It also cost a third attestation. With no identifier (poTokenInfo's cold read) the default
 * identifier was the anon visitorData, so the cache key was `player:<local>` while warmup had
 * stored `player:<page>` — a guaranteed miss, and since sabr_potoken.js invalidates its attestation
 * whenever ctx.visitorData changes, that miss tore down the page-identity BotGuard VM and re-ran
 * att/get for a token nothing plays under. Run 23 attested three times: page → local → page → local.
 */
async function ensurePoToken(identifier, forceRefresh = false, purpose = 'player') {
  const it = await createAnonSession();
  const clientVersion = (it.session && it.session.context && it.session.context.client || {})
    .clientVersion;
  // The identity the STREAM will run under — not merely the one the anon session happens to hold.
  const { visitorData } = await resolveSessionVisitorData();
  // null identifier = the session/cold-start pot, which binds to visitorData.
  if (!identifier) identifier = visitorData;
  return getOrMintPoToken(identifier, visitorData, clientVersion, forceRefresh, purpose);
}

/**
 * The authed session is the one that issues /player, so its `po_token` IS the /player pot and its
 * binding is half of the PoTokenBinding experiment.
 *
 * [videoId] is only consulted when the swap is on, where /player wants a content-bound token. Pass
 * it from any call site that is about to ask for a specific video; call sites with no video in hand
 * (warmup, diagnostics) fall back to the visitorData binding, and the next per-video call rebuilds.
 */
async function createAuthedSession(forceRefresh = false, videoId = null) {
  // THE IDENTITY DECISION (resolveSessionVisitorData creates/awaits the anon session for us).
  // Default: the anon session's locally-invented visitorData, which is what
  // has always produced `MATCH=false` in the mint log — we attest as the page's visitor and stream
  // as a visitor YouTube never issued. With SabrSessionIdentity.USE_PAGE_IDENTITY on, the authed
  // session adopts the page's real identity so both halves agree.
  //
  // Costs nothing in latency: the authed session's own PO-token mint already blocks on the WebView,
  // so by the time we get here the page identity is either long since captured or never coming.
  // Falls back silently when the page has none — this is an experiment, not a requirement.
  const { visitorData, localVisitorData, fromPage } = await resolveSessionVisitorData();
  if (usePageIdentity) {
    if (fromPage) {
      console.log('[__yt] authed session adopting PAGE identity ('
        + visitorData.slice(0, 16) + '…, ' + visitorData.length + ' chars) instead of the'
        + ' local one (' + localVisitorData.slice(0, 16) + '…) — attestation and stream now agree');
    } else {
      console.log('[__yt] page identity unavailable — authed session keeps the local one');
    }
  }
  // NOTE: clientVersion is deliberately NOT taken from the page. Mixing the page's clientVersion
  // with our bundled one is regression (1) from the 2026-08-02 /sw.js_data attempt: the /player call
  // and the token meant to authorise its stream ended up on two different client versions.
  const bindingId = bindingIdFor('player', swapPoTokenBindings, videoId, visitorData);

  if (forceRefresh) {
    authedSessionPromise = null;
    await invalidatePoToken();
  } else if (authedSessionPromise && authedSessionBindingId !== bindingId) {
    // Swap is on and we've moved to a different video: the cached session carries a pot bound to
    // the PREVIOUS videoId, which /player would reject for this one. Rebuild (cheap — the player
    // cache means no base.js fetch), but do NOT invalidate the token cache: the old video's pot is
    // still valid for the old video and may be wanted again.
    console.log(`[__yt] /player pot binding changed (${String(authedSessionBindingId).slice(0, 12)}…`
      + ` → ${String(bindingId).slice(0, 12)}…) — rebuilding authed session`);
    authedSessionPromise = null;
  }
  if (authedSessionPromise) return authedSessionPromise;
  authedSessionBindingId = bindingId;

  authedSessionPromise = (async () => {
    const { poToken } = await ensurePoToken(bindingId, false, 'player');
    console.log('[__yt] Creating authed Innertube session with PO token…');
    // normaliseVisitorData again on the result: we pass the already-decoded value in below, but
    // this session is the one that issues /player, so guarantee the invariant rather than assume it.
    return Innertube.create({
      ...regionOptions(),
      // Same pin as createAnonSession — and it matters more here, because THIS is the session that
      // issues /player. A fresh Innertube.create() re-rolls the random UA, so before this pin the
      // watchdog's forceFresh path could silently hand /player a different browser identity than the
      // one the cold-start session used. That is exactly what happened in run 15.
      user_agent: DESKTOP_UA,
      po_token: poToken,
      visitor_data: visitorData,
      retrieve_player: true,
      // The ONLY session that retrieves a player, so the only one that needs the cache. Without it
      // every rebuild of this session — notably the grace bail-out's forceFresh — pays a full
      // base.js download + extraction. See playerCache above.
      cache: playerCache,
      // ⚠️ MUST stay false. youtubei.js feeds the SAME `cache` object to both Player.create and
      // Session.getSessionData, and getSessionData's `enable_session_cache` parameter defaults to
      // TRUE — so adding a cache silently opts into session caching as well:
      //
      //     if (cache && enable_session_cache) { … Found session data in cache. }
      //
      // That would restore the previous session's context/api key wholesale and make forceFresh a
      // no-op, defeating the one mechanism that has actually converted grace into status=1. We want
      // the player cached and the session emphatically not.
      enable_session_cache: false,
      // Same reasoning as the anon session: reuse the visitorData we already have instead of
      // re-deriving it from a GET /sw.js_data call, and skip the config round-trip. That removes
      // TWO blocking network calls that used to run before Player.create() even started fetching
      // base.js — a real chunk of the "~7s to first play" cold-start cost.
      generate_session_locally: true,
      retrieve_innertube_config: false,
    }).then((authed) => {
      normaliseVisitorData(authed);
      return authed;
    });
  })().catch((err) => {
    // Don't cache a rejected promise — that would poison every later call
    // until process restart. Clear it so the next call retries cleanly.
    authedSessionPromise = null;
    throw err;
  });
  return authedSessionPromise;
}

// ── Search-result shape helpers (unchanged from M1) ─────────────────────────
// YouTube commonly returns channel/avatar images (yt3.ggpht.com / yt3.googleusercontent.com) as
// protocol-relative ("//yt3.ggpht.com/...") while video thumbnails (i.ytimg.com) normally carry an
// explicit scheme. Coil/OkHttp can't resolve a schemeless URL, so avatars silently failed to load —
// normalize before it ever reaches the Kotlin side.
function normalizeThumbUrl(url) {
  if (typeof url !== 'string' || url.length === 0) return url;
  return url.startsWith('//') ? 'https:' + url : url;
}

function pickThumbnail(thumbnails) {
  if (!Array.isArray(thumbnails) || thumbnails.length === 0) return null;
  const sorted = thumbnails.slice().sort((a, b) => (b.width || 0) - (a.width || 0));
  const preferred = sorted.find((t) => (t.width || 0) <= 480) || sorted[0];
  return preferred && preferred.url ? normalizeThumbUrl(preferred.url) : null;
}

function asText(v) {
  if (v == null) return '';
  if (typeof v === 'string') return v;
  // Arrays (e.g. metadata_rows) stringify to "[object Object],[object Object]" — never surface that.
  if (Array.isArray(v)) return '';
  if (typeof v.text === 'string') return v.text;
  // youtubei.js YTNodes carry a `.type` and their toString() returns the CLASS name (e.g.
  // "LockupMetadataView"). Without this guard, an author/title field holding such a node would
  // surface its type name as the text. We've already taken `.text` above, so real Text nodes are
  // unaffected; here we just refuse to fall through to toString() for typed nodes.
  if (typeof v.type === 'string') return '';
  if (typeof v.toString === 'function') {
    const s = v.toString();
    if (s !== '[object Object]' && s.indexOf('[object Object]') === -1) return s;
  }
  return '';
}

// Format a millisecond timestamp as a WebVTT cue time "HH:MM:SS.mmm".
function vttTime(ms) {
  ms = Math.max(0, Math.floor(Number(ms) || 0));
  const p = (n, l) => String(n).padStart(l || 2, '0');
  return `${p(Math.floor(ms / 3600000))}:${p(Math.floor((ms % 3600000) / 60000))}:` +
    `${p(Math.floor((ms % 60000) / 1000))}.${p(ms % 1000, 3)}`;
}

// Pull a channel UCID from whatever author/endpoint shape a node carries.
function pickChannelId(node, meta) {
  const a = node.author || (meta && meta.author);
  const cands = [
    a && a.id,
    a && a.endpoint && a.endpoint.payload && a.endpoint.payload.browseId,
    node.channel_id,
    node.author_id,
    node.endpoint && node.endpoint.payload && node.endpoint.payload.browseId,
  ];
  for (const c of cands) {
    if (typeof c === 'string' && c.indexOf('UC') === 0) return c;
  }
  // Deliberately NOT falling back to "first non-empty candidate" here (this used to): youtubei.js's
  // own Author class defaults `id` to the LITERAL string "N/A" when it can't find a browseId
  // (`this.id = id || nav_text?.runs?.[0]?.endpoint?.payload?.browseId || … || 'N/A'` — confirmed by
  // reading node_modules/youtubei.js/dist/src/parser/classes/misc/Author.js directly), which is
  // exactly the shape `a && a.id` above would have matched — silently assigning the fake channel id
  // "N/A" to the card. All real YouTube channel ids are UC-prefixed; a non-UC "id" here is never
  // trustworthy enough to drive the subscribed checkmark or "don't recommend this channel".
  return null;
}

// Gather string values from a node (depth/budget-bounded, cycle-safe) for pattern matching. Depth
// must be generous: lockup view-models bury their metadata text ~7 levels deep
// (node.metadata.metadata.metadata_rows[i].metadata_parts[j].text).
function collectStrings(node, depth, out, budget, seen) {
  if (!node || depth > 10 || out.length >= budget) return;
  if (typeof node === 'string') { out.push(node); return; }
  if (typeof node !== 'object' || seen.has(node)) return;
  seen.add(node);
  if (typeof node.text === 'string') out.push(node.text);
  for (const k of Object.keys(node)) {
    if (out.length >= budget) return;
    // `type` / `item_type` hold the youtubei.js CLASS name (e.g. "LockupMetadataView"); `style` /
    // `badge_style` hold a raw badge-style enum straight off YouTube's response (e.g.
    // "BADGE_STYLE_TYPE_MEMBERS_ONLY", or shorter undocumented variants like "BADGE_MEMBERS_ONLY") —
    // youtubei.js's BadgeView/MetadataBadge/ThumbnailBadgeView classes assign these verbatim with no
    // validation. None of these are display text — skip them so the author/metadata scan can't
    // mistake a node type or a badge constant for a channel name (isEnumConstantLike below is a
    // second, key-name-independent guard for the same shape, in case a badge enum ever rides under
    // a different key).
    if (k === 'type' || k === 'item_type' || k === 'style' || k === 'badge_style') continue;
    const v = node[k];
    if (typeof v === 'string') out.push(v);
    else if (v && typeof v === 'object') collectStrings(v, depth + 1, out, budget, seen);
  }
}

// A raw enum/style constant straight off YouTube's response (e.g. "BADGE_STYLE_TYPE_MEMBERS_ONLY",
// "BADGE_LIVE_NOW", or any other SCREAMING_SNAKE_CASE identifier) — never a real channel name, but
// not caught by the existing id-token filter (which requires 22+ chars; plenty of these constants
// are shorter). Requires at least one underscore (so legitimate all-caps names/acronyms like "NASA"
// or "BBC" are never excluded — real names essentially never contain underscores).
function isEnumConstantLike(t) {
  return /^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$/.test(t);
}

// Find the uploader name for nodes whose author is a channel-link (lockups, watch-next/related):
// the text/part carrying a channel (UC…) endpoint has the channel name as its text. Checks several
// endpoint field names + Text `.runs` (where the channel link usually lives).
function authorTextIfClean(t) {
  if (!t) return '';
  const s = String(t).trim();
  if (!s) return '';
  if (/\bviews?\b|\bago\b|subscrib|watching|premier|streamed/i.test(s)) return '';
  // A card's 3-dot menu ("Go to channel", "Save to playlist", "Not interested", "Report", "Share",
  // "Download", "Remove from Watch later", "Clip", "Block user", …) is itself built from Button/
  // MenuNavigationItem nodes with a plain-string `.text` label AND its own `.endpoint` — "Go to
  // channel" specifically carries a genuine UC-prefixed browseId (it navigates to the very channel
  // the video belongs to). The endpoint walk below (findChannelLink) can't structurally tell a menu
  // button apart from a real byline run — both are "a string next to a channel endpoint" — so once a
  // card has no real byline to find first (channel-tab video lists omit it entirely, since it's
  // redundant on a channel's own page), the walk falls through into the menu and "Go to channel"
  // reads as a syntactically valid, non-blank, non-enum "channel name". Reject known action labels
  // by content rather than trying to structurally distinguish button nodes from Text nodes.
  if (/^(go to channel|about (this|the) channel|(un)?subscribe|share|download|report|not interested|save to .*|remove from .*|add to .*|clip|block( user)?|copy link|turn on notifications|turn off notifications)$/i.test(s)) return '';
  // Metadata rows commonly ride a separator glyph (a bare "." or "•" middot, dashes, pipes) on the
  // SAME run as a channel endpoint — e.g. "ChannelName . 1.2M views . 2 days ago" where each "."
  // between items is its own Text node. Without this guard, whichever separator run happened to
  // carry the endpoint won the author lookup outright (this function runs before findLockupAuthor,
  // which already excludes bare-count-shaped strings like "."), surfacing a lone "." as the channel
  // name on the card instead of falling through to a real name.
  if (/^[\s.•·\-–—|,:]+$/.test(s)) return '';
  // A badge-style enum (see isEnumConstantLike) that rode along on the same channel-linked run/node
  // as the endpoint — collectStrings' key-based skip handles the common case, but this function
  // doesn't go through collectStrings, so it needs its own guard.
  if (isEnumConstantLike(s)) return '';
  // youtubei.js's own Text/Author classes fall back to the LITERAL string "N/A" when the underlying
  // simpleText/runs data is absent (Text.toString(): `this.text || 'N/A'`; Author: `nav_text?.text ||
  // 'N/A'`) — confirmed by reading node_modules/youtubei.js's parser source directly rather than
  // guessing (live API calls aren't reachable from this sandbox). That's a real, distinct string, not
  // an empty one, so none of the blank-string checks above catch it — without this it would ride
  // through as if it were a genuine (wrong) channel name.
  if (s === 'N/A') return '';
  return s;
}
function hasUcBrowse(ep) {
  const bid = ep && ep.payload && ep.payload.browseId;
  return typeof bid === 'string' && bid.indexOf('UC') === 0;
}
// Depth/cycle-safe walk that finds BOTH the channel name and its UC-prefixed browseId by locating the
// run/node that actually carries a channel navigation endpoint, returning them together as one unit.
// Replaces two separate, narrower lookups that used to run independently:
//  - the old findAuthorByEndpoint (name only) checked a node's whole CONCATENATED `.text` even when
//    only one of several sibling runs carried the channel link — so a delimiter-joined byline like
//    "ChannelName • 1.2M views • 2 days ago" riding as a SINGLE Text with multiple runs (one YouTube
//    layout does this; another emits each piece as its own separate metadata part) got rejected
//    outright by authorTextIfClean's views/ago noise guard, even though the specific run carrying the
//    endpoint was a clean name. This version reads the MATCHING RUN's own `.text`, not the parent's.
//  - pickChannelId's shallow fixed-field probe (node.author.id, node.channel_id, node.endpoint, …)
//    which simply don't exist on the newer LockupView-shaped nodes youtubei.js now uses for search
//    results (confirmed by reading LockupView.js: content_image/metadata/content_id/content_type/
//    renderer_context only — no author, channel_id, or endpoint field on the node itself). For those
//    nodes pickChannelId always returned null even when a channel endpoint existed a few levels
//    down inside metadata.metadata.metadata_rows[].metadata_parts[].text — that's exactly the
//    "channel name shows but the subscribed checkmark / hide-channel never works on some home feed
//    cards" gap this function closes by doing the same deep walk for the id as for the name.
function findChannelLink(node, depth, seen) {
  if (!node || typeof node !== 'object' || depth > 12 || seen.has(node)) return null;
  seen.add(node);
  if (Array.isArray(node.runs)) {
    for (const run of node.runs) {
      if (hasUcBrowse(run && run.endpoint)) {
        const t = authorTextIfClean(run && run.text != null ? String(run.text) : asText(node.text));
        if (t) return { name: t, channelId: run.endpoint.payload.browseId };
      }
    }
  }
  // A node that itself carries a channel endpoint (its text/name is the channel name).
  const eps = [node.endpoint, node.navigation_endpoint, node.navigationEndpoint,
    node.command, node.on_tap, node.tap_command, node.innertube_command];
  for (const ep of eps) {
    if (hasUcBrowse(ep)) {
      const t = authorTextIfClean(asText(node.text) || asText(node.name) || asText(node.title) || asText(node.content));
      if (t) return { name: t, channelId: ep.payload.browseId };
    }
  }
  for (const k of Object.keys(node)) {
    const v = node[k];
    if (v && typeof v === 'object') {
      const r = findChannelLink(v, depth + 1, seen);
      if (r) return r;
    }
  }
  return null;
}

// Last-resort author for lockup nodes whose channel byline parsed to plain text with no endpoint
// (e.g. collab "A and B" bylines): the first metadata string that isn't the title / a view-count /
// upload-time / duration / badge is the uploader.
function findLockupAuthor(node, title) {
  const meta = node.metadata;
  if (!meta || typeof meta !== 'object') return '';
  const strs = [];
  collectStrings(meta, 0, strs, 80, new Set());
  for (const raw of strs) {
    const t = (raw || '').trim();
    if (!t || t === title) continue;
    if (t.indexOf('://') !== -1 || t.indexOf('/') !== -1) continue;   // urls / paths
    if (t.length > 60) continue;                                       // not a channel name
    if (/^[A-Za-z0-9_-]{22,}$/.test(t)) continue;                      // ids / tokens (no spaces)
    if (/\bviews?\b|\bwatching\b|\bago\b|premier|streamed|subscrib/i.test(t)) continue;
    if (/^[\d.,]+\s*[kmb]?$/i.test(t)) continue;        // bare counts like "1.2M"
    if (/^\d{1,2}(:\d{2}){1,2}$/.test(t)) continue;     // duration
    if (/^(new|live|verified|members? only|cc|hd|4k|shorts?)$/i.test(t)) continue; // badge words
    if (isEnumConstantLike(t)) continue;   // raw badge-style enum, e.g. BADGE_STYLE_TYPE_MEMBERS_ONLY
    if (t === 'N/A') continue;             // youtubei.js's Text/Author "missing data" placeholder — see authorTextIfClean
    return t;
  }
  return '';
}

// Robustly pull views / published / duration from ANY video node shape — classic Video,
// CompactVideo, GridVideo, or the new lockup view-models where the text lives in nested metadata
// rows. Known fields first; if anything's still missing, scan the node's strings by pattern so
// related / channel / playlist cards carry the same data as the trending cards.
function extractMeta(node, meta) {
  let duration = asText(node.duration) || (node.duration && String(node.duration.seconds || '')) ||
    asText(node.length_text) || asText(node.duration_text) || '';
  let views = asText(node.short_view_count) || asText(node.view_count) ||
    asText(meta && meta.view_count) || '';
  let published = asText(node.published) || asText(node.published_time) ||
    asText(node.published_time_text) || asText(node.publishedTimeText) || asText(node.upload_date) ||
    asText(meta && meta.published) || '';
  if (!views || !published || !duration) {
    const strs = [];
    collectStrings(node, 0, strs, 400, new Set());
    if (!views) {
      const s = strs.find((x) => /\bviews?\b/i.test(x) && !/subscrib/i.test(x));
      if (s) views = s.trim();
    }
    if (!published) {
      const s = strs.find((x) =>
        /\d+\s*(second|minute|hour|day|week|month|year)s?\s*ago/i.test(x) ||
        /^(streamed|premiered)/i.test(x.trim()));
      if (s) published = s.trim();
    }
    if (!duration) {
      const s = strs.find((x) => /^\s*\d{1,2}(:\d{2}){1,2}\s*$/.test(x));
      if (s) duration = s.trim();
    }
  }
  return { views, published, duration };
}

// ── Members-only filtering ───────────────────────────────────────────────────
// This app has no sign-in flow and no membership/purchase support at all (see createAnonSession) —
// a members-only video is a pure dead end: it shows up looking like any other card, but tapping it
// fails to play. Rather than surface that and let the user find out the hard way, every video list
// hides them outright. Filtering happens once, centrally, inside normaliseVideo (below) — every
// listing path in this app (search, home feed's every source kind, channel videos, the Subscriptions
// tab, playlists, related/watch-next) already funnels through it, so this one change covers all of
// them instead of needing a separate check per surface.
//
// youtubei.js passes badge data straight through from YouTube's response with a different shape per
// renderer: classic Video/CompactVideo/GridVideo expose a flat `.badges` array (MetadataBadge);
// LockupView-shaped nodes (search results, topic-blend, channel-tab lists) bury per-row badges at
// metadata.metadata.metadata_rows[].badges (BadgeView) instead; either can ALSO carry a thumbnail
// overlay lock badge (`thumbnail_overlays`, a ThumbnailOverlayBadgeView → ThumbnailBadgeView). Rather
// than hand-model the exact path per renderer, collectBadgeStrings walks the node but ONLY descends
// into keys actually named like a badge/overlay container — never generic title/description/byline
// fields — so a video whose title happens to mention "members" can't get caught by this. The style
// enum is typically "BADGE_STYLE_TYPE_MEMBERS_ONLY" (or the shorter, undocumented "BADGE_MEMBERS_ONLY"
// seen in the wild — see the earlier channel-name badge-leak fix); the human label/tooltip varies by
// locale ("Members only", "Join to watch", …) — matching broadly on /member/i against text confined to
// badge containers, plus a plain style/enum check, covers the variants without hardcoding one exact
// string.
const BADGE_CONTAINER_KEYS = new Set([
  'badges', 'thumbnail_overlays', 'thumbnailBadges', 'thumbnail_overlay', 'overlays', 'badge',
]);
function collectBadgeStrings(node, depth, out, budget, seen, inBadgeContainer) {
  if (!node || depth > 8 || out.length >= budget) return;
  if (typeof node !== 'object' || seen.has(node)) return;
  seen.add(node);
  if (Array.isArray(node)) {
    for (const item of node) collectBadgeStrings(item, depth + 1, out, budget, seen, inBadgeContainer);
    return;
  }
  for (const k of Object.keys(node)) {
    if (out.length >= budget) return;
    const v = node[k];
    if (inBadgeContainer && typeof v === 'string' &&
        (k === 'style' || k === 'badge_style' || k === 'label' || k === 'text' ||
         k === 'tooltip' || k === 'accessibility_label' || k === 'icon_type' || k === 'icon_name')) {
      out.push(v);
    } else if (v && typeof v === 'object') {
      collectBadgeStrings(v, depth + 1, out, budget, seen, inBadgeContainer || BADGE_CONTAINER_KEYS.has(k));
    }
  }
}
function isMembersOnly(node) {
  const strs = [];
  collectBadgeStrings(node, 0, strs, 60, new Set(), false);
  return strs.some((s) => /MEMBERS_ONLY|members?[\s-]?only/i.test(s));
}

function normaliseVideo(node) {
  if (!node || typeof node !== 'object') return null;
  // Lockup nodes carry an explicit content_type ('VIDEO' | 'CHANNEL' | 'PLAYLIST' | ...) — bail out
  // immediately for a non-video lockup rather than risk misparsing a channel/playlist card as a
  // video (a channel lockup's content_id + metadata.title alone can otherwise look video-shaped).
  if (typeof node.content_type === 'string' && node.content_type !== 'VIDEO') return null;

  // No membership support anywhere in this app — see isMembersOnly's comment. Bail out before doing
  // any of the (heavier) author/meta extraction below, same as the content_type check above.
  if (isMembersOnly(node)) return null;

  // `content_id` covers the newer "lockup" view-model nodes; the rest cover
  // classic Video / CompactVideo / GridVideo renderers.
  const id = node.id || node.video_id || node.content_id ||
    (node.endpoint && node.endpoint.payload && node.endpoint.payload.videoId);
  if (!id) return null;

  // Lockup nodes nest metadata under `.metadata` (LockupMetadataView).
  const meta = node.metadata || {};
  const title = asText(node.title) || asText(meta.title);
  const authorObj = node.author || meta.author;
  let author = asText(node.author) ||
    (authorObj && asText(authorObj.name)) ||
    (typeof authorObj === 'string' ? authorObj : '') ||
    asText(meta.author) || '';
  let channelId = pickChannelId(node, meta);
  // Classic Video/CompactVideo nodes carry author.id/author.endpoint, already covered above. Newer
  // LockupView-shaped nodes (what search — and so the home feed's topic-blend sources — mostly
  // returns now) carry NEITHER an author field NOR any of pickChannelId's fixed fields; the channel
  // link only exists nested inside metadata.metadata.metadata_rows[].metadata_parts[].text. Do ONE
  // deep walk for whichever piece (name and/or id) is still missing rather than two separate,
  // narrower lookups — see findChannelLink's comment for why this replaced the old split logic.
  if (!author || !channelId) {
    const link = findChannelLink(node, 0, new Set());
    if (link) {
      if (!author) author = link.name;
      if (!channelId) channelId = link.channelId;
    }
  }
  if (!author) author = findLockupAuthor(node, title); // lockup byline as plain text, no endpoint at all
  const m = extractMeta(node, meta);
  const duration = m.duration;
  const views = m.views;
  const published = m.published;

  const thumbs = node.thumbnails ||
    (node.thumbnail && (node.thumbnail.thumbnails || node.thumbnail)) ||
    (node.content_image && node.content_image.thumbnails) ||
    (meta.thumbnail && (meta.thumbnail.thumbnails || meta.thumbnail)) ||
    (node.rich_thumbnail && node.rich_thumbnail.thumbnails) || [];
  const thumbnail = pickThumbnail(Array.isArray(thumbs) ? thumbs : []);

  if (!title && !thumbnail) return null; // not actually a video node

  return {
    id: String(id),
    title: String(title),
    thumbnail,
    author: String(author),
    duration: String(duration),
    views: String(views),
    channelId,
    published: String(published),
  };
}

// ── ustreamer-config extraction ─────────────────────────────────────────────
// `videoPlaybackUstreamerConfig` is required by googlevideo's SabrStream.
// Google's player response nests it under
//   playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig.videoPlaybackUstreamerConfig
// but youtubei.js sometimes lifts it into streaming_data depending on version.
// We probe every known location and walk the raw response as a last resort.
function extractUstreamerConfig(info) {
  if (!info) return null;
  const tryPaths = [
    info.streaming_data && info.streaming_data.video_playback_ustreamer_config,
    info.player_config &&
      info.player_config.media_common_config &&
      info.player_config.media_common_config.media_ustreamer_request_config &&
      info.player_config.media_common_config.media_ustreamer_request_config.video_playback_ustreamer_config,
    info.playerConfig &&
      info.playerConfig.mediaCommonConfig &&
      info.playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig &&
      info.playerConfig.mediaCommonConfig.mediaUstreamerRequestConfig.videoPlaybackUstreamerConfig,
  ];
  for (const v of tryPaths) {
    if (typeof v === 'string' && v.length > 0) return v;
  }
  // Deep search as a last resort — keyed off the camelCase field name we saw
  // in Google's raw player response.
  const seen = new Set();
  function walk(obj, depth) {
    if (!obj || typeof obj !== 'object' || depth > 6 || seen.has(obj)) return null;
    seen.add(obj);
    for (const k of Object.keys(obj)) {
      const v = obj[k];
      if (k === 'videoPlaybackUstreamerConfig' || k === 'video_playback_ustreamer_config') {
        if (typeof v === 'string' && v.length > 0) return v;
      }
      if (v && typeof v === 'object') {
        const found = walk(v, depth + 1);
        if (found) return found;
      }
    }
    return null;
  }
  const fromWalk = walk(info, 0);
  if (fromWalk) {
    console.log('[__yt] ustreamer-config found via deep walk (not at known path)');
    return fromWalk;
  }
  console.warn('[__yt] no videoPlaybackUstreamerConfig anywhere on info; ' +
    'keys at top level: ' + Object.keys(info).join(','));
  if (info.player_config) {
    console.warn('  player_config keys: ' + Object.keys(info.player_config).join(','));
  }
  if (info.streaming_data) {
    console.warn('  streaming_data keys: ' + Object.keys(info.streaming_data).join(','));
  }
  return null;
}

// A YouTube storyboard ("scrub preview") spec has one board per resolution level (youtubei.js's
// PlayerStoryboardSpec, exposed as info.storyboards.boards) — level 0 is tiny/fine-grained, later
// levels progressively bigger and coarser (fewer, larger tiles). Picks whichever level's thumbnail
// width is closest to TARGET_STORYBOARD_WIDTH: big enough to look decent in a floating scrub-preview
// box, small enough that a single sprite-sheet fetch (each covers columns*rows tiles) stays cheap.
// `template_url` still carries a literal "$M" placeholder for which sprite sheet (of
// storyboard_count total) to fetch — youtubei.js's PlayerStoryboardSpec already resolves $L/$N
// itself, but leaves $M for the caller since that depends on which frame you're seeking to.
const TARGET_STORYBOARD_WIDTH = 160;

function extractStoryboard(info) {
  try {
    const sb = info.storyboards;
    const boards = sb && Array.isArray(sb.boards) ? sb.boards : null;
    if (!boards || boards.length === 0) return null;
    let best = boards[0];
    for (const b of boards) {
      if (Math.abs((b.thumbnail_width || 0) - TARGET_STORYBOARD_WIDTH) <
          Math.abs((best.thumbnail_width || 0) - TARGET_STORYBOARD_WIDTH)) {
        best = b;
      }
    }
    if (!best || !best.template_url) return null;
    return {
      templateUrl: String(best.template_url),
      thumbWidth: best.thumbnail_width || 0,
      thumbHeight: best.thumbnail_height || 0,
      columns: best.columns || 0,
      rows: best.rows || 0,
      intervalMs: best.interval || 0,
      thumbCount: best.thumbnail_count || 0,
      storyboardCount: best.storyboard_count || 0,
    };
  } catch (e) {
    return null;
  }
}

// ── Video info / format normalisation ───────────────────────────────────────
function normaliseThumbs(arr) {
  if (!Array.isArray(arr)) return [];
  return arr
    .filter((t) => t && t.url)
    .map((t) => ({ url: String(t.url), width: t.width || 0, height: t.height || 0 }));
}

async function normaliseFormat(f, player) {
  if (!f) return null;

  // Resolve the playable URL. CRITICAL: web-family URLs carry an `&n=`
  // throttling parameter that must be transformed by base.js's nsig function,
  // or googlevideo returns 403. `format.decipher(player)` applies both the
  // signature cipher AND the nsig transform, AND (via Player.decipher)
  // unconditionally stamps the player's current `po_token` onto the URL as
  // `&pot=` — for every client, not just web-family, since the pot-stamp
  // step runs regardless of whether there was a sig/nsig to transform. So
  // always prefer decipher() over the raw `f.url`, for every client.
  //
  // format.decipher() is ASYNC in this youtubei.js version — awaiting it is
  // load-bearing. An unawaited call assigns a Promise object here, which
  // fails the `typeof === 'string'` check below and silently drops every
  // format's url to null (this is what "0 direct" after a raw probe that
  // passed meant: the probe counts pre-decipher `f.url`, this function's
  // output does not).
  let url = null;
  try {
    if (player && typeof f.decipher === 'function') {
      const deciphered = await f.decipher(player);
      url = typeof deciphered === 'string' && deciphered.length > 0
        ? deciphered
        : (typeof f.url === 'string' ? f.url : null);
    } else if (typeof f.url === 'string') {
      url = f.url;
    }
  } catch (e) {
    // Fall back to the raw url if decipher fails (better than nothing).
    if (typeof f.url === 'string') url = f.url;
  }

  return {
    itag: f.itag || 0,
    mimeType: String(f.mime_type || ''),
    quality: String(f.quality_label || f.quality || ''),
    bitrate: f.bitrate || 0,
    width: f.width || 0,
    height: f.height || 0,
    fps: f.fps || 0,
    contentLength: typeof f.content_length === 'string'
      ? f.content_length : (f.content_length != null ? String(f.content_length) : null),
    url: typeof url === 'string' ? url : null,
    hasAudio: !!f.has_audio,
    hasVideo: !!f.has_video,
    isDrc: !!f.is_drc,
    // OTF (open-time fragmented) formats need segment-based reconstruction (getSegmentTemplate's
    // sq=0/sq=$Number$ media URLs), not a single HTTP GET against `url` as if it were a plain
    // progressive file — NewPipeExtractor marks this explicitly (YoutubeStreamExtractor sets
    // isUrl=false for FORMAT_STREAM_TYPE_OTF) specifically so downstream code never treats one as a
    // directly-fetchable file. info.toDash() (used to build dashManifest above) already handles OTF
    // correctly on its own via youtubei.js's internal segment-template logic; this flag exists so
    // any OTHER consumer of this formats array (e.g. a progressive-source fallback) can tell the
    // difference instead of silently trying a bare GET on an OTF url and getting a malformed file.
    isOtf: !!f.is_type_otf,
    audioTrack: f.audio_track ? {
      id: String(f.audio_track.id || ''),
      displayName: String(f.audio_track.display_name || ''),
    } : null,
    // True for the video's original/default audio. Dubbed tracks (e.g.
    // auto-dubbed French) carry an audio_track without this flag.
    audioIsDefault: !!(f.audio_track && f.audio_track.audio_is_default),
    // LOCAL FIX: this used to also substring-match "dubbed" against `f.xtags`, but xtags is a
    // base64url-encoded PROTOBUF blob (FormatXTags), not searchable text — decoding it to ASCII and
    // then finding "dubbed" as a literal substring only works when that text happens to land on a
    // byte-alignment boundary, which is essentially never guaranteed. It was dead weight that looked
    // like real dubbed-track detection but almost never matched. youtubei.js's own Format
    // constructor already decodes the xtags protobuf properly (reading the 'acont' tag) and exposes
    // the result as f.is_dubbed / f.is_auto_dubbed — use those instead of re-parsing badly.
    isDubbed: !!(f.audio_track && /dub/i.test(f.audio_track.display_name || '')) ||
      !!f.is_dubbed || !!f.is_auto_dubbed,
    language: f.language ? String(f.language) : null,
  };
}

// ── SABR info surface ───────────────────────────────────────────────────────
// Maps a youtubei.js Format to the camelCase JSON shape PipePipe's
// YoutubeSabrFormat.fromAdaptiveFormats() reads. The format-id fields the SABR
// server matches on are itag + lastModified + xtags, so those must be exact.
// last_modified is a Date in youtubei.js; last_modified_ms is the raw value.
function sabrFormatJson(f) {
  const o = {
    itag: f.itag,
    mimeType: f.mime_type,
    isDrc: !!f.is_drc,
  };
  if (f.last_modified_ms != null) o.lastModified = String(f.last_modified_ms);
  if (f.xtags != null) o.xtags = f.xtags;
  if (f.quality_label != null) o.qualityLabel = f.quality_label;
  if (f.audio_quality != null) o.audioQuality = f.audio_quality;
  if (f.width != null) o.width = f.width;
  if (f.height != null) o.height = f.height;
  if (f.bitrate != null) o.bitrate = f.bitrate;
  if (f.content_length != null) o.contentLength = String(f.content_length);
  if (f.approx_duration_ms != null) o.approxDurationMs = String(f.approx_duration_ms);
  if (f.audio_track && f.audio_track.id != null) o.audioTrack = { id: f.audio_track.id };
  // initRange/indexRange (upstream #78): YoutubeSabrFormat.fromJson() on the Java side reads these
  // to build the init-segment metadata YoutubeSabrSession.fetchInitializationData() needs. Safe to
  // pass through as-is — these are plain byte offsets, no decipher/encoding involved.
  if (f.init_range) o.initRange = { start: f.init_range.start, end: f.init_range.end };
  if (f.index_range) o.indexRange = { start: f.index_range.start, end: f.index_range.end };
  // LOCAL NOTE — `url` deliberately NOT included here, unlike initRange/indexRange above. Unlike
  // those, a format's `url` for a web-family client is signature/nsig-obfuscated until
  // `format.decipher(player)` runs (see normaliseFormat's doc for why that must be awaited), and
  // nothing in this SABR path currently deciphers per-format URLs — the SABR protocol streams media
  // through the UMP session, not per-format HTTP GETs. Only fetchInitializationData() would ever
  // read this url, and NOTHING calls fetchInitializationData()/bootstrapInitialization() anywhere in
  // the app today (verified: not referenced outside YoutubeSabrSession/YoutubeSabrProbe's own
  // internals) — so this is currently dead code either way. If that ever gets wired up, the url
  // populated here would ALSO need to go through the same decipher step as normaliseFormat's, or
  // fetchInitializationData's range GET will 403/404 on an un-deciphered URL exactly like the DASH
  // manifest did before that fix.
  return o;
}

// youtubei.js client name → PipePipe YoutubeSabrClientProfile enum constant.
const SABR_PROFILE_MAP = {
  ANDROID_VR: 'ANDROID_VR',
  ANDROID: 'ANDROID',
  IOS: 'IOS',
  WEB: 'WEB',
  MWEB: 'WEB',
  WEB_EMBEDDED: 'WEB_EMBEDDED',
  TV: 'TVHTML5',
  TV_EMBEDDED: 'TVHTML5',
};
// Order to probe for a SABR-capable player response. A usable one needs BOTH a
// serverAbrStreamingUrl and a videoPlaybackUstreamerConfig.
//
// MOBILE FIRST: the WEB serverAbrStreamingUrl carries an `n` param that must run YouTube's nsig
// transform, which FAILS inside QuickJS ("Failed to decipher nsig") → every SABR POST 403s. The
// ANDROID/ANDROID_VR/IOS clients serve "truly direct" URLs with no nsig requirement, and our
// YoutubeSabrClientProfile versions for ANDROID (21.03.36) and ANDROID_VR (1.65.10) match
// youtubei.js's exactly. WEB is kept only as a last resort.
// WEB FIRST: the WEB client is what the canonical SABR player uses, and its WEB BotGuard PO token
// (which we can mint) is natively trusted — so the server authorizes random-access (forward seek)
// and doesn't drop the session into status=3 after the cold-start grace. The WEB
// serverAbrStreamingUrl carries an `n` param that needs the nsig transform; with the fixed eval
// shim youtubei.js runs that transform in QuickJS. ANDROID/IOS remain fallbacks.
const SABR_CLIENT_PREFERENCE = ['WEB', 'ANDROID', 'IOS', 'TV', 'WEB_EMBEDDED', 'ANDROID_VR'];

// Apply base.js's nsig transform to a URL's `n` parameter (the serverAbrStreamingUrl
// carries one, exactly like format URLs; an untransformed n → 403). Falls back to
// the raw URL if no player or the transform fails.
async function decipherUrlN(player, url) {
  if (!url) return url;
  const hadN = /[?&]n=/.test(url);
  try {
    if (player && typeof player.decipher === 'function') {
      const out = await player.decipher(url, null, null, undefined);
      if (typeof out === 'string' && out.length > 0) {
        const inN = (url.match(/[?&]n=([^&]*)/) || [])[1] || '';
        const outN = (out.match(/[?&]n=([^&]*)/) || [])[1] || '';
        const inPot = /[?&]pot=/.test(url), outPot = /[?&]pot=/.test(out);
        console.log('[__yt] abrUrl n-decipher: hadN=' + hadN +
          ' result=' + (out === url ? 'unchanged' : 'changed') +
          ' inN=' + inN + ' outN=' + outN +
          ' nChanged=' + (inN !== outN) + ' potIn=' + inPot + ' potOut=' + outPot +
          ' lenIn=' + url.length + ' lenOut=' + out.length);
        return out;
      }
    }
    console.warn('[__yt] abrUrl n-decipher: no player / empty result (hadN=' + hadN + ')');
  } catch (e) {
    console.warn('[__yt] abrUrl n-decipher FAILED (hadN=' + hadN + '): ' + ((e && e.message) || e));
  }
  return url;
}

// ── Client selection ────────────────────────────────────────────────────────
// Innertube clients that still serve traditional direct https URLs (no SABR).
//
// VISIONOS FIRST, TV_EMBEDDED FALLBACK as of 2026-08-22. The token-free-client theory below (git
// blame for the prior version of this comment) was directionally right but wasn't grounded in what
// actually works — that only came from reading NewPipeExtractor's and GrayJay's real, current
// source instead of secondary docs (yt-dlp's PO Token Guide). See CLAUDE.md's "attestation /
// streaming protocol changes" section for why that's now a mandatory first step, not an afterthought.
//
// NewPipeExtractor (github.com/TeamNewPipe/NewPipeExtractor, dev branch,
// YoutubeStreamExtractor.java#onFetchPage) fetches ALL of its stream data from exactly one client:
// `VISIONOS` (Innertube client id 101 — YouTube's little-used Apple Vision Pro app). WEB is fetched
// too, but ONLY for metadata/thumbnails — the code's own comment says playability is deliberately
// not checked on that response "because we use this request exclusively for metadata, not for
// extracting streams." PoTokenProvider.setPoTokenProvider() is a no-op stub whose Javadoc states:
// "the extractor doesn't use any client supporting poTokens until SABR support is added to the
// extractor." NewPipe isn't picking a token-optional client among several — it's picking the ONE
// client obscure enough that YouTube hasn't bothered gating it at all, and never touching PO tokens.
//
// GrayJay (plugins.grayjay.app/Youtube/YoutubeScript.js, source.getContentDetails's non-ABR legacy
// path) tries `TVHTML5_SIMPLY_EMBEDDED_PLAYER` first (requestTvHtml5EmbedStreamingData), then `IOS`
// (requestIOSStreamingData), before ever falling back to its SABR/UMP session client. Neither of
// those two request bodies ever sets serviceIntegrityDimensions.poToken — pot is attached ONLY on
// GrayJay's generic WEB-context player call and its WEB-scoped SABR/UMP session (clientName: 1).
// Note GrayJay never uses bare `TVHTML5` ("TV" in the old version of this list) — only the
// _EMBEDDED_PLAYER variant. Our own logs confirm why: plain TV threw "The page needs to be
// reloaded." (TVHTML5 wants a fuller living-room-device handshake neither reference app bothers
// implementing), so it's dropped from this list entirely rather than kept as dead weight.
//
// youtubei.js's `TV_EMBEDDED` IS GrayJay's TVHTML5_SIMPLY_EMBEDDED_PLAYER under the hood (see
// node_modules/youtubei.js/dist/src/utils/Constants.js — CLIENTS.TV_EMBEDDED.NAME) — no separate
// client needed there. VISIONOS needed a real dependency bump: youtubei.js only added it in 18.0.0
// (see package.json for the version pin and comment) — 17.x has no such client.
//
// Ordering: VISIONOS is the default (NewPipe's sole client, and per that evidence the one least
// likely to be pot-gated at all), TV_EMBEDDED is the fallback (GrayJay's first choice). WEB/ANDROID/
// MWEB/IOS remain further fallbacks for videos those two can't serve (embeddability/age
// restrictions are common on VISIONOS/TV_EMBEDDED) — exactly what the direct-url-count probe below
// falls through on.
const CLIENT_PREFERENCE = ['VISIONOS', 'TV_EMBEDDED', 'WEB', 'ANDROID', 'MWEB', 'IOS'];
// Clients whose GVS media URLs actually require a PO token, per yt-dlp's PO Token Guide
// (github.com/yt-dlp/yt-dlp/wiki/PO-Token-Guide: web/mweb always require one; tv/web_embedded/
// android_vr never do; android/ios "may") — cross-checked against GrayJay's actual source, which
// never sets serviceIntegrityDimensions.poToken on its TV_EMBEDDED/IOS/ANDROID requests (only on
// its WEB-context calls), and against NewPipeExtractor, whose VisionOS-only path never touches a
// PoTokenProvider at all. Replaces the old `WEB_FAMILY` set, which included WEB_EMBEDDED (wrong per
// the same guide) and was never actually read anywhere — this one is: chooseClientInfo() and
// getLegacyPlayback() both gate the gvs pot mint+stamp on membership here, so a client that doesn't
// ask for a pot doesn't pay for one (one fewer BotGuard attestation round trip) and doesn't get a
// possibly-unwanted token stamped onto its media URLs.
const POT_REQUIRED_CLIENTS = new Set(['WEB', 'MWEB']);
const MIN_DIRECT = 3; // skip clients that return only the lone 360p itag 18

// Per-client streaming User-Agent. Mobile URLs are bound to the app UA — a
// desktop browser UA gets 403. Matches what youtubei.js sends in /player.
const STREAM_UA = {
  IOS: 'com.google.ios.youtube/20.11.6 (iPhone10,4; U; CPU iOS 16_7_7 like Mac OS X)',
  ANDROID: 'com.google.android.youtube/21.03.36 (Linux; U; Android 13) gzip',
  // Copied from youtubei.js 18.0.0's Constants.CLIENTS.VISIONOS.USER_AGENT (not publicly exported,
  // so hardcoded here same as IOS/ANDROID above — keep in sync if the youtubei.js pin moves).
  // VISIONOS's /player request already claims this identity (see HTTPClient.js's VISIONOS case);
  // media requests should match it rather than silently falling back to DESKTOP_UA below and
  // claiming a Vision Pro on one request and a Windows browser on the next.
  VISIONOS: 'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 '
    + '(KHTML, like Gecko) Version/26.0 Safari/605.1.15',
};
// Also pinned as the Innertube *context* userAgent — see createAnonSession(). Chrome 125 on
// Windows 10 is chosen to agree with every other identity field youtubei.js puts in the locally
// generated context (browserName=Chrome, browserVersion=125.0.0.0, osName=Windows, osVersion=10.0,
// platform=DESKTOP), so the client we claim to be is internally consistent on every axis.
const DESKTOP_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
  '(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36';

// The client that last worked is cached (in-memory + persisted) so we make ONE
// /player call per video instead of re-probing all candidates each time.
const PREF_CLIENT_KEY = 'yt_pref_client_v4'; // v4: VISIONOS-first/TV_EMBEDDED-fallback reorder + bare TV dropped (2026-08-22), see CLIENT_PREFERENCE's doc. Bumped so a device with an old cached client (even 'TV_EMBEDDED' or 'TV', now removed) re-sweeps instead of trusting a preference from before VISIONOS existed as an option.
let preferredClient = null;
let preferredClientLoaded = false;

async function loadPreferredClient() {
  if (preferredClientLoaded) return;
  preferredClientLoaded = true;
  try {
    const c = await globalThis.__nativeStorageGet(PREF_CLIENT_KEY);
    if (c && CLIENT_PREFERENCE.indexOf(c) !== -1) {
      preferredClient = c;
      console.log('[__yt] restored preferred client: ' + c);
    }
  } catch (_) { /* first run / no storage */ }
}

async function savePreferredClient(c) {
  preferredClient = c;
  try { await globalThis.__nativeStorageSet(PREF_CLIENT_KEY, c); } catch (_) {}
}

// Same idea as PREF_CLIENT_KEY above, but for getSabrInfo's SABR_CLIENT_PREFERENCE probe — a
// SEPARATE preference (a client can return direct URLs but not a serverAbrStreamingUrl+ustreamer
// pair, or vice versa) that used to be re-probed from scratch (up to 6 sequential /player calls)
// on EVERY video open. Caching the winner turns that into one call in the common case.
const PREF_SABR_CLIENT_KEY = 'yt_pref_sabr_client_v1';
let preferredSabrClient = null;
let preferredSabrClientLoaded = false;

async function loadPreferredSabrClient() {
  if (preferredSabrClientLoaded) return;
  preferredSabrClientLoaded = true;
  try {
    const c = await globalThis.__nativeStorageGet(PREF_SABR_CLIENT_KEY);
    if (c && SABR_CLIENT_PREFERENCE.indexOf(c) !== -1) {
      preferredSabrClient = c;
      console.log('[__yt] restored preferred SABR client: ' + c);
    }
  } catch (_) { /* first run / no storage */ }
}

async function savePreferredSabrClient(c) {
  preferredSabrClient = c;
  try { await globalThis.__nativeStorageSet(PREF_SABR_CLIENT_KEY, c); } catch (_) {}
}

function directUrlCount(info) {
  const sd = info.streaming_data || {};
  const fmts = [
    ...(Array.isArray(sd.adaptive_formats) ? sd.adaptive_formats : []),
    ...(Array.isArray(sd.formats) ? sd.formats : []),
  ];
  return fmts.filter((f) => typeof f.url === 'string' && f.url.length > 0).length;
}

/**
 * Mint (if [client] is in POT_REQUIRED_CLIENTS) and stamp a content-bound GVS pot onto [player],
 * mirroring the mint getLegacyPlayback does for its final choice — pulled up here so a candidate
 * can be verified (see verifyClientPlayable below) with the SAME pot it would actually stream with,
 * instead of verifying an unauthenticated request and getting a false negative for WEB/MWEB.
 * Returns the minted pot (or null: not required for this client, or the mint failed).
 */
async function mintGvsPotIfNeeded(client, videoId, player) {
  if (!POT_REQUIRED_CLIENTS.has(client)) {
    // Explicitly clear, not just "don't set" — player.po_token may still hold a PREVIOUS
    // candidate's minted pot (or the session's own /player-auth pot) from earlier in this same
    // sweep. Clearing it here means verifyClientPlayable's HEAD check below tests the exact URL
    // shape this client will actually ship with (no &pot= — see getLegacyPlayback's matching
    // clear/restore for why that field can't just be left alone).
    if (player) player.po_token = undefined;
    return null;
  }
  if (!player) return null;
  try {
    const { visitorData: gvsVisitorData } = await resolveSessionVisitorData();
    const gvsId = bindingIdFor('gvs', swapPoTokenBindings, videoId, gvsVisitorData);
    const gvsPot = (await ensurePoToken(gvsId, false, 'gvs')).poToken;
    if (gvsPot) player.po_token = gvsPot;
    return gvsPot || null;
  } catch (e) {
    console.warn(`[__yt] probe ${client}: gvs pot mint failed: ` + ((e && e.message) || e));
    return null;
  }
}

/**
 * Real-fetch check that a client's manifest is actually playable, not just present — mirrors
 * GrayJay's verifyDirectPlayback() (plugins.grayjay.app/Youtube/YoutubeScript.js): a client can be
 * pot/session-gated in a way that still returns a well-formed manifest full of URLs (this is
 * exactly how the app picked IOS with "20 direct urls" in an earlier run and then 403'd on every
 * single DASH chunk) — counting URLs in the manifest never catches that, only a real authenticated
 * request does. Does a HEAD against one deciphered sample URL (same as GrayJay's HEAD-before-GET
 * check). Returns true when the URL is reachable (2xx) OR when verification itself couldn't
 * meaningfully run (no sample format, fetch threw for an unrelated reason) — fail OPEN on an
 * untestable client so a fetch bug here can't strand playback entirely. Returns false only on a
 * confirmed rejection, which is the one case we actually want to fall through on.
 */
async function verifyClientPlayable(info, player, client) {
  try {
    const sd = info.streaming_data || {};
    const fmts = [
      ...(Array.isArray(sd.adaptive_formats) ? sd.adaptive_formats : []),
      ...(Array.isArray(sd.formats) ? sd.formats : []),
    ];
    const sample = fmts.find((f) => typeof f.url === 'string' && f.url.length > 0);
    if (!sample || typeof sample.decipher !== 'function' || !player) return true;

    const url = await sample.decipher(player);
    if (typeof url !== 'string' || !url) return true;

    const resp = await globalThis.fetch(url, {
      method: 'HEAD',
      headers: STREAM_UA[client] ? { 'User-Agent': STREAM_UA[client] } : {},
    });
    if (resp && resp.ok) return true;
    console.warn(`[__yt] verify ${client}: HEAD → ${resp ? resp.status : '?'} — treating as unplayable`);
    return false;
  } catch (e) {
    console.warn(`[__yt] verify ${client}: check failed (${(e && e.message) || e}) — accepting anyway`);
    return true;
  }
}

/**
 * Pick an Innertube client that returns direct-URL formats for [videoId].
 * Tries the cached `preferredClient` first (unless [forceReprobe]); otherwise
 * probes CLIENT_PREFERENCE in order, live-verifies the first candidate that meets the direct-url
 * threshold (verifyClientPlayable), and caches the winner. Returns { info, client, gvsPot }
 * — gvsPot is the pot minted during verification (POT_REQUIRED_CLIENTS clients only) so callers
 * don't re-mint; null when the cached fast path was used or the client didn't need one.
 */
async function chooseClientInfo(it, videoId, forceReprobe) {
  await loadPreferredClient();
  if (preferredClient && !forceReprobe) {
    try {
      const info = await it.getBasicInfo(videoId, { client: preferredClient });
      if (directUrlCount(info) >= MIN_DIRECT) {
        // No live-verify on the cached fast path by design (same trade-off GrayJay makes gating its
        // own verifyDirectPlayback behind a setting): the whole point of the cache is one /player
        // call per video, and a client that verified once is overwhelmingly likely to still be good.
        return { info, client: preferredClient, gvsPot: null };
      }
    } catch (_) { /* fall through to a full probe */ }
  }
  // Up to two full sweeps across CLIENT_PREFERENCE. A device that just woke from a long
  // locked-screen sleep often still has its radio/network coming back up when this batch of
  // requests goes out — every single client can spuriously fail together even though the
  // connection recovers moments later (this is what used to surface to the user as "No Innertube
  // client returned playable info" right after unlocking the phone, even though the video was
  // perfectly playable). Only re-sweep when the FIRST pass came back with literally nothing (every
  // client threw) — a pass that got real (if under-threshold) responses isn't a connectivity
  // problem and re-sweeping it would just waste time. setTimeout in this runtime doesn't actually
  // delay (see polyfills.js), so there's no artificial wait here — the real wall-clock time each
  // client's own request/timeout already takes is what gives the network a chance to recover.
  for (let sweep = 0; sweep < 2; sweep++) {
    let firstOk = null;
    for (const client of CLIENT_PREFERENCE) {
      try {
        const info = await it.getBasicInfo(videoId, { client });
        const dc = directUrlCount(info);
        if (!firstOk) firstOk = { info, client, gvsPot: null };
        // Diagnostic: when a client yields no playable URLs, log WHY (YouTube's playabilityStatus).
        // Distinguishes a genuine block (LOGIN_REQUIRED / UNPLAYABLE / AGE_VERIFICATION_REQUIRED /
        // "confirm you're not a bot") from a parse/format-shape regression (status OK but 0 formats).
        if (dc < MIN_DIRECT) {
          const ps = info.playability_status || {};
          const sd = info.streaming_data || {};
          const na = Array.isArray(sd.adaptive_formats) ? sd.adaptive_formats.length : 0;
          const np = Array.isArray(sd.formats) ? sd.formats.length : 0;
          console.log(`[__yt] probe ${client}: direct=${dc} status=${ps.status || '?'}`
            + (ps.reason ? ' reason="' + ps.reason + '"' : '')
            + ` adaptive=${na} progressive=${np} hasSD=${!!info.streaming_data}`);
        }
        if (dc >= MIN_DIRECT) {
          const player = it.session && it.session.player;
          const gvsPot = await mintGvsPotIfNeeded(client, videoId, player);
          const playable = await verifyClientPlayable(info, player, client);
          if (!playable) {
            console.warn(`[__yt] client ${client} had ${dc} direct urls but failed live `
              + 'verification — trying next candidate');
            continue;
          }
          await savePreferredClient(client);
          console.log(`[__yt] client=${client} (${dc} direct urls, verified)`);
          return { info, client, gvsPot };
        }
      } catch (e) {
        console.warn(`[__yt] client ${client} failed: ${(e && e.message) || e}`);
      }
    }
    if (firstOk) {
      console.warn('[__yt] no client met direct-url threshold; using ' + firstOk.client);
      return firstOk;
    }
    if (sweep === 0) console.warn('[__yt] every client failed on first sweep; re-probing once more');
  }
  throw new Error('No Innertube client returned playable info');
}

function looksLikeVideoId(s) {
  return typeof s === 'string' && /^[A-Za-z0-9_-]{11}$/.test(s);
}

// Top-level feed data lives behind non-enumerable getters, so a raw deep walk
// over the feed object misses it. Seed the walk from the known accessors and
// the parser's memo (which records every parsed node by type).
function seedsFromFeed(feed) {
  const seeds = [];
  if (!feed) return seeds;
  for (const g of ['videos', 'contents', 'sections', 'shelves', 'page_contents', 'results']) {
    try { const v = feed[g]; if (v) seeds.push(v); } catch (_) {}
  }
  try {
    if (feed.memo && typeof feed.memo.keys === 'function') {
      for (const k of feed.memo.keys()) {
        const arr = feed.memo.get(k);
        if (arr) seeds.push(arr);
      }
    }
  } catch (_) {}
  return seeds;
}

function logFeedShape(label, feed) {
  try {
    let memo = '';
    if (feed && feed.memo && typeof feed.memo.keys === 'function') {
      const t = [];
      for (const k of feed.memo.keys()) t.push(k + ':' + ((feed.memo.get(k) || []).length));
      memo = t.join(', ');
    }
    console.log(`[__yt] ${label} feed memo=[${memo}] keys=[${feed ? Object.keys(feed).join(',') : ''}]`);
  } catch (_) {}
}

// Collect Video nodes from a parsed youtubei.js feed/result by deep-walking the
// tree. Home feed videos are wrapped in RichItem/RichGrid/RichSection
// containers, so simple `.videos` / `memo.get('Video')` accessors miss them —
// the walk reaches the inner Video nodes regardless of nesting.
function collectVideos(parsed) {
  const out = [];
  const seenIds = {};
  const visited = new Set();

  function walk(node, depth) {
    if (!node || typeof node !== 'object' || depth > 8 || visited.has(node)) return;
    visited.add(node);

    let id = null;
    try {
      id = node.id || node.video_id || node.content_id ||
        (node.endpoint && node.endpoint.payload && node.endpoint.payload.videoId);
    } catch (_) {}
    if (looksLikeVideoId(id) && (node.title != null || node.metadata != null)) {
      const v = normaliseVideo(node);
      if (v && !seenIds[v.id]) { seenIds[v.id] = true; out.push(v); }
      return; // matched a video — don't descend further into it
    }

    let keys;
    try { keys = Object.keys(node); } catch (_) { return; }
    for (const k of keys) {
      let val;
      try { val = node[k]; } catch (_) { continue; }
      if (Array.isArray(val)) {
        for (const x of val) walk(x, depth + 1);
      } else if (val && typeof val === 'object') {
        walk(val, depth + 1);
      }
    }
  }

  walk(parsed, 0);
  return out;
}

function pickLargestThumb(thumbs) {
  if (!Array.isArray(thumbs) || thumbs.length === 0) return null;
  const sorted = thumbs.slice().sort((a, b) => (b.width || 0) - (a.width || 0));
  return sorted[0] && sorted[0].url ? normalizeThumbUrl(sorted[0].url) : null;
}

// Normalise a channel search node → { id, name, avatar, subscriberText }, or null if [node] isn't
// actually a channel card. CLASSIFICATION IS TYPE-GATED (node.type / node.content_type), not
// field-shape sniffing — a plain Video node's author often carries a valid UC… channel id too, so
// guessing off field shape alone previously risked misidentifying videos as channels once search()
// started walking a blended (video+channel) result list instead of two separately-typed queries.
function normaliseChannel(node) {
  if (!node || typeof node !== 'object') return null;

  // Newer "lockup" channel card (content_type on a LockupView).
  if (node.type === 'LockupView' && node.content_type === 'CHANNEL') {
    const id = typeof node.content_id === 'string' ? node.content_id : '';
    if (id.indexOf('UC') !== 0) return null;
    const meta = node.metadata || {};
    const name = asText(meta.title) || '';
    if (!name) return null;
    let avatarThumbs = [];
    try {
      const img = meta.image || {};
      // LockupMetadataView.image is a DecoratedAvatarView (.avatar.image) or an AvatarStackView
      // (falls back to its own .image, if any).
      avatarThumbs = (img.avatar && img.avatar.image) || img.image || [];
    } catch (_) {}
    const avatar = pickLargestThumb(Array.isArray(avatarThumbs) ? avatarThumbs : []);
    let subs = '';
    try {
      const rows = (meta.metadata && meta.metadata.metadata_rows) || [];
      outer: for (const row of rows) {
        for (const part of (row.metadata_parts || [])) {
          const t = asText(part.text);
          if (t) { subs = t; break outer; }
        }
      }
    } catch (_) {}
    return { id: String(id), name: String(name), avatar: avatar || null, subscriberText: String(subs) };
  }

  // Classic Channel renderer (node.type === 'Channel').
  if (node.type !== 'Channel') return null;
  const author = node.author || {};
  const id = (typeof author.id === 'string' && author.id) || '';
  if (id.indexOf('UC') !== 0) return null;
  const name = asText(author.name) || '';
  if (!name) return null;
  const avatar = pickLargestThumb(author.thumbnails || []);
  const subs = asText(node.subscriber_count) || asText(node.video_count) || '';
  return { id: String(id), name: String(name), avatar: avatar || null, subscriberText: String(subs) };
}

// Dig a "… subscribers" string out of a PageHeader's metadata rows (array of rows → parts → text).
function pageHeaderSubs(content) {
  try {
    const rows = content && content.metadata && content.metadata.metadata_rows;
    if (!Array.isArray(rows)) return '';
    for (const row of rows) {
      const parts = row && row.metadata_parts;
      if (!Array.isArray(parts)) continue;
      for (const p of parts) {
        const t = asText(p && p.text);
        if (/subscrib/i.test(t)) return t;
      }
    }
  } catch (_) {}
  return '';
}

// Channel header → { id, name, avatar, banner, subscriberText }. youtubei.js exposes either a
// classic C4TabbedHeader or the newer PageHeader; probe both shapes + metadata as a fallback.
function channelHeader(channel, channelId) {
  let name = '', avatar = null, banner = null, subscriberText = '';
  try {
    const h = channel.header || {};
    // PageHeader (the newer channel-page redesign) has no top-level `.title` — the display name is
    // nested at `content.title` (a DynamicTextView), whose *own* `.text` is the actual Text run. The
    // old `asText(h.title)` here was reading a field that never existed on PageHeader, so the name
    // silently fell through to '' for every channel using the new header shape (i.e. most channels
    // today) unless `channel.metadata.title` below happened to cover it.
    const pageHeaderTitle = h.content && h.content.title && h.content.title.text;
    name = asText(h.author && h.author.name) || asText(pageHeaderTitle) || '';
    avatar = pickLargestThumb(
      (h.author && h.author.thumbnails) ||
      (h.content && h.content.image && h.content.image.avatar && h.content.image.avatar.image) || [],
    );
    banner = pickLargestThumb(
      (h.banner && h.banner.thumbnails) ||
      (h.content && h.content.banner && h.content.banner.image) || [],
    );
    // C4TabbedHeader exposes a clean subscriber Text; PageHeader buries it in metadata rows.
    subscriberText = asText(h.subscribers) || asText(h.subscriber_count) ||
      pageHeaderSubs(h.content) || '';
  } catch (_) {}
  try {
    const m = channel.metadata || {};
    if (!name) name = asText(m.title) || '';
    if (!avatar) {
      avatar = pickLargestThumb(m.avatar || (m.thumbnail && m.thumbnail.thumbnails) || m.thumbnail || []);
    }
  } catch (_) {}
  return {
    id: channelId,
    name: String(name || ''),
    avatar: avatar || null,
    banner: banner || null,
    subscriberText: String(subscriberText || ''),
  };
}

function normalisePlaylist(node) {
  if (!node || typeof node !== 'object') return null;
  const id = node.id || node.content_id || node.playlist_id ||
    (node.endpoint && node.endpoint.payload && node.endpoint.payload.playlistId);
  if (!id || !/^(PL|UU|RD|OL|FL|LL|VL)/.test(String(id))) return null;
  const meta = node.metadata || {};
  const title = asText(node.title) || asText(meta.title) || '';
  if (!title) return null;
  const thumbs = node.thumbnails ||
    (node.thumbnail && (node.thumbnail.thumbnails || node.thumbnail)) ||
    (node.content_image && node.content_image.thumbnails) || [];
  const count = asText(node.video_count) || asText(node.video_count_short) ||
    asText(meta.video_count) || '';
  return {
    id: String(id).replace(/^VL/, ''),
    title: String(title),
    thumbnail: pickThumbnail(Array.isArray(thumbs) ? thumbs : []),
    videoCount: String(count),
  };
}

// Deep-walk a parsed channel/playlist tab collecting Playlist nodes (mirrors collectVideos).
function collectPlaylists(parsed) {
  const out = [];
  const seen = {};
  const visited = new Set();
  function walk(node, depth) {
    if (!node || typeof node !== 'object' || depth > 8 || visited.has(node)) return;
    visited.add(node);
    let id = null;
    try {
      id = node.id || node.content_id || node.playlist_id ||
        (node.endpoint && node.endpoint.payload && node.endpoint.payload.playlistId);
    } catch (_) {}
    if (id && /^(PL|UU|RD|OL|FL|LL|VL)/.test(String(id)) && (node.title != null || node.metadata != null)) {
      const p = normalisePlaylist(node);
      if (p && !seen[p.id]) { seen[p.id] = true; out.push(p); }
      return;
    }
    let keys;
    try { keys = Object.keys(node); } catch (_) { return; }
    for (const k of keys) {
      let val;
      try { val = node[k]; } catch (_) { continue; }
      if (Array.isArray(val)) { for (const x of val) walk(x, depth + 1); }
      else if (val && typeof val === 'object') walk(val, depth + 1);
    }
  }
  walk(parsed, 0);
  return out;
}

// ── Comments ────────────────────────────────────────────────────────────────
// youtubei.js carries the live Comments/CommentThread objects we need for paging (getContinuation)
// and replies (thread.getReplies). callAsync is stateless, so we cache them module-level, keyed to
// the current video+sort; rebuilt whenever either changes so "load more" can't append another
// video's page.
let commentsState = null; // { videoId, sort, comments, threadsById }

function commentsTotalText(comments) {
  try {
    const h = comments && comments.header;
    return asText(h && (h.comments_count || h.count || h.title)) || '';
  } catch (_) { return ''; }
}

// Normalise a CommentThread (top-level) or a bare Comment/CommentView (reply) into the flat shape the
// app consumes. Comment node shapes vary across youtubei.js versions (classic Comment vs CommentView),
// so probe both and fall back to asText.
function normaliseComment(node) {
  if (!node || typeof node !== 'object') return null;
  const c = node.comment || node; // CommentThread wraps the comment; replies may be bare
  if (!c || typeof c !== 'object') return null;
  const author = c.author || {};
  const id = c.comment_id || c.id || node.comment_id || node.id || '';
  const text = asText(c.content) || asText(c.content_text) || asText(c.text) || '';
  const avatar = pickLargestThumb(
    (author && author.thumbnails) ||
    (author && author.image) || [],
  );
  let replyCount = 0;
  if (typeof c.reply_count === 'number') replyCount = c.reply_count;
  else if (typeof node.reply_count === 'number') replyCount = node.reply_count;
  else {
    const rc = parseInt(asText(c.reply_count) || asText(node.reply_count) || '', 10);
    if (!isNaN(rc)) replyCount = rc;
  }
  const hasReplies = replyCount > 0 || !!node.has_replies || !!c.has_replies;
  return {
    id: String(id || ''),
    author: asText(author.name) || asText(c.author_name) || '',
    authorAvatar: avatar || null,
    authorIsVerified: !!(author.is_verified || author.is_verified_artist || c.is_verified),
    authorIsCreator: !!(c.author_is_channel_owner || c.is_channel_owner ||
      node.author_is_channel_owner),
    text: String(text),
    likeText: asText(c.like_count) || asText(c.vote_count) || '',
    publishedText: asText(c.published) || asText(c.published_time) || '',
    replyCount: Number(replyCount) || 0,
    hasReplies: hasReplies,
    isPinned: !!(c.is_pinned || node.is_pinned),
    isHearted: !!(c.is_hearted || c.is_liked || node.is_hearted),
  };
}

// Build a page payload from a youtubei.js Comments object, caching each thread by comment id so
// getCommentReplies can resolve it later. Requires commentsState to be set first.
function commentPageFrom(comments) {
  const threads = (comments && comments.contents) || [];
  const out = [];
  for (const t of threads) {
    const c = normaliseComment(t);
    if (!c) continue;
    if (c.id && commentsState) commentsState.threadsById[c.id] = t;
    out.push(c);
  }
  return {
    comments: out,
    hasContinuation: !!(comments && comments.has_continuation),
    totalText: commentsTotalText(comments),
    disabled: false,
  };
}

// ── Paginated feeds ─────────────────────────────────────────────────────────
// Continuation lives in the live youtubei.js objects, so (like comments) we hold them module-level.
// One holder per concurrent feed: the home "For you" round-robin, the search bar / category feed, and
// the player's related list. Switching query / category / video rebuilds the relevant one.
let homeSession = null;   // { sources:[{source,weight}...], seen:Set } — see pickWeightedRandom
let searchSession = null; // { obj, seen:Set }
let relatedSession = null;// { info, videoId, seen:Set }

// Parse a JSON array of ids passed from Kotlin (callAsync JSON-encodes args). Tolerant of null/garbage.
function parseIds(json) {
  if (!json) return [];
  try {
    const arr = JSON.parse(json);
    return Array.isArray(arr) ? arr.filter((x) => typeof x === 'string' && x) : [];
  } catch (_) { return []; }
}

// Parse a JSON object of { [key]: number } passed from Kotlin — phase 14's topicWeightsJson
// (HomeViewModel.topicWeights()). Tolerant of null/garbage, same spirit as [parseIds]; drops any
// non-finite-number entries rather than letting a stray NaN/string reach pickWeightedRandom.
function parseWeights(json) {
  if (!json) return {};
  try {
    const obj = JSON.parse(json);
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return {};
    const out = {};
    for (const [k, v] of Object.entries(obj)) {
      if (typeof k === 'string' && k && typeof v === 'number' && Number.isFinite(v)) out[k] = v;
    }
    return out;
  } catch (_) { return {}; }
}

// Extract NORMALISED videos from a feed/search/channel object, handling both shapes: search-style
// results expose flat `.videos`/`.results` node arrays; Feed-style objects need a deep walk.
function extractFeedVideos(obj) {
  if (!obj) return [];
  const flat = (Array.isArray(obj.videos) && obj.videos) ||
    (Array.isArray(obj.results) && obj.results) || null;
  if (flat && flat.length) return flat.map(normaliseVideo).filter(Boolean);
  return collectVideos(seedsFromFeed(obj));
}

// Every video pulled from a channel's own "Videos" tab is unambiguously BY that channel — [name]/
// [channelId] here come straight from the getChannel(channelId) call itself, not a guess. Always
// trust that known identity over whatever normaliseVideo's per-card heuristics happened to find,
// rather than only filling in when they came back blank. Two reasons a heuristic result can't be
// trusted here even when non-empty: (1) the byline is commonly OMITTED entirely on a channel's own
// page (redundant — you already know whose channel this is), so there's nothing genuine to find in
// the first place; (2) when the walk falls through past that absence it can land on the card's 3-dot
// menu, whose "Go to channel" action is itself a UC-browseId-linked node — structurally
// indistinguishable from a real byline run to a generic endpoint walk (authorTextIfClean now rejects
// known action labels by content, but this is the sturdier fix: for THIS call site we already know
// the answer with certainty, so there's no reason to trust a heuristic guess at all).
// Used both for fetchChannelVideosCore (channel page / Subscriptions tab) and here, for the home
// feed's per-subscription ('channel' kind) personalization source.
function fillMissingChannelAuthor(videos, name, channelId) {
  if (!name && !channelId) return videos;
  return videos.map((v) => ({
    ...v,
    author: name || v.author,
    channelId: channelId || v.channelId,
  }));
}

// Dedup a list of ALREADY-NORMALISED videos against a session's seen-set, optionally excluding an id.
function dedupById(videos, seen, excludeId) {
  const out = [];
  for (const v of (videos || [])) {
    if (!v || !v.id || v.id === excludeId || seen.has(v.id)) continue;
    seen.add(v.id);
    out.push(v);
  }
  return out;
}

// Normalise + dedup a batch of raw nodes against a session's seen-set, optionally excluding an id.
function freshVideos(nodes, seen, excludeId) {
  const out = [];
  for (const n of (nodes || [])) {
    const v = normaliseVideo(n);
    if (!v || !v.id || v.id === excludeId || seen.has(v.id)) continue;
    seen.add(v.id);
    out.push(v);
  }
  return out;
}

// Fisher-Yates shuffle — never mutates [arr]. Used to break up the rigid, deterministic order a
// single raw page (a topic search, a channel's uploads, …) comes back in, so even one source's
// contribution to the feed doesn't read as "YouTube's exact ranked list, sliced into chunks."
// Ranking (HomeRanker, Kotlin side) still fully applies afterward — this only randomizes the
// order among videos that end up tied on-score (most commonly the no-signal topic filler this
// was written for), same as the "keep relative input order" tie-break it now feeds into.
function shuffled(arr) {
  const out = (arr || []).slice();
  for (let i = out.length - 1; i > 0; i--) {
    const j = Math.floor(Math.random() * (i + 1));
    [out[i], out[j]] = [out[j], out[i]];
  }
  return out;
}

// Stamp every video in [videos] with [tag] (SearchVideo.sourceTag on the Kotlin side) — which
// home-feed source produced it, e.g. "topic:Movies" or "personalized:history". Purely a
// diversification signal for HomeRanker.diversify; never shown in the UI. Returns NEW objects
// (doesn't mutate the input) since normaliseVideo's output may still be referenced elsewhere
// (e.g. a 'topic' continuation's cached src.obj). Shuffles first (see [shuffled]) — every call
// site of this is a home-feed round-robin source; getRelated/getMoreSearch don't use this and
// keep YouTube's own ranked order untouched, which is deliberate.
function tagSource(videos, tag) {
  return shuffled(videos).map((v) => ({ ...v, sourceTag: tag }));
}

// A leaf is truly done only once BOTH it can't fetch any more raw pages AND whatever it already
// fetched is fully drained — a leaf can be [exhausted] (no more continuations) while still
// holding buffered [pending] content from its last raw pull that hasn't been handed out yet.
function leafSpent(s) {
  return !!s.exhausted && (!Array.isArray(s.pending) || s.pending.length === 0);
}

// Pull the next page of NORMALISED videos from one home-feed source, advancing/closing it in
// place. [cap] bounds how much a 'group' case hands out per call — see its own comment below for
// why that matters; leaf kinds ignore it and always return a full raw page (buffered by the
// caller via [pullHomeSourceCapped], not here).
async function pullHomeSource(it, src, cap) {
  try {
    // Generic meta-source wrapping N leaf sources of the SAME bucket (topics, subscribed channels,
    // history-related, search terms, or discovered channels — see buildLeafGroup/HOME_BUCKET_WEIGHTS
    // in getHomeFeed) behind ONE round-robin slot at the TOP level, so a bucket's overall "turn"
    // weight doesn't depend on how many individual leaves happen to be inside it (10 subscriptions
    // shouldn't out-weight 1 search term just by having more entries). Each call here picks ONE live
    // leaf's turn (weighted-random, see below), so repeated calls (via pullBlendedBatch) still rotate
    // through every leaf over time — just one leaf's turn per visit to this group, not all at once.
    //
    // Phase 14 (organic feed follow-up): two changes from the original plain sequential scan.
    // (1) Delegates to [pullHomeSourceCapped] on the CHOSEN leaf instead of calling [pullHomeSource]
    // on it directly, buffering any leftover on that LEAF's own [pending] rather than the group's.
    // Previously a leaf's raw page (often 20-30 items) came back whole and got buffered on the
    // GROUP, which then drained it [cap]-at-a-time over many separate turns before ever picking
    // again — so the round-robin only actually rotated once per full page, not once per turn. In
    // practice that let whichever leaf came up first (e.g. the first enabled topic) dominate the
    // feed for a long stretch, reading as "it always picks the first topic." Capping per-leaf here
    // means a DIFFERENT leaf is eligible on every single visit to this group.
    // (2) Picks WEIGHTED-RANDOMLY among live leaves (see [pickWeightedRandom]) instead of a fixed
    // `cursor`-advancing scan — a leaf's own optional `.weight` (default 1) lets a caller bias which
    // leaves get picked more often. Currently only topic leaves carry a non-default weight (see
    // `fetchTopicsBlend`'s `topicWeights` param, driven by `HomeRanker.topicAffinity` — topics you
    // actually watch more of win this pick more often); every other group (channels/history/search/
    // discover/Movies-variants) has no such signal, so its leaves stay equal-weight, i.e. uniform
    // random — itself an improvement over the old fixed sequential order for those too.
    if (src.kind === 'group') {
      const subs = src.subs;
      if (!Array.isArray(subs) || subs.length === 0) { src.exhausted = true; return []; }
      // Spread the turn's [cap] across leaves by re-picking a (weighted-random) leaf PER VIDEO
      // instead of handing the whole turn to one leaf. Handing one leaf the full cap meant a
      // topics-bucket turn returned `cap` (4) videos from a SINGLE category, which no downstream
      // diversify pass can fully unclump — it read as "every third or fourth card is the same
      // category, four times in a row" in the feed. Per-video picking keeps each turn itself a
      // blend (weights still bias WHICH leaves get picked, exactly as before, just at video
      // granularity rather than turn granularity), at no extra network cost: every leaf buffers
      // its full raw page on its own [pending], so a 1-video pull is almost always a buffer read.
      const out = [];
      let attempts = 0;
      while (out.length < cap && attempts < cap * 3) {
        attempts++;
        const live = subs.filter((s) => !leafSpent(s));
        if (live.length === 0) break;
        const entries = live.map((s) => ({
          source: s,
          weight: (typeof s.weight === 'number' && s.weight > 0) ? s.weight : 1,
        }));
        const pick = pickWeightedRandom(entries);
        const vids = await pullHomeSourceCapped(it, pick.source, 1);
        for (const v of vids) out.push(v);
      }
      if (subs.every(leafSpent)) src.exhausted = true;
      return out;
    }
    if (src.kind === 'topic') {
      if (!src.obj || !src.obj.has_continuation) { src.exhausted = true; return []; }
      src.obj = await src.obj.getContinuation();
      return tagSource(extractFeedVideos(src.obj), `topic:${src.term}`);
    }
    if (src.kind === 'channel' || src.kind === 'discover') {
      const tag = src.kind === 'channel' ? 'personalized:sub' : 'personalized:discover';
      if (src.obj == null) {
        const channel = await it.getChannel(src.id);
        try {
          src.name = channelHeader(channel, src.id).name ||
            asText(channel.metadata && channel.metadata.title) || '';
        } catch (_) { /* best effort */ }
        src.obj = (typeof channel.getVideos === 'function') ? await channel.getVideos() : channel;
        return tagSource(fillMissingChannelAuthor(extractFeedVideos(src.obj), src.name, src.id), tag);
      }
      if (!src.obj.has_continuation) { src.exhausted = true; return []; }
      src.obj = await src.obj.getContinuation();
      return tagSource(fillMissingChannelAuthor(extractFeedVideos(src.obj), src.name, src.id), tag);
    }
    if (src.kind === 'search') {
      if (src.obj == null) {
        const res = await it.search(src.term, { type: 'video' });
        src.obj = res;
        return tagSource(extractFeedVideos(res), 'personalized:search');
      }
      if (!src.obj.has_continuation) { src.exhausted = true; return []; }
      src.obj = await src.obj.getContinuation();
      return tagSource(extractFeedVideos(src.obj), 'personalized:search');
    }
    // related-of-history seed
    if (src.info == null) {
      src.info = await it.getInfo(src.id);
      const videos = (src.info.watch_next_feed || src.info.related_videos || []).map(normaliseVideo).filter(Boolean);
      return tagSource(videos, 'personalized:history');
    }
    await src.info.getWatchNextContinuation(); // throws when exhausted
    return tagSource((src.info.watch_next_feed || []).map(normaliseVideo).filter(Boolean), 'personalized:history');
  } catch (e) {
    src.exhausted = true;
    return [];
  }
}

// ── Phase 11 (HOME_FEED_PERSONALIZATION_PLAN.md): weighted bucket blending ─────────────────────
// Replaces the old MAX_P1_CHANNELS/MAX_P1_HISTORY/MAX_P1_SEARCH_TERMS/MAX_PICKS_PER_P1_SOURCE
// page-1-only special-casing. Every home-feed candidate now comes from exactly one of 5 buckets —
// topics / subscribed channels / history-related / search terms / discovered channels — each
// wrapped as ONE 'group' round-robin source (see pullHomeSource's 'group' case) so a bucket's
// overall turn share doesn't depend on how many individual leaves (channels, search terms, …) it
// happens to contain. HOME_BUCKET_WEIGHTS gives each bucket a relative share of turns instead of
// a flat 1-of-N: subscriptions get a deliberate bump over discover — subscribing is an explicit,
// high-confidence signal, while "discover" (phase 7) is a speculative, one-hop-removed inference
// that shouldn't compete with it as an equal. Weights sum to 20 for clean round percentages:
// topics 20%, channels 30%, history 20%, search 15%, discover 15%.
const HOME_BUCKET_WEIGHTS = { topics: 4, channels: 6, history: 4, search: 3, discover: 3 };

// Videos pulled per turn from whichever bucket is up (see pullHomeSourceCapped). Deliberately
// small relative to a raw continuation page (often 20-30 items) — otherwise a single turn,
// including page 1's very first turn, could be entirely dominated by whichever bucket happened to
// go first, which is exactly the "3 movie cards then 3 news cards"-style clumping this phase
// exists to fix.
const PER_TURN_PULL_CAP = 4;

// Both page 1 (getHomeFeed) and every later page (getMoreHomeFeed) target the SAME batch size —
// deliberately equal, unlike the mechanism's one remaining REAL asymmetry (see getHomeFeed's doc):
// page 1 warms all 5 buckets CONCURRENTLY before this first pull, since it's the one moment the
// user is blocked on a loading spinner with nothing to look at yet; getMoreHomeFeed doesn't, since
// by then the user already has a full screen of content and isn't blocked waiting.
const HOME_BATCH_TARGET = 12;

// One weighted bucket's 'group' source: N individual leaf sources (built from [entries] via
// [toLeaf]), weighted-randomly picked among internally by pullHomeSource's 'group' case. Marked
// exhausted up-front when there are no entries at all, so an empty bucket (e.g. no discovered
// channels yet) is skipped by pullBlendedBatch's picker without ever being pulled.
function buildLeafGroup(entries, toLeaf) {
  return { kind: 'group', subs: entries.map(toLeaf), exhausted: entries.length === 0, pending: [] };
}

// Weighted-random pick among [entries] (each `{source, weight}`, already filtered to non-
// exhausted) — returns the chosen entry, or null if [entries] is empty. Replaces the old
// deterministic interleaveWeighted repeating pattern (see pullBlendedBatch's doc): a fixed
// pattern like topic,channel,history,search,discover,topic,channel,... is technically "fair" by
// weight but reads as mechanical/predictable turn to turn. Picking randomly, proportional to
// weight, keeps the same LONG-RUN bucket mix (still topics ~20%, channels ~30%, …) without a
// rigid repeating order.
function pickWeightedRandom(entries) {
  const total = entries.reduce((sum, e) => sum + e.weight, 0);
  if (total <= 0) return null;
  let r = Math.random() * total;
  for (const e of entries) {
    r -= e.weight;
    if (r <= 0) return e;
  }
  return entries[entries.length - 1];
}

// Upper bound on how many single-source pulls one pullBlendedBatch call will attempt before
// giving up even if [target] isn't reached — a safety valve for the weighted-random picker (the
// old deterministic scan had a natural bound of one pass over session.sources; random picking
// doesn't, so this plays the same role). Generous relative to the 5 buckets so it essentially
// only ever matters once sources are genuinely running dry.
const MAX_BLEND_ATTEMPTS = 40;

// Ensure [src.pending] has content ready, fetching one page if it's currently empty — WITHOUT
// draining any of it. Used to pre-warm multiple bucket sources CONCURRENTLY before getHomeFeed's
// page-1 pull needs to sequentially draw from them (see getHomeFeed) — a no-op if already warm or
// exhausted, so calling it again later (e.g. from pullHomeSourceCapped) costs nothing extra.
async function warmHomeSource(it, src, cap) {
  if (!Array.isArray(src.pending)) src.pending = [];
  if (src.pending.length === 0 && !src.exhausted) {
    src.pending = await pullHomeSource(it, src, cap);
  }
}

// Pull up to [cap] fresh videos from [src] (warming it first if needed) — extra videos beyond cap
// stay buffered in src.pending rather than being discarded, served on src's NEXT turn before
// pulling another page. Keeps every turn's output bounded (see pullBlendedBatch) without wasting
// whatever a single page fetch already returned. [cap] is also threaded down into [warmHomeSource]
// → [pullHomeSource] so a 'group' source's OWN leaf picks are capped the same way — see
// pullHomeSource's 'group' case for why that's the actual fix, not just this top-level cap.
async function pullHomeSourceCapped(it, src, cap) {
  await warmHomeSource(it, src, cap);
  const take = src.pending.slice(0, cap);
  src.pending = src.pending.slice(cap);
  return take;
}

// The engine behind BOTH getHomeFeed's page 1 and getMoreHomeFeed's later pages — picks a bucket
// each turn WEIGHTED-RANDOMLY (see pickWeightedRandom) from [session.sources] (the 5 buckets from
// getHomeFeed, each `{source, weight}`), pulling AT MOST [cap] videos per turn
// (pullHomeSourceCapped) rather than a turn's entire raw page, until [target] fresh videos are
// collected or every bucket is exhausted. Page 1 being just "the first call into this" instead of
// its own bespoke personalized-picks-first assembly is what removes the asymmetry between page 1
// and every later page.
async function pullBlendedBatch(it, session, target, cap) {
  const out = [];
  let attempts = 0;
  while (out.length < target && attempts < MAX_BLEND_ATTEMPTS) {
    const live = session.sources.filter((e) => !e.source.exhausted);
    if (live.length === 0) break; // all exhausted
    const pick = pickWeightedRandom(live);
    attempts++;
    const vids = await pullHomeSourceCapped(it, pick.source, cap);
    const excl = pick.source.kind === 'related' ? pick.source.id : undefined;
    for (const v of dedupById(vids, session.seen, excl)) out.push(v);
  }
  return out;
}

// Baseline filler for page 1 (and the round-robin) when there's no personalization signal to lean
// on yet — most obviously a brand-new install, but this also just fills out whatever's left of page
// 1 for an existing user once their own subscription/history/search sources run out. This used to be
// a single generic 'trending' feed/search: unrepresentative (whatever's globally popular skews hard
// to a handful of genres) and, since HomeRanker has no way to tell a "trending" pick apart from any
// other no-signal pick, it also meant every fallback card got stamped with the same "Trending" label
// — noisy on a fresh install where EVERY card was still a fallback card. Blending across the SAME
// category terms already exposed as the home screen's own filter chips (see HomeViewModel.categories)
// means a new user's very first feed already samples multiple genres instead of one undifferentiated
// bucket, and reuses a call this app already makes for those chips (`it.search(term, {type:'video'})`)
// instead of adding a new code path. This app has no sign-in flow (createAnonSession never carries an
// account) and youtubei.js's it.getHomeFeed() (POST browse FEwhat_to_watch) ALWAYS resolves to a
// "sign in" nudge with zero videos for a logged-out session — confirmed both in youtubei.js's own
// behavior and in production logs — so plain topic searches (which DO work anonymously) are the
// right primitive here, same as they already are for the category chips.
// Default/fallback topic list — mirrors Kotlin's HomeTopics.ALL (youtube/models/HomeTopics.kt).
// Only used if getHomeFeed is ever called without an enabledTopicsJson (e.g. an older cached
// bundle mismatch); the real source of truth for "which topics does THIS user want" is whatever
// list Kotlin passes in, driven by LibraryStore.disabledTopics.
const HOME_TOPIC_TERMS = [
  'Music', 'Gaming', 'News', 'Live', 'Movies',
  'Science', 'Technology', 'DIY', 'Nature', 'Cooking', 'Sports', 'Travel', 'Comedy', 'History',
];

// Search parameters per home-feed topic. Most topics just search their own label as a plain
// video query. "Movies" is special-cased below (see MOVIE_TOPIC_VARIANTS) since it needs several
// concurrent sub-queries, not one. "News" is dynamically localised when a region display name is
// available (see configureRegion/regionName) — a bare "News" query is globally-popular-ranked and
// routinely surfaces channels with no relevance to the user's actual country; folding the country
// name into the query TEXT itself is a much stronger signal than gl/hl alone, which Innertube only
// treats as a soft ranking hint easily outranked by big international channels.
const HOME_TOPIC_QUERY = {
  Movies: { term: 'movie trailer', filters: { type: 'video', duration: 'under_three_mins' } },
};
function homeTopicQuery(topic) {
  if (topic === 'News' && regionName) {
    return { term: `${regionName} news`, filters: { type: 'video' } };
  }
  return HOME_TOPIC_QUERY[topic] || { term: topic, filters: { type: 'video' } };
}

// "Movies" sub-queries, fetched CONCURRENTLY and blended together (see fetchTopicsBlend) instead
// of one single query. Nothing in youtubei.js's SearchFilters can express "trailer OR bloopers OR
// interview" — duration is a single enum value (see homeTopicQuery's old single-query comment,
// still true) and there's no boolean/OR support in the search text either — so the only way to get
// broader-than-trailers movie content without regressing back to full 1-3 hour uploads is to run
// several complementary, individually-narrow queries side by side. Two duration buckets are used
// deliberately: teasers/bloopers are almost always under 3 minutes, while featurettes/interviews
// routinely run 5-15 minutes and would mostly get filtered out by the tighter bucket.
const MOVIE_TOPIC_VARIANTS = [
  { term: 'movie trailer', filters: { type: 'video', duration: 'under_three_mins' } },
  { term: 'movie featurette', filters: { type: 'video', duration: 'three_to_twenty_mins' } },
  { term: 'movie bloopers', filters: { type: 'video', duration: 'under_three_mins' } },
  { term: 'movie cast interview', filters: { type: 'video', duration: 'three_to_twenty_mins' } },
];

// Fetches ONE topic's leaf source(s). Most topics are a single query → single 'topic' leaf.
// "Movies" fans out into MOVIE_TOPIC_VARIANTS, fetched concurrently, and comes back as a nested
// 'group' leaf (subs = one 'topic' leaf per variant) instead — pullHomeSource's 'group' case is
// already fully generic (see its own doc comment) and needs zero changes to round-robin through
// these variants exactly like it round-robins the top-level buckets themselves, so Movies content
// naturally interleaves trailers/featurettes/bloopers/interviews turn-by-turn instead of dumping
// one variant's whole page before moving to the next. Every leaf is tagged `topic:Movies`
// (the topic label, not the underlying query text) so settings/diversify still see one topic.
// [weight] (phase 14, HOME_FEED_PERSONALIZATION_PLAN.md) — this topic's HomeRanker.topicAffinity
// -derived round-robin weight (see fetchTopicsBlend/getHomeFeed's `topicWeights` param), or
// undefined for "no signal, use the default" — carried through onto whichever leaf/group object
// ends up representing this topic inside topicGroup, read by pullHomeSource's 'group' case.
async function fetchTopicSource(it, term, weight) {
  if (term === 'Movies') {
    const variantResults = await Promise.all(MOVIE_TOPIC_VARIANTS.map(async ({ term: queryTerm, filters }) => {
      try {
        const res = await it.search(queryTerm, filters);
        return { obj: res, videos: tagSource(extractFeedVideos(res), 'topic:Movies') };
      } catch (e) {
        console.warn(`[__yt] Movies variant "${queryTerm}" failed: ` + ((e && e.message) || e));
        return { obj: null, videos: [] };
      }
    }));
    const subs = variantResults
      .filter((r) => r.obj)
      .map((r) => ({ kind: 'topic', term: 'Movies', obj: r.obj, pending: r.videos, exhausted: false }));
    if (subs.length === 0) return { term, obj: null, videos: [] };
    // obj/videos here are only used by the OLD single-leaf shape's callers — fetchTopicsBlend below
    // special-cases group=true and ignores them, they're just kept for shape-consistency/logging.
    // [weight] lands on the GROUP itself — Movies as a WHOLE competes with other topics for
    // topicGroup's picks; its 4 internal variants stay equal-weight, no differential signal between
    // trailers/featurettes/bloopers/interviews.
    return { term, obj: true, videos: [], group: { kind: 'group', subs, exhausted: false, pending: [], weight } };
  }
  try {
    const { term: queryTerm, filters } = homeTopicQuery(term);
    const res = await it.search(queryTerm, filters);
    // Tag with the TOPIC label (term), not the underlying query text (queryTerm) — "News" searched
    // as "Netherlands news" should still show up as topic:News to the settings sheet/diversify pass.
    return { term, obj: res, videos: tagSource(extractFeedVideos(res), `topic:${term}`), weight };
  } catch (e) {
    console.warn(`[__yt] topics blend "${term}" failed: ` + ((e && e.message) || e));
    return { term, obj: null, videos: [] };
  }
}

// Fetches every enabled topic CONCURRENTLY and returns one leaf source per topic that actually
// returned something, ready to be wrapped into the topics bucket's 'group' source (see
// getHomeFeed). Each leaf's [pending] is pre-seeded with its own already-fetched first page (see
// pullHomeSourceCapped/warmHomeSource) — the search that already ran here is never re-fetched or
// discarded, whether that leaf's first turn happens during page 1's concurrent warm-up or a much
// later getMoreHomeFeed call. (Used to also return a pre-interleaved `videos` array for page 1 to
// consume directly — no longer needed now that page 1 draws from the same pullBlendedBatch engine
// getMoreHomeFeed uses, see phase 11 of HOME_FEED_PERSONALIZATION_PLAN.md.)
//
// [topicWeights] (phase 14) — a plain `{ [topic]: number }` object from Kotlin's
// HomeViewModel.topicWeights(), itself derived from HomeRanker.topicAffinity(). A topic missing
// from this object (or an entirely absent/empty object) gets `undefined`, which
// pullHomeSource's 'group' case treats as "default weight 1" — same as today's uniform behavior.
async function fetchTopicsBlend(it, topics, topicWeights) {
  const enabled = Array.isArray(topics) && topics.length > 0 ? topics : HOME_TOPIC_TERMS;
  const weights = (topicWeights && typeof topicWeights === 'object') ? topicWeights : {};
  // Shuffled per session so the topic that ends up first in the round-robin (and so gets the
  // earliest turns) isn't always the same one — HomeTopics.ALL/the settings sheet's order is
  // fixed, so without this, whichever enabled topic happens to sort first (e.g. "Gaming" if
  // "Music" is off) would be first EVERY session, not just this one. Promise.all preserves array
  // order, so shuffling [list] here also shuffles the resulting `sources` order below. (Turn order
  // itself is now weighted-random regardless — see pullHomeSource's 'group' case — but this still
  // matters for [diversify]'s "keep relative input order" tie-break on an untouched topic.)
  const list = shuffled(enabled);
  const results = await Promise.all(list.map((term) => fetchTopicSource(it, term, weights[term])));
  const sources = results
    .filter((r) => r.obj)
    .map((r) => r.group || {
      kind: 'topic', term: r.term, obj: r.obj, pending: r.videos, exhausted: false, weight: r.weight,
    });
  return { sources };
}

// Core of getChannelVideos, factored out so getChannelVideosBatch can run it CONCURRENTLY across
// many channels (Promise.all) instead of the caller looping one channel at a time.
async function fetchChannelVideosCore(it, channelId) {
  let videos = [];
  let name = '';
  try {
    const channel = await it.getChannel(channelId);
    try {
      // Reuse the same header/metadata probing as getChannelInfo (channelHeader()) instead of a
      // narrower duplicate — this already knows how to read both the classic C4TabbedHeader shape
      // and the newer PageHeader shape, plus falls back to metadata.title.
      name = channelHeader(channel, channelId).name ||
        asText(channel.metadata && channel.metadata.title) || '';
    } catch (_) {}
    let tab = channel;
    try {
      if (typeof channel.getVideos === 'function') tab = await channel.getVideos();
    } catch (_) { tab = channel; }
    videos = collectVideos(seedsFromFeed(tab));
    if (videos.length === 0) videos = collectVideos(tab);
  } catch (e) {
    console.warn(`[__yt] getChannelVideos(${channelId}) failed: ` + ((e && e.message) || e));
  }
  // See fillMissingChannelAuthor's comment: every video here is unambiguously by [channelId], so
  // always trust that known identity over normaliseVideo's per-card heuristics rather than only
  // patching blanks.
  return fillMissingChannelAuthor(videos.filter((v) => v.id !== channelId), name, channelId);
}

// ── Phase 7 (stretch, HOME_FEED_PERSONALIZATION_PLAN.md): two-hop channel discovery ─────────────
// A "similar channels" signal built ONLY from calls the bridge already makes (getChannel + getInfo)
// — no external data, no ML. For a handful of subscribed channels, look at what shows up in the
// RELATED videos of their own recent uploads; a channelId that recurs across the related lists of
// several DIFFERENT subscribed channels is plausibly similar to the user's taste even though it was
// never subscribed to directly — a poor man's collaborative filtering. This is materially more
// expensive than the other bridge calls (up to DISCOVER_VIDEOS_PER_SEED extra getInfo calls per
// seed channel, run concurrently across seeds, on top of the getChannel to find those uploads) —
// callers should run it rarely and in the background (see LibraryStore.shouldRunDiscovery on the
// Kotlin side), never as part of the fast home-feed page-1 path.
const DISCOVER_VIDEOS_PER_SEED = 2;   // recent uploads sampled per seed channel
const MAX_DISCOVER_SEEDS = 6;         // subscribed channels sampled per run (bounds total calls)
const MIN_DISCOVER_RECURRENCE = 2;    // must recur under >=N different seed channels to count
const MAX_DISCOVER_RESULTS = 15;

// channelIds (+ names) appearing in the related/watch-next feed of [videoId], excluding the seed
// channel itself. Reuses normaliseVideo (author/channelId are already extracted there) instead of
// a separate getChannelInfo round trip per hit.
async function relatedChannelsForVideo(it, videoId, excludeChannelId) {
  try {
    const info = await it.getInfo(videoId);
    const wn = info.watch_next_feed || info.related_videos || [];
    return wn
      .map(normaliseVideo)
      .filter((v) => v && v.channelId && v.channelId !== excludeChannelId)
      .map((v) => ({ id: v.channelId, name: v.author }));
  } catch (e) {
    return [];
  }
}

// One seed channel's contribution to the discovery pass: its most recent DISCOVER_VIDEOS_PER_SEED
// uploads' related-channel hits, flattened.
async function discoverFromChannel(it, channelId) {
  try {
    const videos = await fetchChannelVideosCore(it, channelId);
    const sample = videos.slice(0, DISCOVER_VIDEOS_PER_SEED);
    const perVideo = await Promise.all(sample.map((v) => relatedChannelsForVideo(it, v.id, channelId)));
    return perVideo.flat();
  } catch (e) {
    return [];
  }
}

// ── Bridge surface ──────────────────────────────────────────────────────────
const __yt = {
  /**
   * Set the device region (ISO 3166-1 alpha-2, e.g. "NL"), language (ISO 639-1, e.g. "nl"), and
   * English country display name (e.g. "Netherlands") that every future Innertube session /
   * topic-blend query should use, in place of youtubei.js's hardcoded 'US'/'en' defaults. Called
   * once by QuickJsRuntime.initialize() right after the bundle is evaluated — i.e. always before
   * the first real bridge call (search/getHomeFeed/warmup/…) can possibly run, so it always lands
   * before createAnonSession()/createAuthedSession()'s first Innertube.create(). Plain sync setter,
   * not async — there's no session/network work here, just recording the values for regionOptions()
   * and homeTopicQuery() to pick up whenever they're next used. Safe to call with junk/empty
   * strings (ignored, keeping whatever — or nothing — was set before).
   */
  /**
   * Tell the runtime which identity each PO token binds to. Mirrors `PoTokenBinding.SWAP` on the
   * Kotlin side, which is the single source of truth — this is a push, not a second setting. Called
   * from QuickJsRuntime.initialize() alongside configureRegion, i.e. before any session exists.
   *
   *   false → /player ← visitorData (session bound), GVS ← videoId (content bound)   [today]
   *   true  → /player ← videoId (content bound),     GVS ← visitorData (session bound)
   *
   * When true the /player pot is per-video, so the authed session can no longer be shared across
   * videos — createAuthedSession rebuilds it whenever the binding id changes.
   */
  configurePoTokenBinding(swap) {
    swapPoTokenBindings = !!swap;
    console.log('[__yt] configurePoTokenBinding → swap=' + swapPoTokenBindings
      + (swapPoTokenBindings
        ? ' (/player←videoId, gvs←visitorData)'
        : ' (/player←visitorData, gvs←videoId)'));
  },

  /**
   * Should the AUTHED session run under the WebView page's real visitorData instead of the locally
   * invented one? Mirrors `SabrSessionIdentity.USE_PAGE_IDENTITY`, which is the single source of
   * truth; this is a push, not a second setting.
   *
   * Only the authed session is affected. The anon session (search, home feed, channels) keeps its
   * local identity, because it is created ~7 s before the WebView page finishes loading and blocking
   * it would delay the first screen by that much. See SabrSessionIdentity for the timing evidence.
   */
  configureSessionIdentity(usePage) {
    usePageIdentity = !!usePage;
    console.log('[__yt] configureSessionIdentity → usePageIdentity=' + usePageIdentity);
  },

  configureRegion(location, lang, name) {
    if (typeof location === 'string' && /^[A-Za-z]{2}$/.test(location)) {
      regionLocation = location.toUpperCase();
    }
    if (typeof lang === 'string' && /^[A-Za-z]{2}$/.test(lang)) {
      regionLang = lang.toLowerCase();
    }
    // Free-text display name (not a code), so no format check beyond "non-empty string" — worst
    // case a garbage value just makes the News query text a bit odd, never breaks anything.
    if (typeof name === 'string' && name.trim().length > 0) {
      regionName = name.trim();
    }
    console.log(`[__yt] configureRegion → location=${regionLocation}, lang=${regionLang}, name=${regionName}`);
  },

  /**
   * Autocomplete suggestions for the search box, as the user types. Uses youtubei.js's built-in
   * getSearchSuggestions (YouTube's own /complete/search endpoint) — no session bootstrap beyond
   * the already-warm anonymous session, so this is a single fast HTTP round trip per keystroke
   * (the caller is expected to debounce). Returns [] rather than throwing on a blank query, a
   * too-short query, or any fetch failure so a flaky suggestion call never surfaces as an error.
   */
  async getSearchSuggestions(query) {
    const q = (query || '').trim();
    if (q.length < 2) return { suggestions: [] };
    try {
      const it = await createAnonSession();
      const suggestions = await it.getSearchSuggestions(q);
      return { suggestions: Array.isArray(suggestions) ? suggestions.slice(0, 8) : [] };
    } catch (e) {
      console.warn('[__yt] getSearchSuggestions failed: ' + ((e && e.message) || e));
      return { suggestions: [] };
    }
  },

  async search(query) {
    const it = await createAnonSession();
    console.log(`[__yt] search("${query}")`);
    // A single, unfiltered search — YouTube's normal blended results, videos and any close-match
    // channel card together in its actual ranked order. Previously this ran a SEPARATE type:'channel'
    // query and always rendered those results above the video list regardless of real relevance;
    // now the channel's [position] records how many videos preceded it in the real ranking, so the
    // UI can insert the card at that exact spot instead of pinning it to the top.
    const res = await it.search(query);
    const seen = new Set();
    const seenC = {};
    const items = [];
    const channels = [];
    const nodes = res.results || res.videos || [];
    for (const n of nodes) {
      const ch = normaliseChannel(n);
      if (ch) {
        if (!seenC[ch.id]) { seenC[ch.id] = true; channels.push({ ...ch, position: items.length }); }
        continue;
      }
      const v = normaliseVideo(n);
      if (!v || !v.id || seen.has(v.id)) continue;
      seen.add(v.id);
      items.push(v);
    }
    searchSession = { obj: res, seen };
    console.log(`[__yt] search → ${items.length} videos, ${channels.length} channels`);
    return { videos: items, channels: channels.slice(0, 3), hasMore: !!res.has_continuation };
  },

  /** Next page of the current search / category feed (continuation). */
  async getMoreSearch() {
    const s = searchSession;
    if (!s || !s.obj || !s.obj.has_continuation || typeof s.obj.getContinuation !== 'function') {
      return { videos: [], hasMore: false };
    }
    try {
      s.obj = await s.obj.getContinuation();
      const videos = freshVideos(s.obj.videos || s.obj.results || [], s.seen);
      console.log(`[__yt] getMoreSearch → +${videos.length} (more=${!!s.obj.has_continuation})`);
      return { videos, hasMore: !!s.obj.has_continuation };
    } catch (e) {
      console.warn('[__yt] getMoreSearch failed: ' + ((e && e.message) || e));
      return { videos: [], hasMore: false };
    }
  },

  /**
   * Home feed page 1 (phase 11, HOME_FEED_PERSONALIZATION_PLAN.md) — now just the FIRST call into
   * [pullBlendedBatch] against a freshly-built session, the SAME engine [getMoreHomeFeed] keeps
   * drawing from for every later page. There is no more separate "personalized picks first, topic
   * blend fills the rest" assembly: that used to mean a heavy subscriber's personalized picks
   * could fill all 12 page-1 slots and push topic content out of page 1 entirely. Every candidate
   * now comes from one of 5 WEIGHTED buckets (topics/channels/history/search/discover — see
   * [HOME_BUCKET_WEIGHTS]), and page 1 draws from them exactly like any later page would — just
   * with every bucket's first turn warmed CONCURRENTLY below so first paint stays gated by the
   * slowest SINGLE call across all 5 buckets, not their sum.
   *
   * [historyIdsJson]/[channelIdsJson]/[searchTermsJson]/[discoverChannelIdsJson] are JSON arrays —
   * already capped on the Kotlin side (LibraryStore-driven: subscriptions, watch history, recent
   * search terms, discovered channels) — one leaf source per entry, grouped into its bucket.
   * [enabledTopicsJson] is the subset of topics (see HOME_TOPIC_TERMS / Kotlin's HomeTopics.ALL)
   * the user hasn't switched off in the topic-settings sheet. The logged-out home feed is just a
   * "sign in" nudge, so we never call it — the topics blend is the actual exploratory filler for
   * anonymous sessions, same as it always was for the category chips.
   *
   * [topicWeightsJson] (phase 14, HOME_FEED_PERSONALIZATION_PLAN.md) — a JSON object
   * `{ [topic]: number }` from `HomeViewModel.topicWeights()`, biasing how often each enabled
   * topic wins its turn in the topic bucket's round-robin (see fetchTopicsBlend/pullHomeSource's
   * 'group' case) toward whichever topics the user actually watches more of. Missing/blank/garbage
   * → every topic defaults to equal weight, same as before this phase.
   */
  async getHomeFeed(
    historyIdsJson, channelIdsJson, searchTermsJson, discoverChannelIdsJson, enabledTopicsJson, topicWeightsJson,
  ) {
    const it = await createAnonSession();
    const historyIds = parseIds(historyIdsJson);
    const channelIds = parseIds(channelIdsJson);
    const searchTerms = parseIds(searchTermsJson);
    const discoverChannelIds = parseIds(discoverChannelIdsJson);
    const enabledTopics = parseIds(enabledTopicsJson);
    const topicWeights = parseWeights(topicWeightsJson);

    // One 'group' bucket per kind, each wrapping N individual leaf sources — see buildLeafGroup.
    const channelGroup = buildLeafGroup(channelIds, (id) => ({ kind: 'channel', id, obj: null, exhausted: false }));
    const historyGroup = buildLeafGroup(historyIds, (id) => ({ kind: 'related', id, info: null, exhausted: false }));
    const searchGroup = buildLeafGroup(searchTerms, (term) => ({ kind: 'search', term, obj: null, exhausted: false }));
    const discoverGroup = buildLeafGroup(discoverChannelIds, (id) => ({ kind: 'discover', id, obj: null, exhausted: false }));

    // Fetch the topic blend AND warm each of the other 4 buckets' first turn ALL CONCURRENTLY —
    // page 1 is gated by the slowest of these 5, not their sum (generalizes the latency shape the
    // old p1-specific Promise.all had for 3 personalized sources to all 5 buckets). Each warm-up
    // is capped the same as every later turn (PER_TURN_PULL_CAP) so the FIRST turn doesn't behave
    // any differently from turn two onward — see pullHomeSource's 'group' case doc.
    const [topics] = await Promise.all([
      fetchTopicsBlend(it, enabledTopics, topicWeights),
      warmHomeSource(it, channelGroup, PER_TURN_PULL_CAP).catch(() => {}),
      warmHomeSource(it, historyGroup, PER_TURN_PULL_CAP).catch(() => {}),
      warmHomeSource(it, searchGroup, PER_TURN_PULL_CAP).catch(() => {}),
      warmHomeSource(it, discoverGroup, PER_TURN_PULL_CAP).catch(() => {}),
    ]);
    const topicGroup = topics.sources.length > 0
      ? { kind: 'group', subs: topics.sources, exhausted: false, pending: [] }
      : null;

    // Each bucket kept as ONE entry (not expanded into a repeating pattern) — pullBlendedBatch
    // now picks among these weighted-randomly every turn instead of walking a fixed interleaved
    // order (see pickWeightedRandom).
    const weighted = [
      topicGroup && { source: topicGroup, weight: HOME_BUCKET_WEIGHTS.topics },
      channelGroup.subs.length > 0 && { source: channelGroup, weight: HOME_BUCKET_WEIGHTS.channels },
      historyGroup.subs.length > 0 && { source: historyGroup, weight: HOME_BUCKET_WEIGHTS.history },
      searchGroup.subs.length > 0 && { source: searchGroup, weight: HOME_BUCKET_WEIGHTS.search },
      discoverGroup.subs.length > 0 && { source: discoverGroup, weight: HOME_BUCKET_WEIGHTS.discover },
    ].filter(Boolean);

    homeSession = { sources: weighted, seen: new Set() };

    // All 5 buckets are already warm (or empty/exhausted) from the Promise.all above, so this
    // first pullBlendedBatch call — despite being sequential in shape — never actually blocks on
    // network I/O until a bucket's pre-warmed pending buffer runs dry.
    const page = await pullBlendedBatch(it, homeSession, HOME_BATCH_TARGET, PER_TURN_PULL_CAP);
    console.log(
      `[__yt] getHomeFeed → ${page.length} (buckets=${weighted.length})`,
    );
    return { videos: page, hasMore: weighted.length > 0 };
  },

  /**
   * Next chunk of the home feed (phase 11): the SAME [pullBlendedBatch] engine page 1 uses,
   * against the session [getHomeFeed] already built — weighted-randomly picks among the 5
   * buckets each turn (see pickWeightedRandom), collecting ~10 fresh deduped videos capped per
   * turn. Personalization persists per page.
   */
  async getMoreHomeFeed() {
    const s = homeSession;
    if (!s || s.sources.length === 0) return { videos: [], hasMore: false };
    const it = await createAnonSession();
    const out = await pullBlendedBatch(it, s, HOME_BATCH_TARGET, PER_TURN_PULL_CAP);
    const hasMore = s.sources.some((x) => !x.source.exhausted);
    console.log(`[__yt] getMoreHomeFeed → +${out.length} (more=${hasMore})`);
    return { videos: out, hasMore };
  },

  /** Related / watch-next videos for [videoId] (page 1) + a session for getMoreRelated. */
  async getRelated(videoId) {
    const it = await createAnonSession();
    console.log(`[__yt] getRelated("${videoId}")`);
    const seen = new Set();
    let videos = [];
    let info = null;
    try {
      info = await it.getInfo(videoId);
      const wn = info.watch_next_feed || info.related_videos || [];
      videos = freshVideos(wn, seen, videoId);
      if (videos.length === 0) videos = freshVideos(collectVideos(info), seen, videoId);
    } catch (e) {
      console.warn('[__yt] getRelated failed: ' + ((e && e.message) || e));
    }
    relatedSession = info ? { info, videoId, seen } : null;
    // hasMore: probe the private watch-next continuation by attempting nothing here; report true when
    // we have an info object (getMoreRelated will report false once exhausted).
    const hasMore = !!(info && (info.watch_next_feed || []).length > 0);
    console.log(`[__yt] getRelated → ${videos.length} videos`);
    return { videos, hasMore };
  },

  /** Next page of related videos for the currently-open video (watch-next continuation). */
  async getMoreRelated() {
    const s = relatedSession;
    if (!s || !s.info) return { videos: [], hasMore: false };
    try {
      await s.info.getWatchNextContinuation(); // throws when no continuation remains
      const videos = freshVideos(s.info.watch_next_feed || [], s.seen, s.videoId);
      console.log(`[__yt] getMoreRelated → +${videos.length}`);
      return { videos, hasMore: true };
    } catch (e) {
      return { videos: [], hasMore: false };
    }
  },

  /**
   * Latest uploads for a channel [channelId] (a UCID). Newest-first as YouTube
   * returns them. Stamps each video with the channel id + name so the caller can
   * group/sort across multiple subscribed channels.
   */
  async getChannelVideos(channelId) {
    const it = await createAnonSession();
    console.log(`[__yt] getChannelVideos("${channelId}")`);
    const videos = await fetchChannelVideosCore(it, channelId);
    console.log(`[__yt] getChannelVideos → ${videos.length} videos`);
    return { videos };
  },

  /**
   * Latest uploads for MULTIPLE channels (the Subscriptions tab's video list), fetched
   * CONCURRENTLY via Promise.all instead of one channel at a time. Looping getChannelVideos
   * sequentially meant N round trips for N subscriptions — tens of seconds for a long
   * subscription list. This is a genuine wall-clock win (not just reordering): the fetches are
   * network-bound, so running them at once is gated by the slowest single channel, not the sum.
   */
  async getChannelVideosBatch(channelIdsJson) {
    const it = await createAnonSession();
    const ids = parseIds(channelIdsJson);
    console.log(`[__yt] getChannelVideosBatch(${ids.length} channels)`);
    const perChannel = await Promise.all(ids.map((id) => fetchChannelVideosCore(it, id)));
    const videos = perChannel.flat();
    console.log(`[__yt] getChannelVideosBatch → ${videos.length} videos across ${ids.length} channels`);
    return { videos };
  },

  /**
   * Phase 7 (stretch): the two-hop "similar channels" pass — see the block comment above
   * [discoverFromChannel]. [subscribedChannelIdsJson] is sampled down to MAX_DISCOVER_SEEDS by this
   * function itself (not the caller), so a long subscription list can't blow up the call count; the
   * sample is random each run rather than always the same prefix, so repeated (e.g. daily) runs
   * eventually cover a large subscription list instead of only ever probing the same few channels.
   * Returns { channels: [{ id, name, score }] }, sorted by score (how many distinct seed channels a
   * discovered channel recurred under) descending, capped to MAX_DISCOVER_RESULTS.
   */
  async discoverSimilarChannels(subscribedChannelIdsJson) {
    const it = await createAnonSession();
    const allIds = parseIds(subscribedChannelIdsJson);
    const seedIds = allIds.length <= MAX_DISCOVER_SEEDS
      ? allIds
      : allIds.slice().sort(() => Math.random() - 0.5).slice(0, MAX_DISCOVER_SEEDS);
    console.log(`[__yt] discoverSimilarChannels(${seedIds.length}/${allIds.length} seeds)`);
    if (seedIds.length === 0) return { channels: [] };

    const excludeSet = new Set(allIds); // never "discover" a channel the user already subscribes to
    const perSeed = await Promise.all(seedIds.map((id) => discoverFromChannel(it, id)));

    // Count each distinct channelId ONCE per seed (recurring twice under the same seed's two
    // sampled videos shouldn't out-score recurring once each under two DIFFERENT seeds — the signal
    // we want is "related to multiple things you watch," not "related to one thing you watch, twice").
    const counts = new Map(); // channelId -> { name, count }
    for (const hits of perSeed) {
      const seenThisSeed = new Set();
      for (const h of hits) {
        if (!h.id || excludeSet.has(h.id) || seenThisSeed.has(h.id)) continue;
        seenThisSeed.add(h.id);
        const cur = counts.get(h.id) || { name: '', count: 0 };
        if (h.name && !cur.name) cur.name = h.name;
        cur.count += 1;
        counts.set(h.id, cur);
      }
    }

    const channels = Array.from(counts.entries())
      .filter(([, v]) => v.count >= MIN_DISCOVER_RECURRENCE)
      .sort((a, b) => b[1].count - a[1].count)
      .slice(0, MAX_DISCOVER_RESULTS)
      .map(([id, v]) => ({ id, name: v.name, score: v.count }));
    console.log(`[__yt] discoverSimilarChannels → ${channels.length} channels`);
    return { channels };
  },

  /** Channel header: { id, name, avatar, banner, subscriberText }. */
  async getChannelInfo(channelId) {
    const it = await createAnonSession();
    console.log(`[__yt] getChannelInfo("${channelId}")`);
    try {
      const channel = await it.getChannel(channelId);
      return channelHeader(channel, channelId);
    } catch (e) {
      console.warn('[__yt] getChannelInfo failed: ' + ((e && e.message) || e));
      return { id: channelId, name: '', avatar: null, banner: null, subscriberText: '' };
    }
  },

  /** A channel's playlists: { playlists: [{ id, title, thumbnail, videoCount }] }. */
  async getChannelPlaylists(channelId) {
    const it = await createAnonSession();
    console.log(`[__yt] getChannelPlaylists("${channelId}")`);
    let playlists = [];
    try {
      const channel = await it.getChannel(channelId);
      let tab = channel;
      try {
        if (typeof channel.getPlaylists === 'function') tab = await channel.getPlaylists();
      } catch (_) { tab = channel; }
      playlists = collectPlaylists(seedsFromFeed(tab));
      if (playlists.length === 0) playlists = collectPlaylists(tab);
    } catch (e) {
      console.warn('[__yt] getChannelPlaylists failed: ' + ((e && e.message) || e));
    }
    console.log(`[__yt] getChannelPlaylists → ${playlists.length} playlists`);
    return { playlists };
  },

  /** Videos of a playlist [playlistId], in playlist order: { videos }. */
  async getPlaylistVideos(playlistId) {
    const it = await createAnonSession();
    console.log(`[__yt] getPlaylistVideos("${playlistId}")`);
    let videos = [];
    try {
      const pl = await it.getPlaylist(playlistId);
      videos = collectVideos(seedsFromFeed(pl));
      if (videos.length === 0) videos = collectVideos(pl);
    } catch (e) {
      console.warn('[__yt] getPlaylistVideos failed: ' + ((e && e.message) || e));
    }
    console.log(`[__yt] getPlaylistVideos → ${videos.length} videos`);
    return { videos };
  },

  /**
   * First page of comments for [videoId]. [sortBy] is 'TOP' (default) or 'NEWEST'. Public data, so
   * the anon session is fine. Returns { comments, hasContinuation, totalText, disabled }. On any
   * failure returns an empty page (disabled=true only when YouTube says comments are off) so the UI
   * just hides the section instead of erroring.
   */
  async getComments(videoId, sortBy) {
    const it = await createAnonSession();
    const sort = sortBy === 'NEWEST' ? 'NEWEST_FIRST' : 'TOP_COMMENTS';
    console.log(`[__yt] getComments("${videoId}", ${sort})`);
    try {
      const comments = await it.getComments(videoId, sort);
      commentsState = { videoId, sort, comments, threadsById: {} };
      const page = commentPageFrom(comments);
      console.log(`[__yt] getComments → ${page.comments.length} (more=${page.hasContinuation})`);
      return page;
    } catch (e) {
      const msg = (e && e.message) || String(e);
      console.warn('[__yt] getComments failed: ' + msg);
      commentsState = null;
      return { comments: [], hasContinuation: false, totalText: '', disabled: /disab/i.test(msg) };
    }
  },

  /** Next page of the currently-open comments (continuation). Empty when there's no more. */
  async getMoreComments() {
    const c = commentsState && commentsState.comments;
    if (!c || !c.has_continuation || typeof c.getContinuation !== 'function') {
      return { comments: [], hasContinuation: false, totalText: '', disabled: false };
    }
    try {
      const next = await c.getContinuation();
      commentsState.comments = next;
      const page = commentPageFrom(next);
      console.log(`[__yt] getMoreComments → +${page.comments.length} (more=${page.hasContinuation})`);
      return page;
    } catch (e) {
      console.warn('[__yt] getMoreComments failed: ' + ((e && e.message) || e));
      return { comments: [], hasContinuation: false, totalText: '', disabled: false };
    }
  },

  /** First batch of replies for a top-level comment on the current page. Returns { replies }. */
  async getCommentReplies(commentId) {
    const t = commentsState && commentsState.threadsById && commentsState.threadsById[commentId];
    if (!t) return { replies: [] };
    try {
      let replies = [];
      if (typeof t.getReplies === 'function') {
        const r = await t.getReplies();
        replies = (r && (r.replies || r.comments || r.contents)) || [];
      } else if (t.replies) {
        replies = t.replies;
      }
      const out = replies.map(normaliseComment).filter(Boolean);
      console.log(`[__yt] getCommentReplies(${commentId}) → ${out.length}`);
      return { replies: out };
    } catch (e) {
      console.warn('[__yt] getCommentReplies failed: ' + ((e && e.message) || e));
      return { replies: [] };
    }
  },

  /**
   * Fetch video info + all streaming formats, using a client that serves
   * direct https URLs (so we skip SABR and stream over plain HTTP via Media3).
   * The winning client is cached after the first call (see chooseClientInfo).
   *
   * [forceFresh] (set by the player's failover retry) discards the cached
   * client + session + PO token and re-probes from scratch — recovers if the
   * previously-working client gets clamped by YouTube.
   */
  // FAST metadata path — title/author/duration/thumbnails/playability only. Deliberately does
  // NOT build format lists, mint a PO token, or call info.toDash(): toDash() alone serializes a
  // 30-100KB DASH manifest on the JS thread (confirmed via logcat: 34k-192k chars) and used to run
  // on EVERY video open even though SABR (getSabrInfo, a wholly separate call) is what actually
  // supplies playback and succeeds essentially 100% of the time. That expensive work now only runs
  // on demand, in getLegacyPlayback below, when SABR genuinely fails.
  async getVideoInfo(videoId, forceFresh) {
    if (forceFresh) {
      console.log('[__yt] getVideoInfo forceFresh — resetting client + session + token');
      authedSessionPromise = null;
      await invalidatePoToken().catch(() => {});
    }
    const it = await createAuthedSession(false, videoId);
    console.log(`[__yt] getVideoInfo("${videoId}")`);

    const { info, client: chosenClient } = await chooseClientInfo(it, videoId, !!forceFresh);
    const basic = info.basic_info || {};
    // Uploader tags/keywords — youtubei.js exposes them as basic_info.tags OR .keywords depending
    // on which client/renderer answered; either is free here since basic_info is already fully
    // parsed for the fields above. Never shown in the UI — see HistoryEntry.tags's doc for why
    // this is recorded at all (a low-cost interest signal for HomeRanker's tag-affinity scoring).
    const tags = Array.isArray(basic.tags)
      ? basic.tags.map(String)
      : (Array.isArray(basic.keywords) ? basic.keywords.map(String) : []);
    return {
      id: videoId,
      title: String(basic.title || ''),
      author: String(basic.author || ''),
      channelId: (basic.channel_id || (basic.channel && basic.channel.id) || null),
      durationSec: basic.duration != null ? Number(basic.duration) : null,
      viewCount: basic.view_count != null ? Number(basic.view_count) : null,
      isLive: !!basic.is_live,
      thumbnails: normaliseThumbs(basic.thumbnail),
      formats: [],
      sourceClient: chosenClient,
      streamUserAgent: null,
      dashManifest: null,
      streaming: {},
      tags,
      // Scrub-preview sprite sheet spec, or null if this video has none (rare, but happens for some
      // live-then-VOD uploads). See extractStoryboard's doc for the shape.
      storyboard: extractStoryboard(info),
      // When the video is unavailable (region lock, removed, sign-in/age required, …), surface
      // YouTube's reason so the player shows it instead of a generic "no playable format".
      // Empty for a normal, playable video (status === 'OK').
      playabilityReason: (() => {
        const ps = info.playability_status || {};
        const status = ps.status ? String(ps.status) : '';
        if (!status || status === 'OK') return '';
        const reason = ps.reason ? String(ps.reason) : '';
        return reason || ('This video is unavailable (' + status + ')');
      })(),
    };
  },

  // SLOW legacy-fallback path — everything getVideoInfo used to build unconditionally: direct-URL
  // formats (with nsig deciphering + a GVS pot for every client) and a DASH manifest via
  // info.toDash(). Only called by the app when the native SABR path genuinely fails to set up, so
  // this CPU-heavy work is now the exception rather than the rule on every single video open.
  async getLegacyPlayback(videoId, forceFresh) {
    const it = await createAuthedSession(false, videoId);
    console.log(`[__yt] getLegacyPlayback("${videoId}")`);

    // Captured BEFORE chooseClientInfo runs: `it` is the cached, shared authed session
    // (createAuthedSession reuses it across calls) and chooseClientInfo's own verification step
    // (mintGvsPotIfNeeded/verifyClientPlayable) now mutates this SAME player object's po_token
    // while probing candidates. This is the true session-level pot that authorised /player — it
    // must be restored at the end regardless of what happens in between, so grab it now rather
    // than after chooseClientInfo has already had a chance to overwrite it.
    const player = it.session && it.session.player;
    const originalPlayerPoToken = player ? player.po_token : undefined;

    const { info, client: chosenClient, gvsPot: preMintedGvsPot } =
      await chooseClientInfo(it, videoId, !!forceFresh);

    // GVS URLs need a PO token attested for THIS video (content-bound), not the session-bound
    // token the authed session was already built with (createAuthedSession's 'player' binding is
    // visitorData-bound under the default, non-swap configuration — see bindingIdFor). Mint it
    // and stamp it onto the shared player object BEFORE any deciphering happens below:
    // youtubei.js's Player.decipher() — called both by normaliseFormat() just below AND
    // internally by info.toDash() further down — unconditionally writes whatever
    // `player.po_token` currently holds onto every format URL as `&pot=`. Overwriting that field
    // here is what actually lands the content-bound pot on the DASH manifest; manually
    // string-appending &pot= after the fact (the old approach) only ever touched the allFormats
    // array below, which the DASH playback path never reads — the real manifest URLs kept
    // whatever stale session-bound pot the player already had regardless, which is why earlier
    // pot fixes here didn't stop the grace-then-403 wall.
    //
    // ONLY for POT_REQUIRED_CLIENTS (WEB/MWEB) now — see that set's doc. GrayJay's actual source
    // never sets serviceIntegrityDimensions.poToken on its TV_EMBEDDED/IOS/ANDROID legacy requests,
    // and NewPipeExtractor's VisionOS-only path never touches a PoTokenProvider at all, so stamping
    // one onto those clients' URLs isn't what makes them work and costs a BotGuard attestation round
    // trip for nothing. chooseClientInfo() already minted+stamped this during its own verification
    // step for a pot-required client (preMintedGvsPot) — reuse that instead of minting twice; this
    // block only runs when it didn't (the cached-preferredClient fast path skips minting).
    let gvsPot = preMintedGvsPot || null;
    if (!gvsPot && POT_REQUIRED_CLIENTS.has(chosenClient)) {
      try {
        // LOCAL FIX: pass the REAL visitorData, not null. Under the current (non-swap) binding
        // config bindingIdFor always wants videoId for 'gvs' regardless of this argument, so passing
        // null was harmless in practice — but only because ensurePoToken separately re-derives
        // visitorData whenever it's handed a falsy identifier, i.e. it happened to work by two
        // independent fallbacks agreeing rather than by one explicit value. If PoTokenBinding.SWAP is
        // ever flipped back on, 'gvs' wants visitorData here, and null would silently mint a pot
        // bound to nothing meaningful. Threading the real value through removes that footgun.
        const { visitorData: gvsVisitorData } = await resolveSessionVisitorData();
        const gvsId = bindingIdFor('gvs', swapPoTokenBindings, videoId, gvsVisitorData);
        gvsPot = (await ensurePoToken(gvsId, false, 'gvs')).poToken;
      }
      catch (e) { console.warn('[__yt] gvs pot mint failed: ' + ((e && e.message) || e)); }
    }
    // youtubei.js's Player.decipher() only sets `&pot=` when `this.po_token` is truthy (verified in
    // node_modules/youtubei.js/dist/src/core/Player.js: `if (... && this.po_token)
    // url_components.searchParams.set('pot', this.po_token);`) — so for a non-pot-required client,
    // simply NOT overwriting player.po_token isn't enough: player.po_token still holds the
    // SESSION-level pot from before chooseClientInfo ran (captured above as
    // originalPlayerPoToken), and decipher() would stamp THAT onto the media URL instead, which is
    // still "some pot", not the "no pot param at all" GrayJay/NewPipe actually send. Explicitly
    // clear it for the decipher/toDash pass below when not required; originalPlayerPoToken is
    // restored at the end of this function regardless of which branch ran.
    if (player) {
      player.po_token = gvsPot || undefined;
    }

    const sd = info.streaming_data || {};
    const adaptive = Array.isArray(sd.adaptive_formats) ? sd.adaptive_formats : [];
    const progressive = Array.isArray(sd.formats) ? sd.formats : [];
    // Always run every format through decipher(), not just web-family ones: that's what stamps
    // the po_token set above onto the URL as &pot=. For a client whose format carries no
    // signature/nsig to transform (mobile clients, usually) decipher() is a safe, cheap
    // pass-through — the eval-based sig/nsig transform only runs when the format actually has a
    // cipher or `n` param; the pot stamp always runs.
    const allFormats = (await Promise.all(
      [...adaptive, ...progressive].map((f) => normaliseFormat(f, player))
    )).filter(Boolean);

    // Defensive fallback only — should be a no-op in the common case now that normaliseFormat
    // prefers decipher()'s (already pot-stamped) result. Only fires when decipher() threw and
    // normaliseFormat fell back to the raw, unstamped url.
    if (gvsPot) {
      for (const f of allFormats) {
        if (f.url && f.url.indexOf('pot=') === -1) {
          f.url += (f.url.indexOf('?') !== -1 ? '&' : '?') + 'pot=' + encodeURIComponent(gvsPot);
        }
      }
    }

    const directCount = allFormats.filter((f) => f.url).length;
    console.log(`[__yt] legacy client=${chosenClient}, ${allFormats.length} formats (${directCount} direct)`);

    const streamUserAgent = STREAM_UA[chosenClient] || DESKTOP_UA;

    // Build a DASH manifest from the adaptive formats. This is the primary
    // playback path: it gives ExoPlayer native seeking (via the sidx segment
    // index), automatic adaptive bitrate, proper audio-track selection, and
    // bounded segment-range requests (which googlevideo serves reliably,
    // unlike full-file ranges). info.toDash() deciphers every format itself
    // via the SAME player object (now carrying whatever gvsPot resolved to above — a real pot for
    // WEB/MWEB, nothing at all for the rest), which is what actually lands the right &pot= (or
    // deliberate absence of one) on the manifest's URLs — independent of the allFormats array above.
    let dashManifest = null;
    const isLive = !!(info.basic_info && info.basic_info.is_live);
    if (!isLive) {
      try {
        dashManifest = await info.toDash((url) => url);
        if (typeof dashManifest === 'string' && dashManifest.length > 0) {
          console.log('[__yt] toDash → ' + dashManifest.length + ' chars');
        } else {
          dashManifest = null;
        }
      } catch (e) {
        console.warn('[__yt] toDash failed: ' + ((e && e.message) || e));
      }
    }

    // Restore — see the save above. Must happen even though this is the end of the function: the
    // cached session (and this same player object) outlives this call.
    if (player) {
      player.po_token = originalPlayerPoToken;
    }

    return {
      formats: allFormats,
      sourceClient: chosenClient,
      streamUserAgent: streamUserAgent,
      dashManifest: dashManifest,
    };
  },

  /**
   * Surface everything the native SABR client needs to open a streaming session
   * for [videoId]: the chosen client profile, the deciphered serverAbrStreamingUrl,
   * the base64 videoPlaybackUstreamerConfig, visitorData, and the adaptiveFormats
   * as JSON (shaped for YoutubeSabrFormat.fromAdaptiveFormats). Tries the cached
   * preferred SABR client first (one call in the common case); only falls back to
   * probing all of SABR_CLIENT_PREFERENCE when that miss or fails.
   */
  async getSabrInfo(videoId, forceFresh) {
    // [forceFresh] mirrors getVideoInfo's: drop the cached authed session and PO token so the
    // /player call below is issued by a brand-new Innertube session carrying a freshly attested
    // token. This is the sequence the buffering watchdog stumbled into in run 15 — the session it
    // produced answered StreamProtectionStatus=1 where the two before it got grace — so the SABR
    // layer now reaches for it deliberately (grace bail-out, reload) instead of only ever getting
    // there 85s late via the watchdog. Note it does NOT change visitorData: anonSessionPromise is
    // untouched, so this re-attests the SAME identity rather than assuming a new one.
    if (forceFresh) {
      console.log('[__yt] getSabrInfo forceFresh — resetting session + token');
      authedSessionPromise = null;
      await invalidatePoToken().catch(() => {});
    }
    const it = await createAuthedSession(false, videoId);
    const player = it.session && it.session.player;
    const ctxClient = (it.session && it.session.context && it.session.context.client) || {};
    const visitorData = ctxClient.visitorData || null;
    console.log(`[__yt] getSabrInfo("${videoId}")`);

    let chosen = null;
    let info = null;
    let ustreamer = null;
    let sabrUrl = null;

    await loadPreferredSabrClient();
    if (preferredSabrClient) {
      try {
        const cand = await it.getBasicInfo(videoId, { client: preferredSabrClient });
        const sd = cand.streaming_data || {};
        const u = extractUstreamerConfig(cand);
        const s = sd.server_abr_streaming_url || null;
        console.log(`[__yt] sabr probe (cached) client=${preferredSabrClient} abrUrl=${!!s} ustreamer=${!!u}`);
        if (s && u) { chosen = preferredSabrClient; info = cand; ustreamer = u; sabrUrl = s; }
      } catch (e) {
        console.warn(`[__yt] cached sabr client ${preferredSabrClient} failed: ${(e && e.message) || e}`);
      }
    }

    for (const client of (info ? [] : SABR_CLIENT_PREFERENCE)) {
      try {
        const cand = await it.getBasicInfo(videoId, { client });
        const sd = cand.streaming_data || {};
        const u = extractUstreamerConfig(cand);
        const s = sd.server_abr_streaming_url || null;
        console.log(`[__yt] sabr probe client=${client} abrUrl=${!!s} ustreamer=${!!u}`);
        if (s && u) { chosen = client; info = cand; ustreamer = u; sabrUrl = s; await savePreferredSabrClient(client); break; }
        if (!info) { chosen = client; info = cand; ustreamer = u; sabrUrl = s; }
      } catch (e) {
        console.warn(`[__yt] sabr client ${client} failed: ${(e && e.message) || e}`);
      }
    }
    if (!info) throw new Error('No SABR-capable client returned playable info');

    const sd = info.streaming_data || {};
    const adaptive = Array.isArray(sd.adaptive_formats) ? sd.adaptive_formats : [];
    const decipheredUrl = await decipherUrlN(player, sabrUrl);
    const profile = SABR_PROFILE_MAP[chosen] || 'WEB';
    const basic = info.basic_info || {};

    // Distinct audio tracks (one per language) so the native side can pick by language. The track id
    // matches YoutubeSabrFormat.getAudioTrackId(); isDefault marks the video's ORIGINAL audio.
    const audioTracks = [];
    const seenAudio = {};
    for (const f of adaptive) {
      if (!f.has_audio || f.has_video) continue;
      const at = f.audio_track;
      const id = (at && at.id != null) ? String(at.id) : '';
      const key = id || '__default__';
      if (seenAudio[key]) continue;
      seenAudio[key] = true;
      audioTracks.push({
        id,
        displayName: (at && at.display_name) ? String(at.display_name) : (f.language ? String(f.language) : 'Default'),
        language: f.language ? String(f.language) : '',
        isDefault: !!(at && at.audio_is_default) || !at,
      });
    }

    // Closed-caption tracks: side-loadable timedtext URLs (normalised to WebVTT). asr = auto-generated.
    const captionTracks = (((info.captions && info.captions.caption_tracks) || [])
      .map((ct) => {
        let url = ct.base_url || '';
        if (url && url.indexOf('fmt=') === -1) {
          url += (url.indexOf('?') !== -1 ? '&' : '?') + 'fmt=vtt';
        }
        return {
          languageCode: ct.language_code || '',
          name: asText(ct.name) || ct.language_code || '',
          url,
          isAuto: ct.kind === 'asr',
        };
      })
      .filter((c) => c.url));

    console.log(`[__yt] getSabrInfo → profile=${profile} formats=${adaptive.length} ` +
      `audioTracks=${audioTracks.length} captions=${captionTracks.length} ` +
      `abrUrl=${!!decipheredUrl} ustreamer=${!!ustreamer}`);
    return {
      profile,
      videoId,
      visitorData,
      // WEB session version is the only one we can read reliably; for other
      // profiles let the native side fall back to its profile default.
      clientVersion: profile === 'WEB' ? (ctxClient.clientVersion || null) : null,
      serverAbrStreamingUrl: decipheredUrl || null,
      ustreamerConfig: ustreamer || null,
      adaptiveFormatsJson: JSON.stringify(adaptive.map(sabrFormatJson)),
      audioTracks,
      captionTracks,
      durationSec: basic.duration != null ? Number(basic.duration) : null,
      title: String(basic.title || ''),
      isLive: !!basic.is_live,
    };
  },

  /**
   * Fetch a video's closed captions as WebVTT text for the requested language (matched by display
   * name against the caption tracks). Fetches the timedtext URL THROUGH the runtime's own `fetch`
   * (browser User-Agent + youtube.com Origin/Referer added by the native bridge) — that's what the
   * server needs to return content. (Side-loading the URL into ExoPlayer's OkHttp used the SABR UA
   * with no Referer → an empty 200 body; Innertube's get_transcript endpoint 400s with malformed
   * params in this youtubei.js build.) Prefers native WebVTT, falls back to json3 → convert. Returns
   * '' when unavailable; the native side then shows no captions (never breaks A/V).
   */
  async getCaptionVtt(videoId, languageName) {
    const it = await createAuthedSession(false, videoId);
    let info;
    try {
      info = await it.getBasicInfo(videoId);
    } catch (e) {
      console.warn('[__yt] getCaptionVtt getBasicInfo failed: ' + ((e && e.message) || e));
      return '';
    }
    const tracks = (info.captions && info.captions.caption_tracks) || [];
    if (!tracks.length) { console.log('[__yt] getCaptionVtt: no caption tracks'); return ''; }
    // Match the picked track by display name (the picker is built from these), else language code.
    const want = String(languageName || '').toLowerCase();
    const track = tracks.find((t) => asText(t.name).toLowerCase() === want) ||
      tracks.find((t) => String(t.language_code || '').toLowerCase() === want) ||
      tracks.find((t) => asText(t.name).toLowerCase().indexOf(want) !== -1) ||
      tracks[0];
    const base = track.base_url || '';
    if (!base) return '';

    // YouTube now gates the timedtext endpoint behind a PO token (visitor-data bound) — without it the
    // signed URL returns HTTP 200 with an EMPTY body (verified). Append the session pot + client, the
    // same fix yt-dlp uses. (The pot is the one minted for the session visitorData.)
    let pot = '';
    try { pot = (await ensurePoToken(null, false)).poToken || ''; } catch (e) { /* best effort */ }

    // base_url + &fmt=<fmt> (+ pot/client), replacing any existing fmt.
    const withFmt = (fmt) => {
      let u = base.replace(/([?&])fmt=[^&]*(&|$)/, '$1').replace(/([?&])pot=[^&]*(&|$)/, '$1')
        .replace(/([?&])c=[^&]*(&|$)/, '$1').replace(/[?&]+$/, '');
      u += (u.indexOf('?') !== -1 ? '&' : '?') + 'fmt=' + fmt;
      if (pot) u += '&c=WEB&potc=1&pot=' + encodeURIComponent(pot);
      return u;
    };
    const grab = async (fmt) => {
      try {
        const resp = await fetch(withFmt(fmt));
        const body = await resp.text();
        console.log(`[__yt] getCaptionVtt fetch fmt=${fmt} status=${resp.status} len=${body.length}`);
        return (resp.status >= 200 && resp.status < 300) ? body : '';
      } catch (e) {
        console.warn('[__yt] getCaptionVtt fetch failed: ' + ((e && e.message) || e));
        return '';
      }
    };

    // 1) Native WebVTT — use directly when YouTube returns it (correctly formatted, incl. styling).
    const vtt = await grab('vtt');
    if (vtt && vtt.indexOf('WEBVTT') !== -1) return vtt;

    // 2) json3 → WebVTT. Clamp each cue's end to the next cue's start so ASR (rolling) tracks render
    //    as clean, non-overlapping lines.
    const raw = await grab('json3');
    if (!raw) return '';
    try {
      const events = (JSON.parse(raw).events) || [];
      const cues = [];
      for (const ev of events) {
        if (!ev || !ev.segs || ev.tStartMs == null) continue;
        const text = ev.segs.map((s) => (s && s.utf8) || '').join('').replace(/\s+\n/g, '\n').trim();
        if (!text) continue;
        const start = Number(ev.tStartMs);
        const dur = ev.dDurationMs != null ? Number(ev.dDurationMs) : 0;
        cues.push({ start, end: start + (dur > 0 ? dur : 4000), text });
      }
      let out = 'WEBVTT\n\n';
      let n = 0;
      for (let i = 0; i < cues.length; i++) {
        const c = cues[i];
        const next = cues[i + 1];
        const end = next ? Math.min(c.end, next.start) : c.end;
        if (end <= c.start) continue;
        out += `${vttTime(c.start)} --> ${vttTime(end)}\n${c.text}\n\n`;
        n++;
      }
      console.log(`[__yt] getCaptionVtt json3 cues=${n}`);
      return n > 0 ? out : '';
    } catch (e) {
      console.warn('[__yt] getCaptionVtt json3 parse failed: ' + ((e && e.message) || e));
      return '';
    }
  },

  /**
   * Pre-build the AUTHED Innertube session (fetches + processes base.js, the slow part of opening the
   * first video). Called at app startup so the first playback isn't blocked on it.
   */
  async warmup() {
    try {
      await createAuthedSession();
      console.log('[__yt] warmup: authed session ready');
    } catch (e) {
      console.warn('[__yt] warmup failed: ' + ((e && e.message) || e));
    }
    return { ok: true };
  },

  /** Inspect cached PO token. Triggers session-level mint if none exists. */
  async poTokenInfo() {
    try { await ensurePoToken(null, false); } catch (_) { /* snapshot will carry it */ }
    return poTokenSnapshot();
  },

  /** Force a fresh mint of the session-level PO token. */
  async refreshPoToken() {
    try { await ensurePoToken(null, true); } catch (_) { /* snapshot will carry it */ }
    return poTokenSnapshot();
  },
};

globalThis.__yt = __yt;
console.log('[__yt] runtime ready, surface: ' + Object.keys(__yt).join(', '));
