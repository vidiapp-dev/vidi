package com.example.ytclient.youtube.innertube

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixture-driven tests for [InnertubeParser] (A1, RUNTIME_PERF_PLAN.md). Fixtures are hand-cut to
 * the exact renderer shapes YouTube returns — when a live response ever disagrees, capture the
 * offending snippet into a new test case here first, then fix the parser against it.
 */
class InnertubeParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(s: String) = json.parseToJsonElement(s).jsonObject

    // ── text() ──────────────────────────────────────────────────────────────

    @Test
    fun `text handles simpleText, runs and viewmodel content`() {
        assertEquals("plain", InnertubeParser.text(json.parseToJsonElement("\"plain\"")))
        assertEquals("Simple", InnertubeParser.text(parse("""{"simpleText":"Simple"}""")))
        assertEquals("a b", InnertubeParser.text(parse("""{"runs":[{"text":"a "},{"text":"b"}]}""")))
        assertEquals("VM", InnertubeParser.text(parse("""{"content":"VM"}""")))
        assertEquals("", InnertubeParser.text(null))
        assertEquals("", InnertubeParser.text(parse("""{"foo":"bar"}""")))
    }

    // ── pickThumbnail() ─────────────────────────────────────────────────────

    @Test
    fun `pickThumbnail prefers largest at or under 480 and fixes scheme-relative urls`() {
        val thumbs = parse(
            """{"t":[
                {"url":"//i.ytimg.com/small.jpg","width":168,"height":94},
                {"url":"//i.ytimg.com/mid.jpg","width":336,"height":188},
                {"url":"//i.ytimg.com/big.jpg","width":1280,"height":720}
            ]}""",
        )["t"]!!
        assertEquals(
            "https://i.ytimg.com/mid.jpg",
            InnertubeParser.pickThumbnail(thumbs as kotlinx.serialization.json.JsonArray),
        )
    }

    @Test
    fun `pickThumbnail falls back to largest when everything is over 480`() {
        val thumbs = parse("""{"t":[{"url":"https://a/720.jpg","width":720},{"url":"https://a/1280.jpg","width":1280}]}""")["t"]!!
        assertEquals(
            "https://a/1280.jpg",
            InnertubeParser.pickThumbnail(thumbs as kotlinx.serialization.json.JsonArray),
        )
    }

    // ── classic videoRenderer ───────────────────────────────────────────────

    private val videoRenderer = """
    {
      "videoId": "abc123def45",
      "title": {"runs": [{"text": "A Great Video"}]},
      "ownerText": {"runs": [{"text": "Some Channel",
        "navigationEndpoint": {"browseEndpoint": {"browseId": "UCxyz987"}}}]},
      "lengthText": {"simpleText": "12:34"},
      "viewCountText": {"simpleText": "123,456 views"},
      "shortViewCountText": {"simpleText": "123K views"},
      "publishedTimeText": {"simpleText": "3 hours ago"},
      "thumbnail": {"thumbnails": [
        {"url": "//i.ytimg.com/vi/abc123def45/hqdefault.jpg", "width": 480, "height": 360}
      ]}
    }
    """

    @Test
    fun `videoFromClassic maps every field`() {
        val v = InnertubeParser.videoFromClassic(parse(videoRenderer))
        assertNotNull(v)
        v!!
        assertEquals("abc123def45", v.id)
        assertEquals("A Great Video", v.title)
        assertEquals("Some Channel", v.author)
        assertEquals("UCxyz987", v.channelId)
        assertEquals("12:34", v.duration)
        assertEquals("123K views", v.views)
        assertEquals("3 hours ago", v.published)
        assertEquals("https://i.ytimg.com/vi/abc123def45/hqdefault.jpg", v.thumbnail)
    }

    @Test
    fun `videoFromClassic drops members-only cards but not titles that mention members`() {
        val membersOnly = parse(
            """{
              "videoId": "vid1",
              "title": {"runs": [{"text": "Secret stream"}]},
              "thumbnail": {"thumbnails": [{"url": "https://t/1.jpg", "width": 480}]},
              "badges": [{"metadataBadgeRenderer": {"style": "BADGE_STYLE_TYPE_MEMBERS_ONLY", "label": "Members only"}}]
            }""",
        )
        assertNull(InnertubeParser.videoFromClassic(membersOnly))

        val innocentTitle = parse(
            """{
              "videoId": "vid2",
              "title": {"runs": [{"text": "Our members only grew this year"}]},
              "thumbnail": {"thumbnails": [{"url": "https://t/2.jpg", "width": 480}]}
            }""",
        )
        assertNotNull(InnertubeParser.videoFromClassic(innocentTitle))
    }

    @Test
    fun `videoFromClassic never emits a non-UC channel id`() {
        val v = InnertubeParser.videoFromClassic(
            parse(
                """{
                  "videoId": "vid3",
                  "title": {"runs": [{"text": "T"}]},
                  "ownerText": {"runs": [{"text": "Chan",
                    "navigationEndpoint": {"browseEndpoint": {"browseId": "N/A"}}}]},
                  "thumbnail": {"thumbnails": [{"url": "https://t/3.jpg", "width": 480}]}
                }""",
            ),
        )
        assertNotNull(v)
        assertNull(v!!.channelId)
        assertEquals("Chan", v.author)
    }

    @Test
    fun `videoFromClassic reads grid-style duration from thumbnail overlays`() {
        val v = InnertubeParser.videoFromClassic(
            parse(
                """{
                  "videoId": "vid4",
                  "title": {"simpleText": "Grid"},
                  "thumbnail": {"thumbnails": [{"url": "https://t/4.jpg", "width": 480}]},
                  "thumbnailOverlays": [{"thumbnailOverlayTimeStatusRenderer":
                    {"text": {"simpleText": "1:02:03"}, "style": "DEFAULT"}}]
                }""",
            ),
        )
        assertEquals("1:02:03", v!!.duration)
    }

    // ── lockupViewModel ─────────────────────────────────────────────────────

    private val lockupVideo = """
    {
      "contentId": "lock1234567",
      "contentType": "LOCKUP_CONTENT_TYPE_VIDEO",
      "contentImage": {"thumbnailViewModel": {
        "image": {"sources": [{"url": "https://i.ytimg.com/vi/lock1234567/hq720.jpg", "width": 360, "height": 202}]},
        "overlays": [{"thumbnailOverlayBadgeViewModel": {"thumbnailBadges": [
          {"thumbnailBadgeViewModel": {"text": "9:58", "badgeStyle": "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT"}}
        ]}}]
      }},
      "metadata": {"lockupMetadataViewModel": {
        "title": {"content": "Lockup Video Title"},
        "metadata": {"contentMetadataViewModel": {"metadataRows": [
          {"metadataParts": [{"text": {"content": "Lockup Channel", "commandRuns": [
            {"onTap": {"innertubeCommand": {"browseEndpoint": {"browseId": "UClockchan1"}}}}
          ]}}]},
          {"metadataParts": [
            {"text": {"content": "1,2 mln. weergaven"}},
            {"text": {"content": "3 uur geleden"}}
          ]}
        ]}}
      }}
    }
    """

    @Test
    fun `videoFromLockup maps the new card shape including Dutch metadata`() {
        val v = InnertubeParser.videoFromLockup(parse(lockupVideo))
        assertNotNull(v)
        v!!
        assertEquals("lock1234567", v.id)
        assertEquals("Lockup Video Title", v.title)
        assertEquals("Lockup Channel", v.author)
        assertEquals("UClockchan1", v.channelId)
        assertEquals("9:58", v.duration)
        assertEquals("1,2 mln. weergaven", v.views)
        assertEquals("3 uur geleden", v.published)
        assertEquals("https://i.ytimg.com/vi/lock1234567/hq720.jpg", v.thumbnail)
    }

    @Test
    fun `videoFromLockup refuses non-video lockups`() {
        val channelLockup = parse(
            """{"contentId": "UCsomebody", "contentType": "LOCKUP_CONTENT_TYPE_CHANNEL"}""",
        )
        assertNull(InnertubeParser.videoFromLockup(channelLockup))
    }

    // ── channel cards ───────────────────────────────────────────────────────

    @Test
    fun `channelFromRenderer takes the subscriber-looking count field`() {
        val ch = InnertubeParser.channelFromRenderer(
            parse(
                """{
                  "channelId": "UCchan55555",
                  "title": {"simpleText": "The Channel"},
                  "thumbnail": {"thumbnails": [{"url": "//yt3.ggpht.com/av.jpg", "width": 176}]},
                  "videoCountText": {"simpleText": "1.2M subscribers"}
                }""",
            ),
        )
        assertNotNull(ch)
        ch!!
        assertEquals("UCchan55555", ch.id)
        assertEquals("The Channel", ch.name)
        assertEquals("https://yt3.ggpht.com/av.jpg", ch.avatar)
        assertEquals("1.2M subscribers", ch.subscriberText)
    }

    // ── full-page collection ────────────────────────────────────────────────

    private val searchPage = """
    {
      "contents": {"twoColumnSearchResultsRenderer": {"primaryContents": {"sectionListRenderer": {"contents": [
        {"itemSectionRenderer": {"contents": [
          {"videoRenderer": $videoRenderer},
          {"channelRenderer": {
            "channelId": "UCchan55555",
            "title": {"simpleText": "The Channel"},
            "thumbnail": {"thumbnails": [{"url": "https://yt3.ggpht.com/av.jpg", "width": 176}]},
            "videoCountText": {"simpleText": "1.2M subscribers"}
          }},
          {"lockupViewModel": $lockupVideo},
          {"videoRenderer": $videoRenderer}
        ]}},
        {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "CONT_TOKEN_1"}}}}
      ]}}}}
    }
    """

    @Test
    fun `collect walks a search page in order with dedupe, positions and continuation`() {
        val c = InnertubeParser.collect(parse(searchPage))
        // duplicate videoRenderer deduped by id
        assertEquals(listOf("abc123def45", "lock1234567"), c.videos.map { it.id })
        assertEquals(1, c.channels.size)
        // channel card came after exactly one video in document order
        assertEquals(1, c.channels[0].position)
        assertEquals("CONT_TOKEN_1", c.continuation)
    }

    @Test
    fun `collect handles continuation-response shape and reports no further page`() {
        val contPage = """
        {"onResponseReceivedCommands": [{"appendContinuationItemsAction": {"continuationItems": [
          {"itemSectionRenderer": {"contents": [{"videoRenderer": $videoRenderer}]}}
        ]}}]}
        """
        val c = InnertubeParser.collect(parse(contPage))
        assertEquals(1, c.videos.size)
        assertNull(c.continuation)
        val page = InnertubeParser.parseSearch(parse(contPage))
        assertFalse(page.result.hasMore)
    }

    @Test
    fun `parseSearch caps channels at three and sets hasMore`() {
        val page = InnertubeParser.parseSearch(parse(searchPage))
        assertTrue(page.result.hasMore)
        assertEquals("CONT_TOKEN_1", page.continuation)
        assertEquals(2, page.result.videos.size)
        assertEquals(1, page.result.channels.size)
    }

    // ── subtree scoping + channel titles (A3) ───────────────────────────────

    @Test
    fun `subObject walks a path and returns null off-path`() {
        val root = parse("""{"a":{"b":{"c":{"leaf":1}}}}""")
        assertNotNull(InnertubeParser.subObject(root, "a", "b", "c"))
        assertNull(InnertubeParser.subObject(root, "a", "nope", "c"))
        assertNull(InnertubeParser.subObject(root, "a", "b", "c", "leaf")) // leaf is not an object
    }

    @Test
    fun `watch-next scoping keeps related videos and drops the comments continuation`() {
        // A related-videos walk MUST be scoped to secondaryResults: an unscoped walk would pick up
        // the comment section's continuation token first and page comments into the feed.
        val watchNext = parse(
            """{"contents":{"twoColumnWatchNextResults":{
              "results":{"results":{"contents":[{"itemSectionRenderer":{"contents":[
                {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"COMMENTS_TOKEN"}}}}
              ]}}]}},
              "secondaryResults":{"secondaryResults":{"results":[
                {"compactVideoRenderer":{
                  "videoId":"rel00000001",
                  "title":{"simpleText":"Related One"},
                  "longBylineText":{"runs":[{"text":"Rel Chan",
                    "navigationEndpoint":{"browseEndpoint":{"browseId":"UCrelated01"}}}]},
                  "lengthText":{"simpleText":"4:20"},
                  "thumbnail":{"thumbnails":[{"url":"https://t/rel.jpg","width":336}]}}},
                {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"RELATED_TOKEN"}}}}
              ]}}
            }}}""",
        )
        val scoped = InnertubeParser.subObject(watchNext, "contents", "twoColumnWatchNextResults", "secondaryResults")
        assertNotNull(scoped)
        val c = InnertubeParser.collect(scoped!!)
        assertEquals(listOf("rel00000001"), c.videos.map { it.id })
        assertEquals("Rel Chan", c.videos[0].author)
        assertEquals("UCrelated01", c.videos[0].channelId)
        assertEquals("RELATED_TOKEN", c.continuation)
    }

    @Test
    fun `channelTitle reads metadata, classic header and page header shapes`() {
        assertEquals(
            "From Metadata",
            InnertubeParser.channelTitle(parse("""{"metadata":{"channelMetadataRenderer":{"title":"From Metadata"}}}""")),
        )
        assertEquals(
            "From C4",
            InnertubeParser.channelTitle(parse("""{"header":{"c4TabbedHeaderRenderer":{"title":{"simpleText":"From C4"}}}}""")),
        )
        assertEquals(
            "From PageHeader",
            InnertubeParser.channelTitle(parse("""{"header":{"pageHeaderRenderer":{"pageTitle":"From PageHeader"}}}""")),
        )
        assertEquals("", InnertubeParser.channelTitle(parse("""{"header":{"unknownRenderer":{}}}""")))
    }

    // ── search params (A3) ──────────────────────────────────────────────────

    @Test
    fun `search params encode to the values Innertube expects`() {
        // EgIQAQ== is the well-known type:video filter youtubei.js sends — if this ever changes,
        // the protobuf shape assumption in SearchParams is wrong.
        assertEquals("EgIQAQ==", SearchParams.videosOnly)
        assertEquals("EgQQARgB", SearchParams.videosOfDuration(SearchParams.Duration.SHORT))
        assertEquals("EgQQARgD", SearchParams.videosOfDuration(SearchParams.Duration.MEDIUM))
        assertEquals("EgQQARgC", SearchParams.videosOfDuration(SearchParams.Duration.LONG))
    }

    // ── weighted picker (A3) ────────────────────────────────────────────────

    @Test
    fun `pickWeighted respects boundaries and long-run weight shares`() {
        val e = listOf("a" to 4.0, "b" to 6.0, "c" to 10.0) // total 20
        assertEquals("a", HomeFeedEngine.pickWeighted(e, 0.0))
        assertEquals("a", HomeFeedEngine.pickWeighted(e, 0.19))
        assertEquals("b", HomeFeedEngine.pickWeighted(e, 0.21))
        assertEquals("c", HomeFeedEngine.pickWeighted(e, 0.51))
        assertEquals("c", HomeFeedEngine.pickWeighted(e, 1.0))
        assertNull(HomeFeedEngine.pickWeighted(emptyList<Pair<String, Double>>()))
        assertNull(HomeFeedEngine.pickWeighted(listOf("x" to 0.0)))

        // Mirrors the production BUCKET_WEIGHTS_* mix (topics was raised 4.0 -> 10.0 on
        // 2026-10-04 — see its doc in HomeFeedEngine). The literals are deliberate copies rather
        // than references to the private constants, so this stays a test of the PICKER; keep them
        // in step anyway, since the whole point of asserting the real mix is to document what share
        // of the feed each bucket is actually meant to win.
        val buckets = listOf("topics" to 10.0, "channels" to 6.0, "history" to 4.0, "search" to 3.0, "discover" to 3.0)
        val total = buckets.sumOf { it.second } // 26.0
        val counts = HashMap<String, Int>()
        val n = 100_000
        val rnd = java.util.Random(42)
        repeat(n) {
            val p = HomeFeedEngine.pickWeighted(buckets, rnd.nextDouble())!!
            counts[p] = (counts[p] ?: 0) + 1
        }
        for ((name, w) in buckets) {
            val share = (counts[name] ?: 0).toDouble() / n
            assertTrue("$name share was $share, expected ~${w / total}", kotlin.math.abs(share - w / total) < 0.01)
        }
    }

    @Test
    fun `pickDistinctWeighted samples without replacement and still respects weight`() {
        val rnd = java.util.Random(7)
        val topics = (1..14).map { "t$it" to 1.0 }
        repeat(200) {
            val picks = HomeFeedEngine.pickDistinctWeighted(topics, 4) { rnd.nextDouble() }
            assertEquals(4, picks.size)
            assertEquals("picks must be distinct: $picks", 4, picks.toSet().size)
        }
        // Asking for more than the pool holds yields the whole pool, once each.
        val all = HomeFeedEngine.pickDistinctWeighted(topics, 99) { rnd.nextDouble() }
        assertEquals(14, all.size)
        assertEquals(14, all.toSet().size)
        assertTrue(HomeFeedEngine.pickDistinctWeighted(emptyList<Pair<String, Double>>(), 4).isEmpty())

        val weighted = listOf("heavy" to 10.0, "light" to 1.0, "other" to 1.0)
        var heavy = 0
        repeat(2000) {
            if (HomeFeedEngine.pickDistinctWeighted(weighted, 1) { rnd.nextDouble() }.first() == "heavy") heavy++
        }
        val share = heavy / 2000.0
        assertTrue("heavy share was $share, expected ~0.83", share > 0.75 && share < 0.90)
    }

    // ── related + comments (A4) ─────────────────────────────────────────────

    private val watchNextPage = """
    {"contents":{"twoColumnWatchNextResults":{
      "results":{"results":{"contents":[
        {"itemSectionRenderer":{"sectionIdentifier":"comment-item-section","contents":[
          {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"COMMENT_SECTION_TOKEN"}}}}]}}]}},
      "secondaryResults":{"secondaryResults":{"results":[
        {"compactVideoRenderer":{"videoId":"relatedvid1","title":{"simpleText":"Rel 1"},
          "longBylineText":{"runs":[{"text":"Chan","navigationEndpoint":{"browseEndpoint":{"browseId":"UCabc123"}}}]},
          "lengthText":{"simpleText":"3:00"},"thumbnail":{"thumbnails":[{"url":"https://t/r1.jpg","width":336}]}}},
        {"compactVideoRenderer":{"videoId":"seedvideo00","title":{"simpleText":"The seed itself"},
          "thumbnail":{"thumbnails":[{"url":"https://t/s.jpg","width":336}]}}},
        {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"RELATED_TOKEN"}}}}]}}}}}
    """

    private val commentsPage = """
    {
     "frameworkUpdates":{"entityBatchUpdate":{"mutations":[
       {"entityKey":"KEY1","payload":{"commentEntityPayload":{
         "key":"KEY1",
         "properties":{"commentId":"Ugc1","content":{"content":"First comment text"},"publishedTime":"2 days ago"},
         "author":{"channelId":"UCauth1","displayName":"Author One","avatarThumbnailUrl":"https://a/1.jpg","isVerified":true,"isCreator":false},
         "toolbar":{"likeCountNotliked":"1.2K","replyCount":"3"}}}},
       {"payload":{"engagementToolbarStateEntityPayload":{"key":"TB1","heartState":"TOOLBAR_HEART_STATE_HEARTED"}}},
       {"entityKey":"KEY2","payload":{"commentEntityPayload":{
         "key":"KEY2",
         "properties":{"commentId":"Ugc2","content":{"content":"Second"},"publishedTime":"1 hour ago"},
         "author":{"channelId":"UCauth2","displayName":"Author Two","avatarThumbnailUrl":"https://a/2.jpg","isCreator":true},
         "toolbar":{"likeCountNotliked":"5"}}}}
     ]}},
     "onResponseReceivedEndpoints":[{"reloadContinuationItemsCommand":{"continuationItems":[
       {"commentsHeaderRenderer":{"countText":{"runs":[{"text":"1,234 Comments"}]},
         "sortMenu":{"sortFilterSubMenuRenderer":{"subMenuItems":[
           {"title":"Top comments","serviceEndpoint":{"continuationCommand":{"token":"TOP_TOKEN"}}},
           {"title":"Newest first","serviceEndpoint":{"continuationCommand":{"token":"NEWEST_TOKEN"}}}]}}}},
       {"commentThreadRenderer":{
         "commentViewModel":{"commentViewModel":{"commentId":"Ugc1","commentKey":"KEY1","toolbarStateKey":"TB1","pinnedText":"Pinned by Creator"}},
         "replies":{"commentRepliesRenderer":{"contents":[
           {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"REPLIES_TOKEN_1"}}}}]}}}},
       {"commentThreadRenderer":{
         "commentViewModel":{"commentViewModel":{"commentId":"Ugc2","commentKey":"KEY2","toolbarStateKey":"TB2"}}}},
       {"continuationItemRenderer":{"continuationEndpoint":{"continuationCommand":{"token":"MORE_COMMENTS"}}}}
     ]}}]}
    """

    @Test
    fun `parseRelated drops the seed video and takes the related continuation`() {
        val rel = InnertubeParser.parseRelated(parse(watchNextPage), "seedvideo00")
        assertEquals(listOf("relatedvid1"), rel.videos.map { it.id })
        assertEquals("Chan", rel.videos[0].author)
        assertEquals("RELATED_TOKEN", rel.continuation)
    }

    @Test
    fun `findCommentSectionToken locates the comment placeholder section`() {
        assertEquals("COMMENT_SECTION_TOKEN", CommentsParser.findCommentSectionToken(parse(watchNextPage)))
    }

    @Test
    fun `comments join entity mutations with the thread view-models`() {
        val parsed = CommentsParser.parse(parse(commentsPage))
        assertEquals(2, parsed.page.comments.size)
        val c1 = parsed.page.comments[0]
        assertEquals("Ugc1", c1.id)
        assertEquals("Author One", c1.author)
        assertEquals("First comment text", c1.text)
        assertEquals("1.2K", c1.likeText)
        assertEquals("2 days ago", c1.publishedText)
        assertEquals(3, c1.replyCount)
        assertTrue(c1.hasReplies)
        assertTrue(c1.isPinned)
        assertTrue(c1.isHearted)
        assertTrue(c1.authorIsVerified)
        assertEquals("https://a/1.jpg", c1.authorAvatar)

        val c2 = parsed.page.comments[1]
        assertEquals("Author Two", c2.author)
        assertTrue(c2.authorIsCreator)
        assertFalse(c2.isHearted)
        assertFalse(c2.isPinned)

        assertEquals("1,234 Comments", parsed.page.totalText)
        assertEquals("MORE_COMMENTS", parsed.continuation)
        assertEquals("REPLIES_TOKEN_1", parsed.replyTokens["Ugc1"])
        assertNull(parsed.replyTokens["Ugc2"])
        assertEquals("NEWEST_TOKEN", parsed.sortTokens["Newest first"])
        assertFalse(parsed.page.disabled)
    }

    @Test
    fun `comments-off placeholder is reported as disabled, not as a parse failure`() {
        val off = CommentsParser.parse(
            parse("""{"contents":{"messageRenderer":{"text":{"runs":[{"text":"Comments are turned off."}]}}}}"""),
        )
        assertTrue(off.page.disabled)
        assertTrue(off.page.comments.isEmpty())
    }

    @Test
    fun `legacy commentRenderer shape still parses`() {
        val legacy = CommentsParser.parse(
            parse(
                """{"onResponseReceivedEndpoints":[{"appendContinuationItemsAction":{"continuationItems":[
                  {"commentRenderer":{"commentId":"UgcOld","authorText":{"simpleText":"Old Author"},
                    "contentText":{"runs":[{"text":"legacy "},{"text":"reply"}]},
                    "voteCount":{"simpleText":"7"},"publishedTimeText":{"simpleText":"1 year ago"},
                    "authorThumbnail":{"thumbnails":[{"url":"https://a/old.jpg","width":48}]},
                    "authorIsChannelOwner":true}}]}}]}""",
            ),
        )
        assertEquals(1, legacy.page.comments.size)
        assertEquals("Old Author", legacy.page.comments[0].author)
        assertEquals("legacy reply", legacy.page.comments[0].text)
        assertTrue(legacy.page.comments[0].authorIsCreator)
    }

    // ── channels + playlists (A5) ───────────────────────────────────────────

    @Test
    fun `channelInfo reads the classic C4 header`() {
        val c4 = InnertubeParser.channelInfo(
            parse(
                """{"metadata":{"channelMetadataRenderer":{"title":"Classic Chan"}},
                   "header":{"c4TabbedHeaderRenderer":{
                     "title":{"simpleText":"Classic Chan"},
                     "avatar":{"thumbnails":[{"url":"//yt3/av.jpg","width":176}]},
                     "banner":{"thumbnails":[{"url":"https://yt3/banner.jpg","width":1060}]},
                     "subscriberCountText":{"simpleText":"1.5M subscribers"}}}}""",
            ),
            "UCclassic01",
        )
        assertEquals("UCclassic01", c4.id)
        assertEquals("Classic Chan", c4.name)
        assertEquals("https://yt3/av.jpg", c4.avatar)
        assertEquals("https://yt3/banner.jpg", c4.banner)
        assertEquals("1.5M subscribers", c4.subscriberText)
    }

    @Test
    fun `channelInfo reads the newer PageHeader shape and skips non-subscriber metadata parts`() {
        val ph = InnertubeParser.channelInfo(
            parse(
                """{"metadata":{"channelMetadataRenderer":{"title":"Modern Chan"}},
                   "header":{"pageHeaderRenderer":{"content":{"pageHeaderViewModel":{
                     "image":{"decoratedAvatarViewModel":{"avatar":{"avatarViewModel":{
                       "image":{"sources":[{"url":"https://yt3/modern-av.jpg","width":160}]}}}}},
                     "banner":{"imageBannerViewModel":{"image":{"sources":[{"url":"https://yt3/modern-banner.jpg","width":1060}]}}},
                     "metadata":{"contentMetadataViewModel":{"metadataRows":[
                       {"metadataParts":[{"text":{"content":"@modernchan"}},{"text":{"content":"842K subscribers"}}]}]}}}}}}}""",
            ),
            "UCmodern002",
        )
        assertEquals("Modern Chan", ph.name)
        assertEquals("https://yt3/modern-av.jpg", ph.avatar)
        assertEquals("https://yt3/modern-banner.jpg", ph.banner)
        assertEquals("842K subscribers", ph.subscriberText)
    }

    @Test
    fun `collectPlaylists handles both shapes, strips VL, and rejects non-playlist ids`() {
        val pls = InnertubeParser.collectPlaylists(
            parse(
                """{"contents":{"items":[
                  {"gridPlaylistRenderer":{"playlistId":"PLabc123","title":{"runs":[{"text":"Classic Playlist"}]},
                    "thumbnail":{"thumbnails":[{"url":"https://t/pl.jpg","width":360}]},
                    "videoCountShortText":{"simpleText":"42"}}},
                  {"lockupViewModel":{"contentId":"VLPLxyz789","contentType":"LOCKUP_CONTENT_TYPE_PLAYLIST",
                    "contentImage":{"collectionThumbnailViewModel":{"primaryThumbnail":{"thumbnailViewModel":{
                      "image":{"sources":[{"url":"https://t/pl2.jpg","width":360}]}}}}},
                    "metadata":{"lockupMetadataViewModel":{"title":{"content":"Lockup Playlist"},
                      "metadata":{"contentMetadataViewModel":{"metadataRows":[
                        {"metadataParts":[{"text":{"content":"12 videos"}}]}]}}}}}},
                  {"gridPlaylistRenderer":{"playlistId":"NOTAPLAYLIST","title":{"simpleText":"Nope"}}},
                  {"gridPlaylistRenderer":{"playlistId":"PLabc123","title":{"simpleText":"Duplicate"}}}
                ]}}""",
            ),
        )
        assertEquals(2, pls.size)
        assertEquals("PLabc123", pls[0].id)
        assertEquals("Classic Playlist", pls[0].title)
        assertEquals("42", pls[0].videoCount)
        assertEquals("PLxyz789", pls[1].id) // VL prefix stripped
        assertEquals("Lockup Playlist", pls[1].title)
        assertEquals("12 videos", pls[1].videoCount)
    }

    @Test
    fun `channel tab params are the expected protobuf values`() {
        assertEquals("EgZ2aWRlb3PyBgQKAjoA", SearchParams.CHANNEL_VIDEOS_TAB)
        assertEquals("EglwbGF5bGlzdHPyBgQKAkIA", SearchParams.CHANNEL_PLAYLISTS_TAB)
    }

    // ── suggestions ─────────────────────────────────────────────────────────

    @Test
    fun `parseSuggestions handles plain JSON and JSONP wrappers`() {
        val plain = """["que",[["query one",0],["query two",0]],{"k":1}]"""
        assertEquals(listOf("query one", "query two"), InnertubeClient.parseSuggestions(plain))
        val jsonp = """window.google.ac.h(["que",[["wrapped",0]],{"k":1}])"""
        assertEquals(listOf("wrapped"), InnertubeClient.parseSuggestions(jsonp))
        assertEquals(emptyList<String>(), InnertubeClient.parseSuggestions("garbage"))
    }
}
