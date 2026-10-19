package com.example.ytclient.youtube.innertube

import com.example.ytclient.youtube.models.ChannelInfo
import com.example.ytclient.youtube.models.ChannelPlaylist
import com.example.ytclient.youtube.models.SearchChannel
import com.example.ytclient.youtube.models.SearchResult
import com.example.ytclient.youtube.models.SearchVideo
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Raw Innertube renderer → app model extraction — phase A1 of RUNTIME_PERF_PLAN.md ("Option A").
 *
 * Unlike the JS runtime's `normaliseVideo` (which reads youtubei.js's PARSED YTNode shapes,
 * snake_case), these walkers read the RAW camelCase JSON YouTube actually returns: classic
 * `videoRenderer`/`compactVideoRenderer`/`gridVideoRenderer` nodes, the newer `lockupViewModel`
 * shapes search increasingly returns, `channelRenderer` cards, and `continuationCommand` tokens.
 * The POLICIES are ported from index.js and must be kept in sync with it while both paths coexist:
 *
 *  - thumbnails: largest variant ≤480px wide, else the largest available; `//`-prefixed URLs get
 *    a scheme (`pickThumbnail` in index.js);
 *  - channel ids must be UC-prefixed or they're dropped (never trust a non-UC id — index.js's
 *    `pickChannelId` doc explains the youtubei.js "N/A" trap this guards against);
 *  - members-only cards are filtered out entirely, detected ONLY from badge-shaped containers so a
 *    title mentioning "members" can't false-positive (`isMembersOnly` in index.js);
 *  - views/published/duration fall back to bounded pattern scans when no dedicated field exists
 *    (`extractMeta` in index.js) — with the published/duration patterns matching both English and
 *    Dutch, same locales RelativeTime parses.
 *
 * Everything is pure JsonElement → data class, no I/O — unit-testable on the JVM with fixture
 * JSON (see InnertubeParserTest).
 */
object InnertubeParser {

    // ── Small safe-access helpers ───────────────────────────────────────────

    private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    private fun JsonElement?.str(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /**
     * Display text from any of Innertube's three text shapes — `{"simpleText": …}`, classic
     * `{"runs": [{"text": …}, …]}` (joined), or the newer view-model `{"content": …}` — plus a bare
     * string primitive. Empty string when nothing matches.
     */
    fun text(el: JsonElement?): String {
        when (el) {
            null -> return ""
            is JsonPrimitive -> return if (el.isString) el.content else ""
            is JsonObject -> {
                el.str("simpleText")?.let { return it }
                el.str("content")?.let { return it }
                (el["runs"] as? JsonArray)?.let { runs ->
                    return runs.joinToString("") { r -> (r as? JsonObject)?.str("text") ?: "" }
                }
                return ""
            }
            else -> return ""
        }
    }

    /** Largest thumbnail ≤480px wide, else the largest available (policy from index.js). Accepts
     *  both classic `thumbnails` arrays and view-model `sources` arrays (same element shape). */
    fun pickThumbnail(sources: JsonArray?): String? {
        if (sources.isNullOrEmpty()) return null
        val entries = sources.mapNotNull { it as? JsonObject }
        if (entries.isEmpty()) return null
        val sorted = entries.sortedByDescending { (it["width"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0 }
        val preferred = sorted.firstOrNull {
            ((it["width"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0) <= 480
        } ?: sorted.first()
        val url = preferred.str("url") ?: return null
        return if (url.startsWith("//")) "https:$url" else url
    }

    // ── Members-only filtering ──────────────────────────────────────────────
    // Same principle as index.js's isMembersOnly: only strings that live under badge-shaped
    // container keys count, so titles/descriptions can never trip it. Raw JSON uses camelCase key
    // names for the same containers the JS side matches in snake_case.

    private val BADGE_CONTAINER_KEYS =
        setOf("badges", "thumbnailOverlays", "thumbnailBadges", "overlays", "badge", "ownerBadges")
    private val BADGE_TEXT_KEYS =
        setOf("style", "label", "text", "tooltip", "accessibilityLabel", "iconType", "iconName")
    private val MEMBERS_ONLY = Regex("MEMBERS_ONLY|members?[\\s-]?only", RegexOption.IGNORE_CASE)

    fun isMembersOnly(node: JsonObject): Boolean {
        val found = ArrayList<String>(8)
        fun walk(el: JsonElement, depth: Int, inBadge: Boolean) {
            if (depth > 8 || found.size >= 60) return
            when (el) {
                is JsonArray -> el.forEach { walk(it, depth + 1, inBadge) }
                is JsonObject -> for ((k, v) in el) {
                    if (inBadge && v is JsonPrimitive && v.isString && k in BADGE_TEXT_KEYS) {
                        found.add(v.content)
                    } else if (v is JsonObject || v is JsonArray) {
                        walk(v, depth + 1, inBadge || k in BADGE_CONTAINER_KEYS)
                    }
                    if (found.size >= 60) return
                }
                else -> {}
            }
        }
        walk(node, 0, false)
        return found.any { MEMBERS_ONLY.containsMatchIn(it) }
    }

    // ── Meta pattern fallbacks (ported from index.js extractMeta, EN + NL) ─

    private val DURATION_RE = Regex("^\\s*\\d{1,2}(:\\d{2}){1,2}\\s*$")
    private val PUBLISHED_RE = Regex(
        "\\d+\\s*(second|minute|hour|day|week|month|year)s?\\s*ago" +
            "|\\d+\\s*(seconde|seconden|minuut|minuten|uur|uren|dag|dagen|week|weken|maand|maanden|jaar|jaren)\\s*geleden" +
            "|^\\s*(streamed|premiered)",
        RegexOption.IGNORE_CASE,
    )
    // Digits, then a short run of separators/locale magnitude words ("123K", "1,2 mln.", "3 mln"),
    // then the views word. \w covers the letter abbreviations; the run is length-bounded so a
    // sentence that merely CONTAINS a number and, much later, "views" can't match.
    private val VIEWS_RE = Regex("\\d[\\w.,\\s]{0,12}(views?|weergaven)", RegexOption.IGNORE_CASE)
    private val SUBSCRIBER_RE = Regex("subscrib|abonnee", RegexOption.IGNORE_CASE)

    // ── Classic renderers (videoRenderer / compactVideoRenderer / gridVideoRenderer / …) ──

    /** UC-prefixed browseId from a byline/owner text's first run, or null. */
    private fun channelIdFromRuns(textObj: JsonElement?): String? {
        val run0 = (textObj.arr("runs"))?.firstOrNull() as? JsonObject ?: return null
        val browseId = run0.obj("navigationEndpoint").obj("browseEndpoint")?.str("browseId")
        return browseId?.takeIf { it.startsWith("UC") }
    }

    /** Parse one classic video node (already unwrapped from its `videoRenderer`-style key). */
    fun videoFromClassic(node: JsonObject): SearchVideo? {
        val id = node.str("videoId") ?: return null
        if (isMembersOnly(node)) return null
        val title = text(node["title"]).ifBlank { text(node.obj("title")?.get("accessibility")) }
        val ownerText = node["ownerText"] ?: node["shortBylineText"] ?: node["longBylineText"]
        val author = text(ownerText)
        val channelId = channelIdFromRuns(node["ownerText"])
            ?: channelIdFromRuns(node["shortBylineText"])
            ?: channelIdFromRuns(node["longBylineText"])
        var duration = text(node["lengthText"])
        if (duration.isBlank()) {
            // Grid/compact variants keep the duration only in the thumbnail overlay badge.
            duration = scanForFirst(node["thumbnailOverlays"], DURATION_RE) ?: ""
        }
        val views = text(node["shortViewCountText"]).ifBlank { text(node["viewCountText"]) }
        val published = text(node["publishedTimeText"])
        val thumbnail = pickThumbnail(node.obj("thumbnail")?.get("thumbnails") as? JsonArray)
        if (title.isBlank() && thumbnail == null) return null // not actually a video node
        return SearchVideo(
            id = id,
            title = title,
            thumbnail = thumbnail,
            author = author,
            duration = duration.trim(),
            views = views,
            channelId = channelId,
            published = published,
        )
    }

    // ── Lockup view-models (newer search/feed cards) ───────────────────────

    /** Parse a `lockupViewModel` whose contentType marks it a VIDEO. */
    fun videoFromLockup(node: JsonObject): SearchVideo? {
        val contentType = node.str("contentType") ?: ""
        if (!contentType.contains("VIDEO")) return null
        val id = node.str("contentId") ?: return null
        if (isMembersOnly(node)) return null

        val meta = node.obj("metadata").obj("lockupMetadataViewModel")
        val title = text(meta?.get("title"))

        // Channel link: the metadata row part whose tap command carries a UC browseId — its text is
        // the channel name. (Same single-walk policy as index.js's findChannelLink.)
        var author = ""
        var channelId: String? = null
        var views = ""
        var published = ""
        val parts = metadataParts(meta)
        for (part in parts) {
            val partText = text(part["text"])
            val browseId = commandBrowseId(part)
            if (browseId != null && channelId == null) {
                channelId = browseId
                if (author.isBlank()) author = partText
                continue
            }
            if (partText.isBlank()) continue
            when {
                views.isBlank() && VIEWS_RE.containsMatchIn(partText) &&
                    !SUBSCRIBER_RE.containsMatchIn(partText) -> views = partText.trim()
                published.isBlank() && PUBLISHED_RE.containsMatchIn(partText) -> published = partText.trim()
            }
        }

        // Duration lives in the thumbnail overlay badge; a bounded pattern scan of the image
        // subtree is simpler and more shape-tolerant than modeling every overlay variant.
        val contentImage = node.obj("contentImage")
        val duration = scanForFirst(contentImage, DURATION_RE) ?: ""
        val thumbnail = pickThumbnail(
            contentImage.obj("thumbnailViewModel").obj("image")?.get("sources") as? JsonArray,
        )
        if (title.isBlank() && thumbnail == null) return null
        return SearchVideo(
            id = id,
            title = title,
            thumbnail = thumbnail,
            author = author,
            duration = duration.trim(),
            views = views,
            channelId = channelId,
            published = published,
        )
    }

    /** All metadataParts objects across the lockup metadata's rows, in document order. */
    private fun metadataParts(meta: JsonObject?): List<JsonObject> {
        val rows = meta.obj("metadata").obj("contentMetadataViewModel")?.get("metadataRows") as? JsonArray
            ?: return emptyList()
        val out = ArrayList<JsonObject>()
        for (row in rows) {
            val rowParts = (row as? JsonObject)?.get("metadataParts") as? JsonArray ?: continue
            for (p in rowParts) (p as? JsonObject)?.let(out::add)
        }
        return out
    }

    /** UC browseId reachable from a metadata part's text commandRuns (or the part itself). */
    private fun commandBrowseId(part: JsonObject): String? {
        val runs = part.obj("text")?.get("commandRuns") as? JsonArray ?: return null
        for (r in runs) {
            val id = (r as? JsonObject).obj("onTap").obj("innertubeCommand")
                .obj("browseEndpoint")?.str("browseId")
            if (id != null && id.startsWith("UC")) return id
        }
        return null
    }

    /** First string in [el]'s subtree matching [re] (bounded depth/count, document order). */
    private fun scanForFirst(el: JsonElement?, re: Regex): String? {
        var result: String? = null
        var visited = 0
        fun walk(e: JsonElement, depth: Int) {
            if (result != null || depth > 10 || visited > 400) return
            visited++
            when (e) {
                is JsonPrimitive -> if (e.isString && re.containsMatchIn(e.content)) result = e.content.trim()
                is JsonArray -> for (c in e) { walk(c, depth + 1); if (result != null) return }
                is JsonObject -> for ((_, v) in e) { walk(v, depth + 1); if (result != null) return }
            }
        }
        el?.let { walk(it, 0) }
        return result
    }

    // ── Channel cards ───────────────────────────────────────────────────────

    /** Parse a classic `channelRenderer` search card. */
    fun channelFromRenderer(node: JsonObject): SearchChannel? {
        val id = node.str("channelId")?.takeIf { it.startsWith("UC") } ?: return null
        val name = text(node["title"])
        if (name.isBlank()) return null
        val avatar = pickThumbnail(node.obj("thumbnail")?.get("thumbnails") as? JsonArray)
        // YouTube has historically swapped which field carries the subscriber count on this card;
        // prefer subscriberCountText, fall back to videoCountText (which often holds "1.2M
        // subscribers" on current responses).
        val subs = listOf(text(node["subscriberCountText"]), text(node["videoCountText"]))
            .firstOrNull { SUBSCRIBER_RE.containsMatchIn(it) }
            ?: text(node["subscriberCountText"]).ifBlank { text(node["videoCountText"]) }
        return SearchChannel(id = id, name = name, avatar = avatar, subscriberText = subs)
    }

    /** Parse a `lockupViewModel` whose contentType marks it a CHANNEL. */
    fun channelFromLockup(node: JsonObject): SearchChannel? {
        val contentType = node.str("contentType") ?: ""
        if (!contentType.contains("CHANNEL")) return null
        val id = node.str("contentId")?.takeIf { it.startsWith("UC") } ?: return null
        val meta = node.obj("metadata").obj("lockupMetadataViewModel")
        val name = text(meta?.get("title"))
        if (name.isBlank()) return null
        val avatarSources =
            meta.obj("image").obj("decoratedAvatarViewModel").obj("avatar").obj("avatarViewModel")
                .obj("image")?.get("sources") as? JsonArray
        val avatar = pickThumbnail(avatarSources)
        val subs = metadataParts(meta).firstNotNullOfOrNull { p ->
            text(p["text"]).takeIf { it.isNotBlank() }
        } ?: ""
        return SearchChannel(id = id, name = name, avatar = avatar, subscriberText = subs)
    }

    // ── Ordered collection over a whole response ───────────────────────────

    /** Everything one response page yielded, in YouTube's own ranked order. */
    data class Collected(
        val videos: List<SearchVideo>,
        /** Channel cards, each with [SearchChannel.position] = videos seen before it. */
        val channels: List<SearchChannel>,
        /** First continuation token encountered, or null when the page has no next page. */
        val continuation: String?,
    )

    private val CLASSIC_VIDEO_KEYS = setOf(
        "videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer",
    )

    /**
     * Walk [root] in document order, collecting every recognisable video/channel card plus the
     * page's continuation token. Works unchanged for page-1 responses (`contents…`) and
     * continuation responses (`onResponseReceivedCommands…appendContinuationItemsAction`) since it
     * keys on the renderer names, not the wrapper path. Dedupes videos by id and channels by id.
     */
    fun collect(root: JsonElement): Collected {
        val videos = ArrayList<SearchVideo>()
        val channels = ArrayList<SearchChannel>()
        val seenVideos = HashSet<String>()
        val seenChannels = HashSet<String>()
        var continuation: String? = null

        fun walk(el: JsonElement, depth: Int) {
            if (depth > 40) return
            when (el) {
                is JsonArray -> el.forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    for ((k, v) in el) {
                        when {
                            k in CLASSIC_VIDEO_KEYS && v is JsonObject -> {
                                videoFromClassic(v)?.takeIf { seenVideos.add(it.id) }?.let(videos::add)
                            }
                            k == "lockupViewModel" && v is JsonObject -> {
                                val ct = v.str("contentType") ?: ""
                                if (ct.contains("CHANNEL")) {
                                    channelFromLockup(v)?.takeIf { seenChannels.add(it.id) }
                                        ?.let { channels.add(it.copy(position = videos.size)) }
                                } else {
                                    videoFromLockup(v)?.takeIf { seenVideos.add(it.id) }?.let(videos::add)
                                }
                            }
                            k == "channelRenderer" && v is JsonObject -> {
                                channelFromRenderer(v)?.takeIf { seenChannels.add(it.id) }
                                    ?.let { channels.add(it.copy(position = videos.size)) }
                            }
                            k == "continuationCommand" && v is JsonObject -> {
                                if (continuation == null) continuation = v.str("token")
                            }
                            v is JsonObject || v is JsonArray -> walk(v, depth + 1)
                        }
                    }
                }
                else -> {}
            }
        }
        walk(root, 0)
        return Collected(videos, channels, continuation)
    }

    /** Safe nested-object lookup — `root[path0][path1]…` as a JsonObject, or null anywhere along
     *  the way. For carving a response down to one subtree before [collect] (e.g. a watch-next
     *  response's `secondaryResults`, so the comment section's continuation token can't be
     *  mistaken for the related-videos one). */
    fun subObject(root: JsonObject, vararg path: String): JsonObject? {
        var cur: JsonObject? = root
        for (p in path) {
            cur = cur.obj(p) ?: return null
        }
        return cur
    }

    /**
     * Full channel header from a channel `browse` response — name/avatar/banner/subscribers across
     * the three shapes YouTube serves (ported from the JS `channelHeader`): `channelMetadataRenderer`
     * (metadata, most reliable for the name), the classic `c4TabbedHeaderRenderer`, and the newer
     * `pageHeaderRenderer`, which nests everything under view-models and buries the subscriber count
     * in metadata rows. Each field falls back independently, so a mixed/partial header still yields
     * whatever it does carry.
     */
    fun channelInfo(response: JsonObject, channelId: String): ChannelInfo {
        val metadata = subObject(response, "metadata", "channelMetadataRenderer")
        val c4 = subObject(response, "header", "c4TabbedHeaderRenderer")
        val pageHeader = subObject(response, "header", "pageHeaderRenderer")
        val pageContent = pageHeader.obj("content").obj("pageHeaderViewModel")

        val name = channelTitle(response)

        val avatar = pickThumbnail(c4.obj("avatar")?.get("thumbnails") as? JsonArray)
            ?: pickThumbnail(
                pageContent.obj("image").obj("decoratedAvatarViewModel").obj("avatar")
                    .obj("avatarViewModel").obj("image")?.get("sources") as? JsonArray,
            )
            ?: pickThumbnail(metadata?.get("avatar").obj("thumbnails") as? JsonArray)
            ?: pickThumbnail(metadata?.get("avatar") as? JsonArray)

        val banner = pickThumbnail(c4.obj("banner")?.get("thumbnails") as? JsonArray)
            ?: pickThumbnail(
                pageContent.obj("banner").obj("imageBannerViewModel").obj("image")
                    ?.get("sources") as? JsonArray,
            )

        // C4 exposes a clean subscriber text; PageHeader hides it among metadata rows, so fall back
        // to the first row part that actually looks like a subscriber count.
        val subs = text(c4?.get("subscriberCountText")).ifBlank {
            pageHeaderMetadataParts(pageContent)
                .map { text(it["text"]) }
                .firstOrNull { SUBSCRIBER_RE.containsMatchIn(it) }
                .orEmpty()
        }

        return ChannelInfo(
            id = channelId,
            name = name,
            avatar = avatar,
            banner = banner,
            subscriberText = subs,
        )
    }

    /** metadataParts across a pageHeaderViewModel's metadata rows (subscriber/video counts etc.). */
    private fun pageHeaderMetadataParts(pageContent: JsonObject?): List<JsonObject> {
        val rows = pageContent.obj("metadata").obj("contentMetadataViewModel")
            ?.get("metadataRows") as? JsonArray ?: return emptyList()
        val out = ArrayList<JsonObject>()
        for (row in rows) {
            val parts = (row as? JsonObject)?.get("metadataParts") as? JsonArray ?: continue
            for (p in parts) (p as? JsonObject)?.let(out::add)
        }
        return out
    }

    /** Playlist ids YouTube actually uses; anything else is not a playlist node (ported from JS). */
    private val PLAYLIST_ID_RE = Regex("^(PL|UU|RD|OL|FL|LL|VL)")

    /** Parse a classic `playlistRenderer`/`gridPlaylistRenderer` node. */
    fun playlistFromRenderer(node: JsonObject): ChannelPlaylist? {
        val rawId = node.str("playlistId") ?: return null
        if (!PLAYLIST_ID_RE.containsMatchIn(rawId)) return null
        val title = text(node["title"])
        if (title.isBlank()) return null
        return ChannelPlaylist(
            id = rawId.removePrefix("VL"),
            title = title,
            thumbnail = pickThumbnail(node.obj("thumbnail")?.get("thumbnails") as? JsonArray)
                ?: pickThumbnail(
                    (node["thumbnails"] as? JsonArray)?.firstOrNull()
                        .obj("thumbnails") as? JsonArray,
                ),
            videoCount = text(node["videoCountShortText"]).ifBlank { text(node["videoCountText"]) },
        )
    }

    /** Parse a `lockupViewModel` whose contentType marks it a PLAYLIST. */
    fun playlistFromLockup(node: JsonObject): ChannelPlaylist? {
        val contentType = node.str("contentType") ?: ""
        if (!contentType.contains("PLAYLIST")) return null
        val rawId = node.str("contentId") ?: return null
        if (!PLAYLIST_ID_RE.containsMatchIn(rawId)) return null
        val meta = node.obj("metadata").obj("lockupMetadataViewModel")
        val title = text(meta?.get("title"))
        if (title.isBlank()) return null
        val count = metadataParts(meta).map { text(it["text"]) }
            .firstOrNull { it.contains("video", true) || it.contains("video's", true) }
            .orEmpty()
        return ChannelPlaylist(
            id = rawId.removePrefix("VL"),
            title = title,
            thumbnail = pickThumbnail(
                node.obj("contentImage").obj("collectionThumbnailViewModel")
                    .obj("primaryThumbnail").obj("thumbnailViewModel").obj("image")
                    ?.get("sources") as? JsonArray,
            ),
            videoCount = count,
        )
    }

    /** Every playlist in a response (channel "Playlists" tab), deduped by id, in document order. */
    fun collectPlaylists(root: JsonElement): List<ChannelPlaylist> {
        val out = ArrayList<ChannelPlaylist>()
        val seen = HashSet<String>()
        fun walk(el: JsonElement, depth: Int) {
            if (depth > 40) return
            when (el) {
                is JsonArray -> el.forEach { walk(it, depth + 1) }
                is JsonObject -> for ((k, v) in el) {
                    when {
                        (k == "playlistRenderer" || k == "gridPlaylistRenderer") && v is JsonObject ->
                            playlistFromRenderer(v)?.takeIf { seen.add(it.id) }?.let(out::add)
                        k == "lockupViewModel" && v is JsonObject ->
                            playlistFromLockup(v)?.takeIf { seen.add(it.id) }?.let(out::add)
                        v is JsonObject || v is JsonArray -> walk(v, depth + 1)
                    }
                }
                else -> {}
            }
        }
        walk(root, 0)
        return out
    }

    /** The channel's display name from a channel `browse` response — probes the same shapes the
     *  JS side's channelHeader does: channelMetadataRenderer, classic C4 header, new PageHeader. */
    fun channelTitle(response: JsonObject): String {
        subObject(response, "metadata", "channelMetadataRenderer")?.str("title")
            ?.takeIf { it.isNotBlank() }?.let { return it }
        subObject(response, "header", "c4TabbedHeaderRenderer")?.let { h ->
            text(h["title"]).takeIf { it.isNotBlank() }?.let { return it }
        }
        subObject(response, "header", "pageHeaderRenderer")?.let { h ->
            h.str("pageTitle")?.takeIf { it.isNotBlank() }?.let { return it }
            text(h["pageTitle"]).takeIf { it.isNotBlank() }?.let { return it }
        }
        return ""
    }

    /**
     * Related ("watch next") videos for [videoId] from a `next` response. Scoped to
     * `secondaryResults` — an unscoped walk would pick up the COMMENT section's continuation token
     * first (and eventually page comments into the related list). The seed video itself is
     * filtered out, matching the JS side's `freshVideos(…, excludeId)`.
     */
    fun parseRelated(response: JsonObject, videoId: String?): Collected {
        val scoped = subObject(response, "contents", "twoColumnWatchNextResults", "secondaryResults")
            ?: response
        val c = collect(scoped)
        return if (videoId == null) c else c.copy(videos = c.videos.filter { it.id != videoId })
    }

    /** One parsed search page: the app-model result plus the token for the next page (if any). */
    data class SearchPage(val result: SearchResult, val continuation: String?)

    /** [collect] shaped for the search endpoint: max 3 channel cards, hasMore from the token —
     *  mirroring index.js's `search()` contract exactly. */
    fun parseSearch(response: JsonObject): SearchPage {
        val c = collect(response)
        return SearchPage(
            result = SearchResult(
                videos = c.videos,
                channels = c.channels.take(3),
                hasMore = c.continuation != null,
            ),
            continuation = c.continuation,
        )
    }
}
