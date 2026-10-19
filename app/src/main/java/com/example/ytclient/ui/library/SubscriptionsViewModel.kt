package com.example.ytclient.ui.library

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ytclient.App
import com.example.ytclient.core.local.RelativeTime
import com.example.ytclient.core.models.UiState
import com.example.ytclient.youtube.models.SearchVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Subscriptions tab. The Channels list is read straight off [com.example.ytclient.core.local.LibraryStore].
 *
 * The Videos list fetches each subscribed channel's latest uploads and merges them. A pure
 * newest-first merge (sorting the flattened list by upload time alone) clumps together: a channel
 * that posts several times a day would otherwise occupy several consecutive slots at the top,
 * crowding out channels that post less often but aren't meaningfully "less recent" video-for-video.
 * So [loadVideos] first establishes a priority order by recency, then round-robins across channels
 * within that order (see [interleaveByChannel]) — the same diversity-without-losing-recency trick
 * `HomeRanker` used to use for the home feed via its (since-replaced, see HomeRanker.diversify)
 * `roundRobinByChannel` pass.
 */
class SubscriptionsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as App
    private val bridge = app.youtubeBridge
    private val library = app.library

    val subscriptions = library.subscriptions

    private val _videos = MutableStateFlow<UiState<List<SearchVideo>>>(UiState.Idle)
    val videos: StateFlow<UiState<List<SearchVideo>>> = _videos

    // The subscription set the current video list was built from — so we only refetch when it changes.
    private var loadedFor: Set<String> = emptySet()

    fun loadVideosIfNeeded() {
        val ids = subscriptions.value.map { it.channelId }.toSet()
        if (ids == loadedFor && _videos.value is UiState.Success) return
        loadVideos()
    }

    fun loadVideos() {
        val subs = subscriptions.value
        loadedFor = subs.map { it.channelId }.toSet()
        if (subs.isEmpty()) {
            _videos.value = UiState.Success(emptyList())
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _videos.value = UiState.Loading
            _videos.value = try {
                // One batched bridge call that fetches every channel CONCURRENTLY (Promise.all on the
                // JS side), instead of looping getChannelVideos one channel at a time — that used to
                // mean one full round trip per subscription, sequentially (tens of seconds for a long
                // subscription list).
                val all = bridge.getChannelVideosBatch(subs.map { it.channelId }).videos
                // Recency establishes the priority order (and each channel's own internal order,
                // since filtering a globally recency-sorted list by channelId keeps that channel's
                // videos newest-first too); interleaveByChannel then redistributes so a prolific
                // channel's entries don't all sit adjacent at the top.
                val recencySorted = all
                    .distinctBy { it.id }
                    .sortedByDescending { RelativeTime.approxEpochMs(it.published) ?: 0L }
                UiState.Success(interleaveByChannel(recencySorted))
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                UiState.Error(t.message ?: "Failed to load subscription videos")
            }
        }
    }

    fun unsubscribe(channelId: String) {
        library.unsubscribe(channelId)
        // Drop the just-unsubscribed channel's videos without a full refetch.
        (_videos.value as? UiState.Success)?.let { s ->
            _videos.value = UiState.Success(s.data.filter { it.channelId != channelId })
        }
        loadedFor = subscriptions.value.map { it.channelId }.toSet()
    }

    /**
     * Bucket [videos] by channelId (in the order each channel's first — i.e. most recent — video
     * appears, so the existing recency order), then round-robin across buckets, one video per
     * bucket per pass. A channel with many recent uploads just reappears every N slots (N = number
     * of channels still active in the rotation) instead of all its entries sitting adjacent at the
     * top. Videos with no channelId (shouldn't normally happen for a channel-scoped fetch) each get
     * their own singleton bucket, same as `HomeRanker.roundRobinByChannel`.
     */
    private fun interleaveByChannel(videos: List<SearchVideo>): List<SearchVideo> {
        if (videos.size < 3) return videos
        val buckets = LinkedHashMap<String, ArrayDeque<SearchVideo>>()
        var anonymous = 0
        for (v in videos) {
            val key = v.channelId ?: "anon${anonymous++}"
            buckets.getOrPut(key) { ArrayDeque() }.addLast(v)
        }
        val out = ArrayList<SearchVideo>(videos.size)
        while (out.size < videos.size) {
            for (bucket in buckets.values) {
                if (bucket.isNotEmpty()) out.add(bucket.removeFirst())
            }
        }
        return out
    }
}
