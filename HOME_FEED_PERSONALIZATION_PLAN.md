# Home feed personalization — implementation plan

## Why / current state
`pullHomeSource`/`getHomeFeed`/`getMoreHomeFeed` (js-runtime/src/index.js) do candidate
**generation** only — round-robin across sources (trending, subscribed-channel uploads,
related-to-history, search terms) with dedup, no scoring. Order is "whichever source's
turn it is," not relevance. `HomeViewModel.kt` already proved out one Kotlin-side
post-processing pass (`fillMissingAuthors`, using `LibraryStore` as ground truth) — this
plan generalizes that pattern into a real ranking layer plus several new signal sources.

## Design principle
Keep the JS runtime "dumb" (fetch + normalise candidates only, no personalization
intelligence — it has no access to `LibraryStore` and shouldn't need it). All ranking,
scoring, blocking, and diversity logic lives in Kotlin against `LibraryStore`, in a new
`HomeRanker` class. Ship phase-by-phase as small, independently revertible diffs —
nothing here can be compile-tested in this environment, so each phase should be built,
installed, and sanity-checked before starting the next.

---

## Phase 1 — Ranking foundation + "not interested" (do first, unlocks everything else)

**New file**: `app/src/main/java/com/example/ytclient/ui/home/HomeRanker.kt`

```kotlin
class HomeRanker(private val library: LibraryStore) {
    data class Ranked(val video: SearchVideo, val reason: FeedReason)
    enum class FeedReason { SUBSCRIBED, HISTORY_RELATED, KEYWORD_MATCH, TRENDING, EXPLORATION }

    fun rank(candidates: List<SearchVideo>): List<Ranked> { ... }
}
```

v1 score components (weighted sum, tune empirically):
- **Subscribed bonus** — flat boost if `video.channelId` is in `library.subscriptions`.
- **Channel affinity** — from `library.history`, per channelId:
  `sum(watchedFraction * recencyWeight(lastPlayedAtMs))` across that channel's entries.
  Rewards channels you actually finish videos from, not just open.
- **Block filter** — hard-exclude if channelId/videoId is in the new hidden lists (below).

**Extend `LibraryStore`/`LibraryModels.kt`** with a block-list, same pattern as
`Subscription`:
```kotlin
@Serializable data class HiddenChannel(val channelId: String, val hiddenAtMs: Long = 0L)
// + hiddenVideoIds: Set<String> (no metadata needed, one-shot dismissal)
```
Methods: `hideChannel(channelId)`, `unhideChannel(channelId)`, `hideVideo(videoId)`,
exposed as `StateFlow<Set<String>>` (mirrors `subscribedChannelIds`).

**UI**: add a long-press / overflow (⋮) menu to the home feed's `VideoCard` with "Not
interested" (hide video) and "Don't recommend this channel" (hide channel). This is the
first user-facing control over the algorithm — currently there is none.

**Wire into `HomeViewModel`**: run `HomeRanker.rank(...)` (or fold it into
`fillMissingAuthors`'s existing post-processing spot) in `loadFeed`/`refresh`/`loadMore`,
filtering hidden channels/videos before the ranker even scores them.

Files touched: `HomeRanker.kt` (new), `LibraryStore.kt`, `LibraryModels.kt`,
`HomeViewModel.kt`, the home `VideoCard` composable (+ a small shared card-menu
composable if one doesn't exist). **Effort: medium.**

---

## Phase 2 — Recency decay (subsumes "session momentum")

Realization: a short decay window on `lastPlayedAtMs` *is* session momentum — no
separate in-memory tracker needed. One `recencyWeight(ts)` function, tuned so:
- last ~2 hours → strong boost (this session's topic dominates immediately)
- last ~week → moderate boost (this week's interests)
- last ~month → mild boost, tapering toward the `MAX_HISTORY`/30-day retention cutoff

Replaces the current uniform `historySeedIds().take(3)` cap with a weighted
contribution — old binge-watched topics stop permanently dominating, but a video from
5 minutes ago immediately influences the next `loadMore()` page. Pure addition to
`HomeRanker`'s channel-affinity component from Phase 1. **Effort: small.**

---

## Phase 3 — Diversity cap + exploration slots

Post-ranking interleave step (`HomeRanker.interleave(ranked: List<Ranked>): List<Ranked>`,
called after scoring, before returning to `HomeViewModel`):
- **Diversity cap**: no more than N (e.g. 3) cards from the same channelId per page —
  prevents one strongly-scored channel from dominating a whole screen.
- **Exploration slots**: reserve ~1-in-8 positions for a wildcard pulled from the
  *lowest*-scored-but-not-blocked trending candidates whose channel hasn't appeared
  recently — deliberate anti-filter-bubble mechanism. Tag these `FeedReason.EXPLORATION`
  so Phase 6's transparency label can say "Something different" instead of pretending
  it's a strong match.

**Effort: small**, pure function over the already-ranked list — no new data sources.

---

## Phase 4 — Keyword affinity + keyword-seeded search

Small local tokenizer (lowercase, strip punctuation, stopword list) over
`library.history` titles, same completion+recency weighting as channel affinity, kept
as an in-memory top-N (~30-50) map recomputed per feed load (cheap at `MAX_HISTORY =
500`). Two uses:
1. **Ranking input** — title-keyword overlap becomes another `HomeRanker` score term
   (`FeedReason.KEYWORD_MATCH`), catching interest that isn't captured by channel
   affinity alone (e.g. you watch phonk mixes from many different small channels).
2. **New seed source** — extend `HomeViewModel.searchSeedTerms()` to blend explicit
   `library.searchHistory` with the top derived keywords, feeding `kind: 'search'`
   sources in `pullHomeSource` that aren't things you ever typed but reflect what you
   actually watch.

Files: new small util (`TitleKeywords.kt` or a function inside `HomeRanker`),
`HomeViewModel.searchSeedTerms()`. **Effort: medium** (tokenizer + stopword list is the
only fiddly part).

---

## Phase 5 — Playlist signal

Adding a video to Watch Later / a playlist is a deliberate action, stronger than a
passive watch. Fold `library.playlists.value.flatMap { it.videos }` into the same
channel/keyword affinity inputs from Phases 1 and 4, weighted higher than a plain watch
(no completion fraction to lean on, so treat "added to playlist" as equivalent to a
~0.8-watched-fraction event). **Effort: small**, additive to existing affinity maps.

---

## Phase 6 — Transparency labels ("Because you...")

Once `HomeRanker.Ranked` carries a `FeedReason` (Phase 1) plus enough specificity
(which channel/keyword triggered it — extend the enum to carry a payload, e.g.
`HISTORY_RELATED(channelName)` / `KEYWORD_MATCH(keyword)`), surface it as a small
caption/chip under the card: "Because you watched CoolChannel," "New from a channel you
follow," "Something different." Wire the card-menu's "Not interested" (Phase 1) to be
reason-aware — hiding a `KEYWORD_MATCH` card can offer "stop matching '<keyword>'"
instead of only "hide this channel." **Effort: medium**, mostly UI — depends on Phase 1
+ Phase 4.

---

## Phase 7 (stretch) — Two-hop channel discovery

New JS bridge method, e.g. `discoverSimilarChannels(channelIdsJson)`: for a sample of
subscribed channels, pull `getRelated`/`getChannelVideos` and collect channelIds that
recur across *multiple different* subscribed channels' related lists (excluding
already-subscribed) — a poor man's collaborative filtering using only calls the bridge
already makes, no external data. This is materially more expensive (many chained
network calls) and speculative in value, so: run it as a background, infrequent job
(once/day, or once on cold start after the main feed has already loaded — don't block
first paint), cache the result (`LibraryStore` or a simple local cache keyed by day),
and surface discovered channels as a new low-weight `kind: 'discover'` source alongside
the existing ones. **Effort: large.** Do this last, and only if Phases 1-6 feel like
they're not enough — validate the earlier, cheaper wins qualitatively first.

---

## Phase 8 — Topic settings + Movies clip filter + month-window search seeds

Three independent, small changes shipped together because they touch the same call path
(`getHomeFeed`'s topic blend / search seeds), prompted by direct feedback on the shipped
feed: (a) topic blocks felt monotone, (b) "Movies" surfaced whole 2-hour uploads instead
of anything scrollable, (c) only the single most-recent search term ever reached the feed.

- **More topics + on/off toggles.** `youtube/models/HomeTopics.kt` is now the canonical
  topic list (Music/Gaming/News/Live/Movies + Science/Technology/DIY/Nature/Cooking/
  Sports/Travel/Comedy/History). `LibraryStore.disabledTopics` stores which the user
  switched off (absence-based, so new topics default to enabled for existing installs);
  a settings sheet off a gear icon on the Home screen (`HomeScreen.kt`) edits it via
  `HomeViewModel.setTopicEnabled`. The enabled subset is threaded through
  `YoutubeBridge.getHomeFeed`'s new `enabledTopics` param → `getHomeFeed`'s
  `enabledTopicsJson` → `fetchTopicsBlend(it, topics)` in index.js, replacing the old
  hardcoded `HOME_TOPIC_TERMS` call site (which still exists as the default/fallback).
- **Movies → clips/trailers, not full movies.** `HOME_TOPIC_QUERY`/`homeTopicQuery` in
  index.js special-case "Movies": the actual query sent is `"movie trailer"` with
  `duration: 'under_three_mins'` (youtubei.js `SearchFilters`), which excludes virtually
  all full-length uploads while still matching trailers/teasers/clips. The topic's
  display label ("Movies") is unchanged — only the underlying query/filters differ.
- **Search seeds: a month of terms, not just the latest one.** `SearchHistoryEntry` adds
  a `searchedAtMs` timestamp to `LibraryStore.searchHistory` (migrated from the old plain
  `List<String>` shape on first load). `LibraryStore.recentSearchTerms(withinDays, limit)`
  filters to a recency window; `HomeViewModel.searchSeedTerms()` now calls it with a
  30-day window instead of blindly taking the top 5 by recency-of-storage. `getHomeFeed`
  itself still only *awaits* one term on page 1 (`MAX_P1_SEARCH_TERMS`) — the rest reach
  the feed via `getMoreHomeFeed`'s round-robin, same as before.

**Effort: medium** (spans Kotlin + index.js + a small new settings UI, but each piece is
independently simple). Files: `HomeTopics.kt` (new), `LibraryStore.kt`,
`LibraryModels.kt`, `YoutubeBridge(Impl).kt`, `HomeViewModel.kt`, `HomeScreen.kt`,
index.js (`HOME_TOPIC_QUERY`, `fetchTopicsBlend`, `getHomeFeed`).

---

## Phase 9 — Score-aware diversify (replaces channel-only round-robin)

Feedback: topic blocks ("3 movie cards then 3 news cards") survived Phase 3's
`roundRobinByChannel` in practice — that pass only diversifies by `channelId`, and a
topic search tends to return many results from a handful of large channels, so
channel-bucketing incidentally reproduced topic-shaped runs. Fix: every home-feed video
now carries a `sourceTag` (`SearchVideo.sourceTag`, e.g. `"topic:Movies"` or
`"personalized:history"` — set in index.js at each `pullHomeSource`/`fetchTopicsBlend`
call site via the new `tagSource` helper, never shown in the UI). `HomeRanker.diversify`
replaces `roundRobinByChannel`: instead of a hard bucket-and-round-robin (which
subordinates score order to bucket order), it greedily walks the score-sorted list and at
each slot takes the *highest-scoring* remaining candidate whose `channelId` AND
`sourceTag` haven't both appeared in the last `DIVERSITY_WINDOW` picks — falling back to
the best-scoring remaining item outright if nothing qualifies, so nothing is ever
dropped. This follows the score ordering much more closely than a bucket round-robin
while still breaking up same-channel/same-topic runs. Note this only applies to the "For
you" feed — tapping a category chip (e.g. "Movies") is a plain unranked category search
and intentionally shows only that category. **Effort: small**, pure function over the
already-scored list, same shape as the pass it replaces.

---

## Phase 10 — Tag affinity (weak, title-text-matched signal)

Phase 4's title-keyword affinity was removed for producing false positives on common
words shared across unrelated titles. Video **tags** (uploader-curated keywords, e.g.
"unreal engine 5", not generic title tokens) are a materially more precise signal and
were already available for free — `basic_info.tags`/`.keywords` sit on every
`getVideoInfo` response index.js already makes on video open, just previously dropped.
Now threaded through: index.js's `getVideoInfo` → `VideoInfo.tags` →
`LibraryStore.recordPlay(tags = ...)` → `HistoryEntry.tags`. `HomeRanker.tagAffinity()`
aggregates a channel-affinity-style weighted map (tag → recency×completion-weighted
score) across watch history, and `HomeRanker.score()`/`reasonFor()` give a small bonus
(`TAG_INTEREST_BONUS`, well below `SUBSCRIBED_BONUS`/`AFFINITY_WEIGHT`) — applied ONLY
when nothing stronger already scored the candidate — to feed cards whose *title*
contains one of the top-weighted tags verbatim (`FeedReason.TagInterest`, "Because you
watch '<tag>' videos"). Deliberately conservative: tags under `MIN_TAG_LENGTH` chars are
excluded (cuts down on short, generic tags like "hd" matching everywhere), and only the
top `TAG_MATCH_POOL` tags by weight are ever matched against. Worth watching for false
positives in practice the same way Phase 4's removal was motivated by them — if it turns
out too noisy, the fix is likely narrowing the match to whole-word/phrase boundaries
rather than a plain substring check. **Effort: medium**, spans index.js + 3 Kotlin
models + `HomeRanker`.

---

## Phase 11 — Weighted bucket blending (removes the getHomeFeed/getMoreHomeFeed asymmetry)

Prompted by analyzing the actual round-robin math once phase 8 grew the topic list from 5 to 14:
with every personalization cap maxed out (10 subscriptions, 3 history seeds, 5 search terms, 8
discovered channels), the OLD design gave the topic blend — despite now containing 14 topics — a
flat 1-of-27 "turn" share in `getMoreHomeFeed`'s round-robin, while subscriptions + discovered
channels alone ate ~two-thirds of turns. That's the opposite of a good "explore vs. exploit"
balance: an established user's feed drifted hard toward "more of what you already do," with
topic/genuinely-new content squeezed to a sliver. Separately, `getMoreHomeFeed` pulled a turn's
ENTIRE raw page (often 20-30 items) uncapped, so a single scroll batch was typically dominated by
just one source — invisible only because `HomeRanker` used to re-diversify the WHOLE accumulated
feed on every call (see phase 9), which won't be true once ranking becomes stable/append-only.

Fix, index.js: every home-feed candidate now comes from exactly one of 5 buckets — topics /
subscribed channels ("channels") / history-related ("history") / search terms ("search") /
discovered channels ("discover") — each wrapped as ONE `'group'` round-robin source (the
generalized form of the old topics-only meta-source; see `pullHomeSource`'s `'group'` case) so a
bucket's overall turn share never depends on how many individual leaves (10 subscriptions vs. 1)
happen to be inside it. `HOME_BUCKET_WEIGHTS` gives each bucket a RELATIVE share instead of a flat
1-of-N — `{ topics: 4, channels: 6, history: 4, search: 3, discover: 3 }` (sums to 20 for clean
round numbers: topics 20%, channels 30%, history 20%, search 15%, discover 15%) — subscriptions
get a deliberate bump over discover, since subscribing is an explicit, high-confidence signal
while phase 7's discovery is a speculative, one-hop-removed inference that shouldn't compete with
it as an equal. `interleaveWeighted` round-robin-interleaves the weighted copies (same trick
`fetchTopicsBlend` already used for individual topics) so consecutive turns alternate buckets
instead of running one bucket's whole allotment before the next starts. `PER_TURN_PULL_CAP` (4)
bounds how many videos a single turn can contribute — `pullHomeSourceCapped`/`warmHomeSource`
buffer any surplus a page fetch returned in `src.pending` rather than discarding it, so nothing
fetched is wasted, it's just doled out across more turns.

`getHomeFeed`'s page 1 is now just the FIRST call into `pullBlendedBatch` — the SAME engine
`getMoreHomeFeed` calls for every later page — against a freshly-built, already-weighted session,
instead of its own bespoke "personalized picks first, topic blend fills the rest" assembly (which
could fill all 12 page-1 slots with personalized picks alone for a heavy subscriber, pushing topic
content off page 1 entirely). To keep first-paint latency where it was, all 5 buckets' first turns
are warmed CONCURRENTLY (`Promise.all` of `fetchTopicsBlend` + 4× `warmHomeSource`) before
`pullBlendedBatch` sequentially draws from them — so despite being sequential in shape, page 1's
pull never actually blocks on network I/O until a bucket's pre-warmed buffer runs dry. This also
made the old `MAX_P1_CHANNELS`/`MAX_P1_HISTORY`/`MAX_P1_SEARCH_TERMS`/`MAX_PICKS_PER_P1_SOURCE`
special-casing unnecessary — removed.

**Effort: medium**, JS-only (no Kotlin/bridge signature changes). Files: `index.js`
(`pullHomeSource`'s `'group'` case, `HOME_BUCKET_WEIGHTS`, `PER_TURN_PULL_CAP`,
`buildLeafGroup`/`interleaveWeighted`/`warmHomeSource`/`pullHomeSourceCapped`/`pullBlendedBatch`,
`fetchTopicsBlend`, `getHomeFeed`, `getMoreHomeFeed`).

---

## Phase 12 — Stable, append-only ranking (no reordering while scrolling)

Feedback: [rank] used to re-run over the WHOLE accumulated feed on every `personalizationTrigger`
tick (hide/subscribe, a watch-progress update every ~5s during ANY playback, a playlist add, a
background discovery run finishing) — any of these could shift the order of cards already
scrolled past, which reads as unpleasant flicker/reshuffling while scrolling. Fix: `HomeViewModel`
no longer derives `feed`/`feedReasons` reactively from a `combine()` pipeline over the whole raw
feed. Instead, ranking happens IMPERATIVELY, once, at the moment each batch is fetched:
`loadFeed`/`refresh` rank page 1 and `rankAndReplace` it (a legitimate reset — nothing was on
screen yet, or the user explicitly asked for a fresh one); `loadMore` ranks ONLY the newly fetched
batch and `rankAndAppendBatch`s it after everything already ranked, which is never touched again.
`_ranked: MutableStateFlow<List<HomeRanker.Ranked>>` is the stable, append-only accumulator this
builds; `library.playlists`/`library.discoveredChannels`/watch-progress changes now only affect
which score the NEXT batch gets, never retroactively re-scoring what's already visible.

Explicit hide/unhide ("Not interested" / "Don't recommend this channel") is the one deliberate
exception — the whole point of that action is to make a visible card disappear immediately. A
narrow `combine(hiddenChannelIds, hiddenVideoIds)` collector in `HomeViewModel.init` FILTERS
`_ranked` live (removal only, relative order of survivors untouched) whenever those change — not a
re-rank, just a removal, so it doesn't conflict with "never reorder."

Batch-boundary seam: since each batch is now diversified independently instead of together with
the whole accumulated feed, `HomeRanker.rank`/`diversify` gained a `previouslyShown` param —
`rankAndAppendBatch` passes the tail of what's already ranked so a new batch's first pick can't
freely repeat the exact channel/topic the previous (already-frozen) batch just ended on.

Known, accepted limitation: `injectExploration`'s ~1-in-8 slot counter now resets per batch
instead of running across the whole accumulated feed, so exploration-pick spacing is only
approximately global rather than exact. Not fixed here — the effect is a subtle frequency
statistic, not a visible clumping problem, unlike the diversify seam above.

**Effort: medium**, Kotlin-only. Files: `HomeViewModel.kt` (replaces `personalizationTrigger`/
`personalizedFeed`/`_rawFeed` with `_ranked`/`rankAndReplace`/`rankAndAppendBatch`/the hidden-filter
collector), `HomeRanker.kt` (`rank`/`diversify`'s `previouslyShown` param).

---

## Phase 13 — Region-aware News query + multi-variant Movies blend

Feedback: (a) the News topic still surfaced channels with no relevance to the user's own country
(Arabic/Sri Lankan/Nepali news channels reported) even after `RegionDetector`/`configureRegion`
started biasing `gl`/`hl`; (b) the Movies topic, having been narrowed to `duration:
'under_three_mins'` trailers only (phase 8), was asked to broaden back out to include longer
trailers, featurettes, bloopers, and interviews — without regressing to full 1-3 hour uploads.

(a): `gl`/`hl` is a SOFT ranking hint on Innertube's search endpoint, not a hard filter — on a
short, globally-popular query like plain "News" it's easily outranked by big international
channels. Fix: `RegionDetector.detectRegion` now returns a `RegionInfo(location, lang,
displayName)` instead of a bare `Pair<String?, String?>`, computing `displayName` (e.g.
"Netherlands") via `Locale("", code).getDisplayCountry(Locale.ENGLISH)`. `displayName` is threaded
through `YoutubeBridgeImpl` → `QuickJsRuntime`'s new `regionName` constructor param →
`configureRegion(location, lang, name)`'s 3rd arg → a new `regionName` module-level var in
index.js. `homeTopicQuery('News')` now returns `{ term: '<displayName> news', ... }` when a region
name is known, folding the country into the query TEXT itself — a much stronger signal than
`gl`/`hl` alone — falling back to the plain `'News'` query otherwise (no region detected).
English was chosen deliberately over the device's own display language: it's the language most
international news channels' titles/descriptions actually use, regardless of the device's
language setting.

(b): No single youtubei.js `SearchFilters` combination can express "trailer OR bloopers OR
interview" — `duration` is a single enum value and there's no boolean/OR support in the search
text. Fix: `MOVIE_TOPIC_VARIANTS` — 4 complementary queries (`movie trailer` / `movie featurette` /
`movie bloopers` / `movie cast interview`, split across the `under_three_mins` and
`three_to_twenty_mins` duration buckets since featurettes/interviews commonly run longer than
trailers/bloopers) — fetched CONCURRENTLY via a new `fetchTopicSource(it, term)` helper. For
`term === 'Movies'` this returns a nested `kind: 'group'` leaf (one `'topic'` sub-leaf per variant,
all tagged `topic:Movies`) instead of the old single leaf; `pullHomeSource`'s `'group'` case is
already fully generic (see phase 11) and needed ZERO changes to round-robin through these variants
exactly like it round-robins the top-level buckets, so Movies content now naturally interleaves
trailers/featurettes/bloopers/interviews turn-by-turn instead of exhausting one variant's page
before moving to the next.

**Effort: small**, mostly JS + a small Kotlin plumbing change. Files: `RegionDetector.kt`
(`RegionInfo` data class), `QuickJsRuntime.kt` (`regionName` param + 3rd `configureRegion` arg),
`YoutubeBridgeImpl.kt` (pass `region.displayName`), `index.js` (`regionName` var, `configureRegion`
3rd param, `homeTopicQuery`'s News case, `MOVIE_TOPIC_VARIANTS` + `fetchTopicSource` +
`fetchTopicsBlend` updated to use it).

---

## Phase 14 — Organic round-robin (per-leaf capped draining + weighted-random turn order)

Feedback: the "For you" feed reads as "always the same topic" — e.g. with Gaming as the first
enabled topic, the feed floods with Gaming videos for a long stretch before any other topic shows
up, even though `pullHomeSource`'s `'group'` case (phase 11) round-robins its leaves correctly on
paper (`src.cursor = (idx + 1) % subs.length` every call).

Root cause: the round-robin cursor only actually advances when `warmHomeSource` re-invokes
`pullHomeSource` on the group, which only happens once the group's OWN buffered `pending` is fully
drained. But a single leaf pull returns an entire raw page (a topic search page, a channel's
uploads page, …) — often 20-30 items — which the group then buffered and drained
`PER_TURN_PULL_CAP` (4) items at a time across many separate turns before that buffer ever emptied.
So the leaf cursor effectively advanced once per full PAGE, not once per TURN — whichever leaf
came up first dominated for 5-8+ turns before the next leaf ever got a chance, and since topic
order was a fixed static list (`HomeTopics.ALL` filtered by disabled topics), the same topic was
first every session. The identical bug affected every bucket, not just topics (a single subscribed
channel or search term could dominate the same way).

Fix, four parts:
1. **Per-leaf capped draining** — `pullHomeSource`'s `'group'` case now calls
   `pullHomeSourceCapped(it, sub, cap)` on the chosen leaf instead of `pullHomeSource(it, sub)`
   directly, so any leftover beyond `cap` buffers on the LEAF's own `pending`, not the group's.
   `cap` is threaded through `warmHomeSource`/`pullHomeSourceCapped`/`pullHomeSource`. Net effect:
   the group's cursor genuinely advances to the next leaf on every single visit, instead of only
   once a whole page is drained. (Bonus: a topic leaf's PRE-fetched first page, previously built in
   `fetchTopicSource` but never actually consumed — `pullHomeSource`'s old `'topic'` case always
   called `getContinuation()`, ignoring the leaf's seeded `pending` — is now genuinely served.)
   A `leafSpent(s)` helper (`exhausted` AND `pending` empty) replaces the old bare `.exhausted`
   check so a leaf with buffered-but-undrained content isn't skipped early.
2. **Weighted-random turn order** — `pullBlendedBatch` picks a bucket each turn via
   `pickWeightedRandom` (proportional to `HOME_BUCKET_WEIGHTS`) instead of walking a fixed,
   pre-expanded `interleaveWeighted` repeating pattern (topic, channel, history, search, discover,
   topic, …). Same long-run bucket mix, less mechanically predictable turn to turn. `session.sources`
   is now the plain 5-entry `{source, weight}` list (no longer pre-expanded); a `MAX_BLEND_ATTEMPTS`
   safety valve replaces the old scan's natural one-pass bound.
3. **Shuffled topic order per session** — `fetchTopicsBlend` shuffles the enabled-topics list
   before fetching, so the topic that lands first in the round-robin isn't always the same one
   (previously always whichever sorted first in `HomeTopics.ALL`).
4. **Within-page shuffling** — `tagSource` (the single choke point every home-feed source's raw
   page passes through) now shuffles before tagging, so even one source's contribution doesn't read
   as "YouTube's own ranked list, sliced into chunks." `HomeRanker`'s scoring/diversify still fully
   applies afterward on the Kotlin side — this only randomizes the order among videos that end up
   tied on-score, which is the common case for unpersonalized topic filler.
   `getRelated`/`getMoreSearch` don't route through `tagSource` and keep YouTube's own order intact,
   which is deliberate (those aren't round-robin sources).

**Effort: small**, JS-only (`index.js`): `shuffled`, `leafSpent`, `pickWeightedRandom`,
`MAX_BLEND_ATTEMPTS` added; `pullHomeSource`, `warmHomeSource`, `pullHomeSourceCapped`,
`pullBlendedBatch`, `tagSource`, `fetchTopicsBlend`, `getHomeFeed`, `getMoreHomeFeed` updated.

---

## Phase 15 — Grouped-row ("Netflix") home layout

Feedback: the feed is one long flat column of look-alike cards. Everything phases 1-14 learned
about WHY a video is being shown is squeezed into a one-line caption under each card (phase 6), so
the structure of the recommendation — "here's your subscriptions, here's more of the channel you
watched last night, here's your Unreal Engine stuff" — is invisible. YouTube's own home response
has shelves for this; the anonymous session this app uses gets an empty one back, so there is no
server-side structure to adopt. It has to be built client-side from what the ranker already knows.

**Scope decision: a presentation layer only.** No new fetching, no new scoring. `HomeGrouper.group`
is a pure function from the existing append-only `_ranked` list (phase 12) + the live subscribed set
to an ordered `List<HomeRow>`; `HomeViewModel.rows` is a plain `combine(...).stateIn(...)` over
those two flows, so rows fill in and appear as the ONE existing flat continuation (`loadMore`) keeps
growing `_ranked`. Both layouts render the same videos, always.

Design points worth recording:
- **Subscriptions row is pinned first, and grouped from the SUBSCRIBED SET, not from
  `FeedReason.Subscribed`.** `HomeRanker.reasonFor` checks watch affinity before subscription, so a
  subscribed channel you actually watch is labelled `HistoryRelated` and `Subscribed` effectively
  means "subscribed but never watched." Correct for a per-card caption, wrong for a shelf titled
  "New from your subscriptions" — so the grouper tests channelId against
  `LibraryStore.subscribedChannelIds` first. (Consequence: a subscribed channel doesn't also get its
  own "Because you watched X" row. Deliberate.)
- **Other rows are ordered by the mean of their top-3 scores**, ties broken by first appearance.
  Max would let one fresh-subscription upload (`FRESH_SUB_BONUS`, 8f) hoist an otherwise weak shelf;
  a full mean would punish a long row for its own tail. `Ranked` had to start CARRYING its score for
  this — it was previously computed, sorted on, and thrown away.
- **`Unpersonalized`/`Exploration` share one pinned-last "Recommended for you" row**; neither names
  anything specific enough to title a shelf. `Discovered` gets one shared row rather than one per
  discovered channel (too weak a signal, too few videos each).
- **Rows under 3 videos fold into the catch-all** instead of being dropped, so no video is visible
  in one layout and missing in the other; rows cap at 20 videos since there's no per-row
  continuation to justify unbounded growth.
- **Known caveat**: row ORDER can shift as later batches change a row's aggregate score — a partial
  tension with phase 12's "never reorder what's already shown." Bounded in practice (pinned
  first/last rows never move, a reason's score profile is mostly set by its first batch) and left
  documented rather than solved.
- **Deferred**: true per-row pagination (a continuation per shelf). That needs a new bridge surface
  and N concurrent JS-side sessions through the single Mutex-guarded QuickJS call queue; today a
  shelf that runs out just stops growing.

The layout is a persisted user preference (`HomeLayoutMode.NETFLIX`/`CLASSIC` in a new `home_prefs`,
same idiom as `PlayerViewModel`'s SponsorBlock toggle), defaulting to `NETFLIX`, with an icon button
next to the search field to switch back. Category chips and search results are unaffected: they
carry no ranking signal, so they always render the classic list.

**Effort: medium**, Kotlin/Compose only, no JS. Files: `HomeGrouper.kt` (new: `HomeRow` +
`HomeGrouper.group`), `VideoCard.kt` (new: compact shelf card mirroring `VideoRow`'s decorations),
`HomeRanker.kt` (`Ranked.score`), `HomeViewModel.kt` (`rows`, `HomeLayoutMode`, `layoutMode`,
`setHomeLayoutMode`/`toggleHomeLayoutMode`, `prefs`), `HomeScreen.kt` (`HomeRowsList` + the toggle),
`VideoRow.kt` (`formatDuration` made `internal` so the card shares it).

---

## Phase 16 — Per-topic rows in the grouped ("Netflix") layout

Feedback: phase 15 shipped `Unpersonalized`/`Exploration` sharing one pinned-last "Recommended for
you" shelf, which is where every topic-blend pick (see phase 9's `SearchVideo.sourceTag`,
`"topic:<Name>"`) landed unless something more specific (subscription, watch history, a matched
tag) already explained it. That flattened exactly the structure the "For you" topic settings sheet
(phase 8) lets the user curate — "here's your Music picks, here's your Gaming picks" — back into
one undifferentiated pile, so the ask was for each enabled topic to get its own named shelf instead.

**Still a presentation-layer change, same scope discipline as phase 15**: no new fetching, no new
scoring weight. The only new thing is a name for a video that would otherwise fall through to
`Unpersonalized` — `HomeRanker.FeedReason.Topic(topic)`, checked in `reasonFor` LAST, after every
identity/tag-based reason phases 1-10 already established, so a topic-blend pick from a channel you
watch or are subscribed to still gets that more specific (and more honest) reason instead. Only a
video whose `sourceTag` is `"topic:<Name>"` AND has no stronger signal becomes `Topic`.

`HomeGrouper.bucketFor` gets one new branch: `Topic` buckets by the lower-cased topic name and
titles the shelf with the topic itself (e.g. "Music", "Gaming") — unlike every other per-identity
row, a row here can be large and varied, since it's every topic-blend pick nothing else claimed,
not a narrow affinity signal. `HomeViewModel.reasonInfo` gets a matching branch for the classic flat
list, which has no per-topic row of its own and instead captions the card with the topic that
surfaced it, the same way it already names a watched channel or matched tag.

Both exhaustive `when`s (`HomeGrouper.bucketFor`, `HomeViewModel.reasonInfo`) required a branch for
the new sealed variant to keep compiling — the compiler catches any future `FeedReason` addition
that forgets one.

**Effort: small**, Kotlin-only. Files: `HomeRanker.kt` (`FeedReason.Topic`, `reasonFor`),
`HomeGrouper.kt` (`bucketFor`), `HomeViewModel.kt` (`reasonInfo`).

---

## Suggested build order

| Phase | Idea | Effort | Depends on |
|---|---|---|---|
| 1 | Ranking layer + hide/not-interested | Medium | — |
| 2 | Recency decay / session momentum | Small | 1 |
| 3 | Diversity cap + exploration slots | Small | 1 |
| 4 | Keyword affinity + keyword search seeds | Medium | 1 |
| 5 | Playlist signal | Small | 1, 4 |
| 6 | "Because you..." transparency labels | Medium | 1, 4 |
| 7 | Two-hop channel discovery | Large | 1 (stretch) |
| 8 | Topic settings + Movies clip filter + month search seeds | Medium | 1 |
| 9 | Score-aware diversify (replaces channel-only round-robin) | Small | 3 |
| 10 | Tag affinity (weak, title-matched signal) | Medium | 1 |
| 11 | Weighted bucket blending (removes getHomeFeed/getMoreHomeFeed asymmetry) | Medium | 8 |
| 12 | Stable, append-only ranking (no reordering while scrolling) | Medium | 9, 11 |
| 13 | Region-aware News query + multi-variant Movies blend | Small | 8 |
| 14 | Organic round-robin (per-leaf capped draining + weighted turn order) | Small | 11 |
| 15 | Grouped-row ("Netflix") home layout | Medium | 6, 12 |
| 16 | Per-topic rows in the grouped layout | Small | 15 |

Phases 1-3 give the most noticeable improvement for the least risk (pure Kotlin,
local-only, no new JS bridge surface) and are the natural first PR. Phase 7 is the only
one that touches the JS runtime bundle and adds real network cost — worth holding until
the cheaper phases prove out.

## Phase 17 — "Because you watched <video>" rows from history provenance (2026-10-17)
Feedback: the "Because you watched …" rows take a long time to appear. Reading the code showed
the rows weren't what their title said. `FeedReason.HistoryRelated` — titled "Because you watched
<channel>" — means "this video's CHANNEL has watch affinity", and HomeGrouper sends every
subscribed channel to the subscriptions row first. So a row needed three feed videos from one
watched-but-unsubscribed channel, which only accumulates after many pages. Meanwhile the history
bucket (phase 2's related-of-history source: watch-next of the last `MAX_HISTORY_SEEDS` = 3
watches) fetched exactly the "because you watched" content — but tagged it a bare
`personalized:history` that `reasonFor` never read, so it fell through to `Unpersonalized` and was
shown under "Recommended for you".

**Done:**
- HomeFeedEngine tags related-of-history picks `personalized:history:<seedVideoId>`
  (`HISTORY_SOURCE_TAG_PREFIX`), so the provenance survives to the ranker.
- New `FeedReason.WatchedVideo(seedVideoId, seedTitle)`, checked FIRST in `reasonFor` — provenance
  answers "why is this here at all", where the affinity reasons answer "why might you like it".
  Seed titles come from local history via the memoised `Affinities` snapshot; a seed with no
  known title falls through to the other reasons rather than render an empty label.
- HomeGrouper: one row per seed, "Because you watched <video title>", keyed `watched:<seedId>`.
  Thin seed rows pool into a "Based on what you've watched" shelf, generalising phase 16's
  topics pool into an ordered `POOLS` list (watched first — it's derived from what the user did,
  a topic is a setting they left on). Both pools still dissolve as seeds/topics graduate to rows.
- `HistoryRelated` rows and captions renamed "More from <channel>" — keys unchanged so reveal
  state survives — so the two reasons stop making the same claim in the same words.
- Row titles are capped at one line, since they can now carry a full YouTube video title.

**Not changed, deliberately:** the history bucket's weight (4 of 26) and seed count (3). The
user chose relabelling over fetching more; if the watched rows still feel slow to appear once
labelled, those two numbers are the next lever.

## Testing notes
None of this can be compiled or run in this environment. Each phase should be a small,
self-contained diff; build and test on-device between phases rather than stacking
several at once, so a regression is easy to bisect.
