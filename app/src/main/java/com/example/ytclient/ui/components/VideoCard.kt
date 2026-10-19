package com.example.ytclient.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.ytclient.App
import com.example.ytclient.youtube.models.SearchVideo

/**
 * Phase 15 (HOME_FEED_PERSONALIZATION_PLAN.md): the compact, fixed-width card the grouped-row
 * ("Netflix") home layout puts inside each shelf's horizontally-scrolling `LazyRow`.
 *
 * This is a SHAPE sibling of [VideoRow], not a replacement: [VideoRow] is a full-width list row
 * (thumbnail left, text right) that only makes sense in a vertical list, so a horizontal shelf
 * needed a narrow, stacked card instead — thumbnail on top, two lines of title, uploader beneath.
 * Everything that isn't layout is deliberately mirrored from [VideoRow] rather than reinvented, so
 * the two never disagree about what a video "is":
 *  - the same self-decoration from the shared [com.example.ytclient.core.local.LibraryStore] —
 *    subscribed ✓ badge, watched-progress bar — read the same way, so no caller has to pass state in;
 *  - the same thumbnail URL, built by the shared [thumbnailUrl] (which both card sizes call, so the
 *    two can't drift into fetching — and separately caching — different sizes of the same image);
 *  - the same [formatDuration] (shared from VideoRow.kt, not copied) for the corner duration chip;
 *  - the same tap = open the video, long-press = "Not interested" / "Don't recommend this channel"
 *    contract, hitting the same [com.example.ytclient.core.local.LibraryStore] hide APIs.
 *
 * The one deliberate difference: add-to-playlist is a long-press MENU ITEM here instead of
 * [VideoRow]'s trailing (+) icon button. A 150dp-wide card has no room for a persistent trailing
 * action, and an always-visible button inside a horizontally-scrolling row is also a tap target
 * users hit while trying to swipe. Set [showAddToPlaylist] = false to drop it entirely.
 *
 * Deliberately NOT rendered here: phase 6's "Because you..." caption. In this layout the ROW TITLE
 * already states the reason for every card in it (see `HomeGrouper`), so repeating it per card
 * would be pure noise — which is why this composable has no `reasonLabel` parameter at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoCard(
    video: SearchVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showAddToPlaylist: Boolean = true,
) {
    val library = (LocalContext.current.applicationContext as App).library
    val subscribed by library.subscribedChannelIds.collectAsState()
    val fractions by library.watchedFractions.collectAsState()

    val isSubscribed = video.channelId != null && subscribed.contains(video.channelId)
    val watchedFraction = fractions[video.id]
    // Keyed on the video id like VideoRow's own dialog/menu state: LazyRow recycles composables as
    // the shelf scrolls, and an un-keyed remember would carry an open menu over to whatever video
    // lands in that slot next.
    var showDialog by remember(video.id) { mutableStateOf(false) }
    var showHideMenu by remember(video.id) { mutableStateOf(false) }
    val channelId = video.channelId

    Box {
        Column(
            modifier = modifier
                .width(CARD_WIDTH)
                .combinedClickable(onClick = onClick, onLongClick = { showHideMenu = true })
                .padding(horizontal = 4.dp, vertical = 6.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF202020)),
            ) {
                val thumbUrl = thumbnailUrl(video)
                AsyncImage(
                    model = thumbUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                )

                val durationText = formatDuration(video.duration)
                if (durationText.isNotBlank()) {
                    Text(
                        text = durationText,
                        color = Color.White,
                        fontSize = 10.sp,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(Color(0xCC000000))
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    )
                }

                if (watchedFraction != null && watchedFraction > 0.01f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(3.dp)
                            .background(Color(0x99FFFFFF)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(watchedFraction.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(Color(0xFFFF0000)),
                        )
                    }
                }
            }

            Spacer(Modifier.height(6.dp))

            // heightIn(min = …) reserves both title lines whether or not the title needs them, so a
            // shelf of mixed one- and two-line titles keeps its uploader lines aligned instead of
            // looking like the cards are different heights.
            Text(
                text = video.title,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = TITLE_MIN_HEIGHT),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isSubscribed) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Subscribed",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(11.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                }
                Text(
                    text = video.author,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        DropdownMenu(expanded = showHideMenu, onDismissRequest = { showHideMenu = false }) {
            DropdownMenuItem(
                text = { Text("Not interested") },
                onClick = {
                    showHideMenu = false
                    library.hideVideo(video.id)
                },
            )
            if (channelId != null) {
                DropdownMenuItem(
                    text = { Text("Don't recommend this channel") },
                    onClick = {
                        showHideMenu = false
                        library.hideChannel(channelId)
                    },
                )
            }
            if (showAddToPlaylist) {
                DropdownMenuItem(
                    text = { Text("Add to playlist") },
                    onClick = {
                        showHideMenu = false
                        showDialog = true
                    },
                )
            }
        }
    }

    if (showDialog) {
        // Collected HERE rather than alongside `subscribed`/`fractions` at the top of the
        // composable — see [VideoRow]'s identical note for why.
        val playlists by library.playlists.collectAsState()
        AddToPlaylistDialog(
            video = video,
            playlists = playlists,
            library = library,
            onDismiss = { showDialog = false },
        )
    }
}

/**
 * Card width. Sized so roughly 2.5 cards are visible across a typical phone (≈360dp of content
 * width inside HomeScreen's 12dp side padding) — the half-card at the edge is what tells the user
 * the row scrolls sideways at all, which a row of exactly-fitting cards fails to communicate.
 */
private val CARD_WIDTH = 150.dp

/** Two lines of `bodySmall` (≈16sp line height) — see the title's own comment. */
private val TITLE_MIN_HEIGHT = 34.dp
