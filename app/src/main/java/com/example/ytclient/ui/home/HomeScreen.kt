package com.example.ytclient.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ytclient.core.models.UiState
import com.example.ytclient.ui.components.SearchField
import com.example.ytclient.ui.components.SearchSuggestionsList
import com.example.ytclient.ui.components.VideoCard
import com.example.ytclient.ui.components.VideoList
import com.example.ytclient.youtube.models.SearchVideo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun HomeScreen(
    onVideoClick: (SearchVideo) -> Unit,
    onOpenChannel: (String) -> Unit,
    viewModel: HomeViewModel = viewModel(),
) {
    val feed by viewModel.feed.collectAsState()
    val feedReasons by viewModel.feedReasons.collectAsState()
    val query by viewModel.query.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val category by viewModel.category.collectAsState()
    val searchChannels by viewModel.searchChannels.collectAsState()
    val refreshing by viewModel.refreshing.collectAsState()
    val loadingMore by viewModel.loadingMore.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val suggestionsLoading by viewModel.suggestionsLoading.collectAsState()
    val disabledTopics by viewModel.disabledTopics.collectAsState()
    // Phase 15 (HOME_FEED_PERSONALIZATION_PLAN.md): the grouped-row layout + its persisted toggle.
    val layoutMode by viewModel.layoutMode.collectAsState()
    val rows by viewModel.rows.collectAsState()
    var showTopicSettings by remember { mutableStateOf(false) }
    // Committing a search from a suggestion has to dismiss the IME from OUT HERE. SearchField does
    // it for its own IME action, but tapping a suggestion never goes near the field — the query is
    // set from this side — so without this the search runs while the field keeps focus and the caret
    // sits blinking behind the results with the keyboard still up.
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            SearchField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                onSearch = viewModel::search,
                onClear = viewModel::clearSearch,
                modifier = Modifier.weight(1f),
            )
            // Phase 15: layout toggle. Hidden while searching — search results carry no ranking
            // signal and so no reasons to group by, meaning the rows layout doesn't apply there
            // (see the layout condition in the UiState.Success branch below); offering the switch
            // anyway would promise a change nothing on screen could deliver.
            if (searchResults == null) {
                IconButton(onClick = viewModel::toggleHomeLayoutMode) {
                    // The icon shows what tapping SWITCHES TO, not what's active: the stacked-list
                    // glyph while rows are showing, the carousel glyph while the classic list is.
                    when (layoutMode) {
                        HomeLayoutMode.NETFLIX -> Icon(
                            Icons.Filled.ViewAgenda,
                            contentDescription = "Switch to the classic list",
                        )
                        HomeLayoutMode.CLASSIC -> Icon(
                            Icons.Filled.ViewCarousel,
                            contentDescription = "Switch to grouped rows",
                        )
                    }
                }
            }
            IconButton(onClick = { showTopicSettings = true }) {
                Icon(Icons.Filled.Settings, contentDescription = "For you topics")
            }
        }
        Spacer(Modifier.height(8.dp))

        if (showTopicSettings) {
            TopicSettingsDialog(
                topics = viewModel.allTopics,
                disabledTopics = disabledTopics,
                onToggle = viewModel::setTopicEnabled,
                onDismiss = {
                    showTopicSettings = false
                    viewModel.refreshTopics()
                },
            )
        }

        // searchResults != null means the user has searched; otherwise show the feed.
        val state = searchResults ?: feed
        val isSearch = searchResults != null
        // Phase 15: whether to render the grouped rows instead of the classic flat list. The layout
        // applies ONLY to the personalized "For you" feed: a category chip (Music/Gaming/…) runs a
        // plain unranked search whose videos carry no FeedReason at all (see
        // HomeViewModel.loadCategory), and search results likewise — there is nothing to group
        // those by, so they keep the classic list no matter what the toggle says. `rows` being
        // non-empty is the last guard: if grouping produced nothing renderable (a very short feed
        // where every bucket fell under HomeGrouper's minimum, or the derived flow simply hasn't
        // caught up with a just-published batch yet), fall back to the flat list rather than show a
        // blank screen — both layouts render the same underlying videos, so that's always safe.
        val useRows = layoutMode == HomeLayoutMode.NETFLIX &&
            !isSearch &&
            category == "For you" &&
            rows.isNotEmpty()
        // Suggestions take priority over whatever's below any time there are fresh ones to show —
        // including right after a previous search: editing the query should show suggestions for
        // the NEW text, not stay hidden just because a search was already committed once. The
        // ViewModel clears [suggestions] on commit/clear, so this naturally goes away once the user
        // isn't actively typing a new query.
        val showSuggestions = query.isNotBlank() && (suggestions.isNotEmpty() || suggestionsLoading)

        // Declared ABOVE the suggestions early return below: state remembered after a `return@Column`
        // is discarded whenever that branch is taken, so typing one character into search and clearing
        // it used to reset the feed's scroll position to the top.
        //
        // Pull-to-refresh wraps the feed content (below the search field). Enabled only on the feed,
        // not on search results.
        val pullState = rememberPullRefreshState(
            refreshing = refreshing,
            onRefresh = { viewModel.refresh() },
        )
        // The pull gesture itself nudges the list down while dragging; once the refresh finishes and
        // the (possibly reordered) feed lands, snap back to the top instead of leaving the list
        // wherever the drag + new content happened to settle.
        val feedListState = rememberLazyListState()
        var wasRefreshing by remember { mutableStateOf(false) }
        LaunchedEffect(refreshing) {
            if (wasRefreshing && !refreshing) {
                feedListState.scrollToItem(0)
            }
            wasRefreshing = refreshing
        }

        if (showSuggestions) {
            SearchSuggestionsList(
                suggestions = suggestions,
                onSelect = { suggestion ->
                    // Dismiss first, then commit — same ordering, and for the same reason, as
                    // SearchField's own IME-action handler: selectSuggestion() clears the suggestion
                    // list synchronously, which tears down this very branch, and the resulting
                    // recomposition was outrunning the dismissal.
                    keyboardController?.hide()
                    focusManager.clearFocus(force = true)
                    viewModel.selectSuggestion(suggestion)
                },
                loading = suggestionsLoading,
            )
            return@Column
        }

        if (!isSearch) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(viewModel.categories) { c ->
                    FilterChip(
                        selected = category == c,
                        onClick = { viewModel.selectCategory(c) },
                        label = { Text(c) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .then(if (!isSearch) Modifier.pullRefresh(pullState) else Modifier),
        ) {
            when (val s = state) {
                is UiState.Idle, is UiState.Loading ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                is UiState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (isSearch) "Search failed" else "Couldn't load feed",
                            color = MaterialTheme.colorScheme.error,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(s.message, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { if (isSearch) viewModel.search() else viewModel.loadFeed() }) {
                            Text("Retry")
                        }
                    }
                }
                is UiState.Success -> {
                    if (s.data.isEmpty() && !(isSearch && searchChannels.isNotEmpty())) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                if (isSearch) "No results" else "Nothing to show",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (useRows) {
                        HomeRowsList(
                            rows = rows,
                            onVideoClick = onVideoClick,
                            modifier = Modifier.fillMaxSize(),
                            listState = feedListState,
                            onLoadMore = viewModel::loadMore,
                            loadingMore = loadingMore,
                        )
                    } else {
                        VideoList(
                            videos = s.data,
                            onVideoClick = onVideoClick,
                            modifier = Modifier.fillMaxSize(),
                            listState = if (!isSearch) feedListState else rememberLazyListState(),
                            onLoadMore = {
                                if (isSearch) viewModel.loadMoreSearch() else viewModel.loadMore()
                            },
                            loadingMore = loadingMore,
                            // Channels are woven into the list at their real search-rank position
                            // (see VideoList) instead of always leading it.
                            channels = if (isSearch) searchChannels else emptyList(),
                            onOpenChannel = onOpenChannel,
                            // Phase 6: "Because you..." captions only make sense on the personalized
                            // "For you" feed, not search results or other categories — those carry
                            // no ranking signal (see HomeViewModel.feedReasons).
                            reasonLabels = if (!isSearch) feedReasons.mapValues { it.value.label } else emptyMap(),
                        )
                    }
                }
            }
            if (!isSearch) {
                PullRefreshIndicator(
                    refreshing = refreshing,
                    state = pullState,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }
        }
    }
}

/**
 * Phase 15 (HOME_FEED_PERSONALIZATION_PLAN.md): the grouped-row ("Netflix") rendering of the "For
 * you" feed — one vertically-scrolling column of shelves, each a title plus a horizontally-
 * scrolling strip of [VideoCard]s. The rows themselves come from [HomeGrouper] via
 * [HomeViewModel.rows]; this only lays them out.
 *
 * Kept private to HomeScreen rather than added to `ui.components`: unlike [VideoList] (shared by
 * search / history / channel / playlists), this shape is meaningful only for a feed that carries
 * [HomeRanker.FeedReason]s, i.e. the home tab and nothing else.
 *
 * Infinite scroll follows [VideoList]'s trigger — same `derivedStateOf` on the list's `layoutInfo`,
 * same "last visible index is within 4 of the end" condition, same `LaunchedEffect(nearEnd, size)` —
 * so both layouts page at roughly the same moment. The window being 4 ROWS here (rather than 4
 * cards) makes it fire earlier in wall-clock terms, which is if anything what this layout wants:
 * every extra page is spread across many shelves, so it takes more of the flat stream to grow the
 * grouped view than it does to grow the flat one.
 *
 * It adds ONE clause [VideoList] does not have: the list must have something off-screen before the
 * trigger can arm. A grouped feed is only a handful of tall rows, so it can easily fit entirely on
 * screen — and a fully-visible list trivially satisfies "within 4 of the end", which made this page
 * continuously on its own. See the comment on the condition itself.
 *
 * [listState] is HomeScreen's shared `feedListState`, the same instance the classic list uses — so
 * pull-to-refresh's snap-back-to-top ([HomeScreen]'s post-refresh `scrollToItem(0)`) keeps working
 * without either layout needing to know the other exists. Only one of them is ever composed at a
 * time, and a stale index carried across a layout switch is clamped by LazyColumn.
 */
@Composable
private fun HomeRowsList(
    rows: List<HomeRow>,
    onVideoClick: (SearchVideo) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    onLoadMore: (() -> Unit)? = null,
    loadingMore: Boolean = false,
) {
    if (onLoadMore != null) {
        val nearEnd by remember {
            derivedStateOf {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
                // Deliberately NO "something must be off-screen" clause. A grouped feed that fits on
                // screen is exactly the case that MUST keep paging: page 1 is ~30 videos, most
                // per-topic buckets are then under HomeGrouper's three-video minimum and fold into
                // the catch-all, so a fresh start renders as just "New from your subscriptions" +
                // "Recommended for you" until more pages land. Gating on off-screen content stalls
                // that at two rows permanently — the feed can never grow enough to earn the scroll
                // that would let it grow.
                //
                // This condition is self-limiting, which is why it does not need such a guard: once
                // enough rows exist to overflow the viewport by 4, `last >= totalItemsCount - 4`
                // goes false on its own. Runaway repetition came from loadMore()'s in-flight guard
                // being set inside its coroutine rather than before launch — fixed there, which is
                // where it belonged.
                last >= info.totalItemsCount - 4
            }
        }
        // The condition above is self-limiting but NOT self-pacing, and on cold start those are
        // different problems. A fresh grouped feed is two or three rows, which fit on screen, so
        // `nearEnd` is true from the first frame; this effect is keyed on `rows.size`, so every
        // page that lands restarts it and asks for another. The loop does terminate — once the rows
        // overflow the viewport by 4 — but getting there means several back-to-back
        // `getMoreHomeFeed` round trips, each followed by a full HomeRanker affinity pass over the
        // whole library (rankAndAppendBatch), a regroup, and a feed-cache write. That burst lands
        // in exactly the seconds the user is waiting for the feed to settle, which is most of why
        // it takes so long to feel responsive.
        //
        // Two bounds, neither of which can stall growth the way an "is anything off-screen" guard
        // would (see the comment on the condition itself for why that guard is wrong here):
        //
        //  • The [AUTO_PAGE_DEBOUNCE_MS] delay. Because the effect is cancelled and restarted on
        //    every `rows.size` change, a burst of landing pages collapses into ONE follow-up
        //    request instead of one request each.
        //  • [MAX_AUTO_PAGES] — how many pages the feed may fetch while the user has never
        //    scrolled. Reset on the first sign of an actual scroll, so a user who engages with the
        //    feed still gets unbounded paging; it just stops racing ahead on its own while nobody
        //    has touched it. The cap is high enough (3 pages ≈ 90 more videos) that the rows will
        //    have overflowed any phone viewport well before it binds, which is also what re-arms
        //    the effect: scrolling changes `nearEnd`.
        var autoPages by remember { mutableStateOf(0) }
        LaunchedEffect(listState) {
            snapshotFlow { listState.isScrollInProgress }
                .filter { it }
                .collect { autoPages = 0 }
        }
        LaunchedEffect(nearEnd, rows.size) {
            if (nearEnd && autoPages < MAX_AUTO_PAGES) {
                delay(AUTO_PAGE_DEBOUNCE_MS)
                autoPages++
                onLoadMore()
            }
        }
    }

    LazyColumn(modifier = modifier, state = listState) {
        // Keyed on HomeRow.key (a stable per-reason identity, never a position — see HomeRow's doc)
        // so a regroup that inserts a new shelf, or moves one, re-uses the existing rows instead of
        // re-composing every card and resetting each strip's horizontal scroll position.
        items(rows, key = { it.key }) { row ->
            HomeRowShelf(row = row, onVideoClick = onVideoClick, onLoadMore = onLoadMore)
        }
        // Same trailing spinner as VideoList's, so "more is coming" looks identical in both layouts.
        if (loadingMore) {
            item {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * One shelf: a title plus its horizontally-scrolling strip of cards.
 *
 * Split out of [HomeRowsList]'s `items` block because each shelf now owns a little state — how much
 * of itself it has revealed — and a composable per row is the honest way to express that.
 *
 * ## Swiping a row to its right-hand end
 * A row can HOLD more than it shows (up to `HomeGrouper.MAX_ROW_VIDEOS`), so reaching the end does
 * one of two things, in order:
 *  1. **Reveal more of this row's own cards**, a page at a time. Free and instant — these videos
 *     were already fetched, ranked and grouped into this shelf; the strip was simply not drawing
 *     them yet. Previously they were unreachable in this layout entirely.
 *  2. **Only once the row is fully revealed**, fall through to [onLoadMore] — the ordinary flat
 *     feed continuation. That grows the one shared stream, so what comes back may land in this row
 *     or in any other. There is no per-row fetch; see [HomeGrouper]'s class doc for why.
 *
 * [rememberSaveable] keyed by [HomeRow.key] holds the reveal window: a plain `remember` would be
 * discarded the moment the row scrolled out of the LazyColumn's viewport, silently collapsing a
 * row the user had just expanded. Keying on the row's stable identity also means a regroup — which
 * rebuilds every [HomeRow] instance — keeps each shelf's window, while a genuinely different row
 * arriving under a new key correctly starts fresh.
 */
@Composable
private fun HomeRowShelf(
    row: HomeRow,
    onVideoClick: (SearchVideo) -> Unit,
    onLoadMore: (() -> Unit)?,
) {
    val rowState = rememberLazyListState()
    var revealed by rememberSaveable(row.key) { mutableStateOf(ROW_REVEAL_PAGE) }
    val visible = remember(row.videos, revealed) { row.videos.take(revealed) }

    // Deliberately NOT wrapped in a condition (unlike [HomeRowsList]'s `if (onLoadMore != null)`):
    // any guard here would have to involve `revealed`, which changes as the row expands, and a
    // condition that flips mid-life would dispose this effect exactly when it is still needed. The
    // null check lives inside the effect instead, where it costs nothing.
    val atEnd by remember {
        derivedStateOf {
            val info = rowState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            // Same self-trigger guard as the vertical list's: a strip whose cards all fit on
            // screen has nothing to swipe to, so it must not count as "at the end".
            info.totalItemsCount > info.visibleItemsInfo.size &&
                last >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(atEnd, visible.size) {
        if (!atEnd) return@LaunchedEffect
        if (revealed < row.videos.size) {
            revealed = (revealed + ROW_REVEAL_PAGE).coerceAtMost(row.videos.size)
        } else {
            onLoadMore?.invoke()
        }
    }

    Column(modifier = Modifier.padding(bottom = 8.dp)) {
        Text(
            text = row.title,
            style = MaterialTheme.typography.titleSmall,
            // One line: "Because you watched <video title>" carries a full YouTube title, which
            // would otherwise wrap and push the shelf's cards down by two or three lines.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
        )
        LazyRow(state = rowState, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items(visible, key = { it.video.id }) { ranked ->
                // No reasonLabel: the row title above already says why these are here (see
                // VideoCard's doc).
                VideoCard(video = ranked.video, onClick = { onVideoClick(ranked.video) })
            }
        }
    }
}

/**
 * How many more cards a shelf reveals each time it is swiped to its end — and how many it starts
 * with. Matches the row length this layout shipped with, so a row that never gets swiped looks
 * exactly as it did before.
 */
private const val ROW_REVEAL_PAGE = 20

/**
 * Pages the grouped feed may fetch on its own, without the user ever scrolling — see the
 * auto-paging effect in [HomeRowsList]. Reset the moment a scroll starts.
 */
private const val MAX_AUTO_PAGES = 3

/** Collapses a burst of landing pages into one follow-up request — see [HomeRowsList]. */
private const val AUTO_PAGE_DEBOUNCE_MS = 250L

/**
 * Phase 8 (HOME_FEED_PERSONALIZATION_PLAN.md): on/off switches for every topic
 * [HomeViewModel.allTopics] can blend into the "For you" feed (see fetchTopicsBlend in index.js).
 * A toggle only takes effect on the next fetch — [onDismiss] is expected to call
 * [HomeViewModel.refreshTopics] so closing the sheet re-fetches immediately instead of waiting for
 * an unrelated refresh.
 */
@Composable
private fun TopicSettingsDialog(
    topics: List<String>,
    disabledTopics: Set<String>,
    onToggle: (topic: String, enabled: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("For you topics") },
        text = {
            Column {
                Text(
                    "Choose which topics blend into your \"For you\" feed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    topics.forEach { topic ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(topic, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(8.dp))
                            Switch(
                                checked = topic !in disabledTopics,
                                onCheckedChange = { checked -> onToggle(topic, checked) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        },
    )
}
