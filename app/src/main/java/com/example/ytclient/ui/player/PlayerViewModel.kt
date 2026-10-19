@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ytclient.ui.player

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ytclient.App
import com.example.ytclient.core.coroutines.runCatchingCancellable
import com.example.ytclient.core.models.UiState
import com.example.ytclient.core.settings.PlaybackMode
import com.example.ytclient.core.sponsorblock.SponsorBlock
import com.example.ytclient.core.sponsorblock.SponsorSegment
import com.example.ytclient.playback.PlaybackController
import com.example.ytclient.sabr.AudioTrackInfo
import com.example.ytclient.sabr.CaptionTrackInfo
import com.example.ytclient.sabr.SabrInfoDto
import com.example.ytclient.sabr.SabrQuality
import com.example.ytclient.sabr.SabrSessionFactory
import com.example.ytclient.sabr.SabrSessionHolder
import com.example.ytclient.youtube.bridge.YoutubeBridge
import com.example.ytclient.youtube.models.ChannelInfo
import com.example.ytclient.youtube.models.Comment
import com.example.ytclient.youtube.models.LegacyPlaybackInfo
import com.example.ytclient.youtube.models.SearchVideo
import com.example.ytclient.youtube.models.Thumbnail
import com.example.ytclient.youtube.models.VideoInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An ordered playback queue. For a playlist [autoplayRelated] is false (play in order, stop at the
 * end); for a standalone video it's a single entry with [autoplayRelated] true (when it ends, the
 * first related video plays, which itself becomes a new single-entry related queue → endless chain).
 */
data class PlayerQueue(
    val videos: List<SearchVideo> = emptyList(),
    val index: Int = 0,
    val autoplayRelated: Boolean = true,
) {
    val current: SearchVideo? get() = videos.getOrNull(index)
    val hasNext: Boolean get() = index + 1 < videos.size
}

/** Comment ordering offered by the comments sheet. */
enum class CommentSort { TOP, NEWEST }

/**
 * State for the comments view: the loaded page(s), paging/loading flags, the active sort, and the
 * per-comment expanded replies. Display-only (anonymous app).
 */
data class CommentsUiState(
    val comments: List<Comment> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val totalText: String = "",
    val disabled: Boolean = false,
    val sort: CommentSort = CommentSort.TOP,
    val expandedReplies: Map<String, List<Comment>> = emptyMap(),
    val loadingReplies: Set<String> = emptySet(),
) {
    /** Whether the preview bar / entry point should be shown at all. */
    val available: Boolean get() = !disabled && (loading || comments.isNotEmpty())
}

/**
 * Player-screen ViewModel. Fetches [VideoInfo] for the requested video ID via
 * the shared [YoutubeBridge]; the screen plays the selected formats with Media3.
 *
 * Self-heals on playback errors: googlevideo URLs are short-lived (they expire,
 * and datacenter/VPN IPs get intermittent 403s), so a playback failure triggers
 * an automatic re-fetch of fresh URLs (a new `getVideoInfo` re-mints the token
 * and re-issues stream URLs), up to [MAX_AUTO_RETRIES] times.
 */
class PlayerViewModel(application: Application) : AndroidViewModel(application) {

    private val app: App = application as App
    private val bridge: YoutubeBridge = app.youtubeBridge
    private val library = app.library

    private val _info = MutableStateFlow<UiState<VideoInfo>>(UiState.Idle)
    val info: StateFlow<UiState<VideoInfo>> = _info

    private val _related = MutableStateFlow<List<SearchVideo>>(emptyList())
    val related: StateFlow<List<SearchVideo>> = _related

    /** Infinite scroll for the player "up next" related list. */
    private val _loadingMoreRelated = MutableStateFlow(false)
    val loadingMoreRelated: StateFlow<Boolean> = _loadingMoreRelated
    private var relatedHasMore = false
    private var relatedVideoId: String? = null

    // The native SABR playback source (transport replacement for the legacy
    // direct-URL DASH path). Built from bridge.getSabrInfo(). When this is
    // Success the player streams over SABR; on Error it falls back to legacy.
    private val _sabr = MutableStateFlow<UiState<SabrSessionHolder>>(UiState.Idle)
    val sabr: StateFlow<UiState<SabrSessionHolder>> = _sabr

    /**
     * The user's streaming-transport choice, re-exposed straight off the process-wide
     * [com.example.ytclient.core.settings.PlaybackSettings] (see its doc for why it lives there and
     * not in this ViewModel's own [prefs]). Read here at video-LOAD time — it decides whether a SABR
     * session is built for a video at all — and observed by PlayerScreen so its legacy/DASH render
     * branch can tell a deliberate DASH open apart from a SABR failure that fell through into it.
     */
    val playbackMode: StateFlow<PlaybackMode> = app.playbackSettings.mode

    /**
     * Non-null while the "streaming protection rejected this video — switch to DASH?" dialog should
     * be showing, holding the video id it was raised for.
     *
     * Set ONLY by [retryAfterPlaybackError], and only when the failure is specifically a
     * StreamProtectionStatus=3 rejection (see [PROTECTION_STATUS_REJECTED]) while in SABR mode.
     * Every other fatal cause — I/O streaks, generic extraction failures, an incomplete SABR info —
     * keeps the pre-existing silent fallback, because those are transport failures the viewer has no
     * useful say in. A status=3 is different in kind: the server has refused this session's
     * attestation for this content outright, no rebuild can fix it, and the alternative (DASH, with
     * a fixed format selection instead of per-segment adaptation) is a genuinely different viewing
     * experience rather than a transparent repair — so it gets asked about instead of done quietly.
     *
     * Carries the video id rather than a plain Boolean so a prompt raised for a video the viewer has
     * since navigated away from is recognisable as stale; [loadInternal] clears it on every load.
     */
    private val _dashPrompt = MutableStateFlow<String?>(null)
    val dashPrompt: StateFlow<String?> = _dashPrompt

    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // @Volatile because the SABR pump thread reads it: SabrSessionHolder.notifyGraceSession /
    // notifyFatal invoke their listeners inline on that thread, and both onGraceSession and
    // onSabrFatal gate on this before doing anything. Without it the pump thread can observe a
    // stale value and return early, silently skipping the proactive rebuild that onSabrFatal exists
    // to trigger — leaving playback on the slow "wait for ExoPlayer to starve" path instead.
    @Volatile
    private var loadedVideoId: String? = null
    private var autoRetries = 0

    // Retained so a quality switch can rebuild the SABR session for the SAME video without re-fetching
    // getSabrInfo, and so retries preserve the chosen quality.
    private var lastSabrDto: SabrInfoDto? = null

    // Hard cap on offered/selected video height = the device display's short side (px). Excludes
    // 1440p/4K on smaller panels — there's no visible benefit and the heavy 4K VP9/AV1 buffers OOM
    // the player. Computed once per process.
    private val maxVideoHeight = SabrQuality.deviceMaxVideoHeight(app)

    // The user's preferred video height (0 = auto), persisted across videos AND app restarts so the
    // choice sticks until they pick a different one. Capped by maxVideoHeight.
    private val _preferredHeight = MutableStateFlow(prefs.getInt(PREF_HEIGHT, 0))
    val preferredHeight: StateFlow<Int> = _preferredHeight

    // Adaptive (bandwidth/decoder-driven) quality state — session-scoped, NOT persisted, kept across
    // videos because the link's capacity is a property of the session, not the video.
    //  - _autoHeight: the auto-adjusted height currently in force (0 = follow the user's pick).
    //  - provenBadHeight: the lowest height that has rebuffered; auto-UP never returns to/above it, so
    //    quality settles at the best stable rung instead of ping-ponging.
    private var autoHeight = 0
    private var provenBadHeight = Int.MAX_VALUE

    /** The user's intended ceiling: an explicit pick (>0), or 1080p for "auto" — 1440p/4K is rarely
     *  worth it on a phone and its VP9/AV1 decode is what makes the player rebuffer. */
    private fun userCeilingHeight(): Int =
        if (_preferredHeight.value > 0) _preferredHeight.value else DEFAULT_AUTO_HEIGHT

    /** The height to actually request now: the adaptive height if active, clamped to the user ceiling. */
    private fun effectiveHeight(): Int {
        val ceiling = userCeilingHeight()
        return if (autoHeight in 1 until ceiling) autoHeight else ceiling
    }

    // Preferred audio language (BCP-47-ish code, e.g. "en"), persisted; defaults to English so the
    // English track is chosen by default on multi-audio videos. The available tracks come per-video.
    private val _preferredAudioLang = MutableStateFlow(prefs.getString(PREF_AUDIO_LANG, "en") ?: "en")
    val preferredAudioLang: StateFlow<String> = _preferredAudioLang
    private val _audioTracks = MutableStateFlow<List<AudioTrackInfo>>(emptyList())
    val audioTracks: StateFlow<List<AudioTrackInfo>> = _audioTracks

    /** Closed-caption tracks for the current video (empty = none available; hides the CC menu). */
    private val _captionTracks = MutableStateFlow<List<CaptionTrackInfo>>(emptyList())
    val captionTracks: StateFlow<List<CaptionTrackInfo>> = _captionTracks

    /**
     * Fetch the selected caption track as WebVTT text (via Innertube get_transcript). The raw
     * timedtext URL returns empty bodies for many videos, so the player side-loads this text instead.
     * Returns "" when unavailable (caller then renders no captions).
     */
    suspend fun fetchCaptionVtt(videoId: String, languageName: String): String =
        runCatchingCancellable { bridge.getCaptionVtt(videoId, languageName) }.getOrDefault("")

    /** Reactive set of subscribed channel ids — the player's Subscribe button observes this. */
    val subscribedChannelIds: StateFlow<Set<String>> = library.subscribedChannelIds

    /** videoId → watched fraction, for decorating the "Up next" cards. */
    val watchedFractions: StateFlow<Map<String, Float>> = library.watchedFractions

    // SponsorBlock: auto-skip segments for the current video + a persisted on/off toggle.
    private val _sponsorSegments = MutableStateFlow<List<SponsorSegment>>(emptyList())
    val sponsorSegments: StateFlow<List<SponsorSegment>> = _sponsorSegments
    private val _sponsorEnabled = MutableStateFlow(prefs.getBoolean(PREF_SPONSOR, true))
    val sponsorEnabled: StateFlow<Boolean> = _sponsorEnabled

    // Comments for the current video. Loaded best-effort (off the playback path); the bar/sheet
    // observe this. commentsVideoId guards against a stale fetch landing after a video switch.
    private val _comments = MutableStateFlow(CommentsUiState())
    val comments: StateFlow<CommentsUiState> = _comments
    private var commentsVideoId: String? = null

    fun toggleSponsor() {
        val v = !_sponsorEnabled.value
        _sponsorEnabled.value = v
        prefs.edit().putBoolean(PREF_SPONSOR, v).apply()
    }

    // ── Playback queue / autoplay ───────────────────────────────────────────
    private val _queue = MutableStateFlow(PlayerQueue())
    /** The full playback queue (used by the player's "Up next" playlist view). */
    val queue: StateFlow<PlayerQueue> = _queue
    val currentVideo: StateFlow<SearchVideo?> =
        _queue.map { it.current }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The next video that will autoplay (next un-watched queue item, or first related). */
    val upNext: StateFlow<SearchVideo?> =
        combine(_queue, _related, library.watchedFractions) { q, rel, fracs ->
            val nextInQueue = ((q.index + 1) until q.videos.size)
                .firstOrNull { (fracs[q.videos[it].id] ?: 0f) < WATCHED_THRESHOLD }
                ?.let { q.videos[it] }
            nextInQueue ?: if (q.autoplayRelated) rel.firstOrNull { it.id != q.current?.id } else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Channel header for the current video (uploader logo + subscribe avatar), fetched once per channel.
    private val _channelInfo = MutableStateFlow<ChannelInfo?>(null)
    val channelInfo: StateFlow<ChannelInfo?> = _channelInfo
    private var channelInfoFor: String? = null

    /** Start (or replace) the queue and begin playing [index]. */
    fun startQueue(videos: List<SearchVideo>, index: Int = 0, autoplayRelated: Boolean = true) {
        if (videos.isEmpty()) return
        val safe = index.coerceIn(0, videos.lastIndex)
        // Same queue + same current already playing → no-op (avoids reload on recomposition).
        val q = _queue.value
        if (q.videos.map { it.id } == videos.map { it.id } && q.index == safe) return
        _queue.value = PlayerQueue(videos, safe, autoplayRelated)
        videos[safe].let(::loadInternal)
    }

    /** Advance to the next UN-watched queue item, else the first related (if autoplay). False at end. */
    fun playNext(): Boolean {
        val q = _queue.value
        val nextIdx = ((q.index + 1) until q.videos.size)
            .firstOrNull { (library.watchedFractions.value[q.videos[it].id] ?: 0f) < WATCHED_THRESHOLD }
        if (nextIdx != null) {
            _queue.value = q.copy(index = nextIdx)
            advanceTo(q.videos[nextIdx])
            return true
        }
        if (q.autoplayRelated) {
            val nxt = _related.value.firstOrNull { it.id != q.current?.id }
            if (nxt == null) {
                // The one autoplay failure that has nothing to do with the lock screen: a single
                // video's "next" IS its first related video, so if the related list never loaded
                // (fetch failed, still in flight, or came back empty) there is simply nothing to
                // advance to, and playback stops. This used to return false silently, which made
                // "autoplay didn't fire" indistinguishable from the locked-screen hand-off failing.
                Log.i(
                    "PlayerViewModel",
                    "autoplay: nothing to play after ${q.current?.id} — related list has " +
                        "${_related.value.size} entries (none usable)",
                )
                return false
            }
            _queue.value = PlayerQueue(listOf(nxt), 0, autoplayRelated = true)
            advanceTo(nxt)
            return true
        }
        // A playlist / explicit queue reached its end. By design (playPlaylist starts the queue with
        // autoplayRelated = false), but logged so it can't be mistaken for a failure in a capture.
        Log.i("PlayerViewModel", "autoplay: end of queue after ${q.current?.id} (autoplayRelated=false)")
        return false
    }

    /**
     * The shared tail of [playNext]'s two branches (also reached from the lockscreen/PiP Next control,
     * which is wired to playNext too). If we've already prefetched a FULLY-BUILT session for exactly
     * this video (see [prefetchUpNext]), hand it straight to whatever player is currently live via
     * [PlaybackController.playDirectly] before doing anything else — this is what keeps audio-only
     * background playback (screen locked) advancing through the queue: Compose's normal rebuild-the-
     * player-on-state-change path can stall indefinitely in that state (see playDirectly's doc for
     * why), so this bypasses it entirely rather than racing it. playDirectly no-ops harmlessly if
     * nothing is currently live (e.g. called from some path other than a natural end-of-video); either
     * way `_sabr` below ends up publishing this SAME already-built session, so the normal Compose
     * rebuild — whenever it does run — adopts it (see PlayerBox's `adopted` check) instead of building
     * a redundant duplicate.
     */
    private fun advanceTo(video: SearchVideo) {
        // Gapless fast path: this exact video is already sitting as the player's appended next
        // playlist item (see queuedIntoPlayer). Jump into it — the transition listener then runs
        // the same bookkeeping (onAutoAdvancedIntoQueued) a natural end-of-item advance does.
        val queued = queuedIntoPlayer
        if (queued != null) {
            if (queued.first.id == video.id && PlaybackController.advanceIntoAppended()) return
            // Queued for a DIFFERENT video than the queue actually advanced to — stale; drop it.
            discardQueuedNext()
        }
        // DASH equivalent — e.g. the lockscreen Next button pressed while the next video is already
        // appended: jump into it, and the transition listener runs onAutoAdvancedIntoQueuedLegacy.
        val queuedLegacy = queuedLegacyIntoPlayer
        if (queuedLegacy != null) {
            if (queuedLegacy.first.id == video.id && PlaybackController.advanceIntoAppended()) return
            discardQueuedNext()
        }
        // Prefetched but never appended (the append was declined, or there was no DASH manifest):
        // no gapless hand-off, but the streams are already in hand, so seed the cache and let
        // loadLegacyPrimary skip its network round trip.
        prefetchedLegacy?.takeIf { it.first == video.id }?.let {
            legacyPlaybackCache = it
            prefetchedLegacy = null
        }
        val preloaded = prefetchedHolder?.takeIf { it.first == video.id }
        if (preloaded == null) {
            // No session in hand — the SABR fetch is async, and if the screen is locked Compose
            // can't rebuild the player around the eventual _sabr value, so ask fetchSabr to hand
            // the fresh session straight to the live player once it's built (handOffToLivePlayer).
            loadInternal(video, handOffToLivePlayer = true)
            return
        }
        prefetchedHolder = null
        val (_, holder, dto) = preloaded
        val previous = currentSabrHolder()
        // onEnded: if THIS video also ends before Compose ever catches up (screen still locked), keep
        // the chain going the same way — see PlaybackController.autoAdvanceOnEnded's doc.
        PlaybackController.playDirectly(holder, video.title, video.author) { playNext() }
        previous?.release() // stop the superseded session's pump thread
        // loadInternal (below) unconditionally resets lastSabrDto/_audioTracks/_captionTracks to their
        // "nothing known yet" defaults as part of loading a new video, THEN normally fetchSabr fills
        // them back in once its network call resolves. We already have everything fetchSabr would have
        // produced, in hand — restore it right after instead of waiting on a redundant round trip.
        loadInternal(video, skipSabrFetch = true)
        lastSabrDto = dto
        _audioTracks.value = dto.audioTracks
        _captionTracks.value = dto.captionTracks
        publishSabr(UiState.Success(holder))
    }

    /** Jump to a specific queue index (tapping an item in the up-next playlist). */
    fun playAt(index: Int) {
        val q = _queue.value
        if (index in q.videos.indices && index != q.index) {
            _queue.value = q.copy(index = index)
            q.videos[index].let(::loadInternal)
        }
    }

    private fun loadInternal(
        video: SearchVideo,
        skipSabrFetch: Boolean = false,
        // Hand the freshly-built SABR session straight to the live player (playDirectly) instead of
        // relying on Compose to rebuild around the new _sabr value — required for an autoplay
        // advance that had nothing prefetched, which must still work with the screen locked (no
        // recomposition there — see playDirectly's doc). Harmless in the foreground: the eventual
        // recomposition adopts the same already-playing session via PlayerBox's adoptIfLive check.
        handOffToLivePlayer: Boolean = false,
    ) {
        val videoId = video.id
        if (loadedVideoId == videoId && _info.value is UiState.Success) return
        // A jump to a different video makes any queued-up next playlist item stale (it would
        // otherwise still auto-play the OLD prediction after this video ends). The skipSabrFetch
        // paths (advanceTo's prefetched branch / onAutoAdvancedIntoQueued) manage queuedIntoPlayer
        // themselves, so only the fresh-load path discards here.
        if (!skipSabrFetch) discardQueuedNext()
        loadedVideoId = videoId
        autoRetries = 0
        // A DASH prompt raised for the PREVIOUS video must not survive into this one — the dialog
        // would otherwise be offering to switch the transport of a video the viewer already left.
        _dashPrompt.value = null
        // autoHeight / provenBadHeight intentionally NOT reset — adaptive quality carries across videos
        lastSabrDto = null // preferredHeight intentionally NOT reset — it carries across videos
        _related.value = emptyList()
        _channelInfo.value = null
        // Seed _info immediately from the card's own metadata (title/author/thumbnail/channelId are
        // already known — no network needed) instead of showing UiState.Loading. This lets the outer
        // "loading screen" in PlayerScreen (gated on _info) get skipped entirely on every video open;
        // the only stage the viewer still waits through is the SABR fetch (_sabr), which actually
        // gates playback. fetch() below fills in the authoritative title/durationSec/etc. once
        // getVideoInfo resolves, replacing this placeholder without ever bouncing back through Loading.
        _info.value = UiState.Success(provisionalVideoInfo(video))
        // Seed history immediately with the card's (good) metadata; fetch() enriches duration/channel.
        // topic: only recoverable HERE, from the card's own sourceTag — fetch()'s later enrichment
        // call works from getVideoInfo's VideoInfo, which was never home-feed-sourced and carries no
        // sourceTag. See HistoryEntry.topic's doc (phase 14, HOME_FEED_PERSONALIZATION_PLAN.md).
        library.recordPlay(
            videoId = videoId,
            title = video.title,
            author = video.author,
            channelId = video.channelId,
            thumbnail = video.thumbnail,
            duration = video.duration,
            views = video.views,
            published = video.published,
            topic = video.sourceTag?.removePrefix("topic:")?.takeIf { it != video.sourceTag },
        )
        _sponsorSegments.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            _sponsorSegments.value = SponsorBlock.fetch(videoId, app.sabrHttpClient)
        }
        // fetchSabr goes FIRST: getSabrInfo and getVideoInfo share the PLAYBACK QuickJS context's
        // serialized queue (see YoutubeBridgeImpl's D1 doc — since the context split, the channel
        // header/related/comments calls run on the separate browse context and can't delay this
        // anymore), so whichever of the two is issued first finishes first. getSabrInfo is what
        // actually gates playback start; getVideoInfo (the title/author/legacy-format panel) is
        // secondary UI that can populate a moment later without the viewer noticing.
        // skipSabrFetch: [advanceTo] already has a fully-built session in hand (a prefetched holder)
        // and has published it to `_sabr` itself — fetching another one here would just waste a
        // network round trip and briefly flash the player back to Loading.
        if (!skipSabrFetch) {
            if (playbackMode.value == PlaybackMode.DASH) {
                // DASH-primary: the viewer chose the direct-URL path in Settings, so no SABR session
                // is built for this video at all — not even optimistically. That's the entire point
                // of the setting: DASH here is a conscious choice, not a fallback, so we must not
                // spend a /player round trip plus a WebView attestation on a transport we've been
                // told not to use, nor flash the viewer through a SABR failure on the way to it.
                //
                // handOffToLivePlayer has no equivalent on this path: PlaybackController.playDirectly
                // takes a SabrSessionHolder and there simply isn't one, so a locked-screen autoplay
                // advance in DASH mode still depends on Compose rebuilding the player around the new
                // _info/_sabr values — which is exactly the hand-off's reason to exist. Called out
                // rather than silently papered over; see BACKLOG if this ever needs closing.
                loadLegacyPrimary(videoId)
            } else {
                fetchSabr(videoId, resumeAtMs = if (handOffToLivePlayer) 0L else null)
            }
        }
        fetch(videoId)
        video.channelId?.let(::loadChannelInfo)
        loadRelated(videoId)
        loadComments(videoId)
    }

    // ── Comments ────────────────────────────────────────────────────────────
    /** Load the first page of comments for [videoId] (best-effort, off the playback path). */
    private fun loadComments(videoId: String) {
        commentsVideoId = videoId
        _comments.value = CommentsUiState(loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            val page = runCatchingCancellable { bridge.getComments(videoId, "TOP") }.getOrNull()
            if (commentsVideoId != videoId) return@launch // a newer video took over
            _comments.value = if (page == null) {
                CommentsUiState(loading = false)
            } else {
                CommentsUiState(
                    comments = page.comments,
                    hasMore = page.hasContinuation,
                    totalText = page.totalText,
                    disabled = page.disabled,
                    sort = CommentSort.TOP,
                )
            }
        }
    }

    /** Append the next page of comments (infinite scroll). No-op while already loading / at the end. */
    fun loadMoreComments() {
        val s = _comments.value
        if (s.loadingMore || !s.hasMore || s.loading) return
        val vid = commentsVideoId ?: return
        _comments.value = s.copy(loadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            val page = runCatchingCancellable { bridge.getMoreComments() }.getOrNull()
            if (commentsVideoId != vid) return@launch
            val cur = _comments.value
            _comments.value = if (page == null) {
                cur.copy(loadingMore = false, hasMore = false)
            } else {
                cur.copy(
                    comments = cur.comments + page.comments,
                    loadingMore = false,
                    hasMore = page.hasContinuation,
                )
            }
        }
    }

    /** Switch comment ordering; re-fetches from the top of the new sort. */
    fun setCommentSort(sort: CommentSort) {
        val vid = commentsVideoId ?: return
        if (_comments.value.sort == sort && !_comments.value.loading &&
            _comments.value.comments.isNotEmpty()
        ) {
            return
        }
        _comments.value = CommentsUiState(loading = true, sort = sort)
        viewModelScope.launch(Dispatchers.IO) {
            val arg = if (sort == CommentSort.NEWEST) "NEWEST" else "TOP"
            val page = runCatchingCancellable { bridge.getComments(vid, arg) }.getOrNull()
            if (commentsVideoId != vid) return@launch
            _comments.value = if (page == null) {
                CommentsUiState(loading = false, sort = sort)
            } else {
                CommentsUiState(
                    comments = page.comments,
                    hasMore = page.hasContinuation,
                    totalText = page.totalText,
                    disabled = page.disabled,
                    sort = sort,
                )
            }
        }
    }

    /** Load + expand the first batch of replies for [commentId]. */
    fun loadReplies(commentId: String) {
        val s = _comments.value
        if (s.loadingReplies.contains(commentId) || s.expandedReplies.containsKey(commentId)) return
        val vid = commentsVideoId ?: return
        _comments.value = s.copy(loadingReplies = s.loadingReplies + commentId)
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatchingCancellable { bridge.getCommentReplies(commentId) }.getOrNull()
            if (commentsVideoId != vid) return@launch
            val cur = _comments.value
            _comments.value = cur.copy(
                loadingReplies = cur.loadingReplies - commentId,
                expandedReplies = cur.expandedReplies + (commentId to (res?.replies ?: emptyList())),
            )
        }
    }

    /** Collapse a previously expanded reply thread. */
    fun collapseReplies(commentId: String) {
        val cur = _comments.value
        _comments.value = cur.copy(expandedReplies = cur.expandedReplies - commentId)
    }

    /** The position (ms) to resume [videoId] at from local history; 0 if unwatched or ~finished. */
    fun resumePositionFor(videoId: String): Long {
        val h = library.historyFor(videoId) ?: return 0L
        if (h.durationSec > 0 && h.positionMs > (h.durationSec * 1000L * 0.95).toLong()) return 0L
        return h.positionMs.coerceAtLeast(0L)
    }

    /**
     * Persist a play head to history for a SPECIFIC video (called periodically + on exit). The id is
     * passed explicitly — NOT read from [loadedVideoId] — because on a video switch the outgoing
     * screen's final update fires after loadedVideoId has already advanced, which previously wrote the
     * old position onto the NEW video and made fresh videos resume mid-way.
     */
    fun updateProgress(videoId: String, positionMs: Long) {
        if (videoId.isBlank() || positionMs <= 0L) return
        val durationSec = if (videoId == loadedVideoId) {
            (_info.value as? UiState.Success)?.data?.durationSec ?: 0
        } else {
            0 // keeps the entry's existing duration
        }
        library.updateProgress(videoId, positionMs, durationSec)
    }

    /** Subscribe/unsubscribe to the current video's channel. No-op if the channel id is unknown. */
    fun toggleSubscribe() {
        val cid = (_info.value as? UiState.Success)?.data?.channelId
            ?: _channelInfo.value?.id ?: return
        val name = _channelInfo.value?.name?.takeIf { it.isNotBlank() }
            ?: (_info.value as? UiState.Success)?.data?.author ?: ""
        library.toggleSubscribe(cid, name, avatar = _channelInfo.value?.avatar)
    }

    // The in-flight (or just-launched) getChannelInfo job, so a second caller for the SAME
    // channelId can tell "already loading" apart from "not yet started" — see loadChannelInfo.
    private var channelInfoJob: Job? = null

    private fun loadChannelInfo(channelId: String) {
        // loadInternal() calls this straight away with the card's own channelId, and fetch()
        // calls it again moments later with getVideoInfo's (usually identical) channelId once
        // that network call resolves. The old guard here only checked "already loaded"
        // (_channelInfo.value != null), which is never true yet on the second call — its
        // network round trip hasn't had time to land — so both calls sailed through and every
        // video open fired two full getChannelInfo browse requests back-to-back (confirmed in
        // logcat: the same browseId logged twice within milliseconds of each other). Tracking
        // the in-flight Job closes that gap: a second call for the same channelId while the
        // first is still running is now a genuine no-op.
        if (channelInfoFor == channelId && (channelInfoJob?.isActive == true || _channelInfo.value != null)) return
        channelInfoFor = channelId
        channelInfoJob = viewModelScope.launch(Dispatchers.IO) {
            val ci = runCatchingCancellable { bridge.getChannelInfo(channelId) }.getOrNull() ?: return@launch
            _channelInfo.value = ci
            // Backfill the avatar/name on an existing subscription now that we have it.
            library.updateSubscriptionMeta(channelId, ci.name, ci.avatar)
        }
    }

    private fun loadRelated(videoId: String) {
        relatedVideoId = videoId
        relatedHasMore = false
        viewModelScope.launch(Dispatchers.IO) {
            val res = runCatchingCancellable { bridge.getRelated(videoId) }.getOrNull()
            if (relatedVideoId != videoId) return@launch // a newer video took over
            relatedHasMore = res?.hasMore ?: false
            _related.value = res?.videos ?: emptyList()
        }
    }

    /** Infinite scroll for the "up next" related list (watch-next continuation). */
    fun loadMoreRelated() {
        if (_loadingMoreRelated.value || !relatedHasMore) return
        val vid = relatedVideoId ?: return
        val current = _related.value
        viewModelScope.launch(Dispatchers.IO) {
            _loadingMoreRelated.value = true
            try {
                val res = runCatchingCancellable { bridge.getMoreRelated() }.getOrNull()
                if (relatedVideoId != vid) return@launch
                relatedHasMore = res?.hasMore ?: false
                val more = res?.videos ?: emptyList()
                if (more.isNotEmpty()) {
                    _related.value = (current + more).distinctBy { it.id }
                }
            } finally {
                _loadingMoreRelated.value = false
            }
        }
    }

    /**
     * Manual retry (from the error UI) — resets the auto-retry budget.
     *
     * Retries on the transport the viewer actually chose: in DASH mode this re-fetches the legacy
     * playback info fresh (forceFresh, since a stale signed URL is the likeliest reason they're
     * looking at an error at all) rather than building a SABR session. Reaching for SABR here would
     * be the same category mistake as silently dropping a SABR viewer into DASH, pointed the other
     * way.
     */
    fun retry() {
        val id = loadedVideoId ?: return
        autoRetries = 0
        if (playbackMode.value == PlaybackMode.DASH) {
            loadLegacyPrimary(id, forceFresh = true)
        } else {
            fetchSabr(id)
        }
        fetch(id)
    }

    /**
     * Called by the player on a playback error. Re-fetches and lets the player
     * rebuild, up to a cap. Tiered: the first retry just gets fresh URLs from
     * the same client (handles expiry / transient 403s); later retries force a
     * full reset (re-probe client + re-mint token) to recover if the client
     * itself got clamped. Returns true if a retry was scheduled.
     *
     * [positionMs] is the play head to resume from once the fresh session is ready — passed through to
     * [fetchSabr] so it can hand the new session straight to the live player (see its doc). Without
     * this, recovery relied entirely on Compose recomposing to rebuild the player around the new `_sabr`
     * value, which silently never happens while the screen is locked (no VSYNC while the screen is off)
     * — e.g. a SponsorBlock skip landing on a cold-session hiccup, or the stall watchdog firing, while
     * playing locked, used to just go quiet until the phone was unlocked.
     *
     * Two transport-aware exits sit in front of the generic retry ladder below. In DASH-primary mode
     * recovery stays on DASH (a fresh getLegacyPlayback, never a SABR build). In SABR mode, once the
     * budget is spent, a StreamProtectionStatus=3 rejection raises [dashPrompt] and waits for the
     * viewer instead of switching transport behind their back; every other fatal cause still falls
     * back silently exactly as it did before.
     */
    fun retryAfterPlaybackError(positionMs: Long = 0L): Boolean {
        val id = loadedVideoId ?: return false
        if (autoRetries >= MAX_AUTO_RETRIES) return false
        autoRetries++
        // The recovery hand-off below replaces the player's whole playlist — a queued-up next item
        // wouldn't survive it, so drop (and release) it before it turns into a leak.
        discardQueuedNext()
        // DASH-primary mode: recover ON the transport the viewer actually chose. A failure here is
        // almost always an expired googlevideo URL (the legacy path's URLs are short-lived too),
        // which a fresh getLegacyPlayback fixes; building a SABR session as the retry would quietly
        // move a DASH viewer onto SABR — the mirror image of the silent SABR-to-DASH drop this whole
        // change exists to stop. Defensive belt-and-braces as well: in DASH mode no SABR session was
        // ever built for this video, so there is nothing SABR-side left to rebuild in any case.
        if (playbackMode.value == PlaybackMode.DASH) {
            Log.w(
                "PlayerViewModel",
                "DASH playback error for $id (attempt $autoRetries) — re-fetching legacy streams",
            )
            loadLegacyPrimary(id, forceFresh = true)
            fetch(id, forceFresh = autoRetries >= 2)
            return true
        }
        // LAST attempt: stop rebuilding SABR. When the same video has already failed twice, the
        // usual cause is the streaming server rejecting our PO token (status=3) rather than
        // anything a fresh session fixes — every rebuild then costs another /player round trip and
        // another WebView attestation, and lands in the same place ~25s later. The legacy
        // direct-URL / DASH path needs no SABR PO token at all, so switching to it actually plays
        // the video instead of erroring out. (Only the LAST attempt: a genuine transient — expired
        // URLs, a stalled cold session — is still worth one clean SABR rebuild first.)
        if (autoRetries >= MAX_AUTO_RETRIES) {
            // ...except when the server told us in so many words WHY. A status=3 rejection is the one
            // cause where switching stops being a repair and becomes a decision: SABR cannot serve
            // this video for this session at all, and DASH is a different experience (fixed format
            // selection, no per-segment adaptation), not a transparent substitute. Ask instead — and
            // note `true` here means "handled", NOT "retrying": nothing else happens until the viewer
            // answers via [switchToDashForThisVideo] / [dismissDashPrompt]. Returning true is still
            // right, because the alternative (false) makes PlayerBox paint its red "Playback error"
            // overlay straight over the dialog we're about to show.
            //
            // The verdict has to be read off the live session: by the time a failure reaches this
            // method it has been flattened into a generic PlaybackException with nothing left to
            // distinguish "the server refused our token" from "a socket died". YoutubeSabrSession
            // records every response's status (LOCAL ADDITION — see its
            // getLastResponseProtectionStatus), and SabrSessionHolder.session is a public field, so
            // the unflattened answer is one hop away. Null-safe throughout: a holder can already be
            // gone here (a concurrent quality rebuild, a navigation), and 0 = "no response carried a
            // status yet", which correctly falls through to the silent fallback below.
            val protectionStatus = currentSabrHolder()?.session?.lastResponseProtectionStatus ?: 0
            if (protectionStatus == PROTECTION_STATUS_REJECTED) {
                Log.w(
                    "PlayerViewModel",
                    "SABR rejected (status=3) for $id after $autoRetries attempts — " +
                        "asking before switching to DASH",
                )
                _dashPrompt.value = id
                return true
            }
            Log.w("PlayerViewModel", "SABR failed $autoRetries times for $id — falling back to legacy streams")
            fallBackToLegacyPlayback(id)
            return true
        }
        fetchSabr(id, resumeAtMs = positionMs, seedFrom = currentSabrHolder())
        fetch(id, forceFresh = autoRetries >= 2)
        return true
    }

    /**
     * The SABR session for [holder] opened with StreamProtectionStatus=2 (grace) on its very first
     * response. EXPECTED now, not a failure: [SabrSessionFactory.create] deliberately bootstraps
     * every session on a fast session(visitorData)-bound token (see its doc), which reads as grace
     * by design, while the precisely content(videoId)-bound upgrade token mints in the background
     * from the same attestation and gets swapped into this SAME live session the moment it's ready
     * — no teardown, no fresh session, no fresh attestation.
     *
     * Purely informational — logs so the sequence is visible in the field, but does not rebuild
     * anything. Rebuilding a whole new session on every grace open is exactly the "spam fresh
     * tokens and hope" pattern the two-phase bootstrap replaces (see run-15-through-20 history in
     * SabrSessionFactory). If the upgrade genuinely doesn't land before the grace burst runs dry,
     * [SabrStreamPump] detects that itself (opened grace, later response rejected) and marks the
     * pump fatal — see [onSabrFatal], which is the proactive counterpart to this function and is
     * wired up alongside it everywhere a holder is built.
     */
    private fun onGraceSession(holder: SabrSessionHolder) {
        val id = loadedVideoId
        // Fires from the pump thread; a prefetched next-video holder, or one already superseded by
        // a quality switch or a user navigation, isn't worth logging against the current video.
        if (id == null || id != holder.videoId || currentSabrHolder() !== holder) return
        Log.i(
            "PlayerViewModel",
            "SABR opened in grace (status=2) for $id — content-bound token upgrade is in flight",
        )
    }

    /**
     * LOCAL ADDITION. [SabrStreamPump] just gave up on [holder] (grace gone protected, a dead I/O
     * streak, or a fatal ExtractionException — see `SabrStreamPump.markFatal`'s doc) and is no
     * longer fetching new segments, though it keeps serving whatever it already cached. Previously
     * nothing reacted to this until ExoPlayer itself hit the absent segment and surfaced a hard
     * playback error — measured at 30-40s after the pump actually gave up (see BACKLOG.md "Rebuild
     * the session before the buffer starves"), because the buffered edge plays out first. That gap
     * is exactly the window this closes: kick the SAME background rebuild [rebuildSabr] already
     * uses for quality/audio switches off immediately, so the replacement session has the whole
     * remaining buffer to get ready in, instead of starting only once the buffer is already gone.
     *
     * If the rebuild wins the race, [publishSabr] swaps the player onto the new holder and the
     * viewer never sees an error at all. If it doesn't — slow network, mint failure, etc. —
     * ExoPlayer still hits the stalled pump and [retryAfterPlaybackError] remains the fallback
     * exactly as before; this is a pure addition, not a replacement of that path.
     *
     * [SabrSessionHolder.notifyFatal] is itself one-shot per holder, so this fires at most once per
     * holder — no risk of stacking up duplicate rebuilds for the same failure.
     */
    private fun onSabrFatal(holder: SabrSessionHolder) {
        val id = loadedVideoId
        if (id == null || id != holder.videoId || currentSabrHolder() !== holder) return
        Log.w(
            "PlayerViewModel",
            "SABR pump gave up for $id — starting a proactive rebuild before the buffer runs dry",
        )
        rebuildSabr()
    }

    /**
     * Abandon SABR for [videoId] and play it over the legacy direct-URL / DASH path instead.
     * Publishing [UiState.Error] to `_sabr` is what the player screen already treats as "SABR
     * unavailable → use the legacy source" (see PlayerScreen's `sabrState` branch), so the switch
     * only needs the legacy info to be in `_info` FIRST — hence the fetch is awaited here rather
     * than fired off like [loadLegacyPlaybackIfNeeded] does.
     */
    private fun fallBackToLegacyPlayback(videoId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val cached = legacyPlaybackCache?.takeIf { it.first == videoId }?.second
            val legacy = cached
                ?: runCatchingCancellable { bridge.getLegacyPlayback(videoId, forceFresh = true) }.getOrNull()
            if (legacy == null) {
                Log.w("PlayerViewModel", "legacy fallback unavailable for $videoId")
                publishSabr(UiState.Error("Couldn't load this video"))
                return@launch
            }
            legacyPlaybackCache = videoId to legacy
            (_info.value as? UiState.Success)?.data?.takeIf { it.id == videoId }?.let { current ->
                _info.value = UiState.Success(mergeLegacy(current))
            }
            val previous = currentSabrHolder()
            publishSabr(UiState.Error("Streaming protection rejected — using direct streams"))
            previous?.release() // stop the rejected session's pump thread
        }
    }

    /**
     * Load [videoId] over the legacy direct-URL / DASH path as the PRIMARY transport, because the
     * viewer selected [PlaybackMode.DASH] in Settings. No SABR session is built, or even attempted.
     *
     * Mirrors [fallBackToLegacyPlayback]'s ORDERING exactly, for exactly the same reason:
     * PlayerScreen's DASH/progressive branch reads `dashManifest`/`formats` off `_info`, and it is
     * only reached once `_sabr` has left Idle/Loading/Success — so the legacy info must be merged
     * into `_info` FIRST and `_sabr` flipped only afterwards. Flip them the other way round and the
     * screen renders "No playable format found" in the window between the two.
     *
     * What it deliberately does NOT share with [fallBackToLegacyPlayback] is the FRAMING: nothing
     * here is an error. `_sabr`'s value on this path is a transport-routing signal that PlayerScreen
     * reads, never something it renders — its `is UiState.Error ->` branch logs the message and falls
     * through to the DASH source — so a deliberate DASH open publishes [DASH_BY_CHOICE] and the
     * viewer gets plain DASH playback with no banner, no "something went wrong", no retry prompt.
     * PlayerScreen also observes [playbackMode] so it can log this at INFO instead of warning about
     * a failure that never happened. (If `_sabr` ever grows a rendered message, this is the call
     * site that has to grow a real "intentional" flag with it.)
     *
     * [forceFresh] bypasses both the per-video [legacyPlaybackCache] and the bridge's own cache and
     * asks for freshly signed URLs — set on a retry, where a stale URL is the likeliest cause.
     */
    private fun loadLegacyPrimary(videoId: String, forceFresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            // Publishing Loading first also releases any session the PREVIOUS video left behind (see
            // [publishSabr]) — in DASH mode nothing else would ever displace it, so its pump thread
            // would otherwise keep POSTing for a video nobody is watching.
            publishSabr(UiState.Loading)
            val cached = legacyPlaybackCache?.takeIf { it.first == videoId && !forceFresh }?.second
            val legacy = cached
                ?: runCatchingCancellable {
                    bridge.getLegacyPlayback(videoId, forceFresh = forceFresh)
                }.getOrNull()
            // The viewer moved to a different video while the fetch was in flight. Bailing out is not
            // just tidiness: [publishSabr] RELEASES whatever it displaces, so a stale publish here
            // would tear down the session the NEW video has meanwhile installed and stop its pump.
            if (loadedVideoId != videoId) return@launch
            if (legacy == null) {
                Log.w("PlayerViewModel", "DASH mode: legacy playback unavailable for $videoId")
                publishSabr(UiState.Error("Couldn't load this video"))
                return@launch
            }
            legacyPlaybackCache = videoId to legacy
            (_info.value as? UiState.Success)?.data?.takeIf { it.id == videoId }?.let { current ->
                _info.value = UiState.Success(mergeLegacy(current))
            }
            Log.i("PlayerViewModel", "DASH mode: playing $videoId over direct streams (no SABR session)")
            publishSabr(UiState.Error(DASH_BY_CHOICE))
        }
    }

    /**
     * The viewer accepted the status=3 prompt (see [dashPrompt]): play THIS video over DASH. Does
     * exactly what the automatic fallback used to do unprompted — the only difference is that a
     * human asked for it.
     *
     * Deliberately per-video and NOT persisted: one rejected video says nothing about how the viewer
     * wants every future video streamed, and quietly rewriting their global setting from an error
     * dialog would be precisely the "DASH as a demotion" behaviour this replaces. The durable choice
     * lives in Settings ([com.example.ytclient.core.settings.PlaybackSettings]).
     */
    fun switchToDashForThisVideo() {
        // Fall back to loadedVideoId only defensively — the prompt is always raised with an id, but
        // a dialog surviving one frame past a video switch shouldn't be able to no-op silently.
        val id = _dashPrompt.value ?: loadedVideoId ?: return
        // Cleared FIRST so the dialog disappears on this frame rather than whenever the (async,
        // network-bound) legacy fetch below happens to land.
        _dashPrompt.value = null
        Log.i("PlayerViewModel", "viewer accepted the DASH switch for $id after a status=3 rejection")
        fallBackToLegacyPlayback(id)
    }

    /**
     * The viewer declined the status=3 prompt. Clears the dialog and nothing else: the retry budget
     * is already spent, so the failed session stays where it is and the screen behaves exactly as it
     * did before this prompt existed for an unrecoverable SABR failure — the next playback error
     * reports "not handled" and PlayerBox paints its usual error overlay, with [retry] (or reopening
     * the video) as the way back. Notably we do NOT fall back to DASH anyway: "no" means no.
     */
    fun dismissDashPrompt() {
        _dashPrompt.value = null
    }

    // Prefetched SABR info for the predicted next video (see prefetchUpNext), keyed by video id so a
    // stale entry for a video we DIDN'T end up advancing to (queue reordered, autoplay picked a
    // different related video, …) is simply ignored by fetchSabr rather than needing explicit
    // invalidation on every queue/related-list change.
    private var prefetchedSabr: Pair<String, SabrInfoDto>? = null
    private var prefetchJob: Job? = null

    // A FULLY-BUILT SABR session for the predicted next video (videoId, holder, dto) — a step further
    // than [prefetchedSabr] above. Needed so autoplay-next can hand a next video straight to
    // PlaybackController.playDirectly with zero async work left to do: that's what lets audio-only
    // background playback (screen locked) keep advancing the instant one video ends, since Compose
    // can't rebuild the player in that state (see playDirectly's doc for why). Consumed by
    // [maybeQueueNextIntoPlayer] (the preferred, gapless path — becomes [queuedIntoPlayer]) or by
    // [advanceTo]; released unconsumed in [onCleared] or if superseded by a fresher prediction.
    private var prefetchedHolder: Triple<String, SabrSessionHolder, SabrInfoDto>? = null

    // The prefetched next video currently sitting as the live player's SECOND playlist item
    // (video, holder, dto) — see [maybeQueueNextIntoPlayer] / PlaybackController.appendNext. This is
    // the gapless upgrade over the playDirectly-at-STATE_ENDED hand-off: ExoPlayer transitions into
    // the appended item natively, the player never reports ENDED between queue items, and media3
    // therefore never demotes the foreground service — which a backgrounded app on Android 12+
    // cannot re-promote, previously costing the lockscreen controls (and, once the process got
    // cached/frozen, the audio itself) on the first background autoplay advance. Consumed by
    // [onAutoAdvancedIntoQueued]; discarded via [discardQueuedNext].
    private var queuedIntoPlayer: Triple<SearchVideo, SabrSessionHolder, SabrInfoDto>? = null

    /**
     * If a fully-built session for the predicted next video is on hand and nothing is queued yet,
     * append it to the live player as its next playlist item (see [queuedIntoPlayer]'s doc).
     * MAIN THREAD ONLY (PlaybackController.appendNext touches the ExoPlayer). No-ops harmlessly
     * whenever any precondition isn't met — it's re-invoked on every near-end poll tick anyway.
     */
    private fun maybeQueueNextIntoPlayer() {
        if (queuedIntoPlayer != null) return
        val (id, holder, dto) = prefetchedHolder ?: return
        // Only queue while the prediction still matches the actual up-next video.
        val video = upNext.value?.takeIf { it.id == id } ?: return
        val appended = PlaybackController.appendNext(
            holder, video.title, video.author,
            onTransition = { onAutoAdvancedIntoQueued() },
            onEnded = { playNext() },
        )
        if (appended) {
            queuedIntoPlayer = Triple(video, holder, dto)
            prefetchedHolder = null
        }
    }

    /**
     * Playback crossed into the appended next item (PlaybackController's transition listener) —
     * the player is already playing [queuedIntoPlayer]'s video; catch the ViewModel state up to
     * match, exactly like [advanceTo]'s prefetched branch does after a playDirectly hand-off.
     * Runs on the main thread.
     */
    private fun onAutoAdvancedIntoQueued() {
        val (video, holder, dto) = queuedIntoPlayer ?: return
        queuedIntoPlayer = null
        val q = _queue.value
        val idx = q.videos.indexOfFirst { it.id == video.id }
        _queue.value = if (idx >= 0) {
            q.copy(index = idx)
        } else {
            // The appended video was a related-videos pick, not a queue item — same single-entry
            // related chain playNext()'s autoplayRelated branch builds.
            PlayerQueue(listOf(video), 0, autoplayRelated = true)
        }
        val previous = currentSabrHolder()
        loadInternal(video, skipSabrFetch = true)
        lastSabrDto = dto
        _audioTracks.value = dto.audioTracks
        _captionTracks.value = dto.captionTracks
        publishSabr(UiState.Success(holder))
        previous?.release() // stop the finished session's pump thread
    }

    /**
     * Drop (and release) a queued-but-not-reached next item: the user navigated to a different
     * video, a playback-error recovery is about to replace the whole playlist, or a sleep-at-end
     * was armed (which needs the player to actually END instead of auto-advancing). Safe from any
     * thread.
     */
    private fun discardQueuedNext() {
        // The DASH slot too: every caller (a jump to another video, error recovery, sleep-at-end)
        // needs a stale appended item gone regardless of which transport queued it. Nothing to
        // release on this side — unlike a SABR holder, a LegacyPlaybackInfo owns no pump thread.
        if (queuedLegacyIntoPlayer != null) {
            queuedLegacyIntoPlayer = null
            PlaybackController.clearAppendedNext()
        }
        val queued = queuedIntoPlayer ?: return
        queuedIntoPlayer = null
        PlaybackController.clearAppendedNext()
        queued.second.release()
    }

    /** See [discardQueuedNext] — exposed for the sleep-timer UI ("stop at end of this video"). */
    fun cancelQueuedNext() = discardQueuedNext()

    // ── DASH-mode gapless hand-off ──────────────────────────────────────────
    // The DASH counterpart of [prefetchedHolder] / [queuedIntoPlayer]. Same idea — have the next
    // video ready before the current one ends and append it to the LIVE player, so ExoPlayer moves
    // into it natively and the media session (and the foreground service it holds) never drops —
    // but built only from what DASH mode already uses: the same getLegacyPlayback result
    // [loadLegacyPrimary] would fetch, and the same DASH source PlayerScreen would build.
    //
    // Kept in its OWN slot rather than written straight into [legacyPlaybackCache]: that cache is a
    // single entry holding the CURRENT video's result, which [mergeLegacy] re-reads whenever
    // getVideoInfo completes. Overwriting it with the next video's data mid-playback would make the
    // current video's late merge silently no-op.
    private var prefetchedLegacy: Pair<String, LegacyPlaybackInfo>? = null
    private var legacyPrefetchJob: Job? = null
    private var queuedLegacyIntoPlayer: Pair<SearchVideo, LegacyPlaybackInfo>? = null

    /**
     * DASH mode's [prefetchUpNext]: fetch the predicted next video's direct streams a few seconds
     * before the end (PlayerScreen's onNearEnd, last [NEAR_END_PREFETCH_MS]) and queue them onto the
     * live player. Idempotent per video — re-invoked on every near-end poll tick, which doubles as
     * the retry path when an append is declined.
     */
    private fun prefetchLegacyUpNext(queueIntoPlayer: Boolean) {
        val next = upNext.value ?: return
        if (prefetchedLegacy?.first == next.id) {
            if (queueIntoPlayer) maybeQueueLegacyIntoPlayer()
            return
        }
        if (legacyPrefetchJob?.isActive == true) return
        legacyPrefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val legacy = runCatchingCancellable { bridge.getLegacyPlayback(next.id) }.getOrNull()
            if (legacy == null) {
                Log.w("PlayerViewModel", "DASH prefetch: no direct streams for ${next.id}; advance will load normally")
                return@launch
            }
            prefetchedLegacy = next.id to legacy
            if (queueIntoPlayer) withContext(Dispatchers.Main) { maybeQueueLegacyIntoPlayer() }
        }
    }

    /**
     * Append the prefetched next video's DASH source to the live player (see the section comment
     * above). MAIN THREAD ONLY. No-ops — leaving the old behaviour (a normal load at STATE_ENDED) —
     * whenever a precondition fails, the most notable being a result with no DASH manifest: the
     * progressive-only fallback builds its source from a format choice made inside PlayerScreen,
     * which can't be reproduced here without duplicating that logic.
     */
    private fun maybeQueueLegacyIntoPlayer() {
        if (queuedLegacyIntoPlayer != null || queuedIntoPlayer != null) return
        val (id, legacy) = prefetchedLegacy ?: return
        // Only queue while the prediction still matches the actual up-next video.
        val video = upNext.value?.takeIf { it.id == id } ?: return
        val manifest = legacy.dashManifest ?: run {
            Log.i("PlayerViewModel", "DASH prefetch: ${video.id} has no DASH manifest — no gapless hand-off")
            return
        }
        // Same user agent PlayerScreen would use: mergeLegacy copies streamUserAgent into the
        // VideoInfo the DASH branch reads, falling back to PLAYER_UA exactly as it does there.
        val source = buildDashSource(
            app, manifest, legacy.streamUserAgent ?: PLAYER_UA, video.id, video.title, video.author,
        )
        val appended = PlaybackController.appendNextSource(
            source,
            dashMediaKey(manifest),
            onTransition = { onAutoAdvancedIntoQueuedLegacy() },
            onEnded = { playNext() },
        )
        if (appended) {
            Log.i("PlayerViewModel", "DASH: queued ${video.id} onto the live player (gapless hand-off)")
            queuedLegacyIntoPlayer = video to legacy
            prefetchedLegacy = null
        }
    }

    /**
     * Playback crossed into the appended DASH item. Catch the ViewModel up WITHOUT touching the
     * player: seed [legacyPlaybackCache] with the result the source was built from, then take the
     * normal [loadInternal] path. In DASH mode that runs [loadLegacyPrimary], which finds the cache
     * (no network), merges the same `dashManifest` into `_info` and publishes [DASH_BY_CHOICE].
     * When the UI next composes, PlayerBox computes `dashMediaKey(info.dashManifest)` — identical to
     * the key the live player was recorded under at append time — and ADOPTS it; the outgoing
     * composition sees itself hijacked and leaves the player and session alone. Runs on main.
     */
    private fun onAutoAdvancedIntoQueuedLegacy() {
        val (video, legacy) = queuedLegacyIntoPlayer ?: return
        queuedLegacyIntoPlayer = null
        val q = _queue.value
        val idx = q.videos.indexOfFirst { it.id == video.id }
        _queue.value = if (idx >= 0) {
            q.copy(index = idx)
        } else {
            // Same single-entry related chain playNext()'s autoplayRelated branch builds.
            PlayerQueue(listOf(video), 0, autoplayRelated = true)
        }
        Log.i("PlayerViewModel", "DASH: advanced into ${video.id} on the live player")
        legacyPlaybackCache = video.id to legacy
        loadInternal(video)
    }

    /**
     * Warm the predicted next video's SABR info — and a full, ready-to-play session — ahead of time.
     * Called from the player as playback nears the end (see PlayerScreen's onNearEnd). getSabrInfo is
     * the network round trip that currently gates every video switch; doing it a few seconds early,
     * while the current video is still playing (and therefore not competing with anything more urgent
     * on the shared JS call queue), lets a normal (foreground) advance skip straight to building the
     * player next time. Going the extra step to a fully-built [SabrSessionHolder] (not just the DTO)
     * is what lets [advanceTo] hand a ready session straight to the live player with no further async
     * work — see [prefetchedHolder]'s doc. No-ops if we've already prefetched (or are already
     * prefetching) this exact video.
     */
    fun prefetchUpNext(queueIntoPlayer: Boolean = true) {
        // DASH-primary mode never touches the SABR pipeline below (getSabrInfo -> SabrSessionFactory
        // -> append): running it for a viewer who chose DASH would both spend an attestation we were
        // told not to spend AND hand [advanceTo] a SABR holder to play the next video with — quietly
        // putting them back on SABR at the first autoplay advance.
        //
        // But it used to stop there, on the reasoning that a DASH advance merely takes the
        // "slightly slower" [loadLegacyPrimary] path. With the screen locked it was far worse than
        // slower: that path builds a NEW player, releasing the media session — and the foreground
        // service with it — in between, and Android 12+ won't let a backgrounded app bring the next
        // session's service back into the foreground. The app then lost its network and the next
        // video stalled into "Couldn't load" (captured 2026-10-17). So DASH now gets its own
        // gapless hand-off, built only from what DASH mode already uses — see [prefetchLegacyUpNext].
        if (playbackMode.value == PlaybackMode.DASH) {
            prefetchLegacyUpNext(queueIntoPlayer)
            return
        }
        val next = upNext.value ?: return
        if (prefetchedSabr?.first == next.id || prefetchedHolder?.first == next.id) {
            // Already prefetched (an earlier near-end tick) — the remaining step is getting it
            // queued into the player, which can only happen once a holder exists and may have been
            // declined last tick (e.g. player briefly not in single-item shape). Called from the
            // near-end poll, i.e. already on the main thread appendNext requires.
            if (queueIntoPlayer) maybeQueueNextIntoPlayer()
            return
        }
        if (prefetchJob?.isActive == true) return
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val dto = runCatchingCancellable { bridge.getSabrInfo(next.id) }.getOrNull() ?: return@launch
            prefetchedSabr = next.id to dto
            if (dto.serverAbrStreamingUrl.isNullOrEmpty() || dto.ustreamerConfig.isNullOrEmpty()) return@launch
            val holder = runCatchingCancellable {
                SabrSessionFactory.create(
                    dto, app.webPoProvider, app.sabrHttpClient,
                    preferredVideoHeight = effectiveHeight(),
                    maxVideoHeight = maxVideoHeight,
                    preferredAudioTrackId = resolveAudioTrackId(dto.audioTracks, _preferredAudioLang.value),
                )
            }.getOrNull() ?: return@launch
            // Same grace bail-out as the live path. Harmless while this holder is only a prediction
            // (onGraceSession ignores any holder that isn't the live one), but it has to be attached
            // HERE: once autoplay promotes this holder into the player it is never routed through
            // fetchSabr, so there is no later opportunity to wire it up.
            holder.setOnGraceSession { onGraceSession(holder) }
            holder.setOnFatal { onSabrFatal(holder) }
            // Release a stale prediction we're superseding (its pump was never started — nothing ever
            // read from it — so this is a cheap, harmless no-op, just good hygiene).
            prefetchedHolder?.second?.takeIf { it !== holder }?.release()
            prefetchedHolder = Triple(next.id, holder, dto)
            // Queue it into the live player right away (gapless auto-advance — see
            // queuedIntoPlayer's doc) instead of waiting for the next near-end poll tick; the
            // remaining ticks still act as the retry path if this attempt declines.
            if (queueIntoPlayer) {
                withContext(Dispatchers.Main) { maybeQueueNextIntoPlayer() }
            }
        }
    }

    /**
     * Build the native SABR streaming source for [videoId]: ask the JS runtime for the SABR info
     * (profile, deciphered serverAbrStreamingUrl, ustreamer config, formats) and wrap it in a
     * [SabrSessionHolder]. Network only happens in `getSabrInfo`; the factory itself is cheap.
     *
     * [resumeAtMs] is non-null when this is a playback-error retry (see [retryAfterPlaybackError])
     * for a video that was ALREADY playing, or 0 for an autoplay advance that had nothing
     * prefetched (loadInternal's handOffToLivePlayer) — as opposed to a fresh user-driven video
     * load, where nothing live needs hijacking and the normal Compose build path is exactly right.
     * When set, the freshly-built
     * session is handed straight to whatever player is currently live via
     * [PlaybackController.playDirectly], the same direct-hijack mechanism autoplay-next uses — this is
     * what lets error recovery actually complete while the screen is locked, where Compose can't
     * recompose to rebuild the player the normal way (see playDirectly's doc). No-ops harmlessly if
     * nothing is currently live; `_sabr` below still publishes the session either way so a normal
     * Compose rebuild (foreground, or once the screen unlocks) adopts this SAME session instead of
     * building a redundant duplicate — see PlayerBox's `adopted` check.
     */
    private fun fetchSabr(
        videoId: String,
        resumeAtMs: Long? = null,
        forceFresh: Boolean = false,
        // The still-live holder this fetch is superseding (a playback-error retry passes the
        // holder that just failed). Without this, every retry re-runs SabrSessionFactory's
        // two-phase bootstrap from a bare session(visitorData)-bound pot and races the server's
        // ~60s grace window all over again — the same race that just lost. Seeding with the prior
        // pot (when it's still being accepted) lets the new session start already content-bound,
        // mirroring what rebuildSabr already does for quality/audio switches.
        seedFrom: SabrSessionHolder? = null,
    ) {
        val previous = currentSabrHolder()
        // Read the seed's pot state up front, before previous?.release() below — release() tears
        // down the pump but not these fields, so this is precautionary rather than strictly
        // required, but it keeps the read from depending on release()'s implementation.
        val seedPot = seedFrom?.session?.streamState?.poToken
            ?.takeIf { !seedFrom.session.isPoTokenRefused }
        val seedUpgraded = seedFrom?.isPoTokenUpgraded ?: false
        viewModelScope.launch(Dispatchers.IO) {
            publishSabr(UiState.Loading)
            previous?.release() // stop the superseded session's pump thread
            val next: UiState<SabrSessionHolder> = try {
                // Build exactly ONE session — no more building-and-discarding candidates hoping for
                // a lucky draw (see SabrSessionFactory's two-phase token bootstrap doc for why: the
                // session's opening protection status is no longer a signal to throw it away, it's
                // an EXPECTED transient state that the session upgrades itself out of in place).
                //
                // A forceFresh rebuild means the previous identity was rejected, so a prefetched DTO
                // built against that same rejected identity is worthless — skip it and re-probe.
                val prefetched = prefetchedSabr?.takeIf { it.first == videoId }
                    ?.also { prefetchedSabr = null }
                    ?.takeUnless { forceFresh }
                val dto = prefetched?.second
                    ?: bridge.getSabrInfo(videoId, forceFresh = forceFresh)
                if (dto.serverAbrStreamingUrl.isNullOrEmpty() ||
                    dto.ustreamerConfig.isNullOrEmpty()
                ) {
                    throw IllegalStateException(
                        "SABR info incomplete " +
                            "(abrUrl=${!dto.serverAbrStreamingUrl.isNullOrEmpty()} " +
                            "ustreamer=${!dto.ustreamerConfig.isNullOrEmpty()})",
                    )
                }
                lastSabrDto = dto
                val holder = SabrSessionFactory.create(
                    dto, app.webPoProvider, app.sabrHttpClient,
                    preferredVideoHeight = effectiveHeight(),
                    maxVideoHeight = maxVideoHeight,
                    preferredAudioTrackId =
                        resolveAudioTrackId(dto.audioTracks, _preferredAudioLang.value),
                    seedPoToken = seedPot,
                    seedPoTokenUpgraded = seedUpgraded,
                )
                holder.setOnGraceSession { onGraceSession(holder) }
                holder.setOnFatal { onSabrFatal(holder) }
                _audioTracks.value = dto.audioTracks
                _captionTracks.value = dto.captionTracks
                // getSabrInfo already carries title/durationSec/isLive, and — thanks to fetchSabr
                // being dispatched first (see loadInternal) — it resolves before the separate
                // getVideoInfo call even starts on the shared single-flight JS queue. Patch those
                // fields into whatever VideoInfo is already showing (the provisional card guess, most
                // likely) right now instead of leaving the viewer looking at a possibly-stale/missing
                // duration until getVideoInfo's own (slower, queued-behind-this-one) result lands.
                // getVideoInfo's completion still overwrites this later with the same data plus the
                // fields SABR doesn't carry (author/channelId/viewCount/playabilityReason) — this is
                // purely a head start, not a replacement.
                (_info.value as? UiState.Success)?.data?.let { cur ->
                    if (cur.id == videoId) {
                        _info.value = UiState.Success(
                            cur.copy(
                                title = dto.title.ifBlank { cur.title },
                                durationSec = dto.durationSec?.toInt() ?: cur.durationSec,
                                isLive = dto.isLive,
                            ),
                        )
                    }
                }
                if (resumeAtMs != null) {
                    val info = (_info.value as? UiState.Success)?.data
                    PlaybackController.playDirectly(
                        holder,
                        info?.title,
                        info?.author,
                        resumePositionMs = resumeAtMs,
                    ) { playNext() }
                }
                UiState.Success(holder)
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w("PlayerViewModel", "SABR setup failed, will fall back to legacy: ${t.message}")
                loadLegacyPlaybackIfNeeded(videoId)
                UiState.Error(t.message ?: "SABR setup failed")
            }
            publishSabr(next)
        }
    }

    // The last video's legacy playback info (direct-URL formats + DASH manifest), fetched via
    // getLegacyPlayback ONLY when fetchSabr's catch block above sees a genuine SABR failure — see
    // getLegacyPlayback's own doc comment for why this is lazy rather than part of every fetch().
    // Keyed by video id so a stale entry for a PREVIOUS video is simply ignored by mergeLegacy
    // rather than needing to be explicitly cleared on every new video open.
    private var legacyPlaybackCache: Pair<String, LegacyPlaybackInfo>? = null
    private var legacyPlaybackJob: Job? = null

    private fun mergeLegacy(vi: VideoInfo): VideoInfo {
        val cached = legacyPlaybackCache ?: return vi
        if (cached.first != vi.id) return vi
        val legacy = cached.second
        return vi.copy(
            formats = legacy.formats,
            sourceClient = legacy.sourceClient,
            streamUserAgent = legacy.streamUserAgent,
            dashManifest = legacy.dashManifest,
        )
    }

    /**
     * Fetch (once per video) the heavy legacy-playback fields and merge them into whatever
     * [VideoInfo] is currently showing. Also re-applies the merge from an already-cached result
     * without a new network call — needed because [fetch]'s own completion (a plain getVideoInfo
     * response, which never carries these fields) can land AFTER this ran and would otherwise wipe
     * the merge back out; [fetch] guards against that on its side by calling [mergeLegacy] too, but
     * this covers the reverse ordering.
     */
    private fun loadLegacyPlaybackIfNeeded(videoId: String) {
        val cached = legacyPlaybackCache
        if (cached != null && cached.first == videoId) {
            val current = (_info.value as? UiState.Success)?.data
            if (current != null && current.id == videoId && current.dashManifest == null) {
                _info.value = UiState.Success(mergeLegacy(current))
            }
            return
        }
        if (legacyPlaybackJob?.isActive == true) return
        legacyPlaybackJob = viewModelScope.launch(Dispatchers.IO) {
            val legacy = runCatchingCancellable { bridge.getLegacyPlayback(videoId) }.getOrNull() ?: return@launch
            legacyPlaybackCache = videoId to legacy
            val current = (_info.value as? UiState.Success)?.data ?: return@launch
            if (current.id != videoId) return@launch
            _info.value = UiState.Success(mergeLegacy(current))
        }
    }

    /**
     * Manual video-quality switch (from the player's quality menu). Persists [height] as the user's
     * preference (sticks across videos + restarts), then rebuilds the SABR session for the CURRENT
     * video at that height — audio stays AAC — reusing the already server-accepted pot so there's no
     * fresh attestation. The new holder swaps in on [sabr], rebuilding the player; the screen-hoisted
     * resume position restores playback at the same spot. On failure the existing holder keeps playing
     * (we do NOT drop to the legacy path). [height] 0 = best available (within the device cap).
     */
    fun setPreferredHeight(height: Int) {
        if (_preferredHeight.value != height) {
            _preferredHeight.value = height
            prefs.edit().putInt(PREF_HEIGHT, height).apply()
        }
        // The user took manual control: clear the adaptive override and the proven-bad ceiling so their
        // pick is honoured exactly and adaptation can start fresh from there.
        autoHeight = 0
        provenBadHeight = Int.MAX_VALUE
        val old = currentSabrHolder() ?: return
        if (old.videoFormat.height == effectiveHeight()) return // already there
        rebuildSabr()
    }

    /**
     * Manual audio-language switch. Persists [lang] (carries across videos), then rebuilds the SABR
     * session keeping the current video height. English is the default when nothing is picked.
     */
    fun setPreferredAudioLanguage(lang: String) {
        if (_preferredAudioLang.value != lang) {
            _preferredAudioLang.value = lang
            prefs.edit().putString(PREF_AUDIO_LANG, lang).apply()
        }
        val old = currentSabrHolder() ?: return
        val wantId = resolveAudioTrackId(_audioTracks.value, lang)
        if (old.audioFormat.audioTrackId == wantId) return // already on this track
        rebuildSabr()
    }

    /** Rebuild the SABR session for the current video at the effective height + audio language, reusing
     *  the already-accepted pot (fast, no re-attestation). Shared by quality/audio/adaptive switches. */
    private fun rebuildSabr() {
        val old = currentSabrHolder() ?: return
        val dto = lastSabrDto ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val holder = SabrSessionFactory.create(
                    dto, app.webPoProvider, app.sabrHttpClient,
                    preferredVideoHeight = effectiveHeight(),
                    maxVideoHeight = maxVideoHeight,
                    preferredAudioTrackId = resolveAudioTrackId(dto.audioTracks, _preferredAudioLang.value),
                    // Reuse the pot ONLY if it is still being accepted. Seeding a refused token
                    // (server at status=3) would carry the rejection straight into the new session
                    // and burn the rebuild for nothing; passing null makes the factory mint fresh.
                    seedPoToken = old.session.streamState.poToken
                        ?.takeIf { !old.session.isPoTokenRefused },
                    // Carry the upgrade state along too: if `old` already completed the two-phase
                    // bootstrap (content-bound, presumably accepted), the new session should not
                    // repeat it — it's already reusing the very same bytes.
                    seedPoTokenUpgraded = old.isPoTokenUpgraded,
                )
                // A quality/audio switch opens a genuinely new SABR session against the same
                // player response, so it gets its own n=0 protection verdict and needs its own
                // bail-out. Note this one usually reuses the accepted pot above, so if it comes
                // back in grace the pot is not what GVS is objecting to.
                holder.setOnGraceSession { onGraceSession(holder) }
                holder.setOnFatal { onSabrFatal(holder) }
                publishSabr(UiState.Success(holder))
                old.release()
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Log.w("PlayerViewModel", "SABR rebuild failed, keeping current: ${t.message}")
            }
        }
    }

    /**
     * The player rebuffered repeatedly (link/decoder can't sustain the current quality). Step the video
     * DOWN one decodable rung (floor from [SabrQuality.minAutoDowngradeHeight] — 480p on hardware
     * decode, much lower on software, where the decoder rather than the link is the bottleneck),
     * remember this height as proven-bad so adaptive-UP won't
     * climb back into it, and rebuild reusing the accepted pot.
     */
    fun onBandwidthLow() {
        val holder = currentSabrHolder() ?: return
        val current = holder.videoFormat.height
        val floor = SabrQuality.minAutoDowngradeHeight()
        val next = SabrQuality.videoOptions(holder.info, maxVideoHeight)
            .firstOrNull { it.height < current && it.height >= floor }
        if (next == null) {
            // Was a silent `?: return`, which is why the field log showed "requesting lower quality"
            // three times at 480p with nothing happening. If we're out of rungs, say so — the
            // rebuffering is real and the next question is why, not which quality to try.
            Log.i("PlayerViewModel", "bandwidth low at ${current}p but no rung below floor ${floor}p")
            return
        }
        provenBadHeight = minOf(provenBadHeight, current)
        autoHeight = next.height
        Log.i("PlayerViewModel", "bandwidth low: ${current}p -> ${next.height}p (provenBad=$provenBadHeight)")
        rebuildSabr()
    }

    /**
     * Playback has been smooth for a sustained stretch and we're currently BELOW the user's ceiling due
     * to an earlier downgrade. Step UP one decodable rung — but never to/above [provenBadHeight] (the
     * level that rebuffered), so quality settles at the best stable rung instead of oscillating.
     */
    fun onBandwidthGood() {
        val holder = currentSabrHolder() ?: return
        val current = holder.videoFormat.height
        val ceiling = userCeilingHeight()
        if (current >= ceiling) return // already at the user's ceiling, nothing to gain
        // Next rung up: the smallest decodable height above current that's within ceiling and below the
        // proven-bad level (a gentle one-step climb).
        val next = SabrQuality.videoOptions(holder.info, maxVideoHeight)
            .filter { it.height > current && it.height <= ceiling && it.height < provenBadHeight }
            .minByOrNull { it.height } ?: return
        autoHeight = if (next.height >= ceiling) 0 else next.height // 0 = back to following the ceiling
        Log.i("PlayerViewModel", "bandwidth good: ${current}p -> ${next.height}p (provenBad=$provenBadHeight)")
        rebuildSabr()
    }

    /**
     * Resolve the audioTrackId to stream for a preferred language: exact language match → English →
     * the original/default track → first. Returns null (= original/best) when there are no tracks.
     */
    private fun resolveAudioTrackId(tracks: List<AudioTrackInfo>, lang: String): String? {
        if (tracks.isEmpty()) return null
        // Explicit "play the original" choice.
        if (lang.equals("original", true)) {
            return (tracks.firstOrNull { it.isDefault } ?: tracks.first()).id.ifBlank { null }
        }
        // Match by language CODE only (not displayName substring — "French" contains "en").
        fun langMatch(t: AudioTrackInfo, code: String) =
            t.language.equals(code, true) || t.language.startsWith("$code-", true)
        val chosen = tracks.firstOrNull { langMatch(it, lang) }
            ?: tracks.firstOrNull { langMatch(it, "en") || it.displayName.startsWith("English", true) }
            ?: tracks.firstOrNull { it.isDefault }
            ?: tracks.first()
        return chosen.id.ifBlank { null }
    }

    private fun currentSabrHolder(): SabrSessionHolder? =
        (_sabr.value as? UiState.Success)?.data

    /**
     * The ONLY way `_sabr` should be written. Releases whatever holder is being displaced, read
     * from the field at swap time rather than from a snapshot captured earlier.
     *
     * Why it matters: several paths build sessions concurrently (the stall-watchdog retry, an
     * adaptive quality downgrade, an autoplay advance), and each used to capture `previous` BEFORE
     * launching its coroutine. Interleave two of them and both capture holder A: both release A
     * (harmless), but whichever publishes first has its holder silently overwritten by the second
     * and is never released — a leaked, still-pumping SABR session. Field logs showed sessions from
     * PREVIOUS VIDEOS still POSTing every ~2s. Releasing exactly what we displace, at the moment we
     * displace it, makes that unrepresentable regardless of interleaving. (Double release is safe —
     * it's an idempotent pump shutdown.)
     */
    private fun publishSabr(next: UiState<SabrSessionHolder>) {
        val displaced = currentSabrHolder()
        _sabr.value = next
        val incoming = (next as? UiState.Success)?.data
        if (displaced != null && displaced !== incoming) {
            displaced.release()
        }
    }

    override fun onCleared() {
        currentSabrHolder()?.release()
        prefetchedHolder?.second?.release() // an unconsumed prediction — its pump was never started
        discardQueuedNext() // ditto for a prediction already appended to the (gone) player
        super.onCleared()
    }

    /**
     * A best-effort [VideoInfo] built entirely from the [SearchVideo] card the user just tapped —
     * no network call. Every field it can't know yet (durationSec, formats, streaming, etc.) is left
     * at its default; [fetch] overwrites this with the authoritative version once getVideoInfo
     * resolves. Purely a placeholder so the player can render immediately instead of showing a
     * loading screen while we already have perfectly good title/author/thumbnail on hand.
     */
    private fun provisionalVideoInfo(video: SearchVideo): VideoInfo = VideoInfo(
        id = video.id,
        title = video.title,
        author = video.author,
        channelId = video.channelId,
        thumbnails = video.thumbnail?.let { listOf(Thumbnail(url = it)) } ?: emptyList(),
    )

    private fun fetch(videoId: String, forceFresh: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            // No _info.value = UiState.Loading here: loadInternal() already seeded a provisional
            // Success from the tapped card, and retry()/retryAfterPlaybackError() call fetch() on a
            // video that already has real info showing — bouncing through Loading in either case
            // would just flash the loading screen back on for no reason. The result below (Success or
            // Error) replaces whatever's there regardless.
            //
            // Retried up to FETCH_INFO_RETRIES times on failure: getVideoInfo funnels through
            // chooseClientInfo, which probes every known Innertube client and throws "No Innertube
            // client returned playable info" only when EVERY one of them failed outright — in practice
            // this fires almost exclusively right after the phone wakes from a long locked-screen sleep,
            // where the radio/network is still coming back up and a batch of requests fired together can
            // all spuriously fail even though the connection recovers moments later. A couple of retries
            // gives that recovery a chance instead of immediately declaring the video unloadable.
            var result: UiState<VideoInfo>? = null
            var attempt = 0
            while (result == null) {
                result = try {
                    val vi = bridge.getVideoInfo(videoId, forceFresh)
                    // Enrich the history entry with authoritative channel id + duration (keeps the
                    // card thumbnail/title already seeded by loadVideo — thumbnail is left null here).
                    // tags rides along here too — see HistoryEntry.tags's doc (HomeRanker's tag-affinity
                    // scoring, phase 8 of HOME_FEED_PERSONALIZATION_PLAN.md).
                    library.recordPlay(
                        videoId = vi.id,
                        title = vi.title,
                        author = vi.author,
                        channelId = vi.channelId,
                        durationSec = vi.durationSec ?: 0,
                        tags = vi.tags,
                    )
                    // If the card didn't carry a channelId, fetch the uploader logo now that we know it.
                    vi.channelId?.let(::loadChannelInfo)
                    // getVideoInfo itself never carries formats/dashManifest anymore (see its doc
                    // comment in index.js) — re-apply an already-fetched legacy result in case this
                    // completion lands after loadLegacyPlaybackIfNeeded already populated it.
                    UiState.Success(mergeLegacy(vi))
                } catch (ce: CancellationException) {
                    // Notably includes the delay() below on a cancelled retry wait — without this,
                    // a cancellation mid-backoff used to be treated as just another failed attempt
                    // and could trigger yet another pointless retry+delay+getVideoInfo cycle after
                    // the caller had already stopped caring.
                    throw ce
                } catch (t: Throwable) {
                    if (attempt < FETCH_INFO_RETRIES) {
                        attempt++
                        Log.w("PlayerViewModel", "getVideoInfo failed (attempt $attempt): ${t.message}")
                        delay(1000L * attempt)
                        null // loop again
                    } else {
                        // getVideoInfo is secondary/non-critical metadata — SABR (fetchSabr, dispatched
                        // independently) is what actually gates playback, so a video that's already
                        // playing fine shouldn't have its whole screen blanked to a hard error just
                        // because this refresh couldn't complete. Only actually surface the error if we
                        // don't already have something better on screen.
                        val current = _info.value
                        if (current is UiState.Success) {
                            Log.w(
                                "PlayerViewModel",
                                "getVideoInfo failed after $attempt retries, keeping existing info: ${t.message}",
                            )
                            current
                        } else {
                            UiState.Error(t.message ?: "Failed to load video info")
                        }
                    }
                }
            }
            // Guard against a slow retry (real elapsed time via the delay above) landing after the user
            // has since moved on to a different video.
            val finalResult = checkNotNull(result) { "fetch() loop must not exit with a null result" }
            if (loadedVideoId == videoId) _info.value = finalResult
        }
    }

    private companion object {
        const val MAX_AUTO_RETRIES = 3
        // StreamProtectionStatus the streaming server answers with when it has REFUSED this session's
        // PO token for this content (1 = ok, 2 = grace, 3 = rejected — see
        // org.schabi.newpipe.extractor.services.youtube.sabr.SabrStreamProtectionStatus). The single
        // failure cause no rebuild can fix, and therefore the only one worth asking the viewer about
        // instead of silently changing transport under them.
        const val PROTECTION_STATUS_REJECTED = 3
        // Published as `_sabr`'s value when DASH is the viewer's deliberate choice rather than a
        // fallback. `_sabr` is a transport-routing signal here, not user-facing copy (PlayerScreen
        // logs it and falls through to the DASH source — it never renders it), so this string only
        // ever appears in logcat. See [loadLegacyPrimary].
        const val DASH_BY_CHOICE = "DASH selected in Settings"
        // getVideoInfo retries (see fetch()) — covers the network still coming back up right after the
        // phone wakes from a long locked-screen sleep, not a genuinely dead client/video.
        const val FETCH_INFO_RETRIES = 2
        const val PREFS = "player_prefs"
        const val PREF_HEIGHT = "sabr_preferred_height"
        const val PREF_SPONSOR = "sponsorblock_enabled"
        const val PREF_AUDIO_LANG = "audio_language"
        const val WATCHED_THRESHOLD = 0.95f
        // Auto (no explicit quality pick) caps here; manual selection may still go higher.
        const val DEFAULT_AUTO_HEIGHT = 1080
    }
}
