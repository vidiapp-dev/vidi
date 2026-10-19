package com.example.ytclient.sabr

/**
 * Whose identity does the playback session run under?
 *
 * ── THE MISMATCH ────────────────────────────────────────────────────────────────────────────────
 * Every PO-token mint since this project started has logged `MATCH=false`:
 *
 * ```
 * [sabr-pot] identity: session=visitorData-S page=visitorData-T pageLen=558 via=html#0 MATCH=false
 * ```
 *
 * Two different visitor identities are in play at once:
 *
 *  • **The page's** — real, server-issued, cookie-backed. The youtube.com WebView got it on load,
 *    and it is the identity BotGuard attests under, because att/get and GenerateIT are fetched by
 *    the page with the page's own cookies.
 *  • **The session's** — invented locally. `createAnonSession` runs with
 *    `generate_session_locally: true`, and youtubei.js then does
 *    `visitor_data || encodeVisitorData(generateRandomString(11), now)` — an 11-character id
 *    YouTube has never issued. It is the identity `/player` and the SABR stream run under.
 *
 * So we attest as one visitor and stream as another. Run 21 confirmed the page value is genuine
 * (it decodes to `0x0A 0x0B` + an 11-char id; 558 chars is just its experiments blob), which makes
 * this a real structural mismatch rather than the measurement artifact it was once assumed to be.
 * It is the last untested structural difference between us and the web player.
 *
 * ── WHY THIS IS A FLAG AND NOT JUST A FIX ───────────────────────────────────────────────────────
 * Changing session identity has bitten before. On 2026-08-02, setting
 * `generate_session_locally: false` (so youtubei.js fetched a real identity from `/sw.js_data`)
 * made things WORSE — the stream stopped starting at all rather than dying at 60 s — for two
 * reasons that are worth naming, because this change must avoid both:
 *
 *  1. **clientVersion split.** `/sw.js_data` returns its own clientVersion, so the Innertube session
 *     used that while the SABR DTO and the GVS mint kept the bundled one. Two client versions across
 *     the request that issues a stream and the token that authorises it.
 *     → Avoided here: we take ONLY the visitorData. clientVersion stays the bundled value.
 *  2. **A long value breaking the header.** The real visitorData is percent-encoded; `normaliseVisitorData`
 *     un-percent-encoded it into raw bytes, which made `X-Goog-Visitor-Id` invalid and every
 *     `fetch()` throw "Failed to fetch".
 *     → Avoided here: `normaliseVisitorData` now refuses to decode when the result would not be
 *     header-safe.
 *
 * ── SCOPE ───────────────────────────────────────────────────────────────────────────────────────
 * Only the AUTHED session (the one that issues `/player` and seeds SABR) adopts the page identity.
 * The anon session — which serves search, home feed and channel browsing — is deliberately left
 * alone, for a timing reason: in run 21 the anon session was created at 22:09:36.6 and the home feed
 * resolved at 22:09:42.9, while the WebView page only finished loading at 22:09:43.4. Blocking the
 * anon session on page load would have delayed the first screen by ~7 s. The authed session already
 * waits for the WebView (its PO-token mint blocks on it), so the wait is free there.
 */
object SabrSessionIdentity {

    /**
     * `true`: the authed Innertube session runs under the WebView page's real visitorData, so
     * attestation identity and streaming identity are the same visitor.
     * `false`: today's behaviour — a locally-invented visitorData, `MATCH=false`.
     *
     * Falls back to `false` behaviour automatically at runtime if the page has no valid identity to
     * offer or does not produce one in time, so flipping this on cannot brick playback.
     */
    const val USE_PAGE_IDENTITY = true

    /**
     * How long the authed session may wait for the WebView to report the page identity.
     *
     * WAS 3_000, on the assumption that the authed session is only built once playback is requested,
     * by which time the page is long up. Run 22 disproved that: **warmup** also builds an authed
     * session, and it runs early —
     *
     * ```
     * 22:32:18.847  page identity lookup failed: Timed out waiting for 3000 ms
     * 22:32:24.265  page identity captured: visitorData-V… (558 chars)   ← 5.4 s later
     * 22:32:37.810  authed session adopting PAGE identity … — rebuilding authed session
     * ```
     *
     * So warmup built a whole session on the local identity, minted a pot for it, fetched base.js —
     * and playback then threw all of it away 19 s later and re-attested. Waiting 5.4 s would have
     * produced ONE session that playback could reuse.
     *
     * 20 s is generous because the cost asymmetry is lopsided: waiting is background time nobody is
     * watching, while giving up early costs a wasted session, a wasted mint and a forced
     * re-attestation. The timeout now only fires if the WebView genuinely never comes up, which is
     * the case it was always meant for.
     */
    const val PAGE_IDENTITY_TIMEOUT_MS = 20_000L
}
