@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ytclient.ui.player

import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.util.Log
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import com.example.ytclient.core.coroutines.runCatchingCancellable
import com.example.ytclient.core.models.UiState
import com.example.ytclient.core.settings.PlaybackMode
import com.example.ytclient.core.sponsorblock.SponsorSegment
import com.example.ytclient.sabr.AudioTrackInfo
import com.example.ytclient.sabr.CaptionTrackInfo
import com.example.ytclient.sabr.SabrMediaSource
import com.example.ytclient.sabr.SabrQuality
import com.example.ytclient.sabr.SabrSessionHolder
import com.example.ytclient.playback.PlaybackController
import com.example.ytclient.player.ChunkedDataSource
import org.schabi.newpipe.extractor.localization.Localization
import com.example.ytclient.player.FormatSelector
import com.example.ytclient.player.PlaybackChoice
import com.example.ytclient.ui.components.VideoRow
import com.example.ytclient.youtube.models.SearchVideo
import com.example.ytclient.youtube.models.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// internal (was private): PlayerViewModel builds the next video's DASH source ahead of time and
// must fall back to exactly the user agent this screen would have used — see buildDashSource.
internal const val PLAYER_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

/**
 * The PlayerBox mediaKey for a DASH video, from its manifest.
 *
 * Shared because the locked-screen DASH hand-off depends on two parties computing the SAME key
 * independently: PlayerViewModel queues the next video's source onto the live player under this key
 * (PlaybackController.appendNextSource records it as the live player's identity), and when the UI
 * next composes for that video, PlayerBox computes its key from `info.dashManifest` — which
 * PlayerViewModel.mergeLegacy copies verbatim from the same LegacyPlaybackInfo — and adopts the live
 * player only if the two match ([PlaybackController.adoptIfLive]). If they ever disagree, adoption
 * silently fails and the screen rebuilds the player, releasing the media session — which is the
 * exact failure the hand-off exists to prevent. One function means they cannot disagree.
 */
internal fun dashMediaKey(manifest: String): String = "dash:${manifest.hashCode()}"

/** A forward seek landing more than this far past the last actually-played
 *  position is treated as a "jump into unbuffered territory" — if it fails to
 *  load (server rate-limiting random access), we snap back here instead of
 *  hanging on the unreachable target. */
private const val SEEK_FALLBACK_THRESHOLD_MS = 5_000L

/** How close to the end of the current video before warming the next queued/related video's SABR
 * info (see onNearEnd). 20s comfortably covers a typical outro without firing so early that a viewer
 * who bails out mid-video wastes the prefetch most of the time. */
private const val NEAR_END_PREFETCH_MS = 20_000L

/** If the player sits BUFFERING this long without progressing, re-fetch a fresh session. Cold-start
 *  SABR occasionally hangs on its first request; a clean session recovers without user action. */
private const val STALL_WATCHDOG_MS = 25_000L

/** A SponsorBlock segment whose reported end lands within this much of the video's actual duration
 *  is treated as "runs to the end" rather than skipped-past — see the SponsorBlock poll effect for
 *  why seeking to the segment's literal endMs in that case is the bug, not the fix. Generous enough
 *  to absorb the community-submitted segment's own timing slop plus the fact that youtubei.js's
 *  reported duration and the actual last playable position are rarely bit-identical. */
private const val END_OF_VIDEO_TOLERANCE_MS = 1_500L

/** Hard circuit breaker on the SponsorBlock retry-escalation loop: if a segment still hasn't been
 *  cleared after this many consecutive "landed short, try again further forward" attempts, stop
 *  trying to auto-skip THIS segment run and let it play instead of escalating forever.
 *
 *  Historical note: the loop this guards against had a real, since-fixed root cause — the SABR
 *  chunk source used to resolve EVERY seek to the START of the segment containing the target (up
 *  to a whole ~5s chunk backwards), so a skip whose target fell mid-chunk landed back inside the
 *  sponsor segment and re-triggered itself forever. SabrChunkSource.getAdjustedSeekPositionUs now
 *  resolves seeks DASH-style (exact target under default SeekParameters), so skips land where they
 *  aim and neither the escalation nor this cap should trigger in normal operation anymore. Both
 *  stay as a pure backstop (e.g. a cold session whose segment index is still an estimate can in
 *  principle land short), but the budget is smaller now that undershoot is the exception, not the
 *  rule: a few seconds of visible ad on the rare case this trips beats a playback session that
 *  never recovers (no PlaybackException is thrown for a short landing — the seek "succeeds" — so
 *  the onPlayerError/autoRetries safety net never sees it). */
private const val MAX_SPONSOR_SKIP_ATTEMPTS = 4

@Composable
fun PlayerScreen(
    initialVideos: List<SearchVideo>,
    initialIndex: Int,
    autoplayRelated: Boolean,
    onBack: () -> Unit,
    onOpenChannel: (String) -> Unit,
    inPip: Boolean = false,
    // Whether the in-app mini-player is showing (over the tabs — NOT the system PiP window, that's
    // [inPip]): AppRoot's collapse fraction past its halfway point. Passed as this Boolean, not the
    // fraction itself, so a drag or settle animation recomposes this screen only when the threshold
    // is crossed rather than on every frame (see MainActivity's PlayerOverlay).
    mini: Boolean = false,
    // Live drag delta (px, positive = finger moved down), reported while dragging the expanded video
    // down (to start collapsing) or the mini card up (to expand back) — same callback either
    // direction; AppRoot turns it into its collapse fraction via Animatable.snapTo.
    onCollapseDrag: (Float) -> Unit = {},
    // Drag released, with the final vertical fling velocity (px/s, positive = downward). AppRoot uses
    // this plus the current fraction to decide whether to settle at 0 (expanded) or 1 (mini).
    onCollapseDragEnd: (velocity: Float) -> Unit = {},
    // Drag deltas (px) while moving the mini card around the screen. Two-axis, and unrelated to
    // [onCollapseDrag]: that one changes how collapsed the player is, this one changes where the
    // collapsed card sits. AppRoot owns the position and clamps it to the viewport.
    onMiniDrag: (dxPx: Float, dyPx: Float) -> Unit = { _, _ -> },
    // Tap the mini card → snap straight to expanded (a discrete alternative to dragging it open).
    onExpand: () -> Unit = {},
    // Back pressed from the expanded page → snap straight to mini (a discrete alternative to dragging
    // it collapsed) — see BackHandler below.
    onMinimize: () -> Unit = {},
    viewModel: PlayerViewModel = viewModel(),
) {
    LaunchedEffect(initialVideos, initialIndex) {
        viewModel.startQueue(initialVideos, initialIndex, autoplayRelated)
    }

    val info by viewModel.info.collectAsState()
    val related by viewModel.related.collectAsState()
    val loadingMoreRelated by viewModel.loadingMoreRelated.collectAsState()
    val sabr by viewModel.sabr.collectAsState()
    val preferredHeight by viewModel.preferredHeight.collectAsState()
    val subscribedIds by viewModel.subscribedChannelIds.collectAsState()
    val watchedFractions by viewModel.watchedFractions.collectAsState()
    val currentVideo by viewModel.currentVideo.collectAsState()
    val upNext by viewModel.upNext.collectAsState()
    val channelInfo by viewModel.channelInfo.collectAsState()
    val queue by viewModel.queue.collectAsState()
    val sponsorSegments by viewModel.sponsorSegments.collectAsState()
    val sponsorEnabled by viewModel.sponsorEnabled.collectAsState()
    val audioTracks by viewModel.audioTracks.collectAsState()
    val captionTracks by viewModel.captionTracks.collectAsState()
    val commentsState by viewModel.comments.collectAsState()
    // Non-null while the status=3 "switch to DASH?" prompt should be showing (see the dialog below).
    val dashPromptVideoId by viewModel.dashPrompt.collectAsState()
    // The viewer's transport choice. Only used to tell a DELIBERATE DASH open apart from a SABR
    // failure that fell through into the same rendering branch — see VideoPlayer's dashByChoice.
    val playbackMode by viewModel.playbackMode.collectAsState()

    // The id of the video actually playing (queue current); falls back to the requested entry for the
    // very first frame before the queue is set.
    val videoId = currentVideo?.id ?: initialVideos.getOrNull(initialIndex)?.id ?: ""

    // Whether the comments bottom sheet is open. Reset per video.
    var showComments by remember(videoId) { mutableStateOf(false) }

    // Selected closed-caption track (null = off). Reset per video. Drives the menu's checkmark
    // instantly; the actual subtitle (below) is resolved asynchronously.
    var selectedCaption by remember(videoId) { mutableStateOf<CaptionTrackInfo?>(null) }

    // The caption actually fed to the player: same track but with its WebVTT text resolved (via
    // get_transcript) into a data: URI. Kept separate from selectedCaption so the menu reacts at once
    // while the text loads, and so the player only rebuilds with a subtitle once the text is ready
    // (avoids a load race / the empty-body timedtext problem). null = no captions in the player.
    var playbackCaption by remember(videoId) { mutableStateOf<CaptionTrackInfo?>(null) }
    LaunchedEffect(videoId, selectedCaption) {
        val sel = selectedCaption
        playbackCaption = if (sel == null || videoId.isEmpty()) {
            null
        } else {
            val vtt = viewModel.fetchCaptionVtt(videoId, sel.name)
            if (vtt.isBlank()) {
                null
            } else {
                val data = "data:text/vtt;base64," +
                    android.util.Base64.encodeToString(
                        recenterVttCues(vtt).toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP,
                    )
                sel.copy(url = data)
            }
        }
    }

    // Resume position, hoisted to the SCREEN and keyed on the CURRENT video id, so it is independent
    // of player rebuilds (re-fetch after a 403, a quality change, autoplay-next, …). Seeded from local
    // watch history so "continue watching" resumes where the user left off.
    val resumePositionMs = remember(videoId) { mutableStateOf(viewModel.resumePositionFor(videoId)) }
    // Last position that ACTUALLY played (advances only during real playback, not
    // on seeks). Used as a safe fallback when a forward seek can't be loaded.
    val lastGoodPositionMs = remember(videoId) { mutableStateOf(resumePositionMs.value) }

    // Persist the play head to history for THIS video every few seconds, and once more on exit.
    // videoId is captured per-effect so the outgoing video's final write can't land on the next one.
    LaunchedEffect(videoId) {
        while (true) {
            delay(5000)
            viewModel.updateProgress(videoId, resumePositionMs.value)
        }
    }
    DisposableEffect(videoId) {
        onDispose { viewModel.updateProgress(videoId, resumePositionMs.value) }
    }

    val activity = LocalContext.current as? Activity
    val isLandscape =
        LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isLandscapeState = rememberUpdatedState(isLandscape)

    // Fullscreen model:
    //   null  → auto/sensor: physical rotation drives fullscreen (landscape = FS)
    //   true  → locked landscape fullscreen (entered via the button)
    //   false → locked portrait (exiting); auto-returns to sensor shortly after
    val manualOrientation = remember { mutableStateOf<Boolean?>(null) }
    val fullscreen = manualOrientation.value ?: isLandscape

    // [mini] is the threshold on AppRoot's continuous collapse fraction: past the halfway point of the
    // drag, this composable swaps from the full expanded page to the bare mini layout — by then the
    // container (MainActivity's PlayerOverlay) is already most of the way to the small corner card, so
    // the swap reads as part of the same motion rather than a separate jump.

    // While the player is open, allow the sensor; restore portrait on exit.
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // Apply the manual orientation lock (and hand control back to the sensor
    // after an explicit exit so the next physical rotation works again).
    LaunchedEffect(manualOrientation.value) {
        activity?.requestedOrientation = when (manualOrientation.value) {
            true -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            false -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            null -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        if (manualOrientation.value == false) {
            delay(700)
            manualOrientation.value = null
        }
    }

    ImmersiveMode(enabled = fullscreen)

    BackHandler {
        when {
            fullscreen -> manualOrientation.value = false
            showComments -> showComments = false
            // Expanded → mini first, mini → actually dismiss. Mirrors the mini-player's own job: a
            // progressive "step back" through display states rather than an all-or-nothing close. This
            // SUPERSEDES back's earlier "enter system PiP instead of closing" behavior — now that
            // there's an in-app mini-player, back's job is to stay in the app (collapse), not kick out
            // to the system PiP window. System PiP is still entered automatically on a genuine app
            // leave (Home/task-switch) via MainActivity.enterPip(), called from onUserLeaveHint —
            // that's unaffected by any of this.
            !mini -> onMinimize()
            else -> onBack()
        }
    }

    // Stable identity so the movable content below can capture it once.
    val onToggleFullscreen = remember {
        {
            val isFs = manualOrientation.value ?: isLandscapeState.value
            manualOrientation.value = !isFs
        }
    }

    // Sleep timer: a wall-clock deadline (ms; 0 = off) after which the player pauses, OR "stop at the
    // end of the current video" (sleepAtEnd → don't autoplay the next).
    val sleepDeadline = remember { mutableStateOf(0L) }
    var sleepAtEnd by remember { mutableStateOf(false) }

    // Auto-resume on PiP entry: pressing Home mid-watch should keep playing in the PiP window. The PiP
    // window now also has play/pause + Next controls (via the MediaSession), so the user can pause from
    // there afterwards. Bumping this on PiP entry tells the player to resume — but ONLY if it was
    // actually playing right before backgrounding. Without the isPlaying guard this fired unconditionally
    // on every PiP entry, so a video the user had deliberately paused would un-pause itself the instant
    // the PiP window appeared. Read straight off PlaybackController (not this composition's local
    // isPlaying) since it's the process-wide source of truth and unaffected by whichever
    // fullscreen/mini/PiP composable happens to be live at the moment.
    val forcePlay = remember { mutableStateOf(0) }
    LaunchedEffect(inPip) { if (inPip && PlaybackController.isPlaying.value) forcePlay.value++ }

    // The player as MOVABLE content: a single instance that is *moved* between
    // the fullscreen and inline layouts rather than torn down and rebuilt. This
    // preserves the ExoPlayer and the saved playback position across fullscreen
    // toggles / rotations. Without it, every toggle recreated the player (and
    // reset the remembered position), restarting playback from 0.
    //
    // KEYED ON videoId: the movable content captures resumePositionMs/lastGoodPositionMs by
    // reference *once* when created. Those states are re-keyed per video, so a content that
    // outlived a video change would feed the NEXT video the PREVIOUS video's resume position
    // (cross-video bleed → wrong start time → cold-session re-prime loop). Re-creating it per
    // video re-captures the current states, while fullscreen toggles (same videoId) still reuse
    // the one instance.
    // inPip is a plain parameter of PlayerScreen, not a State — captured directly inside the
    // remember(videoId)-cached movable content below, it would go STALE on any recomposition that
    // changes inPip without also changing videoId (exactly the PiP-entry/exit case), since remember
    // skips re-running its factory and the old closure keeps whatever inPip value it first captured.
    // rememberUpdatedState gives the movable content a State-backed read that's always current,
    // matching the same pattern already used for sponsorSegments/preferredHeight/etc. below.
    // Bare-video-surface trigger: true for the system PiP window OR our own in-app mini-player card.
    // Both want the same downstream effect (native PlayerView controller + settings gear hidden — see
    // VideoPlayer's inPip param doc), just with different chrome drawn OVER the bare surface by the
    // respective caller (system RemoteActions for PiP; our own tap-to-expand/close row for mini, added
    // in the mini branch below).
    val currentInPip by rememberUpdatedState(inPip || mini)

    val playerSlot = remember(videoId) {
        movableContentOf { fs: Boolean ->
            when (val s = info) {
                is UiState.Idle, is UiState.Loading -> LoadingSurface(fullscreen = fs)
                is UiState.Error -> ErrorSurface(s.message, fullscreen = fs) { viewModel.retry() }
                is UiState.Success -> VideoPlayer(
                    info = s.data,
                    sabrState = sabr,
                    fullscreen = fs,
                    resumePositionMs = resumePositionMs,
                    lastGoodPositionMs = lastGoodPositionMs,
                    onToggleFullscreen = onToggleFullscreen,
                    onPlaybackError = { pos -> viewModel.retryAfterPlaybackError(pos) },
                    onEnded = { if (sleepAtEnd) sleepAtEnd = false else viewModel.playNext() },
                    // Lockscreen / PiP "Next" → advance the queue; read upNext live (not a snapshot).
                    onNext = { viewModel.playNext() },
                    hasNext = { viewModel.upNext.value != null },
                    sleepDeadline = sleepDeadline,
                    forcePlay = forcePlay,
                    // Read the State delegates here (inside the movable content) so updates aren't stale.
                    sponsorSegments = if (sponsorEnabled) sponsorSegments else emptyList(),
                    preferredHeight = preferredHeight,
                    onSelectVideoHeight = { viewModel.setPreferredHeight(it) },
                    audioTracks = audioTracks,
                    onSelectAudioLanguage = { viewModel.setPreferredAudioLanguage(it) },
                    captionTracks = captionTracks,
                    selectedCaption = selectedCaption,
                    playbackCaption = playbackCaption,
                    onSelectCaption = { selectedCaption = it },
                    onBandwidthLow = { viewModel.onBandwidthLow() },
                    onBandwidthGood = { viewModel.onBandwidthGood() },
                    // queueIntoPlayer gated on the sleep timer: "stop at end of this video" needs
                    // the player to actually reach STATE_ENDED, which an appended next playlist
                    // item would gaplessly skip right past.
                    onNearEnd = { viewModel.prefetchUpNext(queueIntoPlayer = !sleepAtEnd) },
                    dashByChoice = playbackMode == PlaybackMode.DASH,
                    inPip = currentInPip,
                )
            }
        }
    }

    // Status=3 prompt (see PlayerViewModel.dashPrompt): the streaming server refused this session's
    // attestation for this specific video, so rather than silently changing transport underneath the
    // viewer — which is what used to happen on the third failed attempt — we ask.
    //
    // Composed HERE, ahead of the fullscreen / mini-player early returns below, deliberately: an
    // AlertDialog is its own window, so where it sits in the layout tree is irrelevant, but where it
    // sits in the CALL order is not. Placed after those returns it would simply never be composed in
    // fullscreen or mini mode — precisely the modes a long watch (and therefore a mid-video
    // rejection) tends to be in. Skipped only in system PiP, where a dialog can neither be seen
    // properly nor receive taps; the prompt lives in the ViewModel, so it appears intact the moment
    // the window is expanded again.
    if (dashPromptVideoId != null && !inPip) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDashPrompt() },
            title = { Text("Streaming protection rejected this video") },
            text = {
                Text(
                    "YouTube's streaming server refused the adaptive (SABR) stream for this video, " +
                        "and retrying won't change that. It can be played over DASH instead: direct " +
                        "streams with no attestation, at a quality chosen up front rather than " +
                        "adapting per segment.",
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.switchToDashForThisVideo() }) {
                    Text("Switch to DASH")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDashPrompt() }) { Text("Cancel") }
            },
        )
    }

    // Declared ABOVE the fullscreen/PiP and mini-player early returns below: state remembered after a
    // `return` is discarded whenever that branch is taken, so every fullscreen or minimise round-trip
    // used to lose the related list's scroll position and bring a dismissed "Up next" bar back.
    var upNextDismissed by remember(videoId) { mutableStateOf(false) }
    val contentListState = rememberLazyListState()

    // Fullscreen OR Picture-in-Picture → show only the video surface.
    if (fullscreen || inPip) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            playerSlot(true)
        }
        return
    }

    // In-app mini-player: AppRoot has shrunk our Box down to a small card and left the tabs
    // visible/interactive underneath. Fill whatever size it gave us with just the bare video (same
    // playerSlot(true) call the fullscreen/PiP branch above uses — it fills its container rather than
    // assuming screen size), plus our own minimal chrome: drag to move it around, tap to re-expand,
    // play/pause, and a close button to dismiss outright (same teardown as the normal "← Back",
    // since there's no partial-close state).
    if (mini) {
        // Read straight off PlaybackController rather than the ViewModel/PlayerBox's own local
        // isPlaying — same source the system PiP play/pause button already uses (see
        // PlaybackController.togglePlayPause), so both controls always agree on transport state.
        val isPlaying by PlaybackController.isPlaying.collectAsState()
        // Same reason as currentInPip above: pointerInput keys on Unit so the gesture detector is
        // never torn down mid-drag, which means the lambda it captured would otherwise be whichever
        // one existed on first composition.
        val currentMiniDrag by rememberUpdatedState(onMiniDrag)
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                // Dragging the mini card MOVES it (AppRoot clamps it inside the viewport) rather
                // than changing the collapse fraction, which is what this gesture used to do. Tap is
                // now the way back to expanded — losing drag-up-to-expand is the deliberate cost of
                // being able to put the card somewhere that isn't over whatever you're reading.
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        currentMiniDrag(dragAmount.x, dragAmount.y)
                    }
                }
                .clickable(onClick = onExpand),
        ) {
            playerSlot(true)
            // 44dp of touch target, inset from the very corner, with a translucent disc behind the
            // glyph so it stays findable over a bright frame. The old button was 28dp flush into the
            // corner — under Material's 48dp minimum, and exactly where a thumb reaching for a
            // corner tends to overshoot — so a near-miss fell through to the card's tap-to-expand
            // and threw the user into fullscreen, which is about the worst possible answer to
            // "close this".
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(44.dp),
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            IconButton(
                onClick = { PlaybackController.togglePlayPause() },
                modifier = Modifier.align(Alignment.Center).size(32.dp),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        return
    }

    // When playing a playlist, the "Up next" section shows the numbered playlist (current highlighted,
    // already-watched entries dropped), instead of related videos.
    val isPlaylist = queue.videos.size > 1 && !queue.autoplayRelated
    val playlistDisplay = if (isPlaylist) {
        queue.videos.mapIndexedNotNull { idx, v ->
            val frac = watchedFractions[v.id] ?: 0f
            if (frac >= 0.95f && idx != queue.index) null else idx to v
        }
    } else {
        emptyList()
    }
    // Best-effort: scroll the list to the currently-playing playlist item.
    LaunchedEffect(queue.index, isPlaylist, playlistDisplay.size) {
        if (isPlaylist) {
            val pos = playlistDisplay.indexOfFirst { it.first == queue.index }
            if (pos >= 0) runCatchingCancellable { contentListState.animateScrollToItem((pos + 2).coerceAtLeast(0)) }
        }
    }
    // Infinite scroll for the related "Up next" list (not playlists). Loads more as the page nears end.
    if (!isPlaylist) {
        val nearEnd by remember {
            derivedStateOf {
                val info = contentListState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                info.totalItemsCount > 0 && last >= info.totalItemsCount - 3
            }
        }
        LaunchedEffect(nearEnd, related.size) { if (nearEnd) viewModel.loadMoreRelated() }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Drag DOWN over the video to start collapsing into the mini-player — same callback pair
            // the mini branch above uses to expand back, just opposite direction. Only attached here
            // (not e.g. over the metadata/up-next list below), matching where a user's finger actually
            // lands to "grab" the video.
            Box(
                Modifier.draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { delta -> onCollapseDrag(delta) },
                    onDragStopped = { velocity -> onCollapseDragEnd(velocity) },
                ),
            ) {
                playerSlot(false)
            }

            // Comments open INLINE below the player (not a modal sheet) so the player stays fully
            // visible + playing above. Otherwise the page's Back/metadata/Up-next list is shown.
            if (showComments) {
                CommentsPanel(
                    state = commentsState,
                    onClose = { showComments = false },
                    onSetSort = { viewModel.setCommentSort(it) },
                    onLoadMore = { viewModel.loadMoreComments() },
                    onExpandReplies = { viewModel.loadReplies(it) },
                    onCollapseReplies = { viewModel.collapseReplies(it) },
                    modifier = Modifier.weight(1f),
                )
            } else {
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), state = contentListState) {
                item {
                    // Dragging the video down (above) is now the way to reach the mini-player — this
                    // button is back to being a plain close/dismiss, same as before that existed.
                    TextButton(onClick = onBack, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text("← Back", color = Color.White)
                    }
                }
                (info as? UiState.Success)?.data?.let { vi ->
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(vi.title, style = MaterialTheme.typography.titleMedium, color = Color.White)
                            Spacer(Modifier.height(8.dp))
                            // Uploader: avatar + name (tap opens the channel) on the left, subscribe
                            // button on the right.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    modifier = (vi.channelId?.let { cid ->
                                        Modifier.clickable { onOpenChannel(cid) }
                                    } ?: Modifier).weight(1f),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    AsyncImage(
                                        model = channelInfo?.avatar,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(36.dp).clip(CircleShape)
                                            .background(Color(0xFF303030)),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(vi.author, style = MaterialTheme.typography.bodyMedium, color = Color.White)
                                        channelInfo?.subscriberText?.takeIf { it.isNotBlank() }?.let {
                                            Text(it, style = MaterialTheme.typography.labelSmall, color = Color.LightGray)
                                        }
                                    }
                                }
                                vi.channelId?.let { cid ->
                                    Spacer(Modifier.width(8.dp))
                                    if (subscribedIds.contains(cid)) {
                                        OutlinedButton(onClick = { viewModel.toggleSubscribe() }) { Text("Subscribed") }
                                    } else {
                                        Button(onClick = { viewModel.toggleSubscribe() }) { Text("Subscribe") }
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SleepTimerMenu(
                                    sleepDeadline = sleepDeadline,
                                    sleepAtEnd = sleepAtEnd,
                                    onSetAtEnd = {
                                        sleepAtEnd = it
                                        // Arming "stop at end of video" must also un-queue an
                                        // already-appended next item — otherwise ExoPlayer would
                                        // gaplessly auto-advance into it and STATE_ENDED (which the
                                        // sleep hook rides on) would never fire.
                                        if (it) viewModel.cancelQueuedNext()
                                    },
                                )
                                TextButton(onClick = { viewModel.toggleSponsor() }) {
                                    Text(
                                        if (sponsorEnabled) "SponsorBlock: on" else "SponsorBlock: off",
                                        color = if (sponsorEnabled) MaterialTheme.colorScheme.primary else Color.LightGray,
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
                if (commentsState.available) {
                    item {
                        CommentsPreviewBar(
                            state = commentsState,
                            onClick = { showComments = true },
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                }
                if (isPlaylist) {
                    item {
                        Text(
                            "Up next • Playlist (${queue.index + 1}/${queue.videos.size})",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    items(playlistDisplay, key = { it.first }) { (idx, v) ->
                        val isCurrent = idx == queue.index
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isCurrent) Color(0x33FFFFFF) else Color.Transparent)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${idx + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (isCurrent) Color.White else Color.LightGray,
                                modifier = Modifier.width(24.dp),
                            )
                            VideoRow(
                                video = v,
                                onClick = { viewModel.playAt(idx) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                } else if (related.isNotEmpty()) {
                    item {
                        Text(
                            "Up next",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    items(related, key = { it.id }) { rv ->
                        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                            VideoRow(
                                video = rv,
                                onClick = { viewModel.startQueue(listOf(rv), 0, autoplayRelated = true) },
                            )
                        }
                    }
                    if (loadingMoreRelated) {
                        item {
                            Box(
                                Modifier.fillMaxWidth().padding(16.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator(
                                    strokeWidth = 2.dp,
                                    color = Color.White,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        // Floating up-next card for standalone autoplay (playlists show the full list instead).
        if (!isPlaylist && !showComments) {
            upNext?.let { nx ->
                if (!upNextDismissed) {
                    UpNextBar(
                        video = nx,
                        onPlay = { viewModel.playNext() },
                        onDismiss = { upNextDismissed = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }
    }
}

/** Sleep-timer dropdown: Off / 15 / 30 / 60 min / End of video. */
@Composable
private fun SleepTimerMenu(
    sleepDeadline: MutableState<Long>,
    sleepAtEnd: Boolean,
    onSetAtEnd: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val remainingMin = if (sleepDeadline.value > now) ((sleepDeadline.value - now) / 60000L + 1).toInt() else 0
    val label = when {
        sleepAtEnd -> "Sleep: end of video"
        remainingMin > 0 -> "Sleep: ${remainingMin}m left"
        else -> "Sleep timer: off"
    }
    Box {
        TextButton(onClick = { open = true }) {
            Text(label, color = Color.LightGray, style = MaterialTheme.typography.labelMedium)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(0 to "Off", 15 to "15 minutes", 30 to "30 minutes", 60 to "60 minutes").forEach { (m, l) ->
                DropdownMenuItem(
                    text = { Text(l) },
                    onClick = {
                        open = false
                        onSetAtEnd(false)
                        sleepDeadline.value = if (m > 0) System.currentTimeMillis() + m * 60_000L else 0L
                    },
                )
            }
            DropdownMenuItem(
                text = { Text("End of video") },
                onClick = {
                    open = false
                    sleepDeadline.value = 0L
                    onSetAtEnd(true)
                },
            )
        }
    }
}

/** Floating "Up next" card pinned to the bottom of the non-fullscreen player. */
@Composable
private fun UpNextBar(
    video: SearchVideo,
    onPlay: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(Color(0xF21A1A1A))
            .clickable(onClick = onPlay)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg",
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(width = 64.dp, height = 36.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .background(Color(0xFF303030)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Up next", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB0B0B0))
            Text(
                video.title,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onDismiss) { Text("Dismiss", color = Color(0xFFB0B0B0)) }
    }
}

@Composable
private fun VideoPlayer(
    info: VideoInfo,
    sabrState: UiState<SabrSessionHolder>,
    fullscreen: Boolean,
    resumePositionMs: MutableState<Long>,
    lastGoodPositionMs: MutableState<Long>,
    onToggleFullscreen: () -> Unit,
    // Called with the play head (ms) to resume from once recovery completes — see PlayerBox's
    // onPlaybackError param for why this needs the position now (locked-screen-safe error recovery).
    onPlaybackError: (Long) -> Boolean,
    onEnded: () -> Unit = {},
    onNext: () -> Unit = {},
    hasNext: () -> Boolean = { false },
    sleepDeadline: MutableState<Long>? = null,
    forcePlay: MutableState<Int>? = null,
    sponsorSegments: List<SponsorSegment> = emptyList(),
    preferredHeight: Int = 0,
    onSelectVideoHeight: (Int) -> Unit = {},
    audioTracks: List<AudioTrackInfo> = emptyList(),
    onSelectAudioLanguage: (String) -> Unit = {},
    captionTracks: List<CaptionTrackInfo> = emptyList(),
    selectedCaption: CaptionTrackInfo? = null,
    playbackCaption: CaptionTrackInfo? = null,
    onSelectCaption: (CaptionTrackInfo?) -> Unit = {},
    onBandwidthLow: () -> Unit = {},
    onBandwidthGood: () -> Unit = {},
    onNearEnd: () -> Unit = {},
    // True when the legacy/DASH branch below is reached BY CHOICE (the viewer picked DASH in
    // Settings) rather than because a SABR session failed. Diagnostic only today — it just selects
    // the log level and wording for the `is UiState.Error ->` branch, whose message is a transport
    // ROUTING signal that is never rendered — but it is the hook to consult first if that branch ever
    // grows user-visible copy: "SABR unavailable" must never be shown to someone who never asked for
    // SABR in the first place.
    dashByChoice: Boolean = false,
    // True while the system PiP window is active. Disables our own in-player control overlay (native
    // PlayerView controller + settings gear) since Android's own PictureInPictureParams RemoteActions
    // (play/pause/next, wired in MainActivity) are what actually handle taps on a PiP window — our
    // overlay controls have no way to receive that touch and were only visually clashing with the
    // system's own chrome.
    inPip: Boolean = false,
) {
    val context = LocalContext.current
    val ua = info.streamUserAgent ?: PLAYER_UA

    // PRIMARY path: native SABR (the transport YouTube actually serves now). While
    // the SABR session is still being set up we show loading; only if it fails do
    // we fall through to the legacy direct-URL DASH/progressive paths below.
    when (sabrState) {
        is UiState.Idle, is UiState.Loading -> {
            LoadingSurface(fullscreen = fullscreen, status = "Preparing stream…")
            return
        }
        is UiState.Success -> {
            val holder = sabrState.data
            PlayerBox(
                // Key on the holder instance so a retry (new session) rebuilds; include the RESOLVED
                // caption so the source rebuilds only once its WebVTT text is ready (merged in / out).
                mediaKey = "sabr:${System.identityHashCode(holder)}:cc:${playbackCaption?.languageCode ?: "off"}",
                subtitleLanguage = playbackCaption?.languageCode,
                resumePositionMs = resumePositionMs,
                lastGoodPositionMs = lastGoodPositionMs,
                fullscreen = fullscreen,
                qualityCapHeight = null,
                onPlaybackError = onPlaybackError,
                onEnded = onEnded,
                sleepDeadline = sleepDeadline,
                forcePlay = forcePlay,
                sponsorSegments = sponsorSegments,
                storyboard = info.storyboard,
                onToggleFullscreen = onToggleFullscreen,
                // Publish this player to the MediaSession (lockscreen / notification / PiP controls).
                manageMediaSession = true,
                onNext = onNext,
                hasNext = hasNext,
                // Reference only: the pump paces off what the readers have actually read.
                onPositionUpdate = { holder.setPlayerTimeMs(it) },
                // Cold-session resume is done by a WARM seek (below), not by priming to a non-zero
                // start: a cold SABR session can't map a time to the right segment number (audio/video
                // segment durations differ → wrong seq → endless forward-jump + reload). But that dance
                // is only needed for a genuinely COLD holder (its first player build). A WARM holder
                // (e.g. this same session reused when toggling captions) already knows the stream's
                // segment timing and already has reader positions recorded deep into the video —
                // requesting segment 0 on it reads as a backward seek and forces a wasteful
                // rewind-to-start-then-forward-jump round trip (the long buffering seen when turning
                // captions on/off). A warm holder can instead seek straight to the resume point.
                warmSeek = !holder.isWarm(),
                onBandwidthLow = onBandwidthLow,
                onBandwidthGood = onBandwidthGood,
                onNearEnd = onNearEnd,
                inPip = inPip,
                buildSource = {
                    if (!holder.isWarm()) holder.setStartPositionMs(0L)
                    buildSabrSource(context, holder, info.title, info.author, playbackCaption)
                },
                // Quality + audio + captions live in the in-player gear menu (reachable in fullscreen).
                settingsContent = { dismiss ->
                    // Each group is a collapsible submenu (collapsed by default) so the menu stays short
                    // and any option is reachable without scrolling past the others.
                    val currentTrackId = holder.audioFormat.audioTrackId ?: ""
                    if (audioTracks.size > 1) {
                        val currentAudio = audioTracks.firstOrNull { it.id == currentTrackId }
                        SettingsSubmenu("Audio", currentAudio?.displayName ?: "—") {
                            audioTracks.forEach { t ->
                                val label = buildString {
                                    append(t.displayName)
                                    if (t.isDefault) append(" (original)")
                                    if (t.id == currentTrackId) append("  ✓")
                                }
                                SettingsSubmenuItem(label) {
                                    dismiss()
                                    onSelectAudioLanguage(t.language.ifBlank { "original" })
                                }
                            }
                        }
                    }
                    val maxHeight = remember { SabrQuality.deviceMaxVideoHeight(context) }
                    val qualities = remember(holder) { SabrQuality.videoOptions(holder.info, maxHeight) }
                    if (qualities.isNotEmpty()) {
                        val current = holder.videoFormat.height
                        SettingsSubmenu("Quality", "${current}p") {
                            qualities.forEach { q ->
                                val label = buildString {
                                    append(q.label)
                                    if (q.height == preferredHeight) append(" · Preferred")
                                    if (q.height == current) append("  ✓")
                                }
                                SettingsSubmenuItem(label) {
                                    dismiss()
                                    onSelectVideoHeight(q.height)
                                }
                            }
                        }
                    }
                    if (captionTracks.isNotEmpty()) {
                        SettingsSubmenu("Captions", selectedCaption?.name ?: "Off") {
                            SettingsSubmenuItem("Off" + if (selectedCaption == null) "  ✓" else "") {
                                dismiss(); onSelectCaption(null)
                            }
                            captionTracks.forEach { c ->
                                val label = buildString {
                                    append(c.name)
                                    if (c.isAuto) append(" (auto)")
                                    if (c.languageCode == selectedCaption?.languageCode) append("  ✓")
                                }
                                SettingsSubmenuItem(label) { dismiss(); onSelectCaption(c) }
                            }
                        }
                    }
                },
            )
            return
        }
        is UiState.Error -> {
            // Same routing either way (fall through to the DASH/progressive sources below), but not
            // the same event: in DASH mode this is the expected, chosen path and logging it as a
            // warning on every single video open would be pure noise in the field logs.
            if (dashByChoice) {
                Log.i("PlayerScreen", "DASH mode: rendering the direct-stream path by choice")
            } else {
                Log.w("PlayerScreen", "SABR unavailable (${sabrState.message}); using legacy path")
            }
            // fall through to legacy
        }
    }

    // Legacy fallback path: DASH (native seeking + adaptive bitrate + bounded ranges).
    val dash = info.dashManifest
    if (dash != null) {
        // Max-quality cap. null = Auto (full adaptive). Setting a cap still lets
        // ExoPlayer adapt DOWN below it when bandwidth drops ("adaptive regression").
        var capHeight by remember(info.id) { mutableStateOf<Int?>(null) }
        val heights = remember(info.id) { dashQualityHeights(info) }

        PlayerBox(
            // Keyed on the manifest content so a fresh re-fetch (new URLs)
            // actually rebuilds the player; a re-fetch with the same id alone
            // wouldn't. Built by [dashMediaKey] because PlayerViewModel must compute the IDENTICAL
            // key for a video it queued into the live player ahead of time — see dashMediaKey.
            mediaKey = dashMediaKey(dash),
            // Without this the DASH player's near-end poll called PlayerBox's default `{}` — so in
            // DASH mode nothing was ever prefetched for the next video, and every autoplay advance
            // tore the player (and the media session holding the foreground service) down and built
            // a new one. With the screen locked that new session cannot get back into the foreground
            // (Android 12+), and the app loses its network shortly after. See prefetchUpNext.
            onNearEnd = onNearEnd,
            resumePositionMs = resumePositionMs,
            lastGoodPositionMs = lastGoodPositionMs,
            fullscreen = fullscreen,
            qualityCapHeight = capHeight,
            onPlaybackError = onPlaybackError,
            onToggleFullscreen = onToggleFullscreen,
            inPip = inPip,
            storyboard = info.storyboard,
            // Legacy fallback used to publish no MediaSession at all — lockscreen/background
            // playback (and PiP transport controls) only worked while SABR was serving the video.
            // Since this path is reached whenever SABR genuinely fails, it deserves the same
            // background-playback support as the primary path, not degraded UX on top of degraded
            // transport. onEnded was similarly never wired here, so autoplay-next (and the sleep
            // timer's "stop at end of video") silently didn't fire for a video that fell back to DASH.
            manageMediaSession = true,
            onNext = onNext,
            hasNext = hasNext,
            onEnded = onEnded,
            buildSource = { buildDashSource(context, dash, ua, info.id, info.title, info.author) },
            controls = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (capHeight == null) "▶ Adaptive (auto quality)" else "▶ Up to ${capHeight}p",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.LightGray,
                        modifier = Modifier.weight(1f),
                    )
                    if (heights.isNotEmpty()) {
                        var menuOpen by remember { mutableStateOf(false) }
                        Box {
                            TextButton(onClick = { menuOpen = true }) {
                                Text(
                                    if (capHeight == null) "Auto ▾" else "${capHeight}p ▾",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text("Auto" + if (capHeight == null) "  ✓" else "") },
                                    onClick = { capHeight = null; menuOpen = false },
                                )
                                heights.forEach { h ->
                                    DropdownMenuItem(
                                        text = { Text("${h}p" + if (h == capHeight) "  ✓" else "") },
                                        onClick = { capHeight = h; menuOpen = false },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        )
        return
    }

    // Fallback path: progressive (single video+audio pair) with quality picker.
    val qualities = remember(info) { FormatSelector.qualityOptions(info) }
    var selectedHeight by remember(info) { mutableStateOf<Int?>(null) }
    val choice = remember(info, selectedHeight) {
        val h = selectedHeight
        if (h != null) FormatSelector.choiceForHeight(info, h) else FormatSelector.select(info)
    }

    if (choice == null) {
        // When YouTube reports the video unavailable (region lock, removed, sign-in/age required, …)
        // every client returns no formats — show that reason rather than a generic "no format".
        val reason = info.playabilityReason?.takeIf { it.isNotBlank() } ?: "No playable format found"
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.DarkGray).padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(reason, color = Color.White, textAlign = TextAlign.Center)
        }
        return
    }

    PlayerBox(
        mediaKey = choice.videoUrl,
        resumePositionMs = resumePositionMs,
        lastGoodPositionMs = lastGoodPositionMs,
        fullscreen = fullscreen,
        qualityCapHeight = null,
        onPlaybackError = onPlaybackError,
        onToggleFullscreen = onToggleFullscreen,
        inPip = inPip,
        storyboard = info.storyboard,
        // See the DASH branch above — the progressive fallback deserves the same
        // lockscreen/background/PiP support (and working autoplay-next) as the primary SABR path.
        manageMediaSession = true,
        onNext = onNext,
        hasNext = hasNext,
        onEnded = onEnded,
        buildSource = { buildProgressiveSource(choice, ua, info.id, info.title, info.author) },
        controls = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "▶ ${choice.videoLabel} + ${choice.audioLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.LightGray,
                    modifier = Modifier.weight(1f),
                )
                if (qualities.size > 1) {
                    var menuOpen by remember { mutableStateOf(false) }
                    Box {
                        TextButton(onClick = { menuOpen = true }) {
                            Text(
                                if (choice.height > 0) "${choice.height}p ▾" else "Quality ▾",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            qualities.forEach { q ->
                                DropdownMenuItem(
                                    text = { Text(q.label + if (q.height == choice.height) "  ✓" else "") },
                                    onClick = { selectedHeight = q.height; menuOpen = false },
                                )
                            }
                        }
                    }
                }
            }
        },
    )
}

/**
 * Shared player surface: an ExoPlayer keyed on [mediaKey] (rebuilt when it
 * changes), playing the source from [buildSource], with buffering + error
 * overlays and position preserved across rebuilds (keyed on [positionKey]).
 *
 * [qualityCapHeight] caps the adaptive video height (null = no cap); applied via
 * the track selector without rebuilding the player, so it switches smoothly.
 */
@Composable
private fun PlayerBox(
    mediaKey: String,
    resumePositionMs: MutableState<Long>,
    lastGoodPositionMs: MutableState<Long>,
    fullscreen: Boolean,
    qualityCapHeight: Int?,
    // Called with the play head (ms) to resume from when a fresh session is built, so a retry can hand
    // it straight to the currently-live player (PlaybackController.playDirectly) instead of relying on
    // Compose to recompose and rebuild — which silently never happens while the screen is locked (no
    // VSYNC while the screen is off). Returns whether a retry was actually scheduled.
    onPlaybackError: (Long) -> Boolean,
    onToggleFullscreen: () -> Unit,
    // BCP-47 language of the side-loaded subtitle to display (null = captions off). The source must
    // already include the matching text track (built in buildSource).
    subtitleLanguage: String? = null,
    buildSource: () -> androidx.media3.exoplayer.source.MediaSource,
    controls: @Composable () -> Unit = {},
    // Content of the in-player settings (gear) menu — quality + audio for SABR. Rendered as a
    // DropdownMenu over the video surface, so it's reachable in fullscreen too. null = no gear.
    settingsContent: (@Composable (onDismiss: () -> Unit) -> Unit)? = null,
    onPositionUpdate: (Long) -> Unit = {},
    onEnded: () -> Unit = {},
    // When true, publish this player to the process MediaSession (lockscreen / notification / PiP).
    // Wired only for the SABR (primary) path; the legacy fallbacks stay unmanaged.
    manageMediaSession: Boolean = false,
    onNext: () -> Unit = {},
    hasNext: () -> Boolean = { false },
    sleepDeadline: MutableState<Long>? = null,
    forcePlay: MutableState<Int>? = null,
    sponsorSegments: List<SponsorSegment> = emptyList(),
    // Scrub-preview sprite sheet spec for the seek bar (YouTube-style frame-at-position preview
    // while dragging) — null shows no preview (e.g. legacy fallback paths before their VideoInfo
    // carried one, or a video that genuinely has none).
    storyboard: com.example.ytclient.youtube.models.StoryboardInfo? = null,
    // When true, do NOT seek to the resume position while the source is cold; start at 0 and seek
    // only after the first READY (used by SABR, whose cold session mis-maps non-zero start times).
    warmSeek: Boolean = false,
    // Called when playback rebuffers repeatedly after it got going (bandwidth/decoder can't sustain
    // the current quality). The ViewModel steps quality down. Only wired for SABR.
    onBandwidthLow: () -> Unit = {},
    // Called after a sustained smooth-playback streak: the ViewModel may step quality back up (only if
    // it had previously stepped down). Only wired for SABR.
    onBandwidthGood: () -> Unit = {},
    // Called (repeatedly, once per position-poll tick) once playback is within NEAR_END_PREFETCH_MS
    // of the end. Lets the ViewModel warm the next queued/related video's SABR info ahead of time so
    // playNext() doesn't pay a full getSabrInfo round trip on top of the player rebuild. The callee is
    // expected to no-op on repeat calls for the same upcoming video.
    onNearEnd: () -> Unit = {},
    // True while the system PiP window is active — disables the native PlayerView's own control
    // overlay and our settings gear (see the doc comment on VideoPlayer's inPip param).
    inPip: Boolean = false,
) {
    val context = LocalContext.current
    var playerError by remember(mediaKey) { mutableStateOf<String?>(null) }
    var buffering by remember(mediaKey) { mutableStateOf(true) }
    var isPlaying by remember(mediaKey) { mutableStateOf(false) }

    // In-player settings (gear) menu open state.
    var showSettings by remember(mediaKey) { mutableStateOf(false) }
    // Fullscreen brightness/volume gestures: current screen brightness (0..1, window override),
    // a running media-volume value, and the transient on-screen indicator text.
    //
    // Read SCREEN_BRIGHTNESS_FLOAT (API 30+), not the legacy SCREEN_BRIGHTNESS int (0-255), as the
    // starting point. Both nominally describe "brightness," but on modern devices they are DIFFERENT
    // scales: SCREEN_BRIGHTNESS_FLOAT is the curve-corrected [0f, 1f] value the system's own
    // quick-settings slider actually drags across (and the same scale WindowManager.LayoutParams.
    // screenBrightness expects, post the Android 11 brightness-float refactor), while the legacy int is
    // a lossy compatibility shim on those devices. Driving the window override from the legacy scale is
    // exactly why a swipe-set brightness could never visually reach the slider's true max (1.0f on the
    // legacy-derived scale wasn't actually the slider's 1.0f), and why the bottom of the range felt
    // squashed into a couple of tiny swipe-steps (a few of the WRONG scale's percent span much less of
    // the real one at the dark end, where the correction curve is steepest). Falling back to the legacy
    // int only on pre-API-30 devices, where SCREEN_BRIGHTNESS_FLOAT doesn't exist yet.
    var brightness by remember {
        mutableStateOf(
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    android.provider.Settings.System.getFloat(
                        context.contentResolver,
                        "screen_brightness_float",
                    )
                } else {
                    android.provider.Settings.System.getInt(
                        context.contentResolver,
                        android.provider.Settings.System.SCREEN_BRIGHTNESS,
                    ) / 255f
                }
            }.getOrDefault(0.5f).coerceIn(0f, 1f),
        )
    }
    var volumeLevel by remember { mutableStateOf(0f) }
    var gestureIndicator by remember { mutableStateOf<String?>(null) }

    // Seek-bar scrub preview (YouTube-style "frame at this position" thumbnail): the position
    // currently being dragged to, or null when not scrubbing. NOT keyed on mediaKey — the
    // TimeBar.OnScrubListener below is attached once, in the AndroidView's factory, which (per the
    // update block's own comment) does NOT re-run across a mediaKey change (a quality switch, a
    // retry, …) since the same PlayerView instance is reused. Re-keying this would leave the
    // listener writing into a state object nobody reads after the first mediaKey.
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val timeBarScrubListener = remember {
        object : androidx.media3.ui.TimeBar.OnScrubListener {
            override fun onScrubStart(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                scrubPositionMs = position
            }
            override fun onScrubMove(timeBar: androidx.media3.ui.TimeBar, position: Long) {
                scrubPositionMs = position
            }
            override fun onScrubStop(timeBar: androidx.media3.ui.TimeBar, position: Long, canceled: Boolean) {
                scrubPositionMs = null
            }
        }
    }

    // Latest onEnded, so the player's long-lived listener always calls the current one.
    val currentOnEnded by rememberUpdatedState(onEnded)
    // Brief on-screen note when SponsorBlock auto-skips, so the jump reads as intentional.
    var sponsorSkipMsg by remember(mediaKey) { mutableStateOf<String?>(null) }
    LaunchedEffect(sponsorSkipMsg) { if (sponsorSkipMsg != null) { delay(2500); sponsorSkipMsg = null } }
    // Set by the SponsorBlock poll below right when it issues a skip seek; read by onPlayerError
    // further down so it can tell "this error is the skip itself hiccuping" apart from "a genuinely
    // unreachable seek target" — see the comment at onPlayerError for why that distinction matters
    // (without it, a SponsorBlock skip that trips a cold-SABR-session hiccup gets treated as an
    // unreachable seek and snapped back INSIDE the sponsor segment, which the next poll tick
    // re-detects and re-skips, forever: an infinite reload-into-segment loop).
    var lastSponsorSkipTarget by remember(mediaKey) { mutableStateOf(-1L) }
    var lastSponsorSkipAtMs by remember(mediaKey) { mutableStateOf(0L) }

    val trackSelector = remember(mediaKey) { DefaultTrackSelector(context) }

    // Captured once per (re)build, before the position poll starts overwriting resumePositionMs with
    // the live (≈0) play head. Used for the cold seek (legacy) and the warm seek (SABR).
    val resumeAt = remember(mediaKey) { resumePositionMs.value }

    // True from the moment THIS player starts being torn down (see the DisposableEffect below).
    // ExoPlayer.release() must run on the main thread and BLOCKS until the playback thread winds
    // down; if that takes longer than media3's release timeout it throws ExoTimeoutException and
    // delivers it to our listener as ERROR_CODE_TIMEOUT. Observed on a quality switch: the
    // departing 1080p player timed out on release, onPlayerError treated that as a PLAYBACK failure
    // for the incoming player, and kicked off a whole extra getSabrInfo + player rebuild — which is
    // exactly the "switching quality then seeking back takes ages" behaviour. A dying player's
    // errors are not playback errors; this flag makes the handler ignore them.
    // A single-element array rather than a State: it's read/written from a listener callback, and
    // must NOT trigger recomposition.
    val releasing = remember(mediaKey) { booleanArrayOf(false) }

    // The listener THIS composition attached, so its onDispose can detach exactly that one. Needed on
    // the hand-off path in particular: there the player outlives this composition (the successor
    // adopts it), so release() never clears listeners, and each locked-screen autoplay used to leave
    // one more stale listener on the shared player, still writing into a disposed composition's state.
    // A single-element array like `releasing`: set once inside the remember below, never observed.
    val attachedListener = remember(mediaKey) { arrayOfNulls<androidx.media3.common.Player.Listener>(1) }

    val player = remember(mediaKey) {
        // Adopt an already-live player representing this EXACT mediaKey instead of building a
        // duplicate: this happens when PlaybackController.playDirectly hijacked a still-playing
        // ExoPlayer straight onto this content (an autoplay-next that fired while the screen was
        // locked, before Compose could recompose to rebuild the player the normal way — see its doc).
        // By the time Compose catches up and this composable runs, that player is already correctly
        // playing this video; tearing it down and building a fresh session here would be redundant at
        // best and a jarring restart/glitch at worst.
        val adopted = PlaybackController.adoptIfLive(mediaKey)
        if (adopted != null) {
            Log.i("PlayerScreen", "Adopting already-live player ($mediaKey) — advanced while backgrounded")
        } else {
            Log.i("PlayerScreen", "Building player ($mediaKey) resume=${resumeAt}ms warmSeek=$warmSeek")
        }
        val exo = adopted ?: ExoPlayer.Builder(context, sabrRenderersFactory(context))
            .setTrackSelector(trackSelector)
            .setLoadControl(sabrLoadControl())
            // Auto-pause when headphones/BT audio disconnects instead of blasting through the
            // speaker unannounced — off by default in ExoPlayer.Builder.
            .setHandleAudioBecomingNoisy(true)
            // Background/lockscreen playback keeps the network alive across screen-off via a plain
            // partial wake lock while actually playing (released automatically when paused/idle).
            // Belt-and-suspenders alongside the foreground MediaSessionService (which already exempts
            // the process from most Doze restrictions) — cheap insurance for OEMs with more
            // aggressive background/network throttling on top of stock Doze behavior.
            .setWakeMode(androidx.media3.common.C.WAKE_MODE_NETWORK)
            .build()
        if (adopted != null) {
            // `buffering`/`isPlaying` above were just initialized to their cold-start defaults
            // (true/false). Normally the listener below flips them as the player transitions through
            // its states during prepare(); an adopted player is already sitting in whatever state it
            // reached before this composition existed (typically STATE_READY + actually playing) and
            // won't re-fire those transitions, so without this the buffering spinner would hang
            // forever despite audio/video already playing. Sync once, up front, from the real state.
            buffering = adopted.playbackState == androidx.media3.common.Player.STATE_BUFFERING
            isPlaying = adopted.isPlaying
        }
        // True once THIS exact ExoPlayer instance has been silently handed to a DIFFERENT video in
        // place (see PlaybackController.playDirectly) while this listener is still attached to it — an
        // autoplay-next that fired while the screen was locked, bypassing this composition entirely
        // (Compose couldn't recompose to rebuild the player the normal way — see playDirectly's doc).
        // Once that's happened this callback chain no longer owns what's actually playing and must
        // stop acting on it; its own successor (whichever composition Compose builds once it next
        // recomposes, which adopts this same player — see the `adopted` check above) owns it instead.
        fun isHijackedElsewhere(): Boolean =
            PlaybackController.currentPlayerOrNull() === exo && PlaybackController.adoptIfLive(mediaKey) == null
        // Bandwidth DOWN-probe state (SABR only): counts rebuffers to trigger a quality step-down. Lives
        // here, event-driven off onPlaybackStateChanged below, rather than on the periodic poll (see
        // PlayerBox's merged watchdog effect) — a rebuffer is a state TRANSITION, and sampling for one
        // only every N ms can miss it outright if it starts and fully resolves between two ticks. This
        // callback fires on every real transition, so nothing gets missed. Scoped to this `remember`
        // block (same lifetime as `exo`/the listener itself), so it resets per mediaKey exactly like the
        // poll-based version it replaces did.
        val bandwidthRebuffers = ArrayDeque<Long>()
        var bandwidthFirstReadyAtMs = 0L
        var bandwidthPrevState = -1
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == androidx.media3.common.Player.STATE_BUFFERING
                if (warmSeek && !isHijackedElsewhere()) {
                    val now = System.currentTimeMillis()
                    val ready = state == androidx.media3.common.Player.STATE_READY
                    if (ready && bandwidthFirstReadyAtMs == 0L) bandwidthFirstReadyAtMs = now
                    val enteredBuffering = state == androidx.media3.common.Player.STATE_BUFFERING &&
                        bandwidthPrevState != androidx.media3.common.Player.STATE_BUFFERING
                    // 6s cold-start grace: don't count the video's own normal opening buffer as a rebuffer.
                    val pastColdStart = bandwidthFirstReadyAtMs != 0L && now - bandwidthFirstReadyAtMs > 6_000
                    if (enteredBuffering && pastColdStart) {
                        bandwidthRebuffers.addLast(now)
                        while (bandwidthRebuffers.isNotEmpty() && now - bandwidthRebuffers.first() > 20_000) {
                            bandwidthRebuffers.removeFirst()
                        }
                        if (bandwidthRebuffers.size >= 3) {
                            bandwidthRebuffers.clear()
                            Log.w("PlayerScreen", "repeated rebuffering ($mediaKey) — requesting lower quality")
                            onBandwidthLow()
                        }
                    }
                    bandwidthPrevState = state
                }
                if (state == androidx.media3.common.Player.STATE_ENDED) {
                    if (isHijackedElsewhere()) return
                    // Autoplay-next needs a network round trip + JS-bridge work (fetch the next
                    // video's SABR session) before the next player can start. Our own wake mode above
                    // is tied to ACTIVE playback and lets go right as this fires — with the screen
                    // locked (audio-only) and nothing else holding the CPU awake, that gap is exactly
                    // where Doze can throttle the process and stall the fetch. A short, self-releasing
                    // wake lock covers that gap so the next video's session has a clear run to finish.
                    acquireBackgroundWorkWakeLock(context)
                    currentOnEnded()
                }
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                // DIAG (captions): is a text track present, and did the selector pick it?
                var textGroups = 0
                for (g in tracks.groups) {
                    if (g.type == C.TRACK_TYPE_TEXT) {
                        textGroups++
                        for (i in 0 until g.length) {
                            val f = g.getTrackFormat(i)
                            Log.i(
                                "PlayerCaptions",
                                "text track lang=${f.language} mime=${f.sampleMimeType} " +
                                    "supported=${g.isTrackSupported(i)} selected=${g.isTrackSelected(i)}",
                            )
                        }
                    }
                }
                if (textGroups == 0) Log.i("PlayerCaptions", "no text tracks in this media")
            }
            override fun onPositionDiscontinuity(
                oldPosition: androidx.media3.common.Player.PositionInfo,
                newPosition: androidx.media3.common.Player.PositionInfo,
                reason: Int,
            ) {
                // Capture seeks immediately so an error-triggered rebuild
                // resumes at the seek target, not at the last poll.
                if (reason == androidx.media3.common.Player.DISCONTINUITY_REASON_SEEK) {
                    resumePositionMs.value = newPosition.positionMs
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (isHijackedElsewhere()) return
                // Teardown noise, not a playback failure — see `releasing`'s doc.
                if (releasing[0]) {
                    Log.i("PlayerScreen", "ignoring ${error.errorCodeName} from a player being released")
                    return
                }
                val errAt = resumePositionMs.value
                val lastGood = lastGoodPositionMs.value
                // If this error immediately follows a SponsorBlock-issued skip, it's the skip itself
                // hiccuping on a cold SABR session (see the warm-seek notes elsewhere in this file) —
                // NOT a genuinely unreachable seek target. Skip the snap-back below in that case: it
                // would revert to lastGood, which is still INSIDE the sponsor segment, so the very
                // next poll tick re-detects the segment and re-issues the same skip forever. Falling
                // through to the normal retry() path instead rebuilds the player and resumes at errAt
                // (already past the segment), so the segment is never re-entered.
                val isSponsorSkipHiccup = errAt == lastSponsorSkipTarget &&
                    System.currentTimeMillis() - lastSponsorSkipAtMs < 4000
                // Forward seek into a region the (rate-limited) server won't serve:
                // don't get stuck re-attempting the unreachable target. Snap back
                // to the last position that actually played and retry IN PLACE (no
                // teardown / re-fetch). Recovers if the throttle has since eased;
                // if even that fails it falls through to the normal retry below.
                if (!isSponsorSkipHiccup && errAt > lastGood + SEEK_FALLBACK_THRESHOLD_MS) {
                    Log.w(
                        "PlayerScreen",
                        "seek target ${errAt}ms unreachable (${error.errorCodeName}); " +
                            "snapping back to ${lastGood}ms",
                    )
                    resumePositionMs.value = lastGood
                    exo.seekTo(lastGood)
                    exo.prepare()
                    return
                }
                Log.e("PlayerScreen", "ExoPlayer error ${error.errorCodeName} @${errAt}ms", error)
                // See acquireBackgroundWorkWakeLock's doc — without this, a mid-video error while the
                // screen is locked can have the SABR rebuild (and its legacy-format fallback) both die
                // to Doze throttling instead of actually recovering.
                acquireBackgroundWorkWakeLock(context)
                val willRetry = onPlaybackError(errAt)
                if (!willRetry) {
                    val cause = error.cause
                    playerError = "${error.errorCodeName}: ${cause?.message ?: error.message}"
                }
            }
        }
        exo.addListener(listener)
        attachedListener[0] = listener
        if (adopted == null) {
            exo.setMediaSource(buildSource())
            exo.prepare()
            // Legacy (DASH/progressive) can seek a cold source fine; SABR uses the warm seek below.
            if (!warmSeek && resumeAt > 0L) exo.seekTo(resumeAt)
            exo.playWhenReady = true
        }
        exo
    }

    // True once `player` has been silently handed to a DIFFERENT video in place (see
    // PlaybackController.playDirectly — an autoplay-next that fired while the screen was locked, before
    // Compose could recompose to rebuild the player the normal way) while THIS composition is still
    // alive. From that point on, this composition's polling loops below are watching someone else's
    // video and must stop touching shared state for it — its successor (whichever composition Compose
    // builds once it next recomposes, which adopts this same player — see the `adopted` check above)
    // owns it instead. Not needed for the ordinary "genuinely different player took over" case (a real
    // quality/caption/retry rebuild): there, this composition is disposed shortly after anyway, which
    // cancels these LaunchedEffects on its own.
    fun hijackedElsewhere(): Boolean =
        PlaybackController.currentPlayerOrNull() === player && PlaybackController.adoptIfLive(mediaKey) == null

    // Publish the player to the process MediaSession for system controls, and own its full teardown
    // (session detach + player release) in ONE effect, keyed on the player instance itself. attach()
    // is called for each (re)built player and SWAPS the session's player, so a quality switch /
    // autoplay-next doesn't tear the session down (which would flicker the notification) — only once
    // THIS exact player instance stops being current (superseded by a rebuilt one, or PlayerBox leaves
    // composition entirely) does the onDispose below actually detach + release.
    //
    // This MUST be a single effect rather than split across two differently-keyed ones (as it used to
    // be: a Unit-keyed "detach the session" effect plus a mediaKey-keyed "release the player" effect).
    // When PlayerBox leaves composition outright (e.g. Back to the home feed, or the queue advancing to
    // a new video), Compose disposes ALL of a departing composable's effects in REVERSE declaration
    // order — so the release-check effect (declared later, lower in this file) fired BEFORE the detach
    // effect (declared here, earlier). At that point the session hadn't been torn down yet, so the
    // release check always saw itself as "still the published player" and skipped releasing — the
    // ExoPlayer was detached from the session but never released, so it kept playing audio in the
    // background indefinitely (reported as: leave the player screen, or switch to a different video,
    // and the previous video's audio keeps going, layering with whatever plays next). A single effect
    // has no such ordering hazard.
    if (manageMediaSession) {
        DisposableEffect(player) {
            PlaybackController.attach(context, player, mediaKey, onNext, hasNext)
            onDispose {
                // Ours to remove on every path, the hand-off below included.
                attachedListener[0]?.let(player::removeListener)
                attachedListener[0] = null
                if (hijackedElsewhere()) {
                    // Handed off to a later video via PlaybackController.playDirectly (autoplay-next,
                    // or a locked-screen-safe error retry — see the SponsorBlock poll / onPlayerError
                    // below) while the screen was locked and this composition never got to recompose.
                    // The successor composition that adopts this SAME player instance owns its session
                    // + lifecycle from here on — do not detach or release out from under it.
                    return@onDispose
                }
                // Still the published player and not hijacked: tear the session down too. (If a NEWER
                // player has already been published in our place — the normal same-screen rebuild case,
                // e.g. a quality switch — this is false and we just release below without touching the
                // session the new player is already using.)
                if (PlaybackController.currentPlayerOrNull() === player) PlaybackController.detachCurrent()
                releasing[0] = true // suppress teardown errors — see `releasing`'s doc
                player.release()
            }
        }
    } else {
        DisposableEffect(player) {
            onDispose {
                attachedListener[0]?.let(player::removeListener)
                attachedListener[0] = null
                player.release()
            }
        }
    }

    // Warm resume seek (SABR): start at 0, and once the session has warmed (first READY → real
    // segment timing) seek to the resume point. A cold-session seek mis-maps the target segment and
    // hangs; on warm seek the chunk source resolves the right segments. If we never reach READY, or
    // the seek can't be served, the onPlayerError fallback (lastGood=0) drops to the start instead
    // of looping on an unreachable target.
    if (warmSeek) {
        LaunchedEffect(mediaKey) {
            if (resumeAt <= 1000L) return@LaunchedEffect
            var waited = 0
            while (player.playbackState != androidx.media3.common.Player.STATE_READY && waited < 80) {
                delay(250); waited++
            }
            if (player.playbackState == androidx.media3.common.Player.STATE_READY) {
                lastGoodPositionMs.value = 0L
                player.seekTo(resumeAt)
            }
        }
    }

    // Apply / update the max-height cap + caption selection live. ExoPlayer does NOT show subtitles
    // unless a preferred text language is set (or accessibility captioning is on), so set/clear it to
    // toggle the side-loaded caption track; disable the text renderer entirely when captions are off.
    LaunchedEffect(mediaKey, qualityCapHeight, subtitleLanguage) {
        trackSelector.parameters = trackSelector.buildUponParameters()
            .setMaxVideoSize(Int.MAX_VALUE, qualityCapHeight ?: Int.MAX_VALUE)
            .setPreferredTextLanguage(subtitleLanguage)
            .setSelectUndeterminedTextLanguage(subtitleLanguage != null)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleLanguage == null)
            .build()
    }

    // Sleep timer: pause the player once the deadline passes. Survives quality/autoplay rebuilds
    // because the deadline is hoisted to the screen.
    LaunchedEffect(mediaKey, sleepDeadline?.value) {
        val d = sleepDeadline?.value ?: 0L
        if (d > 0L) {
            val wait = d - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            player.playWhenReady = false
            sleepDeadline?.value = 0L
        }
    }

    // Resume on request (PiP entry) — skip the initial 0 value.
    LaunchedEffect(forcePlay?.value) {
        if ((forcePlay?.value ?: 0) > 0) player.playWhenReady = true
    }

    // All five of the player's periodic health/progress checks — stall watchdog, progress-stall
    // watchdog, bandwidth watcher, SponsorBlock, and resume-position tracking — used to be five
    // independent `while (true) { delay(...) }` loops, each waking its own coroutine on its own cadence
    // (2000/1000/500/400/1000ms). That's fine individually, but it's the one piece of this screen that
    // runs continuously for the ENTIRE duration of every video ever watched, so the wakeup count adds
    // up over an actual viewing session. Merged into one coroutine that sleeps only until the SOONEST
    // check that's actually due (never a fixed short tick), each check keeping its original cadence —
    // same behavior, a fraction of the wakeups.
    //
    // sponsorSegments needs rememberUpdatedState because this effect is keyed on mediaKey alone (not
    // mediaKey + sponsorSegments as the old separate loop was) — segments can finish loading
    // asynchronously after the effect already started, and a plain captured reference would go stale.
    val currentSponsorSegments by rememberUpdatedState(sponsorSegments)
    LaunchedEffect(mediaKey) {
        var stallBufferingSinceMs = 0L
        var progressLastPos = -1L
        var progressStuckSinceMs = 0L
        var smoothSinceMs = 0L
        var sponsorLastPos = -1L
        var sponsorWarm = false
        // Extra forward margin added to a sponsor-skip target on a RETRY (see below) — grows each time
        // we detect the previous skip still didn't clear the segment, reset once a skip actually lands
        // clean. Needed because a seek doesn't necessarily land on the exact requested millisecond
        // (SABR resolves seeks at segment granularity, not arbitrary byte/frame offsets), so blindly
        // re-issuing the identical target on every retry can repeat the identical undershoot forever.
        var sponsorSkipRetryMarginMs = 0L
        // Consecutive undershoot-retry attempts against the CURRENT segment run (see
        // MAX_SPONSOR_SKIP_ATTEMPTS) — reset whenever a skip is issued that ISN'T a retry (a genuinely
        // new segment/run) or once the run is actually cleared.
        var sponsorStuckAttempts = 0
        // The mergedEnd of a segment run we've given up auto-skipping after hitting the attempt cap —
        // -1 when nothing has been given up on. Prevents immediately re-triggering the same runaway
        // escalation on the very next tick while the player is still sitting inside that same run.
        var sponsorGiveUpTarget = -1L
        var nextStallCheckMs = 0L
        var nextProgressCheckMs = 0L
        var nextBandwidthCheckMs = 0L
        var nextSponsorCheckMs = 0L
        var nextPositionCheckMs = 0L
        // The first timestamp at/after `fromMs` that no sponsor segment covers — i.e. where a skip
        // should ACTUALLY land. A single matched segment's own endMs isn't enough on its own: community
        // submissions for the same ad often overlap or sit back-to-back (two slightly different spans
        // covering the same sponsor read), and skipping to just the first one's end can land squarely
        // inside a second one, which used to only get caught (and skipped) on the NEXT poll tick — a
        // visible little stutter, and if the seek back then undershoot too, a real stuck loop. Walking
        // forward through every segment that covers the current candidate — repeatedly, since jumping
        // past one can land inside another — resolves the whole overlapping run in one go.
        fun firstNonSponsorMs(fromMs: Long, segs: List<SponsorSegment>): Long {
            var candidate = fromMs
            var extended = true
            while (extended) {
                extended = false
                for (s in segs) {
                    if (candidate >= s.startMs && candidate < s.endMs) {
                        candidate = s.endMs
                        extended = true
                    }
                }
            }
            return candidate
        }
        while (true) {
            val nowForSleep = System.currentTimeMillis()
            val nextDueMs = minOf(
                nextStallCheckMs,
                nextProgressCheckMs,
                if (warmSeek) nextBandwidthCheckMs else Long.MAX_VALUE,
                if (currentSponsorSegments.isNotEmpty()) nextSponsorCheckMs else Long.MAX_VALUE,
                nextPositionCheckMs,
            )
            // Coerced so a just-missed deadline doesn't busy-loop (floor) and a fully-idle merged
            // effect (nothing due, e.g. no sponsor segments this video) doesn't oversleep past the
            // next genuinely scheduled check by more than this ceiling.
            delay((nextDueMs - nowForSleep).coerceIn(50L, 2000L))

            // Position update (below) is DELIBERATELY not gated by this — see its own comment for why
            // (autoplay-next chaining still needs onNearEnd() to fire after a hijack).
            val hijacked = hijackedElsewhere()
            val now = System.currentTimeMillis()

            if (!hijacked && now >= nextStallCheckMs) {
                // 5s check against a 25s (STALL_WATCHDOG_MS) trigger: a few extra seconds of check
                // granularity is irrelevant when the threshold itself is already "buffering for 25s
                // straight, something is badly wrong" — no point waking up every 2s to confirm that.
                nextStallCheckMs = now + 5000
                // Stall watchdog: if playback is stuck BUFFERING (cold-start SABR can hang on its
                // first request), re-fetch a fresh session instead of hanging forever. Resets whenever
                // it leaves the buffering state.
                if (player.playbackState == androidx.media3.common.Player.STATE_BUFFERING) {
                    if (stallBufferingSinceMs == 0L) {
                        stallBufferingSinceMs = now
                    } else if (now - stallBufferingSinceMs > STALL_WATCHDOG_MS) {
                        stallBufferingSinceMs = 0L
                        Log.w("PlayerScreen", "watchdog: buffering > ${STALL_WATCHDOG_MS}ms ($mediaKey) — retrying")
                        // See acquireBackgroundWorkWakeLock's doc — same Doze-throttling risk as the
                        // onPlayerError retry above, just triggered by the stall watchdog instead of a
                        // thrown PlaybackException.
                        acquireBackgroundWorkWakeLock(context)
                        if (!onPlaybackError(resumePositionMs.value)) {
                            playerError = "Couldn't load — please go back and retry"
                        }
                    }
                } else {
                    stallBufferingSinceMs = 0L
                }
            }

            if (!hijacked && now >= nextProgressCheckMs) {
                // Left tight relative to the others: the stuck-threshold itself is only 6s, so the
                // check interval needs to stay a proportionally small slice of that budget, unlike the
                // stall/sponsor/position checks below whose own thresholds are much longer.
                nextProgressCheckMs = now + 1500
                // Progress-stall watchdog: if the player says it's READY and should be playing but the
                // position hasn't advanced for several seconds, a renderer is silently stuck (typically
                // the audio sink, whose clock then freezes the whole playback position) — the user
                // sees audio drop out and only a manual seek revives it. Detect it and do that seek
                // automatically (a no-op micro-seek to the current position re-syncs the renderers).
                val shouldPlay = player.playWhenReady &&
                    player.playbackState == androidx.media3.common.Player.STATE_READY
                if (!shouldPlay) {
                    progressLastPos = -1L
                    progressStuckSinceMs = 0L
                } else {
                    val pos = player.currentPosition
                    if (progressLastPos >= 0 && pos == progressLastPos) {
                        if (progressStuckSinceMs == 0L) {
                            progressStuckSinceMs = now
                        } else if (now - progressStuckSinceMs > 6_000) {
                            Log.w("PlayerScreen", "playback stuck at ${pos}ms while playing ($mediaKey) — nudging")
                            player.seekTo(pos)
                            progressStuckSinceMs = 0L
                        }
                    } else {
                        progressStuckSinceMs = 0L
                    }
                    progressLastPos = pos
                }
            }

            // Bandwidth UP-probe (SABR only): after ~45s of uninterrupted READY+playWhenReady playback,
            // let the ViewModel probe a step back up (it no-ops unless it had previously stepped down,
            // and never climbs back into a level that rebuffered). The DOWN side (≥3 rebuffers within
            // 20s, after a 6s cold-start grace) moved OUT of this poll entirely and into the player's
            // own Player.Listener.onPlaybackStateChanged (see there) — it's detecting a state
            // TRANSITION, and a transition sampled only every N ms can miss a rebuffer that starts and
            // fully resolves between two ticks; the listener callback fires on every real transition,
            // no sampling gap possible. The UP-probe stays here because it's measuring elapsed time in
            // a state, which genuinely is a periodic-sampling concern rather than an edge-detection one.
            if (!hijacked && warmSeek && now >= nextBandwidthCheckMs) {
                // Loosened from 500ms: with the DOWN/edge-detection half gone, all that's left here is
                // "has 45s elapsed while smooth" — a coarser tick doesn't cost any missed events.
                nextBandwidthCheckMs = now + 1000
                if (player.playbackState == androidx.media3.common.Player.STATE_READY && player.playWhenReady) {
                    if (smoothSinceMs == 0L) {
                        smoothSinceMs = now
                    } else if (now - smoothSinceMs > 45_000) {
                        smoothSinceMs = now
                        onBandwidthGood()
                    }
                } else {
                    smoothSinceMs = 0L
                }
            }

            // SponsorBlock: poll a bit faster than the resume poll and seek past any skip segment. Two
            // guards make this safe on SABR (a cold session mis-maps a far forward seek until it has
            // actually served some segments — see the warm-seek note above):
            //  1. WARM GATE: only skip once natural playback has advanced the position. A segment that
            //     starts at 0 (intro music_offtopic) otherwise fires the instant the player first
            //     reaches READY — before the session is seekable — so the forward seek can't land.
            //  2. DEBOUNCE: after issuing a skip to a target, give the seek time to land before
            //     re-issuing. Without it, re-checking every tick would re-seek before the previous
            //     seek's forward-jump completes, flushing the decoder and restarting it forever —
            //     playback stuck looping at the segment start.
            val segments = currentSponsorSegments
            if (!hijacked && segments.isNotEmpty() && now >= nextSponsorCheckMs) {
                // Loosened from 400ms: segments run many seconds long, so letting up to ~600ms of
                // sponsor content play before the skip fires isn't something a viewer would notice —
                // the tight interval bought precision nobody could perceive.
                nextSponsorCheckMs = now + 600
                if (player.playbackState == androidx.media3.common.Player.STATE_READY && player.isPlaying) {
                    val pos = player.currentPosition
                    if (sponsorLastPos in 0 until pos) sponsorWarm = true // advanced under natural playback
                    sponsorLastPos = pos
                    val seg = if (sponsorWarm) {
                        segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 300 }
                    } else {
                        null
                    }
                    if (seg == null) {
                        // No active segment right now — clear any prior give-up marker so a LATER,
                        // unrelated run that happens to share the same mergedEnd value isn't wrongly
                        // treated as already-abandoned.
                        sponsorGiveUpTarget = -1L
                        // FAST WAKE: if the next segment's start is nearer than the regular 600ms
                        // cadence, wake right at it, so the skip fires within ~a frame of entering
                        // the segment instead of letting up to 600ms of it play first.
                        val untilNextStart = segments.minOf {
                            if (it.startMs > pos) it.startMs - pos else Long.MAX_VALUE
                        }
                        if (untilNextStart < 600) {
                            nextSponsorCheckMs = now + untilNextStart.coerceAtLeast(50L)
                        }
                    }
                    if (seg != null) {
                        val dur = player.duration
                        // Resolve the WHOLE run of overlapping/adjacent segments covering this position,
                        // not just the one that happened to match first — see firstNonSponsorMs's comment
                        // above for why a single seg.endMs isn't enough on its own.
                        val mergedEnd = firstNonSponsorMs(pos, segments)
                        // A merged run whose end reaches (or passes) the actual video duration has
                        // nothing left to skip TO. Seeking to mergedEnd in that case targets a position
                        // at/beyond the real content length — SABR has no chunk to serve there, the seek
                        // errors, onPlayerError treats it as a "sponsor-skip hiccup" (see its comment) and
                        // retries by resuming at that SAME unreachable target, which errors again,
                        // forever. From the outside that looked exactly like the reported bug: the video
                        // never stopped or advanced to the next queued item, and instead of a real skip it
                        // appeared to snap back into the segment (the rebuilt session's resumable range
                        // doesn't reach the bogus target either, so it restarts from the nearest position
                        // it actually has — the segment's own start — which this same poll then
                        // re-detects and re-"skips," forever). Treat it like naturally reaching the end of
                        // the video instead: seek to the real duration and let the existing
                        // STATE_ENDED → currentOnEnded() path stop playback / advance the queue exactly
                        // like it does for any other video. Using mergedEnd (not seg.endMs) here matters:
                        // a chain of overlapping segments can reach the video's end even when the FIRST
                        // matched segment's own endMs doesn't.
                        val runsToVideoEnd = dur != androidx.media3.common.C.TIME_UNSET &&
                            mergedEnd >= dur - END_OF_VIDEO_TOLERANCE_MS
                        // lastSponsorSkipTarget/lastSponsorSkipAtMs are hoisted to PlayerBox scope (not
                        // local here) so onPlayerError can also see "a skip was just issued" — see the
                        // comment there for why that matters.
                        val baseTarget = if (runsToVideoEnd) dur else mergedEnd
                        // RETRY ESCALATION (non-end case only — BACKSTOP): if the position is still (or
                        // again) inside the segment run at/after the target we already sought to last
                        // time, the previous seek undershot. Since SabrChunkSource.getAdjustedSeekPositionUs
                        // resolves seeks to the exact requested position (see MAX_SPONSOR_SKIP_ATTEMPTS's
                        // doc), this should no longer happen in normal operation — it remains possible
                        // only while a cold session's segment index is still an averageDurationMs
                        // estimate. Re-issuing the IDENTICAL target could repeat an identical undershoot,
                        // so grow a margin and aim further past mergedEnd each retry, capped so a
                        // pathological run can't send the seek arbitrarily far.
                        val looksLikeUndershootRetry = !runsToVideoEnd &&
                            lastSponsorSkipTarget >= 0 &&
                            baseTarget <= lastSponsorSkipTarget &&
                            now - lastSponsorSkipAtMs < 4000
                        // GIVE-UP CHECK: already hit MAX_SPONSOR_SKIP_ATTEMPTS on this exact run (see
                        // below) — leave it alone instead of immediately restarting the same runaway
                        // escalation on the very next tick. Clears itself once pos moves past this run
                        // (mergedEnd changes, or seg stops matching entirely) or a genuinely different
                        // run is encountered.
                        val gaveUpOnThisRun = !runsToVideoEnd && mergedEnd == sponsorGiveUpTarget
                        if (gaveUpOnThisRun) {
                            // no-op this tick — let it play.
                        } else {
                            if (looksLikeUndershootRetry) {
                                sponsorStuckAttempts++
                            } else {
                                sponsorStuckAttempts = 0
                            }
                            if (!runsToVideoEnd && sponsorStuckAttempts >= MAX_SPONSOR_SKIP_ATTEMPTS) {
                                // HARD CIRCUIT BREAKER — see MAX_SPONSOR_SKIP_ATTEMPTS's doc. Independent
                                // of *why* the seeks keep landing short (no PlaybackException is thrown in
                                // the suspected cause — ExoPlayer's SABR chunk source resolving every seek
                                // to the start of its containing chunk rather than the requested target —
                                // so onPlayerError/autoRetries never sees this at all). Give up on this
                                // specific run instead of escalating forever.
                                Log.w(
                                    "PlayerScreen",
                                    "SponsorBlock: giving up auto-skipping ${seg.category} run " +
                                        "${seg.startMs}->${mergedEnd}ms after $MAX_SPONSOR_SKIP_ATTEMPTS " +
                                        "stuck attempts (still at ${pos}ms; last requested " +
                                        "${lastSponsorSkipTarget}ms) — letting it play",
                                )
                                sponsorGiveUpTarget = mergedEnd
                                sponsorStuckAttempts = 0
                                sponsorSkipRetryMarginMs = 0L
                            } else {
                                val target = if (runsToVideoEnd) {
                                    dur
                                } else if (looksLikeUndershootRetry) {
                                    sponsorSkipRetryMarginMs =
                                        (sponsorSkipRetryMarginMs + 500L).coerceAtMost(4000L)
                                    lastSponsorSkipTarget + sponsorSkipRetryMarginMs
                                } else {
                                    sponsorSkipRetryMarginMs = 0L
                                    baseTarget
                                }
                                val alreadySeeking =
                                    target == lastSponsorSkipTarget && now - lastSponsorSkipAtMs < 4000
                                if (!alreadySeeking) {
                                    if (runsToVideoEnd) {
                                        Log.i(
                                            "PlayerScreen",
                                            "SponsorBlock: ${seg.category} segment run reaches the end of the " +
                                                "video (${mergedEnd}ms vs ${dur}ms duration) — ending instead of skipping",
                                        )
                                    } else {
                                        // DIAGNOSTIC: shows where the PREVIOUS attempt (if any) actually
                                        // landed vs. what was requested — the gap between the two is exactly
                                        // what confirms/refutes the chunk-boundary-snapping theory above if
                                        // this ever needs debugging again.
                                        Log.i(
                                            "PlayerScreen",
                                            "SponsorBlock skip ${seg.category} ${seg.startMs}->${mergedEnd}ms" +
                                                if (looksLikeUndershootRetry) {
                                                    " (retry +${sponsorSkipRetryMarginMs}ms, attempt " +
                                                        "#${sponsorStuckAttempts} — previous seek to " +
                                                        "${lastSponsorSkipTarget}ms actually landed at ${pos}ms)"
                                                } else {
                                                    ""
                                                },
                                        )
                                        sponsorSkipMsg = "Skipped " + seg.category.replace('_', ' ')
                                    }
                                    player.seekTo(target)
                                    lastSponsorSkipTarget = target
                                    lastSponsorSkipAtMs = now
                                    sponsorLastPos = -1L // reset progress tracking across the seek discontinuity
                                }
                            }
                        }
                    }
                }
            }

            // Keep resume + last-good fresh while the player has a valid position (READY). last-good
            // advances only during real playback, so it marks the pre-seek point we can safely fall
            // back to. NOT updated while buffering/idle/ended, so a transient post-error 0 can't
            // clobber it. NOT gated by `hijacked` (unlike every check above): this block's side effects
            // are all individually harmless if it keeps running against content a hijack has since
            // moved past this composition's stale resumePositionMs/lastGoodPositionMs (discarded once
            // Compose next recomposes) and onPositionUpdate's stale holder reference (already
            // "reference only" per its own doc, on a holder whose pump is idle/released by then).
            // Crucially, onNearEnd() → prefetchUpNext() DOES still matter: it's what lets a SECOND (or
            // later) autoplay-next also hijack the player while the screen stays locked, instead of the
            // chain only ever surviving one hand-off — see PlaybackController's autoAdvanceOnEnded doc
            // for the other half of that chaining story.
            if (now >= nextPositionCheckMs) {
                // Loosened from 1000ms: the near-end prefetch only needs to land once inside a 20s
                // window (NEAR_END_PREFETCH_MS), which a 2.5s check still catches comfortably, and
                // resume-position freshness has a separate, coarser backstop anyway (PlayerScreen's own
                // 5s history-write loop) — this poll isn't the last line of defense for that.
                nextPositionCheckMs = now + 2500
                if (player.playbackState == androidx.media3.common.Player.STATE_READY) {
                    val pos = player.currentPosition
                    resumePositionMs.value = pos
                    lastGoodPositionMs.value = pos
                    // Feed the real play head to the SABR session (no-op for legacy paths) so it
                    // evicts only already-played segments instead of growing unbounded.
                    onPositionUpdate(pos)
                    val dur = player.duration
                    if (dur != androidx.media3.common.C.TIME_UNSET && dur - pos in 0..NEAR_END_PREFETCH_MS) {
                        onNearEnd()
                    }
                }
            }
        }
    }

    // NOTE: teardown (session detach + player release) is handled by the single DisposableEffect(player)
    // above, not here — see its doc comment for why it must be one effect, not two.
    //
    // Do NOT save position on teardown: on a dispose that follows a fatal error the player's
    // currentPosition can read 0 and clobber the seek target.

    // Restore system-controlled screen brightness when the player leaves the screen (the brightness
    // gesture sets a per-window override that would otherwise persist for the activity).
    DisposableEffect(Unit) {
        onDispose {
            (context as? Activity)?.window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                w.attributes = lp
            }
        }
    }

    val playerSurface: @Composable () -> Unit = {
        val baseModifier = if (fullscreen) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
        }
        // Brightness/volume swipe is FULLSCREEN-LANDSCAPE-ONLY. `fullscreen` (the `fs` argument
        // threaded down from playerSlot) is true for THREE cases that all want the same bare, fills-
        // its-container surface: real device fullscreen, the system PiP window, AND our own in-app
        // mini-player card (see currentInPip = inPip || mini in PlayerScreen) — it does NOT by itself
        // mean "real fullscreen." Gating only on it used to also arm this gesture inside the mini
        // card, where it silently stole the vertical drag before it ever reached the mini Box's own
        // `.draggable` (expand-by-dragging-up), since this inner pointerInput sits underneath/inside
        // that outer modifier in the same touch stream. `inPip` here is that combined
        // PiP-or-mini flag, so excluding it rules out both non-real-fullscreen cases in one check —
        // and additionally frees the drag gesture for the mini card's own expand-on-drag-up handler.
        // The explicit landscape check on top rules out the brief window where `fullscreen` has
        // already flipped true (e.g. right after tapping the fullscreen button) but the requested
        // device rotation hasn't actually landed yet — the inline (non-fullscreen, portrait) surface
        // uses vertical drag for the collapse-to-mini-player gesture instead (see PlayerScreen's
        // draggable wrapper around playerSlot(false)/(true)), so this must stay off until both are true.
        val isLandscapeNow = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        val gestureModifier = if (fullscreen && !inPip && isLandscapeNow) {
            Modifier.pointerInput(mediaKey) {
                var leftSide = false
                // A swipe that STARTS within this top fraction of the player (either half) is ignored
                // entirely for brightness/volume — it's left as a deadzone so a swipe-down from the top
                // edge to reveal the system notification shade doesn't also scrub volume/brightness.
                var inTopDeadzone = false
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        leftSide = offset.x < size.width / 2f
                        inTopDeadzone = offset.y < size.height * 0.10f
                        if (!inTopDeadzone && !leftSide) {
                            val am = context.getSystemService(
                                android.content.Context.AUDIO_SERVICE,
                            ) as? AudioManager
                            volumeLevel = (am?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0).toFloat()
                        }
                    },
                    onDragEnd = { gestureIndicator = null },
                    onDragCancel = { gestureIndicator = null },
                ) { _, dragAmount ->
                    if (inTopDeadzone) return@detectVerticalDragGestures
                    // dragAmount > 0 is downward; swipe UP should increase, so subtract.
                    val frac = dragAmount / size.height.toFloat()
                    if (leftSide) {
                        // 0f is a valid, fully-dark WindowManager override (distinct from the negative
                        // BRIGHTNESS_OVERRIDE_NONE used elsewhere to hand control back to the system) —
                        // clamping the floor at 0.01f previously meant the gesture could never reach as
                        // dark as the system's own quick-settings brightness slider allows.
                        brightness = (brightness - frac).coerceIn(0f, 1f)
                        (context as? Activity)?.window?.let { w ->
                            val lp = w.attributes
                            lp.screenBrightness = brightness
                            w.attributes = lp
                        }
                        gestureIndicator = "Brightness ${(brightness * 100).toInt()}%"
                    } else {
                        val am = context.getSystemService(
                            android.content.Context.AUDIO_SERVICE,
                        ) as? AudioManager
                        if (am != null) {
                            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
                            volumeLevel = (volumeLevel - frac * max).coerceIn(0f, max)
                            am.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel.toInt(), 0)
                            gestureIndicator = "Volume ${(volumeLevel / max * 100).toInt()}%"
                        }
                    }
                }
            }
        } else {
            Modifier
        }
        androidx.compose.foundation.layout.BoxWithConstraints(
            // BoxWithConstraints (not a plain Box) so the scrub-preview overlay below can position
            // itself horizontally by seek fraction.
            modifier = baseModifier.then(gestureModifier),
        ) {
            AndroidView(
                factory = { ctx ->
                    // Back to a plain SurfaceView-backed PlayerView (the default — no more inflating a
                    // texture_view layout). That was a real fix for the mini-player's stale/corner-pinned
                    // video (SurfaceView's buffer resizes asynchronously from the View's own layout pass,
                    // and the mini-player was animating this View's actual Compose-measured size every
                    // drag frame), but it traded a rare, gesture-triggered glitch for a persistent cost:
                    // TextureView pushes every video frame through the normal View draw pipeline instead
                    // of a hardware overlay, which is meaningfully worse for battery/GPU over the actual
                    // 99%-of-the-time use case (continuous playback). The real fix belongs where the
                    // resize actually happens — AppRoot no longer animates this View's real layout size at
                    // all (see its BoxWithConstraints comment); only a graphicsLayer transform moves/scales
                    // the ALREADY-fixed-size container, which never touches SurfaceView's buffer. Safe to
                    // revert now that the actual cause is fixed at the source.
                    PlayerView(ctx).apply {
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setFullscreenButtonClickListener { onToggleFullscreen() }
                        useController = !inPip
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                        // Drive the scrub-preview overlay below off the native controller's own seek
                        // bar. Attached once here (the factory runs exactly once per PlayerView
                        // instance — see the update block's comment on why a mediaKey change doesn't
                        // re-run it), not per-recomposition, so this never double-registers.
                        // TimeBar is an interface, not a View, so findViewById needs the concrete View
                        // type + a cast rather than `findViewById<TimeBar>(...)` directly.
                        (findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress) as?
                            androidx.media3.ui.TimeBar)?.addListener(timeBarScrubListener)
                    }
                },
                update = {
                    // Re-bind on every recomposition: a mediaKey change (e.g. a quality switch)
                    // rebuilds `player` IN PLACE without tearing down this AndroidView, so the
                    // factory does NOT re-run. Without this the PlayerView keeps the old, released
                    // player → blank surface + the controls' play button calling prepare() on a dead
                    // player ("Handler on a dead thread"). Setting the same instance is a no-op.
                    if (it.player !== player) it.player = player
                    it.keepScreenOn = isPlaying
                    // The system's own PictureInPictureParams RemoteActions (play/pause/next) are what
                    // handle taps on a PiP window — this view's own tap-to-reveal control bar (play/
                    // pause, seekbar, fullscreen button) can't receive that touch, so leaving it enabled
                    // only risked it being drawn behind/around the system's chrome (the "overlapping
                    // pause + fullscreen buttons" reported in PiP). Disabling it here (rather than only
                    // in the factory) also covers entering/leaving PiP on an already-built PlayerView.
                    it.useController = !inPip
                    if (inPip) it.hideController()
                },
                modifier = Modifier.fillMaxSize(),
            )
            // YouTube-style scrub preview: only while actively dragging the seek bar, and only for a
            // video whose VideoInfo carried a storyboard spec (see StoryboardPreview's doc).
            val previewPos = scrubPositionMs
            if (previewPos != null && storyboard != null && !inPip) {
                val dur = player.duration
                val fraction = if (dur > 0L && dur != androidx.media3.common.C.TIME_UNSET) {
                    (previewPos.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                } else {
                    0.5f
                }
                val previewWidth = 128.dp
                val xOffset = (maxWidth * fraction - previewWidth / 2)
                    .coerceIn(4.dp, (maxWidth - previewWidth - 4.dp).coerceAtLeast(4.dp))
                StoryboardPreview(
                    storyboard = storyboard,
                    positionMs = previewPos,
                    width = previewWidth,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(x = xOffset, y = 24.dp),
                )
            }
            if (buffering && playerError == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
            playerError?.let { err ->
                Box(
                    Modifier.fillMaxSize().background(Color(0xCC000000)).padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "Playback error:\n$err",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            sponsorSkipMsg?.let { msg ->
                Text(
                    text = msg,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(12.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }

            // In-player settings (gear): quality + audio. Overlaid on the surface so it's reachable
            // in fullscreen, where the bottom controls row isn't shown. Hidden entirely in PiP: the
            // tiny PiP window can't receive taps on it anyway (see the useController note above), and
            // leaving it visible was another element clashing with the system's own PiP chrome.
            //
            // key(fullscreen): the whole player subtree is `movableContentOf` and is MOVED between the
            // fullscreen call site and the inline one on rotation. A known Compose quirk is that a
            // pointer-input node (IconButton's clickable) inside moved content loses its hit-test
            // registration, so the gear stops responding after a rotate round-trip. Re-keying on the
            // fullscreen flag (which flips on every such move) rebuilds the node fresh. showSettings is
            // hoisted above this block, so the open/closed state survives the rebuild.
            if (settingsContent != null && !inPip) {
                key(fullscreen) {
                    Box(
                        // windowInsetsPadding(statusBars): in portrait the player sits at the very top
                        // under the status bar, so a corner-anchored gear lands beneath the battery/clock
                        // where the system swallows the touch. Inset it below the status bar (0 in
                        // fullscreen where the bar is hidden) so it stays tappable.
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(4.dp),
                    ) {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                        DropdownMenu(
                            expanded = showSettings,
                            onDismissRequest = { showSettings = false },
                        ) {
                            settingsContent { showSettings = false }
                        }
                    }
                }
            }

            // Transient brightness/volume indicator for the swipe gestures.
            gestureIndicator?.let { g ->
                Text(
                    g,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .background(Color(0xCC000000))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }

    if (fullscreen) {
        playerSurface()
    } else {
        Column {
            playerSurface()
            controls()
        }
    }
}

/**
 * YouTube-style "frame at this position" preview shown while dragging the seek bar. [storyboard] is
 * one resolution level of the video's storyboard spec (see index.js's extractStoryboard); YouTube
 * ships this as a grid of small tiles baked into a handful of sprite-sheet JPEGs rather than one
 * image per timestamp, so showing the right frame means: pick which [storyboard]-interval "frame"
 * [positionMs] falls into, work out which sprite sheet that frame lives in and its (column, row)
 * inside it, then crop just that tile out of the downloaded sheet.
 *
 * Sprite sheets are cached in memory per composition (keyed by URL) so repeated onScrubMove calls
 * during ONE drag — which usually land on the same sheet, since each covers columns*rows frames —
 * don't re-download/re-decode anything; the cache (and this whole composable) is discarded once
 * scrubbing stops, since [PlayerBox] only includes it in composition while actively dragging.
 */
@Composable
private fun StoryboardPreview(
    storyboard: com.example.ytclient.youtube.models.StoryboardInfo,
    positionMs: Long,
    width: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val columns = storyboard.columns.coerceAtLeast(1)
    val rows = storyboard.rows.coerceAtLeast(1)
    val perSheet = (columns * rows).coerceAtLeast(1)
    val intervalMs = storyboard.intervalMs.takeIf { it > 0 } ?: 1000
    val maxFrame = (storyboard.thumbCount - 1).coerceAtLeast(0)
    val frameIndex = (positionMs / intervalMs).toInt().coerceIn(0, maxFrame)
    val maxSheet = (storyboard.storyboardCount - 1).coerceAtLeast(0)
    val sheetIndex = (frameIndex / perSheet).coerceIn(0, maxSheet)
    val indexInSheet = frameIndex % perSheet
    val col = indexInSheet % columns
    val row = indexInSheet / columns
    val sheetUrl = remember(storyboard.templateUrl, sheetIndex) {
        storyboard.templateUrl.replace("\$M", sheetIndex.toString())
    }

    // Cache of already-decoded FULL sprite sheets (not individual tiles) for this scrub session.
    val sheetCache = remember { HashMap<String, android.graphics.Bitmap>() }
    var tile by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }

    LaunchedEffect(sheetUrl, col, row) {
        val sheet = sheetCache[sheetUrl] ?: runCatchingCancellable {
            withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(context).data(sheetUrl).build()
                val result = context.imageLoader.execute(request)
                val drawable = (result as? SuccessResult)?.drawable
                when (drawable) {
                    is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                    null -> null
                    else -> {
                        // Non-bitmap drawable (rare for a plain JPEG sprite sheet, but possible via a
                        // decoder that hands back e.g. a vector) — rasterize it ourselves.
                        val w = drawable.intrinsicWidth.coerceAtLeast(1)
                        val h = drawable.intrinsicHeight.coerceAtLeast(1)
                        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bmp)
                        drawable.setBounds(0, 0, w, h)
                        drawable.draw(canvas)
                        bmp
                    }
                }
            }
        }.getOrNull()
        if (sheet == null) {
            tile = null
            return@LaunchedEffect
        }
        sheetCache[sheetUrl] = sheet
        val cropped = withContext(Dispatchers.Default) {
            runCatching {
                val tw = storyboard.thumbWidth.coerceAtLeast(1).coerceAtMost(sheet.width)
                val th = storyboard.thumbHeight.coerceAtLeast(1).coerceAtMost(sheet.height)
                val x = (col * storyboard.thumbWidth).coerceIn(0, (sheet.width - tw).coerceAtLeast(0))
                val y = (row * storyboard.thumbHeight).coerceIn(0, (sheet.height - th).coerceAtLeast(0))
                android.graphics.Bitmap.createBitmap(sheet, x, y, tw, th)
            }.getOrNull()
        }
        tile = cropped?.asImageBitmap()
    }

    val aspect = if (storyboard.thumbWidth > 0 && storyboard.thumbHeight > 0) {
        storyboard.thumbWidth.toFloat() / storyboard.thumbHeight.toFloat()
    } else {
        16f / 9f
    }
    Box(
        modifier = modifier
            .width(width)
            .aspectRatio(aspect)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF101010))
            .border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = tile
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
        }
        Text(
            text = formatMsClock(positionMs),
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .background(Color(0x99000000))
                .padding(horizontal = 3.dp),
        )
    }
}

/** [ms] as "m:ss" (or "h:mm:ss" past an hour) — same shape as [formatDuration], but that one takes a
 *  raw seconds/HH:MM:SS string off a [SearchVideo], not a live millisecond position. */
private fun formatMsClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * A collapsible group inside the in-player settings (gear) dropdown. Shows [title] + the current
 * [summary] with an expand chevron; tapping toggles [content] (the options) inline. Collapsed by
 * default so the menu stays short and no group is buried behind another's options.
 */
@Composable
private fun SettingsSubmenu(
    title: String,
    summary: String,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    DropdownMenuItem(
        text = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (!expanded) {
                    Text(
                        summary,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                )
            }
        },
        onClick = { expanded = !expanded },
    )
    if (expanded) content()
}

/** One indented option row inside a [SettingsSubmenu]. */
@Composable
private fun SettingsSubmenuItem(label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = onClick,
        contentPadding = PaddingValues(start = 28.dp, end = 12.dp),
    )
}

/** Fail fast on a 403 so our fresh-URL re-fetch kicks in instead of ExoPlayer
 *  retrying a throttled URL for ~1 minute. Used by the PROGRESSIVE path, where
 *  the single URL is dead and re-fetching fresh URLs is the only recovery. */
private fun failFast403(): DefaultLoadErrorHandlingPolicy =
    object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val ex = loadErrorInfo.exception
            if (ex is HttpDataSource.InvalidResponseCodeException && ex.responseCode == 403) {
                return C.TIME_UNSET
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }
    }

/**
 * Used by the DASH path: a transient 403 (datacenter-IP throttle) is RETRIED
 * with backoff up to [maxRetries] — ExoPlayer pauses/buffers the affected
 * segment and resumes once the throttle clears, instead of restarting. The
 * DASH manifest URLs stay valid for hours, so retrying the same segment is the
 * right move. Only a persistent failure becomes fatal → our fresh-URL re-fetch.
 */
private fun retryThrottle403(maxRetries: Int): DefaultLoadErrorHandlingPolicy =
    object : DefaultLoadErrorHandlingPolicy() {
        override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val ex = loadErrorInfo.exception
            if (ex is HttpDataSource.InvalidResponseCodeException && ex.responseCode == 403) {
                return if (loadErrorInfo.errorCount <= maxRetries) {
                    minOf(loadErrorInfo.errorCount * 500L, 5000L)
                } else {
                    C.TIME_UNSET
                }
            }
            return super.getRetryDelayMsFor(loadErrorInfo)
        }

        override fun getMinimumLoadableRetryCount(dataType: Int): Int = maxRetries
    }

private fun okHttpFactory(userAgent: String): OkHttpDataSource.Factory {
    val okHttp = okhttp3.OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    return OkHttpDataSource.Factory(okHttp).setUserAgent(userAgent)
}

// Matches a WebVTT cue timing line, capturing the two timestamps and discarding any cue settings
// (align/position/line/size/vertical) that follow them, e.g.:
//   00:00:01.500 --> 00:00:04.000 align:start position:0%
private val VTT_CUE_TIMING_REGEX = Regex(
    """^(\d{1,2}:\d{2}(?::\d{2})?\.\d{3}\s*-->\s*\d{1,2}:\d{2}(?::\d{2})?\.\d{3})(?:\s+.*)?$""",
)

/**
 * YouTube's auto-generated (ASR) captions ship WebVTT cues with explicit `align:start position:0%`
 * settings, which render them pinned to the bottom-LEFT of the player instead of the usual centered
 * caption look. Strip any per-cue positioning the source set and replace it with our own: centered
 * horizontally, and anchored a little above the very bottom edge rather than flush against it.
 */
private fun recenterVttCues(vtt: String): String =
    vtt.lineSequence().joinToString("\n") { line ->
        val m = VTT_CUE_TIMING_REGEX.find(line)
        if (m != null) "${m.groupValues[1]} line:90% position:50% align:center" else line
    }

/**
 * Briefly hold a partial CPU wake lock (auto-released via timeout, never held indefinitely) to cover
 * a burst of async JS-bridge/network work — the gap between one video ending and the next one's
 * playback actually starting (autoplay-next), or between a mid-video playback error and the SABR
 * session rebuild / legacy-format fallback that recovers from it. All of those involve at least one
 * network round trip through the shared JS runtime with nothing else holding the CPU awake in between
 * (our own wake mode is tied to ACTIVE playback and lets go right as the error/end fires) — exactly
 * the gap where Doze can throttle the process while the screen is locked and stall or kill the fetch.
 * Without this, a mid-video error while locked (a stall watchdog trip, a SponsorBlock-skip hiccup, a
 * genuine network blip, …) can have BOTH the SABR retry and its legacy-format fallback die to the same
 * throttling, landing on "No playable format found" despite the video being fine moments earlier — see
 * fetchSabr's catch block and loadLegacyPlaybackIfNeeded in PlayerViewModel for that fallback chain.
 * Safe to call repeatedly — each call is an independent short-lived lock instance.
 */
private fun acquireBackgroundWorkWakeLock(context: android.content.Context) {
    runCatching {
        val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
        val lock = pm?.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "ytclient:background-work",
        )
        // Timeout acquire: released automatically after 20s regardless of what happens, so a failed/slow
        // fetch (or any bug in the follow-up chain) can never leak a held wake lock.
        lock?.acquire(20_000L)
    }
}

/** Distinct selectable video heights from the adaptive formats (desc). */
private fun dashQualityHeights(info: VideoInfo): List<Int> =
    info.formats
        .filter { it.hasVideo && !it.hasAudio && it.height in 1..4320 }
        .map { it.height }
        .distinct()
        .sortedDescending()

/**
 * Bound ExoPlayer's read-ahead so the SABR pump can pace requests near realtime. media3's default
 * LoadControl prioritises a huge (~128MB) byte target and ignores `maxBufferMs` — it reads ~50s+
 * ahead, loading the whole server-allowed burst (~60s) in a few seconds. That makes us "download"
 * at several× realtime, which trips YouTube's SABR rate cap (delivery stops dead at ~60s). A ~20s
 * buffer target with `prioritizeTimeOverSizeThresholds=true` keeps the loader ~20–30s ahead of real
 * playback, so the pump's read-ahead throttle engages and `playerTime` advances at ~realtime — which
 * is what keeps the server feeding. Values mirror PipePipe (the SABR stack we ported); the smaller
 * buffer is fine for the legacy DASH/progressive fallback paths too.
 */
private fun sabrLoadControl(): androidx.media3.exoplayer.LoadControl =
    DefaultLoadControl.Builder()
        // Deeper buffer so transient stalls (GC pauses, network jitter, decode hiccups) don't drain it
        // mid-play → fewer small buffering pauses. min 25s keeps a healthy floor; max 50s caps memory.
        // Start/resume after 5s (a cold SABR session fills slowly at first — pot mint + first segments —
        // so starting too early underran immediately). The pump (READAHEAD_CUSHION_MS) fills ~30s past
        // the read head, leap-filling up to this max; MAX_AHEAD_BYTES still guards against OOM.
        .setBufferDurationsMs(25_000, 50_000, 5_000, 5_000)
        .setPrioritizeTimeOverSizeThresholds(true)
        .build()

/**
 * Renderers for the player:
 *  - decoder fallback ON: if the primary (hardware) decoder fails to init/configure, drop to another
 *    (e.g. software) decoder instead of erroring out the whole playback.
 *  - a DEEPER AudioTrack PCM buffer (~0.5–2s vs the ~0.25–0.75s default): a GC "stop the world" pause
 *    of a few hundred ms would otherwise drain the small audio buffer and the sink would underrun
 *    (the audible micro-stutter). A larger buffer rides through those pauses.
 */
private fun sabrRenderersFactory(context: android.content.Context): androidx.media3.exoplayer.RenderersFactory =
    object : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
        init { setEnableDecoderFallback(true) }

        // Side-loaded subtitles (our WebVTT captions via SingleSampleMediaSource) are decoded at
        // render time; media3 1.4+ disables that by default and the TextRenderer throws
        // "Legacy decoding is disabled, can't handle text/vtt". DefaultRenderersFactory (1.10) has no
        // toggle for it, so enable it on the TextRenderer it builds.
        override fun buildTextRenderers(
            context: android.content.Context,
            output: androidx.media3.exoplayer.text.TextOutput,
            outputLooper: android.os.Looper,
            extensionRendererMode: Int,
            out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
        ) {
            super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
            out.forEach {
                if (it is androidx.media3.exoplayer.text.TextRenderer) {
                    it.experimentalSetLegacyDecodingEnabled(true)
                }
            }
        }

        override fun buildAudioSink(
            context: android.content.Context,
            enableFloatOutput: Boolean,
            enableAudioTrackPlaybackParams: Boolean,
        ): androidx.media3.exoplayer.audio.AudioSink =
            androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioTrackBufferSizeProvider(
                    androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(500_000)
                        .setMaxPcmBufferDurationUs(2_000_000)
                        .setPcmBufferMultiplicationFactor(8)
                        .build(),
                )
                .build()
    }

/**
 * Native SABR source: a chunk-framework [SabrMediaSource] over the [SabrSessionHolder]. A single
 * background pump drives the one SABR session and fills a shared segment cache; the audio + video
 * tracks are media3 ChunkSampleStreams that only READ that cache (so they never fight over the
 * session). Seeking is time→chunk-index based, so it lands. ExoPlayer keeps doing demux / decode /
 * A-V sync; segment fetches go through the session's own retry/redirect/reload machinery.
 */
private fun buildSabrSource(
    context: android.content.Context,
    holder: SabrSessionHolder,
    title: String?,
    channel: String?,
    caption: CaptionTrackInfo? = null,
): androidx.media3.exoplayer.source.MediaSource {
    // Metadata rides on the MediaItem so the MediaSession notification (lockscreen / shade / PiP)
    // shows title + channel + thumbnail — see PlaybackController.mediaItemFor.
    val item = PlaybackController.mediaItemFor("sabr://" + holder.videoId, holder.videoId, title, channel)
    val sabrSource = SabrMediaSource(item, holder, Localization.DEFAULT)
    if (caption == null || caption.url.isBlank()) return sabrSource

    // Side-load the selected closed-caption track (WebVTT) and merge it with the SABR audio/video.
    // caption.url is a `data:text/vtt;base64,…` URI built from the get_transcript text (the raw
    // timedtext URL returns empty bodies for many videos), so a DefaultDataSource (handles `data:`)
    // reads it. setTreatLoadErrorsAsEndOfStream → any glitch just yields no captions, never breaks A/V.
    val subtitle = MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(caption.url))
        .setMimeType(MimeTypes.TEXT_VTT)
        .setLanguage(caption.languageCode.ifBlank { "und" })
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
    val subtitleSource = androidx.media3.exoplayer.source.SingleSampleMediaSource.Factory(
        DefaultDataSource.Factory(context),
    )
        .setTreatLoadErrorsAsEndOfStream(true)
        .createMediaSource(subtitle, C.TIME_UNSET)
    return MergingMediaSource(sabrSource, subtitleSource)
}

// internal (was private): PlayerViewModel builds the NEXT video's source with this ahead of time and
// queues it onto the live player (the DASH locked-screen hand-off), so it must be the very same
// construction the screen would have done — same chunking, same 403 retry policy, same metadata.
internal fun buildDashSource(
    context: android.content.Context,
    manifestXml: String,
    userAgent: String,
    videoId: String,
    title: String?,
    channel: String?,
): androidx.media3.exoplayer.source.MediaSource {
    // googlevideo 403s large/open-ended ranges (incl. the big range ExoPlayer
    // requests when SEEKING into a single-segment representation), but serves
    // bounded ~1 MB ranges fine. So route https segment fetches through
    // ChunkedDataSource (bounded 1 MB sub-requests from any offset) — this is
    // what lets seeks into unbuffered regions load instead of 403-looping.
    // DefaultDataSource still handles the data: manifest URI via its built-in
    // scheme handler; only http(s) goes through the chunking upstream.
    val dsFactory = DefaultDataSource.Factory(
        context,
        ChunkedDataSource.Factory(okHttpFactory(userAgent)),
    )
    val b64 = android.util.Base64.encodeToString(
        manifestXml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP,
    )
    val uri = "data:application/dash+xml;base64,$b64"
    return DashMediaSource.Factory(dsFactory)
        // Retry throttled segments (buffer/pause) rather than restart.
        .setLoadErrorHandlingPolicy(retryThrottle403(6))
        // Not `MediaItem.fromUri(uri)`: that carries no metadata, so the lockscreen showed only the
        // app name whenever playback was on this path — see PlaybackController.mediaItemFor.
        .createMediaSource(PlaybackController.mediaItemFor(uri, videoId, title, channel))
}

private fun buildProgressiveSource(
    choice: PlaybackChoice,
    userAgent: String,
    videoId: String,
    title: String?,
    channel: String?,
): androidx.media3.exoplayer.source.MediaSource {
    // googlevideo caps range size (1 MB ok, full-file 403). ChunkedDataSource
    // issues sequential bounded ranges under one continuous stream.
    val dsFactory = ChunkedDataSource.Factory(okHttpFactory(userAgent))
    fun progressive(item: MediaItem) =
        ProgressiveMediaSource.Factory(dsFactory)
            .setLoadErrorHandlingPolicy(failFast403())
            .createMediaSource(item)

    // The metadata goes on the VIDEO source specifically: a MergingMediaSource reports its FIRST
    // child's MediaItem as its own, and that is what the media session reads (see
    // PlaybackController.mediaItemFor). The audio half is never consulted for it.
    val videoSrc = progressive(PlaybackController.mediaItemFor(choice.videoUrl, videoId, title, channel))
    return if (choice.audioUrl != null) {
        MergingMediaSource(videoSrc, progressive(MediaItem.fromUri(choice.audioUrl)))
    } else {
        videoSrc
    }
}

/** Hides the system bars (immersive) while [enabled]; restores them otherwise. */
@Composable
private fun ImmersiveMode(enabled: Boolean) {
    val activity = LocalContext.current as? Activity ?: return
    DisposableEffect(enabled) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun LoadingSurface(fullscreen: Boolean = false, status: String = "Loading…") {
    Box(
        if (fullscreen) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black)
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LinearProgressIndicator(
                modifier = Modifier.width(72.dp).height(2.dp).clip(RoundedCornerShape(1.dp)),
                color = Color.White.copy(alpha = 0.85f),
                trackColor = Color.White.copy(alpha = 0.2f),
            )
            Spacer(Modifier.height(10.dp))
            Text(status, color = Color.White.copy(alpha = 0.55f), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ErrorSurface(message: String, fullscreen: Boolean = false, onRetry: () -> Unit) {
    Box(
        if (fullscreen) {
            Modifier.fillMaxSize().background(Color.Black)
        } else {
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.DarkGray)
        },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Failed to load", color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(4.dp))
            Text(message, color = Color.White, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}
