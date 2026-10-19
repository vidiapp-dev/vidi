package com.example.ytclient.sabr

/**
 * THE single place that decides which identity each PO token binds to.
 *
 * Two endpoints want a Proof-of-Origin token, and each token is cryptographically bound to one
 * identifier that the receiving server validates:
 *
 *  • Innertube `/player` — `serviceIntegrityDimensions.poToken` in the JSON body. Gets us the format
 *    list, `serverAbrStreamingUrl` and `ustreamerConfig`. Refused ⇒ no formats at all.
 *  • GVS (`*.googlevideo.com`) — inside the SABR/UMP request body. Authorises the media fetch.
 *    Refused ⇒ formats are fine, playback starts, then `StreamProtectionStatus` 2 or 3.
 *
 * The mint is identical for both: same BotGuard attestation, same VM, same integrity token. ONLY the
 * identifier differs. So this object is the whole experiment.
 *
 * ── THE DISAGREEMENT ────────────────────────────────────────────────────────────────────────────
 * Two authoritative sources assign the identities in opposite directions:
 *
 *  • LuanRT/BgUtils ("Token Types") — the web player uses a SESSION-bound token for playback (Data
 *    Sync ID when logged in, otherwise Visitor ID) and reserves the videoId ("content bound") token
 *    for `/player`, noting it "should not be cached".  ⇒ [SWAP] = true
 *  • yt-dlp's PO Token Guide — "Most PO Tokens (such as for `web` GVS/Player) are bound to the video
 *    ID, so a new token is required for each video."  ⇒ [SWAP] = false
 *
 * The likeliest reconciliation is that this changed during 2025 and BgUtils describes the older
 * model. That is unverified.
 *
 * ── WHY THIS IS ONE FLAG AND NOT TWO ────────────────────────────────────────────────────────────
 * The 2026-08-02 probe alternated only the GVS side while `/player` kept its own binding, and came
 * back inconclusive — both GVS bindings were refused in the same session. But that probe never
 * tested BgUtils' actual claim, which is that BOTH assignments are swapped simultaneously. If the
 * server cross-checks the two tokens against each other, or against the identity that attested them,
 * then half a swap is not a weaker version of the experiment — it is a different and meaningless
 * one. Hence a single boolean driving both halves.
 *
 * The Kotlin side reads [SWAP] directly. The JS runtime is told at startup via
 * `__yt.configurePoTokenBinding(...)` (see QuickJsRuntime), so the constant is never duplicated.
 */
object PoTokenBinding {

    /**
     * `false` (default, shipped through run 18): `/player` ← visitorData, GVS ← videoId.
     * `true`  (the experiment):                  `/player` ← videoId,     GVS ← visitorData.
     *
     * Run 18 confirmed the swap is worth trying: three SABR sessions opened back to back returned
     * protection 2, 2, 1, and sessions 2 and 3 were identical in every observable token property
     * (same attestation, same VM, same binding, mints two apart, both requests issued ~0.1 s after
     * their mint). Nothing on the token side that we currently vary explains the split, so the
     * assignment itself is the remaining untested variable.
     *
     * Supporting hint: `[sabr-pot] identity: … MATCH=false` fires on every injection — we attest as
     * the WebView page's visitor and then mint bound to a different identity (our Innertube
     * session's). If the integrity token is tied server-side to the attesting page's visitor, a
     * session-bound GVS token is the configuration that would expose it.
     *
     * Cost when true: the `/player` pot becomes content-bound, so the authed Innertube session can
     * no longer be shared across videos and is rebuilt per video. With the player cache in place
     * that is one `Innertube.create` (~100 ms, no `base.js` fetch), not a full cold start.
     *
     * ── RESULT: RUN 19 (2026-08-05). The swap was TESTED AND IS WORSE. Reverted to false. ────────
     * Same video, same emulator, one run each way:
     *
     *   SWAP=false (run 18): protection 2, 2, then 1 — four minutes of clean playback, two seeks.
     *   SWAP=true  (run 19): protection 2 for ~1.13 MB of audio, then 3, and 3 forever. No status-1
     *                        session was ever obtained. Playback died and did not recover.
     *
     * Both halves demonstrably flipped (`Minted player pot bound to videoId/content = a965A86aAJ8`
     * and `Minted gvs pot bound to visitorData/session = visitorData-Q…`), and the per-video session
     * rebuild fired as designed — so this is a result about the hypothesis, not about the wiring.
     *
     * Caveat, stated because it is the one thing that keeps this from being a clean refutation:
     * run 19 also saw the server open BOTH its sessions with a SabrContextUpdate round (parts
     * 57/67, no media) that no earlier run had produced. That is either a server-side change or a
     * consequence of the swap, and one run cannot separate them. What is not in doubt is that this
     * configuration did not improve anything, so it does not stay on.
     *
     * BgUtils' reading is therefore not supported by experiment for current YouTube, and yt-dlp's
     * ("most PO tokens, including web GVS, are bound to the video ID") is the one that matches
     * observed behaviour. Do not re-run this without a new reason.
     */
    const val SWAP = false

    /** What the Innertube `/player` token binds to. */
    fun playerIdentifier(videoId: String, visitorData: String): String =
        if (SWAP) videoId else visitorData

    /** What the GVS / SABR streaming token binds to.
     *
     *  [swapOverride], when non-null, takes precedence over [SWAP] for this one call — see
     *  [WatchPageExperimentFlags.resolveGvsSwap]'s doc for where it comes from and why it exists on
     *  top of, not instead of, the hardcoded default below. Null (the ordinary case: unresolved,
     *  timed out, or a call site that doesn't wire it up) falls back to [SWAP] exactly as before this
     *  parameter existed.
     *
     *  CAUTION if this ever resolves `swapOverride = true`: that is the SAME configuration run 19
     *  tested and found worse (see [SWAP]'s doc — session-bound GVS never reached status=1 in that
     *  run). A per-video signal saying session-bound is right for THIS video is a different claim
     *  than "session-bound is always right" (which run 19 refutes), but it is exactly the case worth
     *  watching closely in the field rather than trusting quietly — [WatchPageExperimentFlags] logs
     *  its resolved value for that reason. */
    fun gvsIdentifier(videoId: String, visitorData: String, swapOverride: Boolean? = null): String =
        if (swapOverride ?: SWAP) visitorData else videoId
}
