# Backlog — deferred fixes and open questions

Things found while debugging that were deliberately **not** fixed at the time, either because they
were off the critical path or because changing them would have muddied a single-variable test.
Nothing here is currently breaking playback.

Companion docs: `RUNTIME_PERF_PLAN.md` (Option D / Option A migration),
`FIXES_AND_ANALYSIS_2026-07-30.md` (the debugging narrative these items came out of),
`CLAUDE.md` (mandatory live-lookup-NewPipe/GrayJay-source rule before touching attestation/
PO-token/client-selection/streaming-protocol code — read this before any of that work).

Status key: **P1** worth doing soon · **P2** nice to have · **P3** cosmetic / cleanup

---

## Playback & PO token

### ~~P0 — DASH mode: locked-screen autoplay fails ("Couldn't load"), and the app can crash on screen-off~~ — code-complete 2026-10-17; user-reported working on release 0.1.0 (2026-10-19)
**2026-10-19:** the user reported "all works" after installing the 0.1.0 release (install, cold start,
locked autoplay, lockscreen info, icon). That is a user report, not a log capture: it wasn't
recorded whether that run was in DASH mode (0.1.0 still defaulted to SABR), so the specific
`DASH: queued … / advanced into …` lines below remain unconfirmed. DASH became the default in 0.1.1.

Captured on a physical Pixel (GrapheneOS), profile build, DASH mode, phone unplugged and locked
(test video ended → next `TqxrNAfdy9Q`):
- **Crash** at 23:35:29 as the screen went off: `ForegroundServiceStartNotAllowedException` from
  `DefaultMediaNotificationProvider$OnBitmapLoadedFutureCallback.onSuccess` → `MediaNotificationManager
  .onNotificationUpdated` → `startForeground`. media3 1.10.1 catches this exception when a
  notification is built immediately, but NOT when the provider calls back after downloading the
  artwork (both paths read in the 1.10.1 sources). bf739b8 gave DASH items artwork for the lockscreen,
  which made that unguarded path reachable on DASH. Fixed in 07936b1 (`PlaybackService` wraps the
  provider and catches only that exception).
- **Locked autoplay** at 23:37:45: DASH built a NEW player per video, so the outgoing player screen
  released the media session — and the foreground service — before the next existed. The new session
  hit the same exception (caught this time) and stayed out of the foreground; as a plain background
  app the process lost its network (`UnknownHostException: i.ytimg.com`), the next video buffered
  for 25 s and the stall watchdog showed "Couldn't load — please go back and retry". Two causes:
  `prefetchUpNext` returned early in DASH mode on purpose ("slightly slower" was the assumed cost),
  and the DASH branch's PlayerBox never passed `onNearEnd` anyway. Fixed by a DASH gapless hand-off
  mirroring SABR's: prefetch `getLegacyPlayback` in the last 20 s, build the source with
  PlayerScreen's own `buildDashSource`, append it to the live player under `dashMediaKey(manifest)`
  (`PlaybackController.appendNextSource`), and on transition seed `legacyPlaybackCache` so the normal
  load path republishes the same manifest and PlayerBox adopts the live player.
- Not covered: a legacy result with no DASH manifest (progressive-only) still takes the old path.
- Re-test: DASH mode, unplugged, lock, let it advance twice. Expect `DASH: queued … onto the live
  player` then `DASH: advanced into … on the live player`, and no `MediaController: Release` /
  `MediaSessionImpl: Release` between videos. (The main log buffer rolled over within ~5 min last
  time — plug in promptly, or raise Developer options → Logger buffer sizes.)

### ~~P0 — Both SABR and DASH failing outright: `ReferenceError: 'structuredClone' is not defined`~~ — FIXED 2026-08-23
2026-08-23 emulator run: SABR setup threw immediately ("SABR setup failed, will fall back to
legacy") and the DASH/legacy fallback then failed the same way on every retry
(`getVideoInfo failed (attempt 1/2)`, then "DASH mode: legacy playback unavailable") — both citing
the identical `ReferenceError: 'structuredClone' is not defined`, stack rooted at `cloneIdentifier`
inside a full recursive-descent JS parser (`parseObjectLiteralOrPattern` → … → `JsAnalyzer.create`).

Root cause (confirmed by reading youtubei.js's actual source per CLAUDE.md's live-lookup rule, not
assumed): `JsAnalyzer`/`JsExtractor`
(`node_modules/youtubei.js/dist/src/utils/javascript/{JsAnalyzer,JsExtractor}.js`) are youtubei.js's
AST-based fallback for extracting the nsig/sig decipher function from `base.js` when regex
extraction doesn't cleanly match — built on the `meriyah` parser dependency. Meriyah's own internals
(`cloneIdentifier`) call the real global `structuredClone()` to deep-clone AST nodes while parsing.
QuickJS has never implemented `structuredClone` (a ~2022 browser/Node addition, not part of the JS
language), and `polyfills.js` never added it — so the fallback path throws instead of extracting
anything, and BOTH SABR and DASH fail before playback starts, since both share `Player.create()`'s
initial `base.js` parse.

**Not a regression from the same-day `youtubei.js` 17.0.1→18.0.0 bump** — verified `Player.js`,
`JsAnalyzer.js`, and `JsExtractor.js` are byte-identical between the two versions (`diff` against
fresh `npm pack` downloads of both). It's whichever `base.js` YouTube happens to be serving
(`player/2574220e/...` in this run) that decides whether the regex fast path or this AST fallback
runs, so this bug was latent and could reappear/disappear independent of any change in this repo —
worth remembering if "no playable format" recurs after a period of it working fine.

**Fix applied**: added a `structuredClone` polyfill to `js-runtime/src/polyfills.js` (structural
deep clone with `WeakMap` cycle protection — Meriyah only needs it for plain, JSON-safe AST node
objects, not the full transferable/postMessage semantics of the real API).

**Confirmed fixed 2026-08-23** by a fresh on-device test (emulator, process 16161): zero
`structuredClone` errors anywhere in the run. Both paths recover — `getSabrInfo` succeeds (SABR now
gets as far as opening a session in grace before failing on the separate grace→protected issue
below, instead of never starting), and `getVideoInfo`/`getLegacyPlayback` succeed cleanly, recovering
into `client=VISIONOS` (`[__yt] client=VISIONOS (28 direct urls, verified)`, then `[__yt] legacy
client=VISIONOS, 118 formats (118 direct)` on a second video) — confirming the VISIONOS-first
`CLIENT_PREFERENCE` reorder and the new `verifyClientPlayable` live HEAD-check both work end to end.
DASH playback ran clean for 2+ minutes with zero errors on video `4E9OcgW-z2o`.

### ~~P0 — SABR: grace session flips to protected right as the content-bound pot upgrade lands~~ — REDIRECTED 2026-08-23
2026-08-23 emulator run (process 16161, video `-K5JVE03paw`), reproduced with clean data now that
`structuredClone` no longer contaminates the signal. Session opens in grace (`protection=2/-1`) and
serves 3 successful media-segment responses (n=1,2,3), then a response comes back `protection=3/10`
(n=4). `SabrStreamPump: grace session went protected — abandoning for a rebuild instead of spending
the retry/reload budget` fires, playback continues on buffered data, then fails at `59277ms`:
`ExoPlaybackException: Source error` / `IOException: SABR pump fatal for itag=135` at
`SabrSegmentDataSource.awaitSegment(SabrSegmentDataSource.java:171)`.

**Correction after digging in**: the "upgrade pot mishandled" theory below was wrong, or at least
unnecessary — re-reading `SabrSessionFactory`'s own doc comment and `SabrStreamPump.loop()` in full
(not just the branch that fires) shows grace→protected is an *accepted, by-design* possible outcome
of the two-phase bootstrap already: grace is expected, the content-bound upgrade races the burst, and
if the race is lost the pump is deliberately built to abandon rather than fight a state its own
comment says "no amount of re-minting recovers." That is not a bug to fix — it's the documented
worst case of a design that trades a small chance of this against never spamming fresh attestations.
The actual gap was that nothing reacted to the abandon until ExoPlayer's data source hit the absent
segment and threw — exactly this run's `IOException: SABR pump fatal for itag=135` — which is the
already-open P1 item below ("Rebuild the session before the buffer starves"), not a new pot-handoff
bug. Fixed there 2026-08-23; see that entry for the wasted-reactive-mint finding this investigation
also turned up along the way (real, but separate and lower priority).
Files: `…/sabr/SabrStreamPump.java`, `…/sabr/SabrSessionHolder.java`, `…/ui/player/PlayerViewModel.kt`.

<details><summary>Original (superseded) theory, kept for history</summary>

The response **immediately following** `AppSabrPoToken: minted real WEB PO token bound to
videoId/content` (thought at the time to be the two-phase bootstrap→upgrade pot swap; it is more
likely the reactive re-mint documented as wasted in the P1 entry below) coming back protected looked
like a tight correlation with the pot upgrade landing. Working theory, not confirmed: the **upgrade**
pot (content-bound, minted mid-session) is not being handed to the SABR/UMP session the same way the
**bootstrap** pot was, so the server treats the upgrade as a token swap it never authorised. Live
comparison against GrayJay's/NewPipe's SABR pot handoff (per `CLAUDE.md`'s live-lookup rule) was
proposed before changing code. Superseded by the finding above before that comparison was needed.

</details>

### P1 — GVS pot binding: read YouTube's own hint instead of only the hardcoded `PoTokenBinding.SWAP` default
The live-lookup comparison originally proposed for the item above (against GrayJay's SABR pot
handoff) turned out to have a real answer worth acting on, even though it wasn't the fix for the
grace→protected item. GrayJay's YouTube plugin (`plugins.grayjay.app/Youtube/YoutubeScript.js`,
`YTSessionClient.getClientInit`/`getContentDetails`, confirmed via live fetch 2026-08-23) does not
guess the GVS pot binding and reactively fix it up the way our two-phase bootstrap does — it reads
`serializedExperimentFlags` off the watch page itself (under `ytcfg`'s
`WEB_PLAYER_CONTEXT_CONFIGS.WEB_PLAYER_CONTEXT_CONFIG_ID_KEVLAR_WATCH`), specifically
`html5_generate_content_po_token`/`html5_generate_session_po_token`, and mints exactly the binding
YouTube says it wants for that session, once, upfront.

**Applied 2026-08-23** as an OPTIONAL, best-effort override on top of the existing hardcoded default —
never a replacement for it, since `PoTokenBinding.SWAP=false` is itself the result of two carefully
run A/B experiments (runs 18/19, see `PoTokenBinding`'s doc) and this must not regress behind it.
New `WatchPageExperimentFlags.resolveGvsSwap` does a plain HTTP GET of the watch page (no WebView —
the field is static embedded JSON, mirroring GrayJay's own `getClientConfig(homeHtml, …)`), extracts
the two flags, and resolves a `Boolean?` (null = unresolved, callers fall back to `SWAP` exactly as
before). Wired into `SabrSessionFactory.create` (kicked off concurrently, never awaited by the fast
bootstrap mint — only by the background upgrade job, which now skips minting a content-bound upgrade
entirely when the flag says session-bound is already correct) and into
`AppSabrPoTokenProvider`'s reactive re-mint path.

**Not yet confirmed live** — outbound fetches to youtube.com from the dev environment were
rate-limited (HTTP 429) both times while investigating, so neither the page's exact structure nor
this extraction has been exercised against a real watch page yet. Written defensively (any parse
failure, timeout, or ambiguous/absent flags resolves to `null` → today's behavior, unchanged) so a
wrong assumption cannot regress playback — but it also means the feature could simply be silently
inert until confirmed. **First on-device test is the actual verification**: check logcat for
`WatchPageExperimentFlags` — either `serializedExperimentFlags not found` / `http <code>` (extraction
isn't working, needs a fresh look at the actual page HTML) or `swap=true/false/null` (working; `swap`
is what to watch). If a run ever shows `swap=true`, that reintroduces the exact configuration run 19
found worse (session-bound GVS never reached status=1) — per-video is a different claim than
always-on-globally, but it's the case worth watching most closely, not trusting quietly.
Files: `…/sabr/WatchPageExperimentFlags.kt` (new), `…/sabr/PoTokenBinding.kt` (`swapOverride` param),
`…/sabr/SabrSessionFactory.kt`, `…/sabr/AppSabrPoTokenProvider.kt`.

### ~~P0 — Refresh `sabr-reference/` to upstream HEAD and re-port~~ — DONE 2026-08-02
Refreshed to `44c84c3` and re-ported on 2026-08-02 (see `sabr-reference/README.md`'s refresh log and
`FIXES_AND_ANALYSIS_2026-07-30.md`'s "SABR package re-ported from upstream 44c84c3" entry). This item
sat stale in the backlog for two weeks after the work was actually done — re-audited 2026-08-18:
`main` moved 5 more commits on the SABR package between the pin and 2026-08-02 (PO-token/attestation/
protection-boundary hardening only — nothing touching rewind/refetch/buffered-edge logic), then went
quiet. **Not worth refreshing again yet** — see the P1 item below for why a refresh wouldn't even help
the active bug, and note `3d309951`/`a953c3c8`/`60462a13` upstream will need reconciling against our
own local `protectedRounds` patch in `BuiltinSabrSessionPolicy.java` whenever it does happen, not a
mechanical copy. Also worth watching, not yet actionable: an unmerged upstream branch
(`fix/youtube-shorts-upload-dates`, commit `c82d0030`, PR #92, 2026-08-11) doing a full SABR rewrite
that also drops media3 support — if/when that merges to `main`, treat it as a much bigger re-port than
a routine refresh.

### P1 — Stall bug: SABR pump gets stuck re-requesting a segment that can never satisfy the reader
Root cause (2026-08-18 investigation): `prepareForRewind()` never calls `discardCachedSegment()` on
the target before re-requesting it, so if the cached copy is the problem (e.g. it reliably fails
downstream ExoPlayer extraction), `ingestAndCacheSegment()`'s `putIfAbsent` throws away every re-sent
copy from the server and the loop repeats forever — this is what Run 28 in `FIXES_AND_ANALYSIS`
observed as ~200 identical re-fetches of segment 12 while the buffered edge was at 439s.
**Fix applied 2026-08-18** (`SabrStreamPump.java`, `YoutubeSabrSession.java`): added
`session.discardCachedSegment(refetch)` before `prepareForRewind()`, a consecutive-repeat cap
(`MAX_CONSECUTIVE_REWIND_REPEATS = 5`) as a backstop, and removed `prepareForRewind`'s collateral
rewind of the companion track's buffered bookkeeping (that pairing belongs to real user seeks via
`prepareForMediaSegment`, not this single-track internal repair path). `SabrStreamPump.DEBUG` is also
now `true` so the next reproduction's `rewind to seq=…` trace can confirm the mechanism directly.
**Needs on-device validation** — none of this has run against a real build yet. If the stall still
reproduces after this, the DEBUG trace is the next diagnostic step (see the investigation report).

### ~~P1 — Audio dies mid-stream on multi-audio videos~~ — RETRACTED
`writeAudioFormatByItagAndXtagsLength()` is only reachable via `writeOfficialWebPreferredFormats()`,
gated on `writeAllPreferredFormats` + `writeOfficialWebPreferredFormats` — both default `false` with
no callers in our app. **That code never runs here.** The `audioTracks=14` correlation with audio
dying is still unexplained and worth investigating, but this was not the mechanism.

### ~~P1 — Adopt the WebView's visitorData as the session identity~~ — RESOLVED by the reference refresh
Run 9 (first run on the refreshed upstream port) shows `protection=1/-1` on **every** response —
status 1 is accepted. The token is being honoured; `prot=2`/`prot=3` are gone. Whatever in the stale
port was breaking attestation, the refresh fixed it. `MATCH=false` in the identity diagnostic is
therefore NOT load-bearing — leaving the note below for history, but do not spend time on it.

### P2 — Honour the server's NextRequestPolicy readahead
The server advertises `targetAudioReadaheadMs=15000 / targetVideoReadaheadMs=15000` every round;
`SabrStreamPump` ignores it and paces on `READAHEAD_CUSHION_MS = 30_000` + a 100MB byte cap. Fetching
2× the requested readahead is the likely reason for the many `segments=count=0, backoff=2000` rounds.
Wire in `SabrDecodedResponse.getNextRequestPolicy()` — but only after the contiguous-edge fix is
confirmed, one variable at a time.

### (historical) Adopt the WebView's visitorData as the session identity
**Was re-promoted after run 8, now superseded — see above.** The run-7 "declared play head" theory was wrong: run 8 shows
`prot=2` on the **very first** no-media round at 21.3s of buffer, long before any 60s boundary.
Status 2 = grace, so the token has never been accepted in any run. The ~60s wall is the grace burst.

**Diagnostic rule worth remembering:** look at `prot` on the FIRST response of a session. If it is 2
before any boundary, the token is the problem and nothing downstream will change that. Two theories
(bitrate, declared play head) were promoted to root cause on correlation while this discriminator
sat unread in the logs.

The GVS PO token is attested inside the youtube.com WebView, which
has its own session and cookies. The Innertube session that makes `/player` has a different identity,
so the token authorises a session the stream wasn't issued to → grace burst → `status=3` at ~60s.

Two attempts have failed for the same reason, and the pattern is worth remembering:

* `generate_session_locally: true` — youtubei.js *fabricates* an identity from
  `generateRandomString(11)`. YouTube has no record of it. (Current state; dies at 60s.)
* `generate_session_locally: false` — fetches a *real* identity from `/sw.js_data`, but a
  **different** one from the WebView's. Reverted: worse (stream never starts), and the diagnostic
  showed `session=visitorData-H… page=visitorData-I… MATCH=false` — still mismatched.

Fetching a second real identity just swaps one mismatch for another. The identity must come **from
the WebView**, since that is where the attestation physically happens. Sketch: read the page's
visitorData (the diagnostic already does this — 558 chars, `via=html`), hand it to the JS runtime
before `createAnonSession()`, and pass it as `visitor_data` so `visitor_data || …` short-circuits the
fabrication. Then `/player`, GVS and BotGuard all share one identity that YouTube actually issued.

Watch out for what run 5 exposed: the real visitorData is ~556 chars (not ~28) and must survive the
`X-Goog-Visitor-Id` header path, and don't let a second clientVersion enter the picture — keep the
bundled one everywhere or take the page's everywhere, not one each.
Files: `app/src/main/assets/sabr_potoken.js`, `…/core/webpo/WebPoTokenProvider.kt`,
`js-runtime/src/index.js`.

### P2 — Audio dies mid-stream on multi-audio videos (mechanism unknown)
Run 8: `audioTracks=14`, audio stopped ~30s while video continued. Runs 6 and 7 had `audioTracks=1`
and no audio problem. The correlation is real; my proposed mechanism was not (see the retraction
above). Needs a fresh look — reproduce on a multi-dub video and log which formatIds actually go into
the request, versus a single-track video.

### P2 — Comments returned empty in run 5 (both paths)
`native getComments failed, falling back to JS: no comment section token`, then the JS fallback also
failed with `Comments page did not have any content`. Seen on a build with the reverted session
change, so it may be collateral or may be an independent parser break — check against a clean build
before digging.

### P1 — Don't block first playback on the *session* PO token
Measured cold start: 40s from process launch to `getSabrInfo`, because everything waits on `warmup`,
which waits on the session pot, which waits on the WebView loading youtube.com (~17s on its own).
But the session pot is only for the Innertube `/player` call — it is the **GVS** pot that genuinely
needs the WebView, and only at play time. Start the browse/search path without waiting on
attestation at all, and mint lazily.

### P1 — 8.4s to evaluate `base.js` in QuickJS
`base.js` (~2MB) downloads in ~2s and then takes **8.4s** to evaluate in QuickJS before
`warmup: authed session ready`. Single largest software cost in the cold-start chain. It is fetched
only for the nsig transform. Options: cache the parsed form, defer it until an nsig is actually
needed, or port the transform natively (it's the last real reason the playback QuickJS context
exists).

### P1 — Cache the GVS integrity token
Every forced mint re-runs the full BotGuard attestation (`att/get` → interpreter → snapshot →
`GenerateIT`), ~1–2s, and the WebView work lands on the **main thread**. The integrity token itself
has a TTL (`WebPO init complete (ttl=43200s)`), so the expensive half is cacheable and only the
final bind step needs re-running per videoId.
Files: `app/src/main/java/com/example/ytclient/core/webpo/WebPoTokenProvider.kt`,
`app/src/main/assets/sabr_potoken.js`.

### P2 — Revisit the re-mint throttle now that the root cause is fixed
`MIN_PO_TOKEN_REFRESH_INTERVAL_MS = 8_000` and `MAX_PO_TOKEN_REFRESHES = 3` were tuned against the
mint storm that the broken visitorData was causing. With a valid identity a genuine `status=3`
should be rare and recoverable, so the throttle may now be too conservative — upstream retries
immediately after the server's backoff. Retune against real data, don't guess.
File: `…/sabr/YoutubeSabrSession.java`.

### P2 — `rebuildSabr()` reuses a possibly-stale `lastSabrDto`
Quality and audio-track switches rebuild the session from the cached DTO, i.e. the original
`ustreamerConfig` and `serverAbrStreamingUrl`. Those expire on a long watch. The pot reuse is now
guarded by `isPoTokenRefused`, but the DTO staleness is not.
File: `…/ui/player/PlayerViewModel.kt` (~line 927).

### ~~P3 — `identity: … page=(none) MATCH=false` is a misleading diagnostic~~ — FIXED 2026-08-02
Read only `ytcfg.get('VISITOR_DATA')` (empty in this WebView), so it compared nothing on every mint
while reading like a real finding — and led me to record page-identity mismatch as "refuted" when it
had never been tested. Now falls back to the HTML scrape and reports `pageLen=` / `via=`.

### P3 — Finish the SABR reference diff
43 of 48 files under `…/services/youtube/sabr/` are byte-identical to `sabr-reference/`. Five are
not, and only `YoutubeSabrSession.java` has been reviewed. Unreviewed: `UmpReader.java` (121 diff
lines), `SabrMediaSegmentCollector.java` (71), `YoutubeSabrStreamState.java` (59),
`SabrWebmSegmentIndexParser.java` (10), `SabrResponseDecoder.java` (9). `YoutubeSabrProbe.java`
(533) is our own I/O hook and is expected to differ.

---

## Extraction / client hygiene

### P1 — Drop the redundant second `/player` call
Every video load makes two blocking `/player` round trips: `getSabrInfo` (WEB, the SABR path) and
`getVideoInfo` (iOS, the legacy direct-URL fallback). When SABR builds successfully — the normal
case — the iOS URLs are never used, so that's ~600ms of dead latency on every open. Fetch the
fallback lazily, only when the SABR path actually fails.
File: `js-runtime/src/index.js`.

### ~~P3 — Locale-dependent "Newest first" comment sort~~ — FIXED 2026-08-18
`YoutubeBridgeImpl.getComments` picked the newest-sort continuation token by matching the sort-menu
label against the substring `"new"`, which only works in English (e.g. Dutch's label is "Nieuwste
eerst" and silently failed to match, quietly serving TOP order instead with no error). Fixed to pick
by the sort menu's stable position (YouTube always returns [Top, Newest] in that order; `sortTokens`
is a `LinkedHashMap` preserving it) with the old substring match kept as a fallback.
File: `youtube/bridge/YoutubeBridgeImpl.kt`.

### ~~P3 — Dead, wrongly-encoded `SEARCH_PARAMS_VIDEOS_ONLY` constant~~ — FIXED 2026-08-18
Percent-encoded base64 (`"EgIQAQ%3D%3D"`) left over in `InnertubeClient` from before `SearchParams`
existed, but `params` is written straight into a JSON body field, which wants the raw value
(`"EgIQAQ=="`, what `SearchParams.videosOnly` actually computes). Unused today (nothing called it)
but doc-linked from `search()`'s KDoc as the example to use — a future caller reaching for it instead
of `SearchParams.videosOnly` would silently break video-only search filtering. Removed the constant
and repointed the KDoc; also dropped an unused `kotlinx.serialization.json.jsonArray` import in the
same file. File: `youtube/innertube/InnertubeClient.kt`.

### P2 — iOS `/player` sends a desktop `userAgent` in `context.client`
`clientName: "iOS", clientVersion: "20.11.6", deviceModel: "iPhone10,4"` … alongside
`userAgent: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) … Edg/141.0.0.0"`. The *streaming* UA
(`STREAM_UA.IOS`) is correct; only the context one is wrong. Returns 200 today, but this is exactly
the sort of client inconsistency that attracts attestation trouble.
File: `js-runtime/src/index.js` (~line 869).

### P2 — A6: retire the browse QuickJS context
All browse endpoints are cut over to native Kotlin with a JS fallback still in place. Remaining:
delete the dead browse code from `js-runtime/src/index.js`, drop the second QuickJS context, rebuild
the bundle, and take before/after startup + memory measurements. Tracked in `RUNTIME_PERF_PLAN.md`.

### P2 — On-device validation of A4 and A5
Comments (A4) and channels/playlists (A5) are still marked unvalidated in `RUNTIME_PERF_PLAN.md`.
They appear healthy in logcat (`native getComments → 20`, `native getChannelInfo(...) → Epic
History`) but have not been walked through deliberately.

---

### ~~P1 — Rebuild the session *before* the buffer starves~~ — FIXED 2026-08-23
In run 4 the pump went fatal at 18:12:51 but ExoPlayer only surfaced the error at **18:13:30** — a
39-second gap, because the reader had ~64s buffered and only discovers `isFatal()` when it next
needs an absent segment. Playing out the buffer is right; waiting until starvation to *start*
recovering is not. Once the pump is fatal we know the rebuild is coming, so kick it off immediately
and hand over when it's ready — the user would see no interruption at all instead of a freeze.

**Re-surfaced 2026-08-23** by the emulator run (process 16161, video `-K5JVE03paw`) analyzed right
after the `structuredClone` fix — the first clean reproduction of the grace→protected failure with
uncontaminated evidence. Re-reading `SabrStreamPump.loop()` end to end (not just the grace branch)
confirmed this P1 item, not a pot-binding bug, is what actually turns "SABR opens in grace and later
goes protected" — itself an *accepted*, by-design possible outcome of the two-phase bootstrap (see
`SabrSessionFactory`'s doc: grace is expected, the content-bound upgrade races it, and if the race
is lost the pump is deliberately built to abandon rather than fight a state that "no amount of
re-minting recovers" per its own comment) — into a *visible* failure. All four `fatal = true` sites
in `SabrStreamPump.loop()` were reactive-only: nothing observed the transition until ExoPlayer's own
data source hit the absent segment and threw, which is exactly `SabrSegmentDataSource.awaitSegment`
→ `IOException: SABR pump fatal for itag=135` → `ExoPlaybackException` at 59277ms in this run.
`onGraceSession` (the only existing early hook) fires when grace *opens*, not when the pump gives up,
and is documented as "purely informational... does not rebuild anything."

**Fix applied**: `SabrStreamPump.markFatal()` — a new private helper that sets `fatal = true` and
calls `SabrSessionHolder.notifyFatal()` — replaces all four raw `fatal = true` assignments.
`SabrSessionHolder` gained a `setOnFatal`/`notifyFatal` pair (one-shot via `AtomicBoolean`, mirroring
the existing `setOnGraceSession`/`notifyGraceSession` pattern exactly). `PlayerViewModel.onSabrFatal`
is wired to it at all three places a live holder is built (`prefetchNext`, `fetchSabr`, `rebuildSabr`
itself) and calls the existing `rebuildSabr()` — the same non-disruptive background-build-then-swap
already proven for quality/audio switches — the instant the pump gives up, instead of waiting for
ExoPlayer to notice. If the rebuild wins the race the viewer sees no interruption; if it doesn't,
`retryAfterPlaybackError` remains the unmodified fallback, so this is additive, not a replacement.
**Not yet confirmed by an on-device test** — do that next, and check whether the proactive rebuild
actually lands before the buffer runs dry on a real grace→protected session, or merely narrows the
window.
Files: `…/sabr/SabrStreamPump.java` (`markFatal`), `…/sabr/SabrSessionHolder.java`
(`setOnFatal`/`notifyFatal`), `…/ui/player/PlayerViewModel.kt` (`onSabrFatal`).

Separately (not fixed, not blocking): `YoutubeSabrSession.applyControlPolicy`'s `REFRESH_PO_TOKEN`
branch still fires a real WebView/BotGuard re-mint on the very first protected response after a
grace session, even though `SabrStreamPump` reads the stale `lastResponseProtectionStatus` from that
SAME response one line later and abandons before any follow-up request could ever use the freshly
minted token — so that mint (~1-2s of real WebView work) is provably wasted whenever a session opened
in grace. Worth guarding (`firstResponseProtectionStatus == GRACE && protectionStatus == PROTECTED`
→ skip the reactive mint, since the pump is about to abandon anyway) but left alone here to keep this
fix scoped to the vendored `YoutubeSabrSession`/`BuiltinSabrSessionPolicy` files as little as
possible. Also noted in passing: `AppSabrPoTokenProvider.mintedOnce` is local to that provider object
and never learns about `SabrSessionFactory`'s proactive upgrade mint (which bypasses the provider
entirely via direct `streamState.setPoToken`), so the first REACTIVE re-mint after a proactive
upgrade gets rejected will incorrectly skip forcing reattestation (`forceReattest=false`) — moot for
the grace→protected case above since that reactive mint is thrown away regardless, but real for a
session that opens at status=1 and later drops to 3 transiently (the "different animal" case
`SabrStreamPump`'s own comment carves out, where the reactive re-mint is not thrown away).

### P2 — Pot reuse across a quality switch is untested against grace
`reused existing WEB pot (95 bytes)` — the rebuild inherits the previous session's token. If the
server tracks the grace burst per *token* rather than per session, the new session starts with a
partly-spent budget. Run 4 is ambiguous (session 2 got what looks like a fresh ~64s of media), so
this needs a deliberate test once the identity fix lands.

---

## Review sweep 2026-08-27 (stability + performance)

Full read-based review of the UI, ViewModel and runtime layers. **No compiler was available** (the
Linux side of the dev bridge has JDK 11 but no Android SDK and no kotlinc), so everything here comes
from reading, not from a build — treat build-breakage claims as unverified in both directions.

### ~~P0 — Grouped home feed stalls at two rows on a fresh start~~ — FIXED 2026-08-27
Regression introduced by the previous session's paging guard. Page 1 is ~30 videos, so most
per-topic buckets sit under `HomeGrouper.MIN_ROW_VIDEOS` and fold into the catch-all — a fresh start
legitimately renders as just "New from your subscriptions" + "Recommended for you" and depends on
paging a few more pages to grow the topic shelves. The added
`info.totalItemsCount > info.visibleItemsInfo.size` clause blocked exactly that: with two rows
nothing is off-screen, so the trigger never armed and the feed could never grow enough to earn the
scroll that would let it grow. Deadlock.

The original condition was already self-limiting (`last >= totalItemsCount - 4` goes false once
enough rows overflow the viewport by 4), so the "runaway paging" the guard was written for was
mostly theoretical. **The real defect was elsewhere**: `loadMore()`/`loadMoreSearch()` checked
`_loadingMore.value` on the caller's thread but set it *inside* `viewModelScope.launch`, which
returns before its body runs — so two triggers in one frame both passed and started concurrent
continuation fetches against the one JS-side cursor, silently dropping a page at the dedup.
**Fixed**: guard reverted in `HomeRowsList` and `VideoList`; the in-flight flag is now claimed
synchronously before `launch` in both `loadMore()` and `loadMoreSearch()`.

### ~~P1 — `feedLoadJob` declared after `init`, so its initialiser wiped the guard~~ — FIXED 2026-08-27
Kotlin runs property initialisers and `init` blocks in textual order. `private var feedLoadJob: Job?
= null` sat at ~line 528, below the `init` block that calls `loadFeed()` — so the `= null` executed
*after* `loadFeed()` had stored its Job, leaving the in-flight guard dead for the whole first page-1
load. A topic toggle or Retry tap in that window started a second concurrent `getHomeFeed`; both
call `rankAndReplace`, so the later-resolving one wins and can show the pre-toggle topic set.
Moved above `init`.

### ~~P1 — `publishSabr` is a non-atomic read-modify-write; a leaked session keeps POSTing~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** `publishSabr` now swaps with `_sabr.getAndUpdate { next }` and releases exactly the value it displaced.

Original report:
`PlayerViewModel.publishSabr` does `val displaced = currentSabrHolder()` then `_sabr.value = next`
as two separate steps, while its own doc claims the pattern makes a leak "unrepresentable regardless
of interleaving". It doesn't. Every caller runs on `Dispatchers.IO` (`rebuildSabr`,
`fallBackToLegacyPlayback`, `loadLegacyPrimary`), and the new `onSabrFatal` → `rebuildSabr` path
fires from the pump thread, making the interleaving materially more likely than before. Two
concurrent publishes both read `displaced = A`, both release A, then B is overwritten by C and
nothing ever releases B — precisely the "sessions from a previous video still POSTing to GVS every
~2s" failure the surrounding code was built to prevent, and a plausible trigger for protection
status=3 on the *current* video.
Fix: `_sabr.getAndUpdate { next }` and release the returned previous value, or confine all `_sabr`
writes to one dispatcher.

### ~~P1 — `WebPoTokenProvider`: three unbounded awaits under the process-wide mint mutex~~ — code-complete 2026-10-19, NOT yet built or run

**Addressed 2026-10-19:** All three waits (init, player-script eval, out-of-page mint) now go through `awaitBounded`, which turns a timeout into a `PoTokenException` (init 30 s, eval 15 s, mint 30 s). The null-WebView mint path now fails its deferred. A timed-out init also drops the WebView, so the next call starts fresh. The pattern was checked live against GrayJay's `BotGuardBrowserGenerator.initialize` (`browser.waitTillLoaded(20000)` plus a `TIMEOUT_MS = 10000` minter poll that reports failure).

Original report:
Every entry point takes `mintMutex.withLock` first, and three paths under it can wait forever:
 * `ensureInitialized()` awaits `initDeferred`, only ever completed from the JS bridge — an offline
   cold start, a WebView provider mid-update, or a CSP-strip intercept serving a page our script
   can't run on leaves it uncompleted.
 * `evaluatePlayerScript` awaits `evaluateJavascript`'s callback with no timeout, though the sibling
   mint path right next to it correctly uses `withTimeout(GVS_MINT_TIMEOUT_MS)`.
 * `generatePoToken` silently no-ops when `webView` is null (the two sibling paths both fail the
   deferred in that case) and then awaits it forever.

Any of these strands the mutex permanently. `App.onCreate` calls `prewarm()` at process start, and
`warmup()` reaches the same mutex *while holding* `QuickJsRuntime`'s `callLock` — so the whole
playback JS queue dies with it. This is a better-evidenced explanation of "SABR and DASH fail
together" than the orphaned-mint-pileup story currently documented in `SabrSessionFactory`.
Fix: bound all three with `withTimeout`, and fail the deferred on the null-WebView path.

### ~~P1 — Mint timeout can never reach its own recovery path~~ — code-complete 2026-10-19, NOT yet built or run

**Addressed 2026-10-19:** The in-page mint now times out via `awaitBounded`, which throws `PoTokenException`, not `TimeoutCancellationException`, so `generateAttestedPoToken`'s reset-and-retry arm is reachable for a hung renderer.

Original report:
`generateAttestedPoTokenOnce` fails via `withTimeout`, which throws `TimeoutCancellationException` —
a `CancellationException`. The `catch (ce: CancellationException)` arm matches first and rethrows,
so the `catch (t: Throwable)` reset-and-retry block below it is unreachable for exactly the
hung-renderer case its own ten-line comment describes.
Fix: catch `TimeoutCancellationException` ahead of `CancellationException`, or convert the timeout
into a `PoTokenException` inside the callee.

### ~~P1 — `pendingSabrMint` is a single slot completed without checking the identifier~~ — code-complete 2026-10-19, NOT yet built or run

**Addressed 2026-10-19:** Each in-page mint gets a request id (`window.__SABR_POC_REQUEST_ID`). `sabr_potoken.js` captures it at injection time and echoes it on every report, error reports included. `onSabrPoTokenResult` drops any report whose id isn't the one in the slot, and the slot is cleared in a `finally` on timeout. This touches only how the SABR/attested-mint result is delivered: which identifier a token is bound to is unchanged (`PoTokenBinding` still decides).

Original report:
`onSabrPoTokenResult` completes whatever deferred is in `pendingSabrMint`, using the payload's
`identifier` only for logging; the slot is also not cleared when the mint times out. A slow mint for
videoId X that lands after a mint for visitorData Y has installed its deferred hands Y's caller a
token bound to X. Silent wrong binding — the exact failure class runs 15-20 were spent chasing.
Fix: stamp the expected identifier next to the deferred, drop a non-matching callback, and null the
slot on timeout.

### ~~P1 — `resetInternal()` does not actually recreate the WebView~~ — code-complete 2026-10-19, NOT yet built or run

**Addressed 2026-10-19:** `resetInternal` now destroys and nulls the WebView, fails every pending deferred, and replaces `pageVisitorData`, the same as `close()` did, which now just calls it. Without the replacement, the new page's identity was never captured either, because capture skips an already-completed deferred.

Original report:
It calls `loadUrl("about:blank")` and nulls `initDeferred`, but leaves `webView` non-null, so the
following `createWebViewIfNeeded()` returns immediately. The comment says the opposite ("recreate
from scratch"). If the renderer really is dead, the retry runs against the same dead WebView.
Related: `resetInternal()` also leaves `pageVisitorData` in place, though `close()` resets it for the
stated reason that "the identity we captured belongs to a page that no longer exists" — so after a
reset the app keeps attesting under the previous page's visitorData.

### ~~P1 — No `onRenderProcessGone` override~~ — code-complete 2026-10-19, NOT yet built or run

**Addressed 2026-10-19:** Overridden: it returns `true` and resets with `rendererAlive = false`, which only destroys the dead WebView (no `loadUrl`/`onPause`) and fails the pending deferreds; the next call creates a new WebView. A report for an instance that was already replaced is ignored.

Original report:
The code explicitly anticipates the hidden WebView being reclaimed ("it's never attached to a
visible window, so the OS is freer to reclaim it"), but without this override a renderer death
terminates the app process instead of being recoverable. Most likely while playing with the screen
locked.
Fix: override it, return `true`, null `webView` and fail the outstanding deferreds.

### P1 — `LibraryStore` decodes the whole library on the main thread during composition
`App.library` is `by lazy`, first touched from `HomeViewModel`'s constructor, which `viewModel()`
builds on the main thread — running eight JSON decodes back to back (subs, hidden channels, hidden
videos, discovered channels, history, playlists, search history, disabled topics), plus a ninth from
`loadCachedHomeFeed()`. The class doc only ever justified deferring *writes*. On a mature install
(history capped at 500 entries carrying title/author/tags, playlists up to 2000 videos) that is
hundreds of KB of JSON before the first frame, growing with use — a likely contributor to the
already-logged `Davey! duration=4809ms` cold-start jank.
Fix: seed the flows empty and populate from a load scope.

**Addressed 2026-10-03 (Option E1, RUNTIME_PERF_PLAN.md) — CODE-COMPLETE, NOT YET BUILT OR RUN.**
Deliberately *not* the suggested fix: seeding the flows empty would have let the first
`getHomeFeed` go out with no history/subscription/search seeds at all on a cold start, i.e. an
unpersonalized page 1 whenever the load lost the race — a correctness regression traded for
latency. Instead every collection is behind a `by lazy` and `LibraryStore.prewarm()` forces them
all from `App.onCreate`'s IO scope, so the main thread at worst waits on the remainder of a load
already in flight rather than performing it. Also fixed here: the `HomeViewModel` constructor no
longer eagerly reads `App.youtubeBridge` (a `synchronized` lazy the playback warmup also resolves
— the `SynchronizedLazyImpl` contention in A3's notes), and the ninth decode
(`loadCachedHomeFeed()` plus the ranking pass it feeds) moved to `Dispatchers.IO`.

### ~~P1 — ExoPlayer listener never removed, and the hijack dispose path skips `release()`~~ — code-complete 2026-10-19, NOT yet built or run

**Addressed 2026-10-19:** The listener is kept in `attachedListener` and removed in `onDispose` on every path, including the hand-off path, where the player itself must stay alive for the successor that adopts it. Still open: the player is built inside `remember`, so a composition that is abandoned before it applies would leak an unreleased player. That's rare; the fix is a `RememberObserver` that releases in `onAbandoned`.

Original report:
`exo.addListener(...)` in `PlayerScreen` has no matching `removeListener`; normally `release()`
covers it, but `onDispose` returns early when `hijackedElsewhere()`. Each locked-screen
autoplay-next hand-off therefore adds another listener to the same adopted player, and
`onIsPlayingChanged`/`onPositionDiscontinuity` have no hijack guard, so every stale listener keeps
writing into a disposed composition's `resumePositionMs`. The player is also built inside a
`remember` factory rather than a `DisposableEffect`, so an abandoned composition leaks it outright.

### P2 — `_ranked` read-modify-written from two threads without `update {}`
The hidden-filter collector (main) and `rankAndReplace`/`rankAndAppendBatch` (IO) both do
`.value` get-then-set, which is not atomic. Interleaved, a "Not interested" tap during an in-flight
`loadMore` either resurrects the hidden card permanently or loses the whole appended page. The
field's own doc claims `StateFlow` makes this safe; it doesn't.
Fix: `_ranked.update { }` in all three writers.

**Addressed 2026-10-03 (Option E, RUNTIME_PERF_PLAN.md) — CODE-COMPLETE, NOT YET BUILT OR RUN.**
Taken via one monitor rather than `update { }`: all three writers (`rankAndReplace`,
`rankAndAppendBatch`, and the hidden-filter path now extracted as `applyHiddenFilter`) are
`@Synchronized` on the ViewModel. `update { }` would have made each `_ranked` mutation atomic but
not the `publishRanked()` that must follow it, so the pair would still interleave. The false claim
in `_ranked`'s own doc is corrected in the same change. Note this entry was *half* fixed in the
first pass of Option E — the two ranking writers got the monitor and the collector didn't, which is
strictly worse than neither having it — so if bisecting, the complete fix is the follow-up commit,
not `24ec0be`.

### P2 — `_ranked` is unbounded, and every append regroups the whole list on the main thread

**2026-10-20, still deliberately open:** capping by dropping the oldest entries would also drop them from `rankAndAppendBatch`'s already-shown dedup, so videos the user scrolled past could come back as "new". A real fix needs a separate, bounded seen-id set. The memory itself (a few thousand small objects in a long session) isn't urgent now that grouping runs off the main thread.

Nothing trims it. At ~30 videos/page an hour of scrolling is 1200-1800 `Ranked` entries retained for
the ViewModel's life; `rows` then re-runs `HomeGrouper.group` (a `distinctBy` plus bucketing plus two
sorts) over the entire list on the main dispatcher on every append, and `publishRanked()` rebuilds a
full List and Map alongside it. The doc sizes this at "a few hundred entries", which the same file's
append-only design does not hold to.
Fix: cap `_ranked`, and/or `flowOn(Dispatchers.Default)` for the grouping combine.

**HALF addressed 2026-10-03 (Option E1, RUNTIME_PERF_PLAN.md) — CODE-COMPLETE, NOT YET BUILT OR
RUN.** The grouping half is done: `rows` is now `flowOn(Dispatchers.Default)` and
`SharingStarted.Lazily`, so `HomeGrouper.group` no longer runs on the main dispatcher on every
append, and no longer runs at construction over an empty list. **`_ranked` is still uncapped and
still retained for the ViewModel's life** — nothing trims it, so the 1200-1800-entry figure above
still stands for a long session; the regroup cost just moved off the main thread rather than
shrinking. Relevant mitigation from the same pass: E2 stopped the grouped layout from
auto-paging itself several times on cold start, which was the fastest way to grow this list without
the user doing anything. Capping remains genuinely open.

### ~~P2 — `feedHasMore` is shared between the "For you" and category feeds~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** Split into `forYouHasMore` / `categoryHasMore`. The same pass found a worse race: a category fetch landing after the user switched chips published that category's videos under the new chip, and a late "For you" page did the same because `publishRanked` published regardless of category. `loadCategory`, `refresh` and `loadMore` now drop results for a category that is no longer selected, and `publishRanked` returns unless "For you" is active.

Original report:
`selectCategory("For you")`'s restore path (added last session) republishes `_ranked` without
restoring `feedHasMore`. Scroll a category to its end, switch back to "For you", and infinite scroll
is dead there until a pull-to-refresh.
Fix: separate `forYouHasMore` / `categoryHasMore`.

### ~~P2 — `topicsAtLastFetch` is stamped before the fetch, not after it succeeds~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** Stamped only after `rankAndReplace` has shown the page, in both `loadFeed` and `refresh`.

Original report:
Contradicts its own doc ("the set the currently-shown feed was actually fetched with"). If
`getHomeFeed` throws, the catch deliberately keeps stale content on screen — but the stamp already
moved, so every later dismissal of the topic sheet short-circuits and the toggle appears inert until
an unrelated refresh.
Fix: stamp in the success branch.

### ~~P2 — Non-`@Volatile` cross-thread fields~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** `forYouHasMore`, `categoryHasMore`, `searchHasMore` and `topicsAtLastFetch` are `@Volatile`.

Original report:
`feedHasMore`, `searchHasMore`, `topicsAtLastFetch` are written from IO coroutines and read from the
main thread with no happens-before edge; the main thread can keep observing a stale
`feedHasMore == false`. (`PlayerViewModel.loadedVideoId` had the same problem for the pump thread and
was fixed 2026-08-27.)

### ~~P2 — `QuickJsRuntime.initialize()` has no lock~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** `initialize()` runs under its own `initLock` Mutex, and a failed init closes its half-built engine instead of leaking it. `close()` now closes the engine on its own JS thread and shuts that executor down (it's terminal).

Original report:
`withContext(jsDispatcher)` serialises threads, not coroutines: `qjs.evaluate(script)` suspends,
letting a second concurrent `initialize()` see `initialized == false`, call `QuickJs.create()` again
and overwrite the field — leaking a native context and re-registering every binding. Reachable from
`App.onCreate`'s `initialize()` racing a ViewModel's first `playback()` call.
Also: `close()` calls `quickJs?.close()` on the caller's thread (main, from `App`), violating the
one-thread invariant stated at the top of the same file, and never shuts the executor down.

### ~~P2 — `AppSabrPoTokenProvider.runBlocking` can park the only pump thread indefinitely~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** The wait is now bounded by WebPoTokenProvider's own timeouts (2026-10-19 fix). An `InterruptedException` from `runBlocking` restores the interrupt flag instead of swallowing it.

Original report:
The class doc says blocking "is fine here (never the UI/JS thread)", but this is the single thread
feeding both readers, and the `mintMutex` wait it blocks on is unbounded (see the P1 above). While
parked, `SabrSegmentDataSource.awaitSegment` counts toward its 120s stall timeout and the buffer
drains. Secondary: `runBlocking` rethrows `InterruptedException`, which the surrounding
`catch (t: Throwable)` swallows without restoring the interrupt flag, so `release()`'s interrupt is
lost and the pump runs another round.

### ~~P2 — `PlayerScreen`'s related list self-triggers, and can crash on duplicate keys~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** The first page is now de-duped like the append path, and `loadMoreRelated` claims its in-flight flag before launching (two triggers in one frame could advance the cursor twice). The self-trigger half is not reachable today: `relatedHasMore` is false until related content has loaded, so the early trigger is a no-op.

Original report:
Two separate issues in the same list:
 * its paging `derivedStateOf` uses `?: 0` rather than an early false, so before related content
   loads the page's two items satisfy the condition and page with nobody scrolling;
 * `items(related, key = { it.id })` is not de-duped. The *append* path de-dupes
   (`(current + more).distinctBy`) — direct evidence the stream can repeat ids — but the first page
   assignment does not, so a repeated id throws `IllegalArgumentException: Key … was already used`
   and takes down the player. `VideoList` defends against exactly this; `PlayerScreen` doesn't.

### ~~P2 — Conditionally-remembered state loses scroll position~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** Hoisted: HomeScreen's pull-refresh state, `feedListState` and `wasRefreshing` now sit above the suggestions return; PlayerScreen's `contentListState` and `upNextDismissed` sit above the fullscreen/PiP and mini returns.

Original report:
Several `remember`s sit below early returns, so they are torn down and recreated:
 * `HomeScreen`: `feedListState`, the pull-refresh state and `wasRefreshing` are declared after the
   `if (showSuggestions) { … return@Column }` block — typing one character into search and clearing
   it resets the feed to the top.
 * `PlayerScreen`: `contentListState` and `upNextDismissed` are declared after the
   `fullscreen || inPip` and `mini` early returns — every fullscreen or minimize round-trip loses the
   related-list position and un-dismisses "Up next". The `movableContentOf` was built to survive
   these transitions; this state sits outside it.
Fix: hoist the declarations above the early returns.

### ~~P2 — Mini/collapse animation recomposes the whole app shell per frame~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** The overlay is its own `PlayerOverlay` composable. `collapse` is read only in `derivedStateOf` (`mini`, `settled`) and inside the `graphicsLayer` block, where scale and translation are now computed. PlayerScreen takes `mini: Boolean` instead of the per-frame `collapseFraction`.

Original report:
`val f = collapse.value` is read inside `playing?.let { }`, and `let` is inline — so the read belongs
to `BoxWithConstraints`'s content lambda, the same scope holding the `Scaffold` and every tab screen.
The `graphicsLayer` block only assigns pre-computed locals, so the state is read during composition,
not draw. `PlayerScreen` can't skip (its `initialVideos: List<SearchVideo>` parameter is an unstable
type), so its whole body re-runs on every drag frame.
Fix: read the animatable *inside* the `graphicsLayer` lambda, and move the `playing?.let` body into
its own `@Composable` so invalidation can't reach the `Scaffold`.

### ~~P2 — Every visible card recomposes every 5 seconds during playback~~ — code-complete 2026-10-20, NOT yet built or run

**Addressed 2026-10-20:** The `playlists` half was already fixed (it's read inside the dialog branch). Now VideoRow/VideoCard collect `watchedFractions` as a `State` and read it only through `remember(video.id) { derivedStateOf { … } }`, so a progress save recomposes only that video's card.

Original report:
`VideoRow` and `VideoCard` each `collectAsState()` three library flows at row scope.
`persistHistory` reassigns `_watchedFractions.value` to a fresh map, and the player calls
`updateProgress` on a `while (true) { delay(5000) }` loop — and since the mini-player now leaves the
tab list composed underneath, this runs for the whole duration of every video. `playlists` is also
read at row scope but used only inside the add-to-playlist dialog, so one playlist edit recomposes
every card on screen for a value none of them render.
Fix: move the `playlists` read into the dialog; derive per-row watched fraction with
`remember(video.id) { derivedStateOf { fractions[video.id] } }`.

### P3 — Smaller items

**Status 2026-10-20:**
 * `LaunchedEffect(forcePlay?.value)`: **not a bug.** A key change restarts the effect with the current composition's `player`, and a rebuilt player starts with `playWhenReady = true` anyway. Adding `player` as a key would be harmful: it would force-play an adopted player the user had paused.
 * Hide lists: **already fixed** (`MAX_HIDDEN_CHANNELS` / `MAX_HIDDEN_VIDEOS`).
 * `saveCachedHomeFeed`: **fixed.** It now takes a `Sequence`, so callers no longer copy the whole accumulated feed.
 * `onCollapseDrag` coroutine per move: **won't fix.** It's cheap next to the per-frame recomposition removed above, and a channel-based rewrite would change the ordering between drags and the settle animation.
 * `awaitSegment` vs released state: **deliberately not changed.** After a quality switch the OLD player can keep reading from the just-released holder until Compose rebuilds it, which can be a long time with the screen locked. Failing those reads at once would reach `onPlayerError` on a player that isn't marked `releasing` yet and start a spurious rebuild. The slow failure is the safer one.
 * `SabrStreamPump.DEBUG`: left on while SABR is still being debugged.

Original list:
 * `LaunchedEffect(forcePlay?.value)` omits `mediaKey`/`player`, so after a quality switch or error
   retry the effect doesn't restart and PiP resume can silently not apply.
 * `LibraryStore`'s hide lists are the only unbounded collections in a class that caps history,
   search history and playlists; both blobs are decoded on the main thread and re-encoded on every
   hide.
 * `saveCachedHomeFeed(_ranked.value.map { it.video })` materialises the full accumulated list to
   keep 30.
 * `MainActivity.onCollapseDrag` allocates and dispatches a coroutine per pointer-move event.
 * `SabrSegmentDataSource.awaitSegment` checks `pump.isFatal()` but not released state, so a read
   arriving after `holder.release()` spins the full 120s before throwing.
 * `SabrStreamPump.DEBUG = true` contradicts its own comment; deliberate per an earlier entry, worth
   flipping back once the rewind trace is no longer needed.

---

## Home feed

### ~~P1 — Grouped-rows layout: topic shelves vanish, return after leaving the screen and coming back~~ — FIXED 2026-08-26
Reported from device testing: in the NETFLIX (grouped rows) home layout the per-topic shelves would
disappear, then be back after visiting the player or channel page and returning.

Root cause is a full-feed RESET, not a rendering fault. `HomeViewModel.rankAndReplace` overwrites
`_ranked` with just the fetched page (~30 videos). `HomeGrouper` folds any bucket under
`MIN_ROW_VIDEOS` (3) into the "Recommended for you" catch-all — so after a reset most per-topic
buckets are 1-2 videos and the topic rows genuinely stop existing. Two paths reset it without the
user asking:
 * `refreshTopics()` ran `loadFeed()` on EVERY dismissal of the topic-settings dialog, even with
   nothing toggled (`HomeScreen`'s `onDismiss`), and
 * `selectCategory("For you")` called `loadFeed()`, so returning from a category chip wiped the
   accumulation too.

The "fixed by navigating away and back" half is the same bug from the other side: leaving and
returning re-composes `HomeRowsList`, whose `LaunchedEffect(nearEnd, rows.size)` fires immediately
on a short list (everything visible trivially satisfies "last visible index within 4 of the end"),
paging until the buckets clear the minimum again. That effect also re-fired on its own appends —
continuous background paging with the list re-measuring under the user, which is the "continuous
updating by the homeranker" the report noticed.

**Fixes applied** (feed content now changes only on explicit intent — pull-to-refresh, retry, a real
topic change, or cold start):
 * `refreshTopics()` compares against `topicsAtLastFetch` (recorded at each real page-1 fetch in
   `loadFeed`/`refresh`) and only refetches when the enabled-topic set actually changed.
 * `selectCategory("For you")` republishes the existing `_ranked` instead of refetching; it only
   calls `loadFeed()` when there is genuinely nothing to restore.
 * `rows` now groups against `_groupingSubscriptions`, a snapshot re-read only at feed-change points
   (`rankAndReplace`/`rankAndAppendBatch`), instead of the live `library.subscribedChannelIds`. A
   live subscribe re-bucketed the whole feed mid-session and could pull a topic row under the
   three-video minimum — a second, quieter way for a shelf to vanish while the screen sat idle.
 * Both paging triggers (vertical list, and the new per-row one) require `totalItemsCount >
   visibleItemsInfo.size` — something genuinely off-screen — before arming, so a fully-visible list
   can no longer page itself.

Hide / "Not interested" still removes a card instantly, and `loadMore()` still appends on real
scroll; only the destructive replace and the self-triggering paging were frozen.

### P2 — `VideoList` has the same self-triggering paging heuristic
`HomeRowsList`'s trigger got the "something must be off-screen" guard above; `VideoList`'s otherwise
identical `derivedStateOf` did not. It is far less exposed — a flat list of cards overflows a phone
screen almost immediately, where a handful of tall shelves may not — so this was left alone rather
than changed untested. Worth aligning if flat-list paging ever looks over-eager.
File: `…/ui/components/VideoList.kt`.

### P2 — Per-row continuations are still not real
Swiping a shelf to its end now reveals more of what that row already holds (up to
`HomeGrouper.MAX_ROW_VIDEOS`, raised 20 → 60 — the old cap silently made already-grouped videos
unreachable in this layout) and then falls through to the ONE flat `getMoreHomeFeed` stream. So
swiping "Because you watched X" to the end still does not fetch more X, it fetches more feed. A real
per-row cursor needs a new bridge surface and N concurrent sessions through the single Mutex-guarded
QuickJS queue — see `HomeGrouper`'s class doc.

**Untested on device** — the whole change above is unbuilt as of this writing.

---

## Performance

### P1 — Heap and GC pressure during playback
Observed on a 1080p stream: heap to 73MB with ~36MB in large-object space, repeated
`Background concurrent mark compact GC` up to **1.033s**, `Suspending all threads took: 92ms`,
AudioTrack underruns. The pump's byte throttle bounds the SABR segment cache, but the ceiling looks
too generous even with `largeHeap`. Some of this was secondary to the software-decode stall (now
capped), so **re-measure before changing anything**.
Files: `…/sabr/SabrStreamPump.java`, `…/sabr/YoutubeSabrSession.java` (`cachedBytes`, eviction).

### P2 — ~9s from player build to codec creation
`Building player` at 13:01:40.7 → `allocate(c2.goldfish.h264.decoder)` at 13:01:50.6. Partly the GC
storm above, partly unknown. Worth a trace once the heap work lands.

### P2 — Startup jank
`Long monitor contention … SynchronizedLazyImpl.getValue() for 583ms`, `Skipped 281 frames`,
`Davey! duration=4809ms` on cold start. Main suspects: WebView + BotGuard bring-up on the main
thread, and contended `by lazy` initialisers.

---

## Environment noise (probably benign — confirm, don't chase)

These recur in every emulator log and have never correlated with a failure:

* `PlayCore StandardIntegrity : Failed to bind to the service` — Play Integrity unavailable on the
  emulator image.
* `WebPoTokenProvider/js ERROR Uncaught OperationError: Internal Error. Retry with an exponential
  backoff. @ base.js:380` — thrown inside YouTube's own player script in the WebView; the pot mint
  succeeds regardless.
* `Handler … sending message to a Handler on a dead thread` from `WakeLockManager` /
  `WifiLockManager` during a player swap — Media3 posting to the outgoing player's released handler.
  Worth confirming it's not our teardown ordering.
* `MESA: Failed to open rendernode`, `Access denied finding property "vendor.mesa.*"`,
  `GLClientState set vao to self` — emulator graphics stack.

---

## Measurement hygiene

### Never judge performance from a debug build — use the `profile` build type
On Android 14+ ArtService will not compile a debuggable app ahead of time, by policy: a forced
`cmd package compile -m speed -f` was rewritten to `dex2oat64 … --compiler-filter=verify` and
reported `actualCompilerFilter=verify, status=PERFORMED` (2026-10-14, physical Pixel, GrapheneOS).
So a debug install runs the app's own code interpreted, and on this app's parse-heavy cold start
that was ~18× slower than the identical code non-debuggable: `session warm` 33.9 s vs 1.8 s, page 1
34.4 s vs 2.0 s, a 5.9 s main-thread freeze vs none. Full write-up in RUNTIME_PERF_PLAN.md E6.

Use `gradlew installProfile` (or the `profile` variant in Android Studio) for anything about speed
or jank. It's debug-signed, so it installs over a debug build without losing data. Quick check of
what's actually on the device:
`adb shell dumpsys package dexopt` → look for `[io.github.vidiapp_dev.vidi]` (`[com.example.ytclient]` for builds from before the app-ID change) → `status=speed` (good) or
`status=verify` (a debug build — numbers will be meaningless). The trade: `run-as`, and with it
per-thread `/proc` CPU profiling, only works on the debug build — so profile on debug to find
*where* time goes, but measure *how much* on `profile`.

### Watch for the debugger agent when reading timings
Run 4 was launched with instrumentation attached — `code_cache/startup_agents/dced2491-agent.so`,
`Redefining intrinsic method java.lang.Thread.currentThread()`, `.studio/instruments-*.jar`. That
inflates everything: `Davey! duration=7965ms`, `Skipped 339 frames`, GC `total 2.050s`,
`Suspending all threads took: 80ms`. Startup and GC numbers from an instrumented run are not
comparable with a plain `adb install` + launch, so don't treat them as a regression against run 3.

### Confirm the APK actually contains the change
Run 4's install path (`~~MX7eyrYIhzGmq3wHVr7CFA==/…-SX2_Q74P66foc7_MAtTz2w==`) was byte-identical to
run 3's, and 1080p was still selected — the `SabrQuality` cap wasn't in that build. Android
randomises the install directory per install, so **comparing the `base.apk` path between two logs is
a quick way to tell whether a rebuild actually landed.**

---

## Privacy

### P2 — SponsorBlock lookups send the plain video ID
`core/sponsorblock/SponsorBlock.kt` calls `/api/skipSegments?videoID=<id>`, and SponsorBlock is on by
default (`PlayerViewModel`'s `PREF_SPONSOR` defaults to `true`), so the SponsorBlock server sees
every video a user opens. That is disclosed in the README's Features section. SponsorBlock's API also has a
k-anonymity form, `/api/skipSegments/<first 4 hex chars of sha256(videoId)>`, which returns the
segments for every video sharing that hash prefix; the client then keeps the entry whose
`videoID` matches. Switching to it hides which video was opened at no functional cost. Verify the
endpoint and response shape against SponsorBlock's current API docs before changing it, then
update the README sentence.

## Build reproducibility

### P2 — `js-runtime/package-lock.json` is gitignored, but the Gradle build treats it as load-bearing `[x]`

**Resolved 2026-10-19 with option 1:** the lockfile is now tracked. This became necessary rather than
optional once releases were built by CI (`.github/workflows/release.yml`) on a fresh Linux checkout:
without the lockfile, every release would bundle whatever youtubei.js was newest that day. The
platform worry below turned out not to apply: a lockfileVersion 3 file records *every* platform's
`@esbuild/*` optional package (win32-x64, linux-x64, darwin-arm64, …), and npm installs only the one
that matches the machine. So one lockfile works for both the Windows dev build and the Linux CI build.
The original analysis follows.
`js-runtime/.gitignore` ignores `package-lock.json` (alongside `yarn.lock` / `pnpm-lock.yaml`),
while `app/build.gradle.kts` declares it an input to BOTH `npmInstall` and `buildJsRuntime`, with a
comment explaining at length why it is not redundant with `package.json`:

> package.json pins ranges (`"youtubei.js": "^17.0.1"`), so an `npm install` that resolves a NEW
> youtubei.js leaves package.json byte-identical. Without this line Gradle then considers the bundle
> UP-TO-DATE and ships one built against the OLD library.

Both halves of that reasoning are right, and together they mean the file is doing real work on this
machine — and that a fresh clone has none of it. The first build in a new checkout runs a plain
`npm install` against the semver ranges and resolves whatever is current, so there is no pinned,
reproducible `youtubei.js` anywhere in version control. Given how much behaviour lives inside
youtubei.js (player extraction, the UA pool, the session-cache default, VISIONOS support arriving
only in 18.0.0 — see CLAUDE.md's live-lookup section), "whatever npm resolved that day" is the one
input least safe to leave unpinned. It is also exactly the class of problem that is invisible until
a rebuild silently changes playback behaviour.

Not fixed, because it is a deliberate-looking choice with a real wrinkle rather than an oversight:
the lockfile records platform-specific optional deps (esbuild ships a per-platform binary —
`@esbuild/win32-x64` here), and CLAUDE.md's environment-boundary section documents a previous
`win32-x64` vs `linux-x64` failure from running `npm install` on the wrong side of the Windows/cloud
boundary. Committing a Windows-resolved lockfile is correct for the only machine that builds this,
but would need `npm install --no-optional`-style care if that ever stops being true.
Options, in order of preference:
1. Drop the `package-lock.json` line from `js-runtime/.gitignore` and commit the lockfile. Highest
   reproducibility; accepts that this repo builds on Windows.
2. Pin `package.json`'s dependencies to exact versions instead of `^` ranges. Platform-neutral, and
   makes the Gradle `inputs.file` on the lockfile genuinely redundant rather than load-bearing.
3. Leave as-is and accept that the JS bundle is not reproducible from a clean clone — fine only
   while the tracked `app/src/main/assets/youtube-runtime.js` is the real artifact of record, which
   it currently is (it is committed, so a clone *can* build the app without re-bundling).
