package com.example.ytclient.youtube.models

import kotlinx.serialization.Serializable

/**
 * A single comment (top-level or reply). Thin, UI-friendly shape; the JS runtime translates whatever
 * youtubei.js returns (classic Comment / newer CommentView) into this. Display-only — the app is
 * anonymous, so there are no like/reply actions.
 */
@Serializable
data class Comment(
    val id: String,
    val author: String = "",
    val authorAvatar: String? = null,
    val authorIsVerified: Boolean = false,
    /** The comment is by the video's uploader. */
    val authorIsCreator: Boolean = false,
    val text: String = "",
    /** Like count as display text, e.g. "1.2K" (empty when zero/unknown). */
    val likeText: String = "",
    /** Relative time, e.g. "2 days ago". */
    val publishedText: String = "",
    val replyCount: Int = 0,
    val hasReplies: Boolean = false,
    val isPinned: Boolean = false,
    /** Hearted by the creator. */
    val isHearted: Boolean = false,
)

/** One page of top-level comments plus the continuation flag for infinite scroll. */
@Serializable
data class CommentPage(
    val comments: List<Comment> = emptyList(),
    val hasContinuation: Boolean = false,
    /** Header count text, e.g. "12K Comments" (best-effort; may be empty). */
    val totalText: String = "",
    /** True when the uploader has turned comments off for this video. */
    val disabled: Boolean = false,
)

/** Replies for a single top-level comment (first batch). */
@Serializable
data class CommentReplies(
    val replies: List<Comment> = emptyList(),
)
