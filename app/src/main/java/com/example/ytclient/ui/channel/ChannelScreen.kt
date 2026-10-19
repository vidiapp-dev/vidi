@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ytclient.ui.channel

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ytclient.App
import com.example.ytclient.core.models.UiState
import com.example.ytclient.ui.components.VideoRow
import com.example.ytclient.youtube.models.ChannelInfo
import com.example.ytclient.youtube.models.ChannelPlaylist
import com.example.ytclient.youtube.models.SearchVideo
import androidx.compose.ui.platform.LocalContext

private enum class ChannelTab { VIDEOS, PLAYLISTS }

@Composable
fun ChannelScreen(
    channelId: String,
    onBack: () -> Unit,
    onVideoClick: (SearchVideo) -> Unit,
    onPlayPlaylist: (List<SearchVideo>, Int) -> Unit,
    viewModel: ChannelViewModel = viewModel(),
) {
    LaunchedEffect(channelId) { viewModel.load(channelId) }

    val info by viewModel.info.collectAsState()
    val videos by viewModel.videos.collectAsState()
    val playlists by viewModel.playlists.collectAsState()
    val subscribed by viewModel.subscribedChannelIds.collectAsState()
    val openedPlaylist by viewModel.openedPlaylist.collectAsState()

    var mode by remember { mutableStateOf(ChannelTab.VIDEOS) }

    // A channel playlist is open → show its numbered video list; back returns to the channel.
    openedPlaylist?.let { plState ->
        BackHandler { viewModel.closePlaylist() }
        ChannelPlaylistDetail(
            state = plState,
            onBack = { viewModel.closePlaylist() },
            onPlayAt = { vids, index -> onPlayPlaylist(vids, index) },
        )
        return
    }

    LazyColumn(modifier = Modifier.fillMaxWidth()) {
        item {
            ChannelHeader(
                info = (info as? UiState.Success)?.data,
                isSubscribed = subscribed.contains(channelId),
                onBack = onBack,
                onToggleSubscribe = { viewModel.toggleSubscribe() },
            )
        }
        item {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = mode == ChannelTab.VIDEOS,
                    onClick = { mode = ChannelTab.VIDEOS },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text("Videos") }
                SegmentedButton(
                    selected = mode == ChannelTab.PLAYLISTS,
                    onClick = { mode = ChannelTab.PLAYLISTS },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text("Playlists") }
            }
        }

        when (mode) {
            ChannelTab.VIDEOS -> when (val v = videos) {
                is UiState.Idle, is UiState.Loading -> item { LoadingItem() }
                is UiState.Error -> item { ErrorItem(v.message) }
                is UiState.Success -> items(v.data, key = { it.id }) { video ->
                    VideoRow(
                        video = video,
                        onClick = { onVideoClick(video) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
            ChannelTab.PLAYLISTS ->
                if (playlists.isEmpty()) item { EmptyItem("No playlists") }
                else items(playlists, key = { it.id }) { pl ->
                    ChannelPlaylistRow(pl = pl, onClick = { viewModel.openPlaylist(pl.id) })
                }
        }
    }
}

@Composable
private fun ChannelHeader(
    info: ChannelInfo?,
    isSubscribed: Boolean,
    onBack: () -> Unit,
    onToggleSubscribe: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 4.5f).background(Color(0xFF202020))) {
            if (info?.banner != null) {
                AsyncImage(
                    model = info.banner,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 4.5f),
                )
            }
            IconButton(onClick = onBack, modifier = Modifier.align(Alignment.TopStart)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = info?.avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF303030)),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info?.name?.ifBlank { "Channel" } ?: "Loading…",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!info?.subscriberText.isNullOrBlank()) {
                    Text(
                        info!!.subscriberText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Box(modifier = Modifier.padding(horizontal = 12.dp)) {
            if (isSubscribed) {
                OutlinedButton(onClick = onToggleSubscribe) { Text("Subscribed") }
            } else {
                Button(onClick = onToggleSubscribe) { Text("Subscribe") }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ChannelPlaylistDetail(
    state: UiState<List<SearchVideo>>,
    onBack: () -> Unit,
    onPlayAt: (List<SearchVideo>, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("Playlist", style = MaterialTheme.typography.titleLarge)
        }
        when (state) {
            is UiState.Idle, is UiState.Loading -> LoadingItem()
            is UiState.Error -> ErrorItem(state.message)
            is UiState.Success -> {
                val videos = state.data
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(videos, key = { _, it -> it.id }) { index, v ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(24.dp),
                            )
                            VideoRow(
                                video = v,
                                onClick = { onPlayAt(videos, index) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelPlaylistRow(pl: ChannelPlaylist, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.width(120.dp).aspectRatio(16f / 9f).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).background(Color(0xFF202020))) {
            if (pl.thumbnail != null) {
                AsyncImage(
                    model = pl.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
            Icon(
                Icons.Filled.PlaylistPlay,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(pl.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (pl.videoCount.isNotBlank()) {
                Text(
                    pl.videoCount,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun LoadingItem() {
    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorItem(message: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text("Couldn't load: $message", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyItem(text: String) {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
