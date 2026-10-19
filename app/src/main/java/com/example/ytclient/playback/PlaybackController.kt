@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ytclient.playback

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Rational
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.example.ytclient.MainActivity
import com.example.ytclient.sabr.SabrMediaSource
import com.example.ytclient.sabr.SabrSessionHolder
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.localization.Localization

/**
 * Bridges the Composable-owned [ExoPlayer] to a process-wide [MediaSession] so the system shows
 * lockscreen / notification / PiP controls. The UI keeps driving the ExoPlayer directly; this
 * object only *publishes* the current player to the session (and hosts it via [PlaybackService]).
 *
 * The player is rebuilt on every quality switch / retry / autoplay-next, so [attach] is called per
 * player and simply swaps the session's player ([MediaSession.setPlayer]) — the session + foreground
 * service survive the swap. The ExoPlayer's lifecycle stays owned by the UI; this object never
 * releases it (only the session).
 *
 * Metadata (title / channel / thumbnail) rides on the player's current [androidx.media3.common.MediaItem]
 * (set in `buildSabrSource`), which the session's notification reads automatically.
 */
object PlaybackController {

    private var appContext: Context? = null
    private var session: MediaSession? = null

    // The raw ExoPlayer currently published (identity check so a stale detach can't kill a session
    // that a newer player already took over).
    private var currentPlayer: ExoPlayer? = null
    private var playingListener: Player.Listener? = null
    // A MediaController bound to PlaybackService. We never use it to control playback (the UI drives
    // the ExoPlayer directly) — its CONNECTION is what makes media3's MediaSessionService wire up and
    // post the MediaStyle notification + promote itself to the foreground (and thus show lockscreen
    // controls). Without a connected controller the service never manages the notification.
    private var controllerFuture: ListenableFuture<MediaController>? = null

    // The mediaKey (see PlayerScreen's PlayerBox) that [currentPlayer] currently represents. Updated by
    // both [attach] (normal UI-driven builds) and [playDirectly] (a direct, UI-bypassing content swap).
    // Lets a LATER PlayerBox composition recognise "the live player already IS this video" via
    // [adoptIfLive] instead of tearing it down and building a redundant duplicate.
    @Volatile private var liveMediaKey: String? = null

    // Set by [playDirectly] each time it hijacks the player, consumed (and cleared) by the very next
    // STATE_ENDED the published player reports. Exists so a SECOND (or later) video ending while the
    // screen is STILL locked — after the first auto-advance already hijacked the player once — keeps
    // the chain going: the video that just ended has no live Compose composition of its own left to
    // notice (the one that would have is the ORIGINAL, now-superseded composition, which correctly
    // recognises via `isHijackedElsewhere` that it no longer owns anything and stays quiet). This is
    // that gap's fallback: [attach]'s own listener stays attached across a [playDirectly] hand-off
    // (same ExoPlayer instance throughout), so it's always around to catch the next ending regardless.
    @Volatile private var autoAdvanceOnEnded: (() -> Unit)? = null

    // ── Gapless auto-advance ────────────────────────────────────────────────
    // A prefetched NEXT video appended as the live player's SECOND playlist item, so ExoPlayer
    // transitions into it natively (READY → READY via onMediaItemTransition) and the player never
    // reaches STATE_ENDED between queue items. That distinction is what keeps background playback
    // alive across an autoplay advance: media3's MediaSessionService demotes its foreground service
    // once the player stops "playing" (ENDED counts), and on Android 12+ a backgrounded app cannot
    // re-promote a demoted service — so the old end→re-prepare hand-off (playDirectly at
    // STATE_ENDED) could come out of the transition with the media notification gone (no lockscreen
    // controls) and the process no longer foreground, which gets it cached/frozen shortly after
    // (audio dying ~30s into the next video). With the next item appended ahead of time there is no
    // gap for the service to be demoted in. [playDirectly] remains the fallback for advances that
    // were never prefetched/appended.
    private class AppendedNext(
        val exo: ExoPlayer,
        val item: MediaItem,
        val mediaKey: String,
        val onTransition: () -> Unit,
        val onEnded: () -> Unit,
    )
    @Volatile private var appendedNext: AppendedNext? = null

    // Backs the warm-seek wait in [playDirectly] (below) — a plain, cancellation-tolerant scope since
    // this object outlives any individual player/composition. SupervisorJob so one failed wait can't
    // take down anything else scheduled on it (there's only ever this one use today, but keeps the
    // object safe to extend).
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val _isPlaying = MutableStateFlow(false)
    /** Whether the published player is playing — drives the PiP play/pause icon. */
    val isPlaying: StateFlow<Boolean> = _isPlaying

    // 16:9 default matches typical landscape uploads; updated live via onVideoSizeChanged below so
    // vertical/non-16:9 content (Shorts, unusual uploads) gets a correctly-shaped PiP window instead
    // of a hardcoded aspect ratio letterboxing/cropping the actual video.
    private val _aspectRatio = MutableStateFlow(Rational(16, 9))
    /** The published player's current video aspect ratio — drives the PiP window shape. */
    val aspectRatio: StateFlow<Rational> = _aspectRatio

    /** Returned by [PlaybackService.onGetSession]; created here before the service starts. */
    fun currentSession(): MediaSession? = session

    /**
     * The [MediaItem] for [uri], carrying the title / channel / artwork the media session renders
     * on the lockscreen, in the notification shade and in PiP. **Every** source handed to a
     * session-managed player must be built from one of these — the session has no other place to
     * get that information from; it reads the player's current MediaItem.
     *
     * This exists as one function because five call sites used to build the item by hand, and only
     * some of them remembered the metadata: the SABR source and the two locked-screen advance paths
     * here did, but the DASH and progressive fallbacks in PlayerScreen used a bare
     * `MediaItem.fromUri(uri)`. Those two paths had already been given a MediaSession
     * (`manageMediaSession = true`) precisely so background playback would work on them — so the
     * notification got posted, with nothing in it, and the lockscreen showed only the app name.
     *
     * [videoId] is used for the artwork URL rather than taken from [uri], because only the SABR
     * scheme carries the id; a DASH source's uri is an inline `data:` manifest and a progressive
     * one is a signed googlevideo URL. hqdefault, not the mqdefault the feed cards use: the
     * lockscreen renders artwork far larger than a 150dp card.
     */
    fun mediaItemFor(uri: String, videoId: String, title: String?, channel: String?): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(channel)
            .setArtworkUri(Uri.parse("https://i.ytimg.com/vi/$videoId/hqdefault.jpg"))
            .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }

    /**
     * Publish [exo] to the media session, creating the session + starting the foreground service on
     * first attach, or swapping the player on a rebuild. [onNext] is invoked by the Next control and
     * [hasNext] gates whether the Next button is shown. [mediaKey] identifies which video/session [exo]
     * now represents (see PlayerScreen's PlayerBox) — recorded so a later composition can recognise via
     * [adoptIfLive] that this exact player already IS the video it's about to build.
     */
    fun attach(
        context: Context,
        exo: ExoPlayer,
        mediaKey: String,
        onNext: () -> Unit,
        hasNext: () -> Boolean,
    ) {
        val app = context.applicationContext
        appContext = app

        // Move the play-state listener from the previous player to this one.
        currentPlayer?.let { prev -> playingListener?.let(prev::removeListener) }
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                _aspectRatio.value = aspectRatioFor(videoSize)
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    // Only meaningfully reachable here for a SECOND (or later) chained end while the
                    // screen is still locked — see autoAdvanceOnEnded's doc. A normal, Compose-live end
                    // is handled by that video's own PlayerBox listener; this one only actually does
                    // anything once armed by playDirectly or an appended-item transition.
                    autoAdvanceOnEnded?.let { cb -> autoAdvanceOnEnded = null; cb() }
                }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // Playback crossed into an appended next item (a natural end-of-item auto-transition,
                // or an explicit advanceIntoAppended) — complete the same bookkeeping playDirectly
                // does for a hand-off, then let the ViewModel catch its own state up via onTransition.
                // Matching by the item's uri: transitions for anything else (setMediaSource replacing
                // the playlist reports the NEW first item, etc.) must not consume the pending append.
                val an = appendedNext ?: return
                if (an.exo !== exo) return
                val uri = mediaItem?.localConfiguration?.uri ?: return
                if (uri != an.item.localConfiguration?.uri) return
                appendedNext = null
                liveMediaKey = an.mediaKey
                // Same chaining contract as playDirectly: if THIS video also ends with no successor
                // appended and no live composition to notice, keep the queue advancing.
                autoAdvanceOnEnded = an.onEnded
                // Back to a single-item timeline: the finished previous item has nothing left to do,
                // and the mediaItemCount == 1 invariant is what appendNext keys on next time around.
                if (exo.mediaItemCount > 1) exo.removeMediaItem(0)
                an.onTransition()
            }
        }
        playingListener = listener
        exo.addListener(listener)
        currentPlayer = exo
        liveMediaKey = mediaKey
        // A genuinely new player is being published — any pending chained hand-off or appended next
        // item from a PREVIOUS player is moot now that Compose is back in the driver's seat for this
        // one. (The appended item's holder stays owned by PlayerViewModel.queuedIntoPlayer, which
        // discards/releases it on its own next advance — see discardQueuedNext there.)
        autoAdvanceOnEnded = null
        appendedNext = null
        _isPlaying.value = exo.isPlaying
        _aspectRatio.value = aspectRatioFor(exo.videoSize)

        val forwarding = nextCapablePlayer(exo, onNext, hasNext)
        val existing = session
        if (existing == null) {
            val openApp = PendingIntent.getActivity(
                app,
                0,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            session = MediaSession.Builder(app, forwarding)
                .setSessionActivity(openApp)
                .build()
            // Connect a controller to our own service. This triggers MediaSessionService.onGetSession
            // and hands media3 control of the foreground notification — media3 calls startForeground
            // itself when playback warrants it (no manual startForegroundService → no
            // ForegroundServiceDidNotStartInTimeException), and the lockscreen controls appear.
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            controllerFuture = MediaController.Builder(app, token).buildAsync()
        } else {
            existing.player = forwarding
        }
    }

    // Android's PictureInPictureParams.Builder.setAspectRatio throws if the ratio falls outside its
    // documented bounds of [1/2.39, 2.39] — an extreme-aspect video (or a 0x0 "not yet known" size
    // right after a rebuild) would otherwise crash setPictureInPictureParams instead of just falling
    // back to 16:9. Clamped a hair inside those bounds (2.3, not 2.39) so float rounding in Rational's
    // own division can't tip a boundary value just over the edge and get rejected anyway.
    private val MIN_PIP_ASPECT = 1f / 2.3f
    private val MAX_PIP_ASPECT = 2.3f

    /** [videoSize] → a PiP-safe [Rational], accounting for rotation and clamped to what Android allows. */
    private fun aspectRatioFor(videoSize: VideoSize): Rational {
        if (videoSize.width <= 0 || videoSize.height <= 0) return Rational(16, 9)
        // A 90/270-rotated stream reports its UNROTATED width/height; swap them so the ratio matches
        // what's actually rendered on screen (PlayerView applies unappliedRotationDegrees itself).
        val rotated = videoSize.unappliedRotationDegrees == 90 || videoSize.unappliedRotationDegrees == 270
        val w = if (rotated) videoSize.height else videoSize.width
        val h = if (rotated) videoSize.width else videoSize.height
        val ratio = w.toFloat() / h.toFloat()
        return when {
            ratio < MIN_PIP_ASPECT -> Rational(10, 23) // ≈ 1/2.3, a safely-inside-bounds vertical ratio
            ratio > MAX_PIP_ASPECT -> Rational(23, 10)
            else -> Rational(w, h)
        }
    }

    /** Tear down the session for whatever player is currently published (player UI left the screen). */
    fun detachCurrent() {
        currentPlayer?.let(::detach)
    }

    /** Tear down when [exo] (the current player) goes away. No-op for an already-swapped-out player. */
    fun detach(exo: ExoPlayer) {
        if (currentPlayer !== exo) return
        playingListener?.let(exo::removeListener)
        playingListener = null
        currentPlayer = null
        liveMediaKey = null
        autoAdvanceOnEnded = null
        appendedNext = null
        _isPlaying.value = false
        // Releasing the controller disconnects from the service; media3 then tears down the
        // notification and stops the foreground service on its own once the session is released.
        controllerFuture?.let(MediaController::releaseFuture)
        controllerFuture = null
        session?.release()
        session = null
        appContext?.let { it.stopService(Intent(it, PlaybackService::class.java)) }
    }

    /** The currently-published player, if any — a plain, Compose-independent read (safe to call from a
     *  ViewModel coroutine, unlike Compose state, which needs a recomposition to reflect a change). */
    fun currentPlayerOrNull(): ExoPlayer? = currentPlayer

    /**
     * If the currently-published player is ALREADY representing [mediaKey]'s content — via a normal
     * [attach] or a direct [playDirectly] hand-off — return it so a PlayerBox composition can adopt and
     * reuse it instead of tearing it down and building a redundant duplicate. Returns null otherwise
     * (normal construction should proceed as usual).
     */
    fun adoptIfLive(mediaKey: String): ExoPlayer? = currentPlayer?.takeIf { liveMediaKey == mediaKey }

    /**
     * Directly swap the published player onto [holder]'s content, bypassing the UI/Compose entirely.
     *
     * Why this exists: normally, advancing to the next queued video happens by the ViewModel updating
     * its state and Compose recomposing to rebuild the player for it. Compose recomposition is driven
     * by the display's VSYNC signal (via Choreographer) — and Android disables VSYNC generation while
     * the screen is off. So with the phone locked (audio-only background playback), a video ending
     * would update all the right ViewModel state, but the UI would have no way to act on it — and
     * autoplay-next would just silently not happen — until the user unlocks the screen.
     *
     * This sidesteps that entirely: it's a plain method call on the already-published ExoPlayer,
     * unaffected by VSYNC/Compose. [PlayerViewModel] calls this (with a next video prefetched ahead of
     * time — see `prefetchUpNext`) the moment the current video ends, so audio-only playback keeps
     * advancing through the queue regardless of screen state.
     *
     * Returns the mediaKey [holder] now represents (same scheme as PlayerScreen's PlayerBox — the
     * caller publishes this as the new `_sabr` state so that once the UI DOES next recompose, its
     * PlayerBox computes the SAME mediaKey and adopts this same player via [adoptIfLive] instead of
     * building a redundant new one), or null if there's no live player to hijack (nothing was actually
     * playing / already torn down — the caller should fall back to the normal rebuild path).
     *
     * [onEnded] arms [autoAdvanceOnEnded] so that if THIS video also ends while still nobody's
     * recomposed (screen still locked), the chain keeps going instead of only ever surviving one
     * hand-off — the caller should pass the same "advance the queue" logic it used to get here.
     *
     * [resumePositionMs] is where playback should continue from — 0 for a genuinely new video
     * (autoplay-next), or the mid-video play head when [holder] is a FRESH session recovering the
     * SAME video after a playback error (e.g. PlayerViewModel.retryAfterPlaybackError, used when a
     * SponsorBlock skip or a stalled cold session needs a clean session to recover — this hijack is
     * what lets that recovery actually complete while the screen is locked, same as autoplay-next).
     * [holder] is always a COLD session here, so — same reasoning as PlayerBox's own warm-seek dance —
     * we can't just prime it to start at [resumePositionMs]; a cold session mis-maps a non-zero start
     * time to the wrong segment. Instead it always cold-starts at 0, and once it's actually warm (first
     * READY) a plain [ExoPlayer.seekTo] resolves correctly. That wait happens on [scope], off the
     * caller's thread, and re-checks identity before acting so a second hand-off (or a teardown) that
     * lands mid-wait can't clobber it.
     */
    fun playDirectly(
        holder: SabrSessionHolder,
        title: String?,
        channel: String?,
        resumePositionMs: Long = 0L,
        onEnded: () -> Unit,
    ): String? {
        val exo = currentPlayer ?: return null
        val mediaKey = "sabr:${System.identityHashCode(holder)}:cc:off"
        val item = mediaItemFor("sabr://" + holder.videoId, holder.videoId, title, channel)
        holder.setStartPositionMs(0L)
        liveMediaKey = mediaKey
        autoAdvanceOnEnded = onEnded
        // setMediaSource below replaces the WHOLE playlist, so any appended-but-not-reached next
        // item is gone with it — drop the pending transition bookkeeping too.
        appendedNext = null
        // ExoPlayer may only be touched from the main thread, but callers aren't always on it —
        // fetchSabr's IO coroutine hands a fresh session over here for locked-screen error recovery.
        // (The old code called setMediaSource straight from that IO thread; media3 rejected it with
        // "Player is accessed on the wrong thread", the exception got swallowed by fetchSabr's own
        // catch, and the recovery silently degraded to the legacy-format fallback.) Main.immediate
        // runs this block synchronously when already on the main thread — preserving the
        // "re-prepare within the STATE_ENDED callback itself" timing that keeps media3 from
        // demoting the foreground service in the gap — and hops over otherwise.
        scope.launch {
            // Re-check identity after a possible hop: a newer hand-off/teardown may have won.
            if (currentPlayer !== exo || liveMediaKey != mediaKey) return@launch
            exo.setMediaSource(SabrMediaSource(item, holder, Localization.DEFAULT))
            exo.prepare()
            exo.playWhenReady = true
            if (resumePositionMs > 0L) {
                var waited = 0
                while (
                    currentPlayer === exo && liveMediaKey == mediaKey &&
                    exo.playbackState != Player.STATE_READY && waited < 80
                ) {
                    delay(250)
                    waited++
                }
                // Re-check identity: a second hand-off (chained autoplay-next) or a teardown could have
                // moved on while we were waiting, in which case this seek would land on the wrong video.
                if (currentPlayer === exo && liveMediaKey == mediaKey && exo.playbackState == Player.STATE_READY) {
                    exo.seekTo(resumePositionMs)
                }
            }
        }
        return mediaKey
    }

    /**
     * Append [holder] as the live player's NEXT playlist item so ExoPlayer auto-advances into it
     * natively when the current video ends — see [AppendedNext]'s doc for why that (and not a
     * hand-off at STATE_ENDED) is what keeps background playback + the media notification alive
     * across an autoplay advance. MAIN THREAD ONLY (it's driven by the player's own callbacks and
     * the near-end poll, both already on main).
     *
     * Returns false (and takes no ownership of [holder]) when there's no live player, something is
     * already appended, or the playlist isn't in its expected single-item shape. On success the
     * caller must keep [holder] alive until [onTransition] consumes it or the caller discards the
     * append via [clearAppendedNext].
     *
     * [onTransition] runs once, on the main thread, when playback actually crosses into the
     * appended item. [onEnded] is then armed as [autoAdvanceOnEnded] for the video AFTER it,
     * mirroring [playDirectly]'s chaining contract.
     */
    fun appendNext(
        holder: SabrSessionHolder,
        title: String?,
        channel: String?,
        onTransition: () -> Unit,
        onEnded: () -> Unit,
    ): Boolean {
        // Full precondition check BEFORE touching the holder, as before this delegated to
        // appendNextSource: a declined append must leave the caller's holder exactly as it was.
        val exo = currentPlayer ?: return false
        if (appendedNext != null || exo.mediaItemCount != 1) return false
        val item = mediaItemFor("sabr://" + holder.videoId, holder.videoId, title, channel)
        holder.setStartPositionMs(0L)
        return appendNextSource(
            SabrMediaSource(item, holder, Localization.DEFAULT),
            "sabr:${System.identityHashCode(holder)}:cc:off",
            onTransition,
            onEnded,
        )
    }

    /**
     * [appendNext] for any ready-built [source], recorded under [mediaKey] — which must be the exact
     * key the UI's PlayerBox will compute for that video, because that is how the next composition
     * recognises (via [adoptIfLive]) that the live player already IS its video and adopts it instead
     * of building a new one. Same contract otherwise: MAIN THREAD ONLY, false when there's no live
     * player / something is already appended / the playlist isn't a single item.
     *
     * Exists for the DASH path (PlayerViewModel's DASH prefetch builds the source with
     * PlayerScreen's `buildDashSource` and keys it with `dashMediaKey`). DASH used to have no
     * equivalent of [appendNext] at all, so every DASH autoplay advance released this object's
     * media session between players — and on Android 12+ a backgrounded app cannot bring the
     * replacement session's service back into the foreground. Captured 2026-10-17 with the screen
     * locked: session released at the end of one video, `ForegroundServiceStartNotAllowedException`
     * from the next player's session 6 s later, then `UnknownHostException` as the now-background
     * process lost its network, and finally the stall watchdog's "Couldn't load".
     */
    fun appendNextSource(
        source: MediaSource,
        mediaKey: String,
        onTransition: () -> Unit,
        onEnded: () -> Unit,
    ): Boolean {
        val exo = currentPlayer ?: return false
        if (appendedNext != null || exo.mediaItemCount != 1) return false
        exo.addMediaSource(source)
        // The transition listener matches on this item's uri, so it must be the very MediaItem the
        // source reports — taken from the source rather than rebuilt, so the two can't differ.
        appendedNext = AppendedNext(exo, source.mediaItem, mediaKey, onTransition, onEnded)
        return true
    }

    /**
     * Drop a previously appended-but-not-yet-reached next item (the prediction went stale, the user
     * navigated elsewhere, or a sleep-at-end was armed). Safe from any thread; the caller keeps —
     * and is responsible for releasing — the holder it retained ownership of at [appendNext] time.
     */
    fun clearAppendedNext() {
        val an = appendedNext ?: return
        appendedNext = null
        scope.launch {
            val exo = currentPlayer ?: return@launch
            if (exo !== an.exo) return@launch
            while (exo.mediaItemCount > 1) exo.removeMediaItem(exo.mediaItemCount - 1)
        }
    }

    /**
     * Explicitly advance into the already-appended next item (e.g. the lockscreen/PiP Next control
     * targeting exactly the video that's queued up). MAIN THREAD ONLY. Returns false when nothing
     * is appended on the live player — the caller should fall back to its normal advance path. On
     * success the transition listener completes the hand-off bookkeeping, same as a natural end.
     */
    fun advanceIntoAppended(): Boolean {
        val exo = currentPlayer ?: return false
        val an = appendedNext ?: return false
        if (an.exo !== exo || exo.mediaItemCount < 2) return false
        exo.seekToNextMediaItem()
        return true
    }

    /** PiP play/pause toggle. */
    fun togglePlayPause() {
        val p = currentPlayer ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    /** PiP Next — routed through the forwarding player so the queue's onNext runs. */
    fun next() {
        session?.player?.takeIf { it.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT) }?.seekToNext()
    }

    /**
     * Wrap [exo] so the session advertises a Next command (mapped to [onNext] = the ViewModel queue)
     * and never a Previous (the related-video chain has no meaningful back entry). The player's own
     * timeline is a single item, so without this the session would show no transport-skip controls.
     */
    private fun nextCapablePlayer(
        exo: ExoPlayer,
        onNext: () -> Unit,
        hasNext: () -> Boolean,
    ): ForwardingPlayer = object : ForwardingPlayer(exo) {
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS)
                .remove(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()

        override fun isCommandAvailable(command: Int): Boolean = when (command) {
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> false
            else -> super.isCommandAvailable(command)
        }

        override fun hasNextMediaItem(): Boolean = hasNext()

        override fun seekToNext() {
            onNext()
        }

        override fun seekToNextMediaItem() {
            onNext()
        }
    }
}
