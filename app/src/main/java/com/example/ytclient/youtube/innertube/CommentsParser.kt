package com.example.ytclient.youtube.innertube

import com.example.ytclient.youtube.models.Comment
import com.example.ytclient.youtube.models.CommentPage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Comment extraction — phase A4 of RUNTIME_PERF_PLAN.md.
 *
 * Comments are the most awkward Innertube surface, because current responses split each comment
 * across TWO places:
 *
 *  1. `frameworkUpdates.entityBatchUpdate.mutations[]` — the DATA (`commentEntityPayload`: author,
 *     text, published time, like count, reply count) plus separate
 *     `engagementToolbarStateEntityPayload` mutations carrying the hearted state.
 *  2. `onResponseReceivedEndpoints[].{reload,append}…continuationItems[]` — the ORDER, as
 *     `commentThreadRenderer` → `commentViewModel`, which references the entities by key.
 *
 * So parsing is: index the mutations by key, then walk the thread list and join. The older,
 * self-contained `commentRenderer` shape is still handled as a fallback ([commentFromLegacy]) —
 * YouTube has repeatedly served both, and a mixed page must not lose half its comments.
 *
 * Everything here is tolerant by design: a missing field yields a default rather than dropping the
 * comment, since a comment with no like-count is still worth showing. Total failure is handled a
 * level up, where the bridge falls back to the JS path.
 */
object CommentsParser {

    private fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject
    private fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray
    private fun JsonElement?.str(key: String): String? =
        ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** A parsed page plus the tokens needed to keep interacting with it. */
    data class Page(
        val page: CommentPage,
        /** Token for the next page of top-level comments, or null at the end. */
        val continuation: String?,
        /** commentId → token fetching that comment's replies (absent when it has none). */
        val replyTokens: Map<String, String>,
        /** Sort label (e.g. "Newest first") → token re-requesting the section in that order. */
        val sortTokens: Map<String, String>,
    )

    /**
     * The token that loads the comment section of a watch page. A `next` response doesn't include
     * comments inline — it ships a placeholder section whose continuation fetches them, which is
     * why reading comments always costs one extra round trip after the watch-next call.
     */
    fun findCommentSectionToken(nextResponse: JsonObject): String? {
        var token: String? = null
        fun walk(el: JsonElement, depth: Int) {
            if (token != null || depth > 30) return
            when (el) {
                is JsonArray -> el.forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    val section = el["itemSectionRenderer"] as? JsonObject
                    if (section != null && section.str("sectionIdentifier") == "comment-item-section") {
                        token = firstContinuationToken(section)
                        if (token != null) return
                    }
                    for ((_, v) in el) {
                        if (v is JsonObject || v is JsonArray) walk(v, depth + 1)
                        if (token != null) return
                    }
                }
                else -> {}
            }
        }
        walk(nextResponse, 0)
        return token
    }

    private fun firstContinuationToken(el: JsonElement): String? {
        var token: String? = null
        fun walk(e: JsonElement, depth: Int) {
            if (token != null || depth > 12) return
            when (e) {
                is JsonArray -> e.forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    (e["continuationCommand"] as? JsonObject)?.str("token")?.let { token = it; return }
                    for ((_, v) in e) {
                        if (v is JsonObject || v is JsonArray) walk(v, depth + 1)
                        if (token != null) return
                    }
                }
                else -> {}
            }
        }
        walk(el, 0)
        return token
    }

    /** Parse a comment-section response (first page or continuation). */
    fun parse(response: JsonObject): Page {
        val entities = indexEntities(response)
        val hearted = indexHeartStates(response)

        val comments = ArrayList<Comment>()
        val replyTokens = LinkedHashMap<String, String>()
        val sortTokens = LinkedHashMap<String, String>()
        var continuation: String? = null
        var totalText = ""
        var disabled = false

        fun walk(el: JsonElement, depth: Int) {
            if (depth > 30) return
            when (el) {
                is JsonArray -> el.forEach { walk(it, depth + 1) }
                is JsonObject -> {
                    for ((k, v) in el) {
                        when {
                            k == "commentThreadRenderer" && v is JsonObject -> {
                                threadComment(v, entities, hearted)?.let { (c, replyToken) ->
                                    comments.add(c)
                                    replyToken?.let { replyTokens[c.id] = it }
                                }
                            }
                            // A bare commentRenderer outside a thread = a reply page's item.
                            k == "commentRenderer" && v is JsonObject -> {
                                commentFromLegacy(v)?.let(comments::add)
                            }
                            // A bare commentViewModel outside a thread, same case, new shape.
                            k == "commentViewModel" && v is JsonObject && el["commentThreadRenderer"] == null -> {
                                commentFromViewModel(v, entities, hearted)?.let(comments::add)
                            }
                            k == "commentsHeaderRenderer" && v is JsonObject -> {
                                totalText = InnertubeParser.text(v["countText"])
                                    .ifBlank { InnertubeParser.text(v["commentsCount"]) }
                                (v.obj("sortMenu").obj("sortFilterSubMenuRenderer")
                                    ?.get("subMenuItems") as? JsonArray)?.forEach { item ->
                                    val o = item as? JsonObject ?: return@forEach
                                    val label = o.str("title") ?: InnertubeParser.text(o["title"])
                                    val t = firstContinuationToken(o)
                                    if (!label.isNullOrBlank() && t != null) sortTokens[label] = t
                                }
                            }
                            // "Comments are turned off" placeholder.
                            k == "messageRenderer" && v is JsonObject -> {
                                val msg = InnertubeParser.text(v["text"])
                                if (msg.contains("turned off", true) || msg.contains("disabled", true)) {
                                    disabled = true
                                }
                            }
                            k == "continuationItemRenderer" && v is JsonObject -> {
                                if (continuation == null) continuation = firstContinuationToken(v)
                            }
                            v is JsonObject || v is JsonArray -> walk(v, depth + 1)
                        }
                    }
                }
                else -> {}
            }
        }
        walk(response, 0)

        return Page(
            page = CommentPage(
                comments = comments,
                hasContinuation = continuation != null,
                totalText = totalText,
                disabled = disabled,
            ),
            continuation = continuation,
            replyTokens = replyTokens,
            sortTokens = sortTokens,
        )
    }

    /** commentId (and entity key) → payload, from the response's entity mutations. */
    private fun indexEntities(response: JsonObject): Map<String, JsonObject> {
        val out = HashMap<String, JsonObject>()
        val mutations = InnertubeParser.subObject(response, "frameworkUpdates", "entityBatchUpdate")
            ?.get("mutations") as? JsonArray ?: return out
        for (m in mutations) {
            val payload = (m as? JsonObject).obj("payload").obj("commentEntityPayload") ?: continue
            payload.str("key")?.let { out[it] = payload }
            payload.obj("properties")?.str("commentId")?.let { out[it] = payload }
        }
        return out
    }

    /** toolbarStateKey → true when the creator hearted that comment. */
    private fun indexHeartStates(response: JsonObject): Set<String> {
        val out = HashSet<String>()
        val mutations = InnertubeParser.subObject(response, "frameworkUpdates", "entityBatchUpdate")
            ?.get("mutations") as? JsonArray ?: return out
        for (m in mutations) {
            val p = (m as? JsonObject).obj("payload").obj("engagementToolbarStateEntityPayload") ?: continue
            val key = p.str("key") ?: continue
            if (p.str("heartState")?.contains("HEARTED", true) == true) out.add(key)
        }
        return out
    }

    /** A thread → its top-level comment plus the token for its replies (if any). */
    private fun threadComment(
        thread: JsonObject,
        entities: Map<String, JsonObject>,
        hearted: Set<String>,
    ): Pair<Comment, String?>? {
        val vm = thread.obj("commentViewModel").obj("commentViewModel")
            ?: thread.obj("commentViewModel")
        val comment = when {
            vm != null -> commentFromViewModel(vm, entities, hearted)
            else -> thread.obj("comment").obj("commentRenderer")?.let(::commentFromLegacy)
        } ?: return null
        val replyToken = thread["replies"]?.let { firstContinuationToken(it) }
        val withReplies = if (replyToken != null && !comment.hasReplies) {
            comment.copy(hasReplies = true)
        } else {
            comment
        }
        return withReplies to replyToken
    }

    /** The modern shape: a view-model referencing entity payloads by key. */
    private fun commentFromViewModel(
        vm: JsonObject,
        entities: Map<String, JsonObject>,
        hearted: Set<String>,
    ): Comment? {
        val commentId = vm.str("commentId") ?: return null
        val entity = vm.str("commentKey")?.let(entities::get) ?: entities[commentId]
        val props = entity.obj("properties")
        val author = entity.obj("author")
        val toolbar = entity.obj("toolbar")
        val toolbarStateKey = vm.str("toolbarStateKey")

        val replyCount = toolbar?.str("replyCount")?.filter(Char::isDigit)?.toIntOrNull() ?: 0
        return Comment(
            id = commentId,
            author = author?.str("displayName").orEmpty(),
            authorAvatar = author?.str("avatarThumbnailUrl"),
            authorIsVerified = author?.get("isVerified")?.asBoolean() ?: false,
            authorIsCreator = author?.get("isCreator")?.asBoolean() ?: false,
            text = props.obj("content")?.str("content").orEmpty(),
            likeText = toolbar?.str("likeCountNotliked").orEmpty(),
            publishedText = props?.str("publishedTime").orEmpty(),
            replyCount = replyCount,
            hasReplies = replyCount > 0,
            isPinned = !vm.str("pinnedText").isNullOrBlank(),
            isHearted = toolbarStateKey != null && toolbarStateKey in hearted,
        )
    }

    /** The older, self-contained shape. */
    private fun commentFromLegacy(node: JsonObject): Comment? {
        val id = node.str("commentId") ?: return null
        val replyCount = (node["replyCount"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
        return Comment(
            id = id,
            author = InnertubeParser.text(node["authorText"]),
            authorAvatar = InnertubeParser.pickThumbnail(
                node.obj("authorThumbnail")?.get("thumbnails") as? JsonArray,
            ),
            authorIsVerified = node["authorCommentBadge"] != null,
            authorIsCreator = node["authorIsChannelOwner"]?.asBoolean() ?: false,
            text = InnertubeParser.text(node["contentText"]),
            likeText = InnertubeParser.text(node["voteCount"]),
            publishedText = InnertubeParser.text(node["publishedTimeText"]),
            replyCount = replyCount,
            hasReplies = replyCount > 0,
            isPinned = node["pinnedCommentBadge"] != null,
            isHearted = node["creatorHeart"] != null,
        )
    }

    private fun JsonElement?.asBoolean(): Boolean =
        (this as? JsonPrimitive)?.content?.equals("true", ignoreCase = true) == true
}
