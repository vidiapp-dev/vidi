package com.example.ytclient.youtube.models

/**
 * Canonical list of "For you" home-feed topics — the labels [com.example.ytclient.youtube.bridge
 * .YoutubeBridge.getHomeFeed]'s `enabledTopics` accepts, matching the JS side's topic → query
 * mapping (see `HOME_TOPIC_QUERY`/`homeTopicQuery` in index.js). Kept in this package (not
 * `ui.home`) so both the UI layer (HomeViewModel, the topic-settings sheet) and the bridge's
 * default parameter can reference the same list without inverting the ui → bridge dependency
 * direction.
 *
 * [LibraryStore.disabledTopics] stores which of these the user switched OFF, absence-based (an
 * empty/missing entry means "enabled") — so adding a new topic here later enables it by default
 * for existing installs instead of requiring a migration.
 */
object HomeTopics {
    val ALL: List<String> = listOf(
        "Music",
        "Gaming",
        "News",
        "Live",
        "Movies",
        "Science",
        "Technology",
        "DIY",
        "Nature",
        "Cooking",
        "Sports",
        "Travel",
        "Comedy",
        "History",
    )
}
