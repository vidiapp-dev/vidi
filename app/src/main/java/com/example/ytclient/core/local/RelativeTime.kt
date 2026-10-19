package com.example.ytclient.core.local

/**
 * Best-effort parser for YouTube's relative upload text ("3 days ago", "2 weeks ago", "Streamed 1
 * month ago", …) into an approximate epoch-ms, used only to ORDER videos from multiple channels.
 * Returns null when nothing recognisable is found (those sort last).
 */
object RelativeTime {

    // English + Dutch unit words: the app configures Innertube with the device's own hl (see
    // QuickJsRuntime.configureRegion), so on a Dutch device the relative text arrives as
    // "3 uur geleden" / "2 dagen geleden" — the English-only parser silently returned null for
    // every card there, which also silently disabled HomeRanker's fresh-subscription-upload boost.
    private val unitMs = mapOf(
        "second" to 1_000L, "seconde" to 1_000L, "seconden" to 1_000L,
        "minute" to 60_000L, "minuut" to 60_000L, "minuten" to 60_000L,
        "hour" to 3_600_000L, "uur" to 3_600_000L, "uren" to 3_600_000L,
        "day" to 86_400_000L, "dag" to 86_400_000L, "dagen" to 86_400_000L,
        "week" to 604_800_000L, "weken" to 604_800_000L,
        "month" to 2_629_800_000L, "maand" to 2_629_800_000L, "maanden" to 2_629_800_000L, // avg month
        "year" to 31_557_600_000L, "jaar" to 31_557_600_000L, "jaren" to 31_557_600_000L, // avg year
    )

    // Lazy unit group + optional plural 's' keeps English behavior identical ("days" → "day"),
    // while Dutch plurals ("dagen", "weken", …) match as whole words and hit their own map keys.
    private val regex = Regex("(\\d+)\\s*(\\p{L}+?)s?\\s*(ago|geleden)", RegexOption.IGNORE_CASE)

    fun approxEpochMs(text: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        if (text.isNullOrBlank()) return null
        val m = regex.find(text) ?: return null
        val amount = m.groupValues[1].toLongOrNull() ?: return null
        val unit = unitMs[m.groupValues[2].lowercase()] ?: return null
        return nowMs - amount * unit
    }
}
