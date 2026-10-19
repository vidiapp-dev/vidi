package com.example.ytclient.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.ytclient.youtube.models.Comment

// Palette for the dark player page (matches the rest of PlayerScreen).
private val PANEL_BG = Color(0xFF0F0F0F)
private val PRIMARY_TEXT = Color.White
private val SECONDARY_TEXT = Color.LightGray
private val MUTED_TEXT = Color(0xFF8A8A8A)
private val LINK = Color(0xFF3EA6FF)

/**
 * Compact entry point shown in the player page: "Comments · N" plus the top comment's author and a
 * one-line snippet. Tapping opens the full [CommentsPanel]. Caller should only place this when
 * [CommentsUiState.available] is true.
 */
@Composable
fun CommentsPreviewBar(
    state: CommentsUiState,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val top = state.comments.firstOrNull()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x14FFFFFF))
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Comments", style = MaterialTheme.typography.titleSmall, color = PRIMARY_TEXT)
            if (state.totalText.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(state.totalText, style = MaterialTheme.typography.labelMedium, color = SECONDARY_TEXT)
            }
        }
        if (top != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = top.authorAvatar,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(24.dp).clip(CircleShape).background(Color(0xFF303030)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    top.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = SECONDARY_TEXT,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * The comments view, rendered INLINE below the player (not a modal sheet) so the player stays fully
 * visible and playing above it. Has a Top/Newest toggle, infinite scroll, and expandable replies.
 * Display-only (anonymous app). [modifier] should give it the remaining space below the player
 * (e.g. `Modifier.weight(1f)`).
 */
@Composable
fun CommentsPanel(
    state: CommentsUiState,
    onClose: () -> Unit,
    onSetSort: (CommentSort) -> Unit,
    onLoadMore: () -> Unit,
    onExpandReplies: (String) -> Unit,
    onCollapseReplies: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Infinite scroll: trigger a load when within a few items of the end.
    val nearEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= info.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.hasMore, state.loadingMore) {
        if (nearEnd && state.hasMore && !state.loadingMore) onLoadMore()
    }

    Column(modifier = modifier.fillMaxSize().background(PANEL_BG)) {
        // Header: title + count, sort toggle, close.
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (state.totalText.isNotBlank()) "Comments · ${state.totalText}" else "Comments",
                style = MaterialTheme.typography.titleSmall,
                color = PRIMARY_TEXT,
                modifier = Modifier.weight(1f),
            )
            SortToggle(state.sort, onSetSort)
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close comments", tint = PRIMARY_TEXT)
            }
        }
        HorizontalDivider(color = Color(0x22FFFFFF))

        when {
            state.loading -> Box(
                Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = PRIMARY_TEXT) }

            state.comments.isEmpty() -> Box(
                Modifier.fillMaxWidth().padding(32.dp),
                contentAlignment = Alignment.Center,
            ) { Text("No comments", style = MaterialTheme.typography.bodyMedium, color = SECONDARY_TEXT) }

            else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                // Positional keys (no explicit key): comment ids can be blank/duplicate across pages,
                // and an explicit duplicate key hard-crashes LazyColumn. The list only grows (append)
                // or is fully replaced (sort), so positional keys are stable here.
                items(state.comments) { c ->
                    CommentRow(
                        comment = c,
                        replies = state.expandedReplies[c.id],
                        repliesLoading = state.loadingReplies.contains(c.id),
                        onToggleReplies = {
                            if (state.expandedReplies.containsKey(c.id)) onCollapseReplies(c.id)
                            else onExpandReplies(c.id)
                        },
                    )
                }
                if (state.loadingMore) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = PRIMARY_TEXT,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun SortToggle(sort: CommentSort, onSetSort: (CommentSort) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SortChip("Top", sort == CommentSort.TOP) { onSetSort(CommentSort.TOP) }
        SortChip("Newest", sort == CommentSort.NEWEST) { onSetSort(CommentSort.NEWEST) }
    }
}

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) LINK else SECONDARY_TEXT,
        )
    }
}

@Composable
private fun CommentRow(
    comment: Comment,
    replies: List<Comment>?,
    repliesLoading: Boolean,
    onToggleReplies: () -> Unit,
    isReply: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (isReply) 44.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp),
    ) {
        AsyncImage(
            model = comment.authorAvatar,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(if (isReply) 24.dp else 32.dp)
                .clip(CircleShape)
                .background(Color(0xFF303030)),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    comment.author.ifBlank { "User" },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (comment.authorIsCreator) LINK else SECONDARY_TEXT,
                )
                if (comment.authorIsVerified) {
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Verified",
                        tint = SECONDARY_TEXT,
                        modifier = Modifier.size(12.dp),
                    )
                }
                if (comment.publishedText.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(comment.publishedText, style = MaterialTheme.typography.labelSmall, color = MUTED_TEXT)
                }
                if (comment.isPinned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        tint = MUTED_TEXT,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(comment.text, style = MaterialTheme.typography.bodyMedium, color = PRIMARY_TEXT)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = "Likes",
                    tint = MUTED_TEXT,
                    modifier = Modifier.size(12.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(comment.likeText.ifBlank { "0" }, style = MaterialTheme.typography.labelSmall, color = MUTED_TEXT)
                if (comment.isHearted) {
                    Spacer(Modifier.width(8.dp))
                    Text("❤ creator", style = MaterialTheme.typography.labelSmall, color = MUTED_TEXT)
                }
            }
            // Replies (top-level only).
            if (!isReply && comment.hasReplies) {
                val expanded = replies != null
                TextButton(
                    onClick = onToggleReplies,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Text(
                        when {
                            expanded -> "Hide replies"
                            comment.replyCount > 0 -> "View ${comment.replyCount} replies"
                            else -> "View replies"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = LINK,
                    )
                }
                if (repliesLoading) {
                    CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        color = PRIMARY_TEXT,
                        modifier = Modifier.size(18.dp),
                    )
                }
                replies?.forEach { reply ->
                    CommentRow(
                        comment = reply,
                        replies = null,
                        repliesLoading = false,
                        onToggleReplies = {},
                        isReply = true,
                    )
                }
            }
        }
    }
}
