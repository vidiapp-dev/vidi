package com.example.ytclient.ui.channel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ytclient.App
import com.example.ytclient.core.coroutines.runCatchingCancellable
import com.example.ytclient.core.models.UiState
import com.example.ytclient.youtube.models.ChannelInfo
import com.example.ytclient.youtube.models.ChannelPlaylist
import com.example.ytclient.youtube.models.SearchVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Channel page: header + latest uploads (new→old) + the channel's playlists. */
class ChannelViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val bridge = app.youtubeBridge
    private val library = app.library

    private val _info = MutableStateFlow<UiState<ChannelInfo>>(UiState.Idle)
    val info: StateFlow<UiState<ChannelInfo>> = _info

    private val _videos = MutableStateFlow<UiState<List<SearchVideo>>>(UiState.Idle)
    val videos: StateFlow<UiState<List<SearchVideo>>> = _videos

    private val _playlists = MutableStateFlow<List<ChannelPlaylist>>(emptyList())
    val playlists: StateFlow<List<ChannelPlaylist>> = _playlists

    val subscribedChannelIds: StateFlow<Set<String>> = library.subscribedChannelIds

    // The opened channel-playlist's videos (numbered detail view). null = no playlist open.
    private val _openedPlaylist = MutableStateFlow<UiState<List<SearchVideo>>?>(null)
    val openedPlaylist: StateFlow<UiState<List<SearchVideo>>?> = _openedPlaylist

    private var loadedId: String? = null

    fun openPlaylist(playlistId: String) {
        _openedPlaylist.value = UiState.Loading
        viewModelScope.launch(Dispatchers.IO) {
            _openedPlaylist.value = runCatchingCancellable { bridge.getPlaylistVideos(playlistId).videos }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Failed to load playlist") },
            )
        }
    }

    fun closePlaylist() {
        _openedPlaylist.value = null
    }

    fun load(channelId: String) {
        if (loadedId == channelId) return
        loadedId = channelId
        _info.value = UiState.Loading
        _videos.value = UiState.Loading
        _playlists.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            val infoRes = runCatchingCancellable { bridge.getChannelInfo(channelId) }
            _info.value = infoRes.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Failed to load channel") },
            )
            // Backfill the stored subscription's name/avatar now that we have them.
            infoRes.getOrNull()?.let { ci ->
                library.updateSubscriptionMeta(channelId, ci.name, ci.avatar)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            _videos.value = runCatchingCancellable { bridge.getChannelVideos(channelId).videos }.fold(
                onSuccess = { UiState.Success(it) },
                onFailure = { UiState.Error(it.message ?: "Failed to load videos") },
            )
        }
        viewModelScope.launch(Dispatchers.IO) {
            _playlists.value = runCatchingCancellable { bridge.getChannelPlaylists(channelId).playlists }.getOrElse { emptyList() }
        }
    }

    fun toggleSubscribe() {
        val ci = (_info.value as? UiState.Success)?.data ?: return
        library.toggleSubscribe(ci.id, ci.name, ci.avatar)
    }
}
