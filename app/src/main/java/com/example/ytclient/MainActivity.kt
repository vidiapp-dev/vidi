package com.example.ytclient

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.example.ytclient.playback.PiPActionReceiver
import com.example.ytclient.playback.PlaybackController
import kotlinx.coroutines.launch
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.example.ytclient.ui.channel.ChannelScreen
import com.example.ytclient.ui.home.HomeScreen
import com.example.ytclient.ui.library.HistoryScreen
import com.example.ytclient.ui.library.PlaylistsScreen
import com.example.ytclient.ui.library.SubscriptionsScreen
import com.example.ytclient.ui.player.PlayerScreen
import com.example.ytclient.ui.settings.SettingsScreen
import com.example.ytclient.ui.theme.YtClientTheme
import com.example.ytclient.youtube.models.SearchVideo

class MainActivity : ComponentActivity() {

    // Whether we're currently in a PiP window (drives a video-only layout).
    private val inPip = mutableStateOf(false)
    // Whether a video is currently open/playing (gates entering PiP on leave).
    private var videoIsOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // App defaults to portrait; PlayerScreen unlocks the sensor while a video
        // is open so it can rotate to landscape for fullscreen, then restores this.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Android 13+: ask for notification permission so the media-style notification can show.
        // Playback + PiP still work if denied — only the shade/lockscreen notification is gated.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        // While in PiP, flip the play/pause action icon to match the live playback state.
        lifecycleScope.launch {
            PlaybackController.isPlaying.collect {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isInPictureInPictureMode) {
                    runCatching { setPictureInPictureParams(pipParams()) }
                }
            }
        }

        setContent {
            YtClientTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(
                        inPip = inPip.value,
                        onVideoOpenChange = { videoIsOpen = it },
                    )
                }
            }
        }
    }

    /**
     * Commit any coalesced library write before the app goes to the background.
     *
     * [com.example.ytclient.core.local.LibraryStore] batches watch-history writes (the resume
     * position is updated every ~5 s during playback, and each write used to re-encode the whole
     * history list), so backgrounding mid-video could otherwise leave the latest position sitting
     * in a pending timer. Flushing here means the only way to lose position is a process kill
     * while the app is still in the foreground.
     */
    override fun onStop() {
        super.onStop()
        (application as App).library.flushPendingWrites()
    }

    // Pressing Home (or swiping away) while a video plays → enter Picture-in-Picture.
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        enterPip()
    }

    /**
     * Enter Picture-in-Picture now, if a video is open and the device supports it. Used both when the
     * user leaves the app ([onUserLeaveHint]) and when they press/swipe back out of the player screen
     * (PlayerScreen's BackHandler calls this directly) — same destination either way: the video keeps
     * playing in a PiP window instead of the player tearing down. Returns whether PiP was actually
     * entered, so a caller like back navigation can fall back to its normal behavior (closing the
     * player) when it wasn't — old API level, PiP unsupported on the device, no video open, or the
     * system otherwise refused.
     */
    fun enterPip(): Boolean {
        if (!videoIsOpen ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            !packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        ) {
            return false
        }
        return runCatching { enterPictureInPictureMode(pipParams()) }.getOrDefault(false)
    }

    /**
     * PiP params with the current transport actions (play/pause reflecting live state, + Next) and the
     * ACTUAL playing video's aspect ratio (see [PlaybackController.aspectRatio]) — a hardcoded 16:9
     * would letterbox/crop a vertical or otherwise non-16:9 video in the PiP window.
     */
    private fun pipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder().setAspectRatio(PlaybackController.aspectRatio.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setActions(buildPipActions(PlaybackController.isPlaying.value))
        }
        return builder.build()
    }

    private fun buildPipActions(isPlaying: Boolean): List<RemoteAction> {
        val toggle = RemoteAction(
            Icon.createWithResource(
                this,
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            ),
            if (isPlaying) "Pause" else "Play",
            "Play or pause",
            piBroadcast(PiPActionReceiver.ACTION_TOGGLE, 1),
        )
        val next = RemoteAction(
            Icon.createWithResource(this, android.R.drawable.ic_media_next),
            "Next",
            "Play next",
            piBroadcast(PiPActionReceiver.ACTION_NEXT, 2),
        )
        return listOf(toggle, next)
    }

    private fun piBroadcast(action: String, requestCode: Int): PendingIntent {
        // EXPLICIT intent (targets the receiver component directly): a manifest receiver with no
        // intent-filter never receives an implicit (action-only) broadcast, so the PiP buttons did
        // nothing. Explicit delivery works regardless of filter and is correct for exported=false.
        val intent = Intent(this, PiPActionReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip.value = isInPictureInPictureMode
        // Closing the PiP window (the X) stops the activity BEFORE this callback fires, so the
        // lifecycle is below STARTED; expanding back to fullscreen leaves it STARTED/RESUMED. On a
        // close, fully finish the app: destroying the activity disposes the player Composable, which
        // releases the ExoPlayer + MediaSession (and stops the foreground service) — otherwise
        // playback would keep running headless via the media notification.
        if (!isInPictureInPictureMode &&
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            finishAndRemoveTask()
        }
    }
}

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Filled.Home),
    SUBSCRIPTIONS("Subs", Icons.Filled.Subscriptions),
    HISTORY("History", Icons.Filled.History),
    PLAYLISTS("Playlists", Icons.Filled.PlaylistPlay),
    // Last on purpose: the four before it are content destinations the viewer moves between
    // constantly, this one is a place you visit occasionally and come back out of.
    SETTINGS("Settings", Icons.Filled.Settings),
}

/** A request to start playback: a queue of [videos] beginning at [index]. */
private data class PlaybackRequest(
    val videos: List<SearchVideo>,
    val index: Int,
    val autoplayRelated: Boolean,
)

// Distance (px) a full 0→1 drag has to travel — roughly a bit over half a typical portrait screen's
// height, so the gesture neither feels twitchy (too short) nor like it never gets there (too long).
private const val COLLAPSE_DRAG_RANGE_PX = 900f

// A release faster than this (px/s) always finishes the gesture in the direction it was moving, even
// if released before the halfway point — a firm flick reads as a decision, not a hesitant partial drag.
private const val COLLAPSE_FLING_VELOCITY_PX_S = 1000f

// How close the mini-player card may get to the left/right/top edges of the viewport. Also its
// resting distance from the right edge, so the default dock is unchanged from before it could move.
private val MINI_MARGIN = 12.dp

// Clearance kept below the card. Larger than [MINI_MARGIN] because the bottom edge has the
// navigation bar under it — the card docks (and clamps) above that rather than over it.
private val MINI_BOTTOM_INSET = 72.dp

@Composable
private fun AppRoot(inPip: Boolean, onVideoOpenChange: (Boolean) -> Unit) {
    var playing by remember { mutableStateOf<PlaybackRequest?>(null) }
    // Progress toward the in-app mini-player card, 0f (fully expanded) .. 1f (fully mini). Animated
    // (not a plain Float/Boolean) so a drag that's released mid-gesture — or a tap-to-expand — eases
    // to its resting value instead of snapping. PlayerScreen reports live drag deltas via
    // onCollapseDrag (see below) from its own gesture detector on the video surface; this is what
    // drives BOTH the container's size/position here AND PlayerScreen's own expanded/bare-video switch,
    // so the two never disagree about how collapsed things are. Distinct from system PiP (the `inPip`
    // param, driven by MainActivity) — this is a purely in-Compose display mode so browsing stays
    // possible underneath.
    val collapse = remember { Animatable(0f) }
    val collapseScope = rememberCoroutineScope()
    var selectedChannel by remember { mutableStateOf<String?>(null) }
    var tab by remember { mutableStateOf(Tab.HOME) }

    // Tell the Activity whether a video is open, so it can decide to enter PiP on leave. A freshly
    // opened/changed video always starts expanded (mini is a display mode you drag/tap into afterwards).
    LaunchedEffect(playing) {
        onVideoOpenChange(playing != null)
        collapse.snapTo(0f)
    }

    fun playVideo(v: SearchVideo) {
        playing = PlaybackRequest(listOf(v), 0, autoplayRelated = true)
    }
    fun playPlaylist(videos: List<SearchVideo>, index: Int) {
        if (videos.isNotEmpty()) {
            playing = PlaybackRequest(videos, index.coerceIn(0, videos.lastIndex), autoplayRelated = false)
        }
    }
    // Live drag delta (px, positive = finger moved down) from PlayerScreen's collapse gesture, applied
    // immediately so the container (below) tracks the finger with no lag.
    fun onCollapseDrag(deltaPx: Float) {
        collapseScope.launch {
            collapse.snapTo((collapse.value + deltaPx / COLLAPSE_DRAG_RANGE_PX).coerceIn(0f, 1f))
        }
    }
    // Drag released — settle at whichever end is closer, unless a fast fling overrides that.
    fun onCollapseDragEnd(velocityPxPerSec: Float) {
        val target = when {
            velocityPxPerSec > COLLAPSE_FLING_VELOCITY_PX_S -> 1f
            velocityPxPerSec < -COLLAPSE_FLING_VELOCITY_PX_S -> 0f
            collapse.value > 0.5f -> 1f
            else -> 0f
        }
        collapseScope.launch { collapse.animateTo(target) }
    }

    // System back closes the channel page first, returning to the tabs. Only armed while there's no
    // video open — PlayerScreen installs its own BackHandler (fullscreen-exit / comments-close / back)
    // while it's showing, expanded or mini.
    if (selectedChannel != null && playing == null) {
        BackHandler { selectedChannel = null }
    }

    // The tabs/channel page are ALWAYS composed now (not just while no video is open) — the mini-player
    // needs something live to float over and stay interactive underneath. The player is layered on top
    // in a second Box, sized/positioned by interpolating between full-screen and a small corner card as
    // `collapse` animates — expanded, it fills the screen and hides all of this (same as before); mini,
    // it shrinks to a small corner card docked above the bottom nav, leaving the Scaffold below fully
    // visible and usable. BoxWithConstraints (not a plain Box) so the interpolation below knows the
    // actual available width/height to lerp from.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = selectedChannel == null && tab == t,
                            onClick = { tab = t; selectedChannel = null },
                            icon = { Icon(t.icon, contentDescription = t.label) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding)) {
                if (selectedChannel != null) {
                    ChannelScreen(
                        channelId = selectedChannel!!,
                        onBack = { selectedChannel = null },
                        onVideoClick = { playVideo(it) },
                        onPlayPlaylist = { videos, index -> playPlaylist(videos, index) },
                    )
                } else {
                    when (tab) {
                        Tab.HOME -> HomeScreen(
                            onVideoClick = { playVideo(it) },
                            onOpenChannel = { selectedChannel = it },
                        )
                        Tab.SUBSCRIPTIONS -> SubscriptionsScreen(
                            onVideoClick = { playVideo(it) },
                            onOpenChannel = { selectedChannel = it },
                        )
                        Tab.HISTORY -> HistoryScreen(onVideoClick = { playVideo(it) })
                        Tab.PLAYLISTS -> PlaylistsScreen(
                            onPlayPlaylist = { videos, index -> playPlaylist(videos, index) },
                        )
                        // No callbacks: nothing here opens a video or a channel, and the settings it
                        // writes are read straight off App's singletons by whoever needs them (see
                        // SettingsViewModel), so there is nothing to hoist up to this level.
                        Tab.SETTINGS -> SettingsScreen()
                    }
                }
            }
        }

        playing?.let { req ->
            val f = collapse.value
            val mini = f >= 0.5f
            // Sized close to Android's own system PiP default window (roughly 220dp wide at 16:9)
            // rather than the smaller card this started at, so the in-app mini player doesn't look
            // noticeably tinier than the out-of-app PiP window it's meant to mirror.
            val miniWidth = 220.dp
            val miniHeight = 124.dp
            val density = LocalDensity.current

            // Where the card is ALLOWED to sit: fully inside the viewport, inset by the margins
            // above. maxOf keeps the range non-empty — coerceIn throws when min > max, which a
            // viewport too small to hold the card at all would otherwise produce.
            val minX = MINI_MARGIN
            val maxX = maxOf(MINI_MARGIN, maxWidth - miniWidth - MINI_MARGIN)
            val minY = MINI_MARGIN
            val maxY = maxOf(MINI_MARGIN, maxHeight - miniHeight - MINI_BOTTOM_INSET)

            // Null until the user actually drags the card, so it docks bottom-right exactly where it
            // always did, then stays wherever they put it. Remembered INSIDE `playing?.let` on
            // purpose: the position survives collapse/expand cycles (same card being moved around)
            // but a different video tears this branch down and rebuilds it, re-docking the card
            // rather than reopening in some corner the user left it in during an unrelated video.
            var miniUserX by remember { mutableStateOf<Dp?>(null) }
            var miniUserY by remember { mutableStateOf<Dp?>(null) }
            val miniOffsetX = (miniUserX ?: maxX).coerceIn(minX, maxX)
            val miniOffsetY = (miniUserY ?: maxY).coerceIn(minY, maxY)

            // Drag deltas from the mini card (see PlayerScreen's mini branch). Clamped on every
            // step, so a fast drag toward an edge stops AT the edge instead of carrying the card
            // partly off-screen. Reads the backing state rather than the captured `miniOffsetX`
            // above so a stale closure can't rewind the position mid-gesture.
            fun onMiniDrag(dxPx: Float, dyPx: Float) {
                with(density) {
                    miniUserX = ((miniUserX ?: maxX) + dxPx.toDp()).coerceIn(minX, maxX)
                    miniUserY = ((miniUserY ?: maxY) + dyPx.toDp()).coerceIn(minY, maxY)
                }
            }

            // The REAL Compose-measured size/position is a hard, DISCRETE jump between exactly
            // full-screen and the mini card's dimensions — never a continuous interpolation of it.
            // This is the actual fix for the mini-player's "video stuck at a stale, corner-pinned
            // size" bug: continuously animating this Box's real size on every drag frame was resizing
            // the embedded PlayerView's SurfaceView that many times too, and SurfaceView's video buffer
            // resizes asynchronously from the View's own layout pass — it fell behind and got stuck. A
            // single hard jump (exactly like the fullscreen toggle already did safely, long before any
            // of this) never trips that: the surface only ever actually resizes once per crossing.
            val realWidth = if (mini) miniWidth else maxWidth
            val realHeight = if (mini) miniHeight else maxHeight
            val realOffsetX = if (mini) miniOffsetX else 0.dp
            val realOffsetY = if (mini) miniOffsetY else 0.dp

            // The SMOOTH, continuous "tracks your finger" appearance is a graphicsLayer transform on
            // top of that fixed real geometry instead — a draw-time-only property that never touches
            // layout/measure, so it animates freely without going anywhere near the resize problem
            // above. scale/translation are computed so the (real, currently fixed-size) box RENDERS as
            // the exact rect the old continuous lerp described — and because rendered size is always
            // realSize * (visualSize / realSize) = visualSize regardless of which regime realSize is
            // currently in, the visual result stays perfectly continuous straight through the instant
            // `mini` flips and the real jump happens.
            val visualWidth = lerp(maxWidth, miniWidth, f)
            val visualOffsetX = lerp(0.dp, miniOffsetX, f)
            val visualOffsetY = lerp(0.dp, miniOffsetY, f)
            val scale = visualWidth / realWidth
            val translationXPx = with(density) { (visualOffsetX - realOffsetX).toPx() }
            val translationYPx = with(density) { (visualOffsetY - realOffsetY).toPx() }

            // Only round the corners once actually at rest at one of the two ends (not mid-drag or
            // mid-settle-animation): in between, the render can be LARGER than the real (just-jumped)
            // box — see the scale comment above — and clipping would cut that overflow off at the
            // smaller box's edge. Square corners for the sub-second a settle animation is still running
            // isn't something anyone will actually notice.
            val settled = f <= 0f || f >= 1f
            Box(
                modifier = Modifier
                    .offset(x = realOffsetX, y = realOffsetY)
                    .size(width = realWidth, height = realHeight)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translationXPx
                        translationY = translationYPx
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .then(if (settled && mini) Modifier.clip(RoundedCornerShape(8.dp)) else Modifier),
            ) {
                PlayerScreen(
                    initialVideos = req.videos,
                    initialIndex = req.index,
                    autoplayRelated = req.autoplayRelated,
                    onBack = { playing = null },
                    onOpenChannel = { cid -> playing = null; selectedChannel = cid },
                    inPip = inPip,
                    collapseFraction = f,
                    onCollapseDrag = ::onCollapseDrag,
                    onCollapseDragEnd = ::onCollapseDragEnd,
                    onMiniDrag = ::onMiniDrag,
                    onExpand = { collapseScope.launch { collapse.animateTo(0f) } },
                    onMinimize = { collapseScope.launch { collapse.animateTo(1f) } },
                )
            }
        }
    }
}
