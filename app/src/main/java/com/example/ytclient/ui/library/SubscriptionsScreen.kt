@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.example.ytclient.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.ytclient.core.local.Subscription
import com.example.ytclient.core.models.UiState
import com.example.ytclient.ui.components.VideoList
import com.example.ytclient.youtube.models.SearchVideo

private enum class SubMode { VIDEOS, CHANNELS }

@Composable
fun SubscriptionsScreen(
    onVideoClick: (SearchVideo) -> Unit,
    onOpenChannel: (String) -> Unit,
    viewModel: SubscriptionsViewModel = viewModel(),
) {
    val subs by viewModel.subscriptions.collectAsState()
    val videos by viewModel.videos.collectAsState()
    var mode by remember { mutableStateOf(SubMode.VIDEOS) }

    LaunchedEffect(mode, subs) {
        if (mode == SubMode.VIDEOS) viewModel.loadVideosIfNeeded()
    }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Text(
            "Subscriptions",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = mode == SubMode.VIDEOS,
                onClick = { mode = SubMode.VIDEOS },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) { Text("Videos") }
            SegmentedButton(
                selected = mode == SubMode.CHANNELS,
                onClick = { mode = SubMode.CHANNELS },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) { Text("Channels") }
        }
        Spacer(Modifier.height(8.dp))

        if (subs.isEmpty()) {
            Empty("No subscriptions yet.\nOpen a video and tap Subscribe.")
            return
        }

        when (mode) {
            SubMode.CHANNELS -> LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(subs, key = { it.channelId }) { sub ->
                    ChannelRow(
                        sub = sub,
                        onOpenChannel = { onOpenChannel(sub.channelId) },
                        onUnsubscribe = { viewModel.unsubscribe(sub.channelId) },
                    )
                }
            }
            SubMode.VIDEOS -> when (val s = videos) {
                is UiState.Idle, is UiState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                is UiState.Error -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Couldn't load videos", color = MaterialTheme.colorScheme.error)
                    Text(s.message, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.loadVideos() }) { Text("Retry") }
                }
                is UiState.Success ->
                    if (s.data.isEmpty()) Empty("No recent uploads from your channels.")
                    else VideoList(videos = s.data, onVideoClick = onVideoClick)
            }
        }
    }
}

@Composable
private fun ChannelRow(sub: Subscription, onOpenChannel: () -> Unit, onUnsubscribe: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f).clickable(onClick = onOpenChannel),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = sub.avatar,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF303030)),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                sub.name.ifBlank { sub.channelId },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        OutlinedButton(onClick = onUnsubscribe) { Text("Unsubscribe") }
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
