package com.example.ytclient.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.ytclient.youtube.models.SearchChannel
import com.example.ytclient.youtube.models.SearchVideo

/**
 * A scrollable list of [VideoRow]s. The card decorates itself from the shared library (subscribed
 * badge, watched-progress bar, add-to-playlist), so this is just a thin de-duped list wrapper.
 * [leadingContent] lets a screen inject items above the list (e.g. a header). [onLoadMore] is invoked
 * as the list nears its end (infinite scroll); [loadingMore] shows a trailing spinner.
 *
 * [channels], when non-empty, are woven into the video list at their recorded [SearchChannel.position]
 * (how many videos preceded them in YouTube's actual search ranking) instead of always leading the
 * list — a channel that ranks 3rd shows between video 2 and video 3, not above video 1.
 *
 * [reasonLabels] is phase 6's per-video "Because you..." caption, keyed by [SearchVideo.id] (see
 * `HomeViewModel.feedReasons`). Defaults to empty so every other screen that reuses this list
 * (search, history, channel, playlists) is unaffected — only the home screen's "For you" feed
 * actually has a populated map to pass in.
 */
@Composable
fun VideoList(
    videos: List<SearchVideo>,
    onVideoClick: (SearchVideo) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    showAddToPlaylist: Boolean = true,
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
    channels: List<SearchChannel> = emptyList(),
    onOpenChannel: ((String) -> Unit)? = null,
    leadingContent: (LazyListScope.() -> Unit)? = null,
    reasonLabels: Map<String, String> = emptyMap(),
) {
    val unique = remember(videos) { videos.distinctBy { it.id } }
    val channelsByPosition = remember(channels, unique) {
        channels.groupBy { it.position.coerceIn(0, unique.size) }
    }

    // Infinite scroll: fire onLoadMore when within a few items of the end.
    if (onLoadMore != null) {
        val nearEnd by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                // Self-limiting by design: it stays true only until enough items exist to overflow
                // the viewport by 4, which is what lets a short first page keep paging until the
                // screen is actually full. See HomeScreen's HomeRowsList for the same reasoning and
                // for why an "only page once something is off-screen" guard was tried here and
                // reverted — it deadlocks a feed that hasn't filled the screen yet.
                last >= info.totalItemsCount - 4
            }
        }
        LaunchedEffect(nearEnd, unique.size) { if (nearEnd) onLoadMore() }
    }

    LazyColumn(modifier = modifier, state = listState) {
        leadingContent?.invoke(this)
        if (channelsByPosition.isEmpty()) {
            items(unique, key = { it.id }) { video ->
                VideoRow(
                    video = video,
                    onClick = { onVideoClick(video) },
                    showAddToPlaylist = showAddToPlaylist,
                    reasonLabel = reasonLabels[video.id],
                )
            }
        } else {
            for (index in 0..unique.size) {
                channelsByPosition[index]?.forEach { ch ->
                    item(key = "ch:${ch.id}") {
                        ChannelCard(channel = ch, onClick = { onOpenChannel?.invoke(ch.id) })
                    }
                }
                if (index < unique.size) {
                    val video = unique[index]
                    item(key = video.id) {
                        VideoRow(
                            video = video,
                            onClick = { onVideoClick(video) },
                            showAddToPlaylist = showAddToPlaylist,
                            reasonLabel = reasonLabels[video.id],
                        )
                    }
                }
            }
        }
        if (loadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}
