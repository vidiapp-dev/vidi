package com.example.ytclient.ui.home

import com.example.ytclient.core.local.DiscoveredChannel
import com.example.ytclient.core.local.HistoryEntry
import com.example.ytclient.core.local.LibraryStore
import com.example.ytclient.core.local.Playlist
import com.example.ytclient.core.local.RelativeTime
import com.example.ytclient.youtube.innertube.HomeFeedEngine
import com.example.ytclient.youtube.models.SearchVideo
import kotlin.math.pow

/**
 * Home feed personalization — phases 1-7 of HOME_FEED_PERSONALIZATION_PLAN.md.
 *
 * The JS runtime / bridge only ever GENERATES candidates (round-robin across a multi-topic
 * discovery blend, subscribed-channel uploads, related-to-history, search terms — see
 * pullHomeSource/fetchTopicsBlend in index.js) with no scoring at all; order is purely "whichever
 * source's turn it is." This class
 * is the first actual ranking pass, applied client-side against [LibraryStore] (which the JS
 * runtime has no access to and shouldn't need). Phase 1 added the block-list filter and a
 * subscribed/history-affinity score; phase 2 refined the affinity's recency curve (see
 * [recencyWeight]); phase 3 added post-score diversity + exploration (see [interleave]); phase 5
 * folded playlist additions into the same affinity inputs (see [weightedSignals]); phase 6 gave
 * [FeedReason] enough detail to build honest "Because you watched X" labels (see
 * HomeViewModel.reasonLabel) instead of a generic one; phase 7 (stretch) added a small score bump +
 * [FeedReason.Discovered] for channels surfaced by `YoutubeBridge.discoverSimilarChannels` (cached
 * in [LibraryStore.discoveredChannels]) — a much weaker, speculative signal than an actual
 * subscription or watch, so it's deliberately the lowest-priority reason above the plain no-signal
 * fallback (see [reasonFor]) and a small [DISCOVERED_BONUS].
 *
 * (Phase 4's title-keyword affinity — "Because you watch 'X' videos" — was removed: tokenizing
 * titles/tags into single words and matching them against OTHER videos' titles produced too many
 * false positives on common words ("hour", "been", …) that happened to recur across unrelated
 * videos without being a real topic signal. Channel affinity ([channelAffinity]) and the explicit
 * subscribe/discover signals below don't have that problem — they're identity-based, not
 * word-overlap-based — so for a long time they were the only affinity signals left.)
 *
 * Phase 8 added [FeedReason.TagInterest] — a deliberately narrow revival of the same idea Phase 4
 * dropped, but matching *uploader-curated tags* ([weightedSignals]-style history weighting, see
 * [tagAffinity]) against candidate titles instead of generic title-token overlap. Tags are
 * specific phrases assigned by the uploader ("unreal engine 5"), not arbitrary common words, so
 * the false-positive risk is materially lower — see [matchedTag]. Phase 9 replaced the old
 * channel-only [roundRobinByChannel] with [diversify], which also spreads out same-topic runs
 * (see [SearchVideo.sourceTag]) while following the score order more closely.
 *
 * Phase 16 added [FeedReason.Topic]: a topic-blend pick (see [SearchVideo.sourceTag]) with no
 * stronger reason above it now names its topic instead of falling into [FeedReason.Unpersonalized],
 * so [HomeGrouper] can give each enabled "For you" topic its own row in the grouped layout instead
 * of merging them all into one catch-all "Recommended for you" shelf.
 *
 * Phase 12 (HOME_FEED_PERSONALIZATION_PLAN.md) changed how [rank] is CALLED, not what it does:
 * HomeViewModel now calls it once per NEWLY fetched batch (page 1, then once per loadMore) and
 * appends the result permanently, instead of re-running it over the WHOLE accumulated feed on
 * every signal change — a hide/subscribe event, or a background discovery run finishing, used to
 * shift the order of cards already scrolled past, which reads as unpleasant flicker while
 * scrolling. [previouslyShown] exists so a freshly-ranked batch's [diversify] pass still avoids
 * repeating the channel/topic the PREVIOUS (already-frozen) batch ended on, even though that
 * previous batch is no longer being re-diversified together with this one. The one exception to
 * "never touch already-shown cards" is explicit hide/unhide (HomeViewModel filters those out of
 * the accumulated list live) — that's a deliberate removal, not a re-rank.
 */
class HomeRanker(private val library: LibraryStore) {

    /**
     * Why a given video was surfaced/boosted — phase 6 (HOME_FEED_PERSONALIZATION_PLAN.md):
     * carries enough detail (which channel) for HomeViewModel to build an honest "Because you
     * watched X" label instead of a generic one.
     */
    sealed interface FeedReason {
        /** channelId is subscribed, but no watch-history affinity for it (yet). */
        data object Subscribed : FeedReason

        /** channelId has watch/playlist affinity — [channelName] is [SearchVideo.author], already
         *  on hand, so no extra lookup is needed to build a label from it. Shown as "More from
         *  <channel>" (it was "Because you watched <channel>" until [WatchedVideo] existed — the
         *  two would otherwise read as the same claim about different things). */
        data class HistoryRelated(val channelName: String) : FeedReason

        /**
         * The candidate was fetched as a related video of [seedVideoId], a video the user actually
         * watched — i.e. it is in the feed BECAUSE of that watch, which is the literal meaning of
         * "Because you watched <title>". Comes from the history bucket's provenance tag
         * (`HomeFeedEngine.HISTORY_SOURCE_TAG_PREFIX`), not from any score.
         *
         * Checked FIRST in [reasonFor], above even [HistoryRelated]: provenance is the most
         * specific and most honest reason a card can have. The channel-affinity reasons answer
         * "why might you like this", whereas this one answers "why is it here at all".
         * [seedTitle] is the watched video's title from local history, never blank (a seed whose
         * title isn't known falls through to the other reasons rather than render an empty label).
         */
        data class WatchedVideo(val seedVideoId: String, val seedTitle: String) : FeedReason

        /** Phase 7 (stretch): channelId came from `YoutubeBridge.discoverSimilarChannels` — not
         *  subscribed, no watch/playlist affinity; the weakest reason above the plain no-signal
         *  fallback. [channelName] is the name recorded alongside the discovery (may be blank). */
        data class Discovered(val channelName: String) : FeedReason

        /** Phase 8: the candidate's title contains one of [tagAffinity]'s top-weighted tags —
         *  see [matchedTag]. Deliberately the WEAKEST affinity signal (only applied in [score]
         *  when nothing else already scored the candidate): it's a title-text match, not an
         *  identity match, so it's inherently less certain than [HistoryRelated]/[Subscribed]. */
        data class TagInterest(val tag: String) : FeedReason

        /** Phase 16 (HOME_FEED_PERSONALIZATION_PLAN.md): the candidate came from the "For you"
         *  topic blend (see [SearchVideo.sourceTag], format `"topic:<Name>"`, and fetchTopicsBlend
         *  in index.js) and nothing more specific above already explains it. Not an affinity signal
         *  the way [HistoryRelated]/[TagInterest] are — the round-robin picks these to keep every
         *  topic the user left enabled represented (see HomeViewModel.topicWeights), independent of
         *  whether the user has shown any actual interest in that topic yet. Naming the topic lets
         *  [HomeGrouper] give it its own row ("Music", "Gaming", …) instead of folding it into the
         *  unpersonalized catch-all. Checked LAST in [reasonFor], after every identity/tag-based
         *  reason above, so a topic-sourced video that's ALSO from a channel you watch or subscribe
         *  to still gets that more specific (and more honest) reason instead. */
        data class Topic(val topic: String) : FeedReason

        /** No personalization signal at all — plain round-robin filler from the multi-topic
         *  discovery blend (see fetchTopicsBlend in index.js: the same category terms as the
         *  home screen's own filter chips, e.g. Music/Gaming/News, blended together) whose
         *  [SearchVideo.sourceTag] didn't survive to here, or wasn't set. Deliberately NOT labeled
         *  in the UI (see HomeViewModel.reasonInfo) — unlike the other reasons here, this one has
         *  nothing honest to say about WHY a given video showed up, so it stays quiet instead of
         *  stamping every unpersonalized card with the same caption. */
        data object Unpersonalized : FeedReason

        /** A phase 3 [injectExploration] pick — deliberately NOT claiming to be a strong match. */
        data object Exploration : FeedReason
    }

    /**
     * One scored candidate. [score] is the raw output of this class's own `score(…)` for the video,
     * RETAINED (phase 15, HOME_FEED_PERSONALIZATION_PLAN.md) rather than discarded after the sort:
     * the flat feed only ever needed the resulting ORDER, but the grouped-row layout re-buckets the same
     * already-ranked list by [reason] and has to order the resulting rows against each other (see
     * [HomeGrouper]), which needs the actual magnitudes back — recomputing them there would mean
     * re-reading the library's live signals long after this batch was frozen (phase 12), producing
     * numbers that no longer match the order the cards were shown in.
     *
     * Note that [score] is NOT the position: [interleave]'s diversify/exploration passes deliberately
     * reorder away from a pure score sort, and an [FeedReason.Exploration] pick keeps the low score
     * that got it buried in the first place (it was promoted for variety, not because it scored well).
     */
    data class Ranked(val video: SearchVideo, val reason: FeedReason, val score: Float = 0f)

    /**
     * Filters out anything the user hid (channel or exact video) or has already essentially
     * finished watching, scores the rest, and returns them ordered by score descending. Ties (e.g.
     * two videos with no affinity signal at all, the common case for a first-week install) keep
     * their relative input order, so this degrades gracefully to "whatever the round-robin already
     * produced" when there's no local signal yet.
     *
     * [candidates] should be just the NEWLY fetched batch (see the class doc's note on phase 12) —
     * [previouslyShown] is the tail of whatever's already been ranked+displayed before this batch,
     * used ONLY to seed [diversify]'s same-channel/same-topic lookback so this batch doesn't open
     * with a repeat of whatever the previous one just ended on. A handful of entries (a few more
     * than [DIVERSITY_WINDOW]) is enough — pass more than needed, [diversify] only reads the tail.
     */
    fun rank(candidates: List<SearchVideo>, previouslyShown: List<SearchVideo> = emptyList()): List<Ranked> {
        if (candidates.isEmpty()) return emptyList()

        val subscribedIds = library.subscribedChannelIds.value
        val hiddenChannelIds = library.hiddenChannelIds.value
        val hiddenVideoIds = library.hiddenVideoIds.value
        // A video you've already (near-)finished has nothing left to offer as a RESULT — you've seen
        // it — but it should still count fully toward channelAffinity below (already weighted by
        // watchedFraction, so a fully-watched video contributes its full weight there; this only
        // stops the video ITSELF from re-surfacing).
        // Everything derived from library snapshots comes from ONE memoised pass — see
        // [affinities]. That now includes the fully-watched id set and the discovered-channel
        // names, which used to be rebuilt per call: both are pure functions of the same immutable
        // snapshots the affinity maps are keyed on, so recomputing them per batch was scanning the
        // whole history and discovery cache again to get an identical answer. The tag pool is
        // likewise computed once per call rather than per candidate.
        val snapshot = affinities()
        val affinity = snapshot.channel
        val tagPool = snapshot.tags
        val discovered = snapshot.discovered
        val fullyWatchedIds = snapshot.fullyWatched

        val visible = candidates.filter { v ->
            !hiddenVideoIds.contains(v.id) &&
                !(v.channelId != null && hiddenChannelIds.contains(v.channelId)) &&
                v.id !in fullyWatchedIds
        }

        val scored = visible
            .map { v -> v to score(v, subscribedIds, affinity, discovered, tagPool) }
            .sortedByDescending { (_, s) -> s }
            .map { (v, s) -> Ranked(v, reasonFor(v, subscribedIds, affinity, discovered, tagPool, snapshot.historyTitles), s) }

        return interleave(scored, previouslyShown)
    }

    private fun score(
        v: SearchVideo,
        subscribedIds: Set<String>,
        affinity: Map<String, Float>,
        discovered: Map<String, String>,
        tagPool: List<String>,
    ): Float {
        var s = 0f
        if (v.channelId != null && subscribedIds.contains(v.channelId)) {
            s += SUBSCRIBED_BONUS
            // A RECENT upload from a subscribed channel is the single strongest "show me this now"
            // signal the feed has — a subscription is an explicit ask for that channel's new
            // videos, and the fully-watched filter above already guarantees anything left here is
            // unwatched. Boosted decisively above every affinity signal (whose sums rarely exceed
            // a few points) so fresh subscription uploads land at/near the top of the batch, with
            // the bonus tapering linearly across the window so the newest of them sort first.
            // Best-effort by nature: `published` is YouTube's relative text ("3 hours ago"), so a
            // card without a parseable timestamp just falls back to the plain SUBSCRIBED_BONUS.
            val publishedMs = RelativeTime.approxEpochMs(v.published)
            if (publishedMs != null) {
                val age = System.currentTimeMillis() - publishedMs
                if (age in 0..FRESH_SUB_WINDOW_MS) {
                    s += FRESH_SUB_BONUS * (1f - age.toFloat() / FRESH_SUB_WINDOW_MS)
                }
            }
        }
        s += (v.channelId?.let(affinity::get) ?: 0f) * AFFINITY_WEIGHT
        if (v.channelId != null && discovered.containsKey(v.channelId)) s += DISCOVERED_BONUS
        // Weakest signal, so it only ever applies when nothing identity-based already scored this
        // candidate — see FeedReason.TagInterest's doc.
        if (s <= 0f && matchedTag(v.title, tagPool) != null) s += TAG_INTEREST_BONUS
        return s
    }

    private fun reasonFor(
        v: SearchVideo,
        subscribedIds: Set<String>,
        affinity: Map<String, Float>,
        discovered: Map<String, String>,
        tagPool: List<String>,
        historyTitles: Map<String, String>,
    ): FeedReason {
        // Provenance first — see FeedReason.WatchedVideo for why it outranks channel affinity.
        v.sourceTag?.takeIf { it.startsWith(HomeFeedEngine.HISTORY_SOURCE_TAG_PREFIX) }?.let { tag ->
            val seedId = tag.removePrefix(HomeFeedEngine.HISTORY_SOURCE_TAG_PREFIX)
            historyTitles[seedId]?.let { title -> return FeedReason.WatchedVideo(seedId, title) }
        }
        if (v.channelId != null && (affinity[v.channelId] ?: 0f) > 0f) {
            return FeedReason.HistoryRelated(v.author.ifBlank { "a channel you've watched" })
        }
        if (v.channelId != null && subscribedIds.contains(v.channelId)) {
            return FeedReason.Subscribed
        }
        if (v.channelId != null) {
            discovered[v.channelId]?.let { name ->
                return FeedReason.Discovered(name.ifBlank { v.author.ifBlank { "a similar channel" } })
            }
        }
        matchedTag(v.title, tagPool)?.let { tag -> return FeedReason.TagInterest(tag) }
        v.sourceTag?.takeIf { it.startsWith(TOPIC_SOURCE_TAG_PREFIX) }?.let { tag ->
            val topic = tag.removePrefix(TOPIC_SOURCE_TAG_PREFIX)
            if (topic.isNotBlank()) return FeedReason.Topic(topic)
        }
        return FeedReason.Unpersonalized
    }

    /**
     * [channelAffinity] + [topTags]`(`[tagAffinity]`)`, computed together and cached — see
     * [affinities].
     */
    private class Affinities(
        val channel: Map<String, Float>,
        val tags: List<String>,
        /** channelId → name, from the discovery cache. */
        val discovered: Map<String, String>,
        /** Videos already (near-)finished, which [rank] filters out of its candidates. */
        val fullyWatched: Set<String>,
        /** videoId → title for watched videos, blank titles excluded — names the seed of a
         *  [FeedReason.WatchedVideo]. Same snapshot as everything else here, so it's memoised with
         *  them rather than rebuilt per batch. */
        val historyTitles: Map<String, String>,
        val historyRef: List<HistoryEntry>,
        val playlistsRef: List<Playlist>,
        val discoveredRef: List<DiscoveredChannel>,
        val computedAtMs: Long,
    )

    @Volatile private var cachedAffinities: Affinities? = null

    /**
     * The library-wide affinity maps [rank] scores against, memoised per `HomeRanker` instance.
     *
     * Both passes are O(whole library), not O(batch): [channelAffinity] walks every history entry
     * AND every video in every playlist, and [tagAffinity] walks every history entry's uploader tag
     * list with a `trim()` + `lowercase()` per tag — which, at [LibraryStore.MAX_HISTORY] entries
     * carrying a few dozen tags each, is tens of thousands of string allocations. [rank] is called
     * once per fetched batch, and the grouped home layout fetches several batches back-to-back on
     * cold start, so this ran repeatedly over identical input during exactly the window the feed is
     * trying to become responsive.
     *
     * Two invalidation conditions, both required:
     *
     *  - **Input identity.** `library.history.value` / `playlists.value` are immutable snapshots
     *    replaced wholesale on every mutation (see LibraryStore's `persist*` functions), so
     *    reference equality is a sound "nothing changed" test — not merely a heuristic.
     *  - **Age.** [recencyWeight] blends in a [SESSION_HALF_LIFE_MS] component, so the weights do
     *    drift with wall-clock time even when the inputs don't. [AFFINITY_CACHE_TTL_MS] bounds that
     *    drift to a minute, which against a half-life measured in hours is far below anything that
     *    could reorder a batch — and well under the time between two user-initiated fetches.
     *
     * Note this is per-instance, and `HomeRanker` is constructed per `HomeViewModel`, so the cache
     * dies with the screen; it exists to collapse a burst, not to persist anything.
     */
    private fun affinities(): Affinities {
        val history = library.history.value
        val playlists = library.playlists.value
        val discoveredChannels = library.discoveredChannels.value
        val now = System.currentTimeMillis()
        cachedAffinities?.let { cached ->
            if (cached.historyRef === history &&
                cached.playlistsRef === playlists &&
                cached.discoveredRef === discoveredChannels &&
                now - cached.computedAtMs < AFFINITY_CACHE_TTL_MS
            ) {
                return cached
            }
        }
        return Affinities(
            channel = channelAffinity(),
            tags = topTags(tagAffinity()),
            discovered = discoveredChannels.associate { it.channelId to it.name },
            // A video you've already (near-)finished has nothing left to offer as a RESULT — you've
            // seen it — but it should still count fully toward channelAffinity (already weighted by
            // watchedFraction, so a fully-watched video contributes its full weight there; this only
            // stops the video ITSELF from re-surfacing).
            fullyWatched = history
                .asSequence()
                .filter { it.watchedFraction >= FULLY_WATCHED_THRESHOLD }
                .mapTo(HashSet()) { it.videoId },
            historyTitles = history.asSequence()
                .filter { it.title.isNotBlank() }
                .associate { it.videoId to it.title },
            historyRef = history,
            playlistsRef = playlists,
            discoveredRef = discoveredChannels,
            computedAtMs = now,
        ).also { cachedAffinities = it }
    }

    /** A single weighted "interest" event feeding [channelAffinity] — see [weightedSignals] for
     *  where these come from. */
    private data class Signal(val channelId: String?, val weight: Float)

    /**
     * Every local signal of "you're interested in this," reduced to a common shape so
     * [channelAffinity] doesn't need to know about every source:
     *  - Watch history: weighted by [HistoryEntry.watchedFraction] (finished > glanced-at) ×
     *    [recencyWeight] of [HistoryEntry.lastPlayedAtMs].
     *  - Phase 5 (HOME_FEED_PERSONALIZATION_PLAN.md): playlist additions (including Watch Later).
     *    Adding a video is a deliberate action — stronger than a passive watch you might not have
     *    even finished — so it's weighted as [PLAYLIST_ADD_WEIGHT] (~as if 80% watched) rather than
     *    an actual watched fraction, which playlist entries don't have. Recency uses
     *    [PlaylistVideo.addedAtMs] the same way history uses [HistoryEntry.lastPlayedAtMs]. A video
     *    that's both watched AND saved contributes from both — that's intentional, not double
     *    counting: two independent signals of interest should add up.
     */
    private fun weightedSignals(): List<Signal> {
        val now = System.currentTimeMillis()
        val fromHistory = library.history.value.map { h ->
            Signal(h.channelId, h.watchedFraction * recencyWeight(now - h.lastPlayedAtMs))
        }
        val fromPlaylists = library.playlists.value.flatMap { it.videos }.map { p ->
            Signal(p.channelId, PLAYLIST_ADD_WEIGHT * recencyWeight(now - p.addedAtMs))
        }
        return fromHistory + fromPlaylists
    }

    /**
     * channelId → affinity score, accumulated across [weightedSignals] (watch history + playlist
     * additions).
     */
    private fun channelAffinity(): Map<String, Float> {
        val byChannel = HashMap<String, Float>()
        for (s in weightedSignals()) {
            val channelId = s.channelId ?: continue
            if (s.weight <= 0f) continue
            byChannel[channelId] = (byChannel[channelId] ?: 0f) + s.weight
        }
        return byChannel
    }

    /**
     * Phase 8 (HOME_FEED_PERSONALIZATION_PLAN.md): tag → affinity score, mirroring
     * [channelAffinity]'s shape but over [HistoryEntry.tags] instead of channelId — same
     * watchedFraction × [recencyWeight] weighting, summed per tag across history. Tags shorter
     * than [MIN_TAG_LENGTH] are skipped: short tags ("hd", "4k", "new") are exactly the kind of
     * generic term that made Phase 4's title-keyword affinity noisy, and tags are cheap enough
     * (bounded by `LibraryStore.MAX_HISTORY` entries × however many tags each declared) that
     * dropping the short/generic ones costs nothing real.
     */
    private fun tagAffinity(): Map<String, Float> {
        val now = System.currentTimeMillis()
        val byTag = HashMap<String, Float>()
        for (h in library.history.value) {
            if (h.tags.isEmpty()) continue
            val weight = h.watchedFraction * recencyWeight(now - h.lastPlayedAtMs)
            if (weight <= 0f) continue
            for (raw in h.tags) {
                val tag = raw.trim()
                if (tag.length < MIN_TAG_LENGTH) continue
                val key = tag.lowercase()
                byTag[key] = (byTag[key] ?: 0f) + weight
            }
        }
        return byTag
    }

    /**
     * Phase 14 (HOME_FEED_PERSONALIZATION_PLAN.md): topic → affinity score, mirroring
     * [tagAffinity]'s shape (same watchedFraction × [recencyWeight] weighting, summed) but keyed by
     * [HistoryEntry.topic] — which home-feed TOPIC (if any) originally surfaced a watched video —
     * instead of uploader tags. PUBLIC, unlike [tagAffinity]/[channelAffinity]: this class's own
     * [rank]/[score] don't use it at all (a topic-sourced video's in-feed score/reason is unaffected
     * by which topic it came from). HomeViewModel reads this directly to build the per-topic
     * round-robin WEIGHTS it passes to [com.example.ytclient.youtube.bridge.YoutubeBridge.getHomeFeed]
     * — biasing how often an enabled topic gets a turn in the JS-side round-robin, entirely upstream
     * of ranking. The topic on/off toggle ([LibraryStore.setTopicEnabled]) stays the only way to
     * exclude a topic outright; this only shifts emphasis among whatever's left enabled.
     */
    fun topicAffinity(): Map<String, Float> {
        val now = System.currentTimeMillis()
        val byTopic = HashMap<String, Float>()
        for (h in library.history.value) {
            val topic = h.topic ?: continue
            val weight = h.watchedFraction * recencyWeight(now - h.lastPlayedAtMs)
            if (weight <= 0f) continue
            byTopic[topic] = (byTopic[topic] ?: 0f) + weight
        }
        return byTopic
    }

    /** The [TAG_MATCH_POOL] highest-weighted tags from [affinity] — computed once per [rank] call
     *  (not once per candidate) since it doesn't depend on the candidate being scored. */
    private fun topTags(affinity: Map<String, Float>): List<String> =
        affinity.entries.sortedByDescending { it.value }.take(TAG_MATCH_POOL).map { it.key }

    /**
     * The first of [tagPool] that appears verbatim in [title] (case-insensitive substring match),
     * or null. A substring match on a multi-word tag phrase ("unreal engine 5") is far more
     * specific than Phase 4's removed single-word title-token overlap — see the class doc's note
     * on why tags were re-introduced where generic keywords weren't. Still a plain substring
     * check, not word-boundary-aware, so a false positive is possible in principle; worth
     * revisiting (e.g. requiring word boundaries) if it turns out noisy in practice.
     */
    private fun matchedTag(title: String, tagPool: List<String>): String? {
        if (title.isBlank() || tagPool.isEmpty()) return null
        val lower = title.lowercase()
        return tagPool.firstOrNull { lower.contains(it) }
    }

    /**
     * Phase 2 (HOME_FEED_PERSONALIZATION_PLAN.md): two blended exponential-decay components
     * instead of one flat half-life, so recency behaves differently at different time scales
     * rather than a single curve trying to do both jobs at once:
     *  - [SESSION_HALF_LIFE_MS] (a few hours) makes whatever you watched *this session* dominate
     *    almost immediately — this is "session momentum" folded straight into recency, since
     *    [HistoryEntry.lastPlayedAtMs] already gives it to us for free with no separate in-memory
     *    tracker needed.
     *  - [LONG_HALF_LIFE_MS] (~10 days) keeps a mild, slowly-tapering pull from the rest of the
     *    history window, so a topic binged three weeks ago doesn't vanish outright, but also can't
     *    permanently outrank anything more recent the way the old flat 14-day half-life effectively
     *    let it (a video watched once 3 weeks ago and one watched 5 times yesterday used to decay
     *    at the exact same rate going forward).
     * Evenly blended: each component contributes up to 0.5 of the total 1.0, so `recencyWeight(0)`
     * is still 1.0 and the curve is continuous (no jump at either half-life's boundary).
     */
    private fun recencyWeight(ageMs: Long): Float {
        if (ageMs <= 0L) return 1f
        val session = 0.5 * halfLifeDecay(ageMs, SESSION_HALF_LIFE_MS)
        val longTail = 0.5 * halfLifeDecay(ageMs, LONG_HALF_LIFE_MS)
        return (session + longTail).toFloat()
    }

    private fun halfLifeDecay(ageMs: Long, halfLifeMs: Long): Double =
        0.5.pow(ageMs.toDouble() / halfLifeMs)

    /**
     * Phase 3/9 (HOME_FEED_PERSONALIZATION_PLAN.md): two post-score passes over the already-sorted
     * [scored] list — reordering only, nothing is added or dropped, so every candidate that
     * survived the block-list filter still appears somewhere.
     *  1. [diversify] — a straight score sort alone would let one strongly-affine channel, or one
     *     home-feed topic (see [SearchVideo.sourceTag]), cluster together and dominate the first
     *     screenful. This spreads both out while following the score order much more closely than
     *     Phase 3's original bucket-and-round-robin (see [diversify]'s own doc for why that
     *     mattered enough to replace).
     *  2. [injectExploration] — everything so far only ever promotes what's ALREADY scored well.
     *     Without a deliberate discovery mechanism the feed only ever narrows toward existing
     *     habits. This periodically pulls a lower-scored pick forward instead of leaving it buried
     *     at the tail, tagged [FeedReason.Exploration] so the UI can label it honestly instead of
     *     pretending it's a strong match.
     */
    private fun interleave(scored: List<Ranked>, previouslyShown: List<SearchVideo>): List<Ranked> =
        injectExploration(diversify(scored, previouslyShown))

    /**
     * Phase 9: redistributes [scored] so consecutive same-channel OR same-topic clustering is
     * broken up, while following the score order as closely as possible. Replaces Phase 3's
     * [roundRobinByChannel]-style hard bucket-and-round-robin, which diversified by channelId only
     * — a home-feed topic search (e.g. "Movies") tends to return many results from a handful of
     * large channels, so bucketing by channel alone incidentally reproduced topic-shaped runs
     * ("3 movie cards then 3 news cards") instead of actually breaking them up.
     *
     * Greedy, not bucketed: walk [scored] (already sorted by score, descending) and at each output
     * slot take the HIGHEST remaining score whose `channelId` AND `sourceTag` haven't BOTH already
     * appeared among the last [DIVERSITY_WINDOW] picks — falling back to the best-scoring
     * remaining item outright once nothing qualifies, so a short list, or one dominated by a
     * single channel/topic, still terminates and never drops a candidate. Null channelId/sourceTag
     * never collides with itself (nothing shared to diversify against).
     *
     * Phase 12: the lookback windows are SEEDED from [previouslyShown]'s tail (capped to
     * [DIVERSITY_WINDOW], same as the windows themselves stay capped to throughout) before this
     * batch's own picks start filling them — otherwise, since each batch is now diversified on its
     * own instead of together with the whole accumulated feed, this batch's first pick could
     * freely repeat the exact channel/topic the previous (already-frozen) batch just ended on.
     */
    private fun diversify(scored: List<Ranked>, previouslyShown: List<SearchVideo>): List<Ranked> {
        if (scored.size < 3) return scored
        val remaining = ArrayDeque(scored)
        val recentChannels = ArrayDeque<String>()
        val recentTopics = ArrayDeque<String>()
        for (v in previouslyShown.takeLast(DIVERSITY_WINDOW)) {
            v.channelId?.let {
                recentChannels.addLast(it)
                if (recentChannels.size > DIVERSITY_WINDOW) recentChannels.removeFirst()
            }
            v.sourceTag?.let {
                recentTopics.addLast(it)
                if (recentTopics.size > DIVERSITY_WINDOW) recentTopics.removeFirst()
            }
        }
        val out = ArrayList<Ranked>(scored.size)
        while (remaining.isNotEmpty()) {
            val idx = remaining.indexOfFirst { r ->
                (r.video.channelId == null || r.video.channelId !in recentChannels) &&
                    (r.video.sourceTag == null || r.video.sourceTag !in recentTopics)
            }
            val picked = remaining.removeAt(if (idx >= 0) idx else 0)
            out.add(picked)
            picked.video.channelId?.let {
                recentChannels.addLast(it)
                if (recentChannels.size > DIVERSITY_WINDOW) recentChannels.removeFirst()
            }
            picked.video.sourceTag?.let {
                recentTopics.addLast(it)
                if (recentTopics.size > DIVERSITY_WINDOW) recentTopics.removeFirst()
            }
        }
        return out
    }

    /**
     * Walks [diversified] and, every [EXPLORATION_INTERVAL] slots, pulls the next item from the
     * bottom [EXPLORATION_POOL_FRACTION] of the list (the "best of the otherwise-buried" — still
     * relatively ordered among themselves) forward into that slot, re-tagged EXPLORATION. Anything
     * not pulled forward stays at the tail in its original relative order — so on a short list nothing
     * meaningfully changes (there's no long tail to draw from yet), and on a longer one only a small,
     * bounded fraction of slots are ever exploration picks.
     */
    private fun injectExploration(diversified: List<Ranked>): List<Ranked> {
        if (diversified.size <= EXPLORATION_INTERVAL) return diversified
        val tailStart = (diversified.size * (1 - EXPLORATION_POOL_FRACTION)).toInt().coerceIn(0, diversified.size)
        val head = diversified.subList(0, tailStart)
        val pool = ArrayDeque(diversified.subList(tailStart, diversified.size))
        if (pool.isEmpty()) return diversified

        val out = ArrayList<Ranked>(diversified.size)
        var sincePick = 0
        for (item in head) {
            out.add(item)
            sincePick++
            if (sincePick >= EXPLORATION_INTERVAL && pool.isNotEmpty()) {
                out.add(pool.removeFirst().copy(reason = FeedReason.Exploration))
                sincePick = 0
            }
        }
        out.addAll(pool) // whatever wasn't pulled forward stays at the tail, reason untouched
        return out
    }

    private companion object {
        const val SUBSCRIBED_BONUS = 1f
        // Fresh-upload boost for subscribed channels (see score()): sized to clear typical affinity
        // sums (a handful of recent watches of one channel lands around 2-6 points after
        // AFFINITY_WEIGHT) so a new subscription upload reliably outranks "more of what you
        // already watched" without needing a separate pinning pass.
        const val FRESH_SUB_BONUS = 8f
        const val FRESH_SUB_WINDOW_MS = 48L * 60 * 60 * 1000 // uploads younger than 2 days count as "new"
        const val AFFINITY_WEIGHT = 2f
        const val DISCOVERED_BONUS = 0.4f // phase 7 (stretch): weaker than a real subscribe/watch signal
        const val TAG_INTEREST_BONUS = 0.3f // phase 8: weakest signal — title-text match, not identity
        // "Fully or nearly-fully watched" — matches PlayerViewModel's own WATCHED_THRESHOLD (0.95f)
        // closely enough (0.9f is slightly more permissive, per the "almost fully watched" ask) that a
        // video no longer belongs in the RESULT list, while still counting at essentially full weight
        // toward channel affinity via HistoryEntry.watchedFraction in [weightedSignals].
        const val FULLY_WATCHED_THRESHOLD = 0.9f
        const val PLAYLIST_ADD_WEIGHT = 0.8f
        const val SESSION_HALF_LIFE_MS = 3L * 60 * 60 * 1000 // 3 hours
        const val LONG_HALF_LIFE_MS = 10L * 24 * 60 * 60 * 1000 // 10 days
        const val EXPLORATION_INTERVAL = 8 // ~1-in-8 slots is an exploration pick
        const val EXPLORATION_POOL_FRACTION = 0.4 // bottom 40% of the sorted list is "explorable"
        const val DIVERSITY_WINDOW = 2 // phase 9: no repeat channel/topic within this many picks
        const val MIN_TAG_LENGTH = 4 // phase 8: drop short/generic tags ("hd", "4k") from tagAffinity
        const val TAG_MATCH_POOL = 25 // phase 8: only the top-N weighted tags are ever matched against

        /**
         * How long a memoised affinity pass stays usable when its inputs haven't changed — see
         * [affinities]. One minute: long enough to collapse the grouped layout's cold-start burst
         * of back-to-back batches into a single pass, short enough that the drift against
         * [SESSION_HALF_LIFE_MS] (3 hours) is immaterial.
         */
        const val AFFINITY_CACHE_TTL_MS = 60_000L

        // Phase 16: prefix fetchTopicsBlend (index.js) stamps on a topic-blend pick's sourceTag —
        // see SearchVideo.sourceTag's doc. Matches the "personalized:<kind>" sibling convention used
        // for the other home-feed sources; reasonFor() strips this prefix to get the bare topic name.
        const val TOPIC_SOURCE_TAG_PREFIX = "topic:"
    }
}
