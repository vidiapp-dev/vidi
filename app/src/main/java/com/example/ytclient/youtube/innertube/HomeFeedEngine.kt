package com.example.ytclient.youtube.innertube

import android.os.SystemClock
import android.util.Log
import com.example.ytclient.youtube.models.SearchResult
import com.example.ytclient.youtube.models.SearchVideo
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * The home feed's candidate generator, natively — phase A3 of RUNTIME_PERF_PLAN.md.
 *
 * A faithful port of index.js's blend engine (`pullBlendedBatch` / `pullHomeSource` /
 * `fetchTopicsBlend` and friends), keeping every behavioural decision those carry:
 *
 *  - **Five weighted buckets** ([BUCKET_WEIGHTS]) — topics / subscribed channels / history-related /
 *    search terms / discovered channels — each ONE round-robin entry regardless of how many leaves
 *    it holds, so 10 subscriptions don't out-weight 1 search term just by being more numerous.
 *  - **Weighted-random bucket pick per turn**, and inside a group a **weighted-random leaf pick per
 *    VIDEO** (not per turn): handing one leaf a whole turn's [PER_TURN_PULL_CAP] is what used to
 *    make every third or fourth card come from the same category four times running.
 *  - **Per-turn caps with buffered leftovers** — a leaf's unused page stays on its own `pending`
 *    and is served on its next turn instead of being refetched or discarded.
 *  - **Shuffle + `sourceTag` stamping per source** ([tagSource]), feeding HomeRanker's diversify
 *    pass; ranking itself stays entirely on the Kotlin/HomeRanker side, as before.
 *
 * What's genuinely new versus the JS engine: every leaf fetch is a coroutine, so page 1 warms all
 * five buckets — and all topic queries within the topics bucket — truly concurrently, parsing
 * included. The single-threaded QuickJS engine could overlap the network waits but never the
 * parsing; here both overlap.
 */
class HomeFeedEngine(
    private val client: InnertubeClient,
    /** English display name of the user's region (e.g. "Netherlands"), for the localised News
     *  query — same signal `configureRegion`/`homeTopicQuery` use on the JS side. */
    private val regionName: String? = null,
) {

    // ── Source model ────────────────────────────────────────────────────────

    /** What a leaf fetches, and how its results get tagged. */
    sealed interface Kind {
        /** A topic search (the home screen's own category terms). [label] is the topic NAME, which
         *  is what the tag carries — not the underlying query text ("Netherlands news" → News). */
        data class Topic(val label: String, val query: String, val params: String?) : Kind

        /** A subscribed channel's uploads. */
        data class Channel(val channelId: String) : Kind

        /** A "similar channel" discovered from subscriptions (phase 7 signal). */
        data class Discover(val channelId: String) : Kind

        /** One of the user's recent search terms. */
        data class SearchTerm(val term: String) : Kind

        /** Watch-next of a recently watched video ("related to your history"). */
        data class Related(val videoId: String) : Kind
    }

    /** A round-robin participant: either a [Leaf] that fetches, or a [Group] of them. */
    sealed class Source {
        val pending: ArrayDeque<SearchVideo> = ArrayDeque()
        var exhausted: Boolean = false

        /** Truly done only once it can't fetch more AND its buffer is drained (leafSpent in JS). */
        val spent: Boolean get() = exhausted && pending.isEmpty()
    }

    class Leaf(val kind: Kind, var weight: Double = 1.0) : Source() {
        /** Continuation token for the next page; null before the first fetch or once exhausted. */
        var continuation: String? = null
        var started: Boolean = false
        /** Channel display name, learned on a channel/discover leaf's first page — used to
         *  backfill cards whose own author text YouTube omitted (fillMissingChannelAuthor in JS). */
        var channelName: String = ""
    }

    class Group(val subs: List<Source>, var weight: Double = 1.0) : Source() {
        init {
            if (subs.isEmpty()) exhausted = true
        }
    }

    /** One home-feed session: the weighted buckets plus every video id already handed out. */
    class Session(val buckets: List<Pair<Source, Double>>, val seen: MutableSet<String> = HashSet()) {
        /** Batches handed out so far — see [broadenTopics] for the one thing that reads it. */
        var batches: Int = 0
    }

    // ── Session construction ────────────────────────────────────────────────

    /**
     * Build a session and warm all five buckets CONCURRENTLY (each capped exactly like a later
     * turn, so turn one behaves like every other turn). Page 1 is then gated by the slowest single
     * bucket rather than their sum.
     */
    suspend fun createSession(
        enabledTopics: List<String>,
        topicWeights: Map<String, Float>,
        subscribedChannelIds: List<String>,
        historyVideoIds: List<String>,
        searchTerms: List<String>,
        discoverChannelIds: List<String>,
    ): Session {
        val topicGroup = Group(enabledTopics.shuffled().map { topic -> topicSource(topic, topicWeights[topic]) })
        val channelGroup = Group(subscribedChannelIds.map { Leaf(Kind.Channel(it)) })
        val historyGroup = Group(historyVideoIds.map { Leaf(Kind.Related(it)) })
        val searchGroup = Group(searchTerms.map { Leaf(Kind.SearchTerm(it)) })
        val discoverGroup = Group(discoverChannelIds.map { Leaf(Kind.Discover(it)) })

        val warmStartedAt = SystemClock.elapsedRealtime()
        coroutineScope {
            // The topics bucket warms WIDER than a normal turn ([INITIAL_TOPIC_BREADTH] vs
            // [PER_TURN_PULL_CAP]): it's the bucket a fresh profile leans on entirely, all of its
            // leaves fetch in this one concurrent round, and each search returns ~20 videos — so
            // paying for a few more topics here buys page 1 (and usually page 2) from buffers,
            // with no further round-trips, AND a wider category mix. The other buckets stay at the
            // normal cap; their leaves are per-channel/per-video and less interchangeable.
            listOf(
                topicGroup to INITIAL_TOPIC_BREADTH,
                channelGroup to PER_TURN_PULL_CAP,
                historyGroup to PER_TURN_PULL_CAP,
                searchGroup to PER_TURN_PULL_CAP,
                discoverGroup to PER_TURN_PULL_CAP,
            ).map { (src, cap) -> async { runCatching { warm(src, cap) } } }.awaitAll()
        }

        // Which topics this session will actually draw from, and which were never queried.
        //
        // Diagnostic, no behavior change — but it is the line that makes the single most
        // confusing thing about this blend visible. The opening warm covers
        // [INITIAL_TOPIC_BREADTH] leaves, and [fetch]'s buffered-first preference then keeps
        // picking from exactly those for as long as at least [PER_TURN_PULL_CAP] of them still
        // hold videos — which, at ~20 videos per search, is many pages. So "cold" below is not
        // "not yet"; in practice it is "not this session". Without this line the only visible
        // symptom is a topic the user explicitly enabled never appearing, with nothing to
        // distinguish "never queried" from "queried and nothing matched".
        if (topicGroup.subs.isNotEmpty()) {
            val warmed = topicGroup.subs.filter { hasBuffered(it) }.mapNotNull { topicLabelOf(it) }
            val cold = topicGroup.subs.filterNot { hasBuffered(it) }.mapNotNull { topicLabelOf(it) }
            Log.i(
                TAG,
                "session topics: ${enabledTopics.size} enabled, ${warmed.size} warmed " +
                    "(INITIAL_TOPIC_BREADTH=$INITIAL_TOPIC_BREADTH) — warm=$warmed cold=$cold",
            )
        }
        // The opening warm is the home feed's single biggest latency item and it is entirely
        // network — up to ~22 genuinely concurrent requests (InnertubeClient uses synchronous
        // `execute()`, which OkHttp's per-host limit never applies to; see the comment on
        // YoutubeBridgeImpl's OkHttpClient). So this number is the slowest of those requests, i.e.
        // mostly YouTube's own latency plus whatever else is competing for the network and the IO
        // pool at the time. Logged separately from the per-batch line so "the feed is slow" can be
        // split into warm vs. blend vs. rank without guessing.
        Log.i(TAG, "session warm: ${SystemClock.elapsedRealtime() - warmStartedAt} ms")

        val buckets = listOfNotNull(
            topicGroup.takeIf { it.subs.isNotEmpty() }?.let { it to BUCKET_WEIGHTS_TOPICS },
            channelGroup.takeIf { it.subs.isNotEmpty() }?.let { it to BUCKET_WEIGHTS_CHANNELS },
            historyGroup.takeIf { it.subs.isNotEmpty() }?.let { it to BUCKET_WEIGHTS_HISTORY },
            searchGroup.takeIf { it.subs.isNotEmpty() }?.let { it to BUCKET_WEIGHTS_SEARCH },
            discoverGroup.takeIf { it.subs.isNotEmpty() }?.let { it to BUCKET_WEIGHTS_DISCOVER },
        )
        return Session(buckets)
    }

    /** One topic's source: usually a single search leaf; "Movies" fans out into a nested group of
     *  complementary narrow queries (see [MOVIE_VARIANTS]) that round-robin among themselves. */
    private fun topicSource(topic: String, weight: Float?): Source {
        val w = weight?.toDouble()?.takeIf { it > 0 } ?: 1.0
        if (topic == "Movies") {
            return Group(
                MOVIE_VARIANTS.map { (query, duration) ->
                    Leaf(Kind.Topic("Movies", query, SearchParams.videosOfDuration(duration)))
                },
                weight = w,
            )
        }
        // News is localised: a bare "News" query is globally-popular-ranked and routinely surfaces
        // channels with no relevance to the user's country (same reasoning as homeTopicQuery).
        val query = if (topic == "News" && !regionName.isNullOrBlank()) "$regionName news" else topic
        return Leaf(Kind.Topic(topic, query, SearchParams.videosOnly), weight = w)
    }

    // ── Batch pulling ───────────────────────────────────────────────────────

    /** Collect up to [target] fresh (unseen) videos, picking a bucket weighted-randomly per turn. */
    suspend fun pullBatch(session: Session, target: Int? = null): List<SearchVideo> {
        val startedAt = SystemClock.elapsedRealtime()
        val firstBatch = session.batches++ == 0
        val want = target ?: if (firstBatch) FIRST_BATCH_TARGET else BATCH_TARGET
        // Page 1 is skipped deliberately: the opening warm already covered
        // [INITIAL_TOPIC_BREADTH] topics, and page 1's latency is the most visible number in the
        // app (A3 spent two on-device rounds getting it to ~1 network round). Broadening from page
        // 2 onward keeps that untouched and puts the cost on pages the user is already reading.
        if (!firstBatch) broadenTopics(session)
        val out = ArrayList<SearchVideo>(want)
        var attempts = 0
        while (out.size < want && attempts < MAX_BLEND_ATTEMPTS) {
            val live = session.buckets.filter { !it.first.exhausted }
            if (live.isEmpty()) break
            attempts++
            val picked = pickWeighted(live) ?: break
            val videos = pullCapped(picked, PER_TURN_PULL_CAP)
            for (v in videos) {
                if (v.id.isBlank() || !session.seen.add(v.id)) continue
                out.add(v)
            }
        }
        // Per-page source mix. Pairs with the session-topics line in [createSession]: that one says
        // which topics COULD appear, this one says which actually did. Note HomeGrouper needs
        // MIN_ROW_VIDEOS of a given reason before it gives it a row, so a topic showing 1-2 here is
        // fetched-but-invisible — a distinct state from both "never queried" and "has a shelf".
        Log.i(
            TAG,
            "batch ${session.batches}: ${out.size} videos in ${SystemClock.elapsedRealtime() - startedAt} ms " +
                "($attempts turns), sources=${out.groupingBy { it.sourceTag ?: "untagged" }.eachCount()}",
        )
        return out
    }

    /**
     * Bring [COLD_TOPICS_PER_BATCH] not-yet-queried topics into play, once per batch.
     *
     * ## Why this is needed at all
     *
     * [fetch] prefers leaves that already hold buffered videos and only warms cold ones when the
     * buffered set can't fill a turn's cap. That preference is right — it's what stopped page 1
     * paying ~3 network rounds for videos already fetched (A3's second on-device round) — but
     * combined with [INITIAL_TOPIC_BREADTH] it had a consequence nobody wrote down: one search
     * returns ~20 videos, so 6 warmed topic leaves keep `buffered.size >= PER_TURN_PULL_CAP` for
     * roughly 15 turns (~5 pages). Until then `cold` is never consulted, so **a user with 14
     * topics enabled sees videos from 6 of them, for the entire session.** That reads as the topic
     * toggles being broken, which is the one thing they must never read as.
     *
     * So breadth gets its own, explicit budget rather than relying on the turn loop to run out of
     * buffer. One batch brings in [COLD_TOPICS_PER_BATCH] topics, warmed CONCURRENTLY so the
     * latency cost is one round trip regardless of the count — the same trick the opening warm in
     * [createSession] uses. After this returns, those leaves are buffered and [fetch]'s ordinary
     * weighted pick can reach them like any other.
     *
     * Scoped to the TOPICS bucket only (identified by [topicLabelOf], since [Session] doesn't label
     * its buckets). The other buckets stay capped as they were: their leaves are per-channel or
     * per-video rather than interchangeable category queries, so "cover more of them" isn't the
     * same kind of win, and widening them would add round trips for no visible breadth.
     */
    private suspend fun broadenTopics(session: Session) {
        val topics = session.buckets.firstNotNullOfOrNull { (src, _) ->
            (src as? Group)?.takeIf { topicLabelOf(it) != null }
        } ?: return
        val cold = topics.subs.filter { !hasBuffered(it) && !it.exhausted }
        if (cold.isEmpty()) return
        val picks = pickDistinctWeighted(cold.map { it to leafWeight(it) }, COLD_TOPICS_PER_BATCH)
        if (picks.isEmpty()) return
        coroutineScope { picks.map { leaf -> async { runCatching { warm(leaf, 1) } } }.awaitAll() }
        Log.i(
            TAG,
            "broadened topics with ${picks.mapNotNull { topicLabelOf(it) }} — " +
                "${cold.size - picks.size} still never queried",
        )
    }

    /** Pull up to [cap] videos from [src], warming it first; leftovers stay buffered on [src]. */
    private suspend fun pullCapped(src: Source, cap: Int): List<SearchVideo> {
        warm(src, cap)
        val take = ArrayList<SearchVideo>(cap)
        while (take.size < cap && src.pending.isNotEmpty()) take.add(src.pending.removeFirst())
        return take
    }

    /** Ensure [src] has buffered content, fetching one page if empty (no-op when already warm). */
    private suspend fun warm(src: Source, cap: Int) {
        if (src.pending.isEmpty() && !src.exhausted) {
            src.pending.addAll(fetch(src, cap))
        }
    }

    /**
     * One fetch from [src]. For a [Group] this re-picks a leaf PER VIDEO (up to [cap]) so a single
     * turn returns a MIX, not one leaf's run — the fix that removed the "four cards from the same
     * category in a row" clumping. For a [Leaf] it's one raw page from the network.
     */
    private suspend fun fetch(src: Source, cap: Int): List<SearchVideo> = when (src) {
        is Group -> {
            val live = src.subs.filter { !it.spent }
            if (live.isEmpty()) {
                src.exhausted = true
                emptyList()
            } else {
                // Choose this turn's leaves up front (weighted, WITHOUT replacement so one turn is
                // a genuine mix), preferring leaves that ALREADY have buffered videos, and only
                // warming cold ones — concurrently — if the buffered ones can't fill the cap.
                //
                // Both halves matter, and each fixed a real regression against the JS engine:
                //  * CONCURRENT warming: picking and pulling one leaf at a time made every cold
                //    leaf its own sequential round-trip (~12 serial searches ⇒ ~20s page 1 on a
                //    fresh profile). fetchTopicsBlend had Promise.all'd every topic up front.
                //  * BUFFERED-FIRST: one search returns ~20 videos, so a handful of warm leaves
                //    already hold several pages' worth. Without this preference, later turns kept
                //    drawing cold leaves and paying another network round for videos we'd already
                //    fetched — page 1 cost ~3 round-trips instead of the 1 it actually needs.
                // [hasBuffered], not `pending.isNotEmpty()`. The latter asks only whether THIS
                // source holds a video, which is the wrong question for a nested group: "Movies"
                // ([MOVIE_VARIANTS]) keeps its ~20 fetched videos on its child leaves and only ever
                // one in its own `pending`, so the moment that one was taken it was classified
                // cold — and `cold` is only consulted when fewer than `cap` buffered leaves remain,
                // which almost never happens. Net effect: Movies was fetched once at session start
                // and then skipped for the rest of the session, while plain topic leaves kept their
                // turns. Asking "can this yield without a network call" is the question both
                // branches actually want, and it's true for a group whose children are warm.
                val buffered = live.filter { hasBuffered(it) }
                val chosen = ArrayList(pickDistinctWeighted(buffered.map { it to leafWeight(it) }, cap))
                if (chosen.size < cap) {
                    val cold = live.filter { !hasBuffered(it) && !it.exhausted }
                    val toWarm = pickDistinctWeighted(cold.map { it to leafWeight(it) }, cap - chosen.size)
                    if (toWarm.size > 1) {
                        coroutineScope {
                            toWarm.map { leaf -> async { runCatching { warm(leaf, 1) } } }.awaitAll()
                        }
                    }
                    chosen.addAll(toWarm)
                }
                val out = ArrayList<SearchVideo>(cap)
                for (leaf in chosen) {
                    if (out.size >= cap) break
                    out.addAll(pullCapped(leaf, 1))
                }
                // Top up from leaves that already have buffered content (never a network call), so
                // a turn whose chosen leaves came back short still fills its cap.
                if (out.size < cap) {
                    for (leaf in src.subs) {
                        if (out.size >= cap) break
                        if (leaf.pending.isEmpty()) continue
                        out.addAll(pullCapped(leaf, 1))
                    }
                }
                if (src.subs.all { it.spent }) src.exhausted = true
                out
            }
        }
        is Leaf -> runCatching { fetchLeaf(src) }.getOrElse { t ->
            Log.w(TAG, "leaf ${src.kind} failed: ${t.message}")
            src.exhausted = true
            emptyList()
        }
    }

    private fun leafWeight(s: Source): Double = when (s) {
        is Leaf -> s.weight
        is Group -> s.weight
    }

    /**
     * Whether [src] can yield a video without a network call.
     *
     * Recurses into a [Group], which [fetch]'s own `buffered` filter does NOT — and that gap is a
     * real asymmetry between topic kinds. A plain topic leaf holds a whole search page (~20 videos)
     * in its own `pending`, but a nested group ("Movies", which fans out into [MOVIE_VARIANTS])
     * keeps its ~20 on its CHILD leaves and only ever one video in its own `pending`. So the moment
     * that one is taken, `fetch` sees `pending.isEmpty()` and files Movies under `cold` — where it
     * is only reached when fewer than [PER_TURN_PULL_CAP] buffered leaves remain, which is rare.
     * [fetch] now classifies both `buffered` and `cold` through this, which is what fixes that.
     */
    private fun hasBuffered(src: Source): Boolean = when (src) {
        is Leaf -> src.pending.isNotEmpty()
        is Group -> src.pending.isNotEmpty() || src.subs.any { hasBuffered(it) }
    }

    /** The topic NAME behind a topics-bucket source, for logging — see [Kind.Topic.label]. Null for
     *  any other kind, so it doubles as a "is this a topic source" test. */
    private fun topicLabelOf(src: Source): String? = when (src) {
        is Leaf -> (src.kind as? Kind.Topic)?.label
        is Group -> src.subs.firstNotNullOfOrNull { topicLabelOf(it) }
    }

    /** Fetch one page from a leaf and advance/close it. */
    private suspend fun fetchLeaf(leaf: Leaf): List<SearchVideo> {
        val kind = leaf.kind
        // A started leaf with no continuation token has nothing left to fetch.
        if (leaf.started && leaf.continuation == null) {
            leaf.exhausted = true
            return emptyList()
        }
        val token = leaf.continuation
        val response = when {
            token != null -> when (kind) {
                is Kind.Topic, is Kind.SearchTerm -> client.searchContinuation(token)
                is Kind.Channel, is Kind.Discover -> client.browseContinuation(token)
                is Kind.Related -> client.nextContinuation(token)
            }
            else -> when (kind) {
                is Kind.Topic -> client.search(kind.query, kind.params)
                is Kind.SearchTerm -> client.search(kind.term, SearchParams.videosOnly)
                is Kind.Channel -> client.browse(kind.channelId, SearchParams.CHANNEL_VIDEOS_TAB)
                is Kind.Discover -> client.browse(kind.channelId, SearchParams.CHANNEL_VIDEOS_TAB)
                is Kind.Related -> client.next(kind.videoId)
            }
        }
        leaf.started = true

        // Watch-next carries the related list AND the comment section; scope the walk to
        // secondaryResults so neither the comments' continuation token nor their content can leak
        // into the feed. (Continuation responses are already scoped to what was requested.)
        val scoped = if (kind is Kind.Related && token == null) {
            InnertubeParser.subObject(response, "contents", "twoColumnWatchNextResults", "secondaryResults")
                ?: response
        } else {
            response
        }
        val collected = InnertubeParser.collect(scoped)
        leaf.continuation = collected.continuation
        if (collected.continuation == null) leaf.exhausted = true

        if (kind is Kind.Channel || kind is Kind.Discover) {
            if (leaf.channelName.isBlank()) leaf.channelName = InnertubeParser.channelTitle(response)
        }
        var videos = collected.videos
        if (kind is Kind.Related) videos = videos.filter { it.id != kind.videoId }
        if (leaf.channelName.isNotBlank()) {
            val cid = (kind as? Kind.Channel)?.channelId ?: (kind as? Kind.Discover)?.channelId
            videos = videos.map { v ->
                v.copy(
                    author = v.author.ifBlank { leaf.channelName },
                    channelId = v.channelId ?: cid,
                )
            }
        }
        return tagSource(videos, tagFor(kind))
    }

    private fun tagFor(kind: Kind): String = when (kind) {
        is Kind.Topic -> "topic:${kind.label}"
        is Kind.Channel -> "personalized:sub"
        is Kind.Discover -> "personalized:discover"
        is Kind.SearchTerm -> "personalized:search"
        // Carries the SEED video id — which watched video this related video was fetched for — so
        // HomeRanker can say "Because you watched <that video>" instead of the generic
        // "Recommended for you" these used to land in. See HISTORY_SOURCE_TAG_PREFIX.
        is Kind.Related -> HISTORY_SOURCE_TAG_PREFIX + kind.videoId
    }

    /**
     * Stamp [videos] with [tag] and shuffle them — the shuffle keeps a single source's slice from
     * reading as "YouTube's exact ranked list, chunked", and only ever reorders candidates that
     * HomeRanker would score equally anyway (its sort is stable, so ties keep input order).
     */
    private fun tagSource(videos: List<SearchVideo>, tag: String): List<SearchVideo> =
        videos.shuffled().map { it.copy(sourceTag = tag) }

    companion object {
        private const val TAG = "HomeFeedEngine"

        /**
         * `SearchVideo.sourceTag` prefix for a related-of-history pick: the full tag is this plus
         * the id of the watched video it was fetched for (`personalized:history:<seedVideoId>`).
         *
         * Public and defined once because HomeRanker parses it back out — it is the only way the
         * ranker knows a card exists BECAUSE of a specific watch. Until 2026-10-17 the tag was a
         * bare `personalized:history` the ranker never read, so these videos fell through every
         * reason to `Unpersonalized` and were shown under "Recommended for you", while the rows
         * titled "Because you watched …" were actually per-CHANNEL affinity rows that only formed
         * once three videos from one watched, unsubscribed channel happened to accumulate.
         */
        const val HISTORY_SOURCE_TAG_PREFIX = "personalized:history:"

        // Bucket weights (sum 20 → topics 20%, channels 30%, history 20%, search 15%, discover
        // 15%): subscribing is an explicit, high-confidence signal, so it outweighs the
        // speculative "discover" inference rather than competing with it as an equal.
        /**
         * Raised from 4.0 on 2026-10-04 (was: topics tied with history, below channels).
         *
         * Every other bucket's weight buys presence for ONE thing — a channel, a watched video, a
         * search term. This one is then split across every enabled topic, so at 14 topics on, a
         * weight unit here buys roughly a fourteenth of the per-topic presence the same unit buys
         * per channel elsewhere. At 4.0 of a 20.0 total that worked out to ~1-2 cards per topic per
         * page, which is below `HomeGrouper.MIN_ROW_VIDEOS` — so no topic ever earned a shelf once
         * the user had subscriptions and history, and the whole blend read as "enabling topics does
         * nothing". 10.0 of 26.0 (~38%) gives the strongest few topics enough picks to cross that
         * bar while the thin tail pools into HomeGrouper's "From your topics" row.
         *
         * This is the knob to turn back down if subscription/history content starts feeling
         * crowded out — note it only changes what gets FETCHED, not the order it renders in: the
         * subscriptions shelf is pinned first by HomeGrouper regardless, and
         * `HomeRanker.SUBSCRIBED_BONUS`/`FRESH_SUB_BONUS` still dominate the flat layout's sort.
         */
        private const val BUCKET_WEIGHTS_TOPICS = 10.0
        private const val BUCKET_WEIGHTS_CHANNELS = 6.0
        private const val BUCKET_WEIGHTS_HISTORY = 4.0
        private const val BUCKET_WEIGHTS_SEARCH = 3.0
        private const val BUCKET_WEIGHTS_DISCOVER = 3.0

        /** Videos pulled per bucket turn — small relative to a raw page so no single turn can
         *  dominate a batch. */
        const val PER_TURN_PULL_CAP = 4

        /** Target size for a CONTINUATION page (`getMoreHomeFeed`). See [FIRST_BATCH_TARGET] for
         *  why page 1 is deliberately no longer equal to it. */
        const val BATCH_TARGET = 12

        /**
         * Target size for page 1, which **must stay >= `LibraryStore.FEED_CACHE_MAX`**.
         *
         * D3's stale-while-revalidate shows the previous session's cached feed head (up to
         * `FEED_CACHE_MAX` = 30 cards) instantly, then REPLACES it wholesale when page 1 lands
         * (`HomeViewModel.rankAndReplace`). That design silently assumed the fresh page would be at
         * least as rich as the cache — true of the JS `getHomeFeed`, which is why three comments in
         * HomeScreen/HomeViewModel still describe page 1 as "~30 videos". The native A3 engine
         * served [BATCH_TARGET] = 12 for every page, so the cold-start refresh was a net CONTENT
         * LOSS: 30 cards collapsing to 12, and with them every shelf that needed
         * `HomeGrouper.MIN_ROW_VIDEOS` to exist. Reported from a real run as "the feed loads with
         * topics, then refreshes and all the topics disappear".
         *
         * Cheap in round trips despite the size: `createSession`'s opening warm has already
         * buffered a full search page (~20 videos) per warmed leaf across every bucket, so the
         * extra turns this adds drain buffers rather than hitting the network — draining them is
         * the whole point of having warmed them.
         */
        const val FIRST_BATCH_TARGET = 30

        /** How many topic leaves the session's opening (concurrent) warm covers — see its call
         *  site. Bigger than a turn's cap on purpose; still well under the ~14 enabled topics so a
         *  cold start doesn't fire every topic query at once. */
        private const val INITIAL_TOPIC_BREADTH = 6

        /**
         * Not-yet-queried topics pulled in per batch after page 1 — see [broadenTopics].
         *
         * Warmed concurrently, so 2 costs the same one round trip as 1 would. Sized so that with
         * `HomeTopics.ALL` at 14 and [INITIAL_TOPIC_BREADTH] at 6, the grouped layout's own
         * cold-start paging (`MAX_AUTO_PAGES = 3` in HomeScreen) reaches 12 of the 14 without the
         * user doing anything, and the last two arrive on the first scroll.
         */
        private const val COLD_TOPICS_PER_BATCH = 2

        /** Safety valve on the weighted-random picker (the old deterministic scan had a natural
         *  bound of one pass; random picking doesn't). */
        private const val MAX_BLEND_ATTEMPTS = 40

        /** Nothing in Innertube's search filters can express "trailer OR bloopers OR interview" —
         *  duration is a single enum and the query text has no OR — so Movies runs several
         *  complementary narrow queries side by side. Short bucket for teasers/bloopers, medium
         *  for featurettes/interviews (which routinely run 5-15 min and the short bucket drops). */
        private val MOVIE_VARIANTS = listOf(
            "movie trailer" to SearchParams.Duration.SHORT,
            "movie featurette" to SearchParams.Duration.MEDIUM,
            "movie bloopers" to SearchParams.Duration.SHORT,
            "movie cast interview" to SearchParams.Duration.MEDIUM,
        )

        /**
         * Weighted-random pick over [entries] (`value to weight`). Proportional to weight, so the
         * long-run mix matches the weights without a rigid repeating pattern. Internal-visible so
         * the distribution can be unit-tested.
         */
        internal fun <T> pickWeighted(entries: List<Pair<T, Double>>, roll: Double = Math.random()): T? {
            val i = pickWeightedIndex(entries, roll)
            return if (i >= 0) entries[i].first else null
        }

        /** [pickWeighted]'s index form — lets [pickDistinctWeighted] remove a pick by position
         *  rather than by value (values are Sources; identity/equality games aren't needed). */
        internal fun <T> pickWeightedIndex(entries: List<Pair<T, Double>>, roll: Double): Int {
            if (entries.isEmpty()) return -1
            val total = entries.sumOf { it.second }
            if (total <= 0.0) return -1
            var r = roll * total
            for (i in entries.indices) {
                r -= entries[i].second
                if (r <= 0.0) return i
            }
            return entries.size - 1
        }

        /**
         * Up to [count] DISTINCT weighted-random picks (sampling without replacement), so a single
         * group turn spreads across several leaves instead of possibly drawing the same one
         * repeatedly — and, crucially, so the leaves it will need can all be warmed at once.
         */
        internal fun <T> pickDistinctWeighted(
            entries: List<Pair<T, Double>>,
            count: Int,
            roll: () -> Double = { Math.random() },
        ): List<T> {
            val pool = entries.toMutableList()
            val out = ArrayList<T>(minOf(count, pool.size))
            while (out.size < count && pool.isNotEmpty()) {
                val i = pickWeightedIndex(pool, roll())
                if (i < 0) break
                out.add(pool.removeAt(i).first)
            }
            return out
        }
    }
}

/** The engine's result shape for a bridge call — mirrors what the JS getHomeFeed returned. */
fun homeFeedResult(videos: List<SearchVideo>, hasMore: Boolean): SearchResult =
    SearchResult(videos = videos, hasMore = hasMore)
