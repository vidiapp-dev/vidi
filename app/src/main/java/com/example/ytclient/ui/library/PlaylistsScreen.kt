package com.example.ytclient.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.ytclient.App
import com.example.ytclient.core.local.Playlist
import com.example.ytclient.core.local.toSearchVideo
import com.example.ytclient.ui.components.VideoRow
import com.example.ytclient.youtube.models.SearchVideo

@Composable
fun PlaylistsScreen(
    onPlayPlaylist: (List<SearchVideo>, Int) -> Unit,
) {
    val library = (LocalContext.current.applicationContext as App).library
    val playlists by library.playlists.collectAsState()

    var openId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    val open = playlists.firstOrNull { it.id == openId }
    if (open != null) {
        BackHandler { openId = null }
        PlaylistDetail(
            playlist = open,
            onBack = { openId = null },
            onPlayAt = { index ->
                onPlayPlaylist(open.videos.map { it.toSearchVideo() }, index)
            },
            onRemoveVideo = { videoId -> library.removeFromPlaylist(open.id, videoId) },
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Playlists", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            IconButton(onClick = { creating = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New playlist")
            }
        }

        LazyColumn {
            items(playlists, key = { it.id }) { pl ->
                PlaylistRow(
                    playlist = pl,
                    onClick = { openId = pl.id },
                    onDelete = if (pl.isWatchLater) null else ({ library.removePlaylist(pl.id) }),
                )
            }
        }
    }

    if (creating) {
        CreatePlaylistDialog(
            onCreate = { name -> library.createPlaylist(name); creating = false },
            onDismiss = { creating = false },
        )
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlaylistPlay, contentDescription = null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${playlist.videos.size} video${if (playlist.videos.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onDelete != null) {
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete playlist") }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: Playlist,
    onBack: () -> Unit,
    onPlayAt: (Int) -> Unit,
    onRemoveVideo: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(
                playlist.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (playlist.videos.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "Empty — add videos from the feed.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn {
                itemsIndexed(playlist.videos, key = { _, it -> it.id }) { index, v ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        VideoRow(
                            video = v.toSearchVideo(),
                            onClick = { onPlayAt(index) },
                            modifier = Modifier.weight(1f),
                            showAddToPlaylist = false,
                        )
                        IconButton(onClick = { onRemoveVideo(v.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CreatePlaylistDialog(onCreate: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Playlist name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
