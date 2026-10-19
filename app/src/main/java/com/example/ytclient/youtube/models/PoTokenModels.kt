package com.example.ytclient.youtube.models

import kotlinx.serialization.Serializable

/**
 * Diagnostic snapshot of the runtime's PO-token cache.
 *
 * Two success states:
 *  - `hasToken=true, isFallback=false`: fully-minted token via BotGuard VM.
 *  - `hasToken=true, isFallback=true`: degraded token (websafeFallbackToken
 *    from the WAA GenerateIT response). Used when the QuickJS environment
 *    isn't convincing enough to produce a full attestation. Works for some
 *    playback paths, may be rejected for others.
 *
 * `hasToken=false`: runtime is still attempting to mint (or generation
 * failed — check `error` and logcat).
 */
@Serializable
data class PoTokenInfo(
    val hasToken: Boolean = false,
    val visitorData: String? = null,
    val isFallback: Boolean = false,
    /** Milliseconds until expiry, or null if no token cached. */
    val expiresInMs: Long? = null,
    /** Last error message from BgUtils, if generation failed. */
    val error: String? = null,
)
