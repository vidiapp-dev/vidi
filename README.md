# Vidi (yt-android)

A modern Android client for YouTube whose YouTube protocol logic lives in an embedded JavaScript runtime. The Android side only knows about a stable bridge interface — Innertube, PO tokens, SABR, and BotGuard never leak into the UI or playback layers.

> Independent project — not affiliated with, endorsed by, or sponsored by YouTube or Google. See [Disclaimer](#disclaimer).

## Download

Signed APKs are attached to each [GitHub Release](https://github.com/vidiapp-dev/vidi/releases/latest)
— download `Vidi-<version>.apk` on your phone and open it (Android asks once to allow installs from
your browser or file manager). Each APK has a `.sha256` checksum next to it. Updates install over the
previous version and keep your data. Vidi needs Android 8.0 or newer.

Every release is signed with the same key. Its SHA-256 certificate fingerprint is:

```
D8:5D:00:65:08:23:99:DA:4A:1B:17:17:74:EC:1E:C4:42:78:39:3E:98:5B:D5:09:72:41:9A:EB:FB:F5:27:D4
```

To check that an APK really comes from this project, compare that fingerprint with the one shown by
[AppVerifier](https://github.com/soupslurpr/AppVerifier) on your phone, or by
`apksigner verify --print-certs Vidi-<version>.apk` on a computer.

Maintainers: see [`RELEASING.md`](RELEASING.md) for how releases are built and signed.

## Status

| Milestone | Scope | State |
|---|---|---|
| **M1** | Runtime PoC — QuickJS embedded, youtubei.js loads, search works | ✅ complete |
| **M2** | Playback PoC — PO token + direct stream URLs | ✅ complete — see [How playback works](#how-playback-works) |
| **M3** | Media3 playback — adaptive video+audio on screen | ✅ complete |
| **M4** | Production architecture — caching, failover, retries, logging | 🟡 retry/failover + client persistence done |
| **M5** | Full app — home, search, player, settings | 🟡 bottom-nav shell · home feed · related videos · quality picker (subscriptions need login — deferred) |

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Android UI (Jetpack Compose)                                │
│   ui/{home,search,subscriptions,player,theme}               │
├─────────────────────────────────────────────────────────────┤
│ Playback (Media3 — future)                                  │
│   player/{media3,datasource}                                │
├─────────────────────────────────────────────────────────────┤
│ YoutubeBridge (Kotlin interface)                            │
│   youtube/bridge/                                           │
├─────────────────────────────────────────────────────────────┤
│ QuickJsRuntime (engine + fetch/log/storage/po-token bridges)│
│   youtube/runtime/                                          │
├─────────────────────────────────────────────────────────────┤
│ WebPoTokenProvider (hidden WebView)   ◄── PO token minting  │
│   core/webpo/ + assets/po_token.html                        │
├─────────────────────────────────────────────────────────────┤
│ youtube-runtime.js (bundled asset)                          │
│   js-runtime/ → npm run build                               │
│   • polyfills (EventTarget, fetch, URL, Intl, ...)          │
│   • Platform.load(...) injects our impls into youtubei.js   │
│   • youtubei.js Innertube session                           │
│   • googlevideo SabrStream                                  │
│   • poTokenManager.js → bridges to WebPoTokenProvider       │
└─────────────────────────────────────────────────────────────┘
```

**Why a separate WebView for PO tokens?** BotGuard's attestation explicitly
probes for real browser-runtime features that no JS sandbox can fake. Multiple
independent RE writeups
([PipePipeExtractor#66](https://github.com/InfinityLoop1308/PipePipeExtractor/issues/66))
converged on this conclusion. We follow SmartTube's working pattern:
QuickJS does everything else, a hidden WebView does the one thing it can't.

**Hard rules** (per the architecture plan):

- The UI layer **never** imports anything from `youtube/runtime` or `youtube/models` except via `YoutubeBridge`.
- Media3 **never** sees PO tokens, SABR session IDs, or BotGuard internals — only opaque byte streams from a `YoutubeDataSource`.
- All YouTube protocol changes are absorbed by upgrading `youtubei.js` / `bgutils-js` / `googlevideo` in `js-runtime/package.json`. Android code should not need to change.

## Repository layout

```
yt-android/
├── app/
│   └── src/main/
│       ├── java/com/example/ytclient/
│       │   ├── App.kt                ← owns the single YoutubeBridge instance
│       │   ├── MainActivity.kt       ← navigation only
│       │   ├── core/                 ← cross-cutting (models, network, db)
│       │   │   ├── models/
│       │   │   ├── network/
│       │   │   └── database/
│       │   ├── player/               ← Media3 integration (M3+)
│       │   │   ├── media3/
│       │   │   └── datasource/
│       │   ├── youtube/              ← Protocol layer (off-limits to UI)
│       │   │   ├── bridge/           ← YoutubeBridge interface + impl
│       │   │   ├── runtime/          ← QuickJsRuntime (engine + bindings)
│       │   │   └── models/           ← Bridge DTOs
│       │   └── ui/                   ← Compose screens
│       │       ├── home/
│       │       ├── search/
│       │       ├── subscriptions/
│       │       ├── player/
│       │       └── theme/
│       ├── assets/
│       │   └── youtube-runtime.js    ← generated by `npm run build`
│       └── AndroidManifest.xml
├── js-runtime/                       ← JS bundle source
│   ├── package.json
│   ├── esbuild.config.mjs
│   └── src/
│       ├── index.js                  ← Platform.load + __yt surface
│       ├── polyfills.js              ← browser-API polyfills
│       ├── sha1.js                   ← SHA-1 for Platform shim
│       └── shims/empty.js            ← stub for node:* imports
├── build.gradle.kts                  ← root plugin versions
└── README.md                         ← this file
```

## Building

Prerequisites: Android Studio (Hedgehog or newer), JDK 17, Node.js 18+, npm.

```bash
# One-time: install JS deps
cd js-runtime
npm install
cd ..
```

After that, just hit **Run** in Android Studio — the Gradle build will:
1. Run `buildJsRuntime` (esbuild → `app/src/main/assets/youtube-runtime.js`) if any JS source changed.
2. Run `mergeDebugAssets` to pick up the fresh bundle.
3. Compile + install the APK.

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

## How playback works

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

## Known limitations (M1)

- No PO token → playback URLs that require one will fail. (Covered by M2.)
- No session persistence — every app launch creates a fresh Innertube session. (M2 will add DataStore-backed token cache.)
- `bridge.search` is the only method exposed.
- `PlayerScreen` is a placeholder.

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
