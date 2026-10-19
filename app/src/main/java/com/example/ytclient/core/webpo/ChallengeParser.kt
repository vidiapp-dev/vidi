package com.example.ytclient.core.webpo

import android.util.Base64
import org.json.JSONArray

/**
 * Parses the two WAA endpoint responses into JS-injectable string fragments.
 *
 * Logic transposed from SmartTube's `JavaScriptUtil.kt`. Not protobuf —
 * Google's `/api/jnn/v1/Create` returns a JSON array whose second element is
 * a YouTube-base64-encoded payload whose bytes are then shifted (+97) to
 * yield plain UTF-8 JSON.
 */
internal object ChallengeParser {

    /**
     * Parse a `/Create` response body into a JSON object literal string ready
     * to be embedded in `var data = …;` and passed to `runBotGuard()`.
     *
     * Raw shape: `[messageId?, "<scrambled-base64>"]`. The scrambled string
     * decodes via [descramble] to a JSON array whose indices we know:
     *   [0] messageId,
     *   [1] [interpreter SafeScript wrapped value …],
     *   [2] [interpreter TrustedResourceUrl wrapped value …],
     *   [3] interpreterHash,
     *   [4] program,
     *   [5] globalName,
     *   [7] clientExperimentsStateBlob.
     */
    fun parseChallengeData(rawCreateResponse: String): String {
        val outer = JSONArray(rawCreateResponse)

        val inner: JSONArray = if (outer.length() > 1 && outer.opt(1) is String) {
            JSONArray(descramble(outer.getString(1)))
        } else {
            // Defensive: occasionally the upstream returns just `[ [ …challenge… ] ]`.
            outer.getJSONArray(0)
        }

        val messageId = inner.optString(0, null)
        val interpreterHash = inner.optString(3, null)
        val program = inner.optString(4, null)
        val globalName = inner.optString(5, null)
        val clientExperimentsStateBlob = inner.optString(7, null)

        val safeScript = inner.optJSONArray(1)?.findFirstString()
        val trustedResourceUrl = inner.optJSONArray(2)?.findFirstString()

        val interpreterJs = mapOf(
            "privateDoNotAccessOrElseSafeScriptWrappedValue" to safeScript,
            "privateDoNotAccessOrElseTrustedResourceUrlWrappedValue" to trustedResourceUrl,
        )
        val obj = mapOf(
            "messageId" to messageId,
            "interpreterJavascript" to interpreterJs,
            "interpreterHash" to interpreterHash,
            "program" to program,
            "globalName" to globalName,
            "clientExperimentsStateBlob" to clientExperimentsStateBlob,
        )
        return toJsonLiteral(obj)
    }

    /**
     * Parse a `/GenerateIT` response into a `(integrityTokenU8Array, ttlSecs)` pair.
     *
     * Raw shape: `[integrityTokenBase64, ttlSecs, mintRefreshThreshold, websafeFallback]`.
     * We return the integrity token as a JS `new Uint8Array([…])` literal so it can
     * be injected directly into the WebView's globals.
     */
    fun parseIntegrityTokenData(rawGenerateITResponse: String): Pair<String, Long> {
        val arr = JSONArray(rawGenerateITResponse)
        val tokenB64 = arr.optString(0, null)
            ?: throw PoTokenException("GenerateIT returned null integrityToken: $rawGenerateITResponse")
        val ttlSecs = arr.optLong(1, 0L)
        if (ttlSecs <= 0L) throw PoTokenException("GenerateIT returned invalid ttl: $rawGenerateITResponse")
        val bytes = decodeYouTubeBase64(tokenB64)
        return newUint8ArrayJs(bytes) to ttlSecs
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /**
     * Decode YouTube-flavoured base64 (URL-safe variant with `.` padding),
     * then add 97 to every byte to undo the server's "scramble".
     */
    private fun descramble(scrambled: String): String {
        val raw = decodeYouTubeBase64(scrambled)
        val out = ByteArray(raw.size) { i -> (raw[i] + 97).toByte() }
        return out.toString(Charsets.UTF_8)
    }

    /** Decode `-`/`_`/`.` variant base64 used by YouTube's APIs. */
    private fun decodeYouTubeBase64(s: String): ByteArray {
        val normalized = s.replace('-', '+').replace('_', '/').replace('.', '=')
        return Base64.decode(normalized, Base64.DEFAULT)
    }

    /** First string element in a JSONArray, or null. */
    private fun JSONArray.findFirstString(): String? {
        for (i in 0 until length()) {
            val v = opt(i)
            if (v is String) return v
        }
        return null
    }

    /** Render a `new Uint8Array([…])` JS literal. */
    private fun newUint8ArrayJs(bytes: ByteArray): String =
        bytes.joinToString(prefix = "new Uint8Array([", postfix = "])") {
            it.toUByte().toString()
        }

    /** Minimal JSON serializer for nested Map/String/null values. */
    private fun toJsonLiteral(value: Any?): String = when (value) {
        null -> "null"
        is String -> org.json.JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        is Map<*, *> -> value.entries.joinToString(
            prefix = "{", postfix = "}", separator = ","
        ) { (k, v) -> "${org.json.JSONObject.quote(k as String)}:${toJsonLiteral(v)}" }
        else -> error("Unsupported value type in challenge JSON: ${value::class}")
    }
}
