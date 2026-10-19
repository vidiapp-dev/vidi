package com.example.ytclient.youtube.runtime

import android.content.Context
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * Best-effort device region ("location") + language, used to bias Innertube's session-level
 * `gl`/`hl` params (see `Innertube.create({ location, lang })` in index.js's createAnonSession /
 * createAuthedSession) instead of youtubei.js's hardcoded 'US'/'en' defaults. Without this, the
 * home feed's News/Movies topic-blend sources always fetched the *US* edition of those categories
 * regardless of where the user actually is.
 *
 * [displayName] is [location]'s ENGLISH country name (e.g. "Netherlands" for "NL") — `gl`/`hl` is
 * only a SOFT ranking signal on a generic single-word query like "News", easily outranked by
 * globally popular international channels; index.js's `homeTopicQuery` folds this into the News
 * query TEXT itself (`"Netherlands news"`) for a much stronger signal. English (not the device's
 * own display language) is deliberate: it's the language most international news channels'
 * titles/descriptions are actually written in, regardless of what language the user's device is
 * set to — a Dutch-language query would miss plenty of relevant Dutch news channels that only
 * describe themselves in English/Dutch-mixed metadata.
 */
data class RegionInfo(
    val location: String?,
    val lang: String?,
    val displayName: String?,
)

/**
 * Prefers the SIM/registered-network country (via [TelephonyManager]) over the configured system
 * [Locale] — plenty of people run their phone with an English (US) language preference regardless
 * of where they physically are, so `Locale.getDefault().country` alone would silently claim "US"
 * for e.g. a Dutch user who just prefers English as a display language, defeating the whole point.
 * Falls back to the Locale's country when telephony is unavailable (WiFi-only tablet, no SIM,
 * airplane mode) and finally to `null` (→ the JS side's own 'US'/'en' defaults) if nothing usable
 * is found. Language always comes from Locale — the network doesn't carry a language signal.
 */
fun detectRegion(context: Context): RegionInfo {
    val locale = Locale.getDefault()
    val lang = locale.language.takeIf { it.length == 2 }

    val telephonyCountry = try {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        (tm?.networkCountryIso?.takeIf { it.isNotBlank() } ?: tm?.simCountryIso?.takeIf { it.isNotBlank() })
            ?.takeIf { it.length == 2 }
    } catch (_: Throwable) {
        null
    }

    val location = (telephonyCountry ?: locale.country.takeIf { it.length == 2 })?.uppercase(Locale.ROOT)
    // Locale.getDisplayCountry falls back to echoing the code itself (e.g. "XQ") when it doesn't
    // recognise it — guard against passing that through as if it were a real, useful query term.
    val displayName = location?.let { code ->
        Locale("", code).getDisplayCountry(Locale.ENGLISH).takeIf { it.isNotBlank() && it != code }
    }

    return RegionInfo(location, lang?.lowercase(Locale.ROOT), displayName)
}
