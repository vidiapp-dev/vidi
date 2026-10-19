package com.example.ytclient.ui.home

/**
 * One horizontally-scrolling shelf in the grouped ("Netflix") home layout: a title, a stable
 * identity, and the cards to show in it.
 *
 * [key] is what Compose keys the outer `LazyColumn` item on (see HomeScreen's NETFLIX branch), so
 * it MUST stay stable for the same conceptual row across recompositions — it's derived from the
 * grouping reason (channelId / tag / a fixed constant), never from the row's position or contents,
 * both of which change every time [HomeGrouper.group] re-runs over a grown feed. [title] is
 * user-visible and may legitimately differ between two rows with the same shape (two different
 * "More from X" channels), which is exactly why it can't double as the key.
 *
 * [videos] carries the full [HomeRanker.Ranked] (not a bare `SearchVideo`) so the row keeps its
 * scores/reasons for anything downstream that wants them; the card itself only renders
 * [HomeRanker.Ranked.video].
 *
 * This is everything the row HOLDS (up to [HomeGrouper.MAX_ROW_VIDEOS]), which is not necessarily
 * everything it shows at once: HomeScreen's `HomeRowsList` reveals it a page at a time as the strip
 * is swiped right, and only asks the feed for more once it has run out. Keep it the full list —
 * truncating here would silently make those extra, already-fetched videos unreachable.
 */
data class HomeRow(
    val title: String,
    val key: String,
    val videos: List<HomeRanker.Ranked>,
)

/**
 * Phase 15 (HOME_FEED_PERSONALIZATION_PLAN.md): the grouped-row ("Netflix") home layout's pure
 * presentation pass — turns the flat, already-ranked feed into titled shelves.
 *
 * **This adds no ranking and no fetching.** [HomeRanker] has already scored every candidate and
 * stamped it with a [HomeRanker.FeedReason]; HomeViewModel has already accumulated those into its
 * append-only `_ranked` list (phase 12). All this does is re-bucket that same list by reason and
 * order the buckets — so the two layouts (classic flat list / grouped rows) are two views of
 * exactly the same scored data, and switching between them can never show different videos.
 *
 * ## Scope: per-row REVEAL, but no per-row fetch
 * A real shelf UI would give every row its OWN continuation ("keep pulling more from THIS channel /
 * THIS tag as the user swipes right"). That is still deliberately NOT here: the app has a single
 * flat home-feed continuation stream (`getMoreHomeFeed`, a round-robin across all sources — see
 * HomeFeedEngine / index.js), and giving each row an independent cursor would mean a new bridge
 * surface, N concurrent JS-side sessions through the single Mutex-guarded QuickJS call queue, and a
 * per-row hasMore/loading state to match.
 *
 * What swiping a row to its right-hand end DOES do, in two steps:
 *  1. Reveal more of what that row ALREADY holds. A row can accumulate up to [MAX_ROW_VIDEOS]
 *     entries while the UI shows a page at a time, so the first swipes past the edge are free —
 *     no fetch, no waiting, just cards that were already grouped into this shelf.
 *  2. Once the row has nothing left to reveal, fall through to the ordinary flat `loadMore()`.
 *     That grows the ONE shared stream, and [group] re-runs over the larger list, so some of what
 *     arrives may land in this row and some in others. Swiping to the end of "More from
 *     X" still does not fetch more X specifically — it fetches more FEED.
 *
 * So the shelves remain a lens on one feed rather than N independent feeds; the reveal step just
 * stops the lens from hiding videos it had already sorted into place. True per-row continuations
 * are still the obvious follow-up if the layout proves worth keeping.
 *
 * ## Row stability caveat (tension with phase 12)
 * Phase 12 froze the flat feed's ORDER so cards never shuffle under a scrolling user. Rows here are
 * ordered by aggregate score (see [Shelf.aggregateScore]), which is recomputed on every regroup, so
 * a later batch that drops a strong video into an older row CAN move that row up past a row above
 * it. In practice this is bounded — a reason's score profile is mostly set by the first batch it
 * appears in, and the pinned first/last rows (subscriptions / "Recommended for you") never move —
 * and [HomeRow.key] keeps Compose from re-creating the row's contents when it does shift. Left as a
 * known caveat rather than solved (e.g. by freezing row order on first paint) so v1 stays a pure
 * function of the ranked list; revisit if it reads as jumpy on-device.
 */
object HomeGrouper {

    /**
     * Buckets [ranked] into ordered [HomeRow]s.
     *
     * [subscribedChannelIds] is consulted DIRECTLY (not via [HomeRanker.FeedReason.Subscribed]) for
     * the subscriptions row — see [bucketFor] for why the reason alone is the wrong test here.
     *
     * Ordering, in full:
     *  1. "New from your subscriptions" — always first when it has anything at all, regardless of
     *     score. A subscription is an explicit standing request for a channel's uploads, so the row
     *     that answers it is a fixed piece of furniture, not a competitor in the score ranking (the
     *     flat layout expresses the same idea with `HomeRanker.FRESH_SUB_BONUS` instead).
     *  2. Every specific-reason row (per watched channel, per interest tag, plus the discovered-
     *     channels row), by descending [Shelf.aggregateScore], ties broken by first appearance in
     *     the feed so equal-scoring rows keep the order the ranker already put their content in.
     *  3. The pooled shelves, in [POOLS] order — "Based on what you've watched", then "From your
     *     topics" — each only when it has [MIN_ROW_VIDEOS] to show. The thin tail of every
     *     per-watched-video / per-topic row that couldn't fill a shelf alone; see the block that
     *     builds them for why that pooling is necessary rather than cosmetic. Pinned for the same
     *     reason as the catch-all (a pool's score averages across unrelated seeds or topics), but
     *     above it, because each still names a reason and that beats no signal at all.
     *  4. "Recommended for you" — always last, for the same reason the subscriptions row is always
     *     first, inverted: it's the no-specific-reason catch-all, so scoring it against rows that
     *     DO name a reason would be comparing a leftovers pile to a shelf.
     */
    fun group(ranked: List<HomeRanker.Ranked>, subscribedChannelIds: Set<String>): List<HomeRow> {
        if (ranked.isEmpty()) return emptyList()

        // Defensive, mirroring VideoList's own `distinctBy { it.id }`: HomeViewModel's append path
        // already de-dupes against what's on screen, but a duplicate id reaching a keyed LazyRow
        // would be a hard crash ("Key was already used"), which is a bad way to find out about a
        // regression upstream.
        val unique = ranked.distinctBy { it.video.id }

        val buckets = LinkedHashMap<String, Bucket>()
        unique.forEachIndexed { index, r ->
            val id = bucketFor(r, subscribedChannelIds)
            buckets.getOrPut(id.key) { Bucket(key = id.key, title = id.title, firstIndex = index) }
                .members
                .add(r)
        }

        // Both pinned rows are pulled out of the score-ordered middle before it's sorted.
        val subscriptions = buckets.remove(SUBSCRIPTIONS_KEY)
        val catchAll = buckets.remove(CATCH_ALL_KEY)

        // Rows too small to look like rows are FOLDED into the catch-all rather than dropped: a
        // single-card shelf reads as a rendering bug, but silently making its video unreachable in
        // this layout (while the classic list still shows it) would be worse — the two layouts are
        // supposed to be the same content, differently arranged.
        val catchAllMembers = ArrayList<HomeRanker.Ranked>(catchAll?.members ?: emptyList())
        // Under-sized rows whose key matches a [POOLS] entry pool into that entry's labelled shelf
        // instead of going straight to the catch-all — see the "Pooled shelves" block below.
        val pooled = POOLS.associateWith { ArrayList<HomeRanker.Ranked>() }
        val standalone = ArrayList<Shelf>(buckets.size)
        for (bucket in buckets.values) {
            if (bucket.members.size >= MIN_ROW_VIDEOS) {
                standalone.add(bucket.toShelf())
                continue
            }
            val pool = POOLS.firstOrNull { bucket.key.startsWith(it.keyPrefix) }
            if (pool != null) pooled.getValue(pool).addAll(bucket.members) else catchAllMembers.addAll(bucket.members)
        }
        standalone.sortWith(compareByDescending<Shelf> { it.aggregateScore }.thenBy { it.firstIndex })

        val out = ArrayList<HomeRow>(standalone.size + POOLS.size + 2)
        subscriptions?.takeIf { it.members.isNotEmpty() }?.let { out.add(it.toShelf().toRow()) }
        standalone.mapTo(out) { it.toRow() }

        // ── Pooled shelves ("Based on what you've watched", "From your topics") ────────────────
        //
        // One labelled shelf per pool, for every row of that KIND that couldn't fill a shelf of its
        // own, pinned in [POOLS] order just above the catch-all.
        //
        // Why these exist: a per-video or per-topic shelf needs [MIN_ROW_VIDEOS] picks sharing that
        // ONE reason, and the arithmetic rarely allows it early in a session. A bucket's share of
        // turns (`HomeFeedEngine.BUCKET_WEIGHTS_*`) is split across all of its leaves — every
        // enabled topic, every history seed — so each lands 1-2 cards per page and few qualify.
        // Before pooling, all of those fell into the unlabelled "Recommended for you" pile: a user
        // with 14 topics enabled reported that enabling topics appeared to do nothing, and the
        // related-to-your-history videos were indistinguishable from filler. The videos were there;
        // nothing said where they came from.
        //
        // Per-BUCKET, not per-card: a seed or topic strong enough for its own shelf still gets one
        // above (that path is unchanged), and only the thin tail collapses here. That makes these
        // rows self-dissolving in the good case — as one accumulates picks across pages it
        // graduates out of the pool into its own shelf.
        //
        // Pinned rather than score-ordered for the same reason the catch-all is: a pool's
        // [Shelf.aggregateScore] averages across unrelated seeds/topics, so comparing it to a shelf
        // that names one reason would compare an aggregate to a specific. Above the catch-all
        // because each still names its reason, which beats "no signal at all".
        for (pool in POOLS) {
            val members = pooled.getValue(pool)
            if (members.size >= MIN_ROW_VIDEOS) {
                out.add(
                    Shelf(
                        key = pool.key,
                        title = pool.title,
                        // Pinned, so never actually compared — recorded only to keep the type
                        // uniform, exactly as the catch-all does.
                        firstIndex = Int.MAX_VALUE,
                        videos = members.sortedByDescending { it.score },
                    ).toRow(),
                )
            } else {
                // Too thin even pooled — fall through to the catch-all rather than render a
                // two-card strip, which is the whole thing [MIN_ROW_VIDEOS] exists to prevent.
                catchAllMembers.addAll(members)
            }
        }
        // The catch-all is held to the same minimum as everything else, EXCEPT when nothing else
        // survived: on a very short feed (a cold start whose first batch was mostly hidden/watched)
        // a two-card "Recommended for you" still beats an empty screen. HomeScreen has its own
        // belt-and-braces fallback to the classic list if this ends up returning nothing at all.
        //
        // "Nothing else survived" now means the OUTPUT is still empty, not just `standalone` — it
        // previously ignored the subscriptions row, so a feed with subs plus two leftovers rendered
        // the two-card strip this minimum is meant to prevent. The topics row above would have
        // widened that same hole.
        if (catchAllMembers.isNotEmpty() && (catchAllMembers.size >= MIN_ROW_VIDEOS || out.isEmpty())) {
            out.add(
                Shelf(
                    key = CATCH_ALL_KEY,
                    title = CATCH_ALL_TITLE,
                    // Pinned last, so its first-appearance index is never actually compared against
                    // anything — recorded only so the type stays uniform.
                    firstIndex = catchAll?.firstIndex ?: Int.MAX_VALUE,
                    videos = catchAllMembers.sortedByDescending { it.score },
                ).toRow(),
            )
        }
        return out
    }

    /**
     * Which shelf [r] belongs in.
     *
     * The subscription test comes FIRST and reads [subscribedChannelIds] directly instead of
     * matching on [HomeRanker.FeedReason.Subscribed], because that reason means something narrower
     * than it sounds: `HomeRanker.reasonFor` checks watch affinity BEFORE subscription, so an upload
     * from a channel you're subscribed to AND actually watch — i.e. most of them — is labelled
     * [HomeRanker.FeedReason.HistoryRelated], and `Subscribed` is left meaning "subscribed but never
     * watched yet." That precedence is right for a per-card caption ("More from X" is the
     * more specific truth) but wrong for a shelf whose title promises everything new from your
     * subscriptions; grouping on the subscription set restores the promise. The cost is that a
     * subscribed channel you also watch no longer gets its own "More from X" row — which
     * is the correct trade: you already asked for that channel explicitly.
     *
     * Everything below that maps one [HomeRanker.FeedReason] variant to one bucket:
     *  - [HomeRanker.FeedReason.HistoryRelated] → one row PER channel, keyed by channelId where the
     *    card has one (two channels can share a display name; ids can't) and falling back to the
     *    lower-cased name otherwise. The title uses the reason's own `channelName`, which
     *    `reasonFor` already resolved to a non-blank string.
     *  - [HomeRanker.FeedReason.TagInterest] → one row per tag. Tags are already lower-cased by
     *    `HomeRanker.tagAffinity`, but the key lower-cases again defensively so a future change
     *    there can't silently split one tag across two rows.
     *  - [HomeRanker.FeedReason.Discovered] → ONE row for all of them, not one per discovered
     *    channel. Discovery is the weakest identity signal in the ranker (`HomeRanker.DISCOVERED_BONUS`
     *    is deliberately tiny) and a daily run yields many channels with a handful of videos each,
     *    so per-channel rows would nearly all fall under [MIN_ROW_VIDEOS] and collapse into the
     *    catch-all anyway. The title reuses HomeViewModel.reasonInfo's existing wording so the two
     *    layouts describe the same thing the same way.
     *  - [HomeRanker.FeedReason.Topic] → one row PER "For you" topic (e.g. "Music", "Gaming"),
     *    keyed by the lower-cased topic name and titled with the topic itself — this is the row
     *    the "topics get their own shelf" ask (phase 16) is actually about. Unlike every other
     *    per-identity row above, a video lands here only because nothing MORE specific already
     *    claimed it in `reasonFor` (a topic-blend pick from a channel you watch still becomes
     *    [HomeRanker.FeedReason.HistoryRelated] instead), so this row skews toward topic picks you
     *    have no other signal about yet.
     *  - [HomeRanker.FeedReason.Subscribed] → the subscriptions row (only reachable if a card's
     *    channelId is somehow absent from [subscribedChannelIds] — e.g. the user unsubscribed after
     *    this batch was ranked and frozen; the reason is still the honest description of why it's
     *    here).
     *  - [HomeRanker.FeedReason.Unpersonalized] / [HomeRanker.FeedReason.Exploration] → the
     *    catch-all. Neither names anything specific enough to title a shelf with: unpersonalized
     *    filler has nothing honest to say at all (see its own doc), and an exploration pick is a
     *    deliberate one-off oddball — a whole shelf of them would imply a theme they don't share.
     *    The flat layout's per-card "Something different" caption is the better home for that idea,
     *    and it still shows there.
     */
    private fun bucketFor(r: HomeRanker.Ranked, subscribedChannelIds: Set<String>): BucketId {
        val channelId: String? = r.video.channelId
        if (channelId != null && subscribedChannelIds.contains(channelId)) {
            return BucketId(SUBSCRIPTIONS_KEY, SUBSCRIPTIONS_TITLE)
        }
        return when (val reason = r.reason) {
            is HomeRanker.FeedReason.Subscribed -> BucketId(SUBSCRIPTIONS_KEY, SUBSCRIPTIONS_TITLE)
            // Was titled "Because you watched <channel>" until WatchedVideo rows existed — the same
            // words making two different claims (a channel you watch vs. a video you watched). The
            // KEY is unchanged so an existing row keeps its reveal state across the rename.
            is HomeRanker.FeedReason.HistoryRelated -> BucketId(
                key = "history:" + (channelId ?: reason.channelName.lowercase()),
                title = "More from ${reason.channelName}",
            )
            is HomeRanker.FeedReason.WatchedVideo -> BucketId(
                key = WATCHED_KEY_PREFIX + reason.seedVideoId,
                title = "Because you watched ${reason.seedTitle}",
            )
            is HomeRanker.FeedReason.TagInterest -> BucketId(
                key = "tag:${reason.tag.lowercase()}",
                title = "Because you watch \"${reason.tag}\" videos",
            )
            is HomeRanker.FeedReason.Discovered -> BucketId(DISCOVERED_KEY, DISCOVERED_TITLE)
            is HomeRanker.FeedReason.Topic -> BucketId(
                key = TOPIC_KEY_PREFIX + reason.topic.lowercase(),
                title = reason.topic,
            )
            is HomeRanker.FeedReason.Unpersonalized -> BucketId(CATCH_ALL_KEY, CATCH_ALL_TITLE)
            is HomeRanker.FeedReason.Exploration -> BucketId(CATCH_ALL_KEY, CATCH_ALL_TITLE)
        }
    }

    /** The shelf a single card maps to — resolved per card, collapsed into one [Bucket] per [key]. */
    private data class BucketId(val key: String, val title: String)

    /** Accumulator: everything sharing a [key], in feed order, plus where the row first appeared. */
    private class Bucket(val key: String, val title: String, val firstIndex: Int) {
        val members = ArrayList<HomeRanker.Ranked>()

        fun toShelf(): Shelf = Shelf(
            key = key,
            title = title,
            firstIndex = firstIndex,
            // Stable sort: cards tied on score (the norm inside a single-channel row, where every
            // member scores the same channel affinity) keep the order HomeRanker's diversify pass
            // already put them in.
            videos = members.sortedByDescending { it.score },
        )
    }

    /** A bucket resolved into "what would be rendered", with the metric rows are ordered by. */
    private class Shelf(
        val key: String,
        val title: String,
        val firstIndex: Int,
        /** Already sorted by score, descending. */
        val videos: List<HomeRanker.Ranked>,
    ) {
        /**
         * Mean of the row's top [ROW_SCORE_SAMPLE] scores — not the max, and not the mean of the
         * whole row. The max would let one outlier hoist a weak shelf: a single fresh subscription
         * upload carries `HomeRanker.FRESH_SUB_BONUS` (8f), an order of magnitude above a typical
         * affinity score, so any row containing one would pin itself to the top on the strength of
         * that one card. The full mean has the opposite failure: a long row is punished for its own
         * tail, so a shelf gets WORSE the more the feed gives it. A small top-N mean asks the right
         * question — "does this row have several genuinely strong cards to open with" — which is
         * also all the user sees before scrolling it.
         */
        val aggregateScore: Double = videos.take(ROW_SCORE_SAMPLE)
            .map { it.score.toDouble() }
            .average()
            // average() of an empty list is NaN; buckets are never empty, but NaN would sort
            // unpredictably rather than fail loudly, so it's pinned to 0.0 explicitly.
            .let { if (it.isNaN()) 0.0 else it }

        fun toRow(): HomeRow = HomeRow(title = title, key = key, videos = videos.take(MAX_ROW_VIDEOS))
    }

    /**
     * Titles/keys for the fixed rows. Kept as constants (rather than inlined at both the bucketing
     * and assembly sites) so the pinned-row lookups in [group] can't drift from what [bucketFor]
     * produces.
     */
    private const val SUBSCRIPTIONS_KEY = "subscriptions"
    private const val SUBSCRIPTIONS_TITLE = "New from your subscriptions"
    private const val DISCOVERED_KEY = "discovered"
    private const val DISCOVERED_TITLE = "Similar to channels you watch"
    private const val CATCH_ALL_KEY = "recommended"
    private const val CATCH_ALL_TITLE = "Recommended for you"
    private const val TOPICS_FALLBACK_KEY = "topics_mixed"
    private const val TOPICS_FALLBACK_TITLE = "From your topics"
    private const val WATCHED_FALLBACK_KEY = "watched_mixed"
    private const val WATCHED_FALLBACK_TITLE = "Based on what you've watched"

    /**
     * Key prefix for a per-watched-video row ([HomeRanker.FeedReason.WatchedVideo]). Shared by
     * [bucketFor] and [POOLS] for the same reason [TOPIC_KEY_PREFIX] is — the pool routing is a
     * string test on the key, so a drift would silently send every thin seed row to the unlabelled
     * catch-all with nothing failing.
     */
    private const val WATCHED_KEY_PREFIX = "watched:"

    /** An under-sized row of one kind, pooled into a single labelled shelf — see [group]. */
    private data class Pool(val keyPrefix: String, val key: String, val title: String)

    /**
     * The pools, in the order their shelves render (all just above the catch-all). Watched-video
     * first: it's derived from what the user actually did, whereas a topic is a setting they left
     * on — the stronger personal signal goes higher.
     */
    private val POOLS = listOf(
        Pool(WATCHED_KEY_PREFIX, WATCHED_FALLBACK_KEY, WATCHED_FALLBACK_TITLE),
        Pool(TOPIC_KEY_PREFIX, TOPICS_FALLBACK_KEY, TOPICS_FALLBACK_TITLE),
    )

    /**
     * Key prefix for a per-topic row. Shared by [bucketFor] (which builds the keys) and [group]
     * (which tests them to route an under-sized topic row into the "From your topics" shelf rather
     * than the catch-all) — the routing is a string test on the key, so the two drifting apart
     * would silently send every topic leftover back to the unlabelled pile with nothing failing.
     *
     * Note this is the GROUPING key, unrelated to `SearchVideo.sourceTag`'s own `"topic:"` prefix
     * (`HomeRanker.TOPIC_SOURCE_TAG_PREFIX`) even though the two currently share a spelling: that
     * one tags where a video was FETCHED from, this one names the shelf a video was GROUPED into,
     * and a topic-sourced card routinely ends up in a history or subscriptions row instead.
     */
    private const val TOPIC_KEY_PREFIX = "topic:"

    /**
     * Fewer than this many cards and a "row" reads as a rendering glitch rather than a shelf — with
     * one or two cards a horizontally-scrolling strip doesn't even scroll, it's just a wide gap. 3
     * is the smallest count that still looks like a deliberate strip on a phone-width screen.
     * Under-sized rows fold into the catch-all instead of vanishing (see [group]).
     */
    private const val MIN_ROW_VIDEOS = 3

    /**
     * Hard cap on how many cards a row may HOLD — not how many it shows at once, which is
     * HomeScreen's per-row reveal window (see the class doc's scope note).
     *
     * This was 20 back when it was also the display count, which meant a row that had accumulated
     * more than 20 entries silently dropped the surplus: those videos were grouped, scored, and
     * then unreachable in this layout even though the classic list still listed them. Now that the
     * strip pages through its own contents, the cap only needs to stop a row growing without bound
     * as the single flat stream keeps loading. 60 is roughly three reveal pages — past that the
     * surplus really is better spent letting NEW rows appear below than making one row longer.
     */
    internal const val MAX_ROW_VIDEOS = 60

    /** How many of a row's top scores [Shelf.aggregateScore] averages — see its doc. */
    private const val ROW_SCORE_SAMPLE = 3
}
