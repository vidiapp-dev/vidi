# Fixes + analysis — 2026-07-30

Four code fixes landed (SponsorBlock skip loop, background autoplay, home-feed ordering ×2), plus
the analysis you asked for on the home feed round-robin and on performance / replacing QuickJS.

---

## 1. SponsorBlock skip loop — root cause found and fixed

### Root cause

`SabrChunkSource.getAdjustedSeekPositionUs` resolved **every** seek to the **start of the SABR
segment containing the target**:

```java
// old
final int seq = state.getSegmentNumberAtOrAfterTimeMs(format, positionUs / 1000);
return state.getSegmentStartMs(format, seq) * 1000; // ← snaps BACKWARDS, up to a whole segment
```

`getSegmentNumberAtOrAfterTimeMs` returns the segment whose `endMs >= t` — i.e. the segment
*containing* `t` — so the resolved position was up to a full segment (~5 s) **before** the
requested target. A SponsorBlock skip to `segmentRun.end` whose target fell mid-chunk therefore
landed back **inside** the sponsor segment; the 600 ms poll re-detected it, re-issued the skip, and
the seek snapped back again — the infinite loop you saw. All the machinery that was added on top
(escalating retry margins, the 8-attempt circuit breaker) was compensating for this one line, and
failed whenever two escalated targets resolved into the same chunk (identical landing position,
no forward progress, no `PlaybackException` for the error path to catch).

### Fix

* `SabrChunkSource.getAdjustedSeekPositionUs` now resolves seeks the way media3's own DASH chunk
  source does: it offers the surrounding two segment starts as sync candidates and calls
  `SeekParameters.resolveSeekPositionUs`. Under the default (`EXACT`) parameters that returns the
  requested position itself; the chunk framework reloads the containing segment and
  decode-discards up to the exact target. **Seeks now land where they aim** — this also makes
  ordinary user scrubbing frame-accurate instead of snapping back up to ~5 s.
* The retry-escalation + circuit breaker in `PlayerScreen` stay as a pure backstop (a cold session
  whose segment index is still an `averageDurationMs` estimate can in principle still land short),
  but the attempt budget dropped 8 → 4 since undershoot is now the exception.
* Speed: the sponsor poll got a **fast wake** — when the next segment's start is nearer than the
  600 ms cadence, the merged poll wakes exactly at the boundary, so a skip fires within ~50 ms of
  entering a segment instead of up to 600 ms in.

Files: `sabr/SabrChunkSource.java`, `ui/player/PlayerScreen.kt`.

---

## 2. Background player dying on autoplay advance — two causes fixed

### Cause A (the one you observed): STATE_ENDED demotes the foreground service

The advance worked by letting the player hit `STATE_ENDED`, then re-preparing it
(`playDirectly`). media3's `MediaSessionService` demotes its foreground service the moment the
player stops "playing" — ENDED counts — and on Android 12+ a **backgrounded app cannot re-promote
a demoted foreground service** (`ForegroundServiceStartNotAllowedException`, swallowed inside
media3). Result after the first background advance: media notification gone (no lockscreen
controls), process no longer foreground → cached → frozen by the OS shortly after — exactly your
"audio stops after roughly 30 seconds, maybe quicker".

**Fix — never reach ENDED between queue items.** The prefetched next video is now **appended to
the live ExoPlayer as a second playlist item** (`PlaybackController.appendNext`, called as soon as
the near-end prefetch finishes building the session). ExoPlayer transitions into it natively
(READY → READY, `onMediaItemTransition`), the foreground service never has a gap to be demoted in,
lockscreen controls persist, and the transition is gapless as a bonus (the next session pre-buffers
during the last seconds of the current one). The transition listener then catches the ViewModel up
(`onAutoAdvancedIntoQueued`) exactly like the old hand-off did, and the lockscreen "Next" button
jumps into the appended item when it matches (`advanceIntoAppended`). `playDirectly` remains the
fallback for advances that were never prefetched. "Sleep at end of video" un-queues the appended
item (and gates further queueing) since it depends on ENDED actually firing.

### Cause B (latent): `playDirectly` was called from the wrong thread in error recovery

`fetchSabr(resumeAtMs=…)` (locked-screen playback-error recovery) called
`PlaybackController.playDirectly` from its **`Dispatchers.IO`** coroutine. media3 rejects player
access off the main thread with an `IllegalStateException` — which fetchSabr's own `catch` block
swallowed as "SABR setup failed, will fall back to legacy". So every locked-screen error recovery
silently degraded or died. `playDirectly` now hops to `Dispatchers.Main.immediate` internally
(inline/synchronous when already on main, preserving the re-prepare-inside-the-ENDED-callback
timing).

Additionally, the no-prefetch advance path (`advanceTo` → `loadInternal`) now passes
`handOffToLivePlayer = true`, so even when the near-end prefetch didn't finish in time, the
eventually-built session is handed straight to the live player instead of waiting for a Compose
recomposition that never happens while the screen is off.

Files: `playback/PlaybackController.kt`, `ui/player/PlayerViewModel.kt`, `ui/player/PlayerScreen.kt`.

### Worth verifying on-device

* Playlist advance with screen locked: lockscreen controls must survive the transition and audio
  must keep going past the first advance (and the second, third, …).
* Foreground advance: should now be gapless when prefetch won the race.
* Sleep timer "end of video" still stops at the end.

---

## 3. Home feed ordering — what was wrong, what changed, what else you could do

### 3a. New subscription uploads not at the top

Diagnosis: a subscribed-channel card scored `SUBSCRIBED_BONUS = 1` (+ channel affinity if you also
watch it). A card from any channel you've watched a few times recently scores
`AFFINITY_WEIGHT (2) × affinity-sum`, easily 2–6+. So "more of what you already watched" routinely
outranked a *brand-new upload from a channel you explicitly subscribed to*. Upload recency was not
a ranking signal at all.

Changed (`HomeRanker.score`): a subscribed-channel card whose `published` text parses to ≤ 48 h old
gets `FRESH_SUB_BONUS = 8`, tapering linearly with age inside the window (newest sort first).
That decisively clears typical affinity sums, so fresh subscription uploads land at/near the top of
each ranked batch; the fully-watched filter already guarantees they're unwatched. Also fixed
`RelativeTime` to parse Dutch relative strings ("3 uur geleden") — the app configures Innertube with
the device locale, so on your device the parser returned null for *every* card, which would have
silently disabled the boost (and already degrades the subscriptions-tab ordering today).

Further options if you want it stronger/cleaner:
* A deterministic pinning pass in `rank()` (partition fresh-sub cards to the front, capped at ~3
  per batch) instead of a score bonus — guarantees "at the top" regardless of affinity outliers.
* A dedicated "New from your subscriptions" shelf above the feed, fed straight from the channels
  bucket sorted by `RelativeTime` — sidesteps ranking entirely.
* Nudging `HOME_BUCKET_WEIGHTS.channels` up (6 → 8) so more subscription uploads are in the
  candidate pool in the first place.

### 3b. One category dominating every third/fourth card

Diagnosis: the top-level round-robin does alternate buckets, but when the **topics** bucket won a
turn, `pullHomeSource`'s `'group'` case handed the *entire turn cap* (`PER_TURN_PULL_CAP = 4`) to
**one** weighted-randomly picked topic leaf. So each topics turn injected 4 consecutive same-
category cards into a 12-card batch; `diversify` (window 2) can only space them out to roughly
every third slot — which is precisely "each third or fourth card is from one category, four times,
before another category appears".

Changed (`js-runtime/src/index.js`, bundle rebuilt into `app/src/main/assets/youtube-runtime.js`):
the `'group'` case now re-picks a leaf **per video** instead of per turn. A topics turn now returns
a 4-category mix (weights still bias which topics get picked, just at video granularity). Zero
extra network — every leaf buffers its full raw page in `pending`, so the per-video pulls are
buffer reads.

Further options:
* Raise `DIVERSITY_WINDOW` 2 → 3 in `HomeRanker` if same-topic runs still read as clumpy.
* Sample-without-replacement inside one group turn (force 4 *distinct* leaves when ≥4 are live) if
  the weighted random still doubles up too often for your taste.

---

## 4. Performance vs NewPipe/PipePipe, and replacing QuickJS

### Why NewPipe feels instant and this app doesn't

NewPipe's extractor is **native JVM code end-to-end**: OkHttp → Jackson parse → model objects, no
JS anywhere except a ~few-KB deciphering snippet. This app routes every operation through:

1. **QuickJS, a bytecode interpreter** (no JIT). youtubei.js is a 1.5 MB bundle that walks huge
   Innertube JSON trees building parser class instances — the kind of allocation-heavy code where
   an interpreter is 10–50× slower than V8, on one core.
2. **One thread + one global lock.** `QuickJsRuntime.callAsync` wraps every bridge call in a single
   `Mutex`, and the engine itself is single-threaded. Opening a video issues `getSabrInfo`,
   `getVideoInfo`, `getChannelInfo`, `getRelated`, `getComments` — strictly head-of-line, so the
   playback-critical call competes with feed/comment chatter (you already work around this by
   ordering `fetchSabr` first; the queue is the disease, that's a symptom).
3. **A stringly bridge.** Every request/response is JSON-stringified across the boundary, binary
   bodies additionally base64'd (+33 % size, decode on the JS thread), then the JS result is
   re-serialized to JSON and parsed *again* by kotlinx.serialization. Innertube responses are
   100 KB–1 MB; you pay three parses per call.
4. WebView warm-up (~1.5 s) for the first PO-token mint / nsig eval.

SABR token minting itself is WebView/BotGuard-bound — swapping the JS engine won't speed the mint;
keeping the WebView warm and pre-minting for the predicted next video (which the prefetch path now
effectively does) is the lever there.

### Runtime replacement options, ranked

**Option A — port the hot read paths to Kotlin (the NewPipe move). Biggest win, most work.**
Feed/search/channel/related/comments are plain Innertube POSTs with well-known response shapes;
you already vendor part of `org.schabi.newpipe.extractor` in-tree for SABR. Migrating browse/search
to native Kotlin (OkHttp + kotlinx.serialization, or lifting NewPipeExtractor wholesale) removes
the interpreter, the lock, and the triple parse for everything users perceive as "app speed",
inherits real parallelism from coroutines for free, and shrinks the JS runtime's job to
player-response + nsig/sig — which you already evaluate in the WebView anyway. This is the only
option that reaches actual NewPipe latency, and it can be done incrementally (feed first, then
search, then watch-next).

**Option B — embed Node.js (libnode). Real V8 JIT + real parallelism, moderate work.**
`app/libnode/` suggests you've already started. You get: V8 JIT (the 1.5 MB bundle parses
Innertube trees an order of magnitude faster), `worker_threads` for genuine multi-core JS (e.g. a
dedicated worker for playback-critical calls, another for feed/browse), and native `fetch`/undici —
which deletes the Kotlin `__nativeFetch` JSON/base64 bridge entirely (Node does its own I/O;
binary bodies stay binary). Costs: ~25–35 MB APK per ABI, slower cold start than QuickJS
(mitigable with a snapshot), NDK build maintenance, and you must re-home the WebView-bound pieces
(PO mint stays in Kotlin/WebView; expose it to workers via a small RPC).

**Option C — Javet (V8 isolates on Android). Similar wins to B, different trade-offs.**
One V8 isolate per thread gives JIT + parallelism with a cleaner Java API than raw libnode and no
Node runtime semantics to emulate — but you keep your fetch/storage polyfill bridge (still worth
converting to byte-array bindings instead of base64 strings). Android AARs are ~20 MB+/ABI.
J2V8 is abandoned, GraalJS has no JIT on ART, Zipline is QuickJS underneath — none of those help.

**Option D — cheap, immediate: multiple QuickJS contexts + kill the global lock. Days, not weeks.**
QuickJS is single-threaded *per context*, but nothing stops two contexts on two threads. Split by
workload: context 1 = playback (`getSabrInfo`, `getLegacyPlayback`, decipher), context 2 =
browse/feed/search/comments. Session state (visitorData/poToken) already persists through
`SessionStore`, so both contexts converge on the same identity; memory cost is a few MB each.
This removes the head-of-line blocking (video-open no longer waits behind a feed page mid-parse)
without changing engines — but each call is still interpreter-slow, so feed loads won't reach
NewPipe territory. Pairs well with two more cheap wins: cache page 1 of the home feed on disk and
render it instantly on launch while refreshing in the background (NewPipe's perceived speed is
partly exactly this), and return youtubei.js `parse: false` raw JSON for endpoints where Kotlin
only needs a handful of fields.

**Recommendation:** D now (it's low-risk and makes the app feel materially better this week),
then A incrementally for the browse paths — it makes B/C mostly unnecessary. Choose B over C only
if you want to keep youtubei.js long-term as the single source of YouTube-schema truth; the
libnode scaffolding you already have makes it the natural pick of the two.

---

## Build notes

* The JS bundle was rebuilt (`app/src/main/assets/youtube-runtime.js`) from the edited
  `js-runtime/src/index.js` with the same esbuild options/version as `npm run build`.
* Kotlin/Java changes were desk-checked but **not compiled** here (no Android SDK in this
  environment) — please run a normal `gradlew assembleDebug`.

---

## 2026-08-02 — SABR reference diff: what is and isn't divergent

Stopped hypothesising and diffed the whole SABR package against the verbatim PipePipe copy in
`sabr-reference/`. **43 of 48 files are byte-identical**, including every file that touches the
wire format:

| divergent lines | file | verdict |
|---|---|---|
| 533 | `YoutubeSabrProbe.java` | app-specific I/O hook — expected |
| 312 | `YoutubeSabrSession.java` | mostly our logging + pot lock; **one real regression, below** |
| 121 | `UmpReader.java` | to review |
| 71 | `SabrMediaSegmentCollector.java` | to review |
| 59 | `YoutubeSabrStreamState.java` | to review |
| **0** | `YoutubeSabrRequestBuilder.java` | **identical** |
| **0** | `SabrPlaybackCookie`, `SabrProto`, `SabrColdStartPoToken`, `SabrSegmentRequest`, … | identical |

### Answered: does audio/video stream separation break the PO token? **No.**

Ruled out by construction, not by guesswork:

* There is **one** `YoutubeSabrSession` per video, **one** `SabrStreamPump` per holder, and
  `pumpOnce()` is called from that pump thread only. `SabrSessionHolder`'s own contract: *"the audio
  and video `SabrSegmentDataSource`s drive the SAME session (one SABR response carries both
  formats)"*.
* `YoutubeSabrRequestBuilder` is **byte-identical to the reference**, so both tracks go out in one
  request with one `StreamerContext` (pot in field 2, playback cookie in field 3). This is exactly
  the canonical multiplexing.

The two pump threads seen on one session in earlier logs were the **zombie-resurrection bug**
(already fixed via `SabrStreamPump.released`), not per-track pumps.

### Ruled out: the token is not the cold-start placeholder

`SabrColdStartPoToken.generate()` emits `2 + 8 + len(videoId)` = **21 bytes**. The field logs show
`potLen=95` throughout, and `SabrSessionFactory` force-mints a real WebView/BotGuard pot at session
build. So a genuine attested token is in play from the first request — it is being **refused**, not
missing. Corollary worth internalising: the ~60s cliff is the server's **grace burst expiring**, so
"works for a minute, then breaks" and "was never accepted" are the same observation.

### The real regression: no escalation path (fixed)

Upstream drives segments through a blocking fetch loop that, on a protected response with the
PO-token budget spent, **throws `SabrProtocolException`** — killing the session so the layer above
rebuilds from a *fresh* `/player` response (new `ustreamerConfig`, new pot).

Our pump-driven `pumpOnce()` correctly declines to throw on a single `status=3` (one round is normal
pacing) — but it never escalated either. Once `MAX_PO_TOKEN_REFRESHES` was spent it returned an
empty list **forever**, so the pump re-sent the same already-rejected token every ~2s indefinitely.
That is precisely the observed log: `prot=3 … error=null` repeating with no recovery. We turned a
self-healing hard failure into an unrecoverable soft loop.

**Fix:** `MAX_CONSECUTIVE_PROTECTED_ROUNDS = 8` (~16s at the server's 2000ms backoff, enough to
cover the re-mints the throttle allows). Reset only when media actually arrives. On exceed, throw —
pump marks fatal → playback error → view-model re-fetches and builds a brand-new holder.

**Fix (companion):** `PlayerViewModel.rebuildSabr()` seeded the new session with
`old.session.streamState.poToken` to skip re-attestation on quality/audio switches. If that token is
the refused one, the rebuild is wasted. Now guarded by the new `YoutubeSabrSession.isPoTokenRefused`.

### Hardening: pump restart race

`SabrStreamPump.ensureStarted()` restarted whenever `stopped == true` — but `stopped` only means
"asked to stop", not "exited", and the pump spends most of its life inside a blocking HTTP round
trip that ignores `interrupt()`. Two pump threads on one session would corrupt the stream state
(`YoutubeSabrSession` documents it as single-writer) and put two overlapping requests on one
playback cookie. Now `ensureStarted()` refuses to start while the previous thread is alive, and
`loop()`'s `finally` publishes `thread = null` under the same lock as `stopped`. Readers poll
`getCached()` on a timer, so the deferred start self-heals.

Window was small once `released` landed, so this is hardening, **not** the prot=3 cause.

### Still open

The root cause of the refusal itself. Everything downstream of the mint is now proven faithful to
the reference, so the remaining suspects are all in `AppSabrPoTokenProvider` / `WebPoTokenProvider`
— i.e. *how* the token is attested, not how it is sent. Next: confirm from a fresh run whether
`potLen` ever **changes** after a forced re-mint (a `pot NOT applied … (same as current)` line would
mean the WebView is handing back a cached attestation, which would also invalidate the earlier
binding probe that "ruled out" videoId vs visitorData).

---

## 2026-08-02 (later) — ROOT CAUSE FOUND: visitorData was percent-encoded

The escalation added above worked exactly as designed on the next run — pump went fatal after 8
protected rounds, ExoPlayer surfaced `SABR pump fatal for itag=137`, and the view-model rebuilt a
fresh session and resumed at 61509ms instead of hanging forever. But that same log finally exposed
*why* the token was refused.

### The evidence

Every `/player` call sent one visitorData and got a **different** one back:

| call | sent | returned |
|---|---|---|
| getSabrInfo | `visitorData-A…` | `visitorData-B…` |
| getVideoInfo | `visitorData-A…` | `visitorData-C…` |
| rebuild getSabrInfo | `visitorData-A…` | `visitorData-D…` |
| rebuild getVideoInfo | `visitorData-A…` | `visitorData-E…` |

A server that accepts your visitorData echoes it back. A server that *cannot parse* it mints a new
one. Ours was never being accepted — and the reason is visible in the request body:

```
"visitorData":"visitorData-A%3D%3D"
```

`%3D%3D`, not `==`. Corroborated by the WebView logging `visitorLen=32` (the correct raw value is
28 chars) and by `identity: session=… page=(none) MATCH=false` on every single mint.

### The cause

`generate_session_locally: true` makes youtubei.js build the visitorData itself:

```js
function encodeVisitorData(id, timestamp) {
  return encodeURIComponent(u8ToBase64(...).replace(/\+/g, "-").replace(/\//g, "_"));
}
```

The base64url replaces already cover `+` and `/`, so the only thing `encodeURIComponent` can still
touch is the `=` padding. Verified: `visitorData-A==` decodes to a clean 19-byte
protobuf; the `%3D%3D` form decodes to 22 bytes of garbage.

### Why it killed playback specifically

That one string is our client identity in three places at once:

1. `context.client.visitorData` in every Innertube JSON body,
2. the `X-Goog-Visitor-Id` header, and
3. the value handed to the WebView for the GVS/SABR PO-token mint.

For plain browse calls the damage is invisible — YouTube just issues a fresh visitorData and serves
the request. For playback it is fatal: the BotGuard attestation runs under a session identity the
server never accepted, so the minted pot does not match the streaming session that the *same*
`/player` response set up. GVS responds the only way it can — an opening grace burst, then
`StreamProtectionStatus=3` at ~60s. Which is precisely the cliff, and precisely why re-minting
never helped: every re-mint reproduced the same mismatched identity.

This also retroactively explains the "inconclusive" videoId-vs-visitorData binding probe. Both
bindings were rejected because *both* were derived from a broken session identity — the probe was
never testing the variable it thought it was.

### Fix

`normaliseVisitorData(it)` in `js-runtime/src/index.js`, applied to both the anonymous and the
authed session. Decodes once, idempotent, logs the length change. All three consumers read the same
field, so one decode fixes the body, the header and the pot mint together. Bundle rebuilt
(`app/src/main/assets/youtube-runtime.js`).

Native `InnertubeSession` sends no visitorData at all, so search/feed were never affected.

### What to look for on the next run

* `[__yt] visitorData un-percent-encoded (32→28 chars)` at startup.
* `[sabr-pot] context ok visitorLen=28` (was 32).
* The `/player` response echoing back **the same** visitorData we sent.
* No `SABR no-media round … prot=3`.

---

## 2026-08-02 (run 3) — visitorData fix confirmed; remaining failure is decode, not network

`[__yt] visitorData un-percent-encoded (32→28 chars)` fired at startup, `[sabr-pot] context ok
visitorLen=28`, and **there is not a single `prot=3` / `SABR no-media round` line in the whole
run**. The PO-token rejection is gone.

### The new failure is CPU-bound decode

| time | event |
|---|---|
| 13:01:40.6 | SABR session ready, `videoItag=137 (1920x1080)` |
| 13:01:50.6 | `allocate(c2.goldfish.h264.decoder)` |
| 13:01:54→13:02:05 | `AudioTrack … disabled due to previous underrun`, `device stall time corrected`, `Skipped 99 frames` |
| 13:01:54→13:02:04 | heap climbs to 73MB with ~36MB in LOS; `Background concurrent mark compact GC … total 1.033s` |
| 13:02:04.3 | `repeated rebuffering — requesting lower quality` → `1080p -> 720p` |
| 13:02:04.6 | rebuild, `resume=3019ms` |

**~3 seconds of content played in ~23 seconds of wall clock.** `c2.goldfish.h264.decoder` is the
emulator's *software* H.264 decoder. It cannot sustain 1080p, so it falls behind real time, the SABR
cache keeps filling ahead of it, the heap goes to large-object territory, GC pauses reach a full
second, and the player reads the resulting stalls as "rebuffering" and drops quality. Nothing here
is a network or token problem.

### Why 1080p was picked at all — two compounding bugs in `SabrQuality`

1. **AVC hardware decode was never probed.** `deviceMaxVideoHeight()` floors at 1080 unconditionally,
   on the premise (in its own comment) that *"1080p is AVC and light, so it's always safe to offer"*.
   Only VP9 and AV1 were probed. That premise silently assumes hardware AVC.
2. **The software-decoder heuristic missed the emulator anyway.** `hasHardwareDecoder()` classified
   by name prefix (`c2.android.` / `c2.google.` / `omx.google.`). `c2.goldfish.h264.decoder` matches
   none of them, so even had AVC been probed it would have come back "hardware".

**Fix:** `hasHardwareDecoder()` now uses `MediaCodecInfo.isHardwareAccelerated` on API 29+ (the
platform's authoritative answer; the name heuristic stays as the pre-29 fallback, extended with
`c2.goldfish.` and `.sw.`). New `hwAvc` probe, and `deviceMaxVideoHeight()` returns
`SOFTWARE_DECODE_MAX_HEIGHT = 720` when AVC is software-only. No effect on real devices, which
report hardware AVC and keep the 1080–1440 range.

### Why there is a `client=IOS` line (asked)

Intentional, and unrelated to SABR. Two different `/player` calls happen per video:

* `getSabrInfo` → `clientName: "WEB"` — the SABR path. WEB is required because only web-family
  clients get `serverAbrStreamingUrl` + `ustreamerConfig`, and the WEB pot is what GVS accepts.
* `getVideoInfo` → `clientName: "iOS"` — the **legacy direct-URL fallback** path, from
  `CLIENT_PREFERENCE = ['IOS', 'ANDROID', 'MWEB', …]`. Per the comment in `index.js`: mobile clients
  *"serve TRULY direct urls — no nsig transform, no GVS pot"*, whereas web-family URLs need the nsig
  transform that fails in QuickJS. Hence `client=IOS (26 direct urls)` — those URLs are the standby
  used if SABR can't build.

So it is expected. Two things about it are still worth noting:

* It costs a second blocking `/player` round trip (~600ms) on every video load, even when SABR
  succeeds and the direct URLs are never touched. Candidate for lazy/on-demand fetching.
* The iOS request sends `context.client.userAgent` = the **desktop Edge UA**, while `STREAM_UA.IOS`
  is the correct `com.google.ios.youtube/20.11.6 (iPhone10,4; …)`. The playback UA is right, the
  context UA is not. Harmless today (the call returns 200), but it is exactly the kind of client
  inconsistency that attracts attestation trouble — worth aligning.

### Still open

* Heap/GC pressure during playback (~36MB LOS). The pump's byte throttle bounds the cache, but the
  ceiling looks too high for `largeHeap` on a 1080p stream. Measure before changing.
* `identity: … page=(none) MATCH=false` still on every mint — the WebView page exposes no
  visitorData of its own, so that diagnostic never actually compares anything. It was not the cause,
  but the log line is misleading and should either be made meaningful or dropped.

---

## 2026-08-02 (run 4) — prot=3 is back: the visitorData was well-formed but not REAL

**Correction to the run-3 conclusion.** Run 3 was not proof of a fix — that log ended 27s after the
quality switch, before the grace burst could expire. Run 4 runs long enough and prot=3 returns.

### What run 4 shows

| time | event |
|---|---|
| 18:11:52.2 | session 1 ready, `videoItag=137 (1920x1080)`, seeded pot 95B |
| 18:12:17.3 | `repeated rebuffering` → `1080p -> 720p` (software-decode stall again) |
| 18:12:17.7 | `reused existing WEB pot (95 bytes)`, session 2 at 720p |
| 18:12:30.0 | `prot=3 … playerTimeMs=64240 bufferedEndMs=64240 cacheKB=8805` |
| 18:12:51.3 | pump fatal after 8 protected rounds (escalation working) |
| 18:13:30.3 | ExoPlayer surfaces the error, rebuild, `resume=63158ms` |

Session 2 was ~12s old when it hit prot=3, but `playerTimeMs=64240` — it had burst-filled ~64s of
MEDIA. So the pattern is unchanged: **grace covers ~60s of media, then the token is refused.**
The pot has never been accepted; grace has always been what we were watching.

### Root cause: `generate_session_locally: true` invents an identity

Fixing the percent-encoding made the visitorData *well-formed*. It did not make it *real*. From the
bundled youtubei.js:

```js
visitor_data: visitor_data || ProtoUtils_exports.encodeVisitorData(generateRandomString(11), now)
```

With `generate_session_locally: true` and no `visitor_data` supplied, youtubei.js **fabricates** the
identity from a random 11-character string. YouTube has never issued it and has no record of it.
The proof is still in run 4's logs, even with valid base64:

* sent `"visitorData":"visitorData-F=="`
* returned `"visitorData":"visitorData-G…"`

Still a different value back on every single `/player` call. The server can't resolve ours, so it
substitutes its own.

For browse calls that's harmless. For playback it is fatal, and specifically so: the GVS PO token is
attested in the WebView against the fabricated identity, while the stream is issued to whatever
session `/player` actually resolved to. They don't match → grace burst → `StreamProtectionStatus=3`.
Re-minting cannot help, because every mint reproduces the same fabricated identity. That also
explains, finally, why the videoId-vs-visitorData binding probe was "inconclusive": both bindings
derived from an identity the server never recognised.

### Fix

`generate_session_locally: false` on the **anonymous** session, so youtubei.js does
`GET /sw.js_data` and uses a server-issued visitorData. Costs one round trip at cold start — which
is precisely why it had been disabled — and youtubei.js falls back to local generation if that call
fails, so it cannot hard-fail. The authed session keeps `true` deliberately: it is handed the real
visitorData explicitly, and `visitor_data || …` then short-circuits the fabrication, so there is no
second round trip.

`normaliseVisitorData` stays as a safety net (idempotent, no-ops on a clean value).

### The identity diagnostic was never actually testing anything

`sabr_potoken.js` has carried this comment since it was written:

> *if the page identity differs from the session identity, the integrity token may be bound to the
> page while the stream is issued to the session — which would make GVS refuse the pot no matter
> WHAT we bind the mint to*

That is the correct diagnosis. But the check read **only** `ytcfg.get('VISITOR_DATA')`, which is
empty in this WebView, so it printed `page=(none) MATCH=false` on every mint and compared nothing.
I previously recorded page-identity mismatch as "refuted" on the strength of that line — it was
never tested. Now falls back to the same HTML scrape `readVisitorData()` uses, and reports
`pageLen=` and `via=` so the value can be judged (diagnostic only; not used for minting).

### ⚠️ This run did NOT include the software-decode quality cap

Session 1 still picked `videoItag=137 (1920x1080)`. The APK path is byte-identical to run 3's
(`~~MX7eyrYIhzGmq3wHVr7CFA==/com.example.ytclient-SX2_Q74P66foc7_MAtTz2w==`), so the `SabrQuality`
change was not in this build. Two possibilities remain and they need separating:

1. the build simply predates the change, or
2. the change is live but `c2.goldfish.h264.decoder` reports `isHardwareAccelerated == true`
   (plausible — it is a *vendor* codec that forwards to the host, not a `c2.android.*` one).

`deviceMaxVideoHeight()` now logs which branch it took, so the next run answers this directly.

---

## 2026-08-02 (run 5) — `generate_session_locally: false` REVERTED (made it worse)

The change from run 4 was wrong. Playback went from "dies at 60s" to "never starts", so it is
reverted. First, a precision point: **PO-token generation did not fail.** The GVS pots minted fine
throughout (`Minted GVS PO token for ys_BZXi02yQ (len=126)` → 94 bytes). What failed is the stream.

### What actually happened

```
20:47:27.2  Building player                        (no codec adapter is EVER created)
20:47:28.8  prot=-1 status3=false potLen=94 playerTimeMs=0    cacheKB=0     backoffMs=4000
20:47:45.9  prot=2  status3=false potLen=94 playerTimeMs=20000 cacheKB=10322 redirect=true
20:47:51.0  prot=2  … playerTimeMs=25000 cacheKB=14358
20:47:53.6  watchdog: buffering > 25000ms — retrying
```

Media *is* arriving (14MB cached, 25s buffered) and protection is only at 2 (grace), but no
`MediaCodec adapter for track type video` line ever appears — so the video init segment never
reached the extractor and the player never left buffering. Then the same again after the retry.

### Two regressions the change introduced

1. **clientVersion split.** `/sw.js_data` returns its own clientVersion. The logs show the session on
   `2.20260731.00.00` (`[sabr-pot] context ok visitorLen=556 clientVersion=2.20260731.00.00`) while
   the SABR mint still used the bundled `2.20260206.01.00`. The `/player` call and the token meant to
   authorise its stream were made under two different client versions.
2. **The real visitorData is ~556 chars, not ~28.** That long form propagates into the WebView and
   the `X-Goog-Visitor-Id` header — precisely the value `readVisitorData()` already warns about
   ("a 558-char garbage value whose control chars made the X-Goog-Visitor-Id header invalid"). It
   isn't garbage, but nothing downstream was sized or tested for it.

### And it didn't fix the thing it was for

The improved diagnostic finally produced a real reading:

```
identity: session=visitorData-H page=visitorData-I pageLen=558 via=html MATCH=false
```

Still mismatched — because the WebView holds its **own** session, independent of whatever
`/sw.js_data` hands the QuickJS runtime. Fetching a *different* real identity just swaps one
mismatch for another.

**That is the actual lesson, and it reframes the fix**: the identity has to come FROM THE WEBVIEW,
because that is where the BotGuard attestation physically happens. Both previous attempts (fabricate
one; fetch a second real one) were doomed for the same reason. Filed as P1 in `BACKLOG.md`.

The improved diagnostic earned its keep here — it is the first time we have seen both sides of the
comparison, and it is what makes the next step obvious rather than another guess.

### Answered: goldfish lies about hardware acceleration

```
SabrQuality: hardware AVC present — max quality 1344p (hwVp9=true hwAv1=false)
```

So `MediaCodecInfo.isHardwareAccelerated` returns **true** for `c2.goldfish.h264.decoder` — it is a
*vendor* codec, not a `c2.android.*` one — and the run-3 software-decode cap was inert. That settles
the question left open after run 4 (it was option 2, not a stale build). `hasHardwareDecoder()` now
excludes `c2.goldfish.*` explicitly before consulting the platform.

Also note `max quality 1344p`: the emulator's short side is 1344, and `coerceIn(1080, 1440)` passes
it straight through. Harmless (no 1344p format exists, so it just admits 1080p and excludes 1440p)
but the log line is confusing.

### Also seen this run

`native getComments failed, falling back to JS: no comment section token` → JS fallback then failed
too with `Comments page did not have any content`. Both paths returned empty. Needs checking against
a normal build to see whether it is related to the session change or a separate parser break.

---

## 2026-08-02 (run 6) — first clean playback, but read the win carefully

Video played from 21:03:58 to 21:06:48 (user stopped at 2:35). **No `prot=3`, no `SABR no-media
round`, no pump-fatal, no watchdog.** First end-to-end success.

### ⚠️ Do not conclude prot=3 is fixed

The identity is still mismatched — same run, same log:

```
identity: session=visitorData-J page=visitorData-K pageLen=558 via=html MATCH=false
```

Nothing about the token changed; `generate_session_locally` is back to `true`, so the visitorData is
still fabricated. What changed is the **bitrate**. The grace burst is roughly 60s *or ~24MB,
whichever comes first*. Every previous prot=3 was at 1080p, where 24MB arrives in about a minute.
This run ran at 480p for the whole watch — ~155s of 480p is well under 24MB, so **we never left the
grace window**. We didn't beat the protection check; we stayed inside its budget. A longer watch, or
a device that sustains 1080p, will bring it straight back. The P1 backlog item stands.

### The goldfish fix works

`SabrQuality: no hardware AVC decoder — capping quality at 720p` — the `c2.goldfish.*` exclusion
engaged and 1080p was never selected. That is why this run got as far as it did.

### But 720p was still too high, and the ladder had a floor bug

* 720p (itag 298) hit `repeated rebuffering` and auto-dropped to 480p **23 seconds** after the
  player was built. So `SOFTWARE_DECODE_MAX_HEIGHT` was still optimistic → lowered 720 → **480**,
  which is what actually sustains. Saves a wasted session rebuild on every play.
* `repeated rebuffering — requesting lower quality` then fired **three more times at 480p and did
  nothing**, silently. Cause: `onBandwidthLow()` had a hard-coded floor —
  `.firstOrNull { it.height < current && it.height >= 480 } ?: return`. At 480p no rung qualifies and
  the `?: return` says nothing. That floor is right on hardware decode (below 480p the link is the
  problem, not the resolution) and wrong on software decode, where the decoder is the bottleneck and
  360p/240p are legitimate. Now `SabrQuality.minAutoDowngradeHeight()` — 480 with hardware AVC, 144
  without — and the no-rung case logs instead of vanishing.

### Cold start measured: ~40s to first play

`getSabrInfo` isn't called until 21:03:34 — 40s after process start — because everything waits on
`warmup`. Decomposition:

| span | cost | what |
|---|---|---|
| 21:02:57 → 21:03:14.6 | **~17s** | WebView bring-up + `youtube.com loaded (cookies len=1149)` |
| → 21:03:22.6 | ~7s | BotGuard attestation for the **session** pot |
| → 21:03:25.8 | ~2s | `base.js` download (~2MB) |
| → 21:03:34.2 | **~8.4s** | `base.js` evaluation in QuickJS → `warmup: authed session ready` |

Then `Building player` 21:03:35.8 → video codec 21:03:43.7 = another **~8s** (the gap already in the
backlog, re-measured).

Two clear targets, both new backlog entries: playback blocks on the *session* pot, which needs the
WebView loaded — but only the *GVS* pot genuinely needs it, and only at play time. And 8.4s to
evaluate `base.js` in QuickJS is the single largest software cost in the chain.

### Smaller things seen

* `deviceMaxVideoHeight` logged 6× in one session — every quality-menu popup
  (`VRI[Pop-Up Window]#N`) reconstructs it. Now logs the verdict once.
* `PipelineWatcher: onInputBufferReleased: frameIndex not found` ×4 — decoder pipeline noise around
  the quality switches. Benign as far as can be told.
* Heap still reaching 73MB with ~16MB LOS and GC pauses to 862ms **at 480p**, so the memory pressure
  is not purely a function of resolution. Worth the measurement pass already filed.

---

## 2026-08-02 (run 7) — prot=3 at 480p: it was never the token

prot=3 returned at **480p**, with `cacheKB=6874` — 6.9MB. That kills the run-6 explanation (we were
never "under a 24MB byte budget") and, more importantly, it kills the token theory that has driven
the last several days.

### The actual invariant

| run | resolution | cacheKB | **declared playerTimeMs at prot=3** |
|---|---|---|---|
| 4 | 1080p | 19089 | 60500 |
| 4b | 720p | 8451 | 62696 |
| 5 | 720p | 8805 | 64240 |
| 7 | 480p | **6874** | **65134** |

Bytes vary 3×. Resolution varies from 1080p to 480p. Wall-clock varies. The **declared play head is
always ~60-65s**, and `aheadMs` is always 0. That is the invariant, and it is a *client-reported*
value — not something the server independently measures.

### What we were telling the server

`SabrStreamPump` line ~286:

```java
session.getStreamState().setPlayerTimeMs(edgeMs);   // edgeMs = contiguous BUFFERED edge
```

In run 7 the player was built at 21:18:35.8 and prot=3 arrived at 21:18:46.8 — **11 seconds later**,
declaring `playerTimeMs=65134` with `bufferedEndMs=65134`, i.e. `aheadMs=0`. Read that as the server
does: *"I have watched 65 seconds of a video I started 11 seconds ago, and I have nothing buffered."*
No real player can produce that. The pump burst-fills far faster than real time, and because it
declares the buffered edge as the play head, the declared position races through the server's
look-ahead window in seconds. The server stops sending. It looks exactly like a token rejection
because `status=3` is what it answers with.

This also explains run 6, which played fine for 2:35 at 480p: the software decoder was slow, the
readers crawled, so the declared edge tracked close to real time and never made an impossible claim.
Nothing about the token differed between runs 6 and 7 — only how fast the pump got ahead.

And it explains why every token experiment was inconclusive. Binding (videoId vs visitorData),
percent-encoding, fabricated vs `/sw.js_data` identity, page-vs-session mismatch — none of them were
ever the variable. `MATCH=false` is real and probably still worth fixing, but it is not what
produces prot=3.

### Fix

Declare the **real** playback position (already written to `SabrSessionHolder` by `PlayerScreen`'s
`onPositionUpdate`, and until now used for nothing), floored so it can never lag the edge by more
than `MAX_DECLARED_LOOKAHEAD_MS = 30_000`, and clamped so it can never exceed the edge.

The floor matters: reporting the raw real position was tried before and **deadlocked** — it freezes
while the player buffers, the server stops sending, and nothing un-freezes it. That failure is why
this code reported the edge in the first place. Flooring at edge−30s keeps the declared position
honest while the player runs and self-healing when it goes stale.

Internal pacing is untouched — the pump still paces on `readerHead`, which is what avoids the
original deadlock. This changes only what we *tell* the server.

### How to falsify it on the next run

* `SABR no-media round` should not appear at all, and playback should pass 65s of media.
* If prot=3 still fires, the logged `playerTimeMs` should now be close to the real playback position
  rather than ~65s — which would mean the look-ahead theory is wrong and the token is back in play.

---

## 2026-08-02 (run 8) — I over-claimed in run 7. The token really is unaccepted.

The honest-play-head change landed and works: `playerTimeMs` now tracks the real position
(0 → 84 → 2653 → 7508 → … → 58860) instead of the buffered edge, and `aheadMs=30000` exactly at
21:26:38 is `MAX_DECLARED_LOOKAHEAD_MS` engaging as designed. The logs are far more diagnostic now.

**But the run-7 conclusion was wrong, and this log says so plainly:**

```
21:26:08.457  prot=2  playerTimeMs=0      bufferedEndMs=21300  cacheKB=1721
```

`status=2` on the **very first** no-media round, at 21.3s of buffer — nowhere near any 60s boundary.
StreamProtectionStatus 1 = accepted, 2 = grace, 3 = protected. **We are in grace from request #1**,
which means the PO token has never been accepted in any of these runs. The ~60s wall is the grace
burst expiring, exactly as originally thought. The declared play head explained the *number* but was
not the *cause* — with honest reporting the media limit is unchanged.

That's twice now I've promoted a correlated variable to root cause (bitrate in run 6, declared play
head in run 7). The reliable discriminator was in the logs the whole time and I wasn't reading it:
**prot on the first response.** If it's 2 before any boundary, the token is the problem; nothing
downstream will change that.

### What run 8 actually shows

| time | prot | playerTime | bufferedEnd | cacheKB |
|---|---|---|---|---|
| 21:26:08 | 2 | 0 | 21300 | 1721 |
| 21:26:22 | 2 | 10214 | 31534 | 2453 |
| 21:26:38 | 2 | 30200 | **60200** | **3997** |
| 21:26:55 | 2 | 41259 | 60200 | 3997 |
| 21:27:03 | **3** | 48792 | 60200 | 3997 |
| 21:27:18 | 3 | 58860 | 60200 | 3997 |

Media delivery stops dead at **60200ms / 3997KB** and never advances again. The server keeps
answering (`parts=5`, `status=2`) with no media for ~25s, then escalates to `status=3`. The player
drains the 60s it has and dies at the edge — the familiar one-minute failure, now visible in slow
motion because the play head is honest.

So: grace burst ≈ 60s of media, then nothing. Identity work goes back to **P1**, and
`session=visitorData-L page=visitorData-M MATCH=false` remains the standing candidate.

### Audio dying at ~30s: a real lead, specific to multi-audio videos

This video: `getSabrInfo → formats=86 audioTracks=14`. Runs 6 and 7 both had `audioTracks=1` and no
audio problem. This run had 14 and audio died mid-stream. That correlation is worth taking seriously.

`YoutubeSabrRequestBuilder.writeAudioFormatByItagAndXtagsLength()`:

```java
for (final YoutubeSabrFormat format : info.getFormats()) {
    final int currentXtagsLength = xtags == null ? 0 : xtags.length();
    if (format.isAudio() && format.getItag() == itag && currentXtagsLength == xtagsLength) {
        request.writeMessage(16, SabrProto.formatId(format));   // no return
    }
}
```

Two problems, both only bite when a video has many dubs:

1. **No `return`** — unlike `writeVideoFormatByItag` directly below it, which returns after the first
   match. Every matching audio format gets written into the request.
2. It disambiguates on xtags **length**, not value. Dub xtags differ by language code
   (`…lang=es` vs `…lang=fr`), which are frequently the *same length*.

With 14 tracks at itag 140, that plausibly declares several language variants at once. Audio
stopping partway while video continues is what you'd expect if the tracks interleave or the decoder
hits a discontinuity.

Caveat worth stating: this file is **byte-identical to the PipePipe reference**, so this is upstream
behaviour, not a porting bug — which means either PipePipe filters `info.getFormats()` earlier than
we do, or PipePipe has the same bug on multi-audio videos. Do not "fix" it blind. Reproduce on a
14-track video, log which formatIds get written, and compare against a single-track video first.

---

## 2026-08-02 — checked upstream PipePipe for real. The reference is badly stale.

Prompted by a fair challenge: every "byte-identical to the reference" claim I've made was against
`sabr-reference/`, a **pinned snapshot** of `Priveetee/PipePipeExtractor@3aafd5a` (PR #69). I had
never checked live upstream. Cloned it. Upstream HEAD is `44c84c3` (2026-07-23), and `3aafd5a` is
not even reachable from `main` — it was a PR branch.

### How stale

11 files exist upstream that our reference has never seen:

```
BuiltinSabrSessionPolicy   SabrMediaProtocol          SabrRecoverableException
SabrResponseStatePatch     SabrScriptPolicy           SabrScriptPolicyDocument
SabrScriptPolicyManager    SabrSessionPolicy          SabrSessionPolicyHost
SabrSessionPolicyTranscript                           SabrStreamingResponseReader
```

And the shared files have moved a long way:

| diff lines vs our pinned reference | file |
|---|---|
| 1536 | `YoutubeSabrSession.java` |
| 451 | `YoutubeSabrProbe.java` |
| 367 | `SabrMediaSegmentCollector.java` |
| 324 | `YoutubeSabrFormat.java` |
| 308 | `SabrMediaSegment.java` |
| 267 | `YoutubeSabrStreamState.java` |
| 181 | `UmpReader.java` |
| **84** | **`YoutubeSabrRequestBuilder.java`** |

That last one matters most: I repeatedly described the request builder as "byte-identical to the
reference, so the wire format can't be the problem." It is byte-identical to a **stale** snapshot.
Against live upstream it is 84 lines behind, and the wire format is precisely what the server judges.

### The run-8 audio hypothesis is dead

`writeAudioFormatByItagAndXtagsLength()` is unchanged upstream — but it is only reachable from
`writeOfficialWebPreferredFormats()`, gated behind `shouldWriteAllPreferredFormats() &&
shouldWriteOfficialWebPreferredFormats()`. Both flags default `false` and **nothing in our app calls
either setter** (`SabrSessionFactory` explicitly says the official-web ABR fields are intentionally
not enabled). That code never executes in our build. The missing-`return` asymmetry is real but
irrelevant to us. Retracted.

### What upstream actually changed that looks relevant

1. **`getRequestPlayerTimeMs()`** — a *separate* accessor for the play head that goes into the
   request, distinct from `getPlayerTimeMs()`. It returns **0 until both enabled tracks have
   received their init segment**. Our copy uses `getPlayerTimeMs()` on the request path. Different
   from the look-ahead floor I added this session, and complementary to it.
2. **The first media request now carries initial playback state** — buffered ranges, player time,
   and explicit format selection (video-before-audio ordering is configurable). Ours sends a bare
   first request with `playerTime=0`.
3. **`synchronized (streamState)`** around request construction throughout.
4. **A whole policy layer** (`SabrSessionPolicy` + `BuiltinSabrSessionPolicy`) now drives the
   response handling, with explicit outcomes including `REFRESH_PO_TOKEN` vs `REQUIRE_PO_TOKEN`,
   `TRY_RELOAD`, `RESET_RECOVERY_BUDGETS`, `SLEEP_BACKOFF`. Our port hand-rolls a subset of this.

Directly relevant upstream branches, unmerged at the time of checking:

* `fix/2575-sabr-audio-only-cold-start` — "respect active SABR tracks on cold start"
* `fix/2577-sabr-segment-oom` — "avoid SABR segment buffer growth OOM", "stop SABR UMP reads when
  interrupted" (our heap/LOS problem)
* `fix/2577-sabr-init-metadata` — "expose SABR init metadata"

### Conclusion

Stop reasoning against the pinned snapshot. **Refresh `sabr-reference/` to upstream HEAD and re-diff
before any further prot=2/3 theorising** — several of the things we've been deriving from first
principles have upstream implementations, and at least one of my "it can't be X because it matches
the reference" arguments was resting on a stale file.

---

## 2026-08-02 — SABR package re-ported from upstream `44c84c3`

`sabr-reference/` refreshed from the stale `3aafd5a` PR-branch snapshot to upstream `main` HEAD, and
the app's SABR package re-ported from it.

### The "unmerged branches" were already merged

`fix/2577-sabr-segment-oom`, `fix/2575-sabr-audio-only-cold-start` and `fix/2577-sabr-init-metadata`
are all in `main` as PRs #77/#78/#80 — cherry-picking them conflicts *because the content is already
there*. They sit 63-88 commits behind. `main` also contains "stop SABR UMP reads when interrupted"
with more call sites than the branch had. **Take `main`; don't cherry-pick.** (Second wrong claim of
the day corrected: they were never unmerged.)

### What was ported

53 files copied verbatim. Five held back:

| file | why |
|---|---|
| `SabrScriptPolicy{,Document,Manager}` | self-contained, nothing references them; would drag in Ed25519 (bouncycastle) for a signed-policy feature we don't use |
| `YoutubeSabrProbe` | ours — the I/O boundary |
| `YoutubeSabrFormat` | merged by hand: upstream's data model kept, its signature-cipher/nsig subsystem dropped (we do nsig in QuickJS) |

New in our tree: `org.schabi.newpipe.extractor.downloader.StreamingResponse` (a dependency-free POJO
the streaming reader needs). `nanojson` and `org.brotli:dec` were already dependencies.

### Boundary changes

* `YoutubeSabrProbe.postMediaRequest` now matches upstream's 9-arg signature so the session calls it
  unmodified — segment consumers, spool directory and `SabrMediaProtocol` thread through to
  `SabrStreamingResponseReader.readUntil`, and it returns the 16-field result (byte counts, timings).
* `YoutubeSabrSession.fetchInitializationData` routed from `NewPipe.getDownloader().getStreaming`
  to a new `YoutubeSabrProbe.getStreaming` over the same OkHttp stack, so there is one HTTP client
  in the SABR path. `NewPipe` is no longer referenced anywhere.
* `probeFirstMediaResponse` / `probeFollowUpMediaResponse` deleted — the session builds request
  bodies itself now.

### Local additions, deliberately minimal and marked

Everything else is a clean copy, so the next refresh stays cheap. Three additions, each tagged
`LOCAL ADDITION`:

1. `TAG` / `DIAG` + `mirrorToLogcat()` in `addDiagnosticEvent` — upstream keeps diagnostics in an
   in-memory ring; we debug from logcat. Mirroring in one place beats scattering `Log` calls.
2. `isPoTokenRefused()` — set when the policy returns `REFRESH_PO_TOKEN`/`REQUIRE_PO_TOKEN`, so
   `rebuildSabr()` won't seed a refused token into a new session.
3. Nothing else. The hand-rolled `MAX_CONSECUTIVE_PROTECTED_ROUNDS` escalation was **dropped** —
   upstream's policy layer does this properly via `REQUIRE_PO_TOKEN` → `SabrProtocolException`.

### The look-ahead floor was removed

The `MAX_DECLARED_LOOKAHEAD_MS` override added earlier today is gone. Two reasons, and the second
matters more than the first:

* Run 8 disproved the theory behind it, and the refreshed upstream state **reports the buffered edge
  itself** — so that behaviour was never anomalous.
* Upstream's `getRequestPlayerTimeMs()` checks the override **first**. Setting one every round would
  have silently bypassed its "report 0 until both tracks have received init" cold-start fix (#80).
  Keeping my change would have disabled a real upstream fix.

`setPlayHeadMs()` stays — that drives our eviction, not what we report.

### Verification status — read before testing

**Not compiled.** This sandbox has a JRE but no JDK, no root to install one, and the Temurin release
host is blocked. What *was* checked statically:

* every import across the ported package resolves to a file in the tree;
* every cross-type static call (`Type.method(`) resolves to a declared method;
* braces/parens balance in all 55 ported files plus `SabrStreamPump`;
* the `YoutubeSabrSession(info, audio, video, provider)` ctor our factory uses still exists;
* every session/state method the app calls (`pumpOnce`, `prepareForMediaSegment`,
  `prepareForForwardJump`, `getCachedSegment`, `isBeyondEnd`, `setPlayHeadMs`, `getCachedBytes`,
  `isComplete`, `getStreamState`, `evictPlayed`, and the state's getters/setters) still exists.

None of that catches arity or type mismatches inside method bodies. **Expect the first
`gradlew assembleDebug` to surface some.** They should be shallow — wrong overload, missing cast —
rather than structural.

---

## 2026-08-02 (run 9, post-refresh) — the PO token is ACCEPTED. prot=3 is gone.

**`protection=1/-1` on every single one of the ~70 responses in this run.** Status 1 = accepted —
not 2 (grace), not 3 (protected). After days of chasing this, the reference refresh fixed the
attestation. The `status=2`-from-the-first-response signature that ran through every previous log is
absent.

The stall the user saw at ~2:30 was **not** a token rejection. There is no protection event anywhere
in the log.

### What actually happened

The quality ladder walked all the way down: 480p → 360p → 240p → 144p, each step triggered by
`repeated rebuffering`, and at 144p (itag 160) playback finally sustained. Two `request_failed …
SabrRecoverableException: Could not read SABR media payload` entries sit exactly at the quality
switches — those are the old session's in-flight request being torn down, and the new
`SabrRecoverableException` type from the refresh means the session treats them as recoverable rather
than fatal. That part is working as intended.

### The real defect: video segments arrive with holes

At 240p the video sequence numbers came back:

```
n=0: 133:1,2,3,4     n=1: 133:6,7,8      (5 missing)
n=2: 133:11..15      (9,10 missing)      n=3: 133:17..20   (16 missing)
n=4: 133:23..27      (21,22 missing)     n=5: 133:4,5,6,7  ← rewind
```

Holes, then a reader hits one and forces a rewind, then the pump races ahead and holes again. That
thrash is what starved the decoder and drove the ladder down. Later it degenerates into the server
returning the *same* four segments (133:36-39) five requests running.

**Cause, and it is mine.** Upstream's `getPlayerTimeMs()` returns
`max(audio.bufferedEnd, video.bufferedEnd)`. Audio segments here are ~10s and video ~3-7s, so audio
is routinely further ahead. The server fills FROM the reported position, so reporting the max makes
it resume video at audio's edge and skip everything in between.

Our pre-refresh pump had a fix for precisely this, with a comment saying so:

> *"Report the CONTIGUOUS buffered edge (not readerHead): the server fills from the reported
> position, so reporting readerHead (ahead of a laggard track) made it skip past the gap and the slow
> track's edge never advanced. Pace on readerHead, report on edge."*

When I removed the (wrong) look-ahead floor during the refresh, I deleted the `setPlayerTimeMs(edgeMs)`
call along with it — throwing away the older, separately-motivated and *correct* contiguous-edge
report. Restored.

### Ordering fix so both behaviours coexist

Upstream's `getRequestPlayerTimeMs()` checks the override before the cold-start init gate, so a pump
that sets an override every round would silently defeat "respect active SABR tracks on cold start"
(#80). Swapped the order in the ported state (marked `LOCAL MODIFICATION`): the init gate wins until
every enabled track has its init segment, the pump's contiguous edge wins after. Both fixes hold.

### Still open

* The server advertises `targetAudioReadaheadMs=15000 / targetVideoReadaheadMs=15000` in its
  `NextRequestPolicy` every round; our pump ignores it entirely and paces on
  `READAHEAD_CUSHION_MS = 30_000` plus a 100MB byte cap. Fetching twice the readahead the server
  asks for is likely why we see so many `segments=count=0, backoff=2000` rounds. Worth wiring
  `SabrDecodedResponse.getNextRequestPolicy()` into the pump's throttle — but do it *after*
  confirming the contiguous-edge fix, one variable at a time.
* Audio dropping out ~1:10 is probably the same hole/rewind thrash rather than anything
  audio-specific; re-check once the edge fix is in.

---

## 2026-08-02 (run 10) — status=2 from request #1 again, on a different video

Video `3hSjs2hBa94` (4 min) this time, not run 9's `a965A86aAJ8` (73 min). **The very first response
carries `58={1=2}` — status 2, grace.** Run 9's first response was `58={1=1}`, accepted. So the token
outcome flipped between runs on different videos.

**This is not the contiguous-edge change.** Response n=0's status is decided before the pump has
reported anything: the cold-start gate makes `getRequestPlayerTimeMs()` return 0 until both tracks
have their init segment, and n=0 *is* the response that delivers those inits. The change is therefore
neither exonerated nor indicted by this run — it simply cannot act that early.

### A theory I am deliberately NOT promoting

The escalation 2 → 3 lands at n=3, right after we had buffered audio to ~70s and video to ~60s, and
the server's own policy carries `maxSinceLast=60000`. The "we declare a ~60s play head five seconds
into the session and get flagged" story fits nicely.

It is also almost certainly wrong, for the same reason the run-7 version of it was wrong: **run 9
buffered past 60s while reporting an even higher position** (upstream's `max(audio, video)`, since the
override wasn't set then) and held `status=1` for all ~70 responses. Buffer depth and declared
position cannot be the discriminator if the run that reported *more* was the run that passed.

Recording it here so it stops getting re-derived.

### A real defect, found and fixed

`58={1=3, 2=10}` — the server states a **retry budget of 10** alongside the protected status.
`SabrStreamProtectionStatus` decodes `maxRetries`, `SabrResponseDecoder` stores it,
`SabrDecodedResponse` exposes a getter — and **nothing reads it**, upstream included. So
`BuiltinSabrSessionPolicy` emits `REFRESH_PO_TOKEN` → `CONTINUE` on every protected round, forever.

In this run that meant ~35 protected rounds across 90 seconds with ~8 pointless PO-token re-mints,
against a stated budget of 10, until `PlayerScreen`'s `watchdog: buffering > 25000ms` tore the player
down and rebuilt the session at 22:47:46 — and the rebuild is what actually recovered playback.

Fixed: the policy now counts protection boundaries into `State.poTokenRefreshes` (which upstream
tracks but never increments) and emits `TRY_RELOAD` once the count passes the server's budget. Gets
us to the recovery ~80s sooner, and stops hammering GVS with tokens it has already refused.

### The experiment that would actually settle it

Re-run **`a965A86aAJ8`** — run 9's video — on this build. Same video, one variable:

* first response `status=1` and fewer holes than run 9 → the contiguous-edge fix helped, and run 10's
  grace status is a per-video/per-token effect;
* first response `status=2` → something between run 9 and now regressed token acceptance, and the
  edge change is the prime suspect.

A different video each run cannot answer this.

---

## 2026-08-02 (run 11) — my maxRetries fix crashed the pump; and the "one minute mark" is explained

### The crash — mine, and a lesson about the ported host

```
FATAL EXCEPTION: SabrStreamPump
java.lang.IllegalStateException: Invalid SABR recovery state transition
  at SabrSessionPolicyHost.validateResult(SabrSessionPolicyHost.java:188)
```

`SabrSessionPolicyHost.validateResult()` enforces that a control response leaves upstream's recovery
counters alone:

```java
final int expectedRefreshes = reset ? 0 : state.getPoTokenRefreshes();
...
|| next.getPoTokenRefreshes() != expectedRefreshes
```

My run-10 fix incremented `State.poTokenRefreshes` inside the policy, which is precisely what that
invariant forbids — so the very first `status=3` threw and took the pump thread down with it.
`poTokenRefreshes` is upstream's counter, mutated only via `RESET_RECOVERY_BUDGETS`.

Fixed by keeping the streak in a private field on `BuiltinSabrSessionPolicy` (constructed once per
session in `YoutubeSabrSession`, so it is not shared), cleared alongside `resetRecoveryBudgets()`
when media flows again. `next` is now untouched in that branch and the TRY_RELOAD path matches the
shape upstream already uses for `isReloadRequested()`.

I should have read `validateResult` before emitting a new action from a new branch. The host exists
precisely to catch policy bugs, and it did — at runtime, on the user's device, rather than in my head.

### The A/B I asked for was confounded — but it answered a better question

Same video as run 9 (`a965A86aAJ8`), and **n=0 came back `58={1=2}` — status 2, where run 9 got
status 1.** None of my changes can touch that: the cold-start gate makes the request play head 0
until init arrives, and n=0 *is* the init response; the maxRetries code only runs after a status=3.

So **first-response protection status is not a function of our client behaviour.** It varies run to
run on identical inputs (1, 2, 2 across runs 9/10/11). Any experiment that treats it as a dependent
variable is measuring noise.

### What the runs actually show: 60s is the grace window

| run | n=0 status | outcome |
|-----|-----------|---------|
| 9   | **1**     | media all the way to audio seq 21+ / video seq 39+, never protected |
| 10  | **2**     | status 3 at audio seq 7 (~70s) / video seq 11 (~60.6s) |
| 11  | **2**     | status 3 at audio seq 7 (~70s) / video seq 11 (~64s) |

Runs 10 and 11 flip to `status=3` at *the same segment numbers*. A session that starts in grace gets
roughly 60 seconds of media and is then cut off; a session that starts accepted sails past.
`NextRequestPolicy.maxSinceLast=60000` is plausibly that same window.

**This retires the "one minute mark" as a mystery.** It was never a buffer-depth, declared-play-head,
readahead or decoder problem — three separate theories died on those hills. It is the grace period
expiring. Everything downstream of it is a symptom.

The only thing worth working on is getting `status=1` on the first response. Pump tuning cannot help.

---

## 2026-08-02 (run 12) — best run so far; and the read-ahead throttle has never worked

### There was no grace period in this run

`58={1=1}` on n=0, and `protection=1/-1` on **every** response from 23:19:13 to 23:24:26. No status 2,
no status 3, no re-mint, nothing. The reading "failed after grace but recovered and kept prot=1" is
not what happened — the session was accepted from request #1 and stayed accepted for the whole
5½ minutes.

Same video as run 11, which opened at status 2. Three runs on `a965A86aAJ8`: **1, 2, 1.** That
settles it — first-response protection status is not a function of anything we send. Stop tuning for
it.

### What the user saw at ~1:30 was our own quality downgrade

```
23:20:50.080 repeated rebuffering (sabr:91038608) — requesting lower quality
23:20:50.080 bandwidth low: 480p -> 360p (provenBad=480)
23:20:50.317 Building player (sabr:10004934) resume=82831ms warmSeek=true
23:20:50.274 request_failed n=10 SabrRecoverableException: Could not read SABR media payload
```

The "restart and seek forward" is `PlayerScreen` tearing the player down and rebuilding it at 360p,
resuming at 82.8s. Same again at 23:21:06 (360p → 240p, resume 84.6s). The `SabrRecoverableException`
at each switch is the old session's in-flight request being cancelled — expected, handled.

So the ladder is still 480 → 360 → 240, driven by rebuffering, on a software decoder. But this time
it held at 240p and played cleanly for another 3¼ minutes.

### Real defect: the read-ahead throttle compares a min against a max

```java
final long readerHeadMs = holder.getReaderHeadMs();              // max(audio, video)
final long edgeMs = session.getStreamState().getMinBufferedEndMs(); // min(audio, video)
final boolean throttled = edgeMs - readerHeadMs > READAHEAD_CUSHION_MS; // 30_000
```

`edgeMs` is a **min** and `readerHeadMs` is a **max**, so the quantity being tested is structurally
smaller than the real read-ahead and the throttle barely ever fires. Audio decodes cheaply and races
ahead, dragging the max up; video crawls on `c2.goldfish.h264.decoder`, holding the min down.

Measured in this run: the 240p session started at 23:21:09 resuming at 84.6s; by 23:24:23 (194s of
wall clock, so playback ≈ 4:38, matching where the user stopped) the buffered edge was audio seq 52 /
video seq 99 ≈ **8:29**. That is **231 seconds of read-ahead against a 30-second cushion — 8× over.**
The 100MB byte cap never binds at 240p (~44 KB/s ⇒ ~10MB), so nothing else stops it.

Fixed by pacing against `getReaderTailMs()` (min of the two reader positions — the track that
actually needs data), falling back to the head while it is still 0 during cold start so the initial
fill is unchanged.

**Not claiming this causes the audio dropouts or the downgrades.** It is a clear, measured defect
worth fixing on its own; whether it moves the other symptoms is for the next run to say. Three
theories have already died from promoting a correlation to a cause here.

### Remaining, in order

1. Audio drops out mid-playback (before and after the 1:30 rebuild). Still unexplained.
2. The quality ladder bottoming out is the software decoder on the emulator; a hardware-AVC device
   would likely never trigger it. Worth confirming on real hardware before optimising for it.

---

## 2026-08-02 — the maintainer's timing hypothesis: warmup/playback overlap vs. first-response status

Proposed by the user: if playback is started before the warmup PO token is settled, the content mint
may collide with the warmup finishing. Checked against every run where the first-response status is
known. The gap is between `"warmup: authed session ready"` and the first player-path Innertube call
(`getVideoInfo` / `getSabrInfo`):

| run | warmup ready | first playback call | gap | n=0 status | outcome |
|-----|--------------|---------------------|-----|-----------|---------|
| 9   | 22:32:50.563 | 22:32:54.444 | **3.88 s** | **1** | played, never protected |
| 10  | 22:46:07.014 | 22:46:07.025 | **0.011 s** | **2** | → status 3 at ~60s |
| 11  | 23:11:18.671 | 23:11:18.676 | **0.005 s** | **2** | → status 3 at ~60s |
| 12  | 23:18:56.942 | 23:19:04.574 | **7.63 s** | **1** | played, never protected |

Four for four, cleanly separated, no overlap between the groups. Same video (`a965A86aAJ8`) appears in
both groups (runs 9/11/12), so it is not a per-video effect — which is exactly what made the earlier
"first-response status is just noise" conclusion look right when the only variable considered was the
video ID.

**The 5 ms and 11 ms gaps are the tell.** Those are not "the user tapped quickly" — they are the
playback request being *serialised behind warmup* and firing the instant it released. Whatever
mutex/coroutine gates the runtime, the player path queued on it and went the same millisecond warmup
completed. That is a real, reproducible program state, not user timing jitter.

Mechanism is unknown. Candidates, none verified: the content-bound mint reusing a botguard snapshot
or integrity token that warmup has just consumed; GVS degrading a second attestation issued too soon
after the first from the same WebView; or a visitorData/session swap landing between the mint and the
player request.

### Deliberately not writing code for this yet

Same discipline that the last four theories failed: this is a correlation with a plausible story, and
the cheap move is a deliberate A/B before touching anything.

**Experiment (two runs, no rebuild required):**

1. Cold-start the app and tap a video **as fast as possible**, before the feed settles.
2. Cold-start again and **wait ~30 s** on the feed, then tap the same video.

Grep each log for the gap and for `58={1=` on response n=0. If (1) gives status 2 and (2) gives
status 1, the hypothesis holds and the fix is to gate the player path on warmup completion plus a
settle margin, or to re-mint the content token only after warmup has fully released. If both give the
same status, the correlation above is coincidence across four samples and this goes in the bin with
the others.

---

## Run 15 (2026-08-02 23:50) — the timing hypothesis is falsified

Arm 2 of the experiment above: cold start, wait on the feed, then tap `kTVVNWdAnUA`.

| | |
|---|---|
| warmup ready | 23:50:53.438 |
| `getSabrInfo("kTVVNWdAnUA")` | 23:51:03.943 |
| **gap** | **10.5 s** |
| n=0 protection | **2 (grace)** |

Predicted status 1. Got status 2, on the longest gap yet recorded — longer than run 9 (3.88 s) and
run 12 (7.63 s), both of which gave status 1. The gap does not predict the first-response status.
The four-run separation in the table above was coincidence, and this is the fifth theory to die the
same way. Retired.

### The 60 s grace quota is now confirmed a third and fourth time

Two grace sessions ran in this log. Both delivered media up to **audio seq 7 / video seq 11** and
then answered status 3 on the very next response — identical segment numbers to every earlier grace
session, and ~60 s of *media*, not 60 s of wall clock (session 1 burned 13.5 s of wall time getting
there). This is a server-side content quota on the grace burst, not a timer.

### The `maxRetries` fix works, and lands somewhere useless

No crash — `validateResult` is satisfied, so the backoff-pairing fix is good. But `TRY_RELOAD` runs
into:

```
SabrStreamPump  W  SABR pump fatal: SABR player-response reload is not supported
                   (no SabrInfoProvider configured)
```

`YoutubeSabrProbe.setSabrInfoProvider()` is never called, so `fetchSabrInfo` throws. The budget check
therefore converts an infinite retry loop into a *fatal pump error* 30 s in, ExoPlayer surfaces it
33 s later as `Source error`, and only then does the player rebuild. Net recovery ~63 s versus ~90 s
before. Marginal, and for the wrong reason.

### What actually recovered the stream

The buffering watchdog, at 23:52:57:

```
PlayerScreen  W  watchdog: buffering > 25000ms — retrying
[__yt] getVideoInfo forceFresh — resetting client + session + token
```

`forceFresh` clears `authedSessionPromise` and calls `invalidatePoToken()`, so the next
`createAuthedSession()` builds a brand-new Innertube session and mints a fresh visitorData-bound
token. The session built on top of that answered **`58={1=1}` on n=0 and stayed status 1 for the
remaining ~4 minutes**, through three quality downgrades (480→360→240→144), never protected again.

So the recovery path is known and reproducible. Three sessions in one log:

| session | player-response poToken | n=0 status | outcome |
|---|---|---|---|
| 1 (23:51:11) | warmup token, minted 23:50:38 | 2 | 60 s, then status 3 ×11, pump fatal |
| 2 (23:52:36) | same warmup token | 2 | 60 s, then status 3, watchdog fired |
| 3 (23:53:21) | fresh token, minted 23:53:00 | **1** | played to the end of the run |

### New lead: youtubei.js randomises the context User-Agent per session

`Innertube.create()` defaults `user_agent` to `getRandomUserAgent('desktop')` — a 27-entry pool that
includes Opera (`…Chrome/137… OPR/121`), bare Safari (`…Version/16.6.1 Safari/605.1.15`, no Chrome
token at all) and several macOS strings. It does **not** vary `browserName` / `browserVersion` /
`osName` to match, so the generated context can claim `browserName=Chrome, browserVersion=125.0.0.0,
osName=Windows` while the UA says Opera, or Safari, or macOS.

Observed in this log: sessions 1 and 2 drew the Opera UA and got grace; session 3 drew
`Chrome/140.0.0.0` (macOS) and got status 1.

**This is not being promoted to a cause.** n=3, and session 3 is confounded with the token/session
refresh. It also cannot be the whole story: non-Chrome draws are roughly 22 % of the pool, while
grace has been the *majority* outcome across runs 9-15. And the direction is not clean either — the
Opera UA at least agreed with `osName=Windows`, whereas the macOS UA that succeeded did not.

**Action taken:** pinned `user_agent: DESKTOP_UA` on both `createAnonSession()` and
`createAuthedSession()`, with `DESKTOP_UA` changed to `Chrome/125.0.0.0` on Windows 10 so that the
UA, `browserName`, `browserVersion`, `osName`, `osVersion` and `platform` all agree. This is
justified on its own terms as a fingerprint-consistency fix; its real purpose here is to remove a
per-session random variable so the next run is interpretable. It is not expected to fix grace.

### Next

1. **Wire `SabrInfoProvider`** from the Kotlin bridge so `TRY_RELOAD` performs the `forceFresh`
   equivalent in place — new session, new token, re-fetched player response — instead of throwing.
   This is the one change directly evidenced by the log: that exact sequence is what recovered
   playback.
2. **Stop paying for grace.** The n=0 status is decided before a single media byte arrives, and a
   grace session is worth exactly 60 s and then a guaranteed hard failure. Detecting `58={1=2}` on
   n=0 and immediately rebuilding with `forceFresh` skips ~60 s of doomed media plus 10 retries.
3. Audio dropouts remain unexplained, but this log is thick with
   `AudioTrack: disabled due to previous underrun` and `Choreographer: Skipped 30-131 frames`, and
   the ladder bottomed out at 144p while still rebuffering. Confirm on real hardware before
   optimising for `hwAvc=false`.

### Changes made after run 15

**1. `SabrInfoProvider` is wired (`SabrReloadProvider.kt`, installed from `App.onCreate`'s warmup
coroutine).** `TRY_RELOAD` now re-probes instead of throwing. The re-probe goes through
`bridge.getSabrInfo(videoId, forceFresh = true)` — new Innertube session, freshly attested token —
because a reload that reuses the rejected session asks the same unwelcome identity for new URLs.
Bounded by the session's existing `MAX_RELOADS_PER_SESSION = 2` and a 45 s timeout on the pump
thread (the measured forceFresh round trip was ~18 s).

**2. Grace sessions are abandoned at n=0.** `YoutubeSabrSession` latches the protection status of
response n=0; `SabrStreamPump` reports status 2 to the holder; `PlayerViewModel.onGraceSession`
rebuilds with `forceFresh = true`, resuming at the current play head. Playback continues on the
grace burst while the replacement is built, so the switch should be close to invisible. Budget is
one rebuild per video, deliberately separate from `autoRetries` — nothing has *failed* when this
fires, so it must not spend the budget the legacy fallback depends on. All three holder
construction sites are wired (live fetch, autoplay prefetch, quality/audio rebuild).

**3. `Innertube.create()`'s random User-Agent is pinned** (see the previous section).

### Verification

`javac` and `kotlinc` were obtained in the sandbox this time (JDK extracted from the .deb over the
installed JRE's runtime libs; kotlinc via `npm pack kotlin-compiler`), which is a real improvement
over the brace-balance heuristic used in earlier rounds:

- the whole `org.schabi.newpipe.extractor` tree compiles with **0 errors** under real `javac`
  (against local stubs for javax.annotation / nanojson / okhttp3 / brotli / android.util), so the
  n=0 latch, the volatile field and the earlier `maxRetries` fix are genuinely type-checked;
- `SabrSessionHolder.java` + `SabrStreamPump.java` compile with **0 errors** against those classes;
- the changed Kotlin files parse with **0 syntax errors** (full type-checking still needs the
  Android SDK, which the sandbox does not have — the Kotlin side is *not* proven to compile);
- `BuiltinSabrSessionPolicy`'s `TRY_RELOAD` branch was re-walked against all five clauses of
  `SabrSessionPolicyHost.validateResult` — state patch, redirect pairing, requestNumber/reloads/
  redirectCount/poTokenRefreshes transitions, and the backoff/PO-token action gates. All satisfied.

**Known unverified risk:** `SabrReloadProvider` calls `runBlocking` on the pump thread to reach the
suspend bridge. Nothing it touches is held by the pump at that point (the PO-token mutex is taken on
a different code path), but this has not been exercised under contention.

### What run 16 should show

- `[__yt] getSabrInfo forceFresh — resetting session + token` appearing within a second or two of a
  `58={1=2}` at n=0, rather than 85 s later via the watchdog.
- If the hypothesis from run 15 generalises, the rebuilt session answers `58={1=1}` and plays out.
- If it comes back in grace again, that is the more interesting result: the rebuild reproduces the
  watchdog's exact sequence, so a second grace would mean run 15's recovery was not caused by the
  fresh session at all, and the search moves elsewhere.

---

## Run 16 (2026-08-03 22:56) — the bail-out works, and it kills the UA hypothesis

Same video as runs 9/11/12 (`a965A86aAJ8`). Smooth 480p with audio for the full 4 minutes, no quality
downgrades, no rebuffering, no watchdog.

### The intervention fired as designed

| time | event |
|---|---|
| 22:57:24.724 | session 1 response n=0 → `58={1=2}` (grace) |
| 22:57:24.727 | `SABR opened in grace (status=2) … rebuilding on a fresh session/token (attempt 1)` |
| 22:57:24.729 | `getSabrInfo forceFresh — resetting session + token` |
| 22:57:33.449 | player rebuilt |
| 22:57:34.836 | session 2 response n=0 → **`58={1=1}`** |
| …23:01:26 | n=1 … n=16, `protection=1` throughout, video seq 62 (~311 s of content) |

**3 ms** from verdict to decision, ~9 s to a playing replacement. Against the old path — 60 s of grace
burst, then the retry budget, then the 25 s watchdog — that is ~85 s saved, and this time the recovery
was deliberate rather than the watchdog stumbling into it.

### The User-Agent hypothesis is dead

Both sessions ran the pinned UA (`Chrome/125.0.0.0` on Windows, matching `browserName`,
`browserVersion`, `osName`, `osVersion`, `platform`). One got grace, the other got accepted. Pinning
removed the variable and the split survived it, so the UA was never the cause. This is exactly why it
went in as an unpromoted lead rather than a fix; the pin stays (a self-consistent client context is
correct on its own terms) but it explains nothing.

### What actually separates the two sessions

Identical across both: visitorData (`visitorData-N==`), UA, client version, video,
process. The only difference is **which visitorData-bound PO token signed the `/player` call**:

| run / session | token used at `/player` | age | n=0 |
|---|---|---|---|
| 15 s1 | warmup mint | 25 s | 2 |
| 15 s2 | warmup mint (reused) | 111 s | 2 |
| 16 s1 | warmup mint | 23 s | 2 |
| 15 s3 | re-mint | 15 s | **1** |
| 16 s2 | re-mint | 8 s | **1** |

Five for five. But **"freshly minted" and "second mint of the process" are perfectly confounded** —
every accepted session used the process's *second* visitorData mint, every grace session used the
*first*. So this does not yet distinguish token age from something specifically wrong with the
warmup mint.

### Next hypothesis: the warmup mint is taken before the page has settled

The warmup mint happens ~9 ms after `WebPO init complete`:

```
22:56:57.897  WebPO init complete (ttl=43200s)
22:56:57.906  [sabr-pot] fetching att/get challenge...
22:56:58.220  Minted GVS PO token … (len=150)
22:57:00.714  PlayCore StandardIntegrity : warmUpIntegrityToken(...)
22:57:00.720  PlayCore StandardIntegrity : Failed to bind to the service.
22:57:00.726  ERROR Uncaught OperationError: Internal Error. Retry with an exponential backoff.
```

The page's own integrity bring-up is still running ~2.5 s *after* we took our snapshot — and run 15
shows the same straddle. Every later mint runs on a fully warmed page.

**Cheap test:** delay the warmup mint a few seconds past `WebPO init complete`, or discard it and
re-mint once before first playback. If the warmup-timing theory holds, session 1 comes back status 1
and the bail-out never fires. If session 1 is still grace, the discriminator is token *age* or
mint *ordinality*, not page readiness — and the follow-up is to re-mint immediately before `/player`
regardless of how fresh the cached token looks.

### Other observations

- **The reload path is still unexercised.** The session never went protected, so `TRY_RELOAD` and
  `SabrReloadProvider` never ran. That code remains unverified in the field.
- **~7.6 s of the 9 s rebuild is `base.js` re-parse.** After `forceFresh`, the new authed session
  re-fetches and re-parses the player (`ca042962` — the *same* player it already had) inside QuickJS:
  `base.js ← 200` at 22:57:25.342, next activity at 22:57:32.977. Caching the parsed player across
  session rebuilds would cut most of the visible gap.
- **`request_failed n=1 type=InterruptedIOException message=interrupted`** is the old pump being torn
  down mid-request. Harmless, but it logs as a failure and will mislead later analysis; worth
  demoting to debug when the interrupt came from `release()`.
- **`MATCH=false` on every mint, including the accepted session.** The session/page visitorData
  mismatch that `createAnonSession`'s comment blames for grace was present throughout a session that
  GVS accepted and streamed for 4 minutes. Second run in a row showing this. The BACKLOG item
  "Adopt the WebView's visitorData as the session identity" is not on the critical path.
- **Pump pacing is healthy.** 16 responses over 4 minutes, ~25 s apart, buffered edge tracking
  playback with a modest lead — no sign of the 231 s over-fetch from run 12.

### Changes made after run 16

**1. Post-init settle window before the first PO-token mint** (`WebPoTokenProvider`). A new
`awaitPageSettled()` blocks until `SETTLE_AFTER_INIT_MS` (4 s) has elapsed since
`onJsInitializationDone`, and is called from `prewarm()` and from both mint paths.

Driven by a *timestamp*, not by "did this call perform the init" — App starts `prewarm()` and the JS
warmup concurrently, so whichever reaches a mint first must be the one that waits. An earlier draft
put the dwell inside `prewarm()` alone and only worked if `prewarm` happened to win `mintMutex`;
that was luck, not design. In the normal case the window is spent in the background and costs
nothing, because warmup finishes long before the user taps a video.

**2. Player cache across session rebuilds** (`js-runtime/src/index.js`). youtubei.js already has the
logic — `Player.create()` resolves the current player id from `/iframe_api`, checks a cache, and only
downloads + extracts `base.js` on a miss — but ships a browser IndexedDB cache that does not exist in
QuickJS. Added a two-tier one: in-memory (bounded to 3, oldest-first eviction) over a base64 blob in
the existing `__nativeStorage` DataStore, capped at 512 KB per entry because DataStore re-serialises
its whole file on every edit. Every method degrades to a cache miss rather than throwing.

Staleness needs no logic of ours: the key *is* the player id, `Player.create` re-resolves that id on
every call, and youtubei.js version-stamps the blob and carries the signature timestamp inside it.

⚠️ One trap worth recording: youtubei.js feeds the same `cache` object to **both** `Player.create`
and `Session.getSessionData`, and the latter's `enable_session_cache` defaults to **true**. Adding a
cache therefore silently opts into caching the *session* as well — which would have restored the old
session wholesale and made `forceFresh` a no-op, quietly disabling the one mechanism that has
actually converted grace into status=1. `enable_session_cache: false` is now set explicitly with a
comment saying why.

### Verification

Substantially stronger than previous rounds — kotlinc's own `kotlinx-coroutines-core-jvm.jar` turned
out to ship inside the compiler distribution, which removed the last big blind spot:

- **Player cache: 15/15 behavioural tests pass**, run in node against the real code lifted out of
  `index.js` — round-trip through memory and through disk, `ArrayBuffer` shape matching youtubei.js's
  `new Uint8Array(buffer)` contract, wrong-player-id miss, oversize staying memory-only, eviction
  bounded to the newest 3 with evicted entries still restorable from disk, and storage failures
  degrading to a miss without throwing.
  - One initial FAIL was a *harness* bug, not a code bug: indirect `eval` hoists the
    `rememberPlayerInMemory` function declaration to the global object, so a second instance
    clobbered the first's closure. Scoping each instance in an IIFE fixed it. Worth noting because
    the first instinct was to "fix" the cache.
- **Settle window: 5/5 behavioural tests pass**, compiled with real kotlinc against real coroutines
  and executed — including the race that broke the first draft (a mint contending with `prewarm`
  still lands after the window regardless of lock order), plus "uninitialised provider does not
  block" and "reset re-arms the window".
- `WebPoTokenProvider.kt` compiles with the coroutine cascade fully resolved; the 37 remaining
  diagnostics are all missing members on my hand-written Android stubs, and **none fall on any line
  changed in this session** (checked by line-range intersection, not by eye).
- Extractor tree and app-side SABR Java: **0 errors** under real `javac`. All touched Kotlin: **0
  parse errors**. Bundle rebuilt.

Still unproven: the full Kotlin app does not type-check here (no Android SDK), and
`SabrReloadProvider`'s `runBlocking` remains unexercised.

---

## Run 17 (2026-08-03 23:36) — settle window falsified, player cache confirmed

Same video (`a965A86aAJ8`). Both new changes did exactly what they were built to do. One of them
answered its question with a clean "no".

### The settle window worked mechanically — and changed nothing

```
23:36:32.323  WebPO init complete (ttl=43200s)
23:36:32.325  WebPO settling for 3998ms before minting     ← a mint was already queued
23:36:34.041  PlayCore StandardIntegrity : warmUpIntegrityToken(...)
23:36:34.201  ERROR Uncaught OperationError: Internal Error. Retry with an exponential backoff.
23:36:36.614  Minted GVS PO token … (len=150)              ← init + 4.29 s (was init + 9 ms)
```

The mint moved from 9 ms after init to **4.29 s** after it, and now lands *after* the page's own
integrity bring-up instead of before it — exactly the ordering the change was meant to produce. It
cost nothing: the user tapped 19 s later.

**Response n=0 was still `58={1=2}`.** Page readiness is not the cause. Hypothesis retired.

### Token age and mint ordinality are dead too

Five `/player` calls in this run, which is the sharpest data yet:

| session | token age at /player | n=0 |
|---|---|---|
| 1 first play | 19.15 s | 2 |
| 2 grace bail-out | **0.155 s** | 2 |
| 3 reload #1 | 0.145 s | 2 |
| 4 reload #2 | 0.258 s | 3 |
| 5 player rebuild | 28.37 s | **1** |

Pooled with runs 15-16: accepted at 8 s, 15 s, 28 s; grace at 0.15 s, 0.15 s, 19 s, 23 s, 25 s,
111 s. No monotone relationship in either direction. Session 2's token was also the *second* mint of
the process — the exact configuration that produced status 1 in runs 15 and 16 — and it got grace.

So both surviving candidates from run 16 are gone. **Nothing client-side that has been identified
predicts the n=0 verdict.** The remaining live possibility is that the split is substantially
server-side (which GVS frontend answers), which is not something this codebase can fix — but it is
something the recovery path can be designed around, and that is where the effort should now go.

### The player cache is confirmed, with numbers

```
23:36:37.714  GET …/base.js
23:36:37.928  ← 200
23:36:44.911  player cache: stored ca042962 (137645b)     ← 6.98 s of extraction
```

Three subsequent session rebuilds (23:36:58, 23:37:26, 23:37:55) fetched `/iframe_api` and **no
`base.js` at all**. The grace bail-out's rebuild went from ~9 s in run 16 to **1.02 s** here. The
blob is 137 KB, comfortably inside the 512 KB persist cap, so it also went to disk — the
`restored … from disk` path should show on the next cold start and has not been observed yet.

### The reload path ran in the field for the first time, and did not help

`SabrReloadProvider` fired twice (23:37:26, 23:37:55), re-probed cleanly, and the session resumed in
place — response n=16 even delivered audio seq 6-7 at status 2. No crash, no "no SabrInfoProvider
configured". That code is now field-verified. But neither reload recovered the stream; both ended
back at status 3.

### The real problem this run exposes is the ORDER of the recovery ladder

```
23:36:57.996  n=0 grace
23:37:01.824  status 3 (after audio seq 7 / video seq 11 — the same ~60 s quota, again)
23:37:01→23:38:20   10 retries + 2 reloads          ← 78.6 s, none of it useful
23:38:20.416  SABR pump fatal: reload budget spent
23:38:23.502  ExoPlayer Source error
23:38:24.536  player rebuilt → n=0 = 1, plays cleanly for the rest of the run
```

**86.5 seconds** from grace to working playback, 78.6 s of it inside in-session retry machinery that
has never once recovered a session. Meanwhile the full player rebuild has now recovered it **2 for
2** (run 16 session 2, run 17 session 5) — and it sits at the *end* of the ladder.

MAX_GRACE_REBUILDS=1 was also too tight, but the budget is the smaller half of the problem. The fix
is to invert the ladder: when a session that opened in grace goes protected, skip the retry budget
and both reloads and go straight to a player rebuild, and allow more than one.

### The strongest signal in the dataset: UMP part ordering

Every response carries its parts in one of two orders, and the order separates the outcomes
perfectly across runs 15-17 (~30 responses, n=0 and follow-ups alike):

| first part | protection |
|---|---|
| `parts=[47:12, 58:2, …]` | 2 (grace) |
| `parts=[47:12, 58:4, …]` | 3 (protected) |
| `parts=[58:2, 47:12, …]` | **1 (accepted)** |

`47` is the start/resume control, `58` is StreamProtectionStatus. An accepting server emits the
protection status FIRST; a gating one emits it after the control. That is a serialization difference
inside GVS — two different code paths — and it travels with the verdict.

**This is a symptom, not a lever**, but it reframes the problem. It says the verdict is decided
upstream of anything we send, which is exactly what one would predict from the fact that every
client-side hypothesis has now died: User-Agent, page-settle timing, token age, mint ordinality, and
session/page visitorData match are all falsified, each by a run that held the variable and changed
the outcome anyway.

Counting draws across runs 15-17: roughly 3 sessions in 10 land on the accepting path. A rebuild does
not *fix* anything — it buys another draw.

Checked and rejected as an explanation: the "fresh probe vs `(cached)` probe" difference in the first
`getSabrInfo` of a process. `SABR_CLIENT_PREFERENCE` starts with `WEB`, so the fresh path makes
exactly one `/player` call, same as the cached path; the only reason run 17 s1 logs a fresh probe is
that a reinstall cleared the persisted preference.

One honest caveat: the first session of all three runs landed on grace, 3/3. At a ~30% success rate
that is p ≈ 0.34 — far short of evidence that first-play is special.

### Changes made after run 17

**1. The recovery ladder is inverted.** `SabrStreamPump` now abandons a session the moment one that
*opened* in grace answers protected, instead of letting the policy spend the server's 10-retry budget
and both in-session reloads (78.6 s in run 17, with no observed success ever). Guarded on the session
having opened in grace — a session that opened at status 1 and hits a transient 3 is a different
case, and the existing re-mint/reload path is the right response for it. Needed a new
`getLastResponseProtectionStatus()` alongside the n=0 latch.

**2. `MAX_GRACE_REBUILDS` 1 → 3.** The old value assumed a second grace meant retrying was pointless.
Run 17 showed the premise was right (rebuild #1 did come back in grace) and the conclusion was wrong
(what eventually fixed it was another rebuild, 86 s later). If each session is close to an
independent draw, more draws is the correct shape — and since the player cache landed, a draw costs
~1 s instead of ~9 s. Three bounds the worst case at a few seconds and takes the odds of catching an
accepting session from ~30% to ~65%.

Verified: extractor package and app-side SABR Java both compile with **0 errors** under real `javac`;
`PlayerViewModel.kt` has 0 parse errors.

### What run 18 should show

- `SABR opened in grace … (attempt 1/2/3)` in quick succession, each rebuild ~1 s, instead of one
  attempt followed by a long stall.
- If a rebuild draws the accepting path, `parts=[58:2, 47:12, …]` and clean playback within a few
  seconds of opening the video.
- If all four draws land on grace: ~60 s of burst, then `grace session went protected — abandoning
  for a rebuild`, and NO 78 s of retry/reload grinding.
- The part-ordering prediction is falsifiable: any status-1 response whose parts start with `47`, or
  any grace response starting with `58`, kills it.

---

## Ecosystem research (2026-08-04) — status 2 is NOT a grace period, and I had it wrong

Searched the yt-dlp/NewPipe/PipePipe/LuanRT ecosystem for prior art. The single most important
finding overturns the model this document has been built on since run 9.

### The documented meaning of StreamProtectionStatus

From LuanRT's BgUtils, which is the reference for the BotGuard/PO-token side of this:

| sps | meaning |
|---|---|
| 1 | the stream is either already using a PO token or does not need one |
| 2 | **the stream requires a PO token**, but the client may pull **~1-2 MB** before playback is interrupted |
| 3 | the stream requires a PO token and playback is interrupted immediately |

So status 2 is not probation, not a warm-up, not a "grace period" in any temporal sense. **It means
GVS is not seeing a valid PO token on that session and is giving us a small byte allowance before
cutting us off.**

### The seq-7 boundary is a BYTE quota, and it confirms the above exactly

Every grace session in every run stopped at audio seq 7. I read that as "~60 s of content". It isn't
— itag 140 is CBR ~128 kbps, so 60 s and 1.08 MB are the same number wearing different clothes.
Summing the audio actually delivered before the cutoff, on two *different* videos:

```
run 17  a965A86aAJ8   audio seq 1-7 = 1,131,429 bytes = 1.08 MB
run 15  kTVVNWdAnUA   audio seq 1-7 = 1,131,615 bytes = 1.08 MB
```

Two different videos, agreeing to within 200 bytes, landing inside the documented 1-2 MB allowance.
That is a byte quota, not a timer, and it is the same quota BgUtils describes.

### What this means for the conclusion drawn after run 17

The "it's a server-side lottery, ~3 draws in 10 land well, so take more draws" conclusion is
**probably wrong**, or at best describes a symptom. The documented semantics say status 2 is a
verdict on *our token*. That makes this a client-side problem again, and puts the question back to:
why is GVS intermittently not accepting a token we believe we minted correctly?

The `58`-first / `47`-first part-ordering correlation still holds and is still a useful oracle, but
it should now be read as "the response is serialized differently once the token is accepted" rather
than as evidence of two independent server paths.

The retry-ladder changes from run 17 stay — they are a strict improvement regardless, and PipePipe
reached for the same workaround (5.1.0 "introduces a temp workaround to retry automatically"). But
they are palliative, not the fix.

### Verified in our code: the token IS in the right place

`YoutubeSabrRequestBuilder` writes it into the StreamerContext message at field 2
(1 = clientInfo, 2 = poToken, 3 = playbackCookie), which matches the known SABR layout. And
youtubei.js deliberately does NOT append `pot=` to the URL when `sabr=1` — consistent with our logs
(`potIn=false potOut=false`). So placement is correct; if the fault is ours it is in the token's
content, binding, or freshness, not where we put it.

### Escape hatches the ecosystem uses (none of which we currently take)

yt-dlp's per-client GVS PO-token requirements:

| client | GVS PO token | note |
|---|---|---|
| `web` | **required** | only SABR formats — this is what we use |
| `mweb` | required | **yt-dlp's own TL;DR recommendation is `mweb` + a GVS PO token** |
| `web_safari` | required\* | \*serves **HLS (m3u8)**, which does not need a GVS PO token at this time |
| `tv` | **not required** | all formats DRM'd without cookies |
| `android_vr` | **not required** | "made for kids" videos unavailable |
| `web_embedded` | **not required** | embeddable videos only |

Our `SABR_CLIENT_PREFERENCE` is `['WEB', 'ANDROID', 'IOS', 'TV', 'WEB_EMBEDDED', 'ANDROID_VR']` —
`WEB` wins every time, so the three clients that need no GVS token are never reached. NewPipe's
AudricV notes they deliberately run WEB *and* TVHTML5, using TVHTML5 for users who cannot supply
poTokens. Caveat from the same thread: SABR configs and URLs are client-specific and cannot be mixed
(an MWEB audio itag on a WEB SABR URL errors).

### Other implementations worth reading

- **SmartTube** ships a Java/ExoPlayer SABR implementation —
  `exoplayer-amzn-2.10.6/library/sabr/src/main/java/com/google/android/exoplayer2/source/sabr` — the
  closest thing to a direct comparison for our port.
- **LuanRT/googlevideo** + **yt-sabr-shaka-demo** are the reference SABR implementations.
- **NewPipe #12248** is where the community coordinates; NewPipe itself has not shipped SABR.
- **PipePipe** has no public write-up of the protection-status handling; its 5.1.0 answer was
  automatic retry.

### Next steps this suggests, in order of expected value

1. **Try a client that needs no GVS PO token at all** (`android_vr`, or `tv` with cookies), or
   `web_safari` for HLS. If any of them plays, the entire attestation problem is sidestepped rather
   than mitigated. Cheap to test: reorder `SABR_CLIENT_PREFERENCE`.
2. **Re-examine why the token is rejected**, now that we know status 2 *is* a rejection. The obvious
   suspects are the content binding and the identity the token is attested against — note
   `MATCH=false` (session vs page visitorData) has been present on every run including the accepted
   ones, which I previously used to dismiss it; under the new reading it deserves another look.
3. Keep the retry ladder as a safety net.

---

## BgUtils comparison (2026-08-04) — three divergences in our attestation

Compared `app/src/main/assets/sabr_potoken.js` against LuanRT/BgUtils' documented flow. The binding
question is NOT re-litigated here: `AppSabrPoTokenProvider` already probed videoId vs visitorData on
2026-08-02 and both were rejected, so it is not the variable. Three other differences are, and none
has been tested.

### 1. We re-run the ENTIRE attestation for every mint; the reference attests once and mints many

BgUtils: *"Once minted, the PO Token is then reused for the rest of the session … the player will
continue using the cached token as long as its respective integrity token is still valid."* The model
is: run BotGuard once → keep `webPoSignalOutput` and the integrity token → call `mintCallback`
repeatedly.

Ours re-runs `att/get` → `new Function(interpreterJavascript)()` → snapshot → `GenerateIT` → mint on
**every single mint**. Counting `[sabr-pot] run start` in run 17: **11 full attestations in 108
seconds**, each one creating a brand-new BotGuard VM in the page.

This is the most plausible mechanism I have for the intermittency. BotGuard exists to attest the
runtime *environment*; a VM created and snapshotted 30 ms later has observed essentially nothing of
the page — no user interaction, no event history, no dwell. The real player creates the VM once, lets
it live, and mints from it repeatedly. If snapshot quality varies with how much the VM has observed,
"sometimes accepted, sometimes not" is exactly what you would expect — and it would explain why
elapsed-time and token-age hypotheses kept half-fitting: they are all proxies for how settled the VM
and page were.

Our own log already shows the integrity token TTL is **43200 s (12 h)**. We throw that away and
re-attest seconds later.

### 2. We never call `shutdownFunction`, so every mint leaks a BotGuard VM

BgUtils captures four functions from the VM callback:

```js
const vmFunctionsCallback = (asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) => {…};
this.syncSnapshotFunction = await this.vm.a(this.program, vmFunctionsCallback, true, undefined, () => {}, [[], []])[0];
```

`sabr_potoken.js` `loadBotGuard()` captures only the first:

```js
vm.a(program, function (asyncSnapshotFunction) { … resolve({ asyncSnapshotFunction }); }, true, undefined, function () { }, [[], []]);
```

`shutdownFunction` is never captured and never called, and the return value of `vm.a(...)` (whose
`[0]` is the sync snapshot function) is discarded. So run 17 left **11 live BotGuard VM instances**
in one page. Several concurrent attestation VMs in a single document is not something the real player
ever does, and is plausibly itself an abuse signal.

### 3. We discard three of the four `GenerateIT` response fields

The response is `[integrityToken, estimatedTtlSecs, mintRefreshThreshold, websafeFallbackToken]`.
`fetchIntegrityToken()` reads `data[0]` and drops the rest. `mintRefreshThreshold` is precisely the
"re-mint now" signal we have been guessing at for three runs, and `websafeFallbackToken` is the
documented degraded-attestation path we currently have no handling for.

### Supporting detail

BgUtils states a minted token is *"about 110–128 bytes"*. Ours: visitorData-bound ≈ 112 bytes (in
range), videoId-bound 94–95 bytes (below it). The delta is arithmetically explained by the binding
string length (11-char videoId vs 28-char visitorData), so this is suggestive rather than damning —
but it is one more thing pointing at the videoId-bound streaming token being unusual.

### Proposed change, in order

1. **Attest once per page, mint many.** Hold `webPoSignalOutput`, the integrity token and the
   resulting `mintCallback` in page scope; expose a mint-only entry point. Re-attest only when
   `mintRefreshThreshold` / `estimatedTtlSecs` says to, or on an explicit failure.
2. **Capture and call `shutdownFunction`** on the old VM whenever we do re-attest.
3. **Parse all four `GenerateIT` fields** and drive refresh off `mintRefreshThreshold`.

(1) is the substantive one; (2) falls out of it; (3) is what makes (1) correct over a long session.

### Implemented: attest once, mint many (2026-08-04)

`sabr_potoken.js` is restructured. It is still re-injected per mint, but the attestation now lives on
`window.__SABR_POT_STATE` and survives injections:

- **Reuse path** — if a stored attestation exists, was built under the same visitorData, and is
  within `mintRefreshThreshold` (falling back to 90% of `estimatedTtlSecs`, then to a 600 s default),
  the script calls the retained `mintCallback` and returns. No `att/get`, no interpreter eval, no
  snapshot, no `GenerateIT`, no new VM.
- **Attest path** — otherwise it shuts the previous VM down *first*, then runs the full flow and
  stores `{vmFunctions, webPoSignalOutput, mintCallback, integrityToken, estimatedTtlSecs,
  mintRefreshThreshold, websafeFallbackToken, visitorData, attestedAtMs, mintCount}`.
- `loadBotGuard` now captures all four VM functions (was: only `asyncSnapshotFunction`), so
  `shutdownFunction` can actually be called.
- `GenerateIT` parses all four response fields; a response with no integrity token now reports
  distinctly (and says whether a `websafeFallbackToken` was offered) instead of failing generically.
- A mint that fails against a stored attestation drops it and retries once with a full attestation,
  rather than failing the caller. Bounded — the retry cannot re-enter the reuse path.

Kotlin: `generateGvsPoToken(..., forceReattest: Boolean = false)`. `AppSabrPoTokenProvider` passes it
only for the *second and later* forced mint of a provider — the first forced mint is
`SabrSessionFactory` seeding a new session (reuse is correct and is the point), whereas a later one
means the streaming server refused a token this attestation produced, so minting again from the same
VM would be pointless.

**Verified behaviourally — 15/15 in a node harness** that stubs the page (fetch, BotGuard VM,
PoTokenBridge) and re-evaluates the real script file:

```
1st mint attests fully / reports reusedAttestation=false
2nd mint reuses, no network, same VM
10 mints => 1 attestation          (attGet=1 vms=1 mints=10)
visitorData change re-attests, old VM shut down, new mint uses the new VM
explicit force re-attests, and does not stick for later mints
mintRefreshThreshold expiry re-attests
mint failure on a reused attestation re-attests and still returns a token
VMs created - shutdowns <= 1       (created=7 shutdown=6 — no leak)
```

One test initially failed ("still returns a token"). It was the harness, not the code: the stub
failed mints for a 10 ms window and the retry landed inside it, so both attempts failed and the
script correctly reported an error. Made the stub fail exactly one mint and it passes — the bounded
single retry is working as designed.

Expected effect on the log: `[sabr-pot] reusing attestation (age=Ns, mints=N)` for most mints, and
`[sabr-pot] attesting: …` only on cold start, identity change, expiry, or a genuine refusal. Run 17's
11 attestations in 108 s should become 1.

The binding disagreement between BgUtils and yt-dlp is recorded in full on
`AppSabrPoTokenProvider.PROBE_ALTERNATE_BINDING`, together with the observation that the 2026-08-02
probe only ever alternated the GVS side while `/player` kept its own binding — so the *swap* (videoId
to `/player`, visitorData to GVS) that BgUtils' reading implies has never actually been tested.

---

## Run 18 (2026-08-04 23:25–23:32) — attest-once confirmed; opening status-2 survives it

Same video (`a965A86aAJ8`, Homer's Odyssey), 480p, ~4 min of playback plus two forward seeks
(≈12:45 and ≈41:42). User report: smooth, one tiny hiccup at the first seek, stream never broke.

### 1. The attest-once/mint-many change works exactly as designed

Two attestations in the whole 7-minute session, versus **11 in 108 s** in run 17:

| time | event |
|---|---|
| 23:25:46.858 | `attesting: no stored attestation` → attestation **A** (ttl=43200s, refreshThreshold=100s, fallback=no) |
| 23:25:47.101 | mint #0 from A (warmup session pot, 112 B) |
| 23:28:18.976 | `attesting: stored attestation expired` → `previous BotGuard VM shut down` → attestation **B** |
| 23:28:19.436 | mint #0 from B — GVS pot, videoId-bound, 94 B |
| 23:28:21.205 | `reusing attestation (age=2s, mints=1)` — /player pot, visitorData-bound |
| 23:28:21.491 | `reusing attestation (age=2s, mints=2)` — GVS pot |
| 23:28:23.003 | `reusing attestation (age=4s, mints=3)` — /player pot |
| 23:28:23.299 | `reusing attestation (age=4s, mints=4)` — GVS pot |

`previous BotGuard VM shut down` fired on the one re-attestation, so the VM leak is gone (run 17 ended
with 11 live VMs). Expiry fired on `mintRefreshThreshold=100 s` after a 152 s gap — correct, and worth
knowing: **any playback started more than 100 s after the last mint re-attests.** That is one `att/get`
+ interpreter eval + snapshot + `GenerateIT`, ~580 ms here.

The player cache also held: three session builds, **zero `base.js` fetches** after the first
(`player cache: stored ea6f527e (136517b)` at 23:25:54, then `sabr probe (cached)` on both rebuilds).
Each rebuild cost ~1.7 s end-to-end, versus ~9 s before the cache.

### 2. But it did not fix the opening status — and the evidence now rules out the token side

Three SABR sessions were opened back to back:

| session | GVS pot | /player pot | UMP part order | status |
|---|---|---|---|---|
| 1 (23:28:21.126) | B, mint #0 | A, minted 2.5 min earlier | `[47:12, 58:2, …]` | **2** |
| 2 (23:28:22.939) | B, mint #2 | B, mint #1 | `[47:12, 58:2, …]` | **2** |
| 3 (23:28:24.568) | B, mint #4 | B, mint #3 | `[58:2, 47:12, …]` | **1** ✔ |

**Sessions 2 and 3 are identical in every observable token property** — same attestation, same VM,
same binding, same mint path, mints two apart, both requests issued ~0.1 s after their mint. One got
status 2 and the other status 1. There is nothing left on the token side to vary.

This is the strongest disconfirmation so far, and it cuts against the weak-attestation theory that
motivated this very change. That theory (VM snapshotted 30 ms after creation, having observed nothing
of the page; 11 VMs alive at once) predicted that a stable, reused, properly-shut-down attestation
would produce a consistent opening status. It did not. The restructure was still correct on its own
terms — fewer VMs, no leak, no `att/get` storms, ~1.7 s rebuilds — but **it is not the fix.**

Falsified this run, on top of the earlier list (warmup→play gap, User-Agent, page-settle timing,
token age, mint ordinality, visitorData `MATCH`, fresh-vs-cached SABR client):

- **attestation freshness** — the winning session used a 4 s-old reused attestation; the losing one
  used a 0 s-old fresh attestation.
- **attestation reuse itself** — sessions 2 and 3 both reused, and split.

### 3. What held up

- **The UMP part-ordering oracle: 34/34 responses, still perfect.** `58` first ⟺ status 1;
  `47` first ⟺ status 2/3. (Note `58:2` in the `parts=` list is *type 58, length 2 bytes* — not
  status 2. The status is in `wireFields 58={1=…}`.)
- **The grace bail-out fires in ~4 ms** (n=0 at 23:28:21.126, bail logged 23:28:21.130) and again at
  23:28:22.941. `MAX_GRACE_REBUILDS = 3` was exactly enough — raising it from 1 was the right call.
- **Once status 1, it stays.** Responses n=1…n=33 across ~4 minutes: `protection=1` on every one.
- **Seeks do not reset protection.** Two seeks, both mid-session; the codec flushed
  (`Discard frames from previous generation`, `Ignoring stale input buffer done callback`) and the
  server resumed at seq 149/77 and 490/251 respectively, still `protection=1`. The "tiny hiccup" the
  user saw is the refill burst: 7 responses in 1.2 s after seek 1, 10 in 2.4 s after seek 2, plus an
  `AudioTrack … device stall time corrected` on each. Expected, not a fault.

### 4. Working theory now: the opening status is probabilistic, and retrying is the mitigation

Three trials this run: 2, 2, 1. Run 17 needed multiple rebuilds over ~86 s. If p(status 1) ≈ 1/3 per
fresh session, `MAX_GRACE_REBUILDS = 3` succeeds ~70% of the time — which matches the field
behaviour, including the runs that took a while to settle. Two consequences worth acting on:

1. **Raise the retry ceiling.** At ~1.7 s per rebuild it is cheap; 5 attempts gives ~87%.
2. **Move the retry off the player.** The bail happens at n=0 in 4 ms, long before any media is
   needed, but we currently tear down and rebuild the whole `ExoPlayer` each time — three
   `Init`/`Release` cycles at 23:28:19/21/23. Probing the SABR session *before* building the player
   would make the retries invisible and cut ~2 s off cold start.

### 5. Minor observations

- `PlayCore StandardIntegrity : warmUpIntegrityToken` → `Failed to bind to the service` (23:25:43.97),
  and `Uncaught OperationError: Internal Error. Retry with an exponential backoff.` from
  `base.js:378` (a WebCrypto failure) — both emulator artifacts. They occurred identically before all
  three sessions, so neither is the variable, but on real hardware Play Integrity *will* bind, and
  that is one more reason a hardware run is worth doing.
- `identity: … MATCH=false` on every single injection, as always: the WebView page's visitorData
  (`visitorData-O`, 558 chars) is never our Innertube session's (`visitorData-P`). We attest as
  the page's visitor and mint bound to our session's identity. This cannot explain a 2-vs-1 split
  between identical sessions — it is constant — but it remains a plausible reason we need retries at
  all, and it is exactly what the binding swap below would test.

---

## The binding swap (2026-08-05) — and the mislabelled logs that nearly hid it

### 1. The log trap, fixed first

Every PO-token log line in runs 15–18 claimed the wrong thing, in two independent ways:

- `WebPoTokenProvider` logged **`Minted GVS PO token for …`** for *both* tokens, because both go
  through the same in-page att/get flow. The name described the *mechanism*, not the endpoint.
- `sabr_potoken.js` decided its label with `identifier === videoId ? 'videoId' : 'visitorData'`,
  but the QuickJS runtime passes the **visitorData** as the `videoId` argument when minting the
  `/player` pot. So that comparison was true for both, and every token in the log — including every
  session pot — printed as **"bound to videoId"**.

Run 18's analysis is right only because it was keyed off the actual identifier *values*, not those
labels. That is not a margin worth relying on again.

Fixed by making the mint path binding-agnostic and honest:

- `generateGvsPoToken(videoId, …)` → **`generateAttestedPoToken(identifier, purpose, …)`**. The
  function no longer has a `videoId` concept at all; it mints whatever it is handed.
- `__SABR_POC_VIDEO_ID` is gone. `__SABR_POC_IDENTIFIER` is required; `__SABR_POC_PURPOSE`
  (`player` | `gvs`) is carried through for logging.
- New `bindingKind(identifier, ctx)` in the script *derives* the label by comparing the identifier
  against the identity being attested under, rather than against a variable the caller controls.
- The token cache is now keyed `"<purpose>:<identifier>"` instead of bare identifier, so a
  `/player` pot can never be served for a GVS request when the two share a binding value. Storage
  key bumped `yt_po_cache_v3` → `v4` so old identifier-keyed entries are dropped rather than
  lingering unreachable for their 5 h TTL.

New log shape: `Minted gvs pot bound to visitorData/session = visitorData-P… (len=148)`.

### 2. The swap

`PoTokenBinding.SWAP` is the single source of truth and is now **`true`**:

| | `/player` (`serviceIntegrityDimensions.poToken`) | GVS (SABR/UMP body) |
|---|---|---|
| `SWAP = false` (runs ≤18) | visitorData — session | videoId — content |
| `SWAP = true` (now) | **videoId — content** | **visitorData — session** |

Both halves move together. The Kotlin side reads the constant directly; the JS runtime is told at
startup via `__yt.configurePoTokenBinding(...)`, pushed from `QuickJsRuntime.initialize()` next to
`configureRegion`. The old `AppSabrPoTokenProvider.PROBE_ALTERNATE_BINDING` flag and its alternation
counter are **deleted** — keeping a second, half-swap knob around is what wasted the 2026-08-02
probe, and two flags for one decision is the bug this change exists to avoid.

Consequence worth knowing: with the swap on the `/player` pot is content-bound, so the authed
Innertube session can no longer be shared across videos. `createAuthedSession(forceRefresh, videoId)`
now rebuilds when the binding id changes. With the player cache that is one `Innertube.create` and
no `base.js` fetch (~100 ms in run 18's numbers), and it does **not** invalidate the token cache —
revisiting a video reuses its pot.

### 3. Verification

- `node --check` on all three changed scripts; full esbuild bundle rebuilt (1590 KB).
- **21/21** on a harness loading the real `poTokenManager.js`: both binding assignments, the
  endpoints never colliding on one identity under either setting, videoId-absent fallback, purpose
  reaching the native bridge, purpose-keyed cache isolation, `forceRefresh` scoping, v4 persistence.
- **11/11** on a harness running the real `index.js`, bundled against a stub `youtubei.js`, so
  `createAuthedSession` itself is under test: swap off reuses one session across three videos and
  mints exactly one `/player` pot (no regression); swap on content-binds, rebuilds on video change,
  does *not* rebuild for the same video twice, reuses the cached pot on revisit, and falls back to
  the visitorData binding for `warmup()` where there is no videoId.
- Kotlin: 0 syntax errors across all four changed/new files. `PoTokenBinding.kt` compiles clean
  standalone. `AppSabrPoTokenProvider.kt` type-checks **cleanly** against the real
  `generateAttestedPoToken` signature — lifted verbatim out of the source rather than hand-copied
  into the stub, so a drifted signature could not pass. `QuickJsRuntime`'s new positional call
  checked the same way.

Two harness failures along the way, both mine, both worth recording because they are the same
species as the player-cache bug in run 17's write-up:

1. The session harness reported 6 failures against correct code. esbuild had **inlined** the stub
   module into the bundle, so the `created` array the test imported directly was a different
   instance from the one the bundle wrote to. The runtime log in the same output showed the code
   rebuilding sessions exactly as intended. Fixed by recording onto `globalThis`.
2. Two further "failures" were assertions hardcoding a realistic-looking visitorData prefix
   (`TOKEN(player:Cg…`) while the stub returns `VIS_DEFAULT`. The assertion was wrong, not the code.

### 4. What run 19 should show

- `[__yt] configurePoTokenBinding → swap=true (/player←videoId, gvs←visitorData)` at startup.
- `Minted player pot bound to videoId/content = a965A86aAJ8` and
  `Minted gvs pot bound to visitorData/session = visitorData-P…` — note the labels now differ.
- One extra `[__yt] /player pot binding changed … — rebuilding authed session` per video opened.
- The question this answers: whether the first SABR response's `StreamProtectionStatus` is 1 instead
  of 2. If it is still 2, the assignment is not the variable either, and the remaining suspects are
  the `MATCH=false` identity split (we attest as the page's visitor, mint under our session's) and
  the possibility that the opening status is genuinely probabilistic — in which case the fix is the
  pre-flight probe, not a token change.

---

## Run 19 (2026-08-05) — the swap is worse; reverted. Two real bugs found instead.

### 1. Result: SWAP = true is worse, and it is now back to false

Same video, same emulator, one run each way:

| | `/player` ← | GVS ← | outcome |
|---|---|---|---|
| Run 18 (`SWAP=false`) | visitorData | videoId | protection 2, 2, **1** — 4 min clean playback, two seeks |
| Run 19 (`SWAP=true`) | videoId | visitorData | protection 2 for ~1.13 MB, then **3 forever**. No status-1 session ever. Dead. |

The wiring worked exactly as built — `Minted player pot bound to videoId/content = a965A86aAJ8
(len=126)`, `Minted gvs pot bound to visitorData/session = visitorData-Q… (len=148)`, and
`/player pot binding changed (visitorData-Q… → a965A86aAJ8…) — rebuilding authed session`. So this
is a result about the hypothesis, not the implementation.

**BgUtils' reading is not supported by experiment for current YouTube.** yt-dlp's — "most PO tokens,
including web GVS, are bound to the video ID" — matches observed behaviour. The disagreement is
settled for our purposes, and the note on `PoTokenBinding` says not to re-run without a new reason.

One caveat, stated because it stops this being a clean refutation: run 19 also saw the server open
**both** its sessions with a `SabrContextUpdate` round (parts 57/67, no media, no protection field)
that no earlier run produced. That is either a server-side change or a consequence of the swap, and
one run cannot separate them. What is not in doubt is that the configuration didn't help.

### 2. The byte quota is now nailed down to the byte

Audio (itag 140, CBR) delivered under status 2 before the flip to 3, across three runs on two videos:

```
run 15: 1,131,615 B    run 17: 1,131,429 B    run 19: 1,131,429 B
```

Run 19 matches run 17 exactly. The "~60 s of content" framing was always an artifact of itag 140
being constant-bitrate; the real quota is ~1.08 MiB of media and it is enforced precisely.

### 3. BUG (fixed): the grace bail-out silently disarmed itself

`YoutubeSabrSession` latched `firstResponseProtectionStatus` on `requestNumber == 0`,
unconditionally. Run 19's response n=0 was the context-update round with **no field 58**, so
`getStreamProtectionStatus()` returned −1 and that −1 got latched. n=1 then came back status 2 — but
`SabrStreamPump`'s `getFirstResponseProtectionStatus() == PROTECTION_GRACE` was comparing against
−1, so **the bail-out never armed**. No `SABR opened in grace` line appears anywhere in run 19.

The consequence is the whole failure shape: instead of bailing in 4 ms and rebuilding (which is what
rescued runs 17 and 18), the session burned its grace burst, then ten status-3 retries over 25 s,
then a reload, then died — 63 s of nothing.

Fixed by latching the first response that actually **carries** a status rather than literally
response 0:

```java
final int protectionStatus = result.getDecodedResponse().getStreamProtectionStatus();
lastResponseProtectionStatus = protectionStatus;
if (protectionStatus > 0 && firstResponseProtectionStatus <= 0) {
    firstResponseProtectionStatus = protectionStatus;
}
```

This bug was present for every run since the bail-out was added; it simply never fired before,
because until run 19 every n=0 happened to carry a protection status. Worth remembering as a
reminder that "first response" and "response number zero" are not the same claim.

### 4. Suspicious (not yet fixed): the reload path and the enforcement id

After `SabrReloadProvider` ran its `forceFresh` re-probe at 00:14:49, the very next response was:

```
n=16  parts=[44:43]  controls={44=[type=sabr.media_serving_enforcement_id_error, code=2]}
      → SABR pump fatal
```

The context machinery is implemented (`SabrContextUpdate` is decoded, stored in
`YoutubeSabrStreamState.sabrContexts`, and echoed by `YoutubeSabrRequestBuilder`), so this is not a
case of ignoring part 57 outright. The likelier reading: the reload swaps in a fresh
`serverAbrStreamingUrl` + ustreamerConfig + **a newly minted cpn**, while the stream state keeps the
`SabrContextUpdate` the server issued against the PREVIOUS identity. The server then sees a context
whose enforcement id doesn't match the request and errors. If so, the reload path must either carry
the cpn forward or drop the stored contexts — currently it does neither. Not changed yet; it needs
its own run to confirm, and run 19 can't isolate it from the swap.

### 5. What did work

The logging fix from earlier today did its job immediately and is what made this analysis
straightforward: `player` and `gvs` pots are now distinguishable at a glance, with their real
bindings and lengths (126 chars → 94 B videoId-bound; 148 → 111 B visitorData-bound, the difference
being purely the binding string length, as predicted).

### 6. Where this leaves the investigation

Falsified so far: warmup→play gap, User-Agent, page-settle timing, token age, mint ordinality,
visitorData `MATCH`, fresh-vs-cached SABR client, attestation freshness, attestation reuse, and now
the binding assignment. That is essentially every property of the token itself.

Remaining live hypotheses, in order:

1. **The opening status is probabilistic** and retrying is the only lever. Supported by run 18's
   2/2/1 on token-identical sessions. If so the work is: raise `MAX_GRACE_REBUILDS`, and move the
   retry off the player into a pre-flight probe so it costs no visible startup time.
2. **The `MATCH=false` identity split** — we attest as the WebView page's visitor
   (`visitorData-R`, 558 chars) and mint under our Innertube session's (`visitorData-Q`, 28).
   Untested, and the only structural mismatch left standing.
3. **The reload/enforcement-id bug above**, which is at minimum making failures worse than they
   need to be.

---

## 2026-08-05 (b) — SABR pre-flight, and the reload's stale context

### 1. Pre-flight: decide the session's fate before the player exists

New `com.example.ytclient.sabr.SabrPreflight`. `fetchSabr` now builds a session, asks it its opening
protection status, and only publishes a holder once the answer isn't "grace" — retrying on a fresh
session/token up to `MAX_PREFLIGHT_ATTEMPTS = 4`.

Why this is nearly free: `pumpOnce` caches its segments **inside the session** (`SabrStreamPump`'s
loop discards the returned list and only checks whether it was empty, because the data sources read
from that cache). So a probe on a good session fetches exactly the bytes playback was about to ask
for. A probe on a bad session costs one request the old code made anyway. What it removes is the
expensive part: run 18 spent three `ExoPlayerImpl` Init/Release cycles and ~3.4 s of visible restart
discovering what this now settles before the first frame.

`MAX_GRACE_REBUILDS` stays. It is not redundant — it covers sessions that pass the probe and go bad
later, which the probe by construction cannot see.

Two details that are deliberate rather than incidental:

- **The probe issues up to 3 requests, not 1.** Run 19's n=0 was a `SabrContextUpdate` round with no
  protection field; the status only arrived with n=1. A one-shot probe would have called that
  session UNKNOWN and published it, reproducing the exact failure.
- **`startPositionMs` needs no special handling.** Every consumer (`PlaybackController` twice,
  `PlayerScreen`) sets it to 0 for a cold holder and lets ExoPlayer warm-seek, and `isWarm()` is
  `pump != null` — which the probe never creates, since it calls `session.pumpOnce` directly. So the
  probe's requests start exactly where playback's would.

### 2. Reload: drop SABR contexts when the identity changes

`maybeReload` swapped `info` (carrying a NEW cpn) and the streaming URL but left
`YoutubeSabrStreamState.sabrContexts` populated with the `SabrContextUpdate` the server had issued —
and signed, `contentIdLength=23, signatureBytes=37` — against the PREVIOUS identity. Run 19's first
post-reload response was `sabr.media_serving_enforcement_id_error code=2`, which killed the pump.

Added `YoutubeSabrStreamState.clearSabrContexts()` and call it from `maybeReload`. This diverges
from upstream, which has the identical bug and simply has not hit it: before run 19 no session in
this project had ever been sent a context update at all.

Still inference, not proof — it needs a run where a reload actually happens.

### 3. Verification — including a correction to the last three write-ups

**My earlier `javac` checks in this session were vacuous.** The sandbox has a JRE, not a JDK;
`javac` was not on PATH, so every `javac … | grep -c "error:"` was counting an empty stream and
printing a reassuring `0`. That covered the run-19 write-up's "0 syntax errors" claims and the
"clearSabrContexts resolves" check. Both are now redone for real, having installed a JDK
(`openjdk-11-jdk-headless` overlaid onto the system JRE, which supplies the `libjli.so` the
extracted deb lacks).

It mattered immediately: **the first real compile found a hard error in `SabrPreflight`** —
`catch (InterruptedException)` around a `pumpOnce` that declares only `IOException` and
`ExtractionException` is an unreachable-catch, which javac rejects. That would have failed the
user's build. Replaced with a single `catch (Throwable)` that tests for interruption and restores
the flag.

What now actually passes:

- **The entire `org.schabi` extractor tree compiles clean** under real javac (with jsr305, nanojson,
  brotli and okhttp stubbed at the surface it uses) — so `clearSabrContexts()` and the
  protection-status latch both resolve and type-check.
- **22/22 behavioural tests** on the real `SabrPreflight` against a scripted session double: status
  on n=0, the run-19 "status only on n=1" shape (both grace and ok), silent server bounded at 3
  requests, throwing request → UNKNOWN rather than propagating, checked-exception variant, interrupt
  flag preserved across the probe, already-latched status costing zero requests, the 5 s budget
  cutting a slow probe off at 2 requests, and `describe()` not lying about statuses it doesn't know.
- Kotlin: `PlayerViewModel` parses clean and braces/parens balance. The pre-flight **loop** inside
  `fetchSabr` is not covered by an executable test — it needs Android + coroutines — so it rests on
  review. The two things I checked by hand rather than by machine: the grace callback captures a
  per-iteration `val` (not the loop variable, which would make every callback refer to the last
  holder), and the prefetched DTO is consumed-and-cleared only on attempt 0, preserving the original
  semantics where a `forceFresh` call still clears it.

### 4. What run 20 should show

- `SABR preflight for <id>: status=1 (ok) after 1 attempt(s) — publishing` on a lucky first draw, or
  one or more `opened in grace — discarding before the player sees it` lines first.
- **Exactly one** `ExoPlayerImpl Init` per video, regardless of how many sessions were discarded.
  That is the headline: retries should now be invisible.
- If a reload happens, no `media_serving_enforcement_id_error` after it.

---

## Run 20 (2026-08-05) — the pre-flight was starved by its own budget

Video would not load at all: 44 s of spinner, six SABR sessions built, every one status 2, no
playback. Two separate problems, one of them mine.

### 1. BUG (mine, fixed): the probe budget was smaller than one server-mandated backoff

The server now opens with a `SabrContextUpdate` round (parts 57/67, no media, no protection field)
carrying its own **`backoffMs` of 4000-4800 ms**, which `pumpOnce` honours internally by sleeping.
`SabrPreflight.BUDGET_MS` was 5 000 — smaller than one mandated wait. So the probe could not
reliably reach the second request, which is where the status lives.

The arithmetic from the log, first attempt:

```
00:39:57.6    probe starts            (deadline 00:40:02.6)
00:39:57.838  n=0 returns             — context update, backoffMs=4800, no status
00:40:02.643  probe gives up          — "status=unknown after 1 attempt(s) — publishing"
00:40:03.226  n=1 arrives             — status=2, on the PLAYER'S pump, 0.58 s too late
00:40:03.324  reactive bail-out fires — full ExoPlayer teardown, exactly what the probe existed to avoid
```

The second `pumpOnce` was issued, spent its 4800 ms inside the backoff sleep, and returned at
02.643 — the deadline to the millisecond — so the loop broke before a third. The probe wasn't
useless; it was starved. Budget raised to 12 s (longest observed backoff + request + margin, still
well under the ~25 s the server's own retry budget burns).

Note this only became visible now: before run 19 no session had ever been sent a context update, so
n=0 always carried the status and one request was always enough.

### 2. Not mine: every draw was grace

Across six sessions on one video — four seen by the probe, two by the reactive path — **every single
one opened at status 2**. The "roughly 3 draws in 10 are good" model behind `MAX_PREFLIGHT_ATTEMPTS
= 4` and `MAX_GRACE_REBUILDS = 3` did not hold at all that day. Whatever decides this is not
independent per session, at least not always.

So more draws are no longer the answer, and four of them cost 44 s of spinner for nothing.
`MAX_PREFLIGHT_ATTEMPTS` cut to 2, and a hard `PREFLIGHT_TOTAL_BUDGET_MS = 20_000` now caps the
whole loop regardless of attempt count. When the draws are all bad the viewer gets a grace session —
~1.08 MiB of real media, with the reactive rebuild still working underneath — instead of an
indefinite spinner. That is the same conclusion `MAX_GRACE_REBUILDS`'s own doc reached in run 18
("the burst is better than nothing"); the pre-flight had quietly stopped honouring it.

### 3. Confirmed by this run

- `pumpOnce` is the right call for the probe: `SabrStreamPump`'s production loop uses the identical
  `session.pumpOnce(localization)` and discards the returned list, so the probe is not a special
  path and is not losing media.
- The context-update opening round is **not** a run-19 artifact of the binding swap. Run 20 has the
  swap off and every session still opened with parts 57/67. It is a server-side change. The run-19
  write-up flagged this as unseparable from the swap; it is now separated, and the swap is not
  implicated in it.
- The `firstResponseProtectionStatus` latch fix is doing its job — every "opened in grace" line in
  this run came from a status that arrived on n=1, which the old `requestNumber == 0` check would
  have missed entirely.

### 4. Still open

- `request_failed n=2 type=SabrRecoverableException message=Could not read SABR media payload`
  appeared once (00:40:03.332) on a session the probe had already advanced to n=1. Not yet
  explained; worth watching for whether it only happens on handover from probe to pump.
- The reload/enforcement-id fix is still untested — no reload fired in this run.
- Why every session is grace today, when run 18 got a good one on the third try, is unexplained and
  is now the main question.

---

## 2026-08-05 (c) — what the three tokens actually are, and the MATCH=false measurement was junk

### 1. The three tokens — and which lane each binds to

There are **three** tokens in **two lanes**:

| token | lane | construction | bound to (OUR MODEL, see below) |
|---|---|---|---|
| **Cold start** | GVS / streaming | XOR placeholder, no BotGuard | **Video ID** |
| **Session bound** | GVS / streaming | BotGuard-minted | **Video ID** |
| **Content bound** | Innertube `/player` | BotGuard-minted | **visitorData** |

The cold-start token is **not** the `/player` pot, and cannot be reused as it.
`SabrColdStartPoToken.generate` builds it locally: random 2-byte key, an 8-byte header carrying
clientState + a unix timestamp, the identifier appended, then XOR'd against the key. No attestation
is involved. The `/player` pot is a genuine BotGuard mint. Different objects, different bindings.

⚠️ **BgUtils' README says the opposite for the two GVS rows** — it describes the streaming lane as
session-bound (Visitor ID) and reserves videoId for `/player`. Treat that as describing a
SUPERSEDED model. The evidence for the table above:

- yt-dlp's PO Token Guide: "Most PO Tokens (such as for `web` GVS/Player) are bound to the video ID."
- yt-dlp emits `Detected experiment to bind GVS PO Token to video id` — i.e. YouTube MOVED GVS to
  videoId binding, which is exactly how BgUtils ends up describing an older arrangement.
- Runs 18 and 20 (gvs←videoId) are the only ones that have ever produced protection status 1.
  Run 19 (gvs←visitorData) never reached status 1 and went to a hard status 3.

Caveat kept deliberately: run 19 flipped BOTH sides at once, so strictly it proves the *pair* is
worse, not which half. But gvs←videoId is the only configuration that has ever worked, and two
independent yt-dlp signals agree, so the burden of proof now sits on the other side.

Status 2 being a handoff window is the one part of BgUtils' account that still stands, and it is
independent of binding: playback starts on the placeholder, and the 1-2 MB allowance is the budget
for the real mint to land. Our measured 1,131,429 B sits inside it.

### 2. NOTED FOR LATER: cold-start-first playback

Idea (maintainer, 2026-08-05): mint a **visitorData-bound cold-start token once per session**, use it to
open any video instantly with the grace allowance, and swap in the real session-bound token in the
background before the ~1.08 MiB runs out. That is precisely the flow BgUtils describes the web
player using, and it would make cold start independent of mint latency.

CORRECTION (2026-08-05, maintainer): an earlier draft of this note called our cold-start binding a bug
against BgUtils. That was wrong — it was written on BgUtils' superseded model. Under the table
above, `YoutubeSabrProbe.coldStartPoToken(videoId, 0)` is already bound CORRECTLY, and the whole
GVS lane (placeholder then real) stays on the videoId. Nothing to invert.

The one real blocker: the cold-start path is effectively dead code today. `SabrSessionFactory`
seeds a real attested pot at construction and `maybeApplyPoToken` then keeps it
(`current != null && !forceRefresh → return false`), so the placeholder is never what goes out.
Implementing this idea means deliberately opening on the cold-start token and upgrading in the
background — which is a behaviour change to the seeding path, not a binding change.

### 3. The MATCH=false measurement was an artifact — fixed the measurement, not the identity

`pageLen=558` never made sense: a real logged-out visitorData is ~85-200 chars (run 20's `/player`
responses returned ~85). The old check took the **first** `"VISITOR_DATA":"` in the raw page HTML
and read to the next quote, which lands inside whatever blob mentions the key first.

Acting on that number — by "fixing" MATCH, or by adopting the page value as our session identity —
would have meant acting on a measurement artifact. So this round only makes the measurement
trustworthy:

- `looksLikeVisitorData(v)` — a visitorData is a base64 (often percent-encoded, often URL-safe)
  protobuf whose field 1 is the 11-char visitor id, so the first two bytes are always `0x0A 0x0B`.
  Handles `%3D` padding, `=` escapes and the URL-safe alphabet.
- `visitorCandidates()` — enumerates `ytcfg.get`, `ytcfg.data_`, `yt.config_` and up to four HTML
  occurrences, validating each, instead of first-match-wins.
- The log now prints `identity sources: <src>=<len>ch/ok|bad …` for every candidate, then picks the
  first VALID one as the page identity.

**10/10** on a harness that pulls the real validator out of the asset and feeds it values taken
verbatim from run 20/19 logs: our 28-char session value, the ~85-char server-returned value with
`%3D%3D` padding, the long iOS-flavoured one, and the URL-safe form all pass; empty, null,
non-base64, valid-base64-wrong-shape, a 560-char junk blob (the shape the old scrape was finding),
and a right-prefix-wrong-id-length value are all rejected.

What run 21 answers: whether the page has a real identity at all, and if so whether it genuinely
differs from the session's. Only then is there a MATCH problem worth fixing — and only then is it
worth deciding which of the two identities should win.

---

## Run 21 (2026-08-05 22:09) — the pre-flight works. One prediction of mine was wrong.

~5 minutes of clean playback, stopped by the user. Every response n=1…n=21 `protection=1`.

### 1. The pre-flight did exactly what it was built to do

```
22:09:59.505  session 1 seeded, gvs pot (95 B, videoId-bound)
22:10:01.265  n=0 → status 2          parts=[47:12, 58:2, …]
22:10:01.273  "opened in grace — discarding before the player sees it (attempt 1/2)"
22:10:01.677  session 2 seeded, same attestation (age 12s), same binding
22:10:02.169  n=0 → status 1          parts=[58:2, 47:12, …]
22:10:02.170  "status=1 (ok) after 2 attempt(s) — publishing"
22:10:02.270  ExoPlayerImpl Init 5a5c72f
```

**Exactly one `ExoPlayerImpl Init` for the whole session** — the headline metric named at the end of
the run-20 write-up. The bad draw was discarded before the player existed, so it cost the viewer
nothing visible. Tap to first frame was ~4.2 s including the discarded session, against run 18's
~6 s across three player builds.

Two further confirmations:

- **The part-ordering oracle held again**, and cleanly separated the two attempts: `47` first ⇒
  status 2, `58` first ⇒ status 1.
- **The two attempts were token-identical.** Same attestation (age 10 s vs 12 s), same videoId
  binding, same 95-byte pot, mints 1 and 3 off one VM. One drew 2, the other drew 1. That is now
  four runs of evidence that the opening status is a server-side draw, not a property of our token.

Also of note: run 21 saw **no `SabrContextUpdate` opening round** at all — n=0 carried media and the
status directly. So the parts-57/67 behaviour from runs 19-20 is intermittent server-side, not a
permanent change. The `BUDGET_MS` raise to 12 s wasn't exercised this run; it stays as insurance.

### 2. CORRECTION: the 558-char page visitorData is REAL, not a bad scrape

```
[sabr-pot] identity sources: html#0=558ch/ok
[sabr-pot] identity: session=visitorData-S page=visitorData-T pageLen=558 via=html#0 MATCH=false
```

The previous section predicted "the 558-char value is almost certainly a bad scrape". **It is not.**
It passes the validator: base64-decodes to `0x0A 0x0B` followed by an 11-char visitor id
(`hyqWmnMelO`). 558 chars simply means the page's value carries a large experiments/config blob that
the ~85-char `/player` responses don't. The old scrape was reading the right thing all along.

Building the validator was still the right move — it converted a guess into a fact, and it is what
lets this correction be stated with confidence rather than swapped for another guess. But the
conclusion I drew before running it was wrong, and the note above has been superseded.

So **MATCH=false is genuine**: the WebView page really does hold a different, real, server-issued
visitor identity (`hyqWmnMelO…`, stable across the whole run) from our Innertube session's
(`_u-RpguxFe…`). That remains the one structural mismatch on the table.

Side finding: only `html#0` produced a candidate. `ytcfg.get`, `ytcfg.data_` and `yt.config_` were
all empty in this WebView, so the HTML scrape is not a fallback here — it is the only source.

### 3. On the cold-start timing theory (maintainer)

Theory: the web player's request 0 carries a *cold-start* token and upgrades to a real one during
the grace window; ours carries a real token immediately, and never having presented a cold-start
token might itself be scored as anomalous.

There is something real underneath it — our request-0 fingerprint genuinely differs from the web
player's, and that is a difference we have never tested. But the run-21 data argues it is not
decisive: both attempts presented a real token on request 0, and one still drew status 1. If
"no cold start ever seen" were a hard signal, both would have been refused.

At most it could be one soft input among several. It stays on the list, below the identity
mismatch, and it is cheap to test whenever we want: open deliberately on the cold-start token and
compare the status-1 rate across ~10 sessions.

### 4. Still untested

The reload / `clearSabrContexts` fix — no reload fired in this run either.

---

## 2026-08-05 (d) — the identity fix: authed session adopts the page's visitorData

Run 21 established that `MATCH=false` is real, not a measurement artifact. This is the fix for it,
behind `SabrSessionIdentity.USE_PAGE_IDENTITY` (**on**).

### What changes

The authed Innertube session — the one that issues `/player` and seeds SABR — now runs under the
**WebView page's real, server-issued visitorData** instead of the one youtubei.js invents locally
(`generate_session_locally: true` → `encodeVisitorData(generateRandomString(11), now)`, an id
YouTube has never issued). Attestation identity and streaming identity become the same visitor.

### The three things this had to avoid

1. **clientVersion split** — regression (1) of the 2026-08-02 `/sw.js_data` attempt. We take ONLY
   the visitorData; clientVersion stays the bundled value. Asserted by a test.
2. **A long value breaking the header** — regression (2). `normaliseVisitorData` un-percent-encoded a
   real visitorData into raw bytes, making `X-Goog-Visitor-Id` invalid and every `fetch()` throw
   "Failed to fetch". It now refuses the decode when the result would contain non-ASCII, and says so
   in the log.
3. **Delaying the first screen** — the anon session (search, home feed, channels) is deliberately
   NOT changed. Run 21's timing: anon session created 22:09:36.6, home feed resolved 22:09:42.9,
   WebView page loaded 22:09:43.4. Blocking the anon session on page load would have cost ~7 s on the
   first screen. The authed session already waits for the WebView (its mint blocks on it), so the
   wait is free there.

### Shape

- `visitor_data.js` — NEW shared asset holding the validator and candidate enumeration, used by both
  `sabr_potoken.js` (identity diagnostic) and `WebPoTokenProvider.capturePageVisitorData` (the
  capture). Deliberately shared rather than copied: two copies could disagree about "what is the
  page's identity", which is the bug class that made the 558-char reading untrustworthy.
- `WebPoTokenProvider` — captures the page identity on `onPageFinished`, exposes
  `awaitPageVisitorData(timeoutMs)`. Completes with `""` on any failure so a waiter can never hang,
  and is reset when `resetInternal()` reloads the page.
- `QuickJsRuntime` — `__nativePageVisitorData()` bridge; pushes the flag via
  `__yt.configureSessionIdentity(...)` next to the other two config calls.
- `index.js` — `createAuthedSession` consults it, adopts it when valid, logs which identity won.

### Verification

- **9/9** on a new harness running the real `createAuthedSession` against a stub youtubei.js: flag
  off reproduces today exactly (and never even consults the page), flag on adopts a 558-char page
  value, the `/player` pot binds to the identity actually in use, an empty page identity falls back,
  a throwing lookup falls back without propagating, and clientVersion is never taken from the page.
- Regression suites still green: 11/11 session binding, 21/21 token cache, 10/10 validator (the last
  retargeted at the shared asset — it loads the real file and takes the global, no copy of the logic).
- Kotlin: 0 syntax errors across the three changed/new files; `SabrSessionIdentity` compiles clean
  standalone. Bundle rebuilt (1592 KB).

One harness failure along the way, mine again and the same species as before: the fallback tests
asserted a new `Innertube.create` had happened, but with an unchanged binding id the session is
correctly REUSED, so there was nothing to inspect. Fixed with `forceFresh`, and the reason is now a
comment in the test.

### What run 22 answers

- `page identity captured: <16 chars>… (N chars)` shortly after page load.
- `authed session adopting PAGE identity (…) instead of the local one (…)` on the first playback.
- `MATCH=true` in the mint diagnostic — the whole point.
- Then the real question: does the status-1 rate go up? **One run cannot answer that** (see the
  baseline problem: at ~1/3 per draw, any single run is consistent with no change). Watch instead
  for a hard signal — playback failing outright, `Failed to fetch`, or `MATCH` still false — and if
  none appears, this needs the draw-rate measurement to evaluate properly.

---

## Run 22 (2026-08-05 22:32) — the identity fix works, and proves the local visitorData was fake

~3 minutes of clean playback, `protection=1` on every response n=1…n=15, **status 1 on the first
preflight attempt** — no discarded sessions at all.

### 1. THE FINDING: YouTube was silently discarding our locally-generated identity

Compare what `/player` echoes back in `responseContext.visitorData`:

| run | identity we sent | identity the server returned | same visitor? |
|---|---|---|---|
| 21 (local) | `visitorData-S==` (`_u-RpguxFe…`) | `visitorData-U…` (`oycsh4WSDeM`) | **NO** |
| 22 (page) | `visitorData-V…` (`L25VXOQ31_0`) | `visitorData-V…` (`L25VXOQ31_0`) | **YES** |

With the invented visitorData, YouTube **ignored it and substituted its own** on every call. With
the page's real one, it echoes ours back. That is direct confirmation of what `SabrSessionIdentity`
only argued from first principles: the local identity was a value YouTube had never issued and would
not adopt, so `/player` and the stream were running under a server-chosen visitor that had nothing to
do with the one BotGuard attested. The mismatch was real and is now closed.

Everything downstream behaved: `/player` accepted the page identity (200, 34 formats, abrUrl,
ustreamer), the GVS pot stayed videoId-bound at 94 bytes, and the SABR session opened at status 1.

### 2. The raised probe budget earned its keep immediately

n=0 was a `SabrContextUpdate` round with `backoffMs=4000`; n=1 (carrying status 1) arrived 4.5 s
later, with the whole probe taking ~6.1 s. **Under the old `BUDGET_MS = 5000` this probe would have
timed out and published UNKNOWN**, exactly as in run 20. The run-20 fix is what let run 22 see its
own success.

### 3. Two things run 22 exposed, both now fixed

**(a) `MATCH=false` was a false negative.** The final diagnostic reads:

```
identity: session=visitorData-V page=visitorData-V pageLen=558 … MATCH=false
```

Identical prefixes, `MATCH=false`. The session value had been percent-decoded on the way in
(558 → 556 chars, the `==` padding) while the page value was still raw, so a plain string comparison
reported a mismatch that did not exist. Added `__ytNormaliseVisitorData` to the shared asset and the
diagnostic now compares canonical forms. **The identities were already the same; only the report was
wrong.**

**(b) Warmup wasted an entire session.** `PAGE_IDENTITY_TIMEOUT_MS` was 3 s on the assumption that
the authed session is only built for playback. Warmup builds one too, and runs early:

```
22:32:18.847  page identity lookup failed: Timed out waiting for 3000 ms
22:32:24.265  page identity captured: … (558 chars)      ← 5.4 s later
22:32:37.810  authed session adopting PAGE identity … — rebuilding authed session
```

Warmup built a session on the local identity, minted a 150-char pot for it and fetched base.js;
playback discarded all of it 19 s later and forced a re-attestation. Waiting 5.4 s would have
produced one session that playback reused. Timeout raised to 20 s — the cost asymmetry is lopsided
(waiting is unwatched background time; giving up early costs a session, a mint and an attestation),
and it now only fires if the WebView genuinely never comes up.

### 4. Observations, not problems

- The `/player` pot is now **858 chars (~643 bytes)** because it binds to a 558-char identity. Far
  above BgUtils' "110-128 bytes", but that figure is for an ~28-char binding — token size tracks
  binding length, as established back in run 18. `/player` accepted it.
- `visitorData un-percent-encoded (558→556 chars)` — the new header guard correctly ALLOWED this
  one, because the decoded form is still printable ASCII. That is the case it was built to permit;
  it only blocks decodes that would produce non-ASCII bytes.

### 5. What this does and does not establish

It establishes the **mechanism**: our identity was being discarded, and now it is not.

It does **not** establish an improvement in the status-1 rate. This is one run, status 1 on one
draw. At a ~1/3 base rate a single clean draw happens by luck one time in three. The baseline
measurement is still the only thing that can answer that question.

Still untested: the reload / `clearSabrContexts` path (no reload has fired in four runs).

---

## Run 23 (2026-08-05 22:43) — both run-22 fixes hold; the mint had not followed the stream

Both fixes did what they were meant to, and the run then exposed that the identity change had only
ever been applied to **half** of the system.

### 1. The two run-22 fixes are confirmed

**The warmup timeout.** No `page identity lookup failed` anywhere. `page identity captured` at
22:43:36.826, adoption logged 1 ms later at 36.827. One authed session, built on the page identity
from the start — no wasted session, no discard, no forced re-attestation. (Run 22 spent 19 s and a
whole session before reaching this state.)

**The MATCH comparison.** `MATCH=true` appears for the first time in the project's history:

```
22:43:50.396  [sabr-pot] context ok visitorLen=556
22:43:50.405  [sabr-pot] identity: session=visitorData-W page=visitorData-W pageLen=558 MATCH=true
```

556 vs 558 is the percent-decoded `==` padding — precisely the false negative the normalisation was
added to remove.

### 2. Playback, including the first seek under the page identity

`status=1 (ok) after 1 attempt(s)`, one `ExoPlayerImpl Init`, and `protection=1` on **every**
response n=1…n=21. The UMP part-ordering oracle held again: every response opens `[58:2, 47:12, …]`.

The forward seek is new evidence. Between n=11 and n=12 the stream jumps from seq 38 / audio 20
(~189 s) to seq 563 / audio 289 (`startMs=2883000` — 48:03 of a 73:02 video):

- no reload, no `SabrContextUpdate`, no re-attestation, no player rebuild
- `protection=1` continues unbroken across the jump
- segments arrive 0.4 s later

A ~45-minute seek is the largest state change we can hand the session, and it did not perturb the
protection status at all.

**The pre-flight costs nothing in latency.** Worth stating plainly, because the 4.6 s between
`response n=0` (51.053) and `n=1` (55.627) looks like a tax and is not. n=0 was a context-update
round carrying `backoff=4000`; that wait is server-mandated and ExoPlayer would have paid it on its
own first request. The probe merely moves it before player construction, and the bytes are not
discarded — n=1's init segments and 9 media segments stay in the session the player is then built
on, which is why n=2 resumes at seq 5.

### 3. The defect: the mint never adopted the page identity

The run-22 fix changed which identity the **stream** runs under. It did not change which identity
the **mint** attests under. Run 23 shows the /player mint split down the middle:

```
22:43:36.884  [po] minting player pot (att/get) bound to visitorData-W   ← page identity
22:43:41.037  [sabr-pot] context ok visitorLen=28                               ← LOCAL identity
22:43:41.047  [sabr-pot] identity: session=visitorData-X page=visitorData-W MATCH=false
```

The token bound to the page visitor while `att/get` declared the local one in its body and in
`X-Goog-Visitor-Id`. That is exactly the mismatch `SabrSessionIdentity` exists to close, surviving
in the one mint that authorises `/player`.

Root cause, `js-runtime/src/index.js`:

```js
async function ensurePoToken(identifier, forceRefresh = false, purpose = 'player') {
  const it = await createAnonSession();
  const visitorData = client.visitorData;   // ← ALWAYS the anon session's
```

Correct only while the anon and authed sessions shared an identity. `createAuthedSession` passed a
page-derived `bindingId` as the identifier, but the context came from the anon session regardless.

**It also bought a third attestation.** With no identifier (`poTokenInfo`'s cold read) the default
identifier was the anon visitorData, so the cache key was `player:<local>` while warmup had stored
`player:<page>` — a guaranteed miss. And since `sabr_potoken.js` invalidates its stored attestation
whenever `ctx.visitorData` changes, that miss tore down the page-identity BotGuard VM and re-ran
`att/get` for a token nothing plays under:

```
22:43:41.047  attesting: no stored attestation      → ctx=local,  bound to page
22:43:50.405  attesting: visitorData changed        → ctx=page,   bound to videoId   (GVS, Kotlin path)
22:43:50.939  attesting: visitorData changed        → ctx=local,  bound to local     (wasted)
```

Three attestations, ctx thrashing local → page → local. Run 22 had two. This also silently
falsified the comment in `App.kt` claiming `poTokenInfo()` "reads the token warmup() already
minted" — true when written, false the moment the authed session adopted a different identity.

**Fix.** Extracted `resolveSessionVisitorData()` — one answer to "which visitor does playback run
as?" — and pointed both `createAuthedSession` and `ensurePoToken` at it. The mint context is now the
identity the stream will run under, and the null-identifier default lands on the same cache key
warmup wrote, so `poTokenInfo()` is a cache hit again.

### 4. Verification

`outputs/mint_identity_test.mjs` (new, 18 assertions) records **both halves** of every mint — what
it bound to and what it attested under. The previous `identity_test.mjs` could not have caught this:
its mint stub discarded the context argument (`async (id, _vd, …)`), so the only half it ever
checked was the binding.

Confirmed non-vacuous by rebuilding the bundle with the pre-fix `ensurePoToken`: 7 of the 18 fail,
reproducing the run-23 log exactly (`bound=Cgt…(558) ctx=VIS_DEFAULT(11)`, plus the extra mint).
Full suite after the fix: 18 + 9 + 21 + 11 + 10 = **69 passing, 0 failing**.

### 5. Observations, not problems

- `PlayCore StandardIntegrity: Failed to bind to the service` and `Uncaught OperationError: Internal
  Error. Retry with an exponential backoff. @base.js:380` — base.js's own **Play Integrity** path
  failing, because the emulator has no Play Services to bind to. BotGuard succeeded 80 ms later and
  the mint was unaffected. On real hardware this path would presumably succeed, which makes it a
  live candidate for part of the status-2 draw rate, and another argument for the hardware run.
- The adoption line logs three times per playback (36.827, 49.959, 50.345) — once per
  `createAuthedSession` call. Noise, not a defect; the session itself is built once.

### 6. What this does and does not establish

Runs 21, 22 and 23 all drew status 1. Tempting, and not yet evidence: **run 21 predates the identity
fix**, so the streak cannot be attributed to it. Post-fix it is two clean draws, which at a ~1/3 base
rate happens by luck about one time in nine.

The baseline draw-rate measurement remains the only thing that can settle this, and it is now the
clear next step — every structural fix since run 19 has been evaluated against a rate nobody has
measured.

Still untested: the reload / `clearSabrContexts` path (no reload in five runs — the 45-minute seek
did not provoke one either).

---

## Run 24 (2026-08-05 23:01) — the mint fix holds; a third instance of the encoding bug

The run-23 fix landed cleanly and the run then surfaced one more place where the same underlying
mistake lives, plus a playback state we have not seen before.

### 1. The mint fix is confirmed, on all three of its consequences

```
23:01:52.828  [sabr-pot] context ok visitorLen=558              ← page identity, was 28
23:01:52.839  [sabr-pot] identity: session=visitorData-Y page=visitorData-Y MATCH=true
23:01:53.582  attestation stored, minting player pot bound to visitorData/session (visitorData-Y…)
```

Three separate confirmations, and the third is the strongest:

- **`MATCH=true` on the FIRST mint.** In run 23 the first mint reported `visitorLen=28 MATCH=false`.
- **The binding label changed by itself.** Run 23 called this same token `other(558ch)`; it now reads
  `visitorData/session`. `bindingKind` decides that label by comparing the identifier against
  `ctx.visitorData` — so the label flipping is the two halves agreeing, reported by a code path that
  knows nothing about the fix.
- **`poTokenInfo()` is a cache hit.** `warmup: authed session ready` at 23:02:01.891,
  `callAsync(poTokenInfo)` returns 9 ms later at 01.900 with no mint in between. Run 23 spent a full
  attestation here.

Attestations: **2, down from 3.**

### 2. The remaining defect: the attestation cache still compares raw strings

The second attestation should not have happened either:

```
23:01:52.828  context ok visitorLen=558   attesting: no stored attestation
23:02:03.010  context ok visitorLen=556   attesting: visitorData changed      ← spurious
              previous BotGuard VM shut down
```

Both mints report `MATCH=true`. Same identity, two spellings: the first mint gets the page's **raw**
value (558 chars), and after `Innertube.create` the session context holds the **percent-decoded**
form (556 — the `==` padding). `identityChanged` compared with `!==`, so it saw a changed identity,
tore down a healthy BotGuard VM and re-ran `att/get`.

This is the **third** appearance of one bug. Run 22 fixed the raw comparison in the MATCH
diagnostic. Run 23's `other(558ch)` label was the same thing in `bindingKind` and went unremarked.
Run 24 is the same thing again in the cache key. The lesson is not "add another normalise call" —
it is that "same identity" was being decided independently in three places, and only one of them
had been taught what the phrase means.

**Fix.** All three now route through `sameVisitor()`. Attest-once only holds if identity equality
means the same thing everywhere it is asked.

Verification: `visitordata_validator_test.mjs` grew 6 assertions pinning `normaliseForCompare`'s
contract, including the exact 558/556 pair from this run and — importantly — that two genuinely
different identities still compare **unequal**, since a comparison that always said "same" would
also make the re-attestation disappear. Suite: 16 + 18 + 9 + 21 + 11 = **75 passing**.

### 3. Playback, and a new end state

`status=1 (ok) after 1 attempt(s)`, one `ExoPlayerImpl Init`, and `protection=1` on **every**
response n=1…n=37 — the longest clean run so far, ~5.5 minutes plus a seek.

The seek at 23:07:24 jumped from seq 77 (~6:39) to seq 550 (46:56) and delivered normally through
n=27. Then the session entered a state we have not logged before:

| n | content | policy |
|---|---|---|
| 28 | `segments=count=0` | `backoff=2000`, videoItag=135 |
| 29 | audio 286, 287, 288 — **all three already delivered** in n=24/25/27 | `videoItag=-1` |
| 30–37 | `segments=count=0` ×8 | `backoff=2000`, `videoItag=-1` |

Nine responses over ~20 s at a steady 2.4 s cadence, the server having dropped video from the
request policy, ending only because the process ended. Every one is `http=200`, `protection=1` — so
this is **not** an attestation failure, and the pump is obeying a server-mandated backoff rather
than spinning on its own.

Two readings, and the log cannot separate them:

1. **Benign idle.** Readahead is ~63 s (`53=[…field2=62990]`); the seek delivered ~51 s of video and
   ~60 s of audio. A satisfied client polling at the server's cadence would look exactly like this.
2. **A stall.** Against reading 1: in steady state this session did *not* poll — n=13→n=14 has a 25 s
   gap with no requests at all. A 2.4 s poll loop is different behaviour, and the duplicate audio in
   n=29 suggests the post-seek buffered-range bookkeeping asked for segments we already had.

What would settle it is simply whether it recovers. Worth watching for next run: if playback
continues past the buffered window, it was idle; if the picture freezes ~50 s after a seek with
`count=0` responses continuing, it is a stall in the post-seek range tracking. Either way it costs
~25 requests/minute while it lasts.

### 4. Where the status-1 evidence stands

Runs 22, 23 and 24 — every run since the identity fix — drew status 1, and run 24 held it across 37
consecutive responses and a seek. At a ~1/3 base rate three post-fix draws in a row is roughly a
1-in-27 coincidence.

That is suggestive and still not a measurement. It remains true that nobody has measured the base
rate, that the estimate of ~1/3 is itself folklore from earlier runs, and that a run in which the
first draw succeeds tells us nothing about the second. The baseline harness is now the only
experiment left that can convert this streak into a number.

Still untested: the reload / `clearSabrContexts` path — six runs, no reload.

---

## Run 25 (2026-08-05 23:14) — total rejection. The independent-draw model is dead.

Every session this run opened in grace. Not most — **all seventeen**. That is not a regression from
the run-24 fixes (which are confirmed working below); it is the server refusing this client for
three minutes straight, and it falsifies the model every retry constant in the codebase was tuned
against.

### 1. The run-24 fix is confirmed

**One attestation for the whole run**, serving mints 1 through 15:

```
23:14:44.201  attesting: no stored attestation          ctx=558
23:14:54.523  reusing attestation (age=10s, mints=1)    ctx=556   ← re-attested in run 24
23:15:39.965  reusing attestation (age=55s, mints=15)
```

That second line is exactly the case that used to read `attesting: visitorData changed`. `MATCH=true`
on every one of the ~20 mint diagnostics, and every session-bound token labelled
`visitorData/session` rather than `other(558ch)`. All three call sites now agree on what "same
identity" means.

(The later `attesting: forced` entries are the status-3 re-mint path deliberately forcing a fresh
attestation — a different mechanism, working as intended.)

### 2. The grace quota is a constant, to the byte

Two sessions this run ran the burst to exhaustion. Summing itag-140 to the last delivered segment:

```
init 6,023 + seq1…seq7  →  975,765 + 161,687 = 1,137,452 B delivered
                                    − 6,023 init = 1,131,429 B of media
```

**1,131,429 B — identical to runs 17 and 19.** The earlier run-15 figure of 1,131,615 and this run's
1,137,452 differ only in whether the init segment was counted; the media quota itself has not moved
across three runs, two videos and ten days. It is a fixed server-side byte budget, not a timer and
not a heuristic. Video always ends at seq 11, audio at seq 7, ~60 s of content.

### 3. Seventeen consecutive grace draws

Sessions built: 2 per pre-flight × (1 initial + 3 grace rebuilds) + 3 more after each of two player
failures = **17, every one status 2**, spread over 23:14:55–23:17:36. The UMP ordering oracle held
without exception: every response opened `[47:12, 58:2, …]`.

The comment on `MAX_PREFLIGHT_ATTEMPTS` says each draw "is close to an independent draw… roughly 3
draws in 10 are good". Under that model:

| observation | probability if draws are independent at p(ok)=0.3 |
|---|---|
| runs 22, 23, 24 — first draw clean, three runs running | 2.7% |
| run 25 — 17 consecutive failures | 0.7¹⁷ ≈ **0.2%** |

Both tails at once is not a 3-in-10 coin. The outcome is **autocorrelated**: it is a property of the
client/session/IP state at that moment, persisting for minutes, not a per-draw lottery. Run 20 first
dented this ("six consecutive sessions all opened in grace") and was written off as a bad day; run 25
settles it.

Three consequences, and the first two are uncomfortable:

- **Every single-run verdict in this document is weaker than it was written to be** — in both
  directions. A run that drew status 1 four times says little about the change under test, and so
  does a run that drew status 2 seventeen times. The identity fix (runs 22–24) is still supported by
  its *mechanism* evidence — the echoed visitorData, the cache-key hit, the label flip — and that
  evidence stands on its own. Its status-1 evidence does not.
- **The retry constants are tuned against a falsified model.** `MAX_PREFLIGHT_ATTEMPTS=2` and
  `MAX_GRACE_REBUILDS=3` were both justified as "more draws ⇒ better odds". If draws are correlated,
  extra draws buy nothing and cost sessions, mints and spinner time — 17 sessions and ~20 mints in
  three minutes here.
- **The baseline harness has to change shape.** Measuring the per-draw rate inside one app launch
  now measures only whichever state that launch is in. The question is the distribution *across*
  launches and time, which means the harness must sample cold, spaced out, not in a tight loop.

### 4. The defect this exposed: a hand-off that was already declined

`notifyGraceSession` fires on response **n=0**, so all three rebuild attempts are spent within
seconds of a session opening — long before the burst runs out. When the burst then does run out and
the server answers status 3, the pump abandons the session "for a rebuild" that was declined a
minute earlier:

```
23:15:45.320  n=2  → "still opening in grace after 3 rebuilds — letting it run"
23:15:46.705  n=5  → status=3, maxRetries=10
23:15:49.164       → "grace session went protected — abandoning for a rebuild"   ← none coming
                     (pump thread exits; playback continues on buffered media)
23:16:49.715       → SABR pump fatal for itag=135 → ExoPlayer error @61832ms
```

**63 seconds** between the pump giving up and the viewer finding out — the burst playing out over a
dead pump. Then the retry inherits `graceRebuilds` already at 3 (it is only reset on video load), so
attempts two and three got zero rebuilds and repeated the same shape, ending at the 25 s buffering
watchdog. Three ladders, ~2.5 minutes, one error dialog.

Worse, each retry is *less* likely to succeed than the first: playback died at 61.8 s and resumed
`resume=61832ms`, but a fresh session bursts from 0 — so the retry must spend nearly the entire
1.13 MB quota just to reach the point where it already failed.

**Fix.** Make the hand-off explicit. `SabrSessionHolder.declineGraceRebuild()` records that the
view-model has bowed out; the pump checks it before taking the abandon branch, and when no rebuild is
coming fires the new terminal `onGraceExhausted` callback instead. The view-model routes that
straight to `fallBackToLegacyPlayback` — the escape hatch that existed the whole time and that
nothing was reaching, because the only path to it was a playback error 63 s in the future.

Verified with javac against the real `org.schabi` tree: **0 errors in the changed files**; the
visibility contract holds (`declineGraceRebuild`/`setOnGraceExhausted` public for the Kotlin caller,
`isGraceRebuildDeclined`/`notifyGraceExhausted` package-private for the same-package pump).

### 5. What this does not explain

Why this client was refused seventeen times when the previous three runs were accepted immediately.
Nothing in the request changed: same video, same page identity, same binding, same pot lengths, one
healthy attestation. The most likely candidates are all client-state rather than client-*request*:
five app launches in 35 minutes against one emulator, one cookie jar (`cookies len=1149` every run)
and one IP, with ~20 att/get mints in the last three minutes alone. That is testable — space the runs
out, or change one of those axes at a time — and it is now a more promising line than any further
change to what we send.

---

## Run 26 (2026-08-05 23:28) — visitorData is healthy; the run-24 fix is the suspect

Different video (`Q8XsNcI8aGw`, 110 formats / 20 audio tracks), 7 sessions, **all grace**. The maintainer asked
whether the identity path got broken. It did not, and the log says so four different ways.

### 1. visitorData is intact — four independent confirmations

```
23:28:24.491  page identity captured: visitorData-Z… (558 chars)
23:28:24.495  authed session adopting PAGE identity (visitorData-Z…) …
23:28:28.733  identity: session=visitorData-Z page=visitorData-Z MATCH=true
23:28:38.301  /player ← 200 {"responseContext":{"visitorData":"visitorData-Z…
```

The fourth line is the decisive one: it is the run-22 test. The server **echoes our identity back**
rather than substituting its own, which is what a broken/rejected visitorData looked like in run 21.
Every mint is labelled `visitorData/session`, `MATCH=true` on all nine.

And mechanically, nothing in the run-25 change could have touched it: those edits were
`SabrSessionHolder` (new fields), `SabrStreamPump` (one conditional in the status-3 branch) and
`PlayerViewModel` (a decline call + a callback). None of them ran this session — the log has
`rebuilding (attempt 1)` and `(attempt 2)` and then the process ended, so the budget was never
exhausted and neither `declineGraceRebuild` nor `onGraceExhausted` executed. They are inert here.

### 2. But the run-24 fix is a live suspect, by a different mechanism

Reuse used to be bounded **by accident**. Before run 24 `identityChanged` compared `ctx.visitorData`
as a raw string, and the ctx alternates 558 ⇄ 556 chars between the /player mint and the GVS mint —
so nearly every mint tripped "visitorData changed" and re-attested. Making that comparison canonical
was right, and it removed the only thing bounding reuse:

| run | attestations | mints per attestation | outcome |
|---|---|---|---|
| 23 | 3 | ~1 | status 1, first draw |
| 24 | 2 | ~1 | status 1, first draw |
| **25** | **1** | **15** | **17/17 grace** |
| **26** | **1** | **9** | **7/7 grace** |

The ratio and the outcome flipped at the same commit. "One attestation, many tokens" is also exactly
the shape an abuse heuristic would key on — a real web client attests per playback, not once per
session with nine tokens drawn off it.

**This is not proof.** Run 25 drew grace after a *forced fresh* attestation too, which argues the
attestation is not the discriminator. And it remains true that runs 25–26 are the 5th and 6th launches
inside an hour on one cookie jar and one IP. But the mint-ratio change is the only client-side
difference between the last clean run and the first bad one, and I previously attributed the failure
entirely to server-side state without checking what we had just changed. That was too quick.

**Change.** `MAX_MINTS_PER_ATTESTATION`, default **1** — the ratio every clean run had. This restores
the pre-run-24 attestation cadence while keeping the run-24 correctness fix (the canonical comparison
still governs *identity*, it just no longer governs *reuse*). The attestation log line now names
which policy fired: `attesting: mint budget spent (1/1)` rather than blaming the clock.

Cost of being wrong in this direction: one extra `att/get` per mint — which runs 22–24 paid without
complaint. Raise the constant to test the other way.

### 3. What to look for next run

- `attesting: mint budget spent (1/1)` between mints, ~3 attestations per playback.
- If sessions go back to status 1, the mint ratio was the cause and the run-25 "server-side client
  state" write-up needs correcting.
- If they still open in grace, the ratio is exonerated and the client-state hypothesis survives —
  at which point the test is to leave the emulator alone for an hour, or clear its cookie jar, and
  change exactly one of those axes.

---

## Run 27 (2026-08-05 23:37) — mint-budget hypothesis falsified. Two wrong calls in a row.

`MAX_MINTS_PER_ATTESTATION = 1` did exactly what it was designed to do:

```
23:37:34.792  attesting: mint budget spent (1/1)   → previous BotGuard VM shut down → att/get
23:37:41.937  attesting: mint budget spent (1/1)
23:37:49.189  attesting: mint budget spent (1/1)
23:37:50.393  attesting: mint budget spent (1/1)
23:37:58.383  attesting: mint budget spent (1/1)
```

Five separate attestations, one mint each — the run-23 cadence restored precisely — and **all five
sessions still opened in grace**. The mints-per-attestation ratio is not the discriminator.
Reverted to 64 (i.e. TTL governs reuse again, as in runs 22-24).

That is two causal stories in a row from me that the next run killed: "server-side client state"
(run 25) and "mint ratio" (run 26). Both were plausible, neither was tested before being written up
as the leading explanation. The pattern to stop is proposing a cause from a single log and changing
code on it.

**The budget also made one thing measurably worse.** att/get went from 2-3 per playback to ~5,
because the mint *count* per playback has grown with rebuild churn. If anything in this system is
rate-limited or scored on attestation frequency, 1 is the wrong direction.

### The record, corrected

"It was working perfectly until run 24" is not what the log says. Runs 18, 19 and 20 all opened in
grace — run 20's note is literally "we're running into a stream that will not load". The clean
stretch was runs **21–24**, four runs. Before that, grace was routine. That does not make runs 25-27
acceptable, but it does mean the thing to explain is a 4-run streak followed by a 3-run drought, not
a working system that one commit broke.

### The old implementation — found, and it is still running

`app/src/main/assets/po_token.html` is the pre-migration WebView flow. It was never deleted and
`WebPoTokenProvider` still injects it on every launch (`po_token helpers loaded; runBotGuard=function`
appears in all seven logs). It carries this, from production experience:

```
// CRITICAL: derive the mint callback ONCE and reuse it for every identifier. Calling
// getMinter(integrityToken) afresh on each mint makes the BotGuard minter accumulate internal
// state, so each minted token grows (~+64 bytes/mint) and the SABR server rejects the periodic
// re-attestation it needs to stream past ~60s. Caching the callback yields stable, valid tokens.
```

`sabr_potoken.js` does honour this — it stores `mintCallback` on the attestation and reuses it — but
only on the reuse path, which `MAX_MINTS_PER_ATTESTATION = 1` had disabled entirely. So run 27 ran
with a fresh minter for every single mint, the configuration that comment exists to warn about.

Suggestive but not conclusive: run 27 is also the only run where the /player pot length **drifts
within the run** — 856 → 858 → 858 → 859 — the "token grows per mint" signature. The integrity token
also alternated 92/96 bytes across those mints, which could account for it innocently. Worth
watching now that reuse is restored: if the length is stable again, the drift was mine.

**No git history is available** in the working folder (`.git` absent), and the build intermediates
only hold a 23:31 snapshot, so the pre-migration attestation path cannot be diffed against the
current one from this machine. If that history exists elsewhere it is the most valuable artifact
left — the old design solved this, and the diff would say how.

### What not to do next

Not another client-side change on a single-run hypothesis. The axis that has never been varied
across all seven runs is the environment: same emulator, same cookie jar (`cookies len=1149`
unchanged since 22:32), same IP, seven launches in ~65 minutes. That is one clean experiment —
wipe the emulator's WebView data or leave it idle an hour, change nothing else — and it either
restores playback or eliminates the last untested variable.

---

## Reverted to the run-24 build (2026-08-05, before run 28)

Everything added after run 24 — the last run that played — is out. javac clean, node --check clean,
no residue.

| change | landed after | status |
|---|---|---|
| `sameVisitor` in `identityChanged` | run 24 | **reverted** to raw `!==` |
| `sameVisitor` in `bindingKind` | run 24 | **reverted** to raw `===` |
| grace hand-off (`declineGraceRebuild` / `onGraceExhausted`) | run 25 | **reverted**, all three files |
| `MAX_MINTS_PER_ATTESTATION` | run 26 | **removed** |

Kept, because they predate the last clean run and reverting them would move two variables at once:
`resolveSessionVisitorData` (run 23), `PAGE_IDENTITY_TIMEOUT_MS = 20_000` (run 22), and the
normalised **MATCH diagnostic** (run 22 — it only prints a log line, it governs nothing).

So run 28 is the run-24 client. If it plays, one of those four lines is implicated and we bisect. If
it still opens in grace, the client is exonerated and the environment is what is left.

### The `%3D` question — answered, and it is not the length drift

The maintainer asked whether the pot length drift is the visitorData's `==` padding going missing. Checking the
code rather than guessing:

The log line `Minted player pot bound to … = <string> (len=N)` prints the **identifier**, and `len=N`
is the length of the **pot**. So the `<string>` ending `…alJZbm9rZWc%3D` is the visitorData, and the
padding is present — as `%3D`, percent-encoded, three characters rather than one `=`.

That does expose a real split, just not the one suspected:

```
/player pot is minted over   …%3D   (raw page value, 558 chars)   ← TextEncoder().encode(identifier)
session + stream run under   …=     (decoded, 556 chars)          ← normaliseVisitorData()
```

Two different byte strings for one identity. **But it cannot explain grace**, for a structural
reason: grace comes from GVS, and the GVS pot is bound to the 11-character **videoId** — no padding,
no percent-encoding, nothing to disagree about. The visitorData binding only appears in the
`/player` pot, and `/player` accepts it every time (200, echoes our identity back). Worth tidying;
not the bug.

**The drift itself** (856 → 858 → 858 → 859 in run 27) mostly tracks the integrity token, which
alternated 92 → 96 → 96 → 96 bytes across those same mints; base64 of a variable-length payload moves
±1. There is a residual +1 across three mints at a constant integrity-token length that is not
explained, and that is exactly the shape `po_token.html` warns about. Now that attestation reuse is
restored, the pot length should be constant within a run again — if run 28 shows it drifting anyway,
that is a real thread to pull.

---

## Run 28 (2026-08-06 22:33) — attestation is FINE. The stall is ours, in the pump.

**The token was never rejected.** `protection=1/-1` on every one of the 245 responses; `58=[status=1]`
from n=1. No grace, no rebuild, no re-mint. One attestation, one /player pot, one GVS pot, and
`reusing attestation (age=78s, mints=1)` at the very end. The identity path is clean:
`MATCH=true`, page identity adopted, `/player` echoing `visitorData-AA…` back at us.

Two things changed at once, so **attribution is not clean**: the client went back to the run-24
build, AND the run happened ~23 hours after run 27 instead of minutes. Either could explain the
clean draw. I am not going to claim which.

### What actually failed

```
22:33:56  SABR preflight: status=1 (ok) after 1 attempt — publishing
22:33:57  player built, playback starts
…         245 responses, all status=1
22:35:06  watchdog: buffering > 25000ms — retrying
```

Playback ran to ~48 s of content and froze. The watchdog rebuilt 25 s later. That is a **client-side
stall on a healthy session** — a completely different failure from runs 18–27.

### The signature: a re-request loop

From n≈3 to n=245, roughly **nine of every ten responses are byte-identical**:

```
segments=[135:12,135:13,135:14,135:15,135:16]      itag 135 only — no audio
mediaBytes={375790, 342056, 825277, 530215, 327931} = 2,402,069 B
```

2.4 MB of the same five video segments, re-sent every ~250 ms — roughly **9 MB/s of duplicate
media**. Only ~14 responses in the entire run carried anything new (n=114, 124, 133, 144, 153, 163,
173, 183, 193, 203, 212, 221, 230, 240).

Forward progress did happen despite that — video reached seq 84 (~439 s), audio seq 45 (~449 s) —
but the pump spent ~90% of its requests re-fetching **segment 12, at 48.2 s, while its own edge was
at 439 s**. It was pinned hundreds of seconds *behind* the buffered edge.

That is the shape of `pendingRefetch`, whose own doc comment reads: *"Set by a reader blocked on an
evicted segment behind the edge (backward seek); the loop repositions the session onto it next
round."* A reader stuck on 135:12 asks for a rewind; the loop calls `prepareForRewind` and gets
12–16 back; the reader is still blocked; next round it asks again. Nothing in the loop notices it
has serviced the same request 200 times.

**This is a hypothesis, not a finding.** `SabrStreamPump.DEBUG` is `false`, which suppresses the one
line that would settle it — `rewind to seq=… (edge=… readerHead=…)`. Flipping that flag and
reproducing would say directly whether it is the refetch path, the forward-jump path, or neither.
That is the next step; no code should change before it.

### Why the audio track matters

The repeated responses carry **itag 135 only**. Audio (140) appears solely in the ~14 progressing
responses. Whatever the pump is doing on those rounds, it is asking for video alone — consistent
with a single-track reader driving the repositioning while the other track is left to starve.
Worth checking against `getReaderTailMs()`, which returns `min(audio, video)` and so is pinned by
the stuck track.
