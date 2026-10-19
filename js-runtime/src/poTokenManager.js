// PO token lifecycle for the QuickJS runtime.
//
// Real minting happens in Kotlin via the WebView-backed
// `WebPoTokenProvider` — see `core/webpo/WebPoTokenProvider.kt`. This module
// is now a thin per-identifier cache on top of the `__nativeMintPoToken`
// bridge, with persistence so tokens survive app restarts up to ~11 hours.
//
// Identifier semantics:
//   • The identifier is whatever the caller says the token must bind to — a videoId (content
//     binding) or a visitorData (session binding). Which endpoint gets which is decided in exactly
//     one place, PoTokenBinding.SWAP on the Kotlin side, and pushed in via configurePoTokenBinding.
//   • `purpose` ('player' | 'gvs') names the endpoint, and is part of the cache key so the two
//     never alias each other.
// Each (purpose, identifier) pair has its own cache entry — the Kotlin side reuses the same
// integrity token under the hood, so extra mints are fast.

// v3: tokens are now att/get-attested (the old /Create-minted v2 tokens are rejected by /player).
// v4: cache keys gained a "<purpose>:" prefix. Bumped rather than migrated so a v3 file's
//     identifier-keyed entries are dropped instead of lingering forever as unreachable garbage.
const STORAGE_KEY = 'yt_po_cache_v4';
// Aligns with the 10-min margin WebPoTokenProvider applies to the server-side
// integrity-token TTL (defaults to 12h → 11h50m usable, we use 5h to be safe).
const TOKEN_TTL_MS = 5 * 60 * 60 * 1000;

let cacheMap = null;  // Map<"<purpose>:<identifier>", { poToken, mintedAt, expiresAt }>
let lastError = null;
let lastIdentifierUsed = null;
// Cache key ("<purpose>:<identifier>") of the most recent mint, for the poTokenInfo() diagnostic.
let lastKeyUsed = null;

function nowMs() { return Date.now(); }

async function loadCache() {
  if (cacheMap) return cacheMap;
  try {
    const raw = await globalThis.__nativeStorageGet(STORAGE_KEY);
    if (raw) {
      const obj = JSON.parse(raw);
      cacheMap = new Map(Object.entries(obj));
    } else {
      cacheMap = new Map();
    }
  } catch (e) {
    console.warn('[po] cache load failed:', (e && e.message) || String(e));
    cacheMap = new Map();
  }
  return cacheMap;
}

async function persist() {
  if (!cacheMap) return;
  const obj = {};
  for (const [k, v] of cacheMap.entries()) obj[k] = v;
  try {
    await globalThis.__nativeStorageSet(STORAGE_KEY, JSON.stringify(obj));
  } catch (e) {
    console.warn('[po] cache write failed:', (e && e.message) || String(e));
  }
}

/**
 * The JS mirror of `PoTokenBinding.playerIdentifier` / `gvsIdentifier` on the Kotlin side. [swap] is
 * pushed in from `PoTokenBinding.SWAP` via `__yt.configurePoTokenBinding`, so there is still exactly
 * one decision — this is just where the JS half reads it.
 *
 *   swap=false → player ← visitorData, gvs ← videoId
 *   swap=true  → player ← videoId,     gvs ← visitorData
 *
 * [videoId] may be null at call sites with no video in hand (warmup, diagnostics); those fall back
 * to the visitorData binding regardless of [swap], and the next per-video call rebinds.
 */
export function bindingIdFor(purpose, swap, videoId, visitorData) {
  const wantsVideoId = swap ? (purpose === 'player') : (purpose === 'gvs');
  return (wantsVideoId && videoId) ? videoId : visitorData;
}

function isFresh(entry) {
  return entry && typeof entry.poToken === 'string' && entry.poToken.length > 0 &&
    typeof entry.expiresAt === 'number' && entry.expiresAt > nowMs();
}

/**
 * Returns a PO token bound to [identifier] for the [purpose] endpoint ('player' | 'gvs'). Uses cache
 * when fresh; mints (via the WebView bridge) when stale or absent. The Kotlin side already
 * serialises concurrent mints so we don't dedupe here.
 */
export async function getOrMintPoToken(identifier, visitorData, clientVersion, forceRefresh = false,
                                       purpose = 'player') {
  if (!identifier) throw new Error('getOrMintPoToken: identifier required');
  lastIdentifierUsed = identifier;
  const cache = await loadCache();

  // Cache key includes the purpose. Under PoTokenBinding.SWAP the /player pot binds to the videoId
  // and the GVS pot binds to the visitorData, but the reverse is also a legal configuration — so
  // "identifier" alone does not identify a token, and keying on it would let a /player pot be handed
  // out for a GVS request (or vice versa) whenever the two happened to share a binding value.
  const key = purpose + ':' + identifier;

  if (!forceRefresh) {
    const hit = cache.get(key);
    if (isFresh(hit)) return { poToken: hit.poToken, identifier, isFallback: false };
  }

  console.log('[po] minting ' + purpose + ' pot (att/get) bound to ' + identifier.slice(0, 20));
  let poToken;
  try {
    // att/get-attested mint, bound to `identifier` with the session's visitorData/clientVersion as
    // the challenge context. YouTube rejects the older out-of-page /Create pot on /player now.
    poToken = await globalThis.__nativeMintAttestedPoToken(
      identifier, visitorData || identifier, clientVersion || '', purpose,
    );
  } catch (e) {
    lastError = (e && e.message) || String(e);
    console.error('[po] mint failed:', lastError);
    throw e;
  }
  if (typeof poToken !== 'string' || poToken.length === 0) {
    throw new Error('__nativeMintPoToken returned invalid: ' + JSON.stringify(poToken));
  }
  const now = nowMs();
  cache.set(key, { poToken, mintedAt: now, expiresAt: now + TOKEN_TTL_MS });
  await persist();
  lastError = null;
  lastKeyUsed = key;
  console.log('[po] minted ' + purpose + ' pot (len=' + poToken.length + ') bound to '
    + identifier.slice(0, 20));
  return { poToken, identifier, isFallback: false };
}

/** Drop all cached tokens. */
export async function invalidatePoToken() {
  cacheMap = new Map();
  try { await globalThis.__nativeStorageSet(STORAGE_KEY, null); } catch (_) {}
}

/** Snapshot for the bridge's `poTokenInfo()` diagnostic. */
export function poTokenSnapshot() {
  // Report on the most-recently-used identifier so the UI shows the live state.
  const id = lastIdentifierUsed;
  const entry = lastKeyUsed && cacheMap && cacheMap.get(lastKeyUsed);
  if (entry && isFresh(entry)) {
    return {
      hasToken: true,
      visitorData: id,
      isFallback: false,
      expiresInMs: Math.max(0, entry.expiresAt - nowMs()),
      error: lastError,
    };
  }
  return {
    hasToken: false,
    visitorData: id,
    isFallback: false,
    expiresInMs: null,
    error: lastError,
  };
}
