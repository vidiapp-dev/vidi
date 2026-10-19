package com.example.ytclient.core.local

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Process-wide local library: subscriptions, watch history, and playlists. Backed by a
 * [android.content.SharedPreferences] file with each collection stored as a kotlinx JSON string —
 * no Room / no new build deps — except watch history, which lives in its own JSON file for the
 * reasons below. Every mutation updates its [StateFlow] synchronously (so any screen observing it
 * sees the change immediately — subscribe in the player → Subscriptions tab refreshes) but the
 * actual disk write (JSON-encode + `SharedPreferences.Editor.apply()`) happens on [writeScope],
 * off whichever thread called the mutator. Several call sites invoke these mutators directly from
 * the Compose/Main dispatcher (e.g. [updateProgress], ticked every 5s during playback) — encoding a
 * few hundred history entries to a JSON string on the main thread on a timer was a real source of
 * jank, hence deferring just the write, not the whole mutation (the in-memory StateFlow update
 * needs to stay synchronous so reactivity isn't delayed).
 *
 * Owned as a singleton by [com.example.ytclient.App].
 *
 * ## Everything here loads lazily, and [prewarm] is what decides who pays
 *
 * This class's constructor used to read and decode all nine collections eagerly. That was a real
 * cold-start cost in the wrong place: `HomeViewModel` touches `app.library` from its CONSTRUCTOR,
 * and `viewModel()` builds it during composition, so the full-file SharedPreferences XML parse plus
 * nine `kotlinx` decodes — history up to [MAX_HISTORY] entries each carrying an uploader tag list,
 * every playlist up to [MAX_PLAYLIST_VIDEOS] videos — all landed on the main thread before the
 * first frame.
 *
 * Every collection is now behind a `by lazy`, so the constructor costs a `getSharedPreferences`
 * call (which merely KICKS OFF Android's own background XML parse — that part is useful to do
 * early) and some serializer allocations. [prewarm] then forces all of them from
 * `App.onCreate`'s IO scope, so by the time any UI asks, the work is already done. If the UI does
 * somehow get there first it simply pays what it used to pay — there is no correctness dependency
 * on prewarm having run, only a latency one.
 *
 * **Invariant for anyone adding a collection here:** a `by lazy` initializer must never call into
 * an `@Synchronized` member of this class. The mutators are `@Synchronized` and they read
 * `_history.value` / `_subscriptions.value` etc. *while holding the monitor*, which forces the
 * lazy; if an initializer then wanted the monitor, two threads could deadlock (monitor-then-lazy
 * vs. lazy-then-monitor). The existing initializers only call private, unsynchronised helpers and
 * dispatch their own writes straight to [writeScope] — keep it that way.
 *
 * ## Why history is a file and not another SharedPreferences key
 *
 * SharedPreferences rewrites its ENTIRE file on every `apply()`. History is both the largest
 * collection and by far the most frequently written one — [updateProgress] is called about every
 * 5 s for the whole duration of a video — so a progress tick used to re-encode the full history
 * list and then drag subscriptions, every playlist, the hidden lists and the feed cache to disk
 * along with it. On top of that, SharedProferences XML-escapes the value, and JSON is quote-heavy
 * (`"` → `&quot;`, six bytes for one), so the stored form ran close to double the JSON's own size,
 * which is paid again on every cold-start parse.
 *
 * So: history is a plain JSON file ([HISTORY_FILE]), written atomically via a temp file + rename,
 * and its writes are COALESCED (see [scheduleHistoryWrite]). Existing installs are migrated out of
 * the legacy prefs key on first read — see [readHistoryFromDisk].
 */
class LibraryStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** See the class doc's "Why history is a file" section. */
    private val historyFile = File(context.applicationContext.filesDir, HISTORY_FILE)

    // encodeDefaults = false (it was true): a field equal to its default is omitted on write and
    // restored from the default on read, so this is a pure size win with no schema or
    // compatibility implications. It matters more here than it looks — the models in
    // LibraryModels.kt are mostly optional fields (author/channelId/thumbnail/duration/views/
    // published/tags/topic), so on a typical entry most of the JSON was default values spelled out
    // in full. Smaller blobs mean a faster cold-start decode and a smaller file to rewrite.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    // Single-thread (not the general IO pool) so writes execute strictly in the order they were
    // dispatched — same effective ordering as if they'd stayed synchronous — even though they now
    // run off the caller's thread. A process-lifetime scope (mirrors App.appScope), not tied to any
    // ViewModel: a write dispatched right as a screen is torn down (e.g. the player's final
    // updateProgress on exit) must still complete, not get cancelled along with that screen's scope.
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    // Coalesced-history-write state — see [scheduleHistoryWrite] for the protocol and for why
    // neither of these is synchronised here. `@Volatile` on the pending list so [flushPendingWrites]
    // (the only reader outside the monitor) sees a current value.
    @Volatile private var pendingHistoryWrite: List<HistoryEntry>? = null
    private var historyFlushJob: Job? = null

    private val subSerializer = ListSerializer(Subscription.serializer())
    private val historySerializer = ListSerializer(HistoryEntry.serializer())
    private val playlistSerializer = ListSerializer(Playlist.serializer())
    private val searchHistorySerializer = ListSerializer(SearchHistoryEntry.serializer())
    // Pre-timestamp search-history shape (a plain JSON array of query strings) — kept only for
    // [loadSearchHistory]'s migration fallback, see its comment.
    private val legacySearchHistorySerializer = ListSerializer(String.serializer())
    private val hiddenChannelSerializer = ListSerializer(HiddenChannel.serializer())
    private val hiddenVideoSerializer = ListSerializer(String.serializer())
    private val discoveredChannelSerializer = ListSerializer(DiscoveredChannel.serializer())
    private val disabledTopicsSerializer = ListSerializer(String.serializer())
    // D3 (RUNTIME_PERF_PLAN.md): the last shown head of the "For you" feed, for an instant
    // (stale-while-revalidate) first paint on the next cold start.
    private val feedCacheSerializer = ListSerializer(com.example.ytclient.youtube.models.SearchVideo.serializer())

    private val _subscriptions: MutableStateFlow<List<Subscription>> by lazy { MutableStateFlow(loadSubs()) }
    val subscriptions: StateFlow<List<Subscription>> by lazy { _subscriptions.asStateFlow() }

    private val _subscribedChannelIds: MutableStateFlow<Set<String>> by lazy {
        MutableStateFlow(_subscriptions.value.map { it.channelId }.toSet())
    }
    /** Quick lookup for card badges. */
    val subscribedChannelIds: StateFlow<Set<String>> by lazy { _subscribedChannelIds.asStateFlow() }

    // ── Home feed personalization: local block-list ("Not interested" / "Don't recommend this
    // channel" — see the shared VideoRow card menu). Kept separate from Subscription/HistoryEntry
    // since hiding is a feed-filtering signal, not a watch or subscribe event. Consulted by
    // HomeRanker to exclude candidates before scoring/display; not currently enforced on other
    // screens (search, channel pages, etc. still show hidden channels/videos if navigated to
    // directly — this only keeps them out of the personalized "For you" feed).
    private val _hiddenChannels: MutableStateFlow<List<HiddenChannel>> by lazy {
        MutableStateFlow(loadHiddenChannels())
    }
    private val _hiddenChannelIds: MutableStateFlow<Set<String>> by lazy {
        MutableStateFlow(_hiddenChannels.value.map { it.channelId }.toSet())
    }
    val hiddenChannelIds: StateFlow<Set<String>> by lazy { _hiddenChannelIds.asStateFlow() }

    private val _hiddenVideoIds: MutableStateFlow<Set<String>> by lazy { MutableStateFlow(loadHiddenVideos()) }
    val hiddenVideoIds: StateFlow<Set<String>> by lazy { _hiddenVideoIds.asStateFlow() }

    // Phase 7 (stretch, HOME_FEED_PERSONALIZATION_PLAN.md): cache for the two-hop "similar
    // channels" discovery pass (see YoutubeBridge.discoverSimilarChannels). The whole list is
    // overwritten by [saveDiscoveredChannels] on each run rather than merged/accumulated, so a
    // channel that stops recurring naturally drops out. [shouldRunDiscovery] gates HOW OFTEN the
    // (expensive, many-network-call) pass actually runs — this cache is what makes the result
    // available to HomeRanker/HomeViewModel between runs.
    private val _discoveredChannels: MutableStateFlow<List<DiscoveredChannel>> by lazy {
        MutableStateFlow(loadDiscoveredChannels())
    }
    val discoveredChannels: StateFlow<List<DiscoveredChannel>> by lazy { _discoveredChannels.asStateFlow() }

    private val _history: MutableStateFlow<List<HistoryEntry>> by lazy { MutableStateFlow(loadHistoryPruned()) }
    /** Sorted most-recently-played first. */
    val history: StateFlow<List<HistoryEntry>> by lazy { _history.asStateFlow() }

    /** videoId → watched fraction (0f..1f). Drives the card progress bar. */
    private val _watchedFractions: MutableStateFlow<Map<String, Float>> by lazy {
        MutableStateFlow(fractionsOf(_history.value))
    }
    val watchedFractions: StateFlow<Map<String, Float>> by lazy { _watchedFractions.asStateFlow() }

    private val _playlists: MutableStateFlow<List<Playlist>> by lazy {
        MutableStateFlow(loadPlaylistsEnsuringDefault())
    }
    val playlists: StateFlow<List<Playlist>> by lazy { _playlists.asStateFlow() }

    private val _searchHistory: MutableStateFlow<List<SearchHistoryEntry>> by lazy {
        MutableStateFlow(loadSearchHistory())
    }
    /** Committed search queries, most recent first, deduped case-insensitively. See
     *  [recentSearchTerms] for the recency-windowed view HomeViewModel actually feeds the home
     *  feed's search-based sources with. */
    val searchHistory: StateFlow<List<SearchHistoryEntry>> by lazy { _searchHistory.asStateFlow() }

    // ── Home feed topic settings ────────────────────────────────────────────
    // Absence-based: a topic (see HomeTopics.ALL) not in this set is enabled. Storing the
    // DISABLED set rather than an explicit on/off map for every known topic means a topic added
    // in a future update is automatically enabled for existing installs, no migration needed.
    private val _disabledTopics: MutableStateFlow<Set<String>> by lazy { MutableStateFlow(loadDisabledTopics()) }
    val disabledTopics: StateFlow<Set<String>> by lazy { _disabledTopics.asStateFlow() }

    /**
     * Force every collection off disk, now, on the calling thread — called once from
     * `App.onCreate`'s IO scope. See the class doc for why this exists and why it's advisory: it
     * only moves work that used to happen on the main thread during composition onto a background
     * thread before the UI needs it. Nothing here depends on it having run.
     *
     * Touching the PUBLIC flows (not the backing `_` ones) on purpose — each public lazy forces
     * its backing lazy, so this covers both, and it can't drift out of date as a private field is
     * renamed.
     */
    fun prewarm() {
        subscriptions.value
        subscribedChannelIds.value
        hiddenChannelIds.value
        hiddenVideoIds.value
        discoveredChannels.value
        history.value
        watchedFractions.value
        playlists.value
        searchHistory.value
        disabledTopics.value
        Log.i(
            TAG,
            "prewarmed: ${history.value.size} history, ${subscriptions.value.size} subs, " +
                "${playlists.value.sumOf { it.videos.size }} playlist videos, " +
                "${searchHistory.value.size} searches, ${hiddenVideoIds.value.size} hidden videos",
        )
    }

    // ── Subscriptions ───────────────────────────────────────────────────────

    fun isSubscribed(channelId: String?): Boolean =
        channelId != null && _subscribedChannelIds.value.contains(channelId)

    @Synchronized
    fun subscribe(channelId: String, name: String, avatar: String? = null) {
        if (channelId.isBlank()) return
        if (_subscriptions.value.any { it.channelId == channelId }) return
        val next = _subscriptions.value + Subscription(channelId, name, avatar, System.currentTimeMillis())
        persistSubs(next)
    }

    @Synchronized
    fun unsubscribe(channelId: String) {
        val next = _subscriptions.value.filterNot { it.channelId == channelId }
        if (next.size != _subscriptions.value.size) persistSubs(next)
    }

    @Synchronized
    fun toggleSubscribe(channelId: String, name: String, avatar: String? = null) {
        if (isSubscribed(channelId)) unsubscribe(channelId) else subscribe(channelId, name, avatar)
    }

    /** Backfill name/avatar on an ALREADY-subscribed channel (e.g. once channel info is fetched). */
    @Synchronized
    fun updateSubscriptionMeta(channelId: String, name: String, avatar: String?) {
        val cur = _subscriptions.value
        if (cur.none { it.channelId == channelId }) return
        val next = cur.map {
            if (it.channelId == channelId) {
                it.copy(name = name.ifBlank { it.name }, avatar = avatar ?: it.avatar)
            } else it
        }
        persistSubs(next)
    }

    private fun persistSubs(list: List<Subscription>) {
        val sorted = list.sortedByDescending { it.subscribedAtMs }
        _subscriptions.value = sorted
        _subscribedChannelIds.value = sorted.map { it.channelId }.toSet()
        writeScope.launch {
            prefs.edit().putString(KEY_SUBS, json.encodeToString(subSerializer, sorted)).apply()
        }
    }

    // ── Home feed block-list ────────────────────────────────────────────────

    fun isChannelHidden(channelId: String?): Boolean =
        channelId != null && _hiddenChannelIds.value.contains(channelId)

    fun isVideoHidden(videoId: String): Boolean = _hiddenVideoIds.value.contains(videoId)

    @Synchronized
    fun hideChannel(channelId: String) {
        if (channelId.isBlank() || isChannelHidden(channelId)) return
        persistHiddenChannels(_hiddenChannels.value + HiddenChannel(channelId, System.currentTimeMillis()))
    }

    @Synchronized
    fun unhideChannel(channelId: String) {
        val next = _hiddenChannels.value.filterNot { it.channelId == channelId }
        if (next.size != _hiddenChannels.value.size) persistHiddenChannels(next)
    }

    @Synchronized
    fun hideVideo(videoId: String) {
        if (videoId.isBlank() || isVideoHidden(videoId)) return
        persistHiddenVideos(_hiddenVideoIds.value + videoId)
    }

    @Synchronized
    fun unhideVideo(videoId: String) {
        if (!isVideoHidden(videoId)) return
        persistHiddenVideos(_hiddenVideoIds.value - videoId)
    }

    // ── Phase 7 (stretch): channel discovery cache ──────────────────────────

    /**
     * Whether it's time to re-run [com.example.ytclient.youtube.bridge.YoutubeBridge
     * .discoverSimilarChannels] — at most once per [DISCOVERY_INTERVAL_MS] (~1 day), since each
     * run is a genuinely expensive multi-call network pass. Callers (HomeViewModel) should check
     * this BEFORE kicking off the background job, not after.
     */
    fun shouldRunDiscovery(nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - prefs.getLong(KEY_DISCOVERY_LAST_RUN_MS, 0L) >= DISCOVERY_INTERVAL_MS

    /**
     * Overwrite the discovered-channels cache with a fresh run's results and stamp the run time
     * (consulted by [shouldRunDiscovery]). Called once, after the background discovery job
     * finishes — never incrementally merged, so a channel that stops recurring on a later run
     * naturally falls out instead of accumulating forever.
     */
    @Synchronized
    fun saveDiscoveredChannels(channels: List<DiscoveredChannel>, nowMs: Long = System.currentTimeMillis()) {
        val stamped = channels.map { it.copy(discoveredAtMs = nowMs) }
        _discoveredChannels.value = stamped
        writeScope.launch {
            prefs.edit()
                .putString(KEY_DISCOVERED_CHANNELS, json.encodeToString(discoveredChannelSerializer, stamped))
                .putLong(KEY_DISCOVERY_LAST_RUN_MS, nowMs)
                .apply()
        }
    }

    private fun loadDiscoveredChannels(): List<DiscoveredChannel> = runCatching {
        prefs.getString(KEY_DISCOVERED_CHANNELS, null)
            ?.let { json.decodeFromString(discoveredChannelSerializer, it) }
            ?: emptyList()
    }.getOrElse { emptyList() }

    // Both hidden lists were the only collections here with NO cap at all — every other one is
    // bounded (MAX_HISTORY, MAX_SEARCH_HISTORY, MAX_PLAYLIST_VIDEOS, FEED_CACHE_MAX). In normal use
    // they grow a few entries at a time from deliberate taps, so this is a bound on pathological
    // growth rather than pruning anybody expects to hit; like MAX_PLAYLIST_VIDEOS it's set
    // generously. Trimming the OLDEST is the right direction: the hidden list is consulted as a
    // filter, and a channel hidden two years and a thousand hides ago is the one whose exclusion
    // the user is least likely to still be counting on.

    private fun persistHiddenChannels(list: List<HiddenChannel>) {
        // Already newest-first after this sort, so `take` drops the oldest.
        val sorted = list.sortedByDescending { it.hiddenAtMs }.take(MAX_HIDDEN_CHANNELS)
        _hiddenChannels.value = sorted
        _hiddenChannelIds.value = sorted.map { it.channelId }.toSet()
        writeScope.launch {
            prefs.edit().putString(KEY_HIDDEN_CHANNELS, json.encodeToString(hiddenChannelSerializer, sorted)).apply()
        }
    }

    private fun persistHiddenVideos(set: Set<String>) {
        // Unlike HiddenChannel these carry no timestamp, so recency comes from INSERTION ORDER:
        // `loadHiddenVideos` rebuilds the set with `toSet()` (a LinkedHashSet) from the stored
        // list, `hideVideo` appends with `+`, and the list written below preserves that order — so
        // the newest entries are at the END and `takeLast` keeps them.
        val capped = if (set.size > MAX_HIDDEN_VIDEOS) set.toList().takeLast(MAX_HIDDEN_VIDEOS).toSet() else set
        _hiddenVideoIds.value = capped
        writeScope.launch {
            prefs.edit()
                .putString(KEY_HIDDEN_VIDEOS, json.encodeToString(hiddenVideoSerializer, capped.toList()))
                .apply()
        }
    }

    private fun loadHiddenChannels(): List<HiddenChannel> = runCatching {
        prefs.getString(KEY_HIDDEN_CHANNELS, null)?.let { json.decodeFromString(hiddenChannelSerializer, it) }
            ?: emptyList()
    }.getOrElse { emptyList() }

    private fun loadHiddenVideos(): Set<String> = runCatching {
        prefs.getString(KEY_HIDDEN_VIDEOS, null)?.let { json.decodeFromString(hiddenVideoSerializer, it) }
            ?: emptyList()
    }.getOrElse { emptyList() }.toSet()

    // ── History ─────────────────────────────────────────────────────────────

    fun historyFor(videoId: String): HistoryEntry? = _history.value.firstOrNull { it.videoId == videoId }

    /** Upsert a play event. Keeps the existing position unless a newer one is supplied.
     *  [topic] — see [HistoryEntry.topic]'s doc — is only ever passed by the FIRST recordPlay call
     *  for a video (PlayerViewModel.loadInternal, which has the originating SearchVideo/sourceTag
     *  on hand); the later enrichment call from fetch() omits it and null here just falls back to
     *  whatever the first call already recorded, same pattern as [channelId]/[thumbnail]. */
    @Synchronized
    fun recordPlay(
        videoId: String,
        title: String,
        author: String = "",
        channelId: String? = null,
        thumbnail: String? = null,
        duration: String = "",
        durationSec: Int = 0,
        views: String = "",
        published: String = "",
        tags: List<String> = emptyList(),
        topic: String? = null,
    ) {
        val now = System.currentTimeMillis()
        val existing = _history.value.firstOrNull { it.videoId == videoId }
        val entry = HistoryEntry(
            videoId = videoId,
            title = title.ifBlank { existing?.title ?: "" },
            author = author.ifBlank { existing?.author ?: "" },
            channelId = channelId ?: existing?.channelId,
            thumbnail = thumbnail ?: existing?.thumbnail,
            duration = duration.ifBlank { existing?.duration ?: "" },
            durationSec = if (durationSec > 0) durationSec else (existing?.durationSec ?: 0),
            views = views.ifBlank { existing?.views ?: "" },
            published = published.ifBlank { existing?.published ?: "" },
            positionMs = existing?.positionMs ?: 0L,
            lastPlayedAtMs = now,
            tags = tags.ifEmpty { existing?.tags ?: emptyList() },
            topic = topic ?: existing?.topic,
        )
        // A play event is worth getting to disk promptly — it happens at most a couple of times per
        // video opened, unlike [updateProgress]'s 5-second ticks.
        persistHistory((listOf(entry) + _history.value.filterNot { it.videoId == videoId }), immediate = true)
    }

    /** Update the resume position (called periodically while playing). */
    @Synchronized
    fun updateProgress(videoId: String, positionMs: Long, durationSec: Int) {
        val existing = _history.value.firstOrNull { it.videoId == videoId } ?: return
        val updated = existing.copy(
            positionMs = positionMs,
            durationSec = if (durationSec > 0) durationSec else existing.durationSec,
            lastPlayedAtMs = System.currentTimeMillis(),
        )
        // THE hot write path — see [scheduleHistoryWrite]. Coalesced: a resume position that is at
        // most HISTORY_WRITE_INTERVAL_MS stale is indistinguishable from an exact one to a user
        // resuming a video, and this is called for the entire duration of every playback.
        persistHistory((listOf(updated) + _history.value.filterNot { it.videoId == videoId }), immediate = false)
    }

    @Synchronized
    fun removeFromHistory(videoId: String) {
        val next = _history.value.filterNot { it.videoId == videoId }
        // Immediate: an explicit deletion must not be lost to a process death inside the coalesce
        // window the way a few seconds of playback position harmlessly can be.
        if (next.size != _history.value.size) persistHistory(next, immediate = true)
    }

    @Synchronized
    fun clearHistory() = persistHistory(emptyList(), immediate = true)

    /**
     * Flush any coalesced history write right now. Called from `MainActivity.onStop` so backgrounding
     * the app commits the current resume position instead of leaving it to the pending timer —
     * without this, "leave mid-video, swipe the app away" could lose up to
     * [HISTORY_WRITE_INTERVAL_MS] of position. A no-op when nothing is pending.
     */
    fun flushPendingWrites() {
        if (pendingHistoryWrite == null) return
        writeScope.launch { flushHistory() }
    }

    private fun persistHistory(list: List<HistoryEntry>, immediate: Boolean) {
        val sorted = list.sortedByDescending { it.lastPlayedAtMs }.take(MAX_HISTORY)
        _history.value = sorted
        _watchedFractions.value = fractionsOf(sorted)
        scheduleHistoryWrite(sorted, immediate)
    }

    /**
     * Queue [list] as the history to persist, replacing whatever was already queued.
     *
     * `immediate = false` lets the write WAIT up to [HISTORY_WRITE_INTERVAL_MS] and absorb every
     * further update that lands in that window, so a 40-minute video's ~480 progress ticks cost a
     * handful of writes rather than 480 full re-encodes of the whole list. `immediate = true`
     * cancels the pending timer and writes now.
     *
     * Not `@Synchronized`, and must not become so: every caller is already inside one of this
     * class's `@Synchronized` mutators (so these fields are effectively monitor-guarded), and
     * taking the monitor from here would create the lazy-vs-monitor deadlock the class doc warns
     * about, since [loadHistoryPruned] — a `by lazy` initializer — reaches the write path too (it
     * dispatches its own write directly, for exactly this reason).
     *
     * Note this relaxes [writeScope]'s "writes land in dispatch order" property for history
     * specifically, because a delayed flush can now be overtaken by a later write to another
     * collection. That's harmless: history is the only thing in [historyFile], and nothing else
     * touches it.
     */
    private fun scheduleHistoryWrite(list: List<HistoryEntry>, immediate: Boolean) {
        pendingHistoryWrite = list
        if (immediate) {
            historyFlushJob?.cancel()
            historyFlushJob = null
            writeScope.launch { flushHistory() }
            return
        }
        // A timer is already ticking — it will pick up the list just stored above, so there is
        // nothing to schedule. This is what makes the coalescing O(1) writes per window rather
        // than one per call.
        if (historyFlushJob?.isActive == true) return
        historyFlushJob = writeScope.launch {
            delay(HISTORY_WRITE_INTERVAL_MS)
            flushHistory()
        }
    }

    /**
     * Write whatever is pending, if anything. Always runs on [writeScope].
     *
     * Drains in a loop rather than writing once. A write landing WHILE this is running would
     * otherwise see [historyFlushJob] still active (a coroutine stays active until it completes,
     * not until its `delay` elapses), decline to start a timer of its own on that basis, and leave
     * its list sitting in the pending slot until some later write happened to come along. Looping
     * guarantees the slot is empty when this returns, which is the invariant
     * [scheduleHistoryWrite]'s `isActive` short-circuit actually depends on. Bounded by the
     * producer: the only repeat writer is [updateProgress]'s ~5 s tick, against a file write
     * measured in milliseconds.
     */
    private fun flushHistory() {
        while (true) {
            val list = pendingHistoryWrite ?: return
            pendingHistoryWrite = null
            writeHistoryFile(list)
        }
    }

    /**
     * Atomic replace: write to a sibling temp file, then rename over the real one, so a kill
     * mid-write leaves the previous history intact rather than a truncated JSON document that the
     * next launch would fail to decode (and silently treat as "no history").
     */
    private fun writeHistoryFile(list: List<HistoryEntry>): Boolean = runCatching {
        val tmp = File(historyFile.parentFile, "$HISTORY_FILE.tmp")
        tmp.writeText(json.encodeToString(historySerializer, list))
        if (!tmp.renameTo(historyFile)) {
            // Rename-over-existing works on the filesystems Android actually uses, but fall back
            // rather than silently dropping the write if some device disagrees.
            historyFile.delete()
            if (!tmp.renameTo(historyFile)) {
                tmp.delete()
                error("could not replace $HISTORY_FILE")
            }
        }
        true
    }.getOrElse {
        Log.w(TAG, "history write failed: ${it.message}")
        false
    }

    private fun fractionsOf(list: List<HistoryEntry>): Map<String, Float> =
        list.associate { it.videoId to it.watchedFraction }

    // ── Search history ──────────────────────────────────────────────────────

    /**
     * Record a committed search query (called once per actual search, not per keystroke). Most
     * recent first; an existing case-insensitive match is moved to the front (with its
     * [SearchHistoryEntry.searchedAtMs] refreshed to now) rather than duplicated.
     */
    @Synchronized
    fun recordSearch(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        val now = System.currentTimeMillis()
        val next = listOf(SearchHistoryEntry(q, now)) +
            _searchHistory.value.filterNot { it.query.equals(q, ignoreCase = true) }
        persistSearchHistory(next)
    }

    @Synchronized
    fun removeSearchTerm(query: String) {
        persistSearchHistory(_searchHistory.value.filterNot { it.query.equals(query, ignoreCase = true) })
    }

    @Synchronized
    fun clearSearchHistory() = persistSearchHistory(emptyList())

    /**
     * Search queries from the last [withinDays] days, most-recent first, capped to [limit] — the
     * "past month of interest" window HomeViewModel folds into the home feed's search-based
     * sources (see HomeViewModel.searchSeedTerms). [searchHistory] itself stays capped by count
     * only ([MAX_SEARCH_HISTORY]), not recency, since a future Search-history screen may still
     * want to show/reuse older queries even once they've aged out of the feed window.
     */
    fun recentSearchTerms(withinDays: Int = 30, limit: Int = 8): List<String> {
        val cutoff = System.currentTimeMillis() - withinDays * 24L * 60 * 60 * 1000
        return _searchHistory.value.asSequence()
            .filter { it.searchedAtMs >= cutoff }
            .map { it.query }
            .take(limit)
            .toList()
    }

    private fun persistSearchHistory(list: List<SearchHistoryEntry>) {
        val capped = list.take(MAX_SEARCH_HISTORY)
        _searchHistory.value = capped
        writeScope.launch {
            prefs.edit().putString(KEY_SEARCH_HISTORY, json.encodeToString(searchHistorySerializer, capped)).apply()
        }
    }

    /**
     * [KEY_SEARCH_HISTORY] used to hold a plain JSON array of query strings (no timestamps) before
     * [SearchHistoryEntry] was introduced for [recentSearchTerms]'s month-window filtering. Try the
     * current shape first; on failure (an old install's pre-migration data), fall back to decoding
     * the legacy string-array shape and stamp each entry `now` — there's no real "last searched"
     * time to recover, so this slightly overstates recency for anything actually searched a while
     * ago, but only once, and it's strictly better than silently discarding existing search history.
     */
    private fun loadSearchHistory(): List<SearchHistoryEntry> {
        val raw = prefs.getString(KEY_SEARCH_HISTORY, null) ?: return emptyList()
        runCatching { json.decodeFromString(searchHistorySerializer, raw) }.getOrNull()?.let { return it }
        return runCatching {
            val legacy = json.decodeFromString(legacySearchHistorySerializer, raw)
            val now = System.currentTimeMillis()
            legacy.map { SearchHistoryEntry(it, now) }
        }.getOrElse { emptyList() }
    }

    // ── Home feed topic settings ────────────────────────────────────────────

    /** Whether [topic] (see HomeTopics.ALL) is currently enabled in the "For you" topic blend. */
    fun isTopicEnabled(topic: String): Boolean = topic !in _disabledTopics.value

    @Synchronized
    fun setTopicEnabled(topic: String, enabled: Boolean) {
        val next = if (enabled) _disabledTopics.value - topic else _disabledTopics.value + topic
        if (next == _disabledTopics.value) return
        _disabledTopics.value = next
        writeScope.launch {
            prefs.edit()
                .putString(KEY_DISABLED_TOPICS, json.encodeToString(disabledTopicsSerializer, next.toList()))
                .apply()
        }
    }

    private fun loadDisabledTopics(): Set<String> = runCatching {
        prefs.getString(KEY_DISABLED_TOPICS, null)?.let { json.decodeFromString(disabledTopicsSerializer, it) }
            ?: emptyList()
    }.getOrElse { emptyList() }.toSet()

    // ── Playlists ───────────────────────────────────────────────────────────

    @Synchronized
    fun createPlaylist(name: String): Playlist {
        val pl = Playlist(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Untitled" },
            isWatchLater = false,
            createdAtMs = System.currentTimeMillis(),
        )
        persistPlaylists(_playlists.value + pl)
        return pl
    }

    @Synchronized
    fun removePlaylist(id: String) {
        val next = _playlists.value.filterNot { it.id == id && !it.isWatchLater }
        if (next.size != _playlists.value.size) persistPlaylists(next)
    }

    @Synchronized
    fun renamePlaylist(id: String, name: String) {
        persistPlaylists(_playlists.value.map { if (it.id == id && !it.isWatchLater) it.copy(name = name) else it })
    }

    @Synchronized
    fun addToPlaylist(playlistId: String, video: PlaylistVideo) {
        persistPlaylists(
            _playlists.value.map { pl ->
                if (pl.id != playlistId || pl.videos.any { it.id == video.id }) pl
                else {
                    val appended = pl.videos + video
                    // Bound worst-case size — a playlist is a deliberate, curated list (unlike
                    // history, there's no natural "stale" cutoff), so this cap is set generously
                    // high purely to protect against unbounded growth of the single JSON blob all
                    // playlists share, not to prune in normal use. Trim oldest-added first only
                    // once actually over the cap, so insertion order is otherwise left alone.
                    val videos = if (appended.size > MAX_PLAYLIST_VIDEOS) {
                        Log.w(TAG, "playlist $playlistId exceeded $MAX_PLAYLIST_VIDEOS videos — dropping oldest")
                        appended.sortedByDescending { it.addedAtMs }.take(MAX_PLAYLIST_VIDEOS)
                    } else appended
                    pl.copy(videos = videos)
                }
            },
        )
    }

    @Synchronized
    fun removeFromPlaylist(playlistId: String, videoId: String) {
        persistPlaylists(
            _playlists.value.map { pl ->
                if (pl.id != playlistId) pl else pl.copy(videos = pl.videos.filterNot { it.id == videoId })
            },
        )
    }

    fun watchLater(): Playlist = _playlists.value.first { it.isWatchLater }

    private fun persistPlaylists(list: List<Playlist>) {
        // Watch Later always sorts first; the rest by creation order.
        val sorted = list.sortedWith(compareByDescending<Playlist> { it.isWatchLater }.thenBy { it.createdAtMs })
        _playlists.value = sorted
        writeScope.launch {
            prefs.edit().putString(KEY_PLAYLISTS, json.encodeToString(playlistSerializer, sorted)).apply()
        }
    }

    // ── Loading ─────────────────────────────────────────────────────────────

    private fun loadSubs(): List<Subscription> = runCatching {
        prefs.getString(KEY_SUBS, null)?.let { json.decodeFromString(subSerializer, it) } ?: emptyList()
    }.getOrElse { emptyList() }.sortedByDescending { it.subscribedAtMs }

    private fun loadHistoryPruned(): List<HistoryEntry> {
        val all = readHistoryFromDisk()
        val cutoff = System.currentTimeMillis() - HISTORY_RETENTION_MS
        val pruned = all.filter { it.lastPlayedAtMs >= cutoff }.sortedByDescending { it.lastPlayedAtMs }.take(MAX_HISTORY)
        if (pruned.size != all.size) {
            Log.i(TAG, "pruned ${all.size - pruned.size} history entries older than 30 days")
            // Dispatched straight to [writeScope] rather than through [scheduleHistoryWrite]: this
            // runs inside a `by lazy` initializer, and the scheduler's fields are guarded by the
            // instance monitor that the `@Synchronized` mutators hold while forcing this very lazy
            // — see the class doc's invariant. Nothing can be queued yet anyway (no mutator can
            // have run before `_history` exists), so there is nothing to coalesce with.
            writeScope.launch { writeHistoryFile(pruned) }
        }
        return pruned
    }

    /**
     * Read history from [historyFile], migrating an existing install's data out of the legacy
     * [KEY_HISTORY_LEGACY] SharedPreferences key on the way (see the class doc for why it moved).
     *
     * The migration deliberately writes the new file ASYNCHRONOUSLY and only drops the legacy key
     * once that write reports success. If the process dies in between, the next launch simply finds
     * the legacy key again and repeats a migration that is idempotent — strictly better than
     * removing the only copy first and discovering the write failed.
     */
    private fun readHistoryFromDisk(): List<HistoryEntry> {
        if (historyFile.exists()) {
            runCatching { json.decodeFromString(historySerializer, historyFile.readText()) }
                .onFailure { Log.w(TAG, "history file unreadable, starting empty: ${it.message}") }
                .getOrNull()
                ?.let { return it }
        }

        val legacy = runCatching {
            prefs.getString(KEY_HISTORY_LEGACY, null)?.let { json.decodeFromString(historySerializer, it) }
        }.getOrNull() ?: return emptyList()

        Log.i(TAG, "migrating ${legacy.size} history entries out of SharedPreferences into $HISTORY_FILE")
        writeScope.launch {
            if (writeHistoryFile(legacy)) {
                prefs.edit().remove(KEY_HISTORY_LEGACY).apply()
                Log.i(TAG, "history migration complete; legacy prefs key dropped")
            }
        }
        return legacy
    }

    private fun loadPlaylistsEnsuringDefault(): List<Playlist> {
        val all = runCatching {
            prefs.getString(KEY_PLAYLISTS, null)?.let { json.decodeFromString(playlistSerializer, it) } ?: emptyList()
        }.getOrElse { emptyList() }
        val withDefault = if (all.any { it.isWatchLater }) all else {
            all + Playlist(
                id = "watch_later",
                name = "Watch Later",
                isWatchLater = true,
                createdAtMs = 0L,
            )
        }
        val sorted = withDefault.sortedWith(compareByDescending<Playlist> { it.isWatchLater }.thenBy { it.createdAtMs })
        if (all.none { it.isWatchLater }) {
            writeScope.launch {
                prefs.edit().putString(KEY_PLAYLISTS, json.encodeToString(playlistSerializer, sorted)).apply()
            }
        }
        return sorted
    }

    // ── Home feed cache (D3, RUNTIME_PERF_PLAN.md) ──────────────────────────

    /**
     * The head of the "For you" feed as it was last shown, or empty. Read once at HomeViewModel
     * startup for an instant first paint while the real page-1 fetch runs (stale-while-
     * revalidate); deliberately NOT loaded in this class's constructor so app startup doesn't pay
     * for it unless/until the home feed actually wants it.
     */
    fun loadCachedHomeFeed(): List<com.example.ytclient.youtube.models.SearchVideo> =
        runCatching {
            prefs.getString(KEY_FEED_CACHE, null)
                ?.let { json.decodeFromString(feedCacheSerializer, it) }
        }.getOrNull() ?: emptyList()

    /** Persist the currently shown feed head (capped to [FEED_CACHE_MAX]); write is deferred to
     *  [writeScope] like every other persist here, so ranking paths can call it freely. */
    fun saveCachedHomeFeed(videos: List<com.example.ytclient.youtube.models.SearchVideo>) {
        val capped = videos.take(FEED_CACHE_MAX)
        writeScope.launch {
            runCatching {
                prefs.edit().putString(KEY_FEED_CACHE, json.encodeToString(feedCacheSerializer, capped)).apply()
            }.onFailure { Log.w(TAG, "feed cache write failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "LibraryStore"
        const val PREFS = "library_store"
        const val KEY_SUBS = "subscriptions"

        /**
         * History's OLD home, inside [PREFS]. Read once per install by [readHistoryFromDisk] and
         * then removed; see the class doc for why history moved to [HISTORY_FILE]. Keep this
         * constant (and that read) around — dropping it would silently discard the watch history of
         * every install that hasn't launched since the move.
         */
        const val KEY_HISTORY_LEGACY = "history"

        const val KEY_PLAYLISTS = "playlists"
        const val KEY_SEARCH_HISTORY = "search_history"
        const val KEY_HIDDEN_CHANNELS = "hidden_channels"
        const val KEY_HIDDEN_VIDEOS = "hidden_videos"
        const val KEY_DISCOVERED_CHANNELS = "discovered_channels"
        const val KEY_DISCOVERY_LAST_RUN_MS = "discovery_last_run_ms"
        const val KEY_DISABLED_TOPICS = "disabled_home_topics"
        const val KEY_FEED_CACHE = "home_feed_cache" // D3 (RUNTIME_PERF_PLAN.md)
        const val FEED_CACHE_MAX = 30 // ~two screenfuls; enough for an instant first paint
        /** History's own file under `filesDir` — see the class doc. */
        const val HISTORY_FILE = "history.json"

        /**
         * How long a coalesced history write may wait — see [scheduleHistoryWrite]. Sized against
         * what it is actually absorbing: [updateProgress] ticks about every 5 s, so this folds
         * roughly six ticks into one write while bounding how much resume position a process death
         * can cost to a few seconds. `MainActivity.onStop` flushes anyway
         * ([flushPendingWrites]), so the ordinary "leave the app" path loses nothing at all.
         */
        const val HISTORY_WRITE_INTERVAL_MS = 30_000L

        const val MAX_HISTORY = 500
        const val MAX_SEARCH_HISTORY = 50
        const val MAX_PLAYLIST_VIDEOS = 2000
        // See the comment above persistHiddenChannels: bounds on pathological growth, not pruning
        // anyone is expected to hit in normal use.
        const val MAX_HIDDEN_CHANNELS = 1000
        const val MAX_HIDDEN_VIDEOS = 2000
        const val HISTORY_RETENTION_MS = 31L * 24 * 60 * 60 * 1000 // ~1 month
        const val DISCOVERY_INTERVAL_MS = 24L * 60 * 60 * 1000 // phase 7: run at most once/day
    }
}
