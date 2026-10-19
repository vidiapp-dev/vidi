package com.example.ytclient.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.ytclient.core.local.LibraryStore
import com.example.ytclient.core.local.Playlist
import com.example.ytclient.core.local.toPlaylistVideo
import com.example.ytclient.youtube.models.SearchVideo

/** Pick an existing playlist (or create one on the fly) to add [video] to. */
@Composable
fun AddToPlaylistDialog(
    video: SearchVideo,
    playlists: List<Playlist>,
    library: LibraryStore,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }

    fun add(playlistId: String) {
        library.addToPlaylist(playlistId, video.toPlaylistVideo(System.currentTimeMillis()))
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save to…") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                playlists.forEach { pl ->
                    val already = pl.videos.any { it.id == video.id }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !already) { add(pl.id) }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.PlaylistPlay, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            pl.name + if (already) "  ✓" else "",
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (already) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                if (creating) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("New playlist name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (newName.isNotBlank()) add(library.createPlaylist(newName.trim()).id)
                        }),
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { creating = true }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text("New playlist", style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            if (creating) {
                TextButton(
                    onClick = { if (newName.isNotBlank()) add(library.createPlaylist(newName.trim()).id) },
                    enabled = newName.isNotBlank(),
                ) { Text("Create") }
            } else {
                TextButton(onClick = onDismiss) { Text("Done") }
            }
        },
        dismissButton = {
            if (creating) TextButton(onClick = { creating = false }) { Text("Cancel") }
        },
    )
}
