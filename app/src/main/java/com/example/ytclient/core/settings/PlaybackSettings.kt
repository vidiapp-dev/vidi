package com.example.ytclient.core.settings

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which transport the player should use to actually stream a video.
 *
 *  - [SABR]: YouTube's own adaptive transport (the default, and what every "normal" open uses).
 *    Quality adapts per-segment, but every session is attested: the streaming server answers each
 *    response with a StreamProtectionStatus, and it can decide mid-video that our PO token is no
 *    longer welcome (status=3), which no amount of rebuilding fixes.
 *  - [DASH]: the direct-URL / DASH-manifest path (`getLegacyPlayback`). No SABR session, therefore
 *    no attestation and nothing that can be "rejected" — at the cost of a fixed, up-front format
 *    selection rather than SABR's per-segment adaptation.
 *
 * DASH is deliberately NOT modelled as "the thing we degrade into". It is a first-class choice a
 * user can make up front, which is why it lives in settings rather than only being reachable from a
 * failure path.
 */
enum class PlaybackMode {
    SABR,
    DASH,
}

/**
 * The user's persisted streaming-transport choice ([PlaybackMode]), as a single process-wide
 * source of truth. Owned as a `by lazy` singleton by [com.example.ytclient.App], alongside the
 * bridge / library / PO-token provider.
 *
 * WHY ITS OWN SINGLETON instead of another key in `PlayerViewModel`'s `player_prefs` (where
 * preferred height, audio language and the SponsorBlock toggle live): both of the readers of this
 * setting sit OUTSIDE the lifetime of any particular player.
 *
 *  - The Settings tab has no relationship to a player at all — it is composed while no video is
 *    open and must be able to read AND write the choice without instantiating a PlayerViewModel
 *    (which would spin up a whole playback-scoped object graph, queue state included, just to flip
 *    one boolean-ish preference).
 *  - `PlayerViewModel` itself needs it at video-LOAD time, in `loadInternal`, i.e. before any
 *    ExoPlayer/SABR session exists — it is the input that decides whether a SABR session is ever
 *    built for this video in the first place, so it cannot be something a session carries.
 *
 * Keeping it in its own SharedPreferences file (`playback_settings`) rather than sharing
 * `player_prefs` also means the two never have to agree on ownership: the player screen owns
 * per-playback preferences, this owns the transport decision, and a future settings item (or a
 * debug switch) can join this file without reaching into player state.
 *
 * The [mode] flow is the single source of truth: [setMode] updates it synchronously — so a Settings
 * radio button re-renders immediately, and a video opened a moment later already sees the new value
 * — while the disk write goes out via the usual `apply()` (async commit, no fsync on the caller).
 */
class PlaybackSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(readPersistedMode())

    /** The active transport choice. Defaults to [PlaybackMode.SABR] — today's behavior unchanged. */
    val mode: StateFlow<PlaybackMode> = _mode.asStateFlow()

    /** Persist [mode] and publish it to every observer (Settings screen, next video load). */
    fun setMode(mode: PlaybackMode) {
        if (_mode.value == mode) return
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    /**
     * Read the stored choice, tolerating anything that isn't a name we still recognise (a value
     * written by an older/newer build, or a hand-edited prefs file) by falling back to the default
     * rather than throwing at construction time — this object is built inside [com.example.ytclient.App]'s
     * lazy initialisation, where an exception would take the whole app down on launch.
     */
    private fun readPersistedMode(): PlaybackMode {
        val stored = prefs.getString(KEY_MODE, null) ?: return PlaybackMode.SABR
        return PlaybackMode.entries.firstOrNull { it.name == stored } ?: PlaybackMode.SABR
    }

    private companion object {
        const val PREFS = "playback_settings"
        const val KEY_MODE = "mode"
    }
}
