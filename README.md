# Vidi

A private, account-free YouTube client for Android. No sign-in, no ads, no tracking: your
subscriptions, history and playlists live only on your phone, and the home feed is personalised on
the device from what you watch.

> Independent project — not affiliated with, endorsed by, or sponsored by YouTube or Google. See [Disclaimer](#disclaimer).

## Features

- **Home feed** built on your phone from your subscriptions, watch history and chosen topics, with
  "Because you watched…" and "From your topics" rows
- **Search** with suggestions, and **channel pages**
- **Local subscriptions, history and playlists**, with no account; they never leave the device
- **Background playback** with lockscreen and notification controls, and **picture-in-picture**
- **Autoplay** of the next video, also with the screen locked
- Quality picker, captions, audio-track selection for multi-language videos, and a sleep timer
- Comments
- [SponsorBlock](https://sponsor.ajay.app/) skipping of sponsor segments. It's on by default and
  can be turned off in the player. Lookups are private: Vidi sends only the first 4 characters of a
  hash of the video ID, and SponsorBlock answers for the ~100 videos that share it, so its server
  can't tell which one you're watching.

Vidi talks to YouTube's and Google's servers to search, stream and pass YouTube's bot checks, like
any client does. Apart from the SponsorBlock lookups described above, it sends nothing anywhere else: there are
no analytics or crash-reporting libraries.

## Download

Signed APKs are attached to each [GitHub Release](https://github.com/vidiapp-dev/vidi/releases/latest)
— download `Vidi-<version>.apk` on your phone and open it (Android asks once to allow installs from
your browser or file manager). Each APK has a `.sha256` checksum next to it. Updates install over the
previous version and keep your data. Vidi needs Android 8.0 or newer.

To get updates automatically, add this repository's URL to [Obtainium](https://github.com/ImranR98/Obtainium),
which installs new GitHub releases as they're published.

Every release is signed with the same key. Its SHA-256 certificate fingerprint is:

```
D8:5D:00:65:08:23:99:DA:4A:1B:17:17:74:EC:1E:C4:42:78:39:3E:98:5B:D5:09:72:41:9A:EB:FB:F5:27:D4
```

To check that an APK really comes from this project, compare that fingerprint with the one shown by
[AppVerifier](https://github.com/soupslurpr/AppVerifier) on your phone, or by
`apksigner verify --print-certs Vidi-<version>.apk` on a computer.

Maintainers: see [`RELEASING.md`](RELEASING.md) for how releases are built and signed.

## Status

Vidi is **early software** (0.x): it's usable day to day, but expect rough edges. Known
limitations:

- **Streaming uses DASH by default**, with the quality picked when a video starts. The alternative,
  YouTube's own adaptive SABR transport, is available in Settings but is **experimental** and not
  reliable yet.
- No sign-in, by design, so videos that need an account (age-restricted, members-only) can't play.
- YouTube changes its internals regularly, and playback can break until Vidi catches up. Updates
  ship as new [releases](https://github.com/vidiapp-dev/vidi/releases).

Development notes and the open-issue list live in [`BACKLOG.md`](BACKLOG.md).

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Android UI (Jetpack Compose)                                │
│   ui/{home,library,channel,player,settings,components}      │
├─────────────────────────────────────────────────────────────┤
│ Playback (Media3 / ExoPlayer)                               │
│   playback/  MediaSessionService, lockscreen, PiP actions   │
│   player/    DASH + progressive data sources                │
│   sabr/      SABR transport (experimental, PipePipe port)   │
├─────────────────────────────────────────────────────────────┤
│ YoutubeBridge (Kotlin interface)                            │
│   youtube/bridge/                                           │
├──────────────────────────────┬──────────────────────────────┤
│ Native Innertube client      │ QuickJsRuntime               │
│   youtube/innertube/         │   youtube/runtime/           │
│   search, browse, home feed, │   youtube-runtime.js         │
│   comments (OkHttp)          │   (youtubei.js: /player,     │
│                              │   client selection, ciphers) │
├──────────────────────────────┴──────────────────────────────┤
│ WebPoTokenProvider (hidden WebView)   ◄── PO token minting  │
│   core/webpo/ + assets/po_token.html                        │
└─────────────────────────────────────────────────────────────┘
```

**Why a separate WebView for PO tokens?** BotGuard's attestation explicitly
probes for real browser-runtime features that no JS sandbox can fake. Multiple
independent RE writeups
([PipePipeExtractor#66](https://github.com/InfinityLoop1308/PipePipeExtractor/issues/66))
converged on this conclusion. We follow SmartTube's working pattern:
QuickJS does everything else, a hidden WebView does the one thing it can't.

**Ground rules:**

- The UI layer **never** imports anything from `youtube/runtime` or `youtube/models` except via `YoutubeBridge`.
- Media3 **never** sees PO tokens, SABR session IDs, or BotGuard internals — only opaque byte streams from a data source.
- Playback-protocol changes (clients, ciphers, `/player`) are absorbed as far as possible by upgrading
  `youtubei.js` in `js-runtime/package.json`. Search, browse and the home feed use the faster native
  Innertube client instead, so those need Kotlin changes when YouTube changes its responses.

## Repository layout

```
vidi/
├── app/src/main/
│   ├── java/com/example/ytclient/    ← code namespace (the app ID is io.github.vidiapp_dev.vidi)
│   │   ├── App.kt                    ← owns the bridge, library and PO-token singletons
│   │   ├── MainActivity.kt           ← navigation, picture-in-picture
│   │   ├── core/                     ← local library, settings, SponsorBlock, PO tokens, perf
│   │   ├── playback/                 ← MediaSessionService + controller (background, lockscreen)
│   │   ├── player/                   ← Media3 data sources, format selection
│   │   ├── sabr/                     ← SABR transport (experimental)
│   │   ├── youtube/                  ← protocol layer (off-limits to the UI)
│   │   │   ├── bridge/               ← YoutubeBridge interface + impl
│   │   │   ├── innertube/            ← native Innertube client + parsers, home-feed engine
│   │   │   ├── runtime/              ← QuickJsRuntime (engine + bindings)
│   │   │   └── models/               ← bridge DTOs
│   │   └── ui/                       ← Compose screens
│   ├── java/org/schabi/newpipe/      ← SABR protocol code ported from PipePipeExtractor (GPL-3.0)
│   └── assets/youtube-runtime.js     ← generated from js-runtime/ by the build
├── js-runtime/                       ← JS bundle source (youtubei.js + polyfills, built by esbuild)
├── .github/workflows/release.yml     ← tag-triggered signed release build (see RELEASING.md)
└── BACKLOG.md                        ← open issues, investigations and their history
```

## Building

Prerequisites: a recent Android Studio (its bundled JetBrains JDK 21 is what Gradle runs on), and
Node.js 18+ with npm on the `PATH`.

Open the project and hit **Run**. The Gradle build runs `npm install` and the esbuild bundle step
(`buildJsRuntime` → `app/src/main/assets/youtube-runtime.js`) by itself whenever the JS sources or
dependencies change.

**Measure and test on the `profile` build type** (`./gradlew installProfile`), not `debug`: a
debuggable build runs interpreted and was measured about 18× slower on cold start
(`RUNTIME_PERF_PLAN.md`, E6). `profile` is the release configuration signed with your debug key. Local builds
install as a separate **Vidi Dev** app (`io.github.vidiapp_dev.vidi.dev`), next to any installed release.

To rebuild only the JS bundle from the command line:
```bash
cd js-runtime
npm run build           # one-shot
npm run watch           # rebuild on save
```

## Adding a new bridge method

Say you want `getVideoInfo(videoId)` exposed to the UI.

1. **JS side** — `js-runtime/src/index.js`:
   ```javascript
   const __yt = {
     async search(query) { /* existing */ },
     async getVideoInfo(videoId) {
       const it = await getInnertube();
       const info = await it.getInfo(videoId);
       return { /* normalised shape */ };
     },
   };
   ```

2. **Kotlin model** — add a `data class` under `youtube/models/`.

3. **Bridge interface** — add the method to `YoutubeBridge`:
   ```kotlin
   suspend fun getVideoInfo(videoId: String): VideoInfo
   ```

4. **Bridge impl** — `YoutubeBridgeImpl`:
   ```kotlin
   override suspend fun getVideoInfo(videoId: String): VideoInfo {
     initialize()
     val raw = runtime.callAsync("getVideoInfo", videoId)
     return json.decodeFromString(VideoInfo.serializer(), raw)
   }
   ```

5. **Rebuild the JS bundle** (`npm run build`) then hit Run.

## Logging

All JS-side `console.log/info/warn/error` is bridged to Android logcat with the tag `QuickJsRuntime/js`. Kotlin-side runtime logs use the tag `QuickJsRuntime`. In Android Studio's Logcat tool window, filter by:

```
tag:QuickJsRuntime
```

…to see both. Useful one-liners already in place:

- `[fetch] POST https://www.youtube.com/youtubei/v1/search (2541b body)` — every HTTP request made by the JS runtime.
- `callAsync(search) → String, len=8599, head={"videos":[...` — what came back from each bridge call.

## How playback works (historical, mid-2026)

> This section and the next record how playback was first made to work, and are kept for their
> lessons. The client list has changed since: the current choice and its reasoning are in the
> `CLIENT_PREFERENCE` comment in `js-runtime/src/index.js`, and open issues are in `BACKLOG.md`.

The working pipeline (after a long detour into SABR — see "M2 wall" below for
the dead ends):

1. **PO token** — minted by a hidden WebView running BotGuard against a real
   `youtube.com` page (CSP stripped via `shouldInterceptRequest` so
   `new Function(interpreterJavascript)` is allowed). This is the only piece
   that genuinely needs a real browser runtime.
2. **Client selection** — `getVideoInfo` probes Innertube clients via
   `getBasicInfo(videoId, { client })` and picks one that returns direct
   `https` stream URLs (no SABR). The winning client is cached. As of mid-2026:

   | Client | Direct URLs | Notes |
   |---|---|---|
   | WEB / MWEB | all formats | web-family; URLs need nsig transform (fails in QuickJS) + GVS `&pot=` |
   | IOS | all formats | **preferred** — truly direct: no nsig, no pot, just a matching app UA |
   | ANDROID | ~1 | needs DroidGuard pot we can't mint |

   We prefer **IOS**: its URLs need neither the nsig descrambler (which doesn't
   run reliably in QuickJS) nor a GVS pot — only the matching iOS app
   User-Agent on the download.
3. **Streaming** — Media3/ExoPlayer fetches the URLs through a custom
   `ChunkedDataSource` over OkHttp. Two non-obvious requirements:
   - **App User-Agent**: mobile URLs are UA-bound; a desktop UA → 403.
   - **Bounded, chunked ranges**: googlevideo 403s open-ended or full-file
     ranges; `bytes=0-1048575`-style 1 MB windows → 206. `ChunkedDataSource`
     issues sequential bounded ranges while presenting one continuous stream.

The key debugging lesson: a native HTTP client (OkHttp) returned **206** for a
URL that ExoPlayer's `DefaultHttpDataSource` 403'd — which isolated the problem
to request *shape* (UA + range bounds), not tokens.

`bgutils-js` and `googlevideo` are no longer used — PO tokens come from the
WebView, and SABR is bypassed entirely via the IOS client.

## M2 wall (historical — the SABR dead ends)

Before finding the direct-URL path we spent significant effort trying to make
the WEB client's SABR path work. Recorded here so we don't repeat it.

**Goal of M2:** prove we can fetch SABR-protected video segments end-to-end.

**What we built that works:**
- `WebPoTokenProvider` — hidden WebView running BotGuard, mints real
  per-identifier PO tokens (200+ chars, not the degraded fallback) on
  every call. ~1.5 s cold, <100 ms warm.
- `sabrSessionManager.js` — full SabrStream integration. Format selection,
  session-state, segment plumbing. Protocol-correct.
- Per-video PO token wiring through both `/youtubei/v1/player` and the
  SABR `StreamerContext.poToken`.

**What we couldn't get past:**

YouTube's `*.googlevideo.com` edge servers respond with HTTP 403 to every
request we send, regardless of:
- Correct video-bound PO token ✅
- Matching User-Agent / Origin / Referer headers ✅
- Per-video minting (not session-level) ✅
- Real device on residential IP (not just emulator) ✅

The 403 has an **empty body**, which is googlevideo's classic
"rejected at WAF layer before reading your request in detail" signal.
The differentiator we *can't* reproduce from an OkHttp client:
- Chrome's TLS fingerprint (JA3/JA4)
- HTTP/2 settings frame shape
- Cookies accumulated by a real youtube.com navigation

**What we tried and why none worked:**

| Approach | Result |
|---|---|
| Switch Innertube client to ANDROID_VR (yt-dlp's classic escape) | API succeeds but Google now returns SABR-only formats for ANDROID_VR too — escape hatch closing as of mid-2026 |
| Route SABR fetch through the WebView's Chromium network stack | CORS preflight to googlevideo gets no `Access-Control-Allow-Origin` (no credentials → no CORS) |
| Visit real `youtube.com` first to populate cookies, then mint + SABR | YouTube's Trusted Types CSP blocks `new Function(interpreterJavascript)()`. Tried `youtube`, `default`, random policy names — all rejected |
| Add `credentials: 'include'` | Same CORS failure without cookies in the jar to send |
| Strip CSP via `shouldInterceptRequest` + real youtube.com page + real cookies + per-video PO token + credentials | googlevideo's edge still returns no `Access-Control-Allow-Origin` on preflight. Real YouTube must use MSE / Service Worker proxy to bypass CORS — both require deep WebView surgery |

**Why this is hard:**
The two requirements are mutually exclusive in our current architecture:
- BotGuard needs a CSP-free page to run `new Function(...)` →
  `loadDataWithBaseURL` (no real cookies)
- SABR needs a credentialed cross-origin fetch →
  real navigation to youtube.com (CSP-restricted)

The actual fix requires either bypassing youtube.com's CSP (via
`shouldInterceptRequest` stripping the CSP header from a Kotlin-side
fetched response), or running BotGuard in a way the CSP allows (an
embedded `<script>` from a trusted source). Both are non-trivial and
still uncertain to pass the SABR WAF at the end.

**Where we're parked:** M3 (Media3 playback) will run against the one
direct-URL format the WEB client still returns — itag 18 (360p
progressive mp4). All the SABR machinery stays in the repo for the day
a workaround emerges. See PipePipeExtractor [#66](https://github.com/InfinityLoop1308/PipePipeExtractor/issues/66)
where independent researchers reached the same conclusion in mid-2026:
> "I don't think it's possible to fully bypass a browser runtime since
> that's what BotGuard checks for. SmartTube's WebView approach is good
> enough imo." — InfinityLoop1308

## Support

Vidi is free and always will be. If it's useful to you and you'd like to say thanks, you can leave
a tip on Ko-fi:

[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/B4H52941LA)

Tips are voluntary gifts: they don't buy features, support or priority, and Vidi works exactly the
same either way. Bug reports and pull requests are just as welcome.

## License

Vidi is free software: you can redistribute it and/or modify it under the terms of the
**GNU General Public License, version 3** — see [`LICENSE`](LICENSE). It is distributed in the hope
that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.

GPL-3.0 isn't a stylistic choice here: the native SABR streaming code is ported from
[PipePipeExtractor](https://github.com/Priveetee/PipePipeExtractor) (a fork of NewPipeExtractor),
which is GPL-3.0, and that obliges the project as a whole to be GPL-3.0 too.

Third-party components — including [youtubei.js](https://github.com/LuanRT/YouTube.js),
[quickjs-kt](https://github.com/dokar3/quickjs-kt) and the QuickJS engine, and the SABR protocol
definitions from [googlevideo](https://github.com/LuanRT/googlevideo) — are listed with their
licences and full licence texts in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md). Both files
are also packaged into the app itself, under `assets/`. [esbuild](https://esbuild.github.io/) is
used to build the JS bundle but is not shipped.

## Disclaimer

Vidi is an independent project. It is **not affiliated with, endorsed by, or sponsored by YouTube
or Google LLC**. "YouTube" is a trademark of Google LLC and is used here only to describe what the
app connects to. Vidi accesses YouTube without using its official API; using it may be contrary to
YouTube's Terms of Service, and you are responsible for how you use it.
