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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
 * The single, shared video card used EVERYWHERE (Home, Search, History, Subscriptions, related,
 * channel, playlists). It decorates itself from the shared
 * [com.example.ytclient.core.local.LibraryStore] — the subscribed ✓ badge, the watched-progress bar,
 * and the add-to-playlist (+) action — so every card looks identical no matter the call site.
 * Set [showAddToPlaylist] = false to hide the trailing + (e.g. where a delete action takes its place).
 *
 * Long-press opens a "Not interested" / "Don't recommend this channel" menu (see
 * HOME_FEED_PERSONALIZATION_PLAN.md phase 1). Deliberately global — since this card is shared by
 * every screen, hiding works the same way wherever you press-and-hold, but it only actually
 * changes what you see going forward on the personalized home feed (HomeRanker consults
 * [com.example.ytclient.core.local.LibraryStore]'s block-list); other screens like search or a
 * channel page still show hidden content if you navigate there directly.
 *
 * [reasonLabel] is phase 6's "Because you..." transparency caption — passed in (not looked up
 * here) so this shared component stays decoupled from `ui.home`'s [com.example.ytclient.ui.home
 * .HomeRanker.FeedReason]; the home screen is the only caller that ever sets it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun VideoRow(
    video: SearchVideo,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    showAddToPlaylist: Boolean = true,
    reasonLabel: String? = null,
) {
    val library = (LocalContext.current.applicationContext as App).library
    val subscribed by library.subscribedChannelIds.collectAsState()
    // The State itself, NOT its value: reading `.value` here would make every card on screen
    // recompose whenever ANY video's progress changes — and the player saves progress every 5 s for
    // the whole length of every video, with the tab list still composed under the mini-player.
    // derivedStateOf below reads it instead, so a card only recomposes when ITS fraction changes.
    val fractionsState = library.watchedFractions.collectAsState()

    val isSubscribed = video.channelId != null && subscribed.contains(video.channelId)
    val watchedFractionState = remember(video.id, fractionsState) {
        derivedStateOf { fractionsState.value[video.id] }
    }
    // A plain local (not a `by` delegate) so the null check below can smart-cast it.
    val watchedFraction = watchedFractionState.value
    var showDialog by remember(video.id) { mutableStateOf(false) }
    var showHideMenu by remember(video.id) { mutableStateOf(false) }
    val channelId = video.channelId

    Box {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { showHideMenu = true })
                .padding(vertical = 6.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF202020)),
            ) {
                // Render from the video id: youtubei.js hands back signed/oddly-hosted thumbnail URLs
                // for some node types that Coil fails to load; the id-derived one is unsigned +
                // always present. See [thumbnailUrl] for which size and why.
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

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = video.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isSubscribed) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Subscribed",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                    }
                    Text(
                        text = video.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val sub = buildString {
                    if (video.views.isNotBlank()) append(video.views)
                    if (video.published.isNotBlank()) {
                        if (isNotEmpty()) append(" • ")
                        append(video.published)
                    }
                }
                if (sub.isNotBlank()) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!reasonLabel.isNullOrBlank()) {
                    Text(
                        text = reasonLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (showAddToPlaylist) {
                IconButton(onClick = { showDialog = true }) {
                    Icon(Icons.Filled.PlaylistAdd, contentDescription = "Add to playlist")
                }
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
        }
    }

    if (showDialog) {
        // Collected HERE, inside the branch, not alongside `subscribed`/`fractions` at the top of
        // the composable. This is the only thing that reads it, and it is the most expensive of the
        // three library flows a card observes by a wide margin: the value is every playlist with
        // every video in it (up to LibraryStore.MAX_PLAYLIST_VIDEOS each). Subscribing at the top
        // meant every visible card held a collector on it — so a feed of 20 cards opened 20
        // subscriptions to that list and recomposed all of them on any playlist change, to render
        // a dialog that is closed on all 20. Now the subscription exists only while the dialog is
        // actually open, i.e. on exactly one card.
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
 * The thumbnail URL for [video]'s card, derived from the video id rather than taken from
 * [SearchVideo.thumbnail] — youtubei.js hands back signed/oddly-hosted URLs for some node types
 * that Coil fails to load, while the id-derived form is unsigned and always present. Falls back to
 * whatever the node gave us only if there is no id at all.
 *
 * `mqdefault` (320×180), not `hqdefault` (480×360). Both [VideoRow] and [VideoCard] draw into a
 * 150dp-wide 16:9 box, so hqdefault was oversized in two directions at once: too many pixels for
 * the box, AND 4:3, so `ContentScale.Crop` discarded the letterboxing — about a quarter of the
 * bytes that had just been downloaded. mqdefault is natively 16:9 (nothing cropped away) at roughly
 * a third of hqdefault's transfer size, which is the single biggest lever on how fast a screenful
 * of cards fills in. The trade is mild softening on a high-density screen, where 320px is stretched
 * to ~400-450px; at this card size that is hard to notice, and reverting is this one constant.
 *
 * Shared by both card composables so the two can never disagree about it — a divergence here would
 * also silently double the image cache's working set, since the URL IS the cache key.
 */
internal fun thumbnailUrl(video: SearchVideo): String? =
    if (video.id.isNotBlank()) "https://i.ytimg.com/vi/${video.id}/mqdefault.jpg" else video.thumbnail

/**
 * "618" seconds → "10:18"; passes through values already containing ":".
 *
 * Package-visible (was file-private) so [VideoCard] — the compact card the phase-15 grouped-row
 * home layout uses — stamps its thumbnail with the exact same duration text as this row does,
 * rather than growing a second copy of this that can drift.
 */
internal fun formatDuration(raw: String): String {
    if (raw.isBlank()) return ""
    if (raw.contains(":")) return raw
    val secs = raw.toLongOrNull() ?: return raw
    if (secs <= 0) return ""
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
