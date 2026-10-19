# Repo instructions for AI assistants (Claude, etc.)

## Attestation / streaming-protocol changes: live lookup is step one, not a fallback

**Before writing or changing ANY code that touches PO tokens, BotGuard attestation, Innertube
client selection, SABR/UMP, or the DASH/legacy streaming path — the first action, before reading
our own source, before proposing a fix, before citing yt-dlp's wiki or any other secondary doc — is
to fetch and read the CURRENT source of NewPipeExtractor and GrayJay's YouTube plugin.** Not from
memory, not from a cached summary of what they "probably" do — a live fetch, this session, of the
actual files below. Secondary documentation (yt-dlp's PO Token Guide included) is a fine
cross-check afterward, never the primary source.

### Why this is mandatory, not a suggestion

On 2026-08-21 we spent a full debugging session building a `CLIENT_PREFERENCE` reorder
(TV_EMBEDDED/TV/WEB_EMBEDDED-first) based entirely on yt-dlp's PO Token Guide. It was
directionally reasonable and still wrong in ways that only became visible once the actual source
was read the next day:

- We had `TV` (bare `TVHTML5`) in the list at all. Neither NewPipe nor GrayJay ever uses it —
  GrayJay's plugin has no `requestTvHtml5StreamingData`, only `requestTvHtml5EmbedStreamingData`
  (`TVHTML5_SIMPLY_EMBEDDED_PLAYER`). Our own logs already showed bare `TV` failing with "The page
  needs to be reloaded." — a full living-room-device handshake neither reference app bothers
  implementing — and we didn't connect that to "the reference apps don't use this client" until we
  actually read their code.
- We were unconditionally stamping a freshly-minted GVS pot onto every client's media URLs,
  including ones that don't want one. GrayJay's actual request bodies for `TVHTML5_SIMPLY_EMBEDDED_PLAYER`/`IOS`/`ANDROID` never set `serviceIntegrityDimensions.poToken` at all — only its
  WEB-context calls and its WEB-scoped SABR/UMP session get one. We only found this by reading
  `requestTvHtml5EmbedStreamingData`/`requestIOSStreamingData`/`requestAndroidStreamingData` in
  `YoutubeScript.js` directly.
- We had no live-verification step. GrayJay's `verifyDirectPlayback()` does a real HEAD + ranged-GET
  against a candidate client's actual media URL before trusting it — because a manifest full of URLs
  doesn't mean they're fetchable; a pot/session-gated client can return a perfectly well-formed
  manifest that still 403s on every chunk. This is exactly how we picked `IOS` with "20 direct urls"
  in one run and then 403'd on literally every DASH chunk. We didn't know this pattern existed until
  we read the function.
- We didn't know NewPipeExtractor's entire current streaming strategy is "fetch everything from the
  `VISIONOS` client and never touch a `PoTokenProvider`" — a single sentence in
  `PoTokenProvider.java`'s Javadoc that changes the whole picture, and that no secondary doc
  mentions because it's a very recent (2026) change on their `dev` branch.

None of this was findable by reasoning from first principles or from yt-dlp's wiki. It was only
findable by reading the actual, current implementation. That is now the required first step.

### What to fetch

Use `curl` via Bash against `raw.githubusercontent.com` (no `gh` CLI / GitHub API available in this
environment — API calls need repo access that isn't granted here; raw file fetches work fine) or
`WebFetch`. Do not rely on cached knowledge of these files' contents — they change.

**NewPipeExtractor** (`github.com/TeamNewPipe/NewPipeExtractor`, `dev` branch) — the extractor
package: `extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/`
- `extractors/YoutubeStreamExtractor.java` — `onFetchPage()` is the entry point; shows exactly which
  clients get called for what (stream data vs. metadata vs. `next`/related content).
- `YoutubeStreamHelper.java` — the actual per-client `/player` request builders
  (`getVisionOsPlayerResponse`, `getWebMetadataPlayerResponse`, etc).
- `InnertubeClientRequestInfo.java` and `ClientsConstants.java` — exact client identity fields
  (clientName/clientVersion/clientId/device info) for every client the extractor uses.
- `PoTokenProvider.java` — whatever the current PO-token story is; read the Javadoc, not just the
  method signature, it has changed meaning before without changing signature.

Example: `curl -sL "https://raw.githubusercontent.com/TeamNewPipe/NewPipeExtractor/dev/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/extractors/YoutubeStreamExtractor.java"`

**GrayJay YouTube plugin** (FUTO) — the deployed source is NOT minified, ~14.5k lines, directly
readable:
- `https://plugins.grayjay.app/Youtube/YoutubeScript.js` — the plugin itself. Key entry points:
  `source.getContentDetails` (the whole playback pipeline — read this first), `getPlayerData` (the
  primary WEB-context/SABR path, note `serviceIntegrityDimensions.poToken` usage),
  `requestTvHtml5EmbedStreamingData` / `requestIOSStreamingData` / `requestAndroidStreamingData` /
  `requestAndroidVRStreamingData` (the legacy per-client fallback chain — note what each body does
  and does NOT include), `verifyDirectPlayback` (the live-verification step), the BotGuard/attestation
  section (`tryGetBotguard`, `BotGuardBrowserGenerator`, `BotGuardGenerator` — a from-scratch
  attestation implementation worth comparing against our own `WebPoTokenProvider` flow whenever
  attestation itself is the thing being debugged, not just client selection).
- `https://plugins.grayjay.app/Youtube/YoutubeConfig.json` — plugin metadata; `scriptUrl` confirms
  the above is the live, current file, not a stale mirror.
- The GitLab source of truth is `gitlab.futo.org/videostreaming/plugins/youtube` if the deployed
  script and repo ever need reconciling, but the deployed script is what's actually running and is
  usually sufficient on its own.

Also worth checking when the question is specifically about the underlying JS library rather than
either app's own logic: **youtubei.js** (`github.com/LuanRT/YouTube.js`) is what our own `index.js`
is built on. Its `CHANGELOG.md` and `dist/src/utils/Constants.js` / `HTTPClient.js` (per-client
context-building switch statements) are worth a live check whenever a client seems to behave
differently than expected — e.g. VISIONOS support only existed from youtubei.js 18.0.0 onward
(2026-08-13), which we only found by checking the changelog instead of assuming our pinned 17.0.1
had everything NewPipe's newer VisionOS client needs.

### How to use what you find

Cross-reference, don't copy blindly — GrayJay and NewPipe have different priorities (GrayJay
supports login/auth flows we don't; NewPipe is anonymous-only like us, which usually makes it the
closer analog). When their approaches conflict, prefer NewPipe's for anonymous/no-login behavior
and GrayJay's for anything involving a live-verification or fallback-chain pattern, since GrayJay's
plugin is more defensively engineered (see `verifyDirectPlayback`, the settings-gated
`allowLoginFallback`, etc.) even though its default flow assumes auth is available.

Cite exactly what you read (file, function name, line content) in code comments and commit-adjacent
notes — see `CLIENT_PREFERENCE`'s doc comment in `js-runtime/src/index.js` for the expected level of
specificity. A comment that says "GrayJay does X" without a function name and file path is not
verifiable later and will be trusted less than it should be.

## Cache-key discipline

`js-runtime/src/index.js` caches two client selections across app restarts:
`PREF_CLIENT_KEY`/`preferredClient` (DASH/legacy — `CLIENT_PREFERENCE`) and
`PREF_SABR_CLIENT_KEY`/`preferredSabrClient` (SABR — `SABR_CLIENT_PREFERENCE`). Both are versioned
strings (`'yt_pref_client_v4'`, `'yt_pref_sabr_client_v1'`) precisely so a stale cached value on an
already-installed device can be invalidated by bumping the suffix — `loadPreferredClient()` /
`loadPreferredSabrClient()` both check `indexOf(cached) !== -1` against the CURRENT preference
array before trusting it, so a removed client name (like dropping bare `TV`) self-heals even
without a bump, but a REORDER or a semantics change (e.g. today's pot-gating change) does not
self-heal — the device just keeps using whatever it already had cached, silently skipping the new
behavior entirely.

**Rule: any edit to `CLIENT_PREFERENCE`, `SABR_CLIENT_PREFERENCE`, or what "using client X" means
(pot handling, verification, UA) must bump the corresponding `PREF_*_KEY` version suffix**, with a
one-line comment on the new key saying what changed and why a re-sweep is needed. Forgetting this
is exactly the kind of thing that produces a confusing "I applied the fix but my test device still
shows the old behavior" report — the fix is real, it's just not reachable from a warm cache.

## Pot identity discipline

There are at least three distinct PO tokens moving through this codebase, and conflating them has
caused real, hard-to-diagnose bugs before (see `resolveSessionVisitorData`'s and
`createAuthedSession`'s doc comments in `index.js` for the "RUN 23" identity-mismatch writeup):

1. The **authed session's own `/player`-auth pot** — minted in `createAuthedSession` via
   `ensurePoToken(bindingId, false, 'player')`, passed to `Innertube.create({ po_token: ... })`.
   Authorizes the `/player` Innertube API call itself. Lives on `it.session.player.po_token` from
   session creation onward.
2. The **content-bound GVS media pot** — minted on demand via `ensurePoToken(gvsId, false, 'gvs')`
   in `chooseClientInfo`/`getLegacyPlayback`, stamped onto the SAME `player.po_token` field
   (temporarily — see below) so `format.decipher()`/`info.toDash()` append it to media URLs as
   `&pot=`. Only actually needed for `POT_REQUIRED_CLIENTS` (`WEB`, `MWEB`).
3. The **SABR/UMP session pot** — handled separately in `getSabrInfo`, its own mint/attach path,
   not shared with the two above.

(1) and (2) share the SAME `player.po_token` field despite serving different purposes and having
different lifetimes — (1) should persist for the life of the cached session, (2) should only be
present for the duration of one `getLegacyPlayback`/`chooseClientInfo` call and ONLY when the
chosen client actually wants one. This is why both functions now save the pre-existing value,
mutate it for the duration of the decipher/verify pass, and restore it afterward — skipping the
restore leaks (2)'s content-bound token (or its absence) into (1)'s territory for the cached
session's next use.

**Rule: any change touching `player.po_token`, `bindingIdFor`, or `ensurePoToken` must state
explicitly which of the three pots above it's minting/reading/mutating, whether `player` is a
shared/cached object that outlives the current call, and if so whether the change needs a
save-before/restore-after pair.** When in doubt, grep for other readers of the same field before
assuming a mutation is scoped to the current function.

## Logging discipline

The `__nativeLog` bug (`QuickJsRuntime.kt` routing every JS console level to `Log.d` regardless of
severity) hid an entire debugging session's worth of evidence — `console.warn`/`console.error`
calls were indistinguishable from routine chatter, and any logcat filter above Debug showed
nothing from the JS runtime at all. That's now fixed (level-routed: `error`→`Log.e`, `warn`→`Log.w`,
`debug`→`Log.d`, else→`Log.i`), but the underlying lesson generalizes.

**Rule: a new diagnostic `console.log`/`warn`/`error` call is not "added" until it's been confirmed
to actually appear in a fresh build + logcat capture.** Don't reason about what a log statement
"should" show, or treat its presence in the source as equivalent to its presence in captured
output — build, run, and read the actual logcat before citing a log line as evidence for anything,
including your own new instrumentation.

## Evidence-over-inference discipline

This thread has already survived one context compaction, and the resumption after it stated
specific claims about log contents (`[__yt]` line text) that turned out to be wrong — caught only
because the user had the actual logs and checked directly. A compaction summary is a pointer to
what was investigated, not a citable source for specific technical details.

**Rule: after any compaction, restart, or long gap in a debugging thread, re-verify specific
claims — log contents, exact code, quoted values, line numbers — against the actual files rather
than trusting a summary for anything you'd write down as a fact.** If a claim can't be re-verified
(the log isn't available anymore, the code has since changed), say so explicitly instead of
restating it as settled. This is the same discipline as the live-lookup rule above, just applied to
this repo's own history instead of upstream projects' source.

## Environment-boundary discipline

Claude sessions working on this repo run in a cloud Linux container bridged to the actual Windows
development machine, not on that machine directly.
Things that don't work across that boundary, discovered the hard way:

- `npm install` / `npm run build` run from the cloud side resolve against a Linux toolchain
  (esbuild's platform-specific binary in particular) that doesn't match what the Windows-side
  Gradle build needs — this produced a `@esbuild/win32-x64` vs `@esbuild/linux-x64` failure before.
  **Don't hand-run these from the cloud session.** `app/build.gradle.kts`'s `npmInstall` /
  `buildJsRuntime` Exec tasks already run `npm install` / `npm run build` automatically as part of
  every `gradlew`/Android Studio build, ON the Windows machine, with the right toolchain. Editing
  `js-runtime/package.json` or `src/*.js` and letting the next real build pick it up is the correct
  flow — not trying to pre-build the bundle yourself.
- Relatedly: don't hand-edit `package-lock.json` to match a version bump made in `package.json`.
  `npmInstall`'s Gradle task tracks both files for staleness and runs plain `npm install` (not
  `npm ci`), which resolves and rewrites the lockfile correctly on the next real build. A
  hand-edited lockfile risks a hash/resolution mismatch a real `npm install` wouldn't produce.
- The `gh` CLI and GitHub's REST/GraphQL API are not available in this session (repo access isn't
  granted for API calls) — use `curl`/`WebFetch` against `raw.githubusercontent.com` for reading
  source instead (see the live-lookup section above).
- `device_stage_files` refuses to stage a path more than 7 folders below the connected repo root
  ("too deeply nested") — this hits real files here, e.g.
  `app/src/main/java/com/example/ytclient/youtube/runtime/QuickJsRuntime.kt`. The workaround is
  `device_bash` with an in-place Python (or sed) edit against the mounted path directly, which
  bypasses the staging depth limit. Reach for that instead of restructuring the repo to be shallower.

## Controlled-test-video discipline

A TV_EMBEDDED/WEB_EMBEDDED "This video is unavailable" result turned out to be that one test
video's embeddability restriction, not a client-selection bug — costing time to disentangle from
the actual signal being tested. **Prefer a small, known set of ordinary, non-live, non-age-gated,
non-embed-restricted public videoIds as default test targets for playback/client-selection
debugging**, and name the videoId used in any findings written up in `BACKLOG.md` or similar docs,
so a later comparison is apples-to-apples instead of confounded by which video happened to be
pasted into that particular test run. If a test result looks client-specific, sanity-check it
against a second, different video before treating it as conclusive.

## Emulator-vs-real-device caveat

Most of the client-selection/pot debugging in this repo's history has been done on the Android
emulator (`likelyEmulator=true`, fingerprint `google/sdk_gphone...`); real-device confirmation
(e.g. the GrapheneOS Pixel runs) has been comparatively rare. BotGuard attestation and
low-level network behavior are exactly the kind of thing that can differ between an emulated
device and real hardware. **Treat emulator-only findings on attestation, pot minting, or
network-level behavior (403s, TLS, connection resets) as provisional until confirmed on real
hardware at least once** — don't close out a finding as verified from emulator logs alone when the
underlying mechanism is device-identity-sensitive.
