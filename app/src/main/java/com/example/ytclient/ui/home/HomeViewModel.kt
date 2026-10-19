package com.example.ytclient.ui.home

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ytclient.App
import com.example.ytclient.core.coroutines.runCatchingCancellable
import com.example.ytclient.core.local.DiscoveredChannel
import com.example.ytclient.core.models.UiState
import com.example.ytclient.core.perf.Startup
import com.example.ytclient.youtube.bridge.YoutubeBridge
import com.example.ytclient.youtube.models.HomeTopics
import com.example.ytclient.youtube.models.SearchChannel
import com.example.ytclient.youtube.models.SearchVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Phase 6 (HOME_FEED_PERSONALIZATION_PLAN.md): the plain-string form of [HomeRanker.FeedReason]
 * that VideoRow/VideoList (package `ui.components`) actually render. Those components stay
 * decoupled from `ui.home`'s [HomeRanker.FeedReason] sealed interface on purpose — they take a
 * pre-built label instead.
 */
data class FeedReasonInfo(val label: String)

/**
 * Phase 15 (HOME_FEED_PERSONALIZATION_PLAN.md): how the home tab lays the "For you" feed out.
 *  - [NETFLIX]: [HomeGrouper]'s titled, horizontally-scrolling rows ("New from your subscriptions",
 *    "Because you watched X", …) — the same ranked videos, grouped by WHY they were surfaced.
 *  - [CLASSIC]: the original single flat vertical list of full-width `VideoRow`s, untouched.
 *
 * Persisted (see [HomeViewModel.layoutMode]); a user preference, not a state the app derives.
 * Deliberately an enum rather than a boolean so a third layout can be added without rewriting every
 * call site, and so the persisted value reads as a name in prefs instead of an opaque flag.
 */
enum class HomeLayoutMode { NETFLIX, CLASSIC }

/**
 * Home tab: shows the recommended feed by default, and search results once the user types a
 * query. [searchResults] is null while not searching, so the screen falls back to [feed].
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App

    /**
     * `by lazy`, not an eager `= app.youtubeBridge`, because this ViewModel is constructed by
     * `viewModel()` DURING COMPOSITION — i.e. on the main thread. `App.youtubeBridge` is a
     * `synchronized` lazy that `App.onCreate`'s own warmup coroutine resolves too, so an eager
     * read here put the main thread into a race it could lose: RUNTIME_PERF_PLAN.md's A3 notes
     * record a ~2 s `SynchronizedLazyImpl` monitor contention on exactly this field, on the
     * first-frame path. Deferring it means the first touch happens inside one of the
     * `Dispatchers.IO` coroutines below instead, by which point `App.onCreate` has very likely
     * already finished constructing it.
     */
    private val bridge: YoutubeBridge by lazy { app.youtubeBridge }

    private val library = app.library
    private val ranker = HomeRanker(library)

    /**
     * Phase 15 (HOME_FEED_PERSONALIZATION_PLAN.md): Home's own small preferences file, holding the
     * layout-mode toggle ([layoutMode]). Same idiom as PlayerViewModel's `prefs` (quality /
     * SponsorBlock / audio language), but a SEPARATE file — a UI preference for the home tab has
     * nothing to do with playback settings, and keeping them apart means clearing one can't take
     * the other with it.
     */
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Pull-to-refresh in progress (keeps the current list visible while re-fetching). */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /** A "load more" (infinite scroll) request is in flight — drives the trailing spinner. */
    private val _loadingMore = MutableStateFlow(false)
    val loadingMore: StateFlow<Boolean> = _loadingMore

    // Whether more pages are available, one flag per list with its own continuation: the "For you"
    // feed, the selected category, and the search-bar results. "For you" and the category used to
    // share one flag, so scrolling a category to its end and switching back left "For you" (which
    // [selectCategory] restores without refetching) unable to page until a pull-to-refresh.
    // @Volatile: written from IO coroutines, read on the main thread by the paging triggers.
    @Volatile private var forYouHasMore = false
    @Volatile private var categoryHasMore = false
    @Volatile private var searchHasMore = false

    // In-flight guard for loadFeed — replaces the old `_feed.value is UiState.Loading` check,
    // which stopped working as a dedup once D3's stale-while-revalidate path started showing
    // Success content during the fetch instead of Loading.
    //
    // Declared HERE, above the init block, and not next to loadFeed() where it used to sit: Kotlin
    // runs property initialisers and init blocks in textual order, so a declaration below init had
    // its `= null` initialiser execute AFTER init's own loadFeed() call had already stored a Job in
    // it — wiping the guard for the whole first page-1 load, and letting a topic toggle or a Retry
    // tap in that window start a second concurrent getHomeFeed against the same cursor.
    private var feedLoadJob: Job? = null

    /**
     * Whether a real (network) "For you" batch has published yet — see [rankAndReplace]'s doc.
     * Guards against the D3 disk-cache seed, which now runs on its own coroutine, landing after
     * page 1 and replacing live content with last session's.
     */
    @Volatile private var realPage1Landed = false

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    /** null = not searching (show the feed). */
    private val _searchResults = MutableStateFlow<UiState<List<SearchVideo>>?>(null)
    val searchResults: StateFlow<UiState<List<SearchVideo>>?> = _searchResults

    /** Close-match channels for the current search (shown as cards above the video results). */
    private val _searchChannels = MutableStateFlow<List<SearchChannel>>(emptyList())
    val searchChannels: StateFlow<List<SearchChannel>> = _searchChannels

    /** Autocomplete suggestions for the in-progress query (cleared once a search actually runs). */
    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions
    /** True while a getSearchSuggestions call is in flight (or queued) for the current query — lets
     *  the dropdown distinguish "still loading" from "no matches" instead of just staying hidden for
     *  both, which on a cold start (browse context still initialising) could otherwise look broken. */
    private val _suggestionsLoading = MutableStateFlow(false)
    val suggestionsLoading: StateFlow<Boolean> = _suggestionsLoading
    private var suggestionsJob: Job? = null

    /** Category chips. "For you" = the personalised blend; the rest are plain category searches. */
    val categories = listOf("For you", "Music", "Gaming", "News", "Live", "Movies")
    private val _category = MutableStateFlow("For you")
    val category: StateFlow<String> = _category

    /** Every topic the "For you" blend can draw from — see the topic-settings sheet. */
    val allTopics: List<String> = HomeTopics.ALL

    /** Topics the user switched off in the settings sheet — everything not in this set is on.
     *  `by lazy` for the same reason as [bridge]: an eager initializer here would force
     *  LibraryStore's lazy load — and with it the first blocking SharedPreferences read — from this
     *  constructor, i.e. on the main thread during composition. */
    val disabledTopics: StateFlow<Set<String>> by lazy { library.disabledTopics }

    /**
     * Flip [topic] on/off in the "For you" topic blend. This changes which candidates exist in
     * the first place — there's nothing for [rankAndAppendBatch] to rescore for a topic that was
     * never fetched — so a toggle only takes visible effect on the next actual fetch; the settings
     * sheet calls [refreshTopics] on dismiss to make that immediate instead of waiting for an
     * unrelated pull-to-refresh.
     */
    fun setTopicEnabled(topic: String, enabled: Boolean) = library.setTopicEnabled(topic, enabled)

    /**
     * The enabled-topic set the currently-shown "For you" feed was actually fetched with. Recorded
     * when a page-1 fetch ([loadFeed]/[refresh]) succeeds and is shown — not before it runs: a failed
     * fetch keeps the old content on screen, and stamping early made [refreshTopics] treat the
     * toggle as already applied, so it looked inert until an unrelated refresh.
     */
    @Volatile private var topicsAtLastFetch: Set<String> = emptySet()

    /**
     * Called when the topic-settings dialog closes. Re-fetches page 1 ONLY if the enabled-topic set
     * actually changed while it was open.
     *
     * The unconditional refetch this replaces was the main way the grouped rows vanished: closing
     * the dialog — even having toggled nothing — ran [loadFeed], whose [rankAndReplace] resets
     * [_ranked] from a long accumulated feed back to a single ~30-video page. Most per-topic buckets
     * then fall under HomeGrouper's three-video minimum and fold into "Recommended for you", so the
     * topic shelves disappear. They only came back once enough pages had been appended again, which
     * is why leaving the screen and returning (which re-triggers paging) appeared to fix it.
     */
    fun refreshTopics() {
        if (_category.value != "For you") return
        if (enabledTopics().toSet() == topicsAtLastFetch) return
        loadFeed()
    }

    /**
     * Phase 12 (HOME_FEED_PERSONALIZATION_PLAN.md): the "For you" feed's stably-ranked,
     * append-only accumulator. Every NEW batch (page 1 from [loadFeed]/[refresh], or one more page
     * from [loadMore]) is ranked exactly ONCE against the ranker's live signals at that moment
     * (see [rankAndReplace]/[rankAndAppendBatch]) and appended here permanently — a later signal
     * change (subscribe, watch progress, a playlist add, a background discovery run finishing)
     * never re-scores or reorders anything already in this list. That used to happen (the whole
     * accumulated feed was re-ranked on every such change), which read as cards shifting/shuffling
     * under the user mid-scroll — unpleasant, and the reason this phase exists.
     *
     * The one deliberate exception is explicit hide/unhide ("Not interested" / "Don't recommend
     * this channel") — see the `hiddenChannelIds`/`hiddenVideoIds` collector in [init]. That's a
     * live FILTER (removal only, order of survivors untouched), not a re-rank: the user just took
     * an explicit action on a card they can see, so removing it immediately is the expected
     * behavior the "Not interested" menu item already promised, not a violation of "no reordering."
     *
     * A [StateFlow] (not a plain `var`) so observers get the change, and because it's written from
     * several coroutines.
     *
     * It is NOT safe to read-modify-write concurrently, which this doc previously claimed it was.
     * `StateFlow` makes an individual get and an individual set atomic; it does nothing for the
     * get-then-set pair every writer here performs. All three writers — [rankAndReplace],
     * [rankAndAppendBatch] and [applyHiddenFilter] — are therefore `@Synchronized` on this
     * ViewModel, which is what actually makes them safe against each other. See
     * [applyHiddenFilter] for the two concrete ways the unguarded version could corrupt the feed.
     */
    private val _ranked = MutableStateFlow<List<HomeRanker.Ranked>>(emptyList())

    /**
     * The subscription set [rows] groups against — deliberately a SNAPSHOT, not
     * `library.subscribedChannelIds` itself.
     *
     * [HomeGrouper]'s bucketing tests this set before anything else, so reading it live meant a
     * subscribe/unsubscribe re-bucketed the whole feed underneath the user: every already-fetched
     * video from that channel jumps into (or out of) "New from your subscriptions", and any row it
     * leaves behind that drops under HomeGrouper's three-video minimum folds into the catch-all and
     * visibly DISAPPEARS mid-session. That was one of the ways topic shelves vanished while the
     * screen sat idle.
     *
     * Re-read at exactly the points the feed itself legitimately changes — a fresh page 1
     * ([rankAndReplace]) or an appended page ([rankAndAppendBatch]) — so subscribing still moves a
     * channel into its own row, just at the next feed change instead of instantly under the pointer.
     */
    private val _groupingSubscriptions: MutableStateFlow<Set<String>> by lazy {
        MutableStateFlow(library.subscribedChannelIds.value)
    }

    /**
     * Phase 15 (HOME_FEED_PERSONALIZATION_PLAN.md): the grouped-row ("Netflix") layout's rows,
     * DERIVED from [_ranked] rather than accumulated alongside it — see [HomeGrouper] for the
     * bucketing rules and the v1 scope cut (no per-row pagination). Nothing here fetches or scores:
     * every regroup is a pure function of the same append-only ranked list the classic flat list
     * renders, so the two layouts can never disagree about what's in the feed.
     *
     * Recomputed whenever [_ranked] changes (a new page, a fresh page 1, a hide-driven removal) or
     * [_groupingSubscriptions] is re-snapshotted — see that field for why the subscription set is
     * frozen here rather than read live.
     *
     * Runs on [Dispatchers.Default], and starts [SharingStarted.Lazily].
     *
     * It used to be `Eagerly` with the grouping left on the collecting (main) dispatcher, on the
     * reasoning that a couple of stable sorts is cheap enough not to warrant a thread hop. Two
     * things made that the wrong trade. `Eagerly` means the combine runs at CONSTRUCTION — which
     * happens during composition, on the main thread — and there is nothing to group at that point
     * anyway now that the cached-feed seed is asynchronous (see [init]), so the eager pass buys a
     * guaranteed main-thread cost in exchange for grouping an empty list. And "a few hundred
     * entries after a long scroll" turns out to also describe COLD START in the grouped layout,
     * which pages itself several times before the rows overflow the viewport (see HomeScreen's
     * `HomeRowsList`) — so those sorts landed on the main thread repeatedly during exactly the
     * window the feed is meant to be becoming responsive.
     */
    val rows: StateFlow<List<HomeRow>> =
        combine(_ranked, _groupingSubscriptions) { ranked, subscribed ->
            val startedAt = SystemClock.elapsedRealtime()
            val grouped = HomeGrouper.group(ranked, subscribed)
            // Grouping is O(whole accumulated feed) and re-runs on every append, so it is the one
            // part of this path whose cost grows with session length (`_ranked` is uncapped — see
            // BACKLOG). Logged so that growth is observable instead of being discovered as
            // late-session jank.
            Log.i(
                TAG,
                "grouped ${ranked.size} ranked into ${grouped.size} rows in " +
                    "${SystemClock.elapsedRealtime() - startedAt} ms",
            )
            grouped
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    /**
     * Phase 15: which home layout is showing, persisted across restarts. Defaults to
     * [HomeLayoutMode.NETFLIX] — the grouped rows are the point of the feature, so they're what a
     * user gets without asking; [toggleHomeLayoutMode] (the icon button next to the search field)
     * is the always-available way back to the classic list.
     *
     * Stored by NAME rather than ordinal so reordering the enum can't silently repoint an existing
     * install's saved choice, and an unrecognised/corrupt value falls back to the default instead of
     * throwing (`enumValueOf` would). Same seeded-MutableStateFlow + prefs.edit()-on-write idiom as
     * PlayerViewModel's `_sponsorEnabled`/`toggleSponsor`.
     */
    private val _layoutMode = MutableStateFlow(readLayoutMode())
    val layoutMode: StateFlow<HomeLayoutMode> = _layoutMode

    private fun readLayoutMode(): HomeLayoutMode {
        val stored = prefs.getString(PREF_LAYOUT_MODE, null) ?: return DEFAULT_LAYOUT_MODE
        return HomeLayoutMode.entries.firstOrNull { it.name == stored } ?: DEFAULT_LAYOUT_MODE
    }

    /** Switch to [mode] (no-op if it's already active) and remember it for next launch. */
    fun setHomeLayoutMode(mode: HomeLayoutMode) {
        if (_layoutMode.value == mode) return
        _layoutMode.value = mode
        prefs.edit().putString(PREF_LAYOUT_MODE, mode.name).apply()
    }

    /** Flip between the two layouts — what the toolbar toggle calls. */
    fun toggleHomeLayoutMode() {
        setHomeLayoutMode(
            when (_layoutMode.value) {
                HomeLayoutMode.NETFLIX -> HomeLayoutMode.CLASSIC
                HomeLayoutMode.CLASSIC -> HomeLayoutMode.NETFLIX
            },
        )
    }

    /** Category (Music/Gaming/etc.) results — plain unranked accumulation, unchanged from before;
     *  categories never carry a ranking signal (see [HomeRanker]) so there's nothing to stabilize. */
    private var categoryVideos: List<SearchVideo> = emptyList()

    private val _feed = MutableStateFlow<UiState<List<SearchVideo>>>(UiState.Idle)

    /** The feed the UI actually observes — either the stably-ranked "For you" accumulator or the
     *  plain category accumulator, published imperatively by whichever load/append function last
     *  touched it (see [publishRanked], [loadCategory], [loadMore]). */
    val feed: StateFlow<UiState<List<SearchVideo>>> = _feed

    private val _feedReasons = MutableStateFlow<Map<String, FeedReasonInfo>>(emptyMap())

    /**
     * Phase 6: per-video "Because you..." label, keyed by video id. Empty outside the "For you"
     * feed — category/search results carry no ranking signal to explain.
     */
    val feedReasons: StateFlow<Map<String, FeedReasonInfo>> = _feedReasons

    /** Maps a [HomeRanker.FeedReason] to the plain label VideoRow actually renders. */
    private fun reasonInfo(reason: HomeRanker.FeedReason): FeedReasonInfo = when (reason) {
        is HomeRanker.FeedReason.Subscribed -> FeedReasonInfo("Because you're subscribed")
        // Renamed from "Because you watched <channel>" in step with HomeGrouper's row title — that
        // wording now belongs to WatchedVideo, where it's literally true.
        is HomeRanker.FeedReason.HistoryRelated -> FeedReasonInfo("More from ${reason.channelName}")
        is HomeRanker.FeedReason.WatchedVideo -> FeedReasonInfo("Because you watched \"${reason.seedTitle}\"")
        is HomeRanker.FeedReason.Discovered -> FeedReasonInfo(
            if (reason.channelName.isNotBlank()) {
                "Similar to channels you watch"
            } else {
                "A channel like ones you watch"
            },
        )
        is HomeRanker.FeedReason.TagInterest -> FeedReasonInfo("Because you watch \"${reason.tag}\" videos")
        // Phase 16: the classic flat list doesn't get its own per-topic row (that's NETFLIX-only,
        // see HomeGrouper) — so here a topic pick is still captioned, naming the topic that
        // surfaced it, the same way the other named reasons above spell out their source.
        is HomeRanker.FeedReason.Topic -> FeedReasonInfo("From \"${reason.topic}\"")
        // Deliberately no caption — see HomeRanker.FeedReason.Unpersonalized's doc.
        is HomeRanker.FeedReason.Unpersonalized -> FeedReasonInfo("")
        is HomeRanker.FeedReason.Exploration -> FeedReasonInfo("Something different")
    }

    init {
        Startup.mark("HomeViewModel constructed")
        // D3 (RUNTIME_PERF_PLAN.md): instant first paint from the previous session's cached feed
        // head, replaced as soon as the real page 1 lands (stale-while-revalidate). Run through
        // the NORMAL ranking path on purpose: anything hidden or fully watched since last session
        // still filters out, and reason labels regenerate against today's signals.
        //
        // On Dispatchers.IO, not inline. The comment this replaces justified doing it inline with
        // "a ~30-card decode is a few ms" — true of the decode, but not of what the line actually
        // costs, and this constructor runs on the MAIN thread (`viewModel()` builds it during
        // composition). `rankAndReplace` → [HomeRanker.rank] reads the whole library to build its
        // affinity maps: every history entry (up to LibraryStore.MAX_HISTORY) and every video in
        // every playlist for `channelAffinity`, plus a trim+lowercase of every uploader tag on
        // every history entry for `tagAffinity`. Forcing `library`'s lazy loads (nine JSON blobs
        // behind a full-file SharedPreferences parse) was the first thing it did, and the ranking
        // pass followed on the same thread.
        //
        // Launched separately from (and concurrently with) [loadFeed] so a disk read can't delay
        // the network request; [rankAndReplace]'s `fromCache` guard is what keeps the two from
        // fighting over the result.
        viewModelScope.launch(Dispatchers.IO) {
            val cached = Startup.timed("cached feed seed loaded") { library.loadCachedHomeFeed() }
            if (cached.isNotEmpty()) {
                rankAndReplace(cached, fromCache = true)
                Startup.mark("cached feed seed shown (${cached.size} cards)")
            }
        }
        loadFeed()
        maybeRunDiscovery()
        // The one signal allowed to retroactively touch already-shown "For you" cards — see
        // _ranked's doc. A pure filter: removes newly-hidden entries, leaves the relative order of
        // whatever survives untouched. drop(1) skips the initial (startup) emission — rank() at
        // fetch time already excludes anything hidden BEFORE that batch was ever ranked, so this
        // collector only has work to do once something gets hidden WHILE already on screen.
        //
        // On Dispatchers.Default, not the default (main) one: collecting these flows is what FORCES
        // LibraryStore's lazy loads for them, and this collector is set up from the constructor,
        // i.e. during composition. Everything it does — a filter plus StateFlow writes — is
        // thread-agnostic, so there is no reason for the main thread to be the one that might end
        // up waiting on a disk read here.
        viewModelScope.launch(Dispatchers.Default) {
            combine(library.hiddenChannelIds, library.hiddenVideoIds) { channels, videos -> channels to videos }
                .drop(1)
                .collect { (hiddenChannelIds, hiddenVideoIds) -> applyHiddenFilter(hiddenChannelIds, hiddenVideoIds) }
        }
    }

    /**
     * Drop newly-hidden entries from [_ranked] — the one signal allowed to retroactively change
     * what's already on screen (see [_ranked]'s doc). Removal only; the relative order of whatever
     * survives is untouched.
     *
     * `@Synchronized`, and extracted from the collector that calls it for exactly that reason. This
     * is the THIRD writer of [_ranked], alongside [rankAndReplace] and [rankAndAppendBatch], and
     * all three do a non-atomic `.value` read-then-write. The other two already shared this monitor;
     * leaving this one outside it meant a "Not interested" tap interleaving with an in-flight
     * `loadMore` could either resurrect the hidden card permanently (the append's write wins, using
     * a `current` snapshot taken before the removal) or lose the whole appended page (the removal's
     * write wins, using a `before` snapshot taken before the append). [_ranked]'s own doc used to
     * claim `StateFlow` made this safe — it doesn't; `StateFlow` makes the individual get and set
     * atomic, not the pair. (`_ranked.update { }` would fix the interleaving too, but not the
     * [publishRanked] that has to follow it, so one monitor across all three writers is the simpler
     * guarantee.)
     */
    @Synchronized
    private fun applyHiddenFilter(hiddenChannelIds: Set<String>, hiddenVideoIds: Set<String>) {
        val before = _ranked.value
        val after = before.filterNot { r ->
            hiddenVideoIds.contains(r.video.id) ||
                (r.video.channelId != null && hiddenChannelIds.contains(r.video.channelId))
        }
        if (after.size == before.size) return
        _ranked.value = after
        if (_category.value == "For you") publishRanked()
    }

    fun selectCategory(c: String) {
        if (_category.value == c) return
        _category.value = c
        if (c == "For you") {
            // Coming BACK to "For you" restores the feed the user already scrolled rather than
            // refetching page 1 and throwing that accumulation away. [loadFeed]'s [rankAndReplace]
            // resets [_ranked] to a single ~30-video page, which drops most per-topic buckets under
            // HomeGrouper's three-video minimum so they fold into the catch-all — i.e. tapping this
            // chip used to make the topic shelves disappear. Only fetch when there is genuinely
            // nothing to restore; pull-to-refresh is the way to ask for new content.
            if (_ranked.value.isNotEmpty()) publishRanked() else loadFeed()
        } else {
            loadCategory(c)
        }
    }

    // Seeds for the personalized round-robin: recent watches + subscribed channels + recent
    // searches (all capped; getHomeFeed itself only ever *awaits* a small prefix of each on page 1,
    // so passing the full capped list here doesn't cost extra load time — it just gives later pages,
    // via getMoreHomeFeed's round-robin, more variety to draw from).
    private fun historySeedIds() = library.history.value.take(MAX_HISTORY_SEEDS).map { it.videoId }
    private fun channelSeedIds() = library.subscriptions.value.take(MAX_SUB_CHANNELS).map { it.channelId }

    /** Phase 7 (stretch): cached discovered-channel ids, capped independently of the cache's own
     *  MAX_DISCOVER_RESULTS cap (index.js) — bounds how many extra round-robin sources a single
     *  home-feed session juggles, on top of the history/channel/search sources above. */
    private fun discoverChannelSeedIds() =
        library.discoveredChannels.value.take(MAX_DISCOVER_CHANNEL_SEEDS).map { it.channelId }

    /** Topics fetchTopicsBlend should actually search — every [HomeTopics.ALL] topic the user
     *  hasn't switched off (see [setTopicEnabled]). */
    private fun enabledTopics(): List<String> = HomeTopics.ALL.filterNot { it in library.disabledTopics.value }

    /**
     * Phase 14 (HOME_FEED_PERSONALIZATION_PLAN.md): per-enabled-topic round-robin weight, sent
     * alongside [enabledTopics] to [YoutubeBridge.getHomeFeed] — topics you actually watch more of
     * (see [HomeRanker.topicAffinity]) get pulled MORE OFTEN by the JS-side weighted-random leaf
     * pick (index.js's `pullHomeSource`'s `'group'` case), without ever fully starving an enabled
     * topic you just haven't watched yet: every enabled topic gets at least [TOPIC_WEIGHT_FLOOR],
     * scaling up to [TOPIC_WEIGHT_CEIL] for whichever has the strongest affinity right now. Purely
     * a TURN-FREQUENCY bias among topics already enabled — [setTopicEnabled]'s toggle is still the
     * only way to exclude one outright. No signal at all (fresh install, or nothing watched from
     * any topic yet) → every entry is [TOPIC_WEIGHT_FLOOR], i.e. today's uniform behavior.
     */
    private fun topicWeights(): Map<String, Float> {
        val enabled = enabledTopics()
        if (enabled.isEmpty()) return emptyMap()
        val affinity = ranker.topicAffinity()
        val maxAffinity = enabled.maxOf { affinity[it] ?: 0f }
        if (maxAffinity <= 0f) return enabled.associateWith { TOPIC_WEIGHT_FLOOR }
        return enabled.associateWith { topic ->
            val score = affinity[topic] ?: 0f
            TOPIC_WEIGHT_FLOOR + (score / maxAffinity) * (TOPIC_WEIGHT_CEIL - TOPIC_WEIGHT_FLOOR)
        }
    }

    /**
     * Phase 7 (stretch, HOME_FEED_PERSONALIZATION_PLAN.md): kicks off the two-hop "similar
     * channels" discovery pass in the background if [LibraryStore.shouldRunDiscovery] says it's
     * been long enough since the last run (~once/day), and does nothing if there are no
     * subscriptions to sample from. Results land in [LibraryStore.discoveredChannels] — per phase
     * 12, a completed run no longer retroactively rescores whatever's already on screen; it only
     * feeds into whichever batch [loadMore] happens to rank next (new [FeedReason.Discovered]
     * picks/labels appear gradually as the user keeps scrolling, not all at once).
     *
     * "Never blocks [loadFeed]'s first paint" needs an explicit wait, not just launching this on a
     * different coroutine: every call into the JS runtime funnels through ONE Mutex-guarded queue
     * (see QuickJsRuntime.callAsync's callLock — the engine itself is single-threaded), so whichever
     * call reaches it first runs to completion before the other even starts. Nothing about launching
     * loadFeed()'s coroutine first guarantees it also WINS that race — Dispatchers.IO is a pool, not
     * a single thread, so this discovery pass (many chained network calls — easily the more
     * expensive of the two) could just as easily get there first and sit in front of the feed's own
     * getHomeFeed call. [App.homeFeedReady] exists to solve exactly this class of problem —
     * awaiting it here (bounded, in case Home's own load fails before ever completing) reuses
     * that guarantee. (Both calls run on the BROWSE QuickJS context since D1, so this intra-
     * context ordering is still load-bearing even though warmup() no longer needs it.)
     */
    private fun maybeRunDiscovery() {
        // Both the seed read and the shouldRunDiscovery() check moved INSIDE the coroutine. They
        // look free, but `library.subscriptions.value` forces one of LibraryStore's lazy loads and
        // `shouldRunDiscovery()` is the first SharedPreferences read on its path — and this is
        // called from the constructor, on the main thread, during composition. Nothing downstream
        // cares which thread decides not to run.
        viewModelScope.launch(Dispatchers.IO) {
            val seeds = library.subscriptions.value.map { it.channelId }
            if (seeds.isEmpty() || !library.shouldRunDiscovery()) return@launch
            withTimeoutOrNull(HOME_FEED_READY_TIMEOUT_MS) { app.homeFeedReady.await() }
            val result = runCatchingCancellable { bridge.discoverSimilarChannels(seeds) }.getOrNull() ?: return@launch
            val now = System.currentTimeMillis()
            library.saveDiscoveredChannels(
                result.channels.map {
                    DiscoveredChannel(channelId = it.id, name = it.name, score = it.score, discoveredAtMs = now)
                },
                now,
            )
        }
    }

    /**
     * Explicit search history feeding the home feed's `kind: 'search'` sources (see pullHomeSource
     * in index.js) — recent queries from roughly the past month, not just the single most-recent
     * one, so an interest that keeps coming up over weeks (not only whatever was typed last)
     * keeps contributing. getHomeFeed itself only ever awaits a small prefix of these on page 1;
     * the rest still reach the round-robin for later pages.
     */
    private fun searchSeedTerms(): List<String> = library.recentSearchTerms(withinDays = 30, limit = MAX_SEARCH_SEEDS)

    /**
     * Backfills blank uploader names on home-feed cards using data we already have locally, for cases
     * the JS-side parsing can't recover (see index.js's `fillMissingChannelAuthor` for the channel-tab
     * case this doesn't cover). The home feed's "related-of-history" source (getInfo(seedId).watch_next_feed
     * — see pullHomeSource in index.js) surfaces plenty of "more from this channel" style nodes whose
     * uploader is only implied by a shelf/context we've already flattened away, exactly like the
     * channel-tab videos fixed earlier — so these blank-author cards skew heavily toward videos from
     * channels the user is subscribed to and/or has already watched (matching what's actually seeded
     * into that source: history + subscriptions). Two local sources cover both:
     *  1. An EXACT video match in watch history — recorded from the authoritative getVideoInfo call
     *     the first time it was opened (see PlayerViewModel.fetch), so its author is reliable.
     *  2. A channelId match against subscriptions, when the node at least kept its channel endpoint
     *     even though the display text was omitted.
     */
    private fun fillMissingAuthors(videos: List<SearchVideo>): List<SearchVideo> {
        if (videos.none { it.author.isBlank() }) return videos
        val historyByVideoId = library.history.value.associateBy { it.videoId }
        val subsByChannelId = library.subscriptions.value.associateBy { it.channelId }
        return videos.map { v ->
            if (v.author.isNotBlank()) return@map v
            historyByVideoId[v.id]?.takeIf { it.author.isNotBlank() }?.let { h ->
                return@map v.copy(author = h.author, channelId = v.channelId ?: h.channelId)
            }
            v.channelId?.let { subsByChannelId[it] }?.let { sub ->
                return@map v.copy(author = sub.name)
            }
            v
        }
    }

    /**
     * Publishes [_ranked] to [_feed]/[_feedReasons] — call after any change to [_ranked] while
     * "For you" is the active category (a background change made while viewing a category tab
     * still updates [_ranked] itself, just doesn't touch what's currently on screen). Always
     * [UiState.Success], even when [_ranked] is empty — an empty RAW fetch is handled separately
     * as [UiState.Error] in [loadFeed] itself; an empty result HERE just means ranking's hidden/
     * fully-watched filter removed everything this particular batch offered (e.g. every candidate
     * happened to already be hidden), which HomeScreen already renders as its own "Nothing to
     * show" empty state (see its `UiState.Success` branch), not an error to retry.
     */
    private fun publishRanked() {
        // Enforced here rather than left to callers: rankAndReplace/rankAndAppendBatch publish
        // unconditionally, so a "For you" page landing after the user switched to a category used to
        // put "For you" videos under that category's chip. [selectCategory] republishes on return.
        if (_category.value != "For you") return
        val r = _ranked.value
        _feed.value = UiState.Success(r.map { it.video })
        _feedReasons.value = r.associate { it.video.id to reasonInfo(it.reason) }
    }

    /**
     * Fresh "For you" fetch ([loadFeed]/[refresh]'s For-you branch): ranks [videos] once and
     * REPLACES whatever was ranked before. A legitimate reset — either nothing was on screen yet,
     * or the user explicitly asked to refresh, or a topic toggle changed what should even be
     * fetched — not a reorder of cards already showing (pull-to-refresh already visually resets
     * the scroll position, see HomeScreen's post-refresh `scrollToItem(0)`).
     *
     * [fromCache] marks the D3 disk-cache seed from [init], which now races the real page-1 fetch
     * because it moved onto its own coroutine. The two can't simply check "is anything ranked yet"
     * independently — both could see empty and then the seed, finishing second, would overwrite a
     * real page 1 with last session's cards. So the flag is authoritative: a real batch sets
     * [realPage1Landed], a cached seed defers to it, and `@Synchronized` makes
     * check-and-publish atomic across the two IO coroutines.
     *
     * Note the seed still applies when page 1 came back EMPTY — `realPage1Landed` is only set on a
     * batch that actually produced videos. That's the intended stale-while-revalidate behavior
     * (see [loadFeed]'s empty branch, which likewise declines to wipe the screen).
     */
    @Synchronized
    private fun rankAndReplace(videos: List<SearchVideo>, fromCache: Boolean = false) {
        if (fromCache && realPage1Landed) return
        if (!fromCache) realPage1Landed = true
        val startedAt = SystemClock.elapsedRealtime()
        _ranked.value = ranker.rank(fillMissingAuthors(videos))
        Log.i(TAG, "ranked ${videos.size} (replace) in ${SystemClock.elapsedRealtime() - startedAt} ms")
        // A feed change is one of the two points the grouping subscription set is allowed to move
        // — see [_groupingSubscriptions].
        _groupingSubscriptions.value = library.subscribedChannelIds.value
        publishRanked()
        // D3: keep the on-disk head in sync with what's shown (deferred write, see LibraryStore).
        library.saveCachedHomeFeed(_ranked.value.asSequence().map { it.video })
    }

    /**
     * [loadMore]'s "For you" branch: ranks the NEWLY fetched [videos] once, against whatever the
     * ranker's live signals are RIGHT NOW, and appends after everything already ranked — never
     * touching or reordering it (see [_ranked]'s doc for why). Seeds the new batch's diversify
     * pass with the tail of what's already shown (see [HomeRanker.rank]'s `previouslyShown`) so a
     * batch boundary can't immediately repeat the channel/topic the previous batch just ended on.
     *
     * `@Synchronized` with [rankAndReplace]: both read-modify-write [_ranked], and the grouped
     * layout's cold-start paging can put an append and the cached-feed seed in flight at once.
     */
    @Synchronized
    private fun rankAndAppendBatch(videos: List<SearchVideo>) {
        // An append is real content, so it also closes the door on a late cached seed.
        realPage1Landed = true
        val current = _ranked.value
        val alreadyShown = current.mapTo(HashSet()) { it.video.id }
        val fresh = videos.filterNot { it.id in alreadyShown }
        if (fresh.isEmpty()) return
        val startedAt = SystemClock.elapsedRealtime()
        val freshlyRanked = ranker.rank(
            fillMissingAuthors(fresh),
            previouslyShown = current.takeLast(DIVERSIFY_SEED_TAIL).map { it.video },
        )
        Log.i(
            TAG,
            "ranked ${fresh.size} (append, ${current.size} already shown) in " +
                "${SystemClock.elapsedRealtime() - startedAt} ms",
        )
        _ranked.value = current + freshlyRanked
        // See [_groupingSubscriptions] — the other legitimate feed-change point.
        _groupingSubscriptions.value = library.subscribedChannelIds.value
        publishRanked()
        // D3: the cache stores the (capped) head, which appending doesn't change once past the
        // cap — still refreshed here so short first batches grow toward a full cached screen.
        library.saveCachedHomeFeed(_ranked.value.asSequence().map { it.video })
    }

    private fun loadCategory(c: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _feed.value = UiState.Loading
            _feedReasons.value = emptyMap()
            try {
                val res = bridge.search(c)
                // The user may have switched away while this was in flight; publishing it now would
                // put this category's videos under another chip.
                if (_category.value != c) return@launch
                categoryHasMore = res.hasMore
                categoryVideos = res.videos
                _feed.value = UiState.Success(res.videos)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (_category.value != c) return@launch
                categoryHasMore = false
                _feed.value = UiState.Error(t.message ?: "Failed to load $c")
            }
        }
    }

    /**
     * "For you" feed: a fast first page (one multi-topic blend call) that also seeds a personalized
     * round-robin (history-related + subscription uploads + the topic blend) for [loadMore]. No heavy
     * up-front blend beyond that.
     */
    fun loadFeed() {
        if (feedLoadJob?.isActive == true) return
        feedLoadJob = viewModelScope.launch(Dispatchers.IO) {
            // D3 (RUNTIME_PERF_PLAN.md), stale-while-revalidate: if we already have ranked content
            // (the disk-cache seed from init, or the existing accumulator when returning to the
            // "For you" tab from a category), keep/restore it on screen while page 1 refetches
            // instead of blanking to a spinner.
            if (_ranked.value.isNotEmpty()) {
                publishRanked()
            } else {
                _feed.value = UiState.Loading
                _feedReasons.value = emptyMap()
            }
            try {
                // Capture the exact set being sent (not a second enabledTopics() call) so
                // [refreshTopics] compares against what this page was really fetched with.
                val topics = enabledTopics()
                val res = bridge.getHomeFeed(
                    historySeedIds(),
                    channelSeedIds(),
                    searchSeedTerms(),
                    discoverChannelSeedIds(),
                    topics,
                    topicWeights(),
                )
                forYouHasMore = res.hasMore
                if (res.videos.isEmpty()) {
                    // Only surface the error state when there's nothing better on screen — with
                    // stale content showing, a transiently empty page 1 shouldn't wipe the screen
                    // (pull-to-refresh remains available to retry).
                    if (_ranked.value.isEmpty()) {
                        _feed.value = UiState.Error("Nothing to show")
                    }
                } else {
                    rankAndReplace(res.videos)
                    topicsAtLastFetch = topics.toSet()
                    Startup.mark("home feed page 1 shown (${res.videos.size} videos)")
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                forYouHasMore = false
                // Same reasoning as the empty case above: never replace visible (stale) content
                // with an error screen for a fetch the user never explicitly asked for.
                if (_ranked.value.isEmpty()) {
                    _feed.value = UiState.Error(t.message ?: "Failed to load home feed")
                }
            } finally {
                // Signal the discovery pass that the home feed has claimed the BROWSE context's
                // call queue with its own getHomeFeed call — see App.homeFeedReady's doc. Only ever
                // needs to fire once (later loadFeed() calls are a no-op here).
                if (!app.homeFeedReady.isCompleted) app.homeFeedReady.complete(Unit)
            }
        }
    }

    /** Pull-to-refresh: re-fetch page 1 of the current view (feed or category) without clearing it. */
    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _refreshing.value = true
            try {
                val cat = _category.value
                val topics = enabledTopics()
                val res = runCatchingCancellable {
                    if (cat == "For you") {
                        bridge.getHomeFeed(
                            historySeedIds(),
                            channelSeedIds(),
                            searchSeedTerms(),
                            discoverChannelSeedIds(),
                            topics,
                            topicWeights(),
                        )
                    } else {
                        bridge.search(cat)
                    }
                }.getOrNull()
                if (res != null) {
                    if (cat == "For you") {
                        forYouHasMore = res.hasMore
                        rankAndReplace(res.videos)
                        topicsAtLastFetch = topics.toSet()
                    } else if (_category.value == cat) { // see loadCategory
                        categoryHasMore = res.hasMore
                        categoryVideos = res.videos
                        _feed.value = UiState.Success(res.videos)
                    }
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    /** Infinite scroll for the feed list: For you → round-robin, ranked once and appended (see
     *  [rankAndAppendBatch]); a category → search continuation, plain accumulation. */
    fun loadMore() {
        val cat = _category.value
        val forYou = cat == "For you"
        if (_loadingMore.value || !(if (forYou) forYouHasMore else categoryHasMore)) return
        if (_feed.value !is UiState.Success) return
        // Claim the guard BEFORE launching, not inside the coroutine. launch() returns before its
        // body runs, so two calls landing in the same frame — which the grouped layout produces
        // readily, since the vertical trigger and a row's own end-of-strip trigger can both fire —
        // BOTH passed the check above and started concurrent continuation fetches, advancing the
        // one JS-side cursor twice and silently dropping a page at rankAndAppendBatch's dedup.
        _loadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = if (forYou) bridge.getMoreHomeFeed() else bridge.getMoreSearch()
                if (forYou) {
                    forYouHasMore = res.hasMore
                    if (res.videos.isNotEmpty()) rankAndAppendBatch(res.videos)
                } else if (_category.value == cat) {
                    // Only while still on the category this page belongs to — see loadCategory.
                    categoryHasMore = res.hasMore
                    if (res.videos.isNotEmpty()) {
                        val existingIds = categoryVideos.mapTo(HashSet()) { it.id }
                        val fresh = res.videos.filterNot { it.id in existingIds }
                        if (fresh.isNotEmpty()) {
                            categoryVideos = categoryVideos + fresh
                            _feed.value = UiState.Success(categoryVideos)
                        }
                    }
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                if (forYou) forYouHasMore = false
                else if (_category.value == cat) categoryHasMore = false
            } finally {
                _loadingMore.value = false
            }
        }
    }

    /** Infinite scroll for the search-bar results (search continuation). */
    fun loadMoreSearch() {
        if (_loadingMore.value || !searchHasMore) return
        val current = (_searchResults.value as? UiState.Success)?.data ?: return
        // Claimed before launch, same reasoning as loadMore() above.
        _loadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val res = bridge.getMoreSearch()
                searchHasMore = res.hasMore
                if (res.videos.isNotEmpty()) {
                    _searchResults.value = UiState.Success((current + res.videos).distinctBy { it.id })
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                searchHasMore = false
            } finally {
                _loadingMore.value = false
            }
        }
    }

    fun onQueryChange(q: String) {
        _query.value = q
        suggestionsJob?.cancel()
        if (q.isBlank()) {
            _suggestions.value = emptyList()
            _suggestionsLoading.value = false
            return
        }
        _suggestionsLoading.value = true
        // Debounce: wait for a pause in typing before hitting the network so we don't fire a
        // suggestion request per keystroke. Cancelled by the next keystroke via suggestionsJob.
        suggestionsJob = viewModelScope.launch(Dispatchers.IO) {
            delay(SUGGESTIONS_DEBOUNCE_MS)
            val res = runCatchingCancellable { bridge.getSearchSuggestions(q) }.getOrNull()
            // Guard against a stale response landing after the query moved on again.
            if (_query.value == q) {
                _suggestions.value = res?.suggestions ?: emptyList()
                _suggestionsLoading.value = false
            }
        }
    }

    /** User tapped a suggestion: adopt it as the query and search immediately. */
    fun selectSuggestion(s: String) {
        suggestionsJob?.cancel()
        _suggestions.value = emptyList()
        _suggestionsLoading.value = false
        _query.value = s
        search()
    }

    fun search() {
        val q = _query.value.trim()
        if (q.isBlank()) return
        suggestionsJob?.cancel()
        _suggestions.value = emptyList()
        _suggestionsLoading.value = false
        library.recordSearch(q)
        viewModelScope.launch(Dispatchers.IO) {
            _searchResults.value = UiState.Loading
            _searchChannels.value = emptyList()
            _searchResults.value = try {
                val res = bridge.search(q)
                _searchChannels.value = res.channels
                searchHasMore = res.hasMore
                UiState.Success(res.videos)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                searchHasMore = false
                UiState.Error(t.message ?: "Search failed")
            }
        }
    }

    /** Clear the query + results → the feed reappears. */
    fun clearSearch() {
        suggestionsJob?.cancel()
        _suggestions.value = emptyList()
        _suggestionsLoading.value = false
        _query.value = ""
        _searchResults.value = null
        _searchChannels.value = emptyList()
        searchHasMore = false
        // The search bar shares the JS search session with category feeds. If a category is showing,
        // reload it so its "load more" continues the category (not the cleared query). "For you" uses
        // a separate (round-robin) session, so it's unaffected.
        if (_category.value != "For you") loadCategory(_category.value)
    }

    private companion object {
        /** Logcat tag for the rank/group timings — `adb logcat -s HomeFeed HomeFeedEngine`. */
        const val TAG = "HomeFeed"

        // Phase 15: Home's own preferences file + the layout-mode key (see [prefs]/[layoutMode]).
        const val PREFS = "home_prefs"
        const val PREF_LAYOUT_MODE = "home_layout_mode"
        val DEFAULT_LAYOUT_MODE = HomeLayoutMode.NETFLIX

        const val MAX_SUB_CHANNELS = 10
        const val MAX_HISTORY_SEEDS = 3
        const val MAX_SEARCH_SEEDS = 5
        const val MAX_DISCOVER_CHANNEL_SEEDS = 8 // phase 7 (stretch)
        const val SUGGESTIONS_DEBOUNCE_MS = 200L
        const val HOME_FEED_READY_TIMEOUT_MS = 6_000L // bound so discovery still runs if Home's load fails

        // Phase 12: how much of the already-ranked tail rankAndAppendBatch hands HomeRanker.rank as
        // `previouslyShown` — deliberately a bit more than HomeRanker's own DIVERSITY_WINDOW (2)
        // needs, since HomeRanker only reads the tail it actually uses; over-supplying costs nothing.
        const val DIVERSIFY_SEED_TAIL = 6

        // Phase 14: topicWeights()' floor/ceiling. Floor = today's uniform weight (no signal yet, or
        // a topic tied for least-watched still gets a fair turn share); ceiling caps how much a
        // heavily-watched topic can crowd out the others, mirroring HOME_BUCKET_WEIGHTS' relative
        // scale on the JS side rather than letting one topic's turns grow unbounded.
        const val TOPIC_WEIGHT_FLOOR = 1f
        const val TOPIC_WEIGHT_CEIL = 4f
    }
}
