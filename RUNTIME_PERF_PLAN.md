# Runtime performance plan — Option D, then Option A

Tracking doc for the two-stage performance program picked on 2026-07-30 (background in
`FIXES_AND_ANALYSIS_2026-07-30.md` §4):

* **Option D** — keep QuickJS, but split it into two independent contexts (playback / browse) so
  the playback-critical path never queues behind feed/comment parsing, plus a couple of cheap
  perceived-speed wins. Days of work, immediate improvement.
* **Option A** — incrementally port the browse/read paths to native Kotlin (the NewPipe
  architecture: OkHttp → kotlinx.serialization → models, no JS). The only route to actually
  NewPipe-class latency. Done endpoint group by endpoint group, each shippable on its own.

Legend: `[ ]` todo · `[~]` in progress · `[x]` done

---

## Option D — split QuickJS, kill head-of-line blocking

### D1. Two QuickJS contexts `[x]` (2026-07-30)

One `QuickJsRuntime` instance per workload class, both owned by `YoutubeBridgeImpl`
(which is the natural router — every JS call already goes through it):

| Context | Thread | Methods |
|---|---|---|
| **playback** | `js-playback` | `warmup`, `getSabrInfo`, `getVideoInfo`, `getLegacyPlayback`, `getCaptionVtt`, `poTokenInfo`, `refreshPoToken` |
| **browse** | `js-browse` | `search`, `getSearchSuggestions`, `getMoreSearch`, `getHomeFeed`, `getMoreHomeFeed`, `getRelated`, `getMoreRelated`, `getChannelVideos`, `getChannelVideosBatch`, `discoverSimilarChannels`, `getChannelInfo`, `getChannelPlaylists`, `getPlaylistVideos`, `getComments`, `getMoreComments`, `getCommentReplies` |

Design notes / invariants:

* `QuickJsRuntime` was already fully self-contained per instance (own single-thread dispatcher,
  own `callLock`, own OkHttp client) — D1 is instantiation + routing, no engine changes. Each
  instance evaluates the same `youtube-runtime.js` bundle.
* **Session-continuation pairs must stay in one context** (they live in JS globals):
  `search`/`getMoreSearch`, `getHomeFeed`/`getMoreHomeFeed`, `getRelated`/`getMoreRelated`,
  `getComments`/`getMoreComments`/`getCommentReplies` — all browse. `getVideoInfo`/
  `getLegacyPlayback`/`getCaptionVtt` share a per-video info cache — all playback.
* Each context creates its own anonymous Innertube session (`generate_session_locally: true`), so
  the two contexts have different visitorData. That's fine: visitorData is regenerated per launch
  anyway, browse calls don't need attestation, and PO tokens are keyed per identifier (videoId /
  visitorData) in the shared `SessionStore` cache. Known benign race: both contexts read-modify-
  write `yt_po_cache_v3`; a lost entry just re-mints (<100 ms warm).
* `WebPoTokenProvider` is shared and already serialises mints behind its own `mintMutex`.
* Cost: second engine + bundle ≈ +8–15 MB RAM, one extra thread. Accepted.
* Video-open win: `getChannelInfo`/`getRelated`/`getComments` no longer sit in front of
  `getSabrInfo`. Within the playback context, `fetchSabr`-first ordering still applies (that
  comment in `PlayerViewModel.loadInternal` remains load-bearing, per-context).

### D2. Remove the startup ordering workarounds `[x]` (2026-07-30)

`App.waitBeforeWarmup()` (wait for home feed + back off while search is active) existed only
because `warmup()` shared one queue with the home feed and search suggestions. With warmup on the
playback context it starts immediately and in parallel — playback is ready sooner, cold-start
search suggestions can't get stuck behind it, and `App.searchActive` (whose only reader was
`waitBeforeWarmup`) is gone. `App.homeFeedReady` **stays**: `HomeViewModel.maybeRunDiscovery()`
still uses it to keep the discovery pass from racing the feed's first paint *within* the browse
context.

### D3. Home feed disk cache — instant first paint `[x]` (2026-07-30)

NewPipe's perceived launch speed is partly "render something immediately". Persist the first
screenful of the accumulated "For you" feed (video cards + reason labels, JSON on disk) whenever
it's (re)built; on the next cold start show it instantly with a background refresh replacing it
when page 1 lands. Stale-while-revalidate, feed only (categories/search stay live-only).
Implemented in `LibraryStore` (`feed_cache.json`, capped at 30 cards) + `HomeViewModel.loadFeed`.

### D4. `parse: false` raw-JSON endpoints `[ ]` — deliberately parked

Returning raw Innertube JSON from youtubei.js for slim endpoints would cut interpreter parse
time, but every endpoint it would help is scheduled to leave JS entirely in Option A. Skip unless
some endpoint stays JS-bound long-term.

---

## Option A — native Kotlin extraction, endpoint group by endpoint group

Goal state: JS runtime shrinks to the **playback context only** (player response / SABR info /
decipher via WebView); everything the user browses is native Kotlin on coroutines — real
parallelism, one parse per response, no bridge.

Standing design decisions (apply to every phase):

* New package `com.example.ytclient.youtube.native` (working name): `InnertubeClient` (OkHttp +
  kotlinx.serialization `JsonElement` tree-walking; reuse `App.sabrHttpClient`'s pattern, own
  client), `InnertubeSession` (visitorData generation, client version/context, gl/hl from
  `RegionDetector`), plus per-endpoint parsers emitting the EXISTING models (`SearchVideo`,
  `ChannelInfo`, `CommentPage`, …) so ViewModels/`HomeRanker` don't change at all.
* Cutover happens inside `YoutubeBridgeImpl` behind the existing `YoutubeBridge` interface —
  each phase swaps one method group from `browseRuntime.callAsync(...)` to the native client.
  Keep a debug toggle (BuildConfig or hidden setting) to flip a group back to JS while validating.
* Innertube request context mirrors what youtubei.js sends today (WEB client, same client
  version string the probing already selected, `Origin`/`Referer`/UA headers as in
  `QuickJsRuntime.performFetch`).
* Parser strategy: tolerant `JsonElement` walkers keyed on renderer names (`videoRenderer`,
  `richItemRenderer`, `lockupViewModel`, `compactVideoRenderer`, shelf/continuation wrappers) —
  port the extraction rules from `js-runtime/src/index.js` (`extractFeedVideos`,
  `normaliseVideo`, `channelHeader`, …), which are themselves the compatibility-hardened part
  worth keeping. NewPipeExtractor stays a reference, not a dependency (its threading and service
  model don't fit, and we already deviate for SABR).
* Each phase ships with a side-by-side check: log native vs JS result counts/fields for a few
  runs before removing the JS path for that group.

### A1. Foundation `[x]` (2026-07-30 — package landed as `youtube.innertube`; `native` is a
reserved word in Java so the working name from this doc's first draft was dropped)
- [x] `InnertubeSession`: context JSON builder (WEB client + pinned `clientVersion` const,
      gl/hl, shared UA). Deliberate scope cuts vs the original sketch: no visitorData GENERATION
      (anonymous works; a `visitorData` hook exists for later) and no persisted client-version
      probe (a bumpable constant suffices for browse endpoints — the probing in the JS runtime is
      a playback-client concern that stays there).
- [x] `InnertubeClient`: `search`/`browse`/`next` + continuation variants, header policy mirroring
      QuickJsRuntime's youtube.com branch, plus the session-free suggest endpoint. (`resolve_url`
      deferred until a phase actually needs it.)
- [x] `InnertubeParser`: ordered document-walk over RAW renderer JSON — classic
      `videoRenderer`/`compactVideoRenderer`/`gridVideoRenderer`/`playlistVideoRenderer`, new
      `lockupViewModel` (video + channel), `channelRenderer`, `continuationCommand` tokens — with
      index.js's policies ported: ≤480px thumbnail pick, UC-only channel ids, badge-container-
      scoped members-only filtering, EN+NL meta fallbacks. NOTE: raw shapes, not youtubei.js's
      parsed snake_case shapes — keep policies (not code) in sync with index.js while both exist.
- [x] Unit tests: `app/src/test/.../InnertubeParserTest.kt` (junit added to gradle), fixtures
      inline rather than resource files. Also verified OUTSIDE gradle with real kotlinc 2.2.10 +
      a kotlinx-json API stub: 39/39 assertions pass on the JVM (this caught a real bug — the
      Dutch "1,2 mln. weergaven" views shape — before it ever shipped).
- [ ] Replace/augment hand-cut fixtures with captured REAL responses once the first live cutover
      (A2 search) is being validated on-device.

### A2. Search `[x]` (2026-07-30 — code complete; needs on-device validation)
- [x] `getSearchSuggestions` cut over to the native suggest endpoint (session-free → skips the JS
      runtime's whole cold-start queue; biggest visible win: typing immediately after app launch).
      JS path retained as an automatic fallback while the native client earns trust.
- [x] `search` + `getMoreSearch` cut over: `NativeSearchSession` in YoutubeBridgeImpl carries the
      continuation token + cross-page seen-id dedupe (index.js's `searchSession`/`freshVideos`
      semantics), channel cards ride along with their ranked `position`. Page 1 falls back to JS
      on failure (and hands the session to the JS side); continuations deliberately do NOT cross
      sides. Category chips (HomeViewModel.loadCategory) share this session exactly like they
      shared the JS one. Both paths log `search → N videos …` lines for side-by-side comparison.
- [x] First on-device validation round: native /search 400'd (fallback carried every query, as
      designed). Root cause: the pinned WEB clientVersion was a year stale — YouTube rejects
      unknown/old versions with HTTP 400 (the working JS request bodies showed the accepted
      version). Fix: `InnertubeSession` now resolves its clientVersion lazily from the bundled
      youtubei.js runtime's CLIENTS table (`YoutubeBridgeImpl.bundledWebClientVersion`), so it
      auto-tracks every bundle rebuild; the constant remains only as a last-resort fallback.
      LESSON for later phases: never pin protocol constants that YouTube versions — derive them
      from the bundle (or probe) instead.
- [ ] Re-validate on-device (expect `native Innertube client version from bundle: …` +
      `native search(...) → N videos` and no fallback lines). If /search STILL 400s with the
      correct version, next suspects in the JS request body: the empty `params: "EgA%3D"` filter
      and visitorData. Then delete the JS fallback + `search`/`getMoreSearch`/
      `getSearchSuggestions` from index.js (bundle shrink) once trusted.

### A3. Home feed sources `[x]` (2026-07-30 — code complete; needs on-device validation)
- [x] Topic blend via native search, per-topic query table ported (localised News, Movies fanning
      out into 4 complementary narrow queries). `SearchParams` BUILDS the Innertube filter protobuf
      instead of hardcoding base64 blobs — verified to reproduce youtubei.js's known-good
      `EgIQAQ==` for type:video, with duration buckets derived the same way.
- [x] Channel uploads (browse + `CHANNEL_VIDEOS_TAB` params + continuations) for the subs and
      discover buckets, including the channel-name backfill for cards YouTube ships author-less.
- [x] Related-of-history seeds via `next`, scoped to `secondaryResults` — important: an unscoped
      walk grabs the COMMENT section's continuation token first and would page comments into the
      feed (regression-tested).
- [x] Round-robin ported to Kotlin (`HomeFeedEngine`): 5 weighted buckets, weighted-random bucket
      pick per turn, per-VIDEO leaf pick inside a group (the anti-clumping fix), per-turn caps with
      buffered leftovers, shuffle + `sourceTag` stamping. All five buckets — and all topic queries
      within the topics bucket — warm CONCURRENTLY via coroutines, so page 1 is gated by the
      slowest single source with parsing overlapped too (impossible on single-threaded QuickJS).
- [x] Bridge cutover with the same fallback discipline as A2 (page 1 may fall back to JS — an
      empty native page 1 counts as failure; continuations never cross sides).
- [x] Verified in-sandbox: picker boundaries + long-run weight shares (100k draws within 1% of
      20/30/20/15/15), params bytes, watch-next scoping, channel-title shapes — 14 more assertions.
- [x] First on-device round: correct results, but page 1 took ~20s on a fresh emulator profile.
      Cause: the group turn picked and pulled ONE leaf at a time, so every cold leaf was its own
      SEQUENTIAL round-trip — 14 enabled topics ⇒ ~12 serial searches for page 1. The JS engine
      never hit this because `fetchTopicsBlend` Promise.all'd every topic before the round-robin
      ran; the port had dropped that. Fixed by choosing a turn's leaves up front
      (`pickDistinctWeighted`, sampling WITHOUT replacement) and warming the cold ones
      concurrently — bounded to the turn's cap, so page 1 is ~3 concurrent rounds instead of ~12
      serial ones. (`buckets=1` in those logs is correct, not a bug: a fresh profile has no
      subs/history/searches/discoveries, so only the topics bucket exists.)
- [x] Second on-device round: 20s → ~9.5s of fetching, still too slow. Two more causes, both
      fixed: (a) turns kept picking COLD leaves even though warm ones held ~19 buffered videos
      each, so page 1 paid ~3 concurrent rounds for videos already fetched — group turns now
      prefer buffered leaves and only warm cold ones to fill the gap; (b) the opening warm now
      covers [INITIAL_TOPIC_BREADTH]=6 topic leaves in its single concurrent round (≈120 videos
      buffered), so page 1 and usually page 2 come from buffers. Expect ~1 network round.
      Also confirmed from the same log: the feed does NOT wait on PO-token minting (feed landed
      16.363, token minted 17.740) — they're independent, as intended.
- [x] Cold-start CPU: `YoutubeBridge.initialize()` no longer eagerly evaluates the 1.5 MB bundle
      on the BROWSE context — nothing on the startup path uses it since A2/A3. It initialises on
      first demand (related/comments/channels, or a fallback). ~4.5s elapsed between process start
      and the first feed request in the last log, with two contexts evaluating concurrently.
- [ ] Re-validate timing on-device (expect page 1 in low single-digit seconds on emulator), then
      retire `getHomeFeed`/`getMoreHomeFeed` from the JS bundle.
- [x] **2026-10-03 — the buffered-first fix above silently capped topic breadth at
      `INITIAL_TOPIC_BREADTH`.** Reported from a live run: 14 topics enabled, videos from only 5.
      Mechanism: `fetch` prefers leaves that already hold buffered videos and consults `cold` only
      when buffered can't fill `PER_TURN_PULL_CAP`. One search returns ~20 videos, so 6 warmed
      topic leaves keep `buffered.size >= 4` for ~15 turns (~5 pages at `BATCH_TARGET = 12`) — and
      Option E2's auto-page cap stops at page 4. So the other 8 topics were never queried **at all**,
      for the whole session. The latency win was real; the breadth cost was never written down.
      Two fixes:
      * `buffered`/`cold` now classify through a recursive `hasBuffered`. The old
        `pending.isNotEmpty()` asked whether THIS source holds a video, which is wrong for a nested
        group: "Movies" (`MOVIE_VARIANTS`) keeps its ~20 on child leaves and only ever one in its
        own `pending`, so it was filed `cold` right after its first video and then skipped all
        session. Plain topic leaves were unaffected, which is why this looked like "one topic is
        just quiet".
      * `broadenTopics()` gives breadth an explicit per-batch budget
        (`COLD_TOPICS_PER_BATCH = 2`, warmed concurrently so it costs one round trip) instead of
        waiting for the turn loop to run out of buffer. Skipped on page 1 so the ~1-round-trip
        page-1 figure this section fought for is untouched.
      Note the resulting trade: wider means thinner. 12 topics across 4 auto-pages is ~4 videos
      each, which only just clears `HomeGrouper.MIN_ROW_VIDEOS = 3`. If the shelves read as too
      sparse, `MAX_AUTO_PAGES` (HomeScreen) or `BATCH_TARGET` are the knobs — not
      `COLD_TOPICS_PER_BATCH`.
      Also added the two diagnostics that made this provable instead of arguable: a `session
      topics: N enabled, M warmed — warm=[…] cold=[…]` line and a per-batch `sources={…}` mix.
      `adb logcat -s HomeFeedEngine`. The engine previously logged nothing but leaf failures, so
      "topic never queried", "queried but nothing matched" and "fetched but under the row minimum"
      were indistinguishable from the outside.
- [x] **2026-10-03 — page 1 was poorer than the cache it replaced.** Reported from a phone run:
      the feed loads with topics, then "refreshes" and every topic shelf disappears, leaving only
      subscription/history cards. Not the ranker clearing anything — D3's stale-while-revalidate
      shows up to `LibraryStore.FEED_CACHE_MAX` = 30 cached cards instantly and then
      `rankAndReplace` REPLACES them wholesale with page 1. The native engine served
      `BATCH_TARGET` = 12 for every page, so that swap dropped the feed from 30 cards to 12 and
      took every shelf needing `HomeGrouper.MIN_ROW_VIDEOS` = 3 with it. D3 assumed a fresh page
      would be at least as rich as the cache — true of the JS `getHomeFeed`, which is why three
      comments in HomeScreen/HomeViewModel still described page 1 as "~30 videos" while the code
      delivered 12. Fixed with a separate `FIRST_BATCH_TARGET` = 30 (continuations stay at 12), with
      the `>= FEED_CACHE_MAX` invariant written down on the constant. Nearly free in round trips:
      the opening warm has already buffered ~20 videos per warmed leaf in every bucket, so the
      extra turns drain buffers instead of fetching. Side effect: those three "~30 videos" comments
      are accurate again.
- [x] **2026-10-04 — topic shelves couldn't coexist with the subs/history shelves at this
      breadth.** Second half of the same report. Once all five buckets were live the topics bucket
      got `BUCKET_WEIGHTS_TOPICS` 4 of 20 = 20% of turns, so ~13 topic videos arrived across page 1
      plus three auto-pages (~66 videos) — while `MIN_ROW_VIDEOS` = 3 per topic needs 36 of them
      for 12 topics, i.e. a ~180-video feed at that share. `HomeRanker.reasonFor` shrinks it
      further by design: identity reasons are checked FIRST, so a topic-sourced video from a
      channel with watch affinity is relabelled `HistoryRelated` and leaves the topics bucket
      entirely. The two asks from this debugging round were therefore in direct tension — breadth
      (reach all 14 topics) makes each topic thinner; depth (earn a shelf) needs fewer topics.
      Resolved by not making them compete:
      * `HomeGrouper` pools every under-sized per-topic row into ONE labelled "From your topics"
        shelf, pinned above the catch-all, instead of dropping them in the unlabelled
        "Recommended for you" pile. Per-bucket, not per-card — a topic strong enough for its own
        shelf still gets one, so the row is self-dissolving as topics accumulate picks across
        pages. Presentation-only, so it holds regardless of how the arithmetic lands.
      * `BUCKET_WEIGHTS_TOPICS` 4.0 → 10.0 (~38% of 26.0). Justification on the constant: every
        other bucket's weight buys presence for ONE channel/video/term, while this one is split
        across every enabled topic, so a unit here buys ~1/14th the per-topic presence the same
        unit buys elsewhere. This is the knob to turn down if subs/history feel crowded out — it
        only changes what is FETCHED, not render order (the subscriptions shelf is pinned first
        regardless).
      Also tightened the catch-all's "nothing else survived" escape hatch to test the real output
      rather than just `standalone`: it previously ignored the subscriptions row, so a feed with
      subs plus two leftovers rendered exactly the two-card strip `MIN_ROW_VIDEOS` exists to
      prevent — and the new topics row would have widened that hole.
- [x] Unrelated startup observation from the same logs, worth a look later: `Skipped 417 frames` /
      `Davey! duration=7110ms` around WebView+BotGuard bring-up, and a 2s `SynchronizedLazyImpl`
      monitor contention (two threads racing the `App.youtubeBridge` lazy). Neither is caused by
      Option A, but both hurt perceived cold-start.
      → Picked up as **Option E** below (2026-10-03). The `SynchronizedLazyImpl` contention is
      addressed in E1 (the main thread no longer resolves that lazy at all); the skipped-frames
      figure should be re-measured after E1–E4 before assuming the WebView bring-up is what causes
      it, since the same frames were also carrying `LibraryStore`'s nine decodes and a ranking pass.
      → **Re-measured 2026-10-14 (E6): on a non-debuggable `profile` build there are no `Davey!` or
      `Skipped N frames` lines at all on cold start.** The stall was debug-build cost, not a
      WebView problem to fix in code.

### A4. Watch-next + comments `[x]` (2026-08-02 — code complete; needs on-device validation)
- [x] `getRelated`/`getMoreRelated` via `next` + `parseRelated` (secondaryResults-scoped, seed
      video filtered, cross-page dedupe).
- [x] `getComments`/`getMoreComments`/`getCommentReplies` via `CommentsParser`. The awkward part:
      current responses SPLIT each comment between `frameworkUpdates…mutations[]`
      (`commentEntityPayload` = author/text/likes/replies, plus separate
      `engagementToolbarStateEntityPayload` for the hearted state) and the thread list
      (`commentThreadRenderer` → `commentViewModel`, referencing entities by key) — so parsing
      indexes the mutations, then joins while walking threads. The older self-contained
      `commentRenderer` shape is still handled, since YouTube serves both.
- [x] Costs, for expectation-setting: comments are inherently 2 round trips (a watch-next response
      only ships a placeholder section whose continuation returns the actual comments), and
      "Newest first" is 3 — its token only exists in the sort menu returned with the first page.
      TOP (the default) stays at 2.
- [x] Guardrail: an empty native page that ISN'T "comments are turned off" is treated as a parse
      failure and falls back to JS, so a future shape change degrades instead of showing "no
      comments" on a video that has them.
- [x] 33 assertions added (entity/view-model join, pinned + hearted + creator flags, reply and
      sort tokens, disabled placeholder, legacy shape, related-vs-comments token separation).
- [ ] On-device validation — comment fixtures here are hand-built from the known response shape,
      NOT captured live (the sandbox can't reach YouTube), so this is the phase most likely to
      need a tweak against a real response. Check: comment count/text/avatars, pinned + hearted
      badges, "show replies", Newest-first sorting, and a comments-disabled video.

### A5. Channel + playlists `[x]` (2026-08-02 — code complete; needs on-device validation)
- [x] `getChannelInfo`: name/avatar/banner/subscribers across all three header shapes
      (channelMetadataRenderer, classic C4, PageHeader view-models, where the subscriber count is
      buried among metadata rows next to the @handle).
- [x] `getChannelVideos` (+ author/channelId backfill from the header) and `getChannelVideosBatch`,
      the latter fanning out CONCURRENTLY across channels — that's the Subscriptions tab's entire
      load and the most visible win of this phase.
- [x] `getChannelPlaylists` / `getPlaylistVideos`: both playlist shapes (classic
      grid/playlistRenderer + lockupViewModel), `VL` prefix stripped, non-playlist ids rejected;
      playlist browse takes the `VL`-prefixed browseId.
- [x] `discoverSimilarChannels`: the two-hop pass, both hops fanned out concurrently, keeping the
      JS scoring rule (count each channel ONCE per seed — "related to multiple things you watch",
      not "related to one thing, twice") and the same seed/recurrence/result bounds.
- [x] `CHANNEL_PLAYLISTS_TAB` params verified by decoding the protobuf (tab name = "playlists")
      and re-encoding it from scratch, same as the search filters.
- [x] 20 assertions added (both header shapes, both playlist shapes, VL stripping, dupe/bad-id
      rejection, tab params).
- [ ] On-device validation: open a channel (header + uploads + playlists tabs), a playlist, the
      Subscriptions tab, and let the daily discovery pass run.

### A6. Shrink the JS runtime `[ ]`
- [ ] Delete browse code paths from `js-runtime/src/index.js`; rebuild a much smaller bundle.
- [ ] Drop the browse QuickJS context entirely (undo half of D1 — by design).
- [ ] Re-measure: cold start, home feed p50, video open p50 vs NewPipe on the same device/network.

### Measurement (do at every phase boundary)
- [ ] Baseline numbers BEFORE A1 with D in place: cold start → first feed card; tap → first frame;
      `adb shell am start -W` + simple logcat timestamps around bridge calls. Record here.
- [x] `core/perf/Startup.kt` now stamps the cold-start path. `adb logcat -s Startup` prints an
      ordered timeline (`+N ms — <stage>`) from `App.onCreate` through `HomeViewModel constructed`,
      the cached-feed seed, page 1, and the playback warmup/PO-token stages. Marks are one-shot per
      stage name so a config change can't bury the real cold start.

---

## Option E — cold start and home-feed responsiveness `[~]` (2026-10-03)

Separate from A/D because none of it is about extraction: it's main-thread I/O, image-loader
configuration, and a paging loop. Motivated by "slow to start, thumbnails take ages, feed isn't
responsive". **All of it is code-complete and NONE of it is on-device validated yet** — it could not
even be compiled in the session that wrote it (Gradle couldn't fork a worker JVM in that
environment: `java.io.IOException: Unable to establish loopback connection`). First build is
expected to be where any typo surfaces.

### E1. Main-thread work in `HomeViewModel`'s constructor `[x]` code-complete
`viewModel()` builds the VM during composition, so everything its constructor touched was on the
main thread before the first frame:
- `app.youtubeBridge` — a `synchronized` lazy `App.onCreate`'s warmup coroutine also resolved, so
  the main thread raced it and could lose. **This is the `~2 s SynchronizedLazyImpl` contention
  A3's notes flagged as "worth a look later".** Fixed on both sides: `App.onCreate` resolves it from
  a dedicated IO coroutine, and `HomeViewModel.bridge` is `by lazy` so the constructor never
  touches it.
- `app.library` — `LibraryStore`'s constructor decoded nine JSON blobs (history up to 500 entries
  with tag lists, every playlist up to 2000 videos, …) behind a blocking full-file SharedPreferences
  parse. Every collection is now `by lazy`, with `LibraryStore.prewarm()` forcing them from
  `App.onCreate`'s IO scope. Worst case the main thread now waits on the *remainder* of a load
  already in flight instead of doing all of it itself.
- The D3 cached-feed seed. Its comment justified running inline with "a ~30-card decode is a few
  ms" — true of the decode, but `rankAndReplace` → `HomeRanker.rank` reads the WHOLE library to
  build its affinity maps. Now on `Dispatchers.IO`, with a `realPage1Landed` flag (and
  `@Synchronized` on both rank-and-publish paths) so the seed can't land after page 1 and replace
  live content with last session's cards.
- `maybeRunDiscovery`'s seed read + `shouldRunDiscovery()`, and the hidden-filter collector, both
  moved off main. `rows` went `Eagerly` → `Lazily` + `flowOn(Dispatchers.Default)`.

Follow-up (same day, second commit): moving the hidden-filter collector off main made BACKLOG's
*"`_ranked` read-modify-written from two threads without `update {}`"* item sharper, not softer —
the first pass gave the monitor to `rankAndReplace`/`rankAndAppendBatch` but not to the collector,
which is worse than giving it to neither. The collector's body is now `applyHiddenFilter`, also
`@Synchronized`, so all three writers of `_ranked` share one monitor; `_ranked`'s doc no longer
claims `StateFlow` makes the get-then-set pair safe.

### E2. The grouped layout paged itself in a loop on cold start `[x]` code-complete
`HomeRowsList`'s `nearEnd` has no "something off-screen" guard (deliberately — see its comment), so
a fresh grouped feed of 2–3 rows fits on screen, `nearEnd` is true from the first frame, and the
effect is keyed on `rows.size` — so every page that landed asked for another. Self-limiting, but
only after several back-to-back `getMoreHomeFeed` round trips, each followed by a full affinity
pass, a regroup and a feed-cache write, all during the seconds the feed is meant to be settling.
Now debounced (250 ms, which the effect's own restart-on-`rows.size` turns into real coalescing) and
capped at `MAX_AUTO_PAGES = 3` pages without a user scroll, reset on the first scroll.

### E3. Thumbnails `[x]` code-complete
- Coil's `OkHttpClient` used the default `Dispatcher`, i.e. **`maxRequestsPerHost = 5`** (verified in
  okhttp 4.12's `Dispatcher.kt`). Every thumbnail is on `i.ytimg.com`, so a screenful arrived in
  strict waves of five. Now 15.
- The image disk cache was **not** eagerly initialised, contrary to `App.onCreate`'s comment:
  `ImageLoader.Builder.diskCache {}` stores a `lazy`, and coil's `DiskLruCache` initialises from
  inside `get`/`edit`, both `@Synchronized` on the whole cache — so the first thumbnail ran
  `readJournal()` + `processJournal()` (stats every entry) holding the one monitor the rest needed.
  Now forced from an IO coroutine by touching `diskCache?.size` (`DiskLruCache.size()` is
  `@Synchronized` and calls `initialize()`).
- `maxSizePercent(0.02)` did not mean "≈ 50 MB typical" as its comment claimed. Coil computes it
  from TOTAL filesystem blocks and clamps to `[10 MB, 250 MB]`, so on any modern phone it sat at the
  **250 MB ceiling** (~8k entries → a big journal). Now an absolute 128 MB.
- `hqdefault.jpg` (480×360, 4:3) → `mqdefault.jpg` (320×180, native 16:9) via a new shared
  `thumbnailUrl()`. Both card sizes draw into a 150dp 16:9 box, so hqdefault was oversized AND
  `ContentScale.Crop` discarded ~25 % of the bytes just downloaded. Roughly a third the transfer.
  **This is the one change with a visible trade-off** (mild softening at high density) and the
  easiest to revert — one constant.

### E4. Storage `[x]` code-complete
- `encodeDefaults = true` → `false`. The models are mostly optional fields, so a typical entry was
  largely default values spelled out in full.
- **Watch history moved out of SharedPreferences into `filesDir/history.json`**, written atomically
  (temp + rename) with **coalesced writes** (`HISTORY_WRITE_INTERVAL_MS = 30 s`, immediate for
  record/remove/clear, flushed from `MainActivity.onStop`). SharedPreferences rewrites its entire
  file on every `apply()`, and `updateProgress` ticks every ~5 s for a whole video — so a progress
  tick re-encoded the full history list and dragged subs, playlists, hidden lists and the feed cache
  to disk with it, XML-escaped (`"` → `&quot;`) at close to double the JSON's size.
  Existing installs migrate on first read; the legacy `"history"` prefs key is only dropped once the
  file write reports success, so an interrupted migration just repeats harmlessly.
- The two hidden lists were the only uncapped collections here. Now `MAX_HIDDEN_CHANNELS = 1000` /
  `MAX_HIDDEN_VIDEOS = 2000`, trimming oldest.
- `HomeRanker`'s affinity passes are memoised per instance, keyed on the identity of
  `history.value`/`playlists.value` (immutable snapshots replaced wholesale on mutation, so
  reference equality is sound) with a 60 s TTL to bound `recencyWeight`'s session-half-life drift.
- `VideoRow`/`VideoCard` no longer collect `library.playlists` per card — every visible card held a
  collector on the full playlist contents to render a dialog that was closed. Now collected inside
  the `if (showDialog)` branch.

### E6. Home-feed latency `[x]` (2026-10-04, **corrected and resolved 2026-10-14 — the original entry was wrong; the real cause was the debug build**)

**What this entry originally claimed, and why it was false.** It said OkHttp's default
`maxRequestsPerHost = 5` was serialising `createSession`'s ~22-request opening warm into about five
waves, and that raising it to 16 on the native `InnertubeClient`'s `OkHttpClient` (0d52585) fixed
"the home feed's single biggest latency cost". It didn't, because the limit never applied.
OkHttp enforces `maxRequests`/`maxRequestsPerHost` only in `Dispatcher.promoteAndExecute()`, which
iterates `readyAsyncCalls` — calls made with `enqueue()`. A synchronous `execute()` goes through
`Dispatcher.executed()`, which appends to `runningSyncCalls` with no limit check (both read
directly in okhttp-4.12.0's `Dispatcher.kt`). `InnertubeClient` only ever calls `execute()`. So the
warm was always fully concurrent, 0d52585 changed nothing at runtime, and the dispatcher setting has
been removed rather than left as config that looks load-bearing.

The tell that should have been checked first: E3's identical-looking thumbnail fix IS real, because
coil's `HttpUriFetcher` uses `Call.await()`, which `enqueue()`s. Same symptom, same knob, opposite
outcome — decided entirely by the call style. **Before tuning a dispatcher limit, check whether the
client's calls are `execute()` or `enqueue()`.**

**A second wrong diagnosis followed from the first** (2026-10-14, "the app is suddenly slow — home
feed, channels page and subscriptions"). Reasoning from 0d52585 having raised the limit, it
concluded that two fan-outs — `getChannelVideosBatch` and `discoverSimilarChannels` — had been
relying on the 5-per-host default as an accidental brake, and that raising it had unleashed them.
There was never a brake, so there was nothing to unleash, and 0d52585 cannot be the cause of the
slowdown.

**What survives, because it was true independently.** Both fan-outs genuinely were unbounded, from
the day they were written: `getChannelVideosBatch` launches one browse + parse of a large
channel-tab document per subscription (`SubscriptionsViewModel` passes all of them), and
discovery launches 18 nested requests + parses right after page 1 paints. Both now hold an explicit
`Semaphore` (`SUBS_FANOUT_PARALLELISM` = 8, `DISCOVER_PARALLELISM` = 2), taken around fetch+parse
and — in discovery's nested case — never around a whole `async` body, which would deadlock. These
are new limits, not restored ones, and the subscriptions one is a trade (that tab loads in waves on
a long sub list) rather than a pure win. Whether they explain the reported slowdown is **unproven**.

Also ruled out on 2026-10-14, by checking rather than reasoning: the bundle has not changed since
24ec0be (an earlier claim that a rebuild had regenerated it was misreading git's LF→CRLF warning);
and the native client's `WEB_CLIENT_VERSION_RE` still matches the bundle and extracts
`2.20260623.01.00`, so the February `DEFAULT_CLIENT_VERSION` fallback is not in use. (A line-based
`grep` reported no match; the bundle is pretty-printed with `NAME` and `VERSION` on separate lines,
and only a whole-file regex with the same `\s*` semantics as the Kotlin one gives the real answer.)

**How it was actually closed (2026-10-14, physical Pixel, GrapheneOS, over USB).** The working
hypothesis at this point was a silent native→JS fallback. A cold-start capture on the debug build
ruled it out, and then ruled out everything else that wasn't CPU:

- **No fallbacks, no HTTP failures.** Zero `falling back to JS`, zero `failed: HTTP`, no
  `js-browse` initialisation; native client on `2.20260623.01.00`.
- **Not YouTube.** The same anonymous `/youtubei/v1/search` from a PC on the same home network:
  0.8–1.1 s each, HTTP 200, 450–840 KB per response.
- **Not the phone's network.** Wi-Fi 7, RSSI -53 dBm, ~2 Gbps link; 24 ms ping to youtube.com,
  0% loss.
- **Not the ranker or grouper.** `ranked 32 … in 67 ms`, `grouped … in 27 ms`.
- **Not request chaining.** `HomeFeedEngine.fetchLeaf` makes exactly one request per leaf.
- **Not the parser's algorithm.** `InnertubeParser.collect` is a single depth-limited walk that does
  not re-enter matched renderers; `scanForFirst` is capped at 400 nodes.

Yet `session warm: 33912 ms` — slower than issuing the ~22 requests one at a time. Per-thread CPU
from `/proc/<pid>/task/*/stat` (via `run-as`, which only works on a debuggable build) put
**116.7 CPU-seconds on the `DefaultDispatcher` workers** where Innertube JSON is parsed, 29.4 s on
the main thread, 26.6 s on `js-playback`, and ~180 s more on worker threads that had already exited.
`dumpsys package dexopt` then showed why: the app was **`[status=verify]`** — never compiled —
while store apps on the same phone were `[status=speed]`. A forced
`cmd package compile -m speed -f` changed nothing, and artd's own log showed why that is policy,
not circumstance: dex2oat was invoked with `--compiler-filter=verify --compilation-reason=cmdline`,
and ArtService reported `actualCompilerFilter=verify, status=PERFORMED`. **ArtService rewrites the
compiler filter for a debuggable package before dex2oat runs.** (Not because the app was running —
asked and checked: a running process doesn't change which filter is chosen, and a blocked compile
would not report `PERFORMED`.) So every debug build runs this app's own code — kotlinx.serialization's
JSON parser over ~13 MB of responses, Compose — interpreted.

The fix was a `profile` build type in `app/build.gradle.kts` (`initWith(release)`,
`isDebuggable = false`, debug-signed so it installs over a debug build without losing data). It
installed as `[status=speed] [reason=install-dm]`. Same cold-start test, same phone, same session:

| | debug build | `profile` build |
|---|---|---|
| cached feed seed shown | 747 ms | 198 ms |
| `session warm` | 33,912 ms | **1,830 ms** |
| page 1 shown | 34,368 ms | **2,028 ms** |
| `ranked` (page 1) | 67 ms | 2 ms |
| image disk cache opened | 44,208 ms | 44 ms |
| PO token ready | 70,868 ms | 7,299 ms |
| `Davey!` / `Skipped N frames` | 6 / 10 (worst 5,887 ms) | **none** |

One variable only, despite the profile build also carrying 6211ca3: its subscription limit (8) is
above this device's 7 subscriptions, and discovery did not run in either capture.

Two consequences beyond the fix. First, the 5.9 s main-thread freeze that coincided with the BotGuard
WebView's bring-up (and the 2026-07 `Skipped 417 frames` / `Davey! duration=7110ms` observation in
A3) does not reproduce at all on the profile build — so it is NOT evidence that the WebView needs
deferring, and was nearly all debug-build cost. Second, **every performance number in this document
recorded from a debug build is suspect**, the A3 on-device rounds included. Measure on `profile`.

Still unexplained: why it felt *suddenly* slower. No new code reached the phone between 2026-10-05
and the capture. Recorded rather than guessed at.

Measurement lines added alongside the original (still valid): `session warm: N ms`,
`batch N: M videos in N ms (T turns)`, `ranked N (replace|append) in N ms`,
`grouped N ranked into M rows in N ms` — `adb logcat -s HomeFeedEngine HomeFeed`.

### E5. Still open
- [x] **Build it.** Done on the user's machine (the authoring sessions couldn't — Gradle can't fork
      its worker JVM there); confirmed running on a real phone by 2026-10-04.
- [~] Re-measure with `adb logcat -s Startup` on a real device, cold start, both layouts. Done for
      the grouped (NETFLIX) layout on 2026-10-14 — see E6's table. Classic layout not measured.
- [ ] No baseline profile exists anywhere in the repo, and `isMinifyEnabled = false` even for
      `release`. This item used to say "if startup is being judged from a debug build, some of what's
      left is JIT" — E6 measured it, and it was not "some": the debug build was ~18× slower on the
      cold-start warm, because ART won't compile a debuggable app at all. The `profile` build type
      fixes the measurement problem. A `baselineprofile` module is still the next lever for a
      FIRST launch after install (the profile build was compiled `speed` at install here, but that
      is not guaranteed on every device/install path), and R8 is a separate, riskier project.
- [ ] `HomeViewModel`'s own `prefs` (the layout-mode file) is still read in the constructor. It's a
      ~100-byte XML so it was left alone, but it IS a blocking first read on the main thread.
- [ ] `_ranked` is still uncapped — see BACKLOG's *"`_ranked` is unbounded, and every append
      regroups the whole list on the main thread"*. E1 fixed the regroup half (`flowOn(Default)`)
      and E2 removed the fastest way to grow the list without user input, but nothing trims it.
- [ ] `js-runtime/package-lock.json` is gitignored while `app/build.gradle.kts` treats it as a
      build input — see BACKLOG's "Build reproducibility" section. Not a perf item, but it's the
      reason a fresh clone's JS bundle isn't reproducible, which would undermine any future
      before/after measurement taken on a different checkout.

---

## Side fixes found while validating

* 2026-08-02 — **ROOT CAUSE of the leaked SABR sessions: released pumps RESURRECT themselves.**
  The `session=<hash>` field made it unambiguous — a log while watching one video showed two
  *other* sessions still pumping (`playerTimeMs=62500` and `playerTimeMs=288867`, i.e. from
  PREVIOUS videos), each POSTing to GVS every ~2s and getting prot=3 forever.
  Mechanism: `SabrStreamPump.stop()` only set `stopped = true`, but `ensureStarted()` — called on
  EVERY read — cleared `stopped` and spawned a fresh thread ("revive the pump if it idled out").
  `holder.release()` used `stop()`, so any straggling read from the departing player's data source
  resurrected the session permanently. Those straggling reads are guaranteed: ExoPlayer's release
  is asynchronous and was observed timing out outright (see the entry above).
  Fix: a terminal `released` flag. `ensureStarted()` refuses to revive a released pump,
  `SabrStreamPump.release()` is the permanent shutdown, and `SabrSessionHolder.release()` calls it
  instead of `stop()`. The revivable `stop()` stays for the genuine idle-timeout case.
  Second leak path closed alongside it: `PlayerViewModel.publishSabr()` is now the only writer of
  `_sabr`, releasing whatever holder it displaces (read at swap time, not from a snapshot captured
  before a coroutine launched). Two concurrent builders — stall-watchdog retry + adaptive downgrade
  — could each capture the same `previous`, both release it, and orphan the loser's holder.
  WHY THIS MAY ALSO EXPLAIN THE prot=3 INTERMITTENCY: several concurrent SABR sessions per client,
  each re-POSTing every 2s and re-minting tokens, is precisely the shape abuse detection reacts to.
  The first video after a cold start (one session) tended to work; later videos — with zombies from
  earlier ones still hammering — got refused. Worth re-testing the previously-failing videos now.

* 2026-08-02 — **Quality-switch stall: a release timeout misread as a playback error.** Observed as
  "switching quality, then seeking back to the resume point, takes a while". `ExoPlayer.release()`
  must run on the main thread and BLOCKS until the playback thread winds down; on the emulator
  (software 1080p decode) that exceeded media3's release timeout, throwing `ExoTimeoutException`
  which arrived at our listener as `ERROR_CODE_TIMEOUT` — from the player being DISPOSED. The
  handler treated it as a failure of the INCOMING player and fired a full recovery: another
  getSabrInfo, another session, another player build at the same resume position (visible as two
  `Building player … resume=121997ms` lines plus the `MediaCodec … Released state` /
  `BufferQueue has been abandoned` / `Handler on a dead thread` cascade). Fixed with a `releasing`
  flag set before `release()`; errors arriving from a dying player are logged and ignored. If it
  still shows up, the follow-up is raising the release timeout via `ExoPlayer.Builder`.
* 2026-08-02 — **PO-token rejection is INTERMITTENT, not systematic.** A later run on a different
  video played straight through (two adaptive downgrades, no `SABR no-media round` lines at all,
  no forced re-mints) with the SAME videoId/content binding that had been refused earlier. So the
  minting pipeline is not categorically broken — which fits `/player` accepting the token, and
  rules out a plain "wrong binding/identity" bug as the sole cause. The identity probe also came
  back `page=(none)`: the WebView page exposes no `ytcfg` VISITOR_DATA to compare against, so that
  hypothesis can't be confirmed from the page side either. Working theory now: the refusal is
  conditional (per video / per session / on how much has been pulled during the grace burst —
  the failing run had `cacheKB=37766`, i.e. ~37 MB, against the documented ~24 MB/60 s grace).
  Worth testing next: whether capping quality (less data through the grace window) correlates with
  surviving past 60 s, and whether specific videos reproduce it deterministically.

* 2026-08-02 — **Follow-up to the mint storm: GVS is genuinely rejecting the token.** With the
  throttle in place the storm is gone (logs now show a clean 8s cadence: one `pot APPLIED`, then
  `pot re-mint throttled` until the window reopens), which exposed the real problem underneath —
  the streaming server rejects our videoId-bound WEB pot roughly 14s into playback, the buffer
  drains over the next ~45s, and playback dies at the ~1 minute mark. Two changes:
  (a) `applyPoTokenForProtectedResponse` now uses a `ReentrantLock.tryLock(2s)` instead of
  `synchronized` — entering a monitor isn't interruptible, so a pump blocked while the other minted
  couldn't be torn down (visible as `SABR pump: I/O errors persisted … giving up: interrupted`);
  (b) `PlayerViewModel.retryAfterPlaybackError` stops rebuilding SABR on its LAST attempt and falls
  back to the legacy direct-URL/DASH path, which needs no PO token at all. Previously each retry
  cost another /player round trip plus another WebView attestation and landed in the same place
  25s later, ending in "Couldn't load"; now the video keeps playing.
  BINDING HYPOTHESIS — **REFUTED by the probe (2026-08-02).** The alternation ran cleanly and both
  bindings were rejected inside one session: seeded `visitorData/session` (116 B) → died; re-mint
  `videoId/content` (95 B) → rejected; `visitorData/session` (115 B) → rejected; `videoId/content`
  (94 B) → rejected. Binding is not the variable. Probe pinned back OFF (`PROBE_ALTERNATE_BINDING`)
  so the videoId/content binding — the original behaviour — is restored and the next test is
  single-variable. Note the /player request accepts the same WebView-minted token happily, which
  also argues the attestation itself is fine.
  **DIAGNOSIS COMPLETE (2026-08-02, second probe): the token IS genuinely refused.** The no-media
  log came back `prot=3 status3=true … playerTimeMs=60720 bufferedEndMs=60720 aheadMs=0
  backoffMs=2000 … error=null`, repeated indefinitely. Reading it:
   * `prot=3` persistently = StreamProtectionStatus PROTECTED — the server is explicitly demanding
     a valid attestation, not pacing us. So it IS the PO token.
   * `aheadMs=0` REFUTES the buffer-ahead-window theory: the client had consumed everything it
     buffered and was asking for more; the server refused. Not a playhead-reporting problem.
   * `60720ms` is the grace burst boundary — the pot was never accepted, it just got the free
     opening ~60s. (Matches the AppSabrPoTokenProvider comment describing a pot that keeps SABR at
     protection status 2/3 and cuts media at ~60s.)
   * `error=null`, `reload=false` — no explicit server error to work from.
  Combined with the binding probe (both bindings refused) and the fact that `/player` ACCEPTS the
  same WebView-minted token, the remaining suspect is IDENTITY, not binding: the attestation runs
  inside the WebView page, which sends its OWN cookies with att/get, while we override the
  visitorData in the request body/header with the QuickJS session's. If the integrity token binds
  to the page identity but the stream is issued to the session identity, GVS refuses the pot
  regardless of what the mint is bound to — exactly the observed behaviour.
  NOW INSTRUMENTED: `sabr_potoken.js` logs `identity: session=… page=… MATCH=<bool>`. If MATCH is
  false, the fix is to unify them — make the JS Innertube session adopt the WebView's visitorData
  (youtubei.js accepts `visitor_data` in `Innertube.create`) so attestation, `/player` and the
  stream all share one identity, instead of injecting ours into the page.
* 2026-08-02 — **Suspected session leak on quality downgrade (new, found in the same log).** After
  two adaptive downgrades (itag 299 1080p60 → 298 720p60 → 135 480p) the log showed THREE
  concurrent no-media rounds with three distinct `cacheKB` values (37766 / 20256 / 5111) on three
  threads — i.e. superseded SABR sessions still pumping. Each keeps POSTing every ~2s forever and
  each can trigger its own PO-token re-mint (a main-thread BotGuard run), so the leak compounds
  every symptom above. `SabrSessionHolder.release()` → `pump.stop()` exists, so the likely cause is
  a race in `PlayerViewModel.rebuildSabr` (`old` is captured before the coroutine launches, so two
  overlapping rebuilds can release the same holder twice and orphan one). The no-media log now
  prints `session=<identityHash>` to confirm before changing the release path.
  NB: 1080p60 (itag 299) software-decoded on the emulator is what triggers the downgrade cascade.
  PREVIOUS LEAD (refuted, kept for the record): `YoutubeSabrSession` logs an always-on `SABR no-media round:` line
  (prot / status3 / potLen / playerTimeMs / bufferedEndMs / aheadMs / backoff / cacheKB). This
  separates three failure modes that all LOOK like "dies at ~1 minute": a genuinely refused token
  (prot=2/3 persistently), an explicit server error (sabrError), or — the strong suspect — prot=1
  with the buffered edge parked ~60s ahead of `playerTimeMs`, i.e. the server's buffer-ahead window
  waiting on client progress. `setPlayHeadMs`'s own doc records that exact ~1-minute death from an
  earlier investigation, and the pump derives playerTime from the SLOWEST track's reader position —
  which 1080p software decode on the emulator (see the next entry) would drag down.
  SUPERSEDED HYPOTHESIS (kept for the record): **the two PO-token bindings look swapped.**
  Reading the code end to end: `AppSabrPoTokenProvider` mints the SABR/GVS token bound to the
  **videoId** (content binding), while `QuickJsRuntime.__nativeMintAttestedPoToken` mints the
  `/player` token bound to **visitorData** (session binding). yt-dlp's PO-token guide documents the
  opposite assignment — GVS/googlevideo tokens are SESSION-bound for logged-out clients, and
  content binding belongs to the `/player` request. The observed failure is the textbook signature
  of a wrong GVS token: `/player` succeeds, playback runs through the opening grace burst, then
  status=3 forever. Re-minting couldn't ever fix it because it re-minted the SAME binding.
  Instrumented rather than assumed: a forced re-mint now ALTERNATES the binding
  (visitorData first, since videoId is what's already failing) and logs which one it used —
  `minted real WEB PO token bound to <binding>`. If media resumes right after a given mint, that
  binding is the answer; hardcode it and delete the probe. Requires `sabr_potoken.js` to accept an
  explicit mint identifier (`window.__SABR_POC_IDENTIFIER`), plumbed through
  `WebPoTokenProvider.generateGvsPoToken(identifier = …)`.
  SECONDARY suspect if the binding probe comes back inconclusive: visitorData drift —
  `getSabrInfo`'s REQUEST carries the QuickJS session's visitorData but the RESPONSE returns a
  different one, so a session-bound token may need to bind to the RESPONSE's identity.
  PERF NOTE spotted while reading: every GVS mint re-runs the FULL attestation (att/get →
  interpreter → snapshot → GenerateIT → mint, ~1s on the main thread), unlike `generatePoToken`
  which reuses a cached `integrityToken`. The integrity token is valid ~12h and the minter callback
  is reusable, so the GVS path should cache them the same way and re-mint per identifier only.
* 2026-08-02 — **Unrelated, same log: 1080p software decode starves audio.** The session picked
  `videoItag=137` (1920x1080 AVC) and the emulator decodes it in software
  (`c2.goldfish.h264.decoder`), which produced `AudioTrack … disabled due to previous underrun` and
  matches "loses audio a few seconds in". Not a protocol problem. Options: cap auto-quality to 720p
  when only a software AVC decoder is available (query `MediaCodecList` for a hardware decoder at
  the target size), or lower `DEFAULT_AUTO_HEIGHT` on low-core devices. Not implemented — it's a
  policy choice, and on real hardware 1080p is usually fine.

* 2026-08-02 — **PO-token mint storm (playback, pre-existing — not an Option A/D regression).**
  Symptom: a video stalls, the 25s watchdog retries, eventually "Couldn't load — please go back and
  retry". Logcat showed ~20 successful mints in 90s, alternating between two thread ids with
  `pot APPLIED force=true newLen=94 oldLen=95` / `newLen=95 oldLen=94`.
  Cause: the audio and video pumps are separate threads sharing one `YoutubeSabrSession`. Both get
  status=3, both force-mint, and since every BotGuard attestation returns a DIFFERENT token they
  never converge — each thread sees a token that isn't the one it was rejected with and mints
  again. `MAX_PO_TOKEN_REFRESHES` didn't bound it because the budget resets on ANY media, so one
  track kept refilling the other's. Each mint runs BotGuard in the WebView, which necessarily
  executes on the MAIN thread (~0.5–1s), so the storm starved the app and caused the very stall the
  watchdog then retried.
  Fix (`YoutubeSabrSession`): serialise forced re-mints on a lock; a thread that finds the token
  already replaced while it waited ADOPTS that token instead of minting its own; a session-wide
  8s floor between mints (a token minted seconds ago and already rejected won't work if re-minted);
  and the budget reset now ignores media that arrives inside a mint's throttle window.

## History

* 2026-08-02 (A5 build fix) — first dev build failed with 32 errors, all one root cause: missing
  imports in `YoutubeBridgeImpl` (`SearchParams`, `DiscoveredChannel`, `SearchVideo`, and the
  coroutine trio — D2 had removed `coroutineScope`/`launch` when simplifying `initialize()`, then
  A5 reintroduced concurrency without re-adding them; everything else was inference cascade).
  Why the sandbox missed it: the stub harness type-checks `innertube/*` but NOT the bridge (it
  needs Context/OkHttp/QuickJS stubs), so only a syntax check ran there — and syntax is valid with
  missing imports. Mitigation: an import/resolvability heuristic now runs over the bridge before
  handing over a build (flags Capitalized symbols that are neither imported, declared in-file, nor
  same-package, ignoring qualified refs). It's a heuristic, not a compiler — it over-reports on
  very large files with embedded triple-quoted strings — but it catches exactly this failure mode.
* 2026-08-02 (A5) — native channels + playlists: `InnertubeParser.channelInfo`/`collectPlaylists`,
  `SearchParams.CHANNEL_PLAYLISTS_TAB`, `InnertubeClient.playlist`, and bridge cutovers for all six
  channel/playlist/discovery endpoints (each with a JS fallback). With this, EVERY browse endpoint
  has a native path — A6 (deleting the JS browse code + retiring the browse QuickJS context) is
  unblocked once these have been validated on-device.
* 2026-08-02 (A4) — native related videos + comments: `youtube/innertube/CommentsParser.kt`,
  `InnertubeParser.parseRelated`, bridge cutover for `getRelated`/`getMoreRelated`/`getComments`/
  `getMoreComments`/`getCommentReplies` (all with JS fallback). Player screen now opens without
  touching QuickJS for anything except the playback calls themselves.
* 2026-07-31 (A3) — native home feed: `youtube/innertube/HomeFeedEngine.kt` + `SearchParams.kt`,
  parser additions (`subObject`, `channelTitle`), `getHomeFeed`/`getMoreHomeFeed` cutover in
  `YoutubeBridgeImpl`. First dev build failed on one line (`pickWeighted` returns the VALUE, not
  the pair — a stray `.first`); the sandbox had only tested the picker standalone because the
  engine couldn't compile without coroutines/OkHttp/Android on the classpath. Closed that gap:
  compiling the real engine source against ~40 lines of stubbed `kotlinx.coroutines`/`android.util`
  /json APIs type-checks it end to end, and is the verification step to repeat for A4/A5 before
  handing a build over. Also fixed during A2 rollout: clientVersion now derived from the bundle
  (see A2 notes) — the same "never pin what YouTube versions" rule shaped `SearchParams`.
* 2026-07-30 (A2) — `search`/`getMoreSearch` cutover in `YoutubeBridgeImpl` (NativeSearchSession:
  token + seen-id dedupe, JS fallback on page 1 only). With this, a typical search session no
  longer touches QuickJS at all. Dev-machine build: `assembleDebug` + `testDebugUnitTest` green
  (all InnertubeParserTest fixtures pass under gradle; zero compiler warnings in the new files) —
  remaining validation is behavioral, on-device.
* 2026-07-30 (A1) — native Innertube foundation: `youtube/innertube/InnertubeSession.kt`,
  `InnertubeClient.kt`, `InnertubeParser.kt`, parser unit tests (`app/src/test/...`), junit dep in
  `app/build.gradle.kts`, and the first cutover (`getSearchSuggestions` native-with-JS-fallback in
  `YoutubeBridgeImpl`). Parser logic functionally verified in-sandbox (39 assertions, real
  kotlinc); full type-check + `gradlew testDebugUnitTest` still to run on the dev machine.
* 2026-07-30 (later) — verification pass, to the extent the sandbox allows (Google's Maven/SDK
  hosts are network-blocked there, so no dependency-resolved Gradle build): all 52 Kotlin files
  run through the REAL kotlinc 2.2.10 frontend with zero syntax-class errors (remaining
  diagnostics are all missing-classpath cascades, categories audited); all 57 Java files parse
  clean (tree-sitter); `RelativeTime` compiled standalone + 13 runtime cases (EN + NL) pass on
  the JVM; the rebuilt JS bundle evaluates and exposes all 25 `__yt` methods under Node with
  stubbed native bindings. A full `gradlew assembleDebug` + on-device smoke test still needs to
  happen on the dev machine.
* 2026-07-30 — plan created; D1 + D2 + D3 implemented (not yet device-tested), D4 parked.
  Files: `youtube/runtime/QuickJsRuntime.kt` (context name param), `youtube/bridge/YoutubeBridgeImpl.kt`
  (dual contexts + routing), `App.kt` + `ui/home/HomeViewModel.kt` (ordering workarounds removed;
  feed cache seed + stale-while-revalidate loadFeed), `core/local/LibraryStore.kt` (feed cache),
  `ui/components/SearchSuggestionsList.kt` (comment). Next session: baseline measurements, then A1.
