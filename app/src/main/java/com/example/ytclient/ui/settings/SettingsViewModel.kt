package com.example.ytclient.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.ytclient.App
import com.example.ytclient.core.settings.PlaybackMode
import kotlinx.coroutines.flow.StateFlow

/**
 * Settings tab. Intentionally thin: every setting it exposes is already owned, persisted and made
 * observable by a process-wide singleton on [App], so this holds no state of its own — it is purely
 * the Compose-side handle onto those singletons.
 *
 * In particular [playbackMode] is re-exposed straight off
 * [com.example.ytclient.core.settings.PlaybackSettings] rather than mirrored into a local
 * MutableStateFlow. Mirroring would create a second copy of a value that
 * [com.example.ytclient.ui.player.PlayerViewModel] reads independently at video-load time, and the
 * two would then have to be kept in sync — the exact single-source-of-truth problem the settings
 * singleton exists to avoid. Writes go the same way: [setPlaybackMode] delegates, so the player
 * sees the new value the instant the radio button is tapped, with no ViewModel-to-ViewModel path.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val playbackSettings = app.playbackSettings

    /** The active streaming transport; the radio group renders directly off this. */
    val playbackMode: StateFlow<PlaybackMode> = playbackSettings.mode

    /** Persist the viewer's transport choice. Takes effect on the NEXT video load, not mid-video. */
    fun setPlaybackMode(mode: PlaybackMode) {
        playbackSettings.setMode(mode)
    }
}
